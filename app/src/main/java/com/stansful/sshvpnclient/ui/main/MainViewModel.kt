package com.stansful.sshvpnclient.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stansful.sshvpnclient.domain.model.AppSettings
import com.stansful.sshvpnclient.domain.model.AppThemeMode
import com.stansful.sshvpnclient.domain.model.SshConfig
import com.stansful.sshvpnclient.domain.model.VpnConnectionState
import com.stansful.sshvpnclient.domain.model.VpnConnectionStatus
import com.stansful.sshvpnclient.domain.model.VpnMode
import com.stansful.sshvpnclient.domain.repository.AppSettingsRepository
import com.stansful.sshvpnclient.domain.repository.SshConfigRepository
import com.stansful.sshvpnclient.domain.repository.SshPrivateKeyRepository
import com.stansful.sshvpnclient.domain.repository.VpnConnectionRepository
import com.stansful.sshvpnclient.domain.usecase.vpn.ConnectVpnUseCase
import com.stansful.sshvpnclient.domain.usecase.vpn.DisconnectVpnUseCase
import com.stansful.sshvpnclient.domain.usecase.vpn.ObserveVpnConnectionStateUseCase
import com.stansful.sshvpnclient.vpn.SshConnectionManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class TunnelCheckResult {
    IDLE,
    SUCCESS,
    FAILURE,
}

data class MainUiState(
    val vpnState: VpnConnectionState = VpnConnectionState(),
    val selectedConfig: SshConfig? = null,
    val selectedKeyName: String? = null,
    val appSettings: AppSettings = AppSettings(),
    val showNoSelectedAppsDialog: Boolean = false,
    val isTunnelCheckRunning: Boolean = false,
    val tunnelCheckResult: TunnelCheckResult = TunnelCheckResult.IDLE,
) {
    val isBusy: Boolean
        get() = vpnState.status == VpnConnectionStatus.DISCONNECTING

    val isConnected: Boolean
        get() = vpnState.status == VpnConnectionStatus.CONNECTED

    val canDisconnect: Boolean
        get() = vpnState.status == VpnConnectionStatus.CONNECTING ||
            vpnState.status == VpnConnectionStatus.CONNECTED ||
            vpnState.status == VpnConnectionStatus.RECONNECTING

    val canConnect: Boolean
        get() = selectedConfig != null &&
            (vpnState.status == VpnConnectionStatus.DISCONNECTED ||
                vpnState.status == VpnConnectionStatus.ERROR)

    val canCheckTunnel: Boolean
        get() = vpnState.status == VpnConnectionStatus.CONNECTED && !isTunnelCheckRunning
}

