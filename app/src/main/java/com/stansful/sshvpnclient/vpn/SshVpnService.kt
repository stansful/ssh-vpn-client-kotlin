package com.stansful.sshvpnclient.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.stansful.sshvpnclient.R
import com.stansful.sshvpnclient.SshVpnApplication
import com.stansful.sshvpnclient.domain.model.AuthType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class SshVpnService : android.net.VpnService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val appContainer
        get() = (application as SshVpnApplication).container

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> connect()
            ACTION_DISCONNECT -> disconnect()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    override fun onDestroy() {
        disconnectInternal(updateState = false)
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun connect() {
        serviceScope.launch {
            val configRepository = appContainer.sshConfigRepository
            val keyRepository = appContainer.sshPrivateKeyRepository
            val connectionRepository = appContainer.vpnConnectionRepository

            val config = configRepository.getSelectedConfig()
            if (config == null) {
                connectionRepository.setError(null, "No configuration selected")
                stopSelf()
                return@launch
            }

            connectionRepository.setConnecting(config.id)
            connectionRepository.appendDiagnostic("Starting VPN connection")
            connectionRepository.appendDiagnostic(
                "Selected config: ${config.username}@${config.host}:${config.port}",
            )
            connectionRepository.appendDiagnostic("Auth type: ${config.authType.label}")
            startVpnForeground()
            connectionRepository.appendDiagnostic("Foreground VPN service started")

            try {
                val privateKey = if (config.authType == AuthType.PRIVATE_KEY) {
                    val keyId = config.privateKeyId
                    if (keyId.isNullOrBlank()) {
                        throw VpnConnectionException("Selected SSH key not found")
                    }
                    connectionRepository.appendDiagnostic("Looking up selected SSH key")
                    keyRepository.getById(keyId)
                        ?: throw VpnConnectionException("Selected SSH key not found")
                } else {
                    null
                }

                val sshSession = appContainer.sshConnectionManager.connect(
                    config = config,
                    privateKey = privateKey,
                    log = connectionRepository::appendDiagnostic,
                    socketProtector = { socket -> protect(socket) },
                )
                connectionRepository.appendDiagnostic("Establishing Android VPN interface")
                val vpnInterface = appContainer.vpnTunnelManager.establish(this@SshVpnService, config)
                connectionRepository.appendDiagnostic("Starting local TUN forwarding layer")
                appContainer.tun2SocksManager.start(
                    context = this@SshVpnService,
                    vpnInterface = vpnInterface,
                    sshSession = sshSession,
                    enableUdpForwarding = config.enableUdpForwarding,
                    log = connectionRepository::appendDiagnostic,
                )
                connectionRepository.appendDiagnostic("VPN connection is connected")
                connectionRepository.setConnected(config.id)
            } catch (error: VpnConnectionException) {
                connectionRepository.appendDiagnostic("Connection failed: ${error.message}")
                error.cause?.message?.let { causeMessage ->
                    connectionRepository.appendDiagnostic("Failure detail: $causeMessage")
                }
                connectionRepository.setError(config.id, error.message ?: "Unknown connection error")
                disconnectInternal(updateState = false)
                stopSelf()
            } catch (error: Exception) {
                connectionRepository.appendDiagnostic(
                    "Connection failed with ${error::class.java.simpleName}: ${error.message}",
                )
                connectionRepository.setError(config.id, "Unknown connection error")
                disconnectInternal(updateState = false)
                stopSelf()
            }
        }
    }

    private fun disconnect() {
        serviceScope.launch {
            appContainer.vpnConnectionRepository.appendDiagnostic("Stopping VPN connection")
            appContainer.vpnConnectionRepository.setDisconnecting(null)
            disconnectInternal(updateState = true)
            appContainer.vpnConnectionRepository.appendDiagnostic("VPN connection disconnected")
            stopSelf()
        }
    }

    private fun disconnectInternal(updateState: Boolean) {
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
        cleanupDisconnectStep("foreground notification") {
            stopForeground(STOP_FOREGROUND_REMOVE)
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
        private const val CHANNEL_ID = "ssh_vpn_connection"
        private const val NOTIFICATION_ID = 3001

        fun connectIntent(context: Context): Intent {
            return Intent(context, SshVpnService::class.java).setAction(ACTION_CONNECT)
        }

        fun disconnectIntent(context: Context): Intent {
            return Intent(context, SshVpnService::class.java).setAction(ACTION_DISCONNECT)
        }
    }
}
