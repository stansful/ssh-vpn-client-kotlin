package com.stansful.sshvpnclient.vpn

import android.content.Context
import android.net.VpnService
import com.stansful.sshvpnclient.R
import com.stansful.sshvpnclient.data.local.SmartConnectStateStore
import com.stansful.sshvpnclient.domain.model.AppSettings
import com.stansful.sshvpnclient.domain.model.AuthType
import com.stansful.sshvpnclient.domain.model.OpenSourcePolicy
import com.stansful.sshvpnclient.domain.model.SshConfig
import com.stansful.sshvpnclient.domain.model.VpnMode
import com.stansful.sshvpnclient.domain.model.VpnSessionOwner
import com.stansful.sshvpnclient.domain.repository.AppSettingsRepository
import com.stansful.sshvpnclient.domain.repository.ProxyProfileRepository
import com.stansful.sshvpnclient.domain.repository.SshConfigRepository
import com.stansful.sshvpnclient.domain.repository.SshPrivateKeyRepository
import com.stansful.sshvpnclient.domain.repository.VpnConnectionRepository
import com.stansful.sshvpnclient.domain.usecase.vpn.ConnectProxyVpnUseCase
import com.stansful.sshvpnclient.domain.usecase.vpn.ConnectSmartVpnUseCase
import com.stansful.sshvpnclient.domain.usecase.vpn.ConnectVpnUseCase
import com.stansful.sshvpnclient.xray.XrayCoreBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What [LastVpnModeStarter.check] found out about a mode, and the config a failure is reported for. */
internal data class VpnModeStartCheck(
    val preconditions: QuickTilePreconditions,
    val failureConfigId: String?,
)

/** Checks and starts a recorded mode: [LastVpnModeStarter]; [AlwaysOnVpnStarter] depends on this much. */
internal interface VpnModeStarter {
    suspend fun check(target: VpnSessionOwner): VpnModeStartCheck

    /**
     * Starts [owner] through its tab's use case. A message when the start failed and the connection
     * state may not say so: the use case threw (published as the error where that is safe), or a Server
     * start found another session owning the state ([unpublishedServerStartFailure]); else null.
     */
    suspend fun connect(owner: VpnSessionOwner, failureConfigId: String?): String?
}

/**
 * Starts a mode with no tab in front of it: the quick settings tile and Always-on VPN
 * ([AlwaysOnVpnStarter]) both bring back the mode recorded in LastVpnSessionStore. The Android lookups
 * live here, the decision is [resolveQuickTileConnectPlan], and the start goes through the same use
 * case the mode's tab uses. Called on the main thread, like the tabs call those use cases.
 */
