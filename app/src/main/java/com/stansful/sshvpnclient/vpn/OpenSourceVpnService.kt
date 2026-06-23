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
import com.stansful.sshvpnclient.domain.model.VpnMode
import com.stansful.sshvpnclient.domain.model.VpnTransportType
import com.stansful.sshvpnclient.xray.XrayCoreBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class OpenSourceVpnService : android.net.VpnService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connectionJob: Job? = null
    @Volatile
    private var userRequestedDisconnect = true

    private val appContainer
        get() = (application as SshVpnApplication).container

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> connect(intent.getStringExtra(EXTRA_PROFILE_ID))
            ACTION_DISCONNECT -> disconnect()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        userRequestedDisconnect = true
        connectionJob?.cancel()
        cleanup()
        if (appContainer.vpnConnectionRepository.currentState.activeTransport == VpnTransportType.XRAY) {
            appContainer.vpnConnectionRepository.setDisconnected()
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun connect(profileId: String?) {
        connectionJob?.cancel()
        userRequestedDisconnect = false
        connectionJob = serviceScope.launch { runConnectionLoop(profileId) }
    }

    private suspend fun runConnectionLoop(profileId: String?) {
        val repository = appContainer.vpnConnectionRepository
        val profile = profileId?.let { appContainer.proxyProfileRepository.getById(it) }
        if (profile == null) {
            repository.setError(profileId, "No opensource configuration selected")
            stopSelf()
            return
        }
        if (!appContainer.xrayCoreBridge.isAvailable) {
            repository.setError(profile.id, XrayCoreBridge.CORE_UNAVAILABLE_MESSAGE)
            stopSelf()
            return
        }
        val settings = appContainer.appSettingsRepository.settings.value
        if (settings.vpnMode == VpnMode.SELECTED_APPS && settings.selectedAppPackages.isEmpty()) {
            repository.setError(profile.id, getString(R.string.error_no_selected_apps))
            stopSelf()
            return
        }

        repository.setConnecting(profile.id, VpnTransportType.XRAY)
        repository.appendDiagnostic("Starting opensource VPN connection")
        repository.appendDiagnostic(
            "Selected public profile: ${profile.protocol.name}/${profile.transport.name} " +
                "${profile.host}:${profile.port}",
        )
        startVpnForeground(profile.name)
        val backoff = ReconnectBackoff(INITIAL_RECONNECT_DELAY_MS, MAX_RECONNECT_DELAY_MS)
        var attempt = 1

        while (!userRequestedDisconnect) {
            try {
                if (attempt > 1) {
                    repository.setReconnecting(profile.id, VpnTransportType.XRAY)
                    repository.appendDiagnostic("Xray reconnect attempt $attempt")
                }
                cleanup()
                val vpnInterface = appContainer.vpnTunnelManager.establish(
                    service = this,
                    sessionName = "opensource: ${profile.name}",
                    appSettings = settings,
                    log = repository::appendDiagnostic,
                )
                appContainer.xrayCoreBridge.startTun(
                    profile = profile,
                    tunFd = vpnInterface.fd,
                    protectSocket = ::protect,
                )
                repository.setConnected(profile.id, VpnTransportType.XRAY)
                repository.appendDiagnostic("Xray VPN connection is connected")
                backoff.reset()
                while (!userRequestedDisconnect && appContainer.xrayCoreBridge.isRunning()) {
                    delay(CONNECTION_MONITOR_INTERVAL_MS)
                }
                if (!userRequestedDisconnect) error("Xray core stopped unexpectedly")
            } catch (error: CancellationException) {
                break
            } catch (error: Exception) {
                if (userRequestedDisconnect) break
                repository.setReconnecting(profile.id, VpnTransportType.XRAY)
                repository.appendDiagnostic(
                    "Xray connection failed: ${error.message ?: error::class.java.simpleName}",
                )
                cleanup()
                val reconnectDelay = backoff.nextFailureDelayMs()
                repository.appendDiagnostic("Xray reconnecting in ${reconnectDelay}ms")
                delay(reconnectDelay)
                attempt += 1
            }
        }
    }

    private fun disconnect() {
        userRequestedDisconnect = true
        connectionJob?.cancel()
        connectionJob = null
        if (appContainer.vpnConnectionRepository.currentState.activeTransport == VpnTransportType.XRAY) {
            appContainer.vpnConnectionRepository.setDisconnecting(null)
        }
        serviceScope.launch {
            cleanup()
            if (appContainer.vpnConnectionRepository.currentState.activeTransport == VpnTransportType.XRAY) {
                appContainer.vpnConnectionRepository.setDisconnected()
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun cleanup() {
        runCatching { appContainer.xrayCoreBridge.stopBlocking() }
        runCatching { appContainer.vpnTunnelManager.close() }
    }

    private fun startVpnForeground(profileName: String) {
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
            .setContentText(getString(R.string.vpn_notification_public_profile_text, profileName))
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
        private const val CONNECTION_MONITOR_INTERVAL_MS = 2_000L
        private const val INITIAL_RECONNECT_DELAY_MS = 250L
        private const val MAX_RECONNECT_DELAY_MS = 5_000L

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
