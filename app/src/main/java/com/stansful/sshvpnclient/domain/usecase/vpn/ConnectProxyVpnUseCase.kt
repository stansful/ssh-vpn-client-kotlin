package com.stansful.sshvpnclient.domain.usecase.vpn

import android.content.Context
import androidx.core.content.ContextCompat
import com.stansful.sshvpnclient.domain.model.VpnMode
import com.stansful.sshvpnclient.domain.model.VpnConnectionStatus
import com.stansful.sshvpnclient.domain.model.VpnTransportType
import com.stansful.sshvpnclient.domain.repository.AppSettingsRepository
import com.stansful.sshvpnclient.domain.repository.ProxyProfileRepository
import com.stansful.sshvpnclient.domain.repository.VpnConnectionRepository
import com.stansful.sshvpnclient.vpn.OpenSourceVpnService
import com.stansful.sshvpnclient.vpn.SshVpnService
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
            vpnConnectionRepository.setError(null, "No opensource configuration selected")
            return
        }
        val settings = appSettingsRepository.settings.value
        if (settings.vpnMode == VpnMode.SELECTED_APPS && settings.selectedAppPackages.isEmpty()) {
            vpnConnectionRepository.setError(profile.id, "Нет выбранных приложений")
            return
        }
        if (vpnConnectionRepository.currentState.activeTransport == VpnTransportType.SSH) {
            context.startService(SshVpnService.disconnectIntent(context))
            withTimeoutOrNull(TRANSPORT_SWITCH_TIMEOUT_MS) {
                vpnConnectionRepository.state.first { state ->
                    state.status == VpnConnectionStatus.DISCONNECTED ||
                        state.activeTransport != VpnTransportType.SSH
                }
            }
        }
        vpnConnectionRepository.setConnecting(profile.id, VpnTransportType.XRAY)
        ContextCompat.startForegroundService(
            context,
            OpenSourceVpnService.connectIntent(context, profile.id),
        )
    }

    private companion object {
        const val TRANSPORT_SWITCH_TIMEOUT_MS = 2_000L
    }
}
