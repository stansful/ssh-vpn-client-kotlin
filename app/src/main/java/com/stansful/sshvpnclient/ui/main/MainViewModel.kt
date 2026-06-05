package com.stansful.sshvpnclient.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stansful.sshvpnclient.domain.model.SshConfig
import com.stansful.sshvpnclient.domain.model.VpnConnectionState
import com.stansful.sshvpnclient.domain.model.VpnConnectionStatus
import com.stansful.sshvpnclient.domain.repository.SshConfigRepository
import com.stansful.sshvpnclient.domain.repository.SshPrivateKeyRepository
import com.stansful.sshvpnclient.domain.repository.VpnConnectionRepository
import com.stansful.sshvpnclient.domain.usecase.vpn.ConnectVpnUseCase
import com.stansful.sshvpnclient.domain.usecase.vpn.DisconnectVpnUseCase
import com.stansful.sshvpnclient.domain.usecase.vpn.ObserveVpnConnectionStateUseCase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MainUiState(
    val vpnState: VpnConnectionState = VpnConnectionState(),
    val selectedConfig: SshConfig? = null,
    val selectedKeyName: String? = null,
) {
    val isBusy: Boolean
        get() = vpnState.status == VpnConnectionStatus.CONNECTING ||
            vpnState.status == VpnConnectionStatus.DISCONNECTING

    val isConnected: Boolean
        get() = vpnState.status == VpnConnectionStatus.CONNECTED
}

class MainViewModel(
    configRepository: SshConfigRepository,
    keyRepository: SshPrivateKeyRepository,
    private val vpnConnectionRepository: VpnConnectionRepository,
    private val connectVpnUseCase: ConnectVpnUseCase,
    private val disconnectVpnUseCase: DisconnectVpnUseCase,
    observeVpnConnectionStateUseCase: ObserveVpnConnectionStateUseCase,
) : ViewModel() {
    val uiState = combine(
        observeVpnConnectionStateUseCase(),
        configRepository.observeSelectedConfig(),
        keyRepository.observeAll(),
    ) { vpnState, selectedConfig, keys ->
        MainUiState(
            vpnState = vpnState,
            selectedConfig = selectedConfig,
            selectedKeyName = selectedConfig
                ?.privateKeyId
                ?.let { keyId -> keys.firstOrNull { it.id == keyId }?.name },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MainUiState(),
    )

    fun connect() {
        viewModelScope.launch {
            try {
                connectVpnUseCase()
            } catch (error: Exception) {
                vpnConnectionRepository.setError(
                    uiState.value.selectedConfig?.id,
                    error.message ?: "Unknown connection error",
                )
            }
        }
    }

    fun disconnect() {
        disconnectVpnUseCase()
    }

    fun onVpnPermissionDenied() {
        vpnConnectionRepository.setError(uiState.value.selectedConfig?.id, "VPN permission denied")
    }
}
