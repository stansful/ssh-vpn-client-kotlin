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
import com.stansful.sshvpnclient.domain.model.VpnTransportType
import com.stansful.sshvpnclient.domain.repository.AppSettingsRepository
import com.stansful.sshvpnclient.domain.repository.AppUpdateCoordinator
import com.stansful.sshvpnclient.domain.repository.SshConfigRepository
import com.stansful.sshvpnclient.domain.repository.VpnConnectionRepository
import com.stansful.sshvpnclient.domain.usecase.vpn.ConnectVpnUseCase
import com.stansful.sshvpnclient.domain.usecase.vpn.DisconnectVpnUseCase
import com.stansful.sshvpnclient.domain.usecase.vpn.ObserveVpnConnectionStateUseCase
import com.stansful.sshvpnclient.ui.common.AppUpdateUiState
import com.stansful.sshvpnclient.vpn.SshConnectionManager
import com.stansful.sshvpnclient.vpn.SshTerminalSession
import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
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
    val updateState: AppUpdateUiState = AppUpdateUiState(),
) {
    private val isSshTransportState: Boolean
        get() = vpnState.activeTransport == VpnTransportType.SSH

    private val isSshErrorState: Boolean
        get() = vpnState.activeTransport == null &&
            vpnState.status == VpnConnectionStatus.ERROR &&
            (vpnState.activeConfigId == selectedConfig?.id ||
                (vpnState.activeConfigId == null && selectedConfig == null))

    val sshStatus: VpnConnectionStatus
        get() = if (isSshTransportState || isSshErrorState) {
            vpnState.status
        } else {
            VpnConnectionStatus.DISCONNECTED
        }

    val sshErrorMessage: String?
        get() = if (isSshTransportState || isSshErrorState) vpnState.errorMessage else null

    val isOpenSourceActive: Boolean
        get() = vpnState.activeTransport == VpnTransportType.XRAY &&
            (vpnState.status == VpnConnectionStatus.CONNECTING ||
                vpnState.status == VpnConnectionStatus.CONNECTED ||
                vpnState.status == VpnConnectionStatus.RECONNECTING)

    val showSshDiagnostics: Boolean
        get() = vpnState.activeTransport != VpnTransportType.XRAY && vpnState.diagnostics.isNotEmpty()

    val isBusy: Boolean
        get() = sshStatus == VpnConnectionStatus.DISCONNECTING

    val isConnected: Boolean
        get() = sshStatus == VpnConnectionStatus.CONNECTED

    val canDisconnect: Boolean
        get() = sshStatus == VpnConnectionStatus.CONNECTING ||
            sshStatus == VpnConnectionStatus.CONNECTED ||
            sshStatus == VpnConnectionStatus.RECONNECTING

    val canConnect: Boolean
        get() = selectedConfig != null &&
            vpnState.status != VpnConnectionStatus.DISCONNECTING &&
            (sshStatus == VpnConnectionStatus.DISCONNECTED ||
                sshStatus == VpnConnectionStatus.ERROR)

    val canCheckTunnel: Boolean
        get() = isConnected && !isTunnelCheckRunning
}

