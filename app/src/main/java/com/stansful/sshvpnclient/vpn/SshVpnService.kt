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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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
    private var wakeRecoveryJob: Job? = null
    private val connectionRunId = AtomicLong(0L)
    private val lifecycleCommandId = AtomicLong(0L)
    @Volatile
    private var lastStartId: Int = 0
    private val connectionMonitorSignal = Channel<Unit>(Channel.CONFLATED)
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
    private val wakeRecoveryPolicy = WakeRecoveryPolicy(MINIMUM_SCREEN_OFF_RECOVERY_MS)
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
                    wakeRecoveryJob?.cancel()
                    appContainer.sshConnectionManager.setDeviceInteractive(isInteractive = false)
                    trafficActivityMonitor.resetBaseline()
                    connectionMonitorSignal.trySend(Unit)
                }

                Intent.ACTION_SCREEN_ON -> {
                    deviceInteractive = true
                    appContainer.sshConnectionManager.setDeviceInteractive(isInteractive = true)
                    connectionMonitorSignal.trySend(Unit)
                    val screenOnAtMs = SystemClock.elapsedRealtime()
                    val durationMs = wakeRecoveryPolicy.recoveryDurationMs(
                        screenOffAtMs = screenOffAtMs,
                        screenOnAtMs = screenOnAtMs,
                    )
                    screenOffAtMs = NO_SCREEN_OFF_TIMESTAMP
                    if (durationMs != null) {
                        scheduleWakeRecovery(durationMs)
                    }
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
        wakeRecoveryJob?.cancel()
        if (::underlyingNetworkMonitor.isInitialized) {
            underlyingNetworkMonitor.close()
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

    private fun scheduleWakeRecovery(screenOffDurationMs: Long) {
        if (userRequestedDisconnect) return
        val runId = connectionRunId.get()
        wakeRecoveryJob?.cancel()
        wakeRecoveryJob = serviceScope.launch {
            delay(WAKE_RECOVERY_DEBOUNCE_MS)
            if (!deviceInteractive || !shouldKeepConnectionAlive(runId) || !canReuseVpnPipeline()) {
                return@launch
            }
            val traffic = trafficActivityMonitor.sampleSinceLast()
            if (shouldDeferVpnDisruption(traffic, elapsedSinceLastForcedCheckMs = 0L)) {
                appContainer.vpnConnectionRepository.appendDiagnostic(
                    "Wake recovery skipped because active VPN traffic moved " +
                        "${traffic.totalBytes / 1_024L} KiB while the screen was off",
                )
                return@launch
            }
            val transportProbe = appContainer.sshConnectionManager.probeActiveTransport(
                log = { message ->
                    appContainer.vpnConnectionRepository.appendDiagnostic("Wake recovery: $message")
                },
                owner = vpnTunnelOwner,
            )
            if (!transportProbe.healthy && shouldKeepConnectionAlive(runId)) {
                val resetCount = appContainer.tun2SocksManager.resetIdleClientConnections(
                    owner = vpnTunnelOwner,
                    minimumIdleMs = WAKE_STALE_CONNECTION_IDLE_MS,
                )
                if (resetCount > 0) {
                    appContainer.vpnConnectionRepository.appendDiagnostic(
                        "Wake recovery: reset $resetCount stale TCP session(s) after " +
                            "${screenOffDurationMs / 1_000L}s screen off",
                    )
                }
                val disconnected = transportProbe.session?.let(
                    appContainer.sshConnectionManager::disconnectIfActive,
                ) == true
                if (disconnected) {
                    appContainer.vpnConnectionRepository.appendDiagnostic(
                        "Wake recovery: SSH transport is stale; reconnecting",
                    )
                    connectionMonitorSignal.trySend(Unit)
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
                connectionMonitorSignal.trySend(Unit)
                return@launch
            }
            transportNetwork = new
            val transition = "${old ?: "none"} -> ${new ?: "none"}"
            appContainer.vpnConnectionRepository.appendDiagnostic(
                "Underlying network changed ($transition); reconnecting SSH transport",
            )
            // Existing TCP sockets cannot migrate to another Network. The connection loop keeps
            // the TUN pipeline and creates the replacement SSH socket on the selected network.
            expectedSession?.let(appContainer.sshConnectionManager::disconnectIfCurrent)
            connectionMonitorSignal.trySend(Unit)
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
            val reconnectBackoff = ReconnectBackoff(
                initialDelayMs = INITIAL_RECONNECT_DELAY_MS,
                maxDelayMs = MAX_RECONNECT_DELAY_MS,
            )
            var reconnectStartedAtMs: Long? = null
            var forceVpnRebuildOnNextAttempt = false
            while (shouldKeepConnectionAlive(runId)) {
                if (attempt > 1) {
                    val publishedReconnect = mutateActiveConnectionIfCurrent(runId, commandId) {
                        connectionRepository.setReconnecting(config.id)
                        connectionRepository.appendDiagnostic("Reconnect attempt $attempt starting")
                    }
                    if (!publishedReconnect) break
                }

                var activeConnectionInterrupted = false
                var activeConnectionWasStable = false
                try {
                    val reuseVpnInterface = everConnected && canReuseVpnPipeline()
                    val connection = connectSingleAttempt(
                        config = config,
                        privateKey = privateKey,
                        appSettings = appSettings,
                        runId = runId,
                        commandId = commandId,
                        lease = lease,
                        reuseVpnInterface = reuseVpnInterface && !forceVpnRebuildOnNextAttempt,
                        includeNetworkDiagnostics = attempt == 1 ||
                            attempt % NETWORK_DIAGNOSTICS_RETRY_INTERVAL == 0,
                    )
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

                    val connectedAtMs = SystemClock.elapsedRealtime()
                    val interruptReason = monitorActiveConnection(connection.sshSession, runId)
                    if (!shouldKeepConnectionAlive(runId)) {
                        break
                    }
                    activeConnectionInterrupted = true
                    activeConnectionWasStable = shouldResetReconnectBackoff(
                        connectedDurationMs = SystemClock.elapsedRealtime() - connectedAtMs,
                        stableConnectionMs = STABLE_CONNECTION_BACKOFF_RESET_MS,
                    )
                    if (activeConnectionWasStable) {
                        reconnectBackoff.reset()
                    }
                    forceVpnRebuildOnNextAttempt = interruptReason.forceVpnRebuild
                    reconnectStartedAtMs = SystemClock.elapsedRealtime()
                    if (!mutateActiveConnectionIfCurrent(runId, commandId) {
                            connectionRepository.setReconnecting(config.id)
                            connectionRepository.appendDiagnostic(
                                "Connection interrupted: ${interruptReason.message}",
                            )
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
                if (activeConnectionInterrupted && activeConnectionWasStable) {
                    connectionRepository.appendDiagnostic("Immediate SSH reconnect starting")
                } else {
                    val reconnectDelayMs = reconnectBackoff.nextFailureDelayMs()
                    connectionRepository.appendDiagnostic(
                        "Reconnecting in ${reconnectDelayMs}ms; press Disconnect to stop",
                    )
                    delay(reconnectDelayMs)
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
        val log = connectionLogger(runId)
        val sshSession = appContainer.sshConnectionManager.connect(
            owner = vpnTunnelOwner,
            lease = lease,
            config = config,
            privateKey = privateKey,
            log = log,
            socketProtector = protectedSocketRoute,
            connectTimeoutMs = if (reuseVpnInterface) RECONNECT_CONNECT_TIMEOUT_MS else INITIAL_CONNECT_TIMEOUT_MS,
            verboseDiagnostics = includeNetworkDiagnostics,
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
        )
    }

    private suspend fun monitorActiveConnection(
        sshSession: Session,
        runId: Long,
    ): ActiveConnectionInterrupt {
        trafficActivityMonitor.resetBaseline()
        var pendingDegradationReason: String? = null
        var degradationDeferredAtMs = 0L
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
            if (!sshSession.isConnected) {
                return ActiveConnectionInterrupt(
                    message = "SSH session disconnected",
                    forceVpnRebuild = false,
                )
            }
            withTimeoutOrNull(monitorCadencePolicy.intervalMs(deviceInteractive)) {
                connectionMonitorSignal.receive()
            }
        }
        return ActiveConnectionInterrupt(
            message = "Connection stopped",
            forceVpnRebuild = false,
        )
    }

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
        private const val MINIMUM_SCREEN_OFF_RECOVERY_MS = 5 * 60_000L
        private const val WAKE_STALE_CONNECTION_IDLE_MS = 2 * 60_000L
        private const val WAKE_RECOVERY_DEBOUNCE_MS = 2_000L
        private const val NO_SCREEN_OFF_TIMESTAMP = -1L

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

private data class ConnectionAttempt(
    val sshSession: Session,
    val reusedVpnInterface: Boolean,
)

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
