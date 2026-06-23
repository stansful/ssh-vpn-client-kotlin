package com.stansful.sshvpnclient.domain.usecase.vpn

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.stansful.sshvpnclient.domain.model.AuthType
import com.stansful.sshvpnclient.domain.model.VpnMode
import com.stansful.sshvpnclient.domain.model.VpnConnectionStatus
import com.stansful.sshvpnclient.domain.model.VpnTransportType
import com.stansful.sshvpnclient.domain.repository.AppSettingsRepository
import com.stansful.sshvpnclient.domain.repository.SshConfigRepository
import com.stansful.sshvpnclient.domain.repository.SshPrivateKeyRepository
import com.stansful.sshvpnclient.domain.repository.VpnConnectionRepository
import com.stansful.sshvpnclient.vpn.SshVpnService
import com.stansful.sshvpnclient.vpn.OpenSourceVpnService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

class ConnectVpnUseCase(
    private val context: Context,
    private val configRepository: SshConfigRepository,
    private val keyRepository: SshPrivateKeyRepository,
    private val vpnConnectionRepository: VpnConnectionRepository,
    private val appSettingsRepository: AppSettingsRepository,
) {
    suspend operator fun invoke(preserveDiagnostics: Boolean = false) {
        val config = configRepository.getSelectedConfig()
        if (config == null) {
            vpnConnectionRepository.setError(null, "No configuration selected")
            return
        }

        val settings = appSettingsRepository.settings.value
        if (settings.vpnMode == VpnMode.SELECTED_APPS && settings.selectedAppPackages.isEmpty()) {
            vpnConnectionRepository.setError(config.id, "No apps selected")
            return
        }

        if (config.authType == AuthType.PRIVATE_KEY) {
            val keyId = config.privateKeyId
            if (keyId.isNullOrBlank() || keyRepository.getById(keyId) == null) {
                vpnConnectionRepository.setError(config.id, "Selected SSH key not found")
                return
            }
        }

        if (vpnConnectionRepository.currentState.activeTransport == VpnTransportType.XRAY) {
            context.applicationContext.startService(
                OpenSourceVpnService.disconnectIntent(context.applicationContext),
            )
            withTimeoutOrNull(TRANSPORT_SWITCH_TIMEOUT_MS) {
                vpnConnectionRepository.state.first { state ->
                    state.status == VpnConnectionStatus.DISCONNECTED ||
                        state.activeTransport != VpnTransportType.XRAY
                }
            }
        } else {
            context.stopService(Intent(context, OpenSourceVpnService::class.java))
        }

        if (preserveDiagnostics) {
            vpnConnectionRepository.setReconnecting(config.id)
        } else {
            vpnConnectionRepository.setConnecting(config.id)
        }
        ContextCompat.startForegroundService(
            context.applicationContext,
            SshVpnService.connectIntent(
                context = context.applicationContext,
                preserveDiagnostics = preserveDiagnostics,
            ),
        )
    }

    private companion object {
        const val TRANSPORT_SWITCH_TIMEOUT_MS = 2_000L
    }
}
