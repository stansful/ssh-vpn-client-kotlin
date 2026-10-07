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
import com.stansful.sshvpnclient.domain.model.AppSettings
import com.stansful.sshvpnclient.domain.model.AuthType
import com.stansful.sshvpnclient.domain.model.GlobalTab
import com.stansful.sshvpnclient.domain.model.OpenSourcePolicy
import com.stansful.sshvpnclient.domain.model.SshConfig
import com.stansful.sshvpnclient.domain.model.VpnConnectionState
import com.stansful.sshvpnclient.domain.model.VpnConnectionStatus
import com.stansful.sshvpnclient.domain.model.VpnMode
import com.stansful.sshvpnclient.domain.model.VpnSessionOwner
import com.stansful.sshvpnclient.ui.shell.Destinations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    private suspend fun connectOrOpenApp() {
        val target = quickTileConnectTarget(appContainer.lastVpnSessionStore.lastOwner.value)
        val settings = appContainer.appSettingsRepository.settings.value
        val selectedAppsReady = settings.vpnMode != VpnMode.SELECTED_APPS ||
            settings.selectedAppPackages.isNotEmpty()
        val vpnPermissionGranted = VpnService.prepare(this) == null
        val failureConfigId: String?
        val preconditions = when (target) {
            VpnSessionOwner.SHADOW_SSH -> {
                val config = appContainer.sshConfigRepository.getSelectedConfig()
                failureConfigId = config?.id
                QuickTilePreconditions.ShadowSsh(
                    configSelected = config != null,
                    privateKeyReady = config != null && hasUsablePrivateKey(config),
                    selectedAppsReady = selectedAppsReady,
                    vpnPermissionGranted = vpnPermissionGranted,
                )
            }

            VpnSessionOwner.OPEN_SOURCE -> {
                val profile = appContainer.proxyProfileRepository.getSelected()
                failureConfigId = profile?.id
                QuickTilePreconditions.OpenSource(
                    consentAccepted = settings.openSourceConsentVersion >= OpenSourcePolicy.CONSENT_VERSION,
                    routeSelected = profile != null,
                    xrayCoreAvailable = isXrayCoreAvailable(),
                    selectedAppsReady = selectedAppsReady,
                    vpnPermissionGranted = vpnPermissionGranted,
                )
            }

            VpnSessionOwner.SMART_CONNECT -> {
                failureConfigId = null
                QuickTilePreconditions.SmartConnect(
                    consentAccepted = settings.smartConnectConsentAccepted(),
                    xrayCoreAvailable = isXrayCoreAvailable(),
                    selectedAppsReady = selectedAppsReady,
                    vpnPermissionGranted = vpnPermissionGranted,
                )
            }
        }

        when (val plan = resolveQuickTileConnectPlan(preconditions)) {
            is QuickTileConnectPlan.Connect -> connect(plan.owner, failureConfigId)
            is QuickTileConnectPlan.OpenApp -> {
                plan.reason.messageRes()?.let { messageRes ->
                    publishStartFailure(
                        target,
                        failureConfigId,
                        getString(messageRes),
                        connectAttempted = false,
                    )
                }
                openMainActivity(plan.tab)
            }
        }
    }

    private suspend fun connect(owner: VpnSessionOwner, failureConfigId: String?) {
        runCatching {
            when (owner) {
                VpnSessionOwner.SHADOW_SSH -> appContainer.connectVpnUseCase()
                VpnSessionOwner.OPEN_SOURCE -> appContainer.connectProxyVpnUseCase()
                VpnSessionOwner.SMART_CONNECT -> appContainer.connectSmartVpnUseCase()
            }
        }.onFailure { error ->
            if (error is CancellationException) throw error
            publishStartFailure(
                owner,
                failureConfigId,
                error.message ?: getString(R.string.qs_tile_unknown_error),
                connectAttempted = true,
            )
            openMainActivity(owner.globalTab())
        }
    }

    /** ConnectVpnUseCase rejects a missing key too, but returns normally, so the tile would not open the app. */
    private suspend fun hasUsablePrivateKey(config: SshConfig): Boolean {
        if (config.authType != AuthType.PRIVATE_KEY) return true
        val keyId = config.privateKeyId
        return !keyId.isNullOrBlank() && appContainer.sshPrivateKeyRepository.getById(keyId) != null
    }

    // Off Main: the first check loads the installed core through a class loader.
    private suspend fun isXrayCoreAvailable(): Boolean = withContext(Dispatchers.IO) {
        appContainer.xrayCoreBridge.isAvailable
    }

    private fun publishStartFailure(
        target: VpnSessionOwner,
        configId: String?,
        message: String,
        connectAttempted: Boolean,
    ) {
        val repository = appContainer.vpnConnectionRepository
        if (!canPublishQuickTileStartFailure(repository.currentState, target, connectAttempted)) return
        if (target == VpnSessionOwner.SMART_CONNECT) {
            // The Smart tab shows its workflow message; a bare VPN error without an owner is hidden there.
            appContainer.smartConnectStateStore.fail(message, keepDesiredActive = false)
        }
        repository.setError(configId, message)
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

    // Mirrors the shell's ConsentGate (Auto rule): consent the Smart tab would not ask for must not block
    // the tile, or every click would open the app and nothing there would unblock it.
    private fun AppSettings.smartConnectConsentAccepted(): Boolean {
        return !showSmartConnectWarningOnEnter ||
            smartConnectConsentVersion >= OpenSourcePolicy.CONSENT_VERSION
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
