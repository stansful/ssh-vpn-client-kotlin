package com.stansful.sshvpnclient.vpn

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.stansful.sshvpnclient.MainActivity
import com.stansful.sshvpnclient.R
import com.stansful.sshvpnclient.SshVpnApplication
import com.stansful.sshvpnclient.domain.model.VpnConnectionState
import com.stansful.sshvpnclient.domain.model.VpnConnectionStatus
import com.stansful.sshvpnclient.domain.model.VpnMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SshVpnTileService : TileService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listeningJob: Job? = null

    private val appContainer
        get() = (application as SshVpnApplication).container

    override fun onTileAdded() {
        super.onTileAdded()
        refreshTileOnce()
    }

    override fun onStartListening() {
        super.onStartListening()
        listeningJob?.cancel()
        listeningJob = serviceScope.launch {
            appContainer.vpnConnectionRepository.state.collect(::updateTile)
        }
    }

    override fun onStopListening() {
        listeningJob?.cancel()
        listeningJob = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) {
            unlockAndRun { handleTileClick() }
        } else {
            handleTileClick()
        }
    }

    override fun onDestroy() {
        listeningJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun handleTileClick() {
        serviceScope.launch {
            val state = appContainer.vpnConnectionRepository.state.first()
            when (state.status) {
                VpnConnectionStatus.CONNECTING,
                VpnConnectionStatus.CONNECTED,
                VpnConnectionStatus.RECONNECTING,
                VpnConnectionStatus.DISCONNECTING,
                -> disconnect()

                VpnConnectionStatus.DISCONNECTED,
                VpnConnectionStatus.ERROR,
                -> connectOrOpenApp()
            }
            refreshTileOnce()
        }
    }

    private suspend fun connectOrOpenApp() {
        val config = appContainer.sshConfigRepository.getSelectedConfig()
        if (config == null) {
            appContainer.vpnConnectionRepository.setError(null, getString(R.string.qs_tile_no_config))
            openMainActivity()
            return
        }

        val settings = appContainer.appSettingsRepository.settings.value
        if (settings.vpnMode == VpnMode.SELECTED_APPS && settings.selectedAppPackages.isEmpty()) {
            appContainer.vpnConnectionRepository.setError(config.id, getString(R.string.qs_tile_no_selected_apps))
            openMainActivity()
            return
        }

        if (VpnService.prepare(this) != null) {
            appContainer.vpnConnectionRepository.setError(config.id, getString(R.string.qs_tile_permission_required))
            openMainActivity()
            return
        }

        runCatching {
            appContainer.connectVpnUseCase()
        }.onFailure { error ->
            appContainer.vpnConnectionRepository.setError(
                config.id,
                error.message ?: getString(R.string.qs_tile_unknown_error),
            )
            openMainActivity()
        }
    }

    private fun disconnect() {
        appContainer.disconnectVpnUseCase()
    }

    private fun refreshTileOnce() {
        serviceScope.launch {
            updateTile(appContainer.vpnConnectionRepository.state.first())
        }
    }

    private fun updateTile(state: VpnConnectionState) {
        val tile = qsTile ?: return
        val status = state.status
        tile.label = getString(R.string.qs_tile_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_launcher_foreground)
        tile.state = when (status) {
            VpnConnectionStatus.CONNECTED,
            VpnConnectionStatus.CONNECTING,
            VpnConnectionStatus.RECONNECTING,
            -> Tile.STATE_ACTIVE

            VpnConnectionStatus.DISCONNECTING -> Tile.STATE_UNAVAILABLE
            VpnConnectionStatus.DISCONNECTED,
            VpnConnectionStatus.ERROR,
            -> Tile.STATE_INACTIVE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(status.tileSubtitleRes())
        }
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                MAIN_ACTIVITY_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun VpnConnectionStatus.tileSubtitleRes(): Int {
        return when (this) {
            VpnConnectionStatus.CONNECTED -> R.string.qs_tile_connected
            VpnConnectionStatus.CONNECTING -> R.string.qs_tile_connecting
            VpnConnectionStatus.RECONNECTING -> R.string.qs_tile_reconnecting
            VpnConnectionStatus.DISCONNECTING -> R.string.qs_tile_disconnecting
            VpnConnectionStatus.ERROR -> R.string.qs_tile_error
            VpnConnectionStatus.DISCONNECTED -> R.string.qs_tile_disconnected
        }
    }

    private companion object {
        const val MAIN_ACTIVITY_REQUEST_CODE = 4101
    }
}
