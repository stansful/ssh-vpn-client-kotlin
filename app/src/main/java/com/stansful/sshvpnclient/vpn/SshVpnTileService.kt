package com.stansful.sshvpnclient.vpn

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.stansful.sshvpnclient.MainActivity
import com.stansful.sshvpnclient.R
import com.stansful.sshvpnclient.SshVpnApplication
import com.stansful.sshvpnclient.domain.model.GlobalTab
import com.stansful.sshvpnclient.domain.model.VpnConnectionState
import com.stansful.sshvpnclient.domain.model.VpnConnectionStatus
import com.stansful.sshvpnclient.domain.model.VpnSessionOwner
import com.stansful.sshvpnclient.ui.shell.Destinations
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SshVpnTileService : TileService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listeningJob: Job? = null
    private var clickJob: Job? = null

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
            tileInputs().collect { (state, lastOwner) -> updateTile(state, lastOwner) }
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
        // A second tap while the first one is still resolving would start or stop a second time.
        if (clickJob?.isActive == true) return
        clickJob = serviceScope.launch {
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

    /** The preconditions, the decision and the start itself are shared with Always-on VPN. */
    private suspend fun connectOrOpenApp() {
        val starter = appContainer.lastVpnModeStarter
        val target = quickTileConnectTarget(appContainer.lastVpnSessionStore.lastOwner.value)
        val check = starter.check(target)
        when (val plan = resolveQuickTileConnectPlan(check.preconditions)) {
            is QuickTileConnectPlan.Connect -> {
                if (starter.connect(plan.owner, check.failureConfigId) != null) {
                    openMainActivity(plan.owner.globalTab())
                }
            }
            is QuickTileConnectPlan.OpenApp -> {
                plan.reason.messageRes()?.let { messageRes ->
                    starter.publishStartFailure(
                        target,
                        check.failureConfigId,
                        getString(messageRes),
                        connectAttempted = false,
                    )
                }
                openMainActivity(plan.tab)
            }
        }
    }

    private fun disconnect() {
        appContainer.disconnectVpnUseCase()
    }

    private fun tileInputs() = combine(
        appContainer.vpnConnectionRepository.state,
        appContainer.lastVpnSessionStore.lastOwner,
    ) { state, lastOwner -> state to lastOwner }

    private fun refreshTileOnce() {
        serviceScope.launch {
            val (state, lastOwner) = tileInputs().first()
            updateTile(state, lastOwner)
        }
    }

    private fun updateTile(state: VpnConnectionState, lastOwner: VpnSessionOwner?) {
        val tile = qsTile ?: return
        val status = state.status
        tile.label = getString(R.string.qs_tile_label)
        tile.icon = Icon.createWithResource(this, quickTileIconRes(status))
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
            tile.subtitle = getString(
                R.string.qs_tile_subtitle,
                getString(quickTileSubtitleMode(state, lastOwner).tileModeLabelRes()),
                getString(status.tileSubtitleRes()),
            )
        }
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openMainActivity(tab: GlobalTab) {
        // Home's mode switch observes the stored tab; the destination extra brings an already
        // running activity back to Home so the switched mode (and its error) is visible.
        appContainer.appSettingsRepository.setActiveGlobalTab(tab)
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_DESTINATION, Destinations.HOME)

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

    private fun QuickTileOpenAppReason.messageRes(): Int? {
        return when (this) {
            QuickTileOpenAppReason.CONSENT_REQUIRED -> null
            QuickTileOpenAppReason.NO_SSH_CONFIG -> R.string.qs_tile_no_config
            QuickTileOpenAppReason.SSH_KEY_MISSING -> R.string.qs_tile_ssh_key_missing
            QuickTileOpenAppReason.NO_PUBLIC_ROUTE -> R.string.qs_tile_no_public_route
            QuickTileOpenAppReason.XRAY_CORE_MISSING -> R.string.qs_tile_xray_core_missing
            QuickTileOpenAppReason.NO_SELECTED_APPS -> R.string.qs_tile_no_selected_apps
            QuickTileOpenAppReason.VPN_PERMISSION_REQUIRED -> R.string.qs_tile_permission_required
        }
    }

    private fun VpnSessionOwner.tileModeLabelRes(): Int {
        return when (this) {
            VpnSessionOwner.SHADOW_SSH -> R.string.qs_tile_mode_ssh
            VpnSessionOwner.OPEN_SOURCE -> R.string.qs_tile_mode_public
            VpnSessionOwner.SMART_CONNECT -> R.string.qs_tile_mode_smart
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
