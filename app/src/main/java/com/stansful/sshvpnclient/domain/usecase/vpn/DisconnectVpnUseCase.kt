package com.stansful.sshvpnclient.domain.usecase.vpn

import android.content.Context
import com.stansful.sshvpnclient.domain.model.VpnTransportType
import com.stansful.sshvpnclient.domain.repository.VpnConnectionRepository
import com.stansful.sshvpnclient.vpn.OpenSourceVpnService
import com.stansful.sshvpnclient.vpn.SshVpnService

class DisconnectVpnUseCase(
    private val context: Context,
    private val vpnConnectionRepository: VpnConnectionRepository,
) {
    operator fun invoke() {
        if (vpnConnectionRepository.currentState.activeTransport == VpnTransportType.XRAY) {
            context.applicationContext.startService(
                OpenSourceVpnService.disconnectIntent(context.applicationContext),
            )
        } else {
            context.applicationContext.startService(
                SshVpnService.disconnectIntent(context.applicationContext),
            )
        }
    }
}
