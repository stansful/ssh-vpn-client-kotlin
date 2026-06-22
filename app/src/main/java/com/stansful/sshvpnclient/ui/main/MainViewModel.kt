package com.stansful.sshvpnclient.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stansful.sshvpnclient.domain.model.AppSettings
import com.stansful.sshvpnclient.domain.model.AppThemeMode
import com.stansful.sshvpnclient.domain.model.CustomThemeColors
import com.stansful.sshvpnclient.domain.model.SshConfigSummary
import com.stansful.sshvpnclient.domain.model.VpnConnectionState
import com.stansful.sshvpnclient.domain.model.VpnConnectionStatus
import com.stansful.sshvpnclient.domain.model.VpnMode
import com.stansful.sshvpnclient.domain.repository.AppSettingsRepository
import com.stansful.sshvpnclient.domain.repository.SshConfigRepository
import com.stansful.sshvpnclient.domain.repository.VpnConnectionRepository
import com.stansful.sshvpnclient.domain.usecase.vpn.ConnectVpnUseCase
import com.stansful.sshvpnclient.domain.usecase.vpn.DisconnectVpnUseCase
import com.stansful.sshvpnclient.domain.usecase.vpn.ObserveVpnConnectionStateUseCase
import com.stansful.sshvpnclient.vpn.SshConnectionManager
import com.stansful.sshvpnclient.vpn.SshTerminalSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class TunnelCheckResult {
    IDLE,
    SUCCESS,
    FAILURE,
}