internal class LastVpnModeStarter(
    context: Context,
    private val appSettingsRepository: AppSettingsRepository,
    private val sshConfigRepository: SshConfigRepository,
    private val sshPrivateKeyRepository: SshPrivateKeyRepository,
    private val proxyProfileRepository: ProxyProfileRepository,
    private val xrayCoreBridge: XrayCoreBridge,
    private val vpnConnectionRepository: VpnConnectionRepository,
    private val smartConnectStateStore: SmartConnectStateStore,
    private val connectVpnUseCase: ConnectVpnUseCase,
    private val connectProxyVpnUseCase: ConnectProxyVpnUseCase,
    private val connectSmartVpnUseCase: ConnectSmartVpnUseCase,
) : VpnModeStarter {
    private val appContext = context.applicationContext

    override suspend fun check(target: VpnSessionOwner): VpnModeStartCheck {
        val settings = appSettingsRepository.settings.value
        val selectedAppsReady = settings.vpnMode != VpnMode.SELECTED_APPS ||
            settings.selectedAppPackages.isNotEmpty()
        val vpnPermissionGranted = VpnService.prepare(appContext) == null
        return when (target) {
            VpnSessionOwner.SHADOW_SSH -> {
                val config = sshConfigRepository.getSelectedConfig()
                VpnModeStartCheck(
                    preconditions = QuickTilePreconditions.ShadowSsh(
                        configSelected = config != null,
                        privateKeyReady = config != null && hasUsablePrivateKey(config),
                        selectedAppsReady = selectedAppsReady,
                        vpnPermissionGranted = vpnPermissionGranted,
                    ),
                    failureConfigId = config?.id,
                )
            }

            VpnSessionOwner.OPEN_SOURCE -> {
                val profile = proxyProfileRepository.getSelected()
                VpnModeStartCheck(
                    preconditions = QuickTilePreconditions.OpenSource(
                        consentAccepted = settings.openSourceConsentVersion >= OpenSourcePolicy.CONSENT_VERSION,
                        routeSelected = profile != null,
                        xrayCoreAvailable = isXrayCoreAvailable(),
                        selectedAppsReady = selectedAppsReady,
                        vpnPermissionGranted = vpnPermissionGranted,
                    ),
                    failureConfigId = profile?.id,
                )
            }

            VpnSessionOwner.SMART_CONNECT -> VpnModeStartCheck(
                preconditions = QuickTilePreconditions.SmartConnect(
                    consentAccepted = settings.smartConnectConsentAccepted(),
                    xrayCoreAvailable = isXrayCoreAvailable(),
                    selectedAppsReady = selectedAppsReady,
                    vpnPermissionGranted = vpnPermissionGranted,
                ),
                failureConfigId = null,
            )
        }
    }

    /**
     * A use case that throws is published as the error of this attempt where that is safe. Null does
     * not mean the mode started: a use case that gave up (another session took over, a validation error
     * it published) returns too.
     */
    override suspend fun connect(owner: VpnSessionOwner, failureConfigId: String?): String? {
        try {
            return when (owner) {
                VpnSessionOwner.SHADOW_SSH -> unpublishedServerStartFailure(connectVpnUseCase())
                VpnSessionOwner.OPEN_SOURCE -> {
                    connectProxyVpnUseCase()
                    null
                }
                VpnSessionOwner.SMART_CONNECT -> {
                    connectSmartVpnUseCase()
                    null
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val message = error.message ?: appContext.getString(R.string.qs_tile_unknown_error)
            publishStartFailure(owner, failureConfigId, message, connectAttempted = true)
            return message
        }
    }

    fun publishStartFailure(
        target: VpnSessionOwner,
        configId: String?,
        message: String,
        connectAttempted: Boolean,
    ) {
        if (!canPublishQuickTileStartFailure(vpnConnectionRepository.currentState, target, connectAttempted)) {
            return
        }
        if (target == VpnSessionOwner.SMART_CONNECT) {
            // The Smart tab shows its workflow message; a bare VPN error without an owner is hidden there.
            smartConnectStateStore.fail(message, keepDesiredActive = false)
        }
        vpnConnectionRepository.setError(configId, message)
    }

    /** ConnectVpnUseCase rejects a missing key too, but returns normally, so the tile would not open the app. */
    private suspend fun hasUsablePrivateKey(config: SshConfig): Boolean {
        if (config.authType != AuthType.PRIVATE_KEY) return true
        val keyId = config.privateKeyId
        return !keyId.isNullOrBlank() && sshPrivateKeyRepository.getById(keyId) != null
    }

    // Off Main: the first check loads the installed core through a class loader.
    private suspend fun isXrayCoreAvailable(): Boolean = withContext(Dispatchers.IO) {
        xrayCoreBridge.isAvailable
    }

    // Mirrors the shell's ConsentGate (Auto rule): consent the Smart tab would not ask for must not block
    // the tile, or every click would open the app and nothing there would unblock it.
    private fun AppSettings.smartConnectConsentAccepted(): Boolean {
        return !showSmartConnectWarningOnEnter ||
            smartConnectConsentVersion >= OpenSourcePolicy.CONSENT_VERSION
    }
}
