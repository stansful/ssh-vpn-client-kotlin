package com.stansful.sshvpnclient.domain.usecase.vpn

import android.content.Context
import androidx.core.content.ContextCompat
import com.stansful.sshvpnclient.domain.model.AuthType
import com.stansful.sshvpnclient.domain.repository.SshConfigRepository
import com.stansful.sshvpnclient.domain.repository.SshPrivateKeyRepository
import com.stansful.sshvpnclient.domain.repository.VpnConnectionRepository
import com.stansful.sshvpnclient.vpn.SshVpnService

class ConnectVpnUseCase(
    private val context: Context,
    private val configRepository: SshConfigRepository,
    private val keyRepository: SshPrivateKeyRepository,
    private val vpnConnectionRepository: VpnConnectionRepository,
) {
    suspend operator fun invoke() {
        val config = configRepository.getSelectedConfig()
        if (config == null) {
            vpnConnectionRepository.setError(null, "No configuration selected")
            return
        }

        if (config.authType == AuthType.PRIVATE_KEY) {
            val keyId = config.privateKeyId
            if (keyId.isNullOrBlank() || keyRepository.getById(keyId) == null) {
                vpnConnectionRepository.setError(config.id, "Selected SSH key not found")
                return
            }
        }

        vpnConnectionRepository.setConnecting(config.id)
        ContextCompat.startForegroundService(
            context.applicationContext,
            SshVpnService.connectIntent(context.applicationContext),
        )
    }
}