class MainViewModel(
    private val appSettingsRepository: AppSettingsRepository,
    configRepository: SshConfigRepository,
    keyRepository: SshPrivateKeyRepository,
    private val vpnConnectionRepository: VpnConnectionRepository,
    private val connectVpnUseCase: ConnectVpnUseCase,
    private val disconnectVpnUseCase: DisconnectVpnUseCase,
    private val sshConnectionManager: SshConnectionManager,
    observeVpnConnectionStateUseCase: ObserveVpnConnectionStateUseCase,
) : ViewModel() {
    private val showNoSelectedAppsDialog = MutableStateFlow(false)
    private val isTunnelCheckRunning = MutableStateFlow(false)
    private val tunnelCheckResult = MutableStateFlow(TunnelCheckResult.IDLE)
    private val vpnState = observeVpnConnectionStateUseCase().stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = VpnConnectionState(),
    )

    private val baseUiState = combine(
        vpnState,
        configRepository.observeSelectedConfig(),
        keyRepository.observeAll(),
        appSettingsRepository.settings,
        showNoSelectedAppsDialog,
    ) { vpnState, selectedConfig, keys, appSettings, showNoSelectedAppsDialog ->
        MainUiState(
            vpnState = vpnState,
            selectedConfig = selectedConfig,
            selectedKeyName = selectedConfig
                ?.privateKeyId
                ?.let { keyId -> keys.firstOrNull { it.id == keyId }?.name },
            appSettings = appSettings,
            showNoSelectedAppsDialog = showNoSelectedAppsDialog,
        )
    }

    val uiState = combine(
        baseUiState,
        isTunnelCheckRunning,
        tunnelCheckResult,
    ) { state, isTunnelCheckRunning, tunnelCheckResult ->
        state.copy(
            isTunnelCheckRunning = isTunnelCheckRunning,
            tunnelCheckResult = tunnelCheckResult,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MainUiState(),
    )

    init {
        viewModelScope.launch {
            var previousSplitTunnelSettings = appSettingsRepository.settings.value.splitTunnelSettings()
            appSettingsRepository.settings
                .drop(1)
                .collect { settings ->
                    val nextSplitTunnelSettings = settings.splitTunnelSettings()
                    if (previousSplitTunnelSettings != nextSplitTunnelSettings) {
                        applyVpnSettingsChange(settings)
                    }
                    previousSplitTunnelSettings = nextSplitTunnelSettings
                }
        }
        viewModelScope.launch {
            vpnState.collect { state ->
                if (state.status != VpnConnectionStatus.CONNECTED) {
                    tunnelCheckResult.value = TunnelCheckResult.IDLE
                    isTunnelCheckRunning.value = false
                }
            }
        }
    }

    fun connect() {
        if (uiState.value.appSettings.requiresSelectedAppsButHasNone()) {
            showNoSelectedAppsDialog.value = true
            return
        }
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

    fun checkTunnel() {
        if (!uiState.value.canCheckTunnel) return
        viewModelScope.launch {
            isTunnelCheckRunning.value = true
            tunnelCheckResult.value = TunnelCheckResult.IDLE
            try {
                sshConnectionManager.checkTcpForward(
                    log = vpnConnectionRepository::appendDiagnostic,
                )
                if (vpnState.value.status == VpnConnectionStatus.CONNECTED) {
                    tunnelCheckResult.value = TunnelCheckResult.SUCCESS
                }
            } catch (_: Exception) {
                // Detailed failure is already written to diagnostics by SshConnectionManager.
                if (vpnState.value.status == VpnConnectionStatus.CONNECTED) {
                    tunnelCheckResult.value = TunnelCheckResult.FAILURE
                }
            } finally {
                isTunnelCheckRunning.value = false
            }
        }
    }

    fun onVpnPermissionDenied() {
        vpnConnectionRepository.setError(uiState.value.selectedConfig?.id, "VPN permission denied")
    }

    fun setShowLogsOnMain(show: Boolean) {
        appSettingsRepository.setShowLogsOnMain(show)
    }

    fun setThemeMode(themeMode: AppThemeMode) {
        appSettingsRepository.setThemeMode(themeMode)
    }

    fun setVpnMode(vpnMode: VpnMode) {
        appSettingsRepository.setVpnMode(vpnMode)
    }

    fun setSelectedAppPackages(packageNames: Set<String>) {
        appSettingsRepository.setSelectedAppPackages(packageNames)
    }

    fun dismissNoSelectedAppsDialog() {
        showNoSelectedAppsDialog.value = false
    }

    private fun applyVpnSettingsChange(settings: AppSettings) {
        if (!vpnState.value.canDisconnect()) return
        if (settings.requiresSelectedAppsButHasNone()) {
            showNoSelectedAppsDialog.value = true
            return
        }

        viewModelScope.launch {
            disconnectVpnUseCase()
            delay(SETTINGS_RECONNECT_DELAY_MS)
            runCatching {
                connectVpnUseCase(preserveDiagnostics = true)
            }.onFailure { error ->
                vpnConnectionRepository.setError(
                    uiState.value.selectedConfig?.id,
                    error.message ?: "Unknown connection error",
                )
            }
        }
    }

    private fun AppSettings.requiresSelectedAppsButHasNone(): Boolean {
        return vpnMode == VpnMode.SELECTED_APPS && selectedAppPackages.isEmpty()
    }

    private fun AppSettings.splitTunnelSettings(): SplitTunnelSettings {
        return SplitTunnelSettings(
            vpnMode = vpnMode,
            selectedAppPackages = if (vpnMode == VpnMode.SELECTED_APPS) {
                selectedAppPackages
            } else {
                emptySet()
            },
        )
    }

    private fun VpnConnectionState.canDisconnect(): Boolean {
        return status == VpnConnectionStatus.CONNECTING ||
            status == VpnConnectionStatus.CONNECTED ||
            status == VpnConnectionStatus.RECONNECTING
    }

    private companion object {
        const val SETTINGS_RECONNECT_DELAY_MS = 450L
    }
}

private data class SplitTunnelSettings(
    val vpnMode: VpnMode,
    val selectedAppPackages: Set<String>,
)
