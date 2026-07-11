package com.stansful.sshvpnclient.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Network
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.stansful.sshvpnclient.R
import com.stansful.sshvpnclient.SshVpnApplication
import com.stansful.sshvpnclient.domain.model.VpnMode
import com.stansful.sshvpnclient.domain.model.VpnTransportType
import com.stansful.sshvpnclient.xray.XrayCoreBridge
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

class OpenSourceVpnService : android.net.VpnService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()
    private val vpnTunnelOwner = Any()
    @Volatile
    private var connectionJob: Job? = null
    private val connectionRunId = AtomicLong(0L)
    private val lifecycleCommandId = AtomicLong(0L)
    @Volatile
    private var lastStartId: Int = 0
    private val connectionMonitorSignal = Channel<Unit>(Channel.CONFLATED)
    @Volatile
    private var userRequestedDisconnect = true
    @Volatile
    private var serviceDestroyed = false
    @Volatile
    private var deviceInteractive = true
    @Volatile
    private var transportNetwork: Network? = null
    private val xrayTransportGeneration = XrayRuntimeGenerationTracker()
    private val socketRoutingFailureGeneration = AtomicLong(NO_XRAY_GENERATION)
    @Volatile
    private var runtimeLease: VpnRuntimeLease? = null
    private var screenReceiverRegistered = false
    private lateinit var underlyingNetworkMonitor: UnderlyingNetworkMonitor
    private val monitorCadencePolicy = ConnectionMonitorCadencePolicy(
        interactiveIntervalMs = INTERACTIVE_CONNECTION_MONITOR_INTERVAL_MS,
        screenOffIntervalMs = SCREEN_OFF_CONNECTION_MONITOR_INTERVAL_MS,
    )
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> deviceInteractive = false
                Intent.ACTION_SCREEN_ON -> deviceInteractive = true
                else -> return
            }
            connectionMonitorSignal.trySend(Unit)
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
        deviceInteractive = (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
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

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_CONNECT -> {
                // Meet the foreground-service deadline before repository or native-core I/O.
                startVpnForeground(profileName = null)
                underlyingNetworkMonitor.start()
                connect(
                    profileId = intent.getStringExtra(EXTRA_PROFILE_ID),
                    startId = startId,
                )
            }
            ACTION_DISCONNECT -> disconnect(startId)
        }
        return START_NOT_STICKY
    }

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
        if (screenReceiverRegistered) {
            runCatching { unregisterReceiver(screenStateReceiver) }
            screenReceiverRegistered = false
        }
        val repository = appContainer.vpnConnectionRepository
        if (repository.currentState.activeTransport == VpnTransportType.XRAY) {
            repository.setDisconnected()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        serviceScope.launch {
            try {
                cleanup(destroyRunId)
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

    private fun connect(profileId: String?, startId: Int) {
        val runId = connectionRunId.incrementAndGet()
        val lease = appContainer.vpnRuntimeLeaseRegistry.claim(vpnTunnelOwner)
        runtimeLease = lease
        val commandId = lifecycleCommandId.incrementAndGet()
        serviceScope.launch {
            lifecycleMutex.withLock {
                if (serviceDestroyed || lifecycleCommandId.get() != commandId) return@withLock
                userRequestedDisconnect = false
                stopOwnedXrayTransport()
                val previousConnectionJob = connectionJob
                previousConnectionJob?.cancel()
                previousConnectionJob?.let { job ->
                    withTimeoutOrNull(CONNECTION_JOB_JOIN_GRACE_MS) { job.join() }
                }
                if (!shouldKeepConnectionAlive(runId) || lifecycleCommandId.get() != commandId) {
                    return@withLock
                }
                cleanup(runId)
                if (!shouldKeepConnectionAlive(runId) || lifecycleCommandId.get() != commandId) {
                    return@withLock
                }
                connectionJob = serviceScope.launch {
                    runConnectionLoop(
                        profileId = profileId,
                        runId = runId,
                        commandId = commandId,
                        startId = startId,
                        lease = lease,
                    )
                }
            }
        }
    }

    private suspend fun runConnectionLoop(
        profileId: String?,
        runId: Long,
        commandId: Long,
        startId: Int,
        lease: VpnRuntimeLease,
    ) {
        try {
            runConnectionLoopInternal(profileId, runId, commandId, startId, lease)
        } catch (error: CancellationException) {
            // The serialized lifecycle transition owns cleanup after bounded cancellation join.
        } catch (error: Exception) {
            if (shouldKeepConnectionAlive(runId)) {
                val message = error.message ?: error::class.java.simpleName
                failAndStop(
                    runId = runId,
                    commandId = commandId,
                    startId = startId,
                    profileId = profileId,
                    message = message,
                    diagnostic = "Xray connection failed: $message",
                )
            }
        } finally {
            if (connectionRunId.get() == runId) {
                connectionJob = null
            }
        }
    }

    private suspend fun runConnectionLoopInternal(
        profileId: String?,
        runId: Long,
        commandId: Long,
        startId: Int,
        lease: VpnRuntimeLease,
    ) {
        val repository = appContainer.vpnConnectionRepository
        val profile = profileId?.let { appContainer.proxyProfileRepository.getById(it) }
        if (!isActiveConnectionCommandCurrent(runId, commandId)) return
        if (profile == null) {
            failAndStop(
                runId = runId,
                commandId = commandId,
                startId = startId,
                profileId = profileId,
                message = "No opensource configuration selected",
            )
            return
        }
        if (!appContainer.xrayCoreBridge.isAvailable) {
            failAndStop(
                runId = runId,
                commandId = commandId,
                startId = startId,
                profileId = profile.id,
                message = XrayCoreBridge.CORE_UNAVAILABLE_MESSAGE,
            )
            return
        }
        if (!isActiveConnectionCommandCurrent(runId, commandId)) return
        val settings = appContainer.appSettingsRepository.settings.value
        if (settings.vpnMode == VpnMode.SELECTED_APPS && settings.selectedAppPackages.isEmpty()) {
            failAndStop(
                runId = runId,
                commandId = commandId,
                startId = startId,
                profileId = profile.id,
                message = getString(R.string.error_no_selected_apps),
            )
            return
        }

        val publishedStart = mutateActiveConnectionIfCurrent(runId, commandId) {
            repository.setConnecting(profile.id, VpnTransportType.XRAY)
            repository.appendDiagnostic("Starting opensource VPN connection")
            repository.appendDiagnostic(
                "Selected public profile: ${profile.protocol.name}/${profile.transport.name} " +
                    "${profile.host}:${profile.port}",
            )
            startVpnForeground(profile.name)
        }
        if (!publishedStart) return
        val backoff = ReconnectBackoff(INITIAL_RECONNECT_DELAY_MS, MAX_RECONNECT_DELAY_MS)
        var attempt = 1

        while (shouldKeepConnectionAlive(runId)) {
            try {
                if (attempt > 1) {
                    val publishedReconnect = mutateActiveConnectionIfCurrent(runId, commandId) {
                        repository.setReconnecting(profile.id, VpnTransportType.XRAY)
                        repository.appendDiagnostic("Xray reconnect attempt $attempt")
                    }
                    if (!publishedReconnect) break
                }
                if (!cleanupActiveConnectionRun(runId, commandId)) break
                if (underlyingNetworkMonitor.currentNetwork() == null) {
                    repository.appendDiagnostic("Waiting for a usable physical network")
                }
                val underlyingNetwork = underlyingNetworkMonitor.awaitUsableNetwork()
                if (!isActiveConnectionCommandCurrent(runId, commandId)) break
                if (!lease.isCurrent()) {
                    throw CancellationException("VPN runtime lease was superseded")
                }
                transportNetwork = underlyingNetwork
                repository.appendDiagnostic("Selected underlying network: $underlyingNetwork")
                val dnsServer = underlyingNetworkMonitor.dnsEndpointFor(underlyingNetwork)
                    ?: throw VpnConnectionException("No DNS server is available on the physical network")
                repository.appendDiagnostic("Selected underlying DNS: $dnsServer")
                val vpnInterface = appContainer.vpnTunnelManager.establish(
                    owner = vpnTunnelOwner,
                    lease = lease,
                    service = this,
                    sessionName = VPN_SESSION_NAME,
                    appSettings = settings,
                    mode = VpnTunnelMode.XRAY,
                    underlyingNetwork = underlyingNetwork,
                    log = repository::appendDiagnostic,
                )
                val attemptGeneration = AtomicLong(NO_XRAY_GENERATION)
                val startedGeneration = appContainer.xrayCoreBridge.startTun(
                    owner = vpnTunnelOwner,
                    lease = lease,
                    profile = profile,
                    tunFd = vpnInterface.fd,
                    dnsServer = dnsServer,
                    protectSocket = { fd ->
                        bindAndProtectXraySocket(
                            fd = fd,
                            runId = runId,
                            generation = attemptGeneration.get(),
                        )
                    },
                    protectListenerSocket = ::protect,
                    onGenerationReserved = { generation ->
                        attemptGeneration.set(generation)
                        socketRoutingFailureGeneration.set(NO_XRAY_GENERATION)
                        xrayTransportGeneration.publish(generation)
                    },
                )
                if (!isActiveConnectionCommandCurrent(runId, commandId)) {
                    stopXrayTransport(startedGeneration)
                    throw CancellationException("Xray connection run was superseded")
                }
                if (underlyingNetworkMonitor.currentNetwork() != underlyingNetwork) {
                    stopXrayTransport(startedGeneration)
                    error("Underlying network changed during Xray startup")
                }
                if (!mutateActiveConnectionIfCurrent(runId, commandId) {
                        repository.setConnected(profile.id, VpnTransportType.XRAY)
                        repository.appendDiagnostic("Xray VPN connection is connected")
                    }
                ) {
                    stopXrayTransport(startedGeneration)
                    throw CancellationException("Xray connection run was superseded before publication")
                }
                val connectedAtMs = android.os.SystemClock.elapsedRealtime()
                while (shouldKeepConnectionAlive(runId) &&
                    appContainer.xrayCoreBridge.isRunning() &&
                    socketRoutingFailureGeneration.get() != startedGeneration
                ) {
                    withTimeoutOrNull(monitorCadencePolicy.intervalMs(deviceInteractive)) {
                        connectionMonitorSignal.receive()
                    }
                }
                if (shouldKeepConnectionAlive(runId)) {
                    if (shouldResetReconnectBackoff(
                            connectedDurationMs = android.os.SystemClock.elapsedRealtime() - connectedAtMs,
                            stableConnectionMs = STABLE_CONNECTION_BACKOFF_RESET_MS,
                        )
                    ) {
                        backoff.reset()
                    }
                    if (socketRoutingFailureGeneration.get() == startedGeneration) {
                        error("Xray physical socket routing failed")
                    }
                    error("Xray core stopped unexpectedly")
                }
            } catch (error: CancellationException) {
                break
            } catch (error: Exception) {
                if (!isActiveConnectionCommandCurrent(runId, commandId)) break
                if (!mutateActiveConnectionIfCurrent(runId, commandId) {
                        repository.setReconnecting(profile.id, VpnTransportType.XRAY)
                        repository.appendDiagnostic(
                            "Xray connection failed: ${error.message ?: error::class.java.simpleName}",
                        )
                    }
                ) {
                    break
                }
                if (!cleanupActiveConnectionRun(runId, commandId)) break
                val reconnectDelay = backoff.nextFailureDelayMs()
                repository.appendDiagnostic("Xray reconnecting in ${reconnectDelay}ms")
                delay(reconnectDelay)
                attempt += 1
            }
        }
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
                stopOwnedXrayTransport()
                val previousConnectionJob = connectionJob
                previousConnectionJob?.cancel()
                previousConnectionJob?.let { job ->
                    withTimeoutOrNull(CONNECTION_JOB_JOIN_GRACE_MS) { job.join() }
                }
                if (serviceDestroyed || lifecycleCommandId.get() != commandId) return@withLock
                connectionJob = null
                val repository = appContainer.vpnConnectionRepository
                cleanup(cleanupRunId)
                finishTerminalTransitionIfCurrent(
                    runId = cleanupRunId,
                    commandId = commandId,
                    startId = startId,
                    requireActiveConnection = false,
                ) {
                    if (repository.currentState.activeTransport == VpnTransportType.XRAY) {
                        repository.setDisconnected()
                    }
                }
            }
        }
    }

    private fun onUnderlyingNetworkChanged(old: Network?, new: Network?) {
        val runId = connectionRunId.get()
        serviceScope.launch {
            if (underlyingNetworkMonitor.currentNetwork() != new) return@launch
            if (!shouldKeepConnectionAlive(runId)) return@launch
            val expectedGeneration = xrayTransportGeneration.snapshot()
            appContainer.vpnTunnelManager.updateUnderlyingNetwork(
                owner = vpnTunnelOwner,
                service = this@OpenSourceVpnService,
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
            appContainer.vpnConnectionRepository.appendDiagnostic(
                "Underlying network changed (${old ?: "none"} -> ${new ?: "none"}); restarting Xray transport",
            )
            // Native outbound sockets are pinned to their original Network and cannot migrate.
            expectedGeneration?.let(::stopXrayTransport)
            connectionMonitorSignal.trySend(Unit)
        }
    }

    private fun bindAndProtectXraySocket(fd: Int, runId: Long, generation: Long): Boolean {
        if (!shouldKeepConnectionAlive(runId)) return false
        val protected = runCatching { protect(fd) }.getOrDefault(false)
        if (!protected) {
            reportXraySocketRoutingFailure(generation, "could not protect socket from VPN routing")
            return false
        }
        val network = underlyingNetworkMonitor.currentNetwork()
        if (network == null) {
            reportXraySocketRoutingFailure(generation, "physical network disappeared")
            return true
        }
        return try {
            // Protect first so a cellular bind failure cannot feed the socket back into this TUN.
            // fromFd duplicates the descriptor, so closing the wrapper does not close Xray's fd.
            ParcelFileDescriptor.fromFd(fd).use { duplicate ->
                network.bindSocket(duplicate.fileDescriptor)
            }
            true
        } catch (error: Exception) {
            appContainer.vpnConnectionRepository.appendDiagnostic(
                "Xray socket was protected but explicit network bind failed; " +
                    "using Android VPN underlying network: " +
                    (error.message ?: error::class.java.simpleName),
            )
            // VpnService.protect plus Builder.setUnderlyingNetworks is a valid fallback.
            true
        }
    }

    private fun reportXraySocketRoutingFailure(generation: Long, reason: String) {
        if (generation == NO_XRAY_GENERATION || xrayTransportGeneration.snapshot() != generation) return
        if (!socketRoutingFailureGeneration.compareAndSet(NO_XRAY_GENERATION, generation)) return
        appContainer.vpnConnectionRepository.appendDiagnostic("Xray socket routing failed: $reason")
        connectionMonitorSignal.trySend(Unit)
    }

    private fun shouldKeepConnectionAlive(runId: Long): Boolean {
        return connectionRunId.get() == runId &&
            !userRequestedDisconnect &&
            !serviceDestroyed &&
            runtimeLease?.isCurrent() == true
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
            runtimeLease?.isCurrent() == true
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

    private suspend fun cleanupActiveConnectionRun(runId: Long, commandId: Long): Boolean {
        return lifecycleMutex.withLock {
            if (!isActiveConnectionCommandCurrent(runId, commandId)) {
                return@withLock false
            }
            cleanup(runId)
            true
        }
    }

    private suspend fun failAndStop(
        runId: Long,
        commandId: Long,
        startId: Int,
        profileId: String?,
        message: String,
        diagnostic: String? = null,
    ) {
        lifecycleMutex.withLock {
            if (!isActiveConnectionCommandCurrent(runId, commandId)) return@withLock
            diagnostic?.let(appContainer.vpnConnectionRepository::appendDiagnostic)
            cleanup(runId)
            finishTerminalTransitionIfCurrent(
                runId = runId,
                commandId = commandId,
                startId = startId,
                requireActiveConnection = true,
            ) {
                appContainer.vpnConnectionRepository.setError(profileId, message)
            }
        }
    }

    /**
     * Main-thread execution makes the local command check atomic with onStartCommand(). Calling
     * stopSelfResult() before state/foreground mutations also detects a newer queued Android start.
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

    private fun cleanup(runId: Long) {
        if (connectionRunId.get() != runId) return
        stopOwnedXrayTransport()
        runCatching { appContainer.vpnTunnelManager.close(vpnTunnelOwner) }
    }

    private fun stopOwnedXrayTransport(): Boolean {
        val ownedGeneration = xrayTransportGeneration.snapshot() ?: return false
        return stopXrayTransport(ownedGeneration)
    }

    private fun stopXrayTransport(expectedGeneration: Long): Boolean {
        val stopped = runCatching {
            appContainer.xrayCoreBridge.stopBlocking(vpnTunnelOwner, expectedGeneration)
        }.getOrDefault(false)
        val isStillCurrent = appContainer.xrayCoreBridge.isRuntimeGenerationCurrent(
            vpnTunnelOwner,
            expectedGeneration,
        )
        if (stopped || !isStillCurrent) {
            xrayTransportGeneration.clearIfMatches(expectedGeneration)
        }
        return stopped
    }

    private fun startVpnForeground(profileName: String?) {
        val manager = getSystemService(Service.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.vpn_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(getString(R.string.vpn_notification_title))
            .setContentText(
                profileName?.let { getString(R.string.vpn_notification_public_profile_text, it) }
                    ?: getString(R.string.vpn_notification_text),
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val ACTION_CONNECT = "com.stansful.sshvpnclient.action.CONNECT_OPEN_SOURCE"
        private const val ACTION_DISCONNECT = "com.stansful.sshvpnclient.action.DISCONNECT_OPEN_SOURCE"
        private const val EXTRA_PROFILE_ID = "com.stansful.sshvpnclient.extra.PROXY_PROFILE_ID"
        private const val CHANNEL_ID = "ssh_vpn_connection"
        private const val NOTIFICATION_ID = 3002
        private const val VPN_SESSION_NAME = "Secure connection"
        private const val INTERACTIVE_CONNECTION_MONITOR_INTERVAL_MS = 10_000L
        private const val SCREEN_OFF_CONNECTION_MONITOR_INTERVAL_MS = 30_000L
        private const val INITIAL_RECONNECT_DELAY_MS = 250L
        private const val MAX_RECONNECT_DELAY_MS = 30_000L
        private const val STABLE_CONNECTION_BACKOFF_RESET_MS = 30_000L
        private const val CONNECTION_JOB_JOIN_GRACE_MS = 1_000L
        private const val NO_XRAY_GENERATION = Long.MIN_VALUE

        fun connectIntent(context: Context, profileId: String): Intent {
            return Intent(context, OpenSourceVpnService::class.java)
                .setAction(ACTION_CONNECT)
                .putExtra(EXTRA_PROFILE_ID, profileId)
        }

        fun disconnectIntent(context: Context): Intent {
            return Intent(context, OpenSourceVpnService::class.java).setAction(ACTION_DISCONNECT)
        }
    }
}
