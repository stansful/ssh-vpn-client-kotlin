package com.stansful.sshvpnclient.domain.usecase.vpn

import android.content.Context
import androidx.core.content.ContextCompat
import com.stansful.sshvpnclient.data.local.SmartConnectStateStore
import com.stansful.sshvpnclient.domain.model.VpnConnectionStatus
import com.stansful.sshvpnclient.domain.model.VpnMode
import com.stansful.sshvpnclient.domain.model.VpnSessionOwner
import com.stansful.sshvpnclient.domain.model.VpnTransportType
import com.stansful.sshvpnclient.domain.repository.AppSettingsRepository
import com.stansful.sshvpnclient.domain.repository.VpnConnectionRepository
import com.stansful.sshvpnclient.vpn.OpenSourceVpnService
import com.stansful.sshvpnclient.vpn.SmartConnectVpnService
import com.stansful.sshvpnclient.vpn.SshVpnService
import com.stansful.sshvpnclient.vpn.canPublishVpnStartFailure
import com.stansful.sshvpnclient.vpn.canProceedAfterVpnOwnerStop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

class ConnectSmartVpnUseCase(
    context: Context,
    private val appSettingsRepository: AppSettingsRepository,
    private val vpnConnectionRepository: VpnConnectionRepository,
    private val smartConnectStateStore: SmartConnectStateStore,
) {
    private val appContext = context.applicationContext

    suspend operator fun invoke() {
        val settings = appSettingsRepository.settings.value
        if (settings.vpnMode == VpnMode.SELECTED_APPS && settings.selectedAppPackages.isEmpty()) {
            smartConnectStateStore.fail("No apps selected", keepDesiredActive = false)
            if (canPublishVpnStartFailure(vpnConnectionRepository.currentState)) {
                vpnConnectionRepository.setError(null, "No apps selected")
            }
            return
        }

        val current = vpnConnectionRepository.currentState
        if (current.sessionOwner == VpnSessionOwner.SMART_CONNECT &&
            current.status in ACTIVE_CONNECTION_STATUSES
        ) {
            return
        }
        when (current.sessionOwner) {
            VpnSessionOwner.SHADOW_SSH ->
                appContext.startService(SshVpnService.disconnectIntent(appContext))
            VpnSessionOwner.OPEN_SOURCE ->
                appContext.startService(OpenSourceVpnService.disconnectIntent(appContext))
            VpnSessionOwner.SMART_CONNECT ->
                appContext.startService(SmartConnectVpnService.stopIntent(appContext))
            null -> Unit
        }
        if (current.sessionOwner != null) {
            withTimeoutOrNull(TRANSPORT_SWITCH_TIMEOUT_MS) {
                vpnConnectionRepository.state.first { state ->
                    state.status == VpnConnectionStatus.DISCONNECTED ||
                        state.sessionOwner != current.sessionOwner
                }
            }
        }
        if (!canProceedAfterVpnOwnerStop(
                stoppedOwner = current.sessionOwner,
                currentState = vpnConnectionRepository.currentState,
            )
        ) {
            return
        }

        smartConnectStateStore.begin()
        vpnConnectionRepository.setConnecting(
            configId = null,
            transport = VpnTransportType.XRAY,
            sessionOwner = VpnSessionOwner.SMART_CONNECT,
        )
        ContextCompat.startForegroundService(
            appContext,
            SmartConnectVpnService.startIntent(appContext),
        )
    }

    private companion object {
        const val TRANSPORT_SWITCH_TIMEOUT_MS = 2_000L
        val ACTIVE_CONNECTION_STATUSES = setOf(
            VpnConnectionStatus.CONNECTING,
            VpnConnectionStatus.CONNECTED,
            VpnConnectionStatus.RECONNECTING,
        )
    }
}
