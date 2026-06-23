package com.stansful.sshvpnclient.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SshVpnService : android.net.VpnService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var connectionJob: Job? = null
    private var wakeRecoveryJob: Job? = null
    @Volatile
    private var connectionRunId: Long = 0L
    @Volatile
    private var userRequestedDisconnect: Boolean = true
    private var screenOffAtMs: Long = NO_SCREEN_OFF_TIMESTAMP
    private var screenReceiverRegistered = false
    private val wakeRecoveryPolicy = WakeRecoveryPolicy(MINIMUM_SCREEN_OFF_RECOVERY_MS)
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOffAtMs = SystemClock.elapsedRealtime()
                }

                Intent.ACTION_SCREEN_ON -> {
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
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (!powerManager.isInteractive) {
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
        when (intent?.action) {
            ACTION_CONNECT -> connect(
                preserveDiagnostics = intent.getBooleanExtra(EXTRA_PRESERVE_DIAGNOSTICS, false),
            )
            ACTION_DISCONNECT -> disconnect()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    override fun onDestroy() {
        userRequestedDisconnect = true
        connectionRunId += 1
        connectionJob?.cancel()
        wakeRecoveryJob?.cancel()
        if (screenReceiverRegistered) {
            runCatching { unregisterReceiver(screenStateReceiver) }
            screenReceiverRegistered = false
        }
        disconnectInternal(updateState = false)
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun connect(preserveDiagnostics: Boolean) {
        userRequestedDisconnect = false
        val runId = ++connectionRunId
        connectionJob?.cancel()
        connectionJob = serviceScope.launch {
            runConnectionLoop(runId, preserveDiagnostics)
        }
    }

    private fun scheduleWakeRecovery(screenOffDurationMs: Long) {
        if (userRequestedDisconnect) return
        wakeRecoveryJob?.cancel()
        wakeRecoveryJob = serviceScope.launch {
            if (userRequestedDisconnect || !canReuseVpnPipeline()) return@launch
            val resetCount = appContainer.tun2SocksManager.resetIdleClientConnections(
                minimumIdleMs = WAKE_STALE_CONNECTION_IDLE_MS,
            )
            if (resetCount > 0) {
                appContainer.vpnConnectionRepository.appendDiagnostic(
                    "Wake recovery: reset $resetCount stale TCP session(s) after " +
                        "${screenOffDurationMs / 1_000L}s screen off",
                )
            }
            val transportHealthy = appContainer.sshConnectionManager.checkActiveTransport(
                log = { message ->
                    appContainer.vpnConnectionRepository.appendDiagnostic("Wake recovery: $message")
                },
            )
            if (!transportHealthy && !userRequestedDisconnect) {
                appContainer.vpnConnectionRepository.appendDiagnostic(
                    "Wake recovery: SSH transport is stale; reconnecting",
                )
                appContainer.sshConnectionManager.disconnect()
            }
        }
    }

    private suspend fun runConnectionLoop(
        runId: Long,
        preserveDiagnostics: Boolean,
    ) {
        val configRepository = appContainer.sshConfigRepository
        val keyRepository = appContainer.sshPrivateKeyRepository
        val connectionRepository = appContainer.vpnConnectionRepository

        val config = configRepository.getSelectedConfig()
        if (config == null) {
            connectionRepository.setError(null, "No configuration selected")
            stopSelf()
            return
        }

        try {
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
            startVpnForeground()
            connectionRepository.appendDiagnostic("Foreground VPN service started")
            val appSettings = appContainer.appSettingsRepository.settings.value
            validateAppSettings(appSettings)
            val privateKey = loadPrivateKey(config, runId, keyRepository::getById)

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
                    connectionRepository.setReconnecting(config.id)
                    connectionRepository.appendDiagnostic("Reconnect attempt $attempt starting")
                }

                var activeConnectionInterrupted = false
                try {
                    val reuseVpnInterface = everConnected && canReuseVpnPipeline()
                    val connection = connectSingleAttempt(
                        config = config,
                        privateKey = privateKey,
                        appSettings = appSettings,
                        runId = runId,
                        reuseVpnInterface = reuseVpnInterface && !forceVpnRebuildOnNextAttempt,
                        includeNetworkDiagnostics = attempt == 1 ||
                            attempt % NETWORK_DIAGNOSTICS_RETRY_INTERVAL == 0,
                    )
                    everConnected = true
                    forceVpnRebuildOnNextAttempt = false
                    reconnectBackoff.reset()
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

                    val interruptReason = monitorActiveConnection(connection.sshSession, runId)
                    if (!shouldKeepConnectionAlive(runId)) {
                        break
                    }
                    activeConnectionInterrupted = true
                    forceVpnRebuildOnNextAttempt = interruptReason.forceVpnRebuild
                    reconnectStartedAtMs = SystemClock.elapsedRealtime()
                    connectionRepository.setReconnecting(config.id)
                    connectionRepository.appendDiagnostic("Connection interrupted: ${interruptReason.message}")
                } catch (error: CancellationException) {
                    disconnectInternal(updateState = false, stopForegroundNotification = false)
                    break
                } catch (error: VpnConnectionException) {
                    if (!shouldKeepConnectionAlive(runId)) {
                        break
                    }
                    if (error.isRecoverableBeforeFirstConnection() || everConnected) {
                        connectionRepository.setReconnecting(config.id)
                    }
                    logConnectionAttemptFailure(
                        attempt = attempt,
                        errorMessage = error.message ?: "Unknown connection error",
                        causeMessage = error.cause?.message,
                    )
                    if (!everConnected && !error.isRecoverableBeforeFirstConnection()) {
                        connectionRepository.setError(config.id, error.message ?: "Unknown connection error")
                        disconnectInternal(updateState = false)
                        stopSelf()
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
                    keepVpnPipeline = everConnected && !forceVpnRebuildOnNextAttempt,
                    announceHotReconnect = activeConnectionInterrupted,
                )
                if (!shouldKeepConnectionAlive(runId)) {
                    break
                }
                connectionRepository.setReconnecting(config.id)
                if (activeConnectionInterrupted) {
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
        } finally {
            if (connectionRunId == runId) {
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
        reuseVpnInterface: Boolean,
        includeNetworkDiagnostics: Boolean,
    ): ConnectionAttempt {
        val connectionRepository = appContainer.vpnConnectionRepository
        ensureConnectionStillWanted(runId)
        validateAppSettings(appSettings)
        if (includeNetworkDiagnostics) {
            NetworkDiagnostics.describe(this@SshVpnService).forEach { message ->
                appendConnectionDiagnostic(runId, message)
            }
        }
        val log = connectionLogger(runId)
        val sshSession = appContainer.sshConnectionManager.connect(
            config = config,
            privateKey = privateKey,
            log = log,
            socketProtector = { socket -> protect(socket) },
            connectTimeoutMs = if (reuseVpnInterface) RECONNECT_CONNECT_TIMEOUT_MS else INITIAL_CONNECT_TIMEOUT_MS,
            verboseDiagnostics = includeNetworkDiagnostics,
        )
        ensureConnectionStillWanted(runId)
        val reusedVpnInterface = reuseVpnInterface && canReuseVpnPipeline()
        if (reusedVpnInterface) {
            connectionRepository.appendDiagnostic("Resuming forwarding on existing Android VPN interface")
            appContainer.tun2SocksManager.resumeSshTransport(sshSession)
        } else {
            if (reuseVpnInterface) {
                connectionRepository.appendDiagnostic(
                    "Existing VPN pipeline is unavailable; rebuilding Android VPN interface",
                )
            }
            cleanupVpnPipelineForRebuild()
            connectionRepository.appendDiagnostic("Establishing Android VPN interface")
            val vpnInterface = appContainer.vpnTunnelManager.establish(
                service = this@SshVpnService,
                config = config,
                appSettings = appSettings,
                log = connectionRepository::appendDiagnostic,
            )
            ensureConnectionStillWanted(runId)
            connectionRepository.appendDiagnostic("Starting local TUN forwarding layer")
            appContainer.tun2SocksManager.start(
                vpnInterface = vpnInterface,
                sshSession = sshSession,
                enableUdpForwarding = config.enableUdpForwarding,
                log = connectionRepository::appendDiagnostic,
            )
        }
        ensureConnectionStillWanted(runId)
        connectionRepository.appendDiagnostic("VPN connection is connected")
        connectionRepository.setConnected(config.id)
        return ConnectionAttempt(
            sshSession = sshSession,
            reusedVpnInterface = reusedVpnInterface,
        )
    }

    private suspend fun monitorActiveConnection(
        sshSession: Session,
        runId: Long,
    ): ActiveConnectionInterrupt {
        while (shouldKeepConnectionAlive(runId)) {
            delay(CONNECTION_MONITOR_INTERVAL_MS)
            if (!appContainer.tun2SocksManager.isRunning) {
                return ActiveConnectionInterrupt(
                    message = "TUN forwarding stopped",
                    forceVpnRebuild = true,
                )
            }
            val degradationReason = appContainer.tun2SocksManager.consumeDegradationReason()
            if (degradationReason != null) {
                return ActiveConnectionInterrupt(
                    message = "TUN forwarding degraded: $degradationReason",
                    forceVpnRebuild = true,
                )
            }
            if (!sshSession.isConnected) {
                return ActiveConnectionInterrupt(
                    message = "SSH session disconnected",
                    forceVpnRebuild = false,
                )
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
        if (runId != null && connectionRunId != runId) return
        appContainer.vpnConnectionRepository.appendDiagnostic(message)
    }

    private fun shouldKeepConnectionAlive(runId: Long): Boolean {
        return connectionRunId == runId && !userRequestedDisconnect
    }

    private fun ensureConnectionStillWanted(runId: Long) {
        if (!shouldKeepConnectionAlive(runId)) {
            throw CancellationException("Connection run stopped")
        }
    }

    private fun validateAppSettings(appSettings: AppSettings) {
        if (appSettings.vpnMode == VpnMode.SELECTED_APPS && appSettings.selectedAppPackages.isEmpty()) {
            throw VpnConnectionException("No apps selected")
        }
    }

    private fun prepareForReconnect(
        keepVpnPipeline: Boolean,
        announceHotReconnect: Boolean,
    ) {
        if (keepVpnPipeline && canReuseVpnPipeline()) {
            if (announceHotReconnect) {
                appContainer.vpnConnectionRepository.appendDiagnostic(
                    "Keeping Android VPN interface active while SSH transport reconnects",
                )
            }
            cleanupDisconnectStep("TUN SSH transport") {
                appContainer.tun2SocksManager.pauseSshTransport()
            }
            cleanupDisconnectStep("SSH session") {
                appContainer.sshConnectionManager.disconnect()
            }
        } else {
            disconnectInternal(updateState = false, stopForegroundNotification = false)
        }
    }

    private fun canReuseVpnPipeline(): Boolean {
        return appContainer.vpnTunnelManager.isEstablished && appContainer.tun2SocksManager.isRunning
    }

    private fun disconnect() {
        serviceScope.launch {
            userRequestedDisconnect = true
            connectionRunId += 1
            connectionJob?.cancel()
            connectionJob = null
            appContainer.vpnConnectionRepository.appendDiagnostic("Stopping VPN connection")
            appContainer.vpnConnectionRepository.setDisconnecting(null)
            disconnectInternal(updateState = true)
            appContainer.vpnConnectionRepository.appendDiagnostic("VPN connection disconnected")
            stopSelf()
        }
    }

    private fun disconnectInternal(
        updateState: Boolean,
        stopForegroundNotification: Boolean = true,
    ) {
        cleanupVpnPipelineForRebuild()
        cleanupDisconnectStep("SSH session") {
            appContainer.sshConnectionManager.disconnect()
        }
        if (updateState) {
            appContainer.vpnConnectionRepository.setDisconnected()
        }
        if (stopForegroundNotification) {
            cleanupDisconnectStep("foreground notification") {
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
        }
    }

    private fun cleanupVpnPipelineForRebuild() {
        cleanupDisconnectStep("TUN forwarding") {
            appContainer.tun2SocksManager.stop()
        }
        cleanupDisconnectStep("VPN interface") {
            appContainer.vpnTunnelManager.close()
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
        private const val CONNECTION_MONITOR_INTERVAL_MS = 2_000L
        private const val INITIAL_CONNECT_TIMEOUT_MS = 20_000
        private const val RECONNECT_CONNECT_TIMEOUT_MS = 8_000
        private const val INITIAL_RECONNECT_DELAY_MS = 250L
        private const val MAX_RECONNECT_DELAY_MS = 5_000L
        private const val NETWORK_DIAGNOSTICS_RETRY_INTERVAL = 5
        private const val MINIMUM_SCREEN_OFF_RECOVERY_MS = 60_000L
        private const val WAKE_STALE_CONNECTION_IDLE_MS = 30_000L
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
        value.contains("Unknown connection error", ignoreCase = true) ||
        value.contains("TUN forwarding", ignoreCase = true)
}
