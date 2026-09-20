package com.stansful.sshvpnclient.vpn

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Network
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jcraft.jsch.Session
import com.stansful.sshvpnclient.R
import com.stansful.sshvpnclient.SshVpnApplication
import com.stansful.sshvpnclient.domain.model.AppSettings
import com.stansful.sshvpnclient.domain.model.AuthType
import com.stansful.sshvpnclient.domain.model.SshConfig
import com.stansful.sshvpnclient.domain.model.SshPrivateKey
import com.stansful.sshvpnclient.domain.model.VpnMode
import com.stansful.sshvpnclient.domain.model.VpnSessionOwner
import com.stansful.sshvpnclient.domain.model.VpnTransportType
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class SshVpnService : android.net.VpnService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()
    private val vpnTunnelOwner = Any()
    @Volatile
    private var connectionJob: Job? = null
    private val connectionRunId = AtomicLong(0L)
    private val lifecycleCommandId = AtomicLong(0L)
    @Volatile
    private var lastStartId: Int = 0

    /**
     * Everything that asks the connection loop to look at its transport. The loop is the only
     * consumer and the only place that starts SSH attempts; producers never block. Overflow drops
     * the oldest hint - the monitor re-checks the transport itself on every wake-up anyway.
     */
    private val runtimeEvents = Channel<PostedTrigger>(
        capacity = RUNTIME_EVENT_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val triggerLog = TriggerLogThrottle()
    @Volatile
    private var userRequestedDisconnect: Boolean = true
    @Volatile
    private var serviceDestroyed = false
    @Volatile
    private var deviceInteractive = true
    @Volatile
    private var transportNetwork: Network? = null
    @Volatile
    private var runtimeLease: VpnRuntimeLease? = null
    private var screenOffAtMs: Long = NO_SCREEN_OFF_TIMESTAMP
    private var screenReceiverRegistered = false
    private var isLowRamDevice = false
    private val trafficActivityMonitor = VpnTrafficActivityMonitor()
    private lateinit var powerManager: PowerManager
    private lateinit var underlyingNetworkMonitor: UnderlyingNetworkMonitor
    private lateinit var protectedSocketRoute: UnderlyingNetworkSocketProtector
    private lateinit var reconnectWakeHelper: ReconnectWakeHelper
    private val reconnectHandoffHold = ReconnectWakeHelper.Hold()

    // Every wake-up is worth one keepalive now, so any screen-off duration counts.
    private val wakeRecoveryPolicy = WakeRecoveryPolicy(minimumScreenOffDurationMs = 0L)
    private val monitorCadencePolicy = ConnectionMonitorCadencePolicy(
        interactiveIntervalMs = INTERACTIVE_CONNECTION_MONITOR_INTERVAL_MS,
        screenOffIntervalMs = SCREEN_OFF_CONNECTION_MONITOR_INTERVAL_MS,
    )
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    deviceInteractive = false
                    screenOffAtMs = SystemClock.elapsedRealtime()
                    appContainer.sshConnectionManager.setDeviceInteractive(isInteractive = false)
                    trafficActivityMonitor.resetBaseline()
                }

                Intent.ACTION_SCREEN_ON -> {
                    deviceInteractive = true
                    appContainer.sshConnectionManager.setDeviceInteractive(isInteractive = true)
                    val screenOffDurationMs = wakeRecoveryPolicy.recoveryDurationMs(
                        screenOffAtMs = screenOffAtMs,
                        screenOnAtMs = SystemClock.elapsedRealtime(),
                    )
                    screenOffAtMs = NO_SCREEN_OFF_TIMESTAMP
                    // A NAT that expired while the phone slept is found by one keepalive now,
                    // not by the first app that times out.
                    postTrigger(ReconnectTrigger.DeviceWake(screenOffDurationMs))
                }
            }
        }
    }

    private val appContainer
        get() = (application as SshVpnApplication).container

    override fun onCreate() {
        super.onCreate()
        underlyingNetworkMonitor = UnderlyingNetworkMonitor(
            context = this,
            onNetworkChanged = ::onUnderlyingNetworkChanged,
            onNetworkEvent = ::onUnderlyingNetworkEvent,
        )
        reconnectWakeHelper = ReconnectWakeHelper(
            context = this,
            onRetryAlarm = { postTrigger(ReconnectTrigger.RetryAlarm) },
        )
        protectedSocketRoute = UnderlyingNetworkSocketProtector(
            protectSocket = ::protect,
            networkProvider = underlyingNetworkMonitor::requireUsableNetwork,
        )
        powerManager = getSystemService(POWER_SERVICE) as PowerManager
        val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        isLowRamDevice = activityManager.isLowRamDevice
        deviceInteractive = powerManager.isInteractive
        appContainer.sshConnectionManager.setDeviceInteractive(deviceInteractive)
        if (!deviceInteractive) {
            screenOffAtMs = SystemClock.elapsedRealtime()
        }
        ContextCompat.registerReceiver(
            this,
            screenStateReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        screenReceiverRegistered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_CONNECT -> {
                // startForegroundService() gives us only a short deadline; do this before any I/O.
                startVpnForeground()
                if (!shouldAcceptVpnConnectCommand(
                        state = appContainer.vpnConnectionRepository.currentState,
                        owner = VpnSessionOwner.SHADOW_SSH,
                        transport = VpnTransportType.SSH,
                    )
                ) {
                    rejectStaleConnectCommand(startId)
                    return START_NOT_STICKY
                }
                val lease = appContainer.vpnRuntimeLeaseRegistry.claim(
                    owner = vpnTunnelOwner,
                    sessionOwner = VpnSessionOwner.SHADOW_SSH,
                )
                if (lease == null) {
                    rejectBusyRuntimeConnectCommand(startId)
                    return START_NOT_STICKY
                }
                underlyingNetworkMonitor.start()
                connect(
                    preserveDiagnostics = intent.getBooleanExtra(EXTRA_PRESERVE_DIAGNOSTICS, false),
                    startId = startId,
                    lease = lease,
                )
            }
            ACTION_DISCONNECT -> disconnect(startId)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    override fun onDestroy() {
        serviceDestroyed = true
        appContainer.vpnRuntimeLeaseRegistry.invalidate(vpnTunnelOwner)
        transportNetwork = null
        lifecycleCommandId.incrementAndGet()
        userRequestedDisconnect = true
        val destroyRunId = connectionRunId.incrementAndGet()
        connectionJob?.cancel()
        if (::underlyingNetworkMonitor.isInitialized) {
            underlyingNetworkMonitor.close()
        }
        if (::reconnectWakeHelper.isInitialized) {
            reconnectWakeHelper.close()
        }
        if (screenReceiverRegistered) {
            runCatching { unregisterReceiver(screenStateReceiver) }
            screenReceiverRegistered = false
        }
        val repository = appContainer.vpnConnectionRepository
        if (isVpnSessionOwnedBy(
                state = repository.currentState,
                owner = VpnSessionOwner.SHADOW_SSH,
                transport = VpnTransportType.SSH,
            )
        ) {
            repository.setDisconnected()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        serviceScope.launch {
            try {
                disconnectInternal(
                    runId = destroyRunId,
                    awaitTunTermination = false,
                )
            } finally {
                serviceScope.cancel()
            }
        }
        super.onDestroy()
    }

    override fun onRevoke() {
        userRequestedDisconnect = true
        connectionJob?.cancel()
        appContainer.vpnConnectionRepository.appendDiagnostic("VPN permission revoked by Android")
        disconnect(lastStartId)
        super.onRevoke()
    }

    private fun connect(
        preserveDiagnostics: Boolean,
        startId: Int,
        lease: VpnRuntimeLease,
    ) {
        val runId = connectionRunId.incrementAndGet()
        runtimeLease = lease
        val commandId = lifecycleCommandId.incrementAndGet()
        serviceScope.launch {
            lifecycleMutex.withLock {
                if (serviceDestroyed || lifecycleCommandId.get() != commandId) return@withLock
                userRequestedDisconnect = false
                val previousConnectionJob = connectionJob
                previousConnectionJob?.cancel()
                // JSch connect() is blocking and does not observe coroutine cancellation itself.
                appContainer.sshConnectionManager.disconnectOwner(vpnTunnelOwner)
                previousConnectionJob?.let { job ->
                    withTimeoutOrNull(CONNECTION_JOB_JOIN_GRACE_MS) { job.join() }
                }
                if (
                    connectionRunId.get() != runId ||
                    lifecycleCommandId.get() != commandId ||
                    userRequestedDisconnect
                ) {
                    return@withLock
                }
                // A cancelled run has finished before shared managers are touched by the new run.
                disconnectInternal(
                    runId = runId,
                )
                if (
                    serviceDestroyed ||
                    connectionRunId.get() != runId ||
                    lifecycleCommandId.get() != commandId ||
                    userRequestedDisconnect
                ) {
                    return@withLock
                }
                connectionJob = serviceScope.launch {
                    runConnectionLoop(
                        runId = runId,
                        commandId = commandId,
                        startId = startId,
                        lease = lease,
                        preserveDiagnostics = preserveDiagnostics,
                    )
                }
            }
        }
    }

    private fun postTrigger(trigger: ReconnectTrigger) {
        runtimeEvents.trySend(PostedTrigger(trigger, SystemClock.elapsedRealtime()))
    }

    /** The link a forwarder or network report is about: it may reach the loop after that link is gone. */
    private fun currentLinkId(): Long = appContainer.sshConnectionManager.activeLinkId(vpnTunnelOwner) ?: 0L

    /** Runs on the ConnectivityManager callback thread: only reads state and posts. */
    private fun onUnderlyingNetworkEvent(event: UnderlyingNetworkEvent) {
        if (!shouldKeepConnectionAlive(connectionRunId.get())) return
        val currentTransportNetwork = transportNetwork
        if (currentTransportNetwork != null && event.network != currentTransportNetwork) return
        when (event) {
            is UnderlyingNetworkEvent.AddressesChanged -> {
                val endpoint = appContainer.sshConnectionManager.transportEndpoint(vpnTunnelOwner)
                postTrigger(
                    ReconnectTrigger.NetworkRefresh(
                        detail = "addresses changed (+${event.added.size}/-${event.removed.size})",
                        addressLostByLink = endpoint
                            ?.takeIf { it.localAddress != null && event.removed.contains(it.localAddress) }
                            ?.linkId,
                    ),
                )
            }
            is UnderlyingNetworkEvent.ValidationChanged -> postTrigger(
                ReconnectTrigger.NetworkRefresh(
                    detail = if (event.validated) "network validated" else "network lost validation",
                    degraded = !event.validated,
                ),
            )
            is UnderlyingNetworkEvent.BlockedStatusChanged -> {
                appContainer.vpnConnectionRepository.appendDiagnostic(
                    if (event.blocked) {
                        "Android is blocking this app's traffic on the underlying network (Doze, Data Saver " +
                            "or background restrictions); SSH replies cannot arrive until it lifts"
                    } else {
                        "Android stopped blocking this app's traffic on the underlying network"
                    },
                )
                if (!event.blocked) {
                    postTrigger(ReconnectTrigger.NetworkRefresh("network unblocked"))
                }
            }
        }
    }

    private fun onUnderlyingNetworkChanged(old: Network?, new: Network?) {
        val runId = connectionRunId.get()
        serviceScope.launch {
            // Ignore a callback superseded by a newer capabilities update.
            if (underlyingNetworkMonitor.currentNetwork() != new) return@launch
            if (!shouldKeepConnectionAlive(runId)) return@launch
            val expectedSession = appContainer.sshConnectionManager.transportSessionSnapshot(vpnTunnelOwner)
            appContainer.vpnTunnelManager.updateUnderlyingNetwork(
                owner = vpnTunnelOwner,
                service = this@SshVpnService,
                network = new,
            )
            if (underlyingNetworkMonitor.currentNetwork() != new || !shouldKeepConnectionAlive(runId)) {
                return@launch
            }
            if (!shouldRestartForNetworkChange(transportNetwork, new)) {
                // No transport to replace: a loop waiting out its backoff may retry right away.
                if (transportNetwork == null && new != null) {
                    postTrigger(ReconnectTrigger.NetworkHandoff("$new is available"))
                }
                return@launch
            }
            transportNetwork = new
            val transition = "${old ?: "none"} -> ${new ?: "none"}"
            appContainer.vpnConnectionRepository.appendDiagnostic(
                "Underlying network changed ($transition); reconnecting SSH transport",
            )
            // Existing TCP sockets cannot migrate to another Network. The connection loop keeps
            // the TUN pipeline and creates the replacement SSH socket on the selected network.
            // Reset client flows before JSch disconnects its channels; otherwise JSch reports the
            // transport failure as a misleading channel EOF and the browser can keep an orphan.
            expectedSession?.let { session ->
                appContainer.sshConnectionManager.disconnectIfCurrent(session) {
                    appContainer.tun2SocksManager.pauseSshTransport(vpnTunnelOwner)
                }
            }
            postTrigger(ReconnectTrigger.NetworkHandoff(transition))
        }
    }

    private suspend fun runConnectionLoop(
        runId: Long,
        commandId: Long,
        startId: Int,
        lease: VpnRuntimeLease,
        preserveDiagnostics: Boolean,
    ) {
        val configRepository = appContainer.sshConfigRepository
        val keyRepository = appContainer.sshPrivateKeyRepository
        val connectionRepository = appContainer.vpnConnectionRepository

        val config = try {
            configRepository.getSelectedConfig()
        } catch (error: Exception) {
            failConnectionAndStop(
                runId = runId,
                commandId = commandId,
                startId = startId,
                configId = null,
                message = error.message ?: "Could not load VPN configuration",
            )
            return
        }
        if (!isActiveConnectionCommandCurrent(runId, commandId)) return
        if (config == null) {
            failConnectionAndStop(
                runId = runId,
                commandId = commandId,
                startId = startId,
                configId = null,
                message = "No configuration selected",
            )
            return
        }

        try {
            val publishedStart = mutateActiveConnectionIfCurrent(runId, commandId) {
                if (preserveDiagnostics) {
                    connectionRepository.setReconnecting(config.id)
                    connectionRepository.appendDiagnostic("Applying updated VPN settings")
                } else {
                    connectionRepository.setConnecting(config.id)
                }
                connectionRepository.appendDiagnostic("Starting VPN connection")
                connectionRepository.appendDiagnostic(
                    "Selected config: ${config.username}@${config.host}:${config.port}",
                )
                connectionRepository.appendDiagnostic("Auth type: ${config.authType.label}")
                connectionRepository.appendDiagnostic("Foreground VPN service is active")
            }
            if (!publishedStart) return
            val appSettings = appContainer.appSettingsRepository.settings.value
            validateAppSettings(appSettings)
            val privateKey = loadPrivateKey(config, runId, keyRepository::getById)
            ensureConnectionStillWanted(runId, commandId)

            var attempt = 1
            var everConnected = false
            val supervisor = ReconnectSupervisor(
                initialBackoffMs = INITIAL_RECONNECT_DELAY_MS,
                maxBackoffMs = MAX_RECONNECT_DELAY_MS,
                stableConnectionMs = STABLE_CONNECTION_BACKOFF_RESET_MS,
            )
            var reconnectStartedAtMs: Long? = null
            var forceVpnRebuildOnNextAttempt = false
            drainStaleRuntimeEvents()
            while (shouldKeepConnectionAlive(runId)) {
                if (attempt > 1) {
                    val publishedReconnect = mutateActiveConnectionIfCurrent(runId, commandId) {
                        connectionRepository.setReconnecting(config.id)
                        connectionRepository.appendDiagnostic("Reconnect attempt $attempt starting")
                    }
                    if (!publishedReconnect) break
                }

                var activeConnectionInterrupted = false
                var reconnectImmediately = false
                try {
                    val reuseVpnInterface = everConnected && canReuseVpnPipeline()
                    supervisor.onAttemptStarted(SystemClock.elapsedRealtime())
                    val attemptWakeHold = ReconnectWakeHelper.Hold()
                    var attemptFailed = true
                    val connection = try {
                        connectSingleAttempt(
                            config = config,
                            privateKey = privateKey,
                            appSettings = appSettings,
                            runId = runId,
                            commandId = commandId,
                            lease = lease,
                            reuseVpnInterface = reuseVpnInterface && !forceVpnRebuildOnNextAttempt,
                            includeNetworkDiagnostics = attempt == 1 ||
                                attempt % NETWORK_DIAGNOSTICS_RETRY_INTERVAL == 0,
                            wakeHold = attemptWakeHold,
                        ).also { attemptFailed = false }
                    } finally {
                        // Overlap the locks: the backoff guard is armed only after a few thread hops.
                        if (attemptFailed && shouldKeepConnectionAlive(runId)) holdCpuForReconnectHandoff()
                        reconnectWakeHelper.release(attemptWakeHold)
                        supervisor.onAttemptEnded(SystemClock.elapsedRealtime())
                    }
                    everConnected = true
                    forceVpnRebuildOnNextAttempt = false
                    reconnectStartedAtMs?.let { startedAt ->
                        val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                        val restorePath = if (connection.reusedVpnInterface) {
                            "without rebuilding Android VPN interface"
                        } else {
                            "after rebuilding Android VPN interface"
                        }
                        connectionRepository.appendDiagnostic("VPN forwarding restored in ${elapsedMs}ms $restorePath")
                    }
                    reconnectStartedAtMs = null

                    supervisor.onConnected(connection.linkId, SystemClock.elapsedRealtime())
                    val interruptReason = monitorActiveConnection(connection, runId, supervisor)
                    if (!shouldKeepConnectionAlive(runId)) {
                        break
                    }
                    holdCpuForReconnectHandoff()
                    activeConnectionInterrupted = true
                    val lossPlan = supervisor.onConnectionLost(
                        nowMs = SystemClock.elapsedRealtime(),
                        interruptForcesRebuild = interruptReason.forceVpnRebuild,
                    )
                    reconnectImmediately = lossPlan.immediate
                    forceVpnRebuildOnNextAttempt = lossPlan.forceVpnRebuild
                    reconnectStartedAtMs = SystemClock.elapsedRealtime()
                    if (!mutateActiveConnectionIfCurrent(runId, commandId) {
                            connectionRepository.setReconnecting(config.id)
                            connectionRepository.appendDiagnostic(
                                "Connection interrupted: ${interruptReason.message}",
                            )
                            lossPlan.note?.let { note ->
                                connectionRepository.appendDiagnostic("SSH transport is flapping: $note")
                            }
                        }
                    ) {
                        break
                    }
                } catch (error: CancellationException) {
                    break
                } catch (error: VpnConnectionException) {
                    if (!shouldKeepConnectionAlive(runId)) {
                        break
                    }
                    if (error.isRecoverableBeforeFirstConnection() || everConnected) {
                        if (!mutateActiveConnectionIfCurrent(runId, commandId) {
                                connectionRepository.setReconnecting(config.id)
                            }
                        ) {
                            break
                        }
                    }
                    logConnectionAttemptFailure(
                        attempt = attempt,
                        errorMessage = error.message ?: "Unknown connection error",
                        causeMessage = error.cause?.message,
                    )
                    if (!everConnected && !error.isRecoverableBeforeFirstConnection()) {
                        failConnectionAndStop(
                            runId = runId,
                            commandId = commandId,
                            startId = startId,
                            configId = config.id,
                            message = error.message ?: "Unknown connection error",
                        )
                        return
                    }
                } catch (error: Exception) {
                    if (!shouldKeepConnectionAlive(runId)) {
                        break
                    }
                    logConnectionAttemptFailure(
                        attempt = attempt,
                        errorMessage = "Unknown connection error",
                        causeMessage = "${error::class.java.simpleName}: ${error.message}",
                    )
                }

                prepareForReconnect(
                    runId = runId,
                    commandId = commandId,
                    keepVpnPipeline = everConnected && !forceVpnRebuildOnNextAttempt,
                    announceHotReconnect = activeConnectionInterrupted,
                )
                if (!shouldKeepConnectionAlive(runId)) {
                    break
                }
                if (!mutateActiveConnectionIfCurrent(runId, commandId) {
                        connectionRepository.setReconnecting(config.id)
                    }
                ) {
                    break
                }
                val reconnectDelayMs = supervisor.beginBackoff(
                    nowMs = SystemClock.elapsedRealtime(),
                    immediate = activeConnectionInterrupted && reconnectImmediately,
                    deviceInteractive = deviceInteractive,
                )
                if (reconnectDelayMs == 0L) {
                    connectionRepository.appendDiagnostic("Immediate SSH reconnect starting")
                } else {
                    connectionRepository.appendDiagnostic(
                        "Reconnecting in ${reconnectDelayMs}ms; press Disconnect to stop",
                    )
                    waitBeforeReconnect(reconnectDelayMs, runId, supervisor)
                }
                attempt += 1
            }
        } catch (error: CancellationException) {
            // The serialized lifecycle transition owns cleanup after the cancelled run joins.
        } catch (error: VpnConnectionException) {
            val message = error.message ?: "VPN connection failed"
            failConnectionAndStop(
                runId = runId,
                commandId = commandId,
                startId = startId,
                configId = config.id,
                message = message,
                diagnostic = "Connection failed: $message",
            )
        } catch (error: Exception) {
            val detail = error.message ?: error::class.java.simpleName
            failConnectionAndStop(
                runId = runId,
                commandId = commandId,
                startId = startId,
                configId = config.id,
                message = "Unknown connection error",
                diagnostic = "Connection failed: $detail",
            )
        } finally {
            if (connectionRunId.get() == runId) {
                connectionJob = null
            }
        }
    }

    private suspend fun loadPrivateKey(
        config: SshConfig,
        runId: Long,
        getKeyById: suspend (String) -> SshPrivateKey?,
    ): SshPrivateKey? {
        if (config.authType != AuthType.PRIVATE_KEY) return null

        val keyId = config.privateKeyId
        if (keyId.isNullOrBlank()) {
            throw VpnConnectionException("Selected SSH key not found")
        }
        appendConnectionDiagnostic(runId, "Looking up selected SSH key")
        val privateKey = getKeyById(keyId) ?: throw VpnConnectionException("Selected SSH key not found")
        appendConnectionDiagnostic(runId, "Selected SSH key: ${privateKey.name}")
        return privateKey
    }

    private suspend fun connectSingleAttempt(
        config: SshConfig,
        privateKey: SshPrivateKey?,
        appSettings: AppSettings,
        runId: Long,
        commandId: Long,
        lease: VpnRuntimeLease,
        reuseVpnInterface: Boolean,
        includeNetworkDiagnostics: Boolean,
        wakeHold: ReconnectWakeHelper.Hold,
    ): ConnectionAttempt {
        val connectionRepository = appContainer.vpnConnectionRepository
        ensureConnectionStillWanted(runId, commandId)
        if (!lease.isCurrent()) {
            throw CancellationException("VPN runtime lease was superseded")
        }
        validateAppSettings(appSettings)
        if (underlyingNetworkMonitor.currentNetwork() == null) {
            appendConnectionDiagnostic(runId, "Waiting for a usable physical network")
        }
        val selectedNetwork = underlyingNetworkMonitor.awaitUsableNetwork()
        ensureConnectionStillWanted(runId, commandId)
        transportNetwork = selectedNetwork
        appendConnectionDiagnostic(runId, "Selected underlying network: $selectedNetwork")
        if (includeNetworkDiagnostics) {
            NetworkDiagnostics.describe(this@SshVpnService).forEach { message ->
                appendConnectionDiagnostic(runId, message)
            }
        }
        val connectTimeoutMs = if (reuseVpnInterface) RECONNECT_CONNECT_TIMEOUT_MS else INITIAL_CONNECT_TIMEOUT_MS
        if (!deviceInteractive) {
            // Screen off: nothing else keeps the CPU up between the SYN and the last handshake
            // packet. Bounded by the attempt deadline and released by the caller; never held while
            // waiting for a network above.
            val wakeLockMs = totalSshConnectDeadlineMs(connectTimeoutMs) + ATTEMPT_WAKE_LOCK_MARGIN_MS
            reconnectWakeHelper.acquire(wakeHold, wakeLockMs)
            reconnectWakeHelper.release(reconnectHandoffHold)
            appendConnectionDiagnostic(
                runId,
                "Screen is off: wake lock held for this attempt, ${wakeLockMs / 1_000}s max",
            )
        }
        val log = connectionLogger(runId)
        val sshSession = appContainer.sshConnectionManager.connect(
            owner = vpnTunnelOwner,
            lease = lease,
            config = config,
            privateKey = privateKey,
            log = log,
            socketProtector = protectedSocketRoute,
            connectTimeoutMs = connectTimeoutMs,
            verboseDiagnostics = includeNetworkDiagnostics,
            onTransportDead = { linkId, reason ->
                // On the watchdog's timer thread: the loop that acts on this must get to run.
                holdCpuForReconnectHandoff()
                postTrigger(ReconnectTrigger.TransportDead(linkId, reason))
            },
        )
        ensureConnectionStillWanted(runId, commandId)
        if (underlyingNetworkMonitor.currentNetwork() != selectedNetwork) {
            appContainer.sshConnectionManager.disconnectIfCurrent(sshSession)
            throw VpnConnectionException("Underlying network changed during SSH connection")
        }
        val reusedVpnInterface = reuseVpnInterface && canReuseVpnPipeline()
        if (reusedVpnInterface) {
            connectionRepository.appendDiagnostic("Resuming forwarding on existing Android VPN interface")
            if (!resumeSshTransportIfCurrent(runId, commandId, sshSession)) {
                throw CancellationException("Connection run was superseded during SSH resume")
            }
        } else {
            if (reuseVpnInterface) {
                connectionRepository.appendDiagnostic(
                    "Existing VPN pipeline is unavailable; rebuilding Android VPN interface",
                )
            }
            if (!cleanupVpnPipelineForRebuildIfCurrent(runId, commandId)) {
                throw CancellationException("Connection run was superseded during VPN rebuild")
            }
            connectionRepository.appendDiagnostic("Establishing Android VPN interface")
            val vpnInterface = appContainer.vpnTunnelManager.establish(
                owner = vpnTunnelOwner,
                lease = lease,
                service = this@SshVpnService,
                config = config,
                appSettings = appSettings,
                underlyingNetwork = selectedNetwork,
                log = connectionRepository::appendDiagnostic,
            )
            ensureConnectionStillWanted(runId, commandId)
            connectionRepository.appendDiagnostic("Starting local TUN forwarding layer")
            val resourceProfile = selectTunResourceProfile(
                isLowRamDevice = isLowRamDevice,
                isPowerSaveMode = powerManager.isPowerSaveMode,
            )
            appContainer.tun2SocksManager.start(
                owner = vpnTunnelOwner,
                lease = lease,
                vpnInterface = vpnInterface,
                sshSession = sshSession,
                enableUdpForwarding = config.enableUdpForwarding,
                maxActiveTcpSessions = resourceProfile.maxActiveTcpSessions,
                sshChannelWindowBytes = resourceProfile.sshChannelWindowBytes,
                maxPendingUploadBytesPerFlow = resourceProfile.maxPendingUploadBytesPerFlow,
                tunWriteQueueCapacity = resourceProfile.tunWriteQueueCapacity,
                outboundPacketPoolCapacity = resourceProfile.outboundPacketPoolCapacity,
                onTransportSuspect = { postTrigger(ReconnectTrigger.TransportDemand) },
                onTransportStall = { active -> postTrigger(ReconnectTrigger.DataPathStall(active, currentLinkId())) },
                onNewFlow = ::probeQuietTransportForNewFlow,
                log = connectionRepository::appendDiagnostic,
            )
        }
        ensureConnectionStillWanted(runId, commandId)
        if (!sshSession.isConnected) {
            throw VpnConnectionException("SSH transport interrupted during VPN startup")
        }
        if (!mutateActiveConnectionIfCurrent(runId, commandId) {
                connectionRepository.appendDiagnostic("VPN connection is connected")
                connectionRepository.setConnected(config.id)
            }
        ) {
            throw CancellationException("Connection run was superseded before publication")
        }
        return ConnectionAttempt(
            sshSession = sshSession,
            reusedVpnInterface = reusedVpnInterface,
            linkId = appContainer.sshConnectionManager.activeLinkId(vpnTunnelOwner) ?: 0L,
        )
    }

    private suspend fun monitorActiveConnection(
        connection: ConnectionAttempt,
        runId: Long,
        supervisor: ReconnectSupervisor,
    ): ActiveConnectionInterrupt {
        val sshSession = connection.sshSession
        trafficActivityMonitor.resetBaseline()
        var pendingDegradationReason: String? = null
        var degradationDeferredAtMs = 0L
        val heartbeat = SshHeartbeatClock(SystemClock.elapsedRealtime(), sleptSoFarMs())
        while (shouldKeepConnectionAlive(runId)) {
            if (!appContainer.tun2SocksManager.isRunning(vpnTunnelOwner)) {
                return ActiveConnectionInterrupt(
                    message = "TUN forwarding stopped",
                    forceVpnRebuild = true,
                )
            }
            appContainer.tun2SocksManager.consumeDegradationReason(vpnTunnelOwner)?.let { reason ->
                if (pendingDegradationReason == null) {
                    pendingDegradationReason = reason
                    degradationDeferredAtMs = SystemClock.elapsedRealtime()
                }
            }
            // A rung of the stall ladder acts at once: it only exists while the forwarder still reports
            // the stall, i.e. a full minute without one byte of channel data while apps kept sending.
            // Deferring it on traffic counters would let it fire later, after the stall had cleared.
            val escalation = supervisor.onMonitorTick(SystemClock.elapsedRealtime(), SystemClock.uptimeMillis())
            if (escalation is ReconnectDecision.Interrupt) {
                logTrigger(source = "stall ladder", decision = escalation)
                return ActiveConnectionInterrupt(
                    message = "TUN forwarding degraded: ${escalation.reason}",
                    forceVpnRebuild = escalation.forceVpnRebuild,
                )
            }
            pendingDegradationReason?.let { degradationReason ->
                val now = SystemClock.elapsedRealtime()
                val traffic = trafficActivityMonitor.sampleSinceLast()
                if (!shouldDeferVpnDisruption(
                        traffic = traffic,
                        elapsedSinceLastForcedCheckMs = now - degradationDeferredAtMs,
                    )
                ) {
                    return ActiveConnectionInterrupt(
                        message = "TUN forwarding degraded: $degradationReason",
                        forceVpnRebuild = true,
                    )
                }
            }
            if (!appContainer.sshConnectionManager.isTransportAlive(sshSession)) {
                return ActiveConnectionInterrupt(
                    message = "SSH session disconnected",
                    forceVpnRebuild = false,
                )
            }
            if (heartbeat.isDue(SystemClock.elapsedRealtime())) {
                logHeartbeat(connection, heartbeat)
            }
            val posted = withTimeoutOrNull(monitorCadencePolicy.intervalMs(deviceInteractive)) {
                runtimeEvents.receive()
            } ?: continue
            val trigger = posted.trigger
            when (val decision = decide(posted, supervisor)) {
                is ReconnectDecision.Interrupt -> {
                    logTrigger(trigger.source, decision)
                    return ActiveConnectionInterrupt(
                        message = decision.reason,
                        forceVpnRebuild = decision.forceVpnRebuild,
                    )
                }
                is ReconnectDecision.Probe -> {
                    val probe = appContainer.sshConnectionManager.probeTransport(vpnTunnelOwner)
                    logTrigger(trigger.source, decision, outcome = probe?.name ?: "no supervised link")
                }
                is ReconnectDecision.Ignore, is ReconnectDecision.RetryNow -> logTrigger(trigger.source, decision)
            }
        }
        return ActiveConnectionInterrupt(
            message = "Connection stopped",
            forceVpnRebuild = false,
        )
    }

    /**
     * Waits out a backoff without ever depending on a timer that deep sleep freezes: the wait ends
     * at the deadline, on a trigger that makes a retry worthwhile (screen on, network back, an app
     * waiting for the tunnel), or on the allow-while-idle alarm. A retry a trigger asks for only
     * moves the deadline closer, and every deadline is guarded the same way: an alarm for more than a
     * few seconds, a wake lock for less - whatever the screen does in the meantime.
     */
    private suspend fun waitBeforeReconnect(
        delayMs: Long,
        runId: Long,
        supervisor: ReconnectSupervisor,
    ) {
        var deadlineMs = SystemClock.elapsedRealtime() + delayMs
        // Whatever queued up while the loop was busy gets a look before anything is armed for nothing.
        while (true) {
            val queued = runtimeEvents.tryReceive().getOrNull() ?: break
            retryDeadline(queued, supervisor)?.let { deadlineMs = minOf(deadlineMs, it) }
        }
        val waitHold = ReconnectWakeHelper.Hold()
        var alarmArmed = false
        var guardedDeadlineMs = Long.MAX_VALUE
        try {
            while (shouldKeepConnectionAlive(runId)) {
                val remainingMs = deadlineMs - SystemClock.elapsedRealtime()
                if (remainingMs <= 0L) break
                if (deadlineMs < guardedDeadlineMs) {
                    if (remainingMs > SHORT_BACKOFF_WAKE_LOCK_MS) {
                        // Replaces an alarm armed for a later deadline: same PendingIntent.
                        reconnectWakeHelper.scheduleRetryAlarm(remainingMs)
                        alarmArmed = true
                        // The alarm wakes the phone for the retry; until then it may sleep.
                        reconnectWakeHelper.release(reconnectHandoffHold)
                    } else {
                        // Costs nothing while the screen is on and covers it going off mid-wait.
                        reconnectWakeHelper.acquire(waitHold, remainingMs + BACKOFF_WAKE_LOCK_MARGIN_MS)
                    }
                    guardedDeadlineMs = deadlineMs
                }
                val posted = withTimeoutOrNull(remainingMs) { runtimeEvents.receive() } ?: break
                retryDeadline(posted, supervisor)?.let { deadlineMs = minOf(deadlineMs, it) }
            }
        } finally {
            // The attempt takes its own lock only after a network is selected; bridge the hops until then.
            if (shouldKeepConnectionAlive(runId)) holdCpuForReconnectHandoff()
            reconnectWakeHelper.release(waitHold)
            if (alarmArmed) reconnectWakeHelper.cancelRetryAlarm()
        }
    }

    /**
     * With the screen off the CPU may suspend on any thread hop between a decision to reconnect and
     * the attempt that follows, and the reconnect then waits for somebody else's wake-up. A short,
     * self-expiring lock bridges that gap; the backoff guard or the attempt's own lock takes over.
     */
    private fun holdCpuForReconnectHandoff() {
        if (!deviceInteractive) {
            reconnectWakeHelper.acquire(reconnectHandoffHold, RECONNECT_HANDOFF_WAKE_MS)
        }
    }

    /** Logs the supervisor's decision; returns the time to retry at if it asks to leave the wait. */
    private fun retryDeadline(posted: PostedTrigger, supervisor: ReconnectSupervisor): Long? {
        val decision = decide(posted, supervisor)
        logTrigger(posted.trigger.source, decision)
        return (decision as? ReconnectDecision.RetryNow)?.let { retry -> SystemClock.elapsedRealtime() + retry.afterMs }
    }

    private fun decide(posted: PostedTrigger, supervisor: ReconnectSupervisor): ReconnectDecision {
        return supervisor.onTrigger(
            trigger = posted.trigger,
            nowMs = SystemClock.elapsedRealtime(),
            postedAtMs = posted.postedAtMs,
            awakeMs = SystemClock.uptimeMillis(),
        )
    }

    /** New TCP flow on the TUN read thread: probe a link that has been silent for a while. Never blocks. */
    private fun probeQuietTransportForNewFlow() {
        val probe = appContainer.sshConnectionManager.probeTransportIfQuiet(vpnTunnelOwner, QUIET_LINK_PROBE_MS)
        if (probe == LinkProbeDecision.SENT) {
            logTrigger(
                source = "new flow",
                decision = ReconnectDecision.Probe("the link was silent for ${QUIET_LINK_PROBE_MS / 1_000}s+"),
                outcome = probe.name,
            )
        }
    }

    private fun drainStaleRuntimeEvents() {
        var drained = 0
        while (runtimeEvents.tryReceive().isSuccess) drained += 1
        if (drained > 0) {
            appContainer.vpnConnectionRepository.appendDiagnostic(
                "Dropped $drained transport hint(s) left over from the previous connection run",
            )
        }
    }

    /**
     * Every trigger leaves a line, including the ones that changed nothing: an empty log during a
     * freeze has to mean "nothing fired", not "something fired and was dropped silently".
     */
    private fun logTrigger(
        source: String,
        decision: ReconnectDecision,
        outcome: String? = null,
    ) {
        val line = "Reconnect trigger [$source] -> ${decision.summary}" + outcome?.let { " ($it)" }.orEmpty()
        val filtered = if (decision is ReconnectDecision.Interrupt) {
            line
        } else {
            triggerLog.filter("$source|${decision::class.java.simpleName}", line, SystemClock.elapsedRealtime())
        }
        filtered?.let(appContainer.vpnConnectionRepository::appendDiagnostic)
    }

    private fun logHeartbeat(connection: ConnectionAttempt, heartbeat: SshHeartbeatClock) {
        val nowMs = SystemClock.elapsedRealtime()
        val sleptMs = sleptSoFarMs()
        val sleptSinceLastMs = heartbeat.mark(nowMs, sleptMs)
        val link = appContainer.sshConnectionManager.describeTransport(vpnTunnelOwner)
            ?: "link #${connection.linkId} not supervised"
        val flows = appContainer.tun2SocksManager.activeTcpSessionCount(vpnTunnelOwner)
        val doze = runCatching { powerManager.isDeviceIdleMode }.getOrDefault(false)
        val network = transportNetwork
        val blocked = network?.let(underlyingNetworkMonitor::blockedStatus) ?: "unknown"
        appContainer.vpnConnectionRepository.appendDiagnostic(
            "SSH heartbeat: $link; flows=$flows; net=${network ?: "none"} blocked=$blocked " +
                "screen=${if (deviceInteractive) "on" else "off"} doze=$doze; " +
                "deep sleep since last heartbeat ${formatLinkDuration(sleptSinceLastMs)}",
        )
    }

    /** Deep sleep since boot: elapsedRealtime counts it, uptimeMillis does not. */
    private fun sleptSoFarMs(): Long = SystemClock.elapsedRealtime() - SystemClock.uptimeMillis()

    private fun logConnectionAttemptFailure(
        attempt: Int,
        errorMessage: String,
        causeMessage: String?,
    ) {
        val connectionRepository = appContainer.vpnConnectionRepository
        val prefix = if (attempt == 1) "Connection failed" else "Reconnect failed"
        connectionRepository.appendDiagnostic("$prefix: $errorMessage")
        causeMessage?.let { message ->
            connectionRepository.appendDiagnostic("Failure detail: $message")
        }
    }

    private fun connectionLogger(runId: Long): (String) -> Unit {
        return { message -> appendConnectionDiagnostic(runId, message) }
    }

    private fun appendConnectionDiagnostic(
        runId: Long?,
        message: String,
    ) {
        if (runId != null && connectionRunId.get() != runId) return
        appContainer.vpnConnectionRepository.appendDiagnostic(message)
    }

    private fun shouldKeepConnectionAlive(runId: Long): Boolean {
        return connectionRunId.get() == runId &&
            !userRequestedDisconnect &&
            !serviceDestroyed &&
            runtimeLease?.isCurrent() == true &&
            isVpnSessionOwnedBy(
                state = appContainer.vpnConnectionRepository.currentState,
                owner = VpnSessionOwner.SHADOW_SSH,
                transport = VpnTransportType.SSH,
            )
    }

    private fun isLifecycleCommandCurrent(runId: Long, commandId: Long, startId: Int): Boolean {
        return isVpnLifecycleCommandCurrent(
            expectedRunId = runId,
            currentRunId = connectionRunId.get(),
            expectedCommandId = commandId,
            currentCommandId = lifecycleCommandId.get(),
            expectedStartId = startId,
            serviceDestroyed = serviceDestroyed,
        )
    }

    private fun isActiveConnectionCommandCurrent(runId: Long, commandId: Long): Boolean {
        return connectionRunId.get() == runId &&
            lifecycleCommandId.get() == commandId &&
            !userRequestedDisconnect &&
            !serviceDestroyed &&
            runtimeLease?.isCurrent() == true &&
            isVpnSessionOwnedBy(
                state = appContainer.vpnConnectionRepository.currentState,
                owner = VpnSessionOwner.SHADOW_SSH,
                transport = VpnTransportType.SSH,
            )
    }

    private suspend fun mutateActiveConnectionIfCurrent(
        runId: Long,
        commandId: Long,
        mutation: () -> Unit,
    ): Boolean = withContext(Dispatchers.Main.immediate) {
        if (!isActiveConnectionCommandCurrent(runId, commandId)) {
            return@withContext false
        }
        mutation()
        true
    }

    private suspend fun failConnectionAndStop(
        runId: Long,
        commandId: Long,
        startId: Int,
        configId: String?,
        message: String,
        diagnostic: String? = null,
    ) {
        lifecycleMutex.withLock {
            if (!isActiveConnectionCommandCurrent(runId, commandId)) return@withLock
            diagnostic?.let(appContainer.vpnConnectionRepository::appendDiagnostic)
            disconnectInternal(
                runId = runId,
            )
            finishTerminalTransitionIfCurrent(
                runId = runId,
                commandId = commandId,
                startId = startId,
                requireActiveConnection = true,
            ) {
                val repository = appContainer.vpnConnectionRepository
                if (isVpnSessionOwnedBy(
                        state = repository.currentState,
                        owner = VpnSessionOwner.SHADOW_SSH,
                        transport = VpnTransportType.SSH,
                    )
                ) {
                    repository.setError(configId, message)
                }
            }
        }
    }

    /**
     * Runs on the service main thread, so onStartCommand() cannot interleave after the guards.
     * stopSelfResult() is intentionally evaluated before state/foreground changes: it also rejects
     * a newer start already queued in ActivityManager but not delivered to this instance yet.
     */
    private suspend fun finishTerminalTransitionIfCurrent(
        runId: Long,
        commandId: Long,
        startId: Int,
        requireActiveConnection: Boolean,
        mutation: () -> Unit,
    ): Boolean = withContext(Dispatchers.Main.immediate) {
        if (!isLifecycleCommandCurrent(runId, commandId, startId)) {
            return@withContext false
        }
        if (requireActiveConnection && !isActiveConnectionCommandCurrent(runId, commandId)) {
            return@withContext false
        }
        if (!stopSelfResult(startId)) {
            return@withContext false
        }
        mutation()
        stopForeground(STOP_FOREGROUND_REMOVE)
        true
    }

    private fun ensureConnectionStillWanted(runId: Long, commandId: Long) {
        if (!isActiveConnectionCommandCurrent(runId, commandId)) {
            throw CancellationException("Connection run stopped")
        }
    }

    private fun validateAppSettings(appSettings: AppSettings) {
        if (appSettings.vpnMode == VpnMode.SELECTED_APPS && appSettings.selectedAppPackages.isEmpty()) {
            throw VpnConnectionException("No apps selected")
        }
    }

    private suspend fun prepareForReconnect(
        runId: Long,
        commandId: Long,
        keepVpnPipeline: Boolean,
        announceHotReconnect: Boolean,
    ) {
        lifecycleMutex.withLock {
            if (!isActiveConnectionCommandCurrent(runId, commandId)) return@withLock
            // Socket first: pausing flows closes their SSH channels, and on a dead path each close
            // would otherwise queue behind a JSch write that never returns.
            appContainer.sshConnectionManager.killTransport(vpnTunnelOwner, "reconnecting")
            if (keepVpnPipeline && canReuseVpnPipeline()) {
                if (announceHotReconnect) {
                    appContainer.vpnConnectionRepository.appendDiagnostic(
                        "Keeping Android VPN interface active while SSH transport reconnects",
                    )
                }
                cleanupDisconnectStep("TUN SSH transport") {
                    appContainer.tun2SocksManager.pauseSshTransport(vpnTunnelOwner)
                }
                cleanupDisconnectStep("SSH session") {
                    appContainer.sshConnectionManager.disconnectOwner(vpnTunnelOwner)
                }
            } else {
                disconnectInternal(
                    runId = runId,
                )
            }
        }
    }

    private suspend fun cleanupVpnPipelineForRebuildIfCurrent(
        runId: Long,
        commandId: Long,
    ): Boolean {
        return lifecycleMutex.withLock {
            if (!isActiveConnectionCommandCurrent(runId, commandId)) {
                return@withLock false
            }
            cleanupVpnPipelineForRebuild(runId)
            true
        }
    }

    private suspend fun resumeSshTransportIfCurrent(
        runId: Long,
        commandId: Long,
        sshSession: Session,
    ): Boolean {
        return lifecycleMutex.withLock {
            if (!isActiveConnectionCommandCurrent(runId, commandId)) {
                return@withLock false
            }
            appContainer.tun2SocksManager.resumeSshTransport(vpnTunnelOwner, sshSession)
            true
        }
    }

    private fun canReuseVpnPipeline(): Boolean {
        return appContainer.vpnTunnelManager.isEstablished(vpnTunnelOwner) &&
            appContainer.tun2SocksManager.isRunning(vpnTunnelOwner)
    }

    private fun disconnect(startId: Int) {
        appContainer.vpnRuntimeLeaseRegistry.invalidate(vpnTunnelOwner)
        val commandId = lifecycleCommandId.incrementAndGet()
        userRequestedDisconnect = true
        transportNetwork = null
        connectionJob?.cancel()
        serviceScope.launch {
            lifecycleMutex.withLock {
                if (serviceDestroyed || lifecycleCommandId.get() != commandId) return@withLock
                userRequestedDisconnect = true
                val cleanupRunId = connectionRunId.incrementAndGet()
                val previousConnectionJob = connectionJob
                previousConnectionJob?.cancel()
                // This runs on Dispatchers.IO: closing many SSH channels must never block main.
                appContainer.sshConnectionManager.disconnectOwner(vpnTunnelOwner)
                previousConnectionJob?.let { job ->
                    withTimeoutOrNull(CONNECTION_JOB_JOIN_GRACE_MS) { job.join() }
                }
                if (serviceDestroyed || lifecycleCommandId.get() != commandId) return@withLock
                connectionJob = null
                val repository = appContainer.vpnConnectionRepository
                disconnectInternal(
                    runId = cleanupRunId,
                )
                finishTerminalTransitionIfCurrent(
                    runId = cleanupRunId,
                    commandId = commandId,
                    startId = startId,
                    requireActiveConnection = false,
                ) {
                    if (isVpnSessionOwnedBy(
                            state = repository.currentState,
                            owner = VpnSessionOwner.SHADOW_SSH,
                            transport = VpnTransportType.SSH,
                        )
                    ) {
                        repository.appendDiagnostic("Stopping VPN connection")
                        repository.setDisconnected()
                        repository.appendDiagnostic("VPN connection disconnected")
                    }
                }
            }
        }
    }

    private fun disconnectInternal(
        runId: Long,
        awaitTunTermination: Boolean = true,
    ) {
        if (connectionRunId.get() != runId) return
        appContainer.sshConnectionManager.killTransport(vpnTunnelOwner, "VPN teardown")
        cleanupVpnPipelineForRebuild(runId, awaitTunTermination)
        cleanupDisconnectStep("SSH session") {
            appContainer.sshConnectionManager.disconnectOwner(vpnTunnelOwner)
        }
    }

    private fun rejectStaleConnectCommand(startId: Int) {
        if (stopSelfResult(startId)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private fun rejectBusyRuntimeConnectCommand(startId: Int) {
        val repository = appContainer.vpnConnectionRepository
        if (isVpnSessionOwnedBy(
                state = repository.currentState,
                owner = VpnSessionOwner.SHADOW_SSH,
                transport = VpnTransportType.SSH,
            )
        ) {
            repository.setError(
                repository.currentState.activeConfigId,
                "Another VPN runtime is still stopping; try again",
            )
        }
        rejectStaleConnectCommand(startId)
    }

    private fun cleanupVpnPipelineForRebuild(
        runId: Long,
        awaitTermination: Boolean = true,
    ) {
        if (connectionRunId.get() != runId) return
        cleanupDisconnectStep("TUN forwarding") {
            appContainer.tun2SocksManager.stop(
                owner = vpnTunnelOwner,
                awaitTermination = awaitTermination,
            )
        }
        cleanupDisconnectStep("VPN interface") {
            appContainer.vpnTunnelManager.close(vpnTunnelOwner)
        }
    }

    private inline fun cleanupDisconnectStep(
        label: String,
        action: () -> Unit,
    ) {
        runCatching(action).onFailure { error ->
            appContainer.vpnConnectionRepository.appendDiagnostic(
                "Disconnect cleanup warning ($label): ${error.message ?: error::class.java.simpleName}",
            )
        }
    }

    private fun startVpnForeground() {
        ensureNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(getString(R.string.vpn_notification_title))
            .setContentText(getString(R.string.vpn_notification_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(Service.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.vpn_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val ACTION_CONNECT = "com.stansful.sshvpnclient.action.CONNECT"
        private const val ACTION_DISCONNECT = "com.stansful.sshvpnclient.action.DISCONNECT"
        private const val EXTRA_PRESERVE_DIAGNOSTICS =
            "com.stansful.sshvpnclient.extra.PRESERVE_DIAGNOSTICS"
        private const val CHANNEL_ID = "ssh_vpn_connection"
        private const val NOTIFICATION_ID = 3001
        private const val INTERACTIVE_CONNECTION_MONITOR_INTERVAL_MS = 5_000L
        private const val SCREEN_OFF_CONNECTION_MONITOR_INTERVAL_MS = 30_000L
        private const val INITIAL_CONNECT_TIMEOUT_MS = 20_000
        private const val RECONNECT_CONNECT_TIMEOUT_MS = 8_000
        private const val CONNECTION_JOB_JOIN_GRACE_MS = 1_000L
        private const val INITIAL_RECONNECT_DELAY_MS = 250L
        private const val MAX_RECONNECT_DELAY_MS = 30_000L
        private const val STABLE_CONNECTION_BACKOFF_RESET_MS = 30_000L
        private const val NETWORK_DIAGNOSTICS_RETRY_INTERVAL = 5
        private const val NO_SCREEN_OFF_TIMESTAMP = -1L
        private const val RUNTIME_EVENT_CAPACITY = 64
        private const val QUIET_LINK_PROBE_MS = 20_000L
        private const val SHORT_BACKOFF_WAKE_LOCK_MS = 4_000L
        private const val BACKOFF_WAKE_LOCK_MARGIN_MS = 1_000L
        private const val ATTEMPT_WAKE_LOCK_MARGIN_MS = 15_000L
        private const val RECONNECT_HANDOFF_WAKE_MS = 10_000L

        fun connectIntent(
            context: Context,
            preserveDiagnostics: Boolean = false,
        ): Intent {
            return Intent(context, SshVpnService::class.java)
                .setAction(ACTION_CONNECT)
                .putExtra(EXTRA_PRESERVE_DIAGNOSTICS, preserveDiagnostics)
        }

        fun disconnectIntent(context: Context): Intent {
            return Intent(context, SshVpnService::class.java).setAction(ACTION_DISCONNECT)
        }
    }
}

/** A trigger and when it was queued: the supervisor needs to know what an attempt already covered. */
private data class PostedTrigger(val trigger: ReconnectTrigger, val postedAtMs: Long)

private data class ConnectionAttempt(
    val sshSession: Session,
    val reusedVpnInterface: Boolean,
    /** Generation that watchdog verdicts are matched against; 0 if the session has no supervised link. */
    val linkId: Long,
)

/** Heartbeat cadence and the deep-sleep counter it reports the growth of. */
private class SshHeartbeatClock(startedAtMs: Long, sleptMs: Long) {
    private var lastAtMs = startedAtMs
    private var lastSleptMs = sleptMs

    fun isDue(nowMs: Long): Boolean = nowMs - lastAtMs >= HEARTBEAT_INTERVAL_MS

    /** @return deep sleep since the previous heartbeat. */
    fun mark(nowMs: Long, sleptMs: Long): Long {
        val sleptSinceLastMs = (sleptMs - lastSleptMs).coerceAtLeast(0L)
        lastAtMs = nowMs
        lastSleptMs = sleptMs
        return sleptSinceLastMs
    }

    private companion object {
        const val HEARTBEAT_INTERVAL_MS = 5 * 60_000L
    }
}

private data class ActiveConnectionInterrupt(
    val message: String,
    val forceVpnRebuild: Boolean,
)

private fun VpnConnectionException.isRecoverableBeforeFirstConnection(): Boolean {
    val value = message.orEmpty()
    return value.contains("timeout", ignoreCase = true) ||
        value.contains("Host unreachable", ignoreCase = true) ||
        value.contains("usable non-VPN network", ignoreCase = true) ||
        value.contains("Underlying network changed", ignoreCase = true) ||
        value.contains("another service instance", ignoreCase = true) ||
        value.contains("transport interrupted", ignoreCase = true) ||
        value.contains("Unknown connection error", ignoreCase = true) ||
        value.contains("TUN forwarding", ignoreCase = true)
}