data class MainUiState(
    val vpnState: VpnConnectionState = VpnConnectionState(),
    val selectedConfig: SshConfigSummary? = null,
    val selectedKeyName: String? = null,
    val appSettings: AppSettings = AppSettings(),
    val showNoSelectedAppsDialog: Boolean = false,
    val isTunnelCheckRunning: Boolean = false,
    val tunnelCheckResult: TunnelCheckResult = TunnelCheckResult.IDLE,
    val terminalState: TerminalUiState = TerminalUiState(),
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

data class TerminalUiState(
    val isOpen: Boolean = false,
    val isConnecting: Boolean = false,
    val output: String = "",
    val input: String = "",
    val errorMessage: String? = null,
)

class MainViewModel(
    private val appSettingsRepository: AppSettingsRepository,
    configRepository: SshConfigRepository,
    private val vpnConnectionRepository: VpnConnectionRepository,
    private val connectVpnUseCase: ConnectVpnUseCase,
    private val disconnectVpnUseCase: DisconnectVpnUseCase,
    private val sshConnectionManager: SshConnectionManager,
    observeVpnConnectionStateUseCase: ObserveVpnConnectionStateUseCase,
) : ViewModel() {
    private val showNoSelectedAppsDialog = MutableStateFlow(false)
    private val isTunnelCheckRunning = MutableStateFlow(false)
    private val tunnelCheckResult = MutableStateFlow(TunnelCheckResult.IDLE)
    private val terminalState = MutableStateFlow(TerminalUiState())
    @Volatile
    private var terminalSession: SshTerminalSession? = null
    private var settingsReconnectJob: Job? = null
    private var settingsReconnectStarted = false
    private val vpnState = observeVpnConnectionStateUseCase().stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = VpnConnectionState(),
    )

    private val baseUiState = combine(
        vpnState,
        configRepository.observeSelectedSummary(),
        appSettingsRepository.settings,
        showNoSelectedAppsDialog,
    ) { vpnState, selectedConfig, appSettings, showNoSelectedAppsDialog ->
        MainUiState(
            vpnState = vpnState,
            selectedConfig = selectedConfig,
            selectedKeyName = selectedConfig?.keyName,
            appSettings = appSettings,
            showNoSelectedAppsDialog = showNoSelectedAppsDialog,
        )
    }

    val uiState = combine(
        baseUiState,
        isTunnelCheckRunning,
        tunnelCheckResult,
        terminalState,
    ) { state, isTunnelCheckRunning, tunnelCheckResult, terminalState ->
        state.copy(
            isTunnelCheckRunning = isTunnelCheckRunning,
            tunnelCheckResult = tunnelCheckResult,
            terminalState = terminalState,
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
                    closeTerminalSession(resetState = true)
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
        closeTerminalSession(resetState = true)
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

    fun setShowTerminalOnMain(show: Boolean) {
        appSettingsRepository.setShowTerminalOnMain(show)
        if (!show) {
            closeTerminalSession(resetState = true)
        }
    }

    fun setThemeMode(themeMode: AppThemeMode) {
        appSettingsRepository.setThemeMode(themeMode)
    }

    fun setCustomThemeColors(colors: CustomThemeColors) {
        appSettingsRepository.setCustomThemeColors(colors)
    }

    fun openTerminal() {
        if (!appSettingsRepository.settings.value.showTerminalOnMain) return
        if (!uiState.value.isConnected || terminalSession?.isActive == true) return
        if (terminalState.value.isConnecting) return

        terminalState.update {
            it.copy(
                isOpen = true,
                isConnecting = true,
                errorMessage = null,
            )
        }

        viewModelScope.launch {
            runCatching {
                sshConnectionManager.openTerminal(
                    log = vpnConnectionRepository::appendDiagnostic,
                    onOutput = ::appendTerminalOutput,
                    onClosed = ::handleTerminalClosed,
                )
            }.onSuccess { session ->
                if (session.isActive) {
                    terminalSession = session
                    terminalState.update {
                        it.copy(
                            isOpen = true,
                            isConnecting = false,
                            errorMessage = null,
                        )
                    }
                } else {
                    session.close()
                }
            }.onFailure { error ->
                val message = error.message ?: "Terminal connection failed"
                vpnConnectionRepository.appendDiagnostic("SSH terminal unavailable: $message")
                terminalState.update {
                    it.copy(
                        isOpen = false,
                        isConnecting = false,
                        errorMessage = message,
                    )
                }
            }
        }
    }

    fun setTerminalInput(input: String) {
        terminalState.update { it.copy(input = input) }
    }

    fun submitTerminalInput() {
        val command = terminalState.value.input
        if (command.isBlank()) return

        val session = terminalSession
        if (session == null || !session.isActive) {
            terminalState.update { it.copy(errorMessage = "Terminal is not connected") }
            return
        }

        terminalState.update { it.copy(input = "", errorMessage = null) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                session.sendLine(command)
            }.onFailure { error ->
                handleTerminalWriteFailed(error)
            }
        }
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

    override fun onCleared() {
        terminalSession?.close()
        terminalSession = null
    }

    private fun applyVpnSettingsChange(settings: AppSettings) {
        if (settings.requiresSelectedAppsButHasNone()) {
            showNoSelectedAppsDialog.value = true
            return
        }
        if (settingsReconnectJob?.isActive == true) {
            if (settingsReconnectStarted) return
            settingsReconnectJob?.cancel()
        }
        if (!vpnState.value.canDisconnect()) return

        settingsReconnectJob = viewModelScope.launch {
            delay(SETTINGS_CHANGE_DEBOUNCE_MS)
            val latestSettings = appSettingsRepository.settings.value
            if (latestSettings.requiresSelectedAppsButHasNone()) {
                showNoSelectedAppsDialog.value = true
                return@launch
            }
            settingsReconnectStarted = true
            try {
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
            } finally {
                settingsReconnectStarted = false
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

    private fun appendTerminalOutput(output: String) {
        terminalState.update { state ->
            state.copy(
                isOpen = true,
                isConnecting = false,
                output = (state.output + output).takeLast(MAX_TERMINAL_OUTPUT_CHARS),
                errorMessage = null,
            )
        }
    }

    private fun handleTerminalClosed(reason: String) {
        terminalSession = null
        terminalState.update {
            it.copy(
                isOpen = false,
                isConnecting = false,
                input = "",
                errorMessage = reason,
            )
        }
        vpnConnectionRepository.appendDiagnostic("SSH terminal closed: $reason")
    }

    private fun handleTerminalWriteFailed(error: Throwable) {
        val message = error.message ?: "Terminal command failed"
        terminalState.update { it.copy(errorMessage = message) }
        vpnConnectionRepository.appendDiagnostic("SSH terminal write failed: $message")
    }

    private fun closeTerminalSession(resetState: Boolean) {
        val session = terminalSession
        terminalSession = null
        terminalState.update { state ->
            if (resetState) {
                TerminalUiState()
            } else {
                state.copy(
                    isOpen = false,
                    isConnecting = false,
                    input = "",
                    errorMessage = null,
                )
            }
        }
        if (session == null) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                session.close()
            }.onFailure { error ->
                val message = error.message ?: error::class.java.simpleName
                vpnConnectionRepository.appendDiagnostic("SSH terminal close failed: $message")
            }
        }
    }

    private companion object {
        const val SETTINGS_CHANGE_DEBOUNCE_MS = 250L
        const val SETTINGS_RECONNECT_DELAY_MS = 450L
        const val MAX_TERMINAL_OUTPUT_CHARS = 120_000
    }
}

private data class SplitTunnelSettings(
    val vpnMode: VpnMode,
    val selectedAppPackages: Set<String>,
)
