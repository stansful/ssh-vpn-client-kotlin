package com.stansful.sshvpnclient.domain.usecase.vpn

import android.content.Context
import com.stansful.sshvpnclient.vpn.SshVpnService

class DisconnectVpnUseCase(
    private val context: Context,
) {
    operator fun invoke() {
        context.applicationContext.startService(
            SshVpnService.disconnectIntent(context.applicationContext),
        )
    }
}
