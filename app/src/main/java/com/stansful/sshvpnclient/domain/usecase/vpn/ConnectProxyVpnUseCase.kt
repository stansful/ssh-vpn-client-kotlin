package com.stansful.sshvpnclient.domain.usecase.vpn

import android.content.Context
import androidx.core.content.ContextCompat
import com.stansful.sshvpnclient.domain.model.VpnMode
import com.stansful.sshvpnclient.domain.model.VpnConnectionStatus
import com.stansful.sshvpnclient.domain.model.VpnSessionOwner
import com.stansful.sshvpnclient.domain.model.VpnTransportType
import com.stansful.sshvpnclient.domain.repository.AppSettingsRepository
import com.stansful.sshvpnclient.domain.repository.ProxyProfileRepository
import com.stansful.sshvpnclient.domain.repository.VpnConnectionRepository
import com.stansful.sshvpnclient.vpn.OpenSourceVpnService
import com.stansful.sshvpnclient.vpn.SmartConnectVpnService
import com.stansful.sshvpnclient.vpn.SshVpnService
import com.stansful.sshvpnclient.vpn.canProceedAfterVpnOwnerStop
import com.stansful.sshvpnclient.vpn.canPublishVpnStartFailure
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

class ConnectProxyVpnUseCase(
    private val context: Context,
    private val proxyProfileRepository: ProxyProfileRepository,
    private val appSettingsRepository: AppSettingsRepository,
    private val vpnConnectionRepository: VpnConnectionRepository,
) {
    suspend operator fun invoke() {
        val profile = proxyProfileRepository.getSelected()
        if (profile == null) {
            publishStartFailure(null, "No opensource configuration selected")
            return
        }
        val settings = appSettingsRepository.settings.value
        if (settings.vpnMode == VpnMode.SELECTED_APPS && settings.selectedAppPackages.isEmpty()) {
            publishStartFailure(profile.id, "No apps selected")
            return
        }
        val stateBeforeSwitch = vpnConnectionRepository.currentState
        if (stateBeforeSwitch.sessionOwner == VpnSessionOwner.SMART_CONNECT) {
            context.startService(SmartConnectVpnService.stopIntent(context))
            withTimeoutOrNull(TRANSPORT_SWITCH_TIMEOUT_MS) {
                vpnConnectionRepository.state.first { state ->
                    state.status == VpnConnectionStatus.DISCONNECTED ||
                        state.sessionOwner != VpnSessionOwner.SMART_CONNECT
                }
            }
        } else if (stateBeforeSwitch.activeTransport == VpnTransportType.SSH) {
            context.startService(SshVpnService.disconnectIntent(context))
            withTimeoutOrNull(TRANSPORT_SWITCH_TIMEOUT_MS) {
                vpnConnectionRepository.state.first { state ->
                    state.status == VpnConnectionStatus.DISCONNECTED ||
                        state.activeTransport != VpnTransportType.SSH
                }
            }
        }
        if (!canProceedAfterVpnOwnerStop(
                stoppedOwner = stateBeforeSwitch.sessionOwner,
                currentState = vpnConnectionRepository.currentState,
            )
        ) {
            return
        }
        vpnConnectionRepository.setConnecting(
            profile.id,
            VpnTransportType.XRAY,
            VpnSessionOwner.OPEN_SOURCE,
        )
        ContextCompat.startForegroundService(
            context,
            OpenSourceVpnService.connectIntent(context, profile.id),
        )
    }

    private fun publishStartFailure(configId: String?, message: String) {
        if (canPublishVpnStartFailure(vpnConnectionRepository.currentState)) {
            vpnConnectionRepository.setError(configId, message)
        }
    }

    private companion object {
        const val TRANSPORT_SWITCH_TIMEOUT_MS = 2_000L
    }
}
