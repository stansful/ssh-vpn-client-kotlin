package com.stansful.sshvpnclient.domain.usecase.vpn

import android.content.Context
import com.stansful.sshvpnclient.domain.model.VpnTransportType
import com.stansful.sshvpnclient.domain.model.VpnSessionOwner
import com.stansful.sshvpnclient.domain.repository.VpnConnectionRepository
import com.stansful.sshvpnclient.vpn.OpenSourceVpnService
import com.stansful.sshvpnclient.vpn.SshVpnService
import com.stansful.sshvpnclient.vpn.SmartConnectVpnService

class DisconnectVpnUseCase(
    private val context: Context,
    private val vpnConnectionRepository: VpnConnectionRepository,
) {
    operator fun invoke() {
        val appContext = context.applicationContext
        when (vpnConnectionRepository.currentState.sessionOwner) {
            VpnSessionOwner.SMART_CONNECT ->
                appContext.startService(SmartConnectVpnService.stopIntent(appContext))
            VpnSessionOwner.OPEN_SOURCE ->
                appContext.startService(OpenSourceVpnService.disconnectIntent(appContext))
            VpnSessionOwner.SHADOW_SSH ->
                appContext.startService(SshVpnService.disconnectIntent(appContext))
            null -> if (vpnConnectionRepository.currentState.activeTransport == VpnTransportType.XRAY) {
                appContext.startService(OpenSourceVpnService.disconnectIntent(appContext))
            } else {
                appContext.startService(SshVpnService.disconnectIntent(appContext))
            }
        }
    }
}
