package com.stansful.sshvpnclient.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
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
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var connectionJob: Job? = null
    private var connectionRunId: Long = 0L
    private var userRequestedDisconnect: Boolean = true

    private val appContainer
        get() = (application as SshVpnApplication).container

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

            var attempt = 1
            var everConnected = false
            var reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
            while (shouldKeepConnectionAlive(runId)) {
                if (attempt > 1) {
                    connectionRepository.setReconnecting(config.id)
                    connectionRepository.appendDiagnostic("Reconnect attempt $attempt starting")
                }

                try {
                    val privateKey = loadPrivateKey(config, runId, keyRepository::getById)
                    val sshSession = connectSingleAttempt(config, privateKey, runId)
                    everConnected = true
                    reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS

                    val interruptReason = monitorActiveConnection(sshSession, runId)
                    if (!shouldKeepConnectionAlive(runId)) {
                        break
                    }
                    connectionRepository.appendDiagnostic("Connection interrupted: $interruptReason")
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

                disconnectInternal(updateState = false, stopForegroundNotification = false)
                if (!shouldKeepConnectionAlive(runId)) {
                    break
                }
                connectionRepository.setReconnecting(config.id)
                connectionRepository.appendDiagnostic(
                    "Reconnecting in ${reconnectDelayMs / 1000}s; press Disconnect to stop",
                )
                delay(reconnectDelayMs)
                reconnectDelayMs = minOf(reconnectDelayMs * 2, MAX_RECONNECT_DELAY_MS)
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
        return getKeyById(keyId) ?: throw VpnConnectionException("Selected SSH key not found")
    }

    private suspend fun connectSingleAttempt(
        config: SshConfig,
        privateKey: SshPrivateKey?,
        runId: Long,
    ): Session {
        val connectionRepository = appContainer.vpnConnectionRepository
        val appSettings = appContainer.appSettingsRepository.settings.value
        ensureConnectionStillWanted(runId)
        validateAppSettings(appSettings)
        NetworkDiagnostics.describe(this@SshVpnService).forEach { message ->
            appendConnectionDiagnostic(runId, message)
        }
        val log = connectionLogger(runId)
        val sshSession = appContainer.sshConnectionManager.connect(
            config = config,
            privateKey = privateKey,
            log = log,
            socketProtector = { socket -> protect(socket) },
        )
        ensureConnectionStillWanted(runId)
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
            context = this@SshVpnService,
            vpnInterface = vpnInterface,
            sshSession = sshSession,
            enableUdpForwarding = config.enableUdpForwarding,
            log = connectionRepository::appendDiagnostic,
        )
        ensureConnectionStillWanted(runId)
        connectionRepository.appendDiagnostic("VPN connection is connected")
        connectionRepository.setConnected(config.id)
        return sshSession
    }

    private suspend fun monitorActiveConnection(
        sshSession: Session,
        runId: Long,
    ): String {
        while (shouldKeepConnectionAlive(runId)) {
            delay(CONNECTION_MONITOR_INTERVAL_MS)
            if (!sshSession.isConnected) {
                return "SSH session disconnected"
            }
        }
        return "Connection stopped"
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
            throw VpnConnectionException("Нет выбранных приложений")
        }
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
        cleanupDisconnectStep("TUN forwarding") {
            appContainer.tun2SocksManager.stop()
        }
        cleanupDisconnectStep("VPN interface") {
            appContainer.vpnTunnelManager.close()
        }
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
        private const val CONNECTION_MONITOR_INTERVAL_MS = 5_000L
        private const val INITIAL_RECONNECT_DELAY_MS = 2_000L
        private const val MAX_RECONNECT_DELAY_MS = 30_000L

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

private fun VpnConnectionException.isRecoverableBeforeFirstConnection(): Boolean {
    val value = message.orEmpty()
    return value.contains("timeout", ignoreCase = true) ||
        value.contains("Host unreachable", ignoreCase = true) ||
        value.contains("Unknown connection error", ignoreCase = true) ||
        value.contains("Could not start local SSH SOCKS bridge", ignoreCase = true)
}