data class TerminalUiState(
    val isOpen: Boolean = false,
    val isConnecting: Boolean = false,
    val output: String = "",
    val outputRevision: Long = 0L,
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
    private val appUpdateCoordinator: AppUpdateCoordinator,
) : ViewModel() {
    private val showNoSelectedAppsDialog = MutableStateFlow(false)
    private val isTunnelCheckRunning = MutableStateFlow(false)
    private val tunnelCheckResult = MutableStateFlow(TunnelCheckResult.IDLE)
    private val terminalState = MutableStateFlow(TerminalUiState())
    private val terminalLock = Any()
    private val terminalOutputBuffer = BoundedTerminalOutputBuffer(MAX_TERMINAL_OUTPUT_CHARACTERS)
    private var terminalGeneration = 0L
    private var terminalOutputPublishJob: Job? = null
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
        appUpdateCoordinator.state,
    ) { state, isTunnelCheckRunning, tunnelCheckResult, terminalState, updateState ->
        state.copy(
            isTunnelCheckRunning = isTunnelCheckRunning,
            tunnelCheckResult = tunnelCheckResult,
            terminalState = terminalState,
            updateState = updateState,
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
                if (state.activeTransport != VpnTransportType.SSH ||
                    state.status != VpnConnectionStatus.CONNECTED
                ) {
                    tunnelCheckResult.value = TunnelCheckResult.IDLE
                    isTunnelCheckRunning.value = false
                    closeTerminal()
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
            } catch (cancellation: CancellationException) {
                // The repository outlives this ViewModel; a cancelled scope must not publish
                // a connection error into app-wide VPN state.
                throw cancellation
            } catch (error: Exception) {
                vpnConnectionRepository.setError(
                    uiState.value.selectedConfig?.id,
                    error.message ?: "Unknown connection error",
                )
            }
        }
    }

    fun disconnect() {
        closeTerminal()
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
                if (vpnState.value.isConnectedSsh()) {
                    tunnelCheckResult.value = TunnelCheckResult.SUCCESS
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Detailed failure is already written to diagnostics by SshConnectionManager.
                if (vpnState.value.isConnectedSsh()) {
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
            closeTerminal()
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

        val generation = beginTerminalGeneration()
        terminalState.value = TerminalUiState(
            isOpen = true,
            isConnecting = true,
        )

        viewModelScope.launch {
            runCatching {
                sshConnectionManager.openTerminal(
                    log = vpnConnectionRepository::appendDiagnostic,
                    onOutput = { output -> appendTerminalOutput(generation, output) },
                    onClosed = { reason -> handleTerminalClosed(generation, reason) },
                )
            }.onSuccess { session ->
                val accepted = synchronized(terminalLock) {
                    if (terminalGeneration == generation && session.isActive) {
                        terminalSession = session
                        true
                    } else {
                        false
                    }
                }
                if (accepted) {
                    terminalState.update {
                        if (isTerminalGenerationCurrent(generation)) {
                            it.copy(
                                isOpen = true,
                                isConnecting = false,
                                errorMessage = null,
                            )
                        } else {
                            it
                        }
                    }
                } else {
                    session.close()
                }
            }.onFailure { error ->
                if (!isTerminalGenerationCurrent(generation)) return@onFailure
                val message = error.message ?: "Terminal connection failed"
                vpnConnectionRepository.appendDiagnostic("SSH terminal unavailable: $message")
                terminalState.update {
                    if (isTerminalGenerationCurrent(generation)) {
                        it.copy(
                            isOpen = false,
                            isConnecting = false,
                            errorMessage = message,
                        )
                    } else {
                        it
                    }
                }
            }
        }
    }

    /** Closes the optional interactive shell without affecting the VPN transport. */
    fun closeTerminal() {
        val session = invalidateTerminalGeneration()
        terminalState.value = TerminalUiState()
        closeTerminalSessionAsync(session)
    }

    fun setTerminalInput(input: String) {
        terminalState.update { it.copy(input = input) }
    }

    fun submitTerminalInput() {
        val command = terminalState.value.input
        if (command.isBlank()) return

        val (session, generation) = synchronized(terminalLock) {
            terminalSession to terminalGeneration
        }
        if (session == null || !session.isActive) {
            terminalState.update { it.copy(errorMessage = "Terminal is not connected") }
            return
        }

        terminalState.update { it.copy(input = "", errorMessage = null) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                session.sendLine(command)
            }.onFailure { error ->
                handleTerminalWriteFailed(generation, error)
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

    fun checkForUpdates(manual: Boolean = true) {
        appUpdateCoordinator.checkForUpdates(manual)
    }

    fun dismissAvailableUpdate() {
        appUpdateCoordinator.dismissAvailableUpdate()
    }

    fun downloadAvailableUpdate() {
        appUpdateCoordinator.downloadAvailableUpdate()
    }

    fun onUpdateActionFailed(message: String) {
        appUpdateCoordinator.onActionFailed(message)
    }

    override fun onCleared() {
        val session = invalidateTerminalGeneration()
        runCatching { session?.close() }
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
        return activeTransport == VpnTransportType.SSH &&
            (status == VpnConnectionStatus.CONNECTING ||
                status == VpnConnectionStatus.CONNECTED ||
                status == VpnConnectionStatus.RECONNECTING)
    }

    private fun VpnConnectionState.isConnectedSsh(): Boolean {
        return activeTransport == VpnTransportType.SSH &&
            status == VpnConnectionStatus.CONNECTED
    }

    private fun beginTerminalGeneration(): Long = synchronized(terminalLock) {
        terminalGeneration += 1
        terminalOutputPublishJob?.cancel()
        terminalOutputPublishJob = null
        terminalOutputBuffer.clear()
        terminalGeneration
    }

    private fun appendTerminalOutput(generation: Long, output: String) {
        if (output.isEmpty()) return
        synchronized(terminalLock) {
            if (terminalGeneration != generation) return
            terminalOutputBuffer.append(output)
            if (terminalOutputPublishJob?.isActive == true) return
            terminalOutputPublishJob = viewModelScope.launch {
                delay(TERMINAL_OUTPUT_UI_BATCH_MS)
                publishTerminalOutput(generation)
            }
        }
    }

    private fun publishTerminalOutput(generation: Long) {
        val output = synchronized(terminalLock) {
            if (terminalGeneration != generation) return
            terminalOutputPublishJob = null
            terminalOutputBuffer.snapshot()
        }
        terminalState.update {
            if (isTerminalGenerationCurrent(generation)) {
                it.copy(
                    isOpen = true,
                    isConnecting = false,
                    output = output,
                    outputRevision = it.outputRevision + 1L,
                    errorMessage = null,
                )
            } else {
                it
            }
        }
    }

    private fun handleTerminalClosed(generation: Long, reason: String) {
        val closedState = synchronized(terminalLock) {
            if (terminalGeneration != generation) return
            terminalOutputPublishJob?.cancel()
            terminalOutputPublishJob = null
            terminalSession = null
            val finalOutput = terminalOutputBuffer.snapshot()
            terminalOutputBuffer.clear()
            terminalGeneration += 1
            ClosedTerminalState(terminalGeneration, finalOutput)
        }
        terminalState.update {
            if (isTerminalGenerationCurrent(closedState.generation)) {
                it.copy(
                    isOpen = false,
                    isConnecting = false,
                    output = closedState.output,
                    outputRevision = it.outputRevision + 1L,
                    input = "",
                    errorMessage = reason,
                )
            } else {
                it
            }
        }
        vpnConnectionRepository.appendDiagnostic("SSH terminal closed: $reason")
    }

    private fun handleTerminalWriteFailed(generation: Long, error: Throwable) {
        if (!isTerminalGenerationCurrent(generation)) return
        val message = error.message ?: "Terminal command failed"
        terminalState.update {
            if (isTerminalGenerationCurrent(generation)) it.copy(errorMessage = message) else it
        }
        vpnConnectionRepository.appendDiagnostic("SSH terminal write failed: $message")
    }

    private fun invalidateTerminalGeneration(): SshTerminalSession? = synchronized(terminalLock) {
        terminalGeneration += 1
        terminalOutputPublishJob?.cancel()
        terminalOutputPublishJob = null
        terminalOutputBuffer.clear()
        val session = terminalSession
        terminalSession = null
        session
    }

    private fun isTerminalGenerationCurrent(generation: Long): Boolean = synchronized(terminalLock) {
        terminalGeneration == generation
    }

    private fun closeTerminalSessionAsync(session: SshTerminalSession?) {
        if (session == null) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { session.close() }
                .onFailure { error ->
                    val message = error.message ?: error::class.java.simpleName
                    vpnConnectionRepository.appendDiagnostic("SSH terminal close failed: $message")
                }
        }
    }

    private companion object {
        const val SETTINGS_CHANGE_DEBOUNCE_MS = 250L
        const val SETTINGS_RECONNECT_DELAY_MS = 450L
        const val TERMINAL_OUTPUT_UI_BATCH_MS = 250L
        const val MAX_TERMINAL_OUTPUT_CHARACTERS = 64 * 1_024
    }
}

private data class ClosedTerminalState(
    val generation: Long,
    val output: String,
)

internal class BoundedTerminalOutputBuffer(
    private val maxCharacters: Int,
) {
    private val chunks = ArrayDeque<StringBuilder>()
    private var characterCount = 0

    init {
        require(maxCharacters > 0)
    }

    fun append(value: String) {
        if (value.isEmpty()) return
        if (value.length >= maxCharacters) {
            clear()
            val tail = value.takeLast(maxCharacters)
            appendChunked(tail)
            characterCount = tail.length
            return
        }

        appendChunked(value)
        characterCount += value.length
        var charactersToRemove = (characterCount - maxCharacters).coerceAtLeast(0)
        while (charactersToRemove > 0) {
            val first = chunks.removeFirst()
            if (first.length <= charactersToRemove) {
                charactersToRemove -= first.length
                characterCount -= first.length
            } else {
                first.delete(0, charactersToRemove)
                chunks.addFirst(first)
                characterCount -= charactersToRemove
                charactersToRemove = 0
            }
        }
    }

    fun snapshot(): String = buildString(characterCount) {
        chunks.forEach { chunk -> append(chunk) }
    }

    fun clear() {
        chunks.clear()
        characterCount = 0
    }

    private fun appendChunked(value: String) {
        var offset = 0
        while (offset < value.length) {
            val chunk = chunks.lastOrNull()
                ?.takeIf { it.length < MAX_CHUNK_CHARACTERS }
                ?: StringBuilder(MAX_CHUNK_CHARACTERS).also(chunks::addLast)
            val copyLength = minOf(MAX_CHUNK_CHARACTERS - chunk.length, value.length - offset)
            chunk.append(value, offset, offset + copyLength)
            offset += copyLength
        }
    }

    private companion object {
        const val MAX_CHUNK_CHARACTERS = 4 * 1_024
    }
}

private data class SplitTunnelSettings(
    val vpnMode: VpnMode,
    val selectedAppPackages: Set<String>,
)
