package com.stansful.sshvpnclient.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stansful.sshvpnclient.domain.model.AppSettings
import com.stansful.sshvpnclient.domain.model.AppThemeMode
import com.stansful.sshvpnclient.domain.model.AppUpdateState
import com.stansful.sshvpnclient.domain.model.CustomThemeColors
import com.stansful.sshvpnclient.domain.model.GlobalTab
import com.stansful.sshvpnclient.domain.model.SshConfigSummary
import com.stansful.sshvpnclient.domain.model.VpnConnectionState
import com.stansful.sshvpnclient.domain.model.VpnConnectionStatus
import com.stansful.sshvpnclient.domain.model.VpnMode
import com.stansful.sshvpnclient.domain.model.VpnSessionOwner
import com.stansful.sshvpnclient.domain.model.VpnTransportType
import com.stansful.sshvpnclient.domain.repository.AppSettingsRepository
import com.stansful.sshvpnclient.domain.repository.AppUpdateCoordinator
import com.stansful.sshvpnclient.domain.repository.SshConfigRepository
import com.stansful.sshvpnclient.domain.repository.VpnConnectionRepository
import com.stansful.sshvpnclient.domain.usecase.config.SelectSshConfigUseCase
import com.stansful.sshvpnclient.domain.usecase.vpn.ConnectVpnResult
import com.stansful.sshvpnclient.domain.usecase.vpn.ConnectVpnUseCase
import com.stansful.sshvpnclient.domain.usecase.vpn.DisconnectVpnUseCase
import com.stansful.sshvpnclient.domain.usecase.vpn.ObserveVpnConnectionStateUseCase
import com.stansful.sshvpnclient.vpn.SshConnectionManager
import com.stansful.sshvpnclient.vpn.SshStopRequests
import com.stansful.sshvpnclient.vpn.canPublishVpnStartFailure
import com.stansful.sshvpnclient.vpn.SshTerminalSession
import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
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
    /** Round trip of the last successful tunnel check, read from its activity line. */
    val tunnelCheckLatencyMs: Long? = null,
    /** `host:port` the last successful tunnel check reached on the server side. */
    val tunnelCheckTarget: String? = null,
    val terminalState: TerminalUiState = TerminalUiState(),
    val updateState: AppUpdateState = AppUpdateState(),
    /** Every saved server, for Home's "Choose server" sheet. */
    val configs: List<SshConfigSummary> = emptyList(),
    /** False until the server list has been read once (avoids flashing the first-run state). */
    val configsLoaded: Boolean = false,
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
    /**
     * Line breaks the bounded buffer has dropped from the front of [output] so far: the number of the
     * first line of [output] in the whole shell session, so the Terminal can key lines stably.
     */
    val firstLineNumber: Long = 0L,
    val input: String = "",
    val errorMessage: String? = null,
)

/** A one-off Server-mode message for a toast; [id] makes repeated identical messages distinct. */
data class ServerSessionNotice(
    val id: Long,
    val message: String,
    val detail: String? = null,
)

class MainViewModel(
    private val appSettingsRepository: AppSettingsRepository,
    configRepository: SshConfigRepository,
    private val vpnConnectionRepository: VpnConnectionRepository,
    private val connectVpnUseCase: ConnectVpnUseCase,
    private val disconnectVpnUseCase: DisconnectVpnUseCase,
    private val selectSshConfigUseCase: SelectSshConfigUseCase,
    private val sshConnectionManager: SshConnectionManager,
    observeVpnConnectionStateUseCase: ObserveVpnConnectionStateUseCase,
    private val appUpdateCoordinator: AppUpdateCoordinator,
    private val sshStopRequests: SshStopRequests,
    /**
     * Runs restarts, and a Connect that takes one over: once the session is stopped, the reconnect
     * must happen even if the Activity finishes.
     */
    private val applicationScope: CoroutineScope,
) : ViewModel() {
    private val showNoSelectedAppsDialog = MutableStateFlow(false)
    private val tunnelCheck = MutableStateFlow(TunnelCheckState())
    private val terminalState = MutableStateFlow(TerminalUiState())
    private val terminalLock = Any()
    private val terminalOutputBuffer = BoundedTerminalOutputBuffer(MAX_TERMINAL_OUTPUT_CHARACTERS)
    private var terminalGeneration = 0L
    private var terminalOutputPublishJob: Job? = null
    @Volatile
    private var terminalSession: SshTerminalSession? = null
    private var connectJob: Job? = null

    /** The Server session a restart stopped that [connectJob] waits for (see [connect]), else null. */
    private var connectStoppedSession: Long? = null
    private var sessionRestartJob: Job? = null

    /**
     * The Server session [sessionRestartJob] stops ([ConnectVpnUseCase.latestSession]); null while the
     * restart is still in its debounce and has stopped nothing.
     */
    private var restartStoppedSession: Long? = null
    private val sessionNotice = MutableStateFlow<ServerSessionNotice?>(null)
    private var noticeSequence = 0L

    /** The latest Server-mode notice not shown yet; the shell shows it as a toast, then [consumeNotice]. */
    val notice: StateFlow<ServerSessionNotice?> = sessionNotice.asStateFlow()

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
        configRepository.observeSummaries(),
    ) { vpnState, selectedConfig, appSettings, showNoSelectedAppsDialog, configs ->
        MainUiState(
            vpnState = vpnState,
            selectedConfig = selectedConfig,
            selectedKeyName = selectedConfig?.keyName,
            appSettings = appSettings,
            showNoSelectedAppsDialog = showNoSelectedAppsDialog,
            configs = configs,
            configsLoaded = true,
        )
    }

    val uiState = combine(
        baseUiState,
        tunnelCheck,
        terminalState,
        appUpdateCoordinator.state,
    ) { state, tunnelCheck, terminalState, updateState ->
        state.copy(
            isTunnelCheckRunning = tunnelCheck.isRunning,
            tunnelCheckResult = tunnelCheck.result,
            tunnelCheckLatencyMs = tunnelCheck.latencyMs,
            tunnelCheckTarget = tunnelCheck.target,
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
                        restartSession(settings)
                    }
                    previousSplitTunnelSettings = nextSplitTunnelSettings
                }
        }
        viewModelScope.launch {
            vpnState.collect { state ->
                if (state.activeTransport != VpnTransportType.SSH ||
                    state.status != VpnConnectionStatus.CONNECTED
                ) {
                    tunnelCheck.value = TunnelCheckState()
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
        // An explicit Connect takes over a restart (Home offers it once the restarted session has
        // stopped): both would start a session, and the loser would report a bogus failure. A restart
        // that has already stopped its session hands over its wait for that session to let go, and so
        // does an earlier Connect that took one over.
        val stoppedSession = restartStoppedSession?.takeIf { sessionRestartJob?.isActive == true }
            ?: connectStoppedSession?.takeIf { connectJob?.isActive == true }
        cancelSessionRestart()
        // The newest request wins; one that already started the service has completed anyway.
        connectJob?.cancel()
        connectStoppedSession = stoppedSession
        // Taking over a restart that stopped its session also takes over its promise to reconnect
        // when the Activity finishes meanwhile, and its handling of a stop from elsewhere: Disconnect
        // here cancels the job, any other stop (the next Activity, the notification, the quick tile)
        // is seen through [SshStopRequests].
        val scope = if (stoppedSession != null) applicationScope else viewModelScope
        val stopRequestsAtRequest = sshStopRequests.current
        connectJob = scope.launch(Dispatchers.Main.immediate) {
            try {
                val result = connectVpnUseCase(
                    stoppedSession = stoppedSession,
                    isStillWanted = { sshStopRequests.current == stopRequestsAtRequest },
                )
                reportUnstartedConnect("Couldn’t connect", result)
            } catch (cancellation: CancellationException) {
                // The repository outlives this ViewModel; a cancelled scope must not publish
                // a connection error into app-wide VPN state.
                throw cancellation
            } catch (error: Exception) {
                reportConnectFailure("Couldn’t connect", error)
            }
        }
    }

    /**
     * Stops the Server-mode session. A connect still waiting for the previous session to let go and a
     * pending or running restart are cancelled first: neither may start a session after this.
     */
    fun disconnect() {
        connectJob?.cancel()
        connectJob = null
        cancelSessionRestart()
        closeTerminal()
        disconnectVpnUseCase()
    }

    /** Drops notice [id] once it was shown, so a recreated screen does not show it again. */
    fun consumeNotice(id: Long) {
        sessionNotice.update { current -> if (current?.id == id) null else current }
    }

    fun checkTunnel() {
        if (!uiState.value.canCheckTunnel) return
        viewModelScope.launch {
            tunnelCheck.value = TunnelCheckState(isRunning = true)
            var success: TunnelCheckSuccess? = null
            try {
                sshConnectionManager.checkTcpForward(
                    log = { line ->
                        parseTunnelCheckSuccess(line)?.let { success = it }
                        vpnConnectionRepository.appendDiagnostic(line)
                    },
                )
                if (vpnState.value.isConnectedSsh()) {
                    tunnelCheck.value = TunnelCheckState(
                        result = TunnelCheckResult.SUCCESS,
                        latencyMs = success?.latencyMs,
                        target = success?.target,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Detailed failure is already written to diagnostics by SshConnectionManager.
                if (vpnState.value.isConnectedSsh()) {
                    tunnelCheck.value = TunnelCheckState(result = TunnelCheckResult.FAILURE)
                }
            } finally {
                tunnelCheck.update { it.copy(isRunning = false) }
            }
        }
    }

    /**
     * Makes [id] the selected server. When an SSH session is running through another server it is
     * restarted through [id] with the same disconnect → pause → reconnect sequence that re-applies
     * app-routing changes; with "Only selected apps" and no apps the session is left alone and the
     * no-selected-apps dialog is raised instead.
     */
    /**
     * Home's "Choose server": stores [id] as the selected server and restarts a running session through
     * it. When the choice can't be stored (database / secret storage), nothing restarts and [onFailed]
     * runs on the main thread.
     */
    fun selectConfig(id: String, onFailed: () -> Unit = {}) {
        val previousId = uiState.value.selectedConfig?.id
        viewModelScope.launch {
            try {
                selectSshConfigUseCase(id)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                onFailed()
                return@launch
            }
            if (id != previousId && vpnState.value.canDisconnect()) {
                restartSession(appSettingsRepository.settings.value)
            }
        }
    }

    /**
     * Restarts a running Server-mode session through the selected server (Servers: another server was
     * picked while connected), like [selectConfig] does for Home's "Choose server".
     */
    fun reconnectThroughSelectedServer() {
        if (vpnState.value.canDisconnect()) restartSession(appSettingsRepository.settings.value)
    }

    /** Persists Home's mode (Auto · Server · Routes). */
    fun setActiveGlobalTab(tab: GlobalTab) {
        appSettingsRepository.setActiveGlobalTab(tab)
    }

    fun onVpnPermissionDenied() {
        vpnConnectionRepository.setError(uiState.value.selectedConfig?.id, "VPN permission denied")
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
                if (error is CancellationException) throw error
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

    /** The Terminal's unsent command (memory only), read when its screen is recreated. */
    val terminalInput: String
        get() = terminalState.value.input

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

    override fun onCleared() {
        // A restart still in its debounce has stopped nothing yet; one past it finishes its reconnect,
        // and so does a Connect that took such a restart over (both run in applicationScope).
        if (restartStoppedSession == null) sessionRestartJob?.cancel()
        val session = invalidateTerminalGeneration()
        runCatching { session?.close() }
    }

    /**
     * Restarts a running SSH session (app-routing change or another server): debounce, disconnect,
     * short pause, reconnect through the server selected at that moment. The reconnect waits for the
     * stopped session to let go (see [ConnectVpnUseCase]). A restart that already began finishes
     * first; a pending one is replaced by the newest request; [disconnect] and [connect] cancel
     * either. Intent is re-checked after each pause and once the old session has let go: a restart
     * is dropped when its session has ended, another mode or a newer Server session (the quick tile,
     * Always-on VPN) has taken the VPN over, or the user has stopped the session since the request
     * (the notification, the quick tile; [SshStopRequests]). Once it has stopped the session it runs
     * on in [applicationScope] when the ViewModel is cleared (the Activity finished or was swiped
     * away), so the VPN is not left off.
     */
    private fun restartSession(settings: AppSettings) {
        if (settings.requiresSelectedAppsButHasNone()) {
            showNoSelectedAppsDialog.value = true
            return
        }
        if (sessionRestartJob?.isActive == true) {
            if (restartStoppedSession != null) return
            sessionRestartJob?.cancel()
        }
        if (!vpnState.value.canDisconnect()) return

        // While the old session tears down its state still shows it running: only this count tells
        // the restart's own stop from one the user made.
        val stopRequestsAtRequest = sshStopRequests.current
        val isStillWanted = { sshStopRequests.current == stopRequestsAtRequest }
        sessionRestartJob = applicationScope.launch(Dispatchers.Main.immediate) {
            delay(SETTINGS_CHANGE_DEBOUNCE_MS)
            // The session may have ended meanwhile (notification Stop, quick tile, another mode).
            if (!isStillWanted() || !vpnConnectionRepository.currentState.canDisconnect()) return@launch
            val latestSettings = appSettingsRepository.settings.value
            if (latestSettings.requiresSelectedAppsButHasNone()) {
                showNoSelectedAppsDialog.value = true
                return@launch
            }
            val restartJob = coroutineContext.job
            // The session stopped below: only while that one still owns the VPN is it worth waiting for.
            val stoppedSession = connectVpnUseCase.latestSession
            restartStoppedSession = stoppedSession
            try {
                disconnectVpnUseCase(forRestart = true)
                delay(SETTINGS_RECONNECT_DELAY_MS)
                if (!isStillWanted() ||
                    !canContinueServerRestart(
                        state = vpnConnectionRepository.currentState,
                        stoppedSession = stoppedSession,
                        latestSession = connectVpnUseCase.latestSession,
                    )
                ) {
                    return@launch
                }
                reconnectAfterRestart(stoppedSession, isStillWanted)
            } finally {
                // A cancelled restart must not clear the session of the restart that replaced it.
                if (sessionRestartJob === restartJob) restartStoppedSession = null
            }
        }
    }

    private suspend fun reconnectAfterRestart(stoppedSession: Long, isStillWanted: () -> Boolean) {
        val result = try {
            connectVpnUseCase(
                preserveDiagnostics = true,
                stoppedSession = stoppedSession,
                isStillWanted = isStillWanted,
            )
        } catch (cancellation: CancellationException) {
            // Disconnect or Connect cancelled the restart: publish nothing.
            throw cancellation
        } catch (error: Exception) {
            reportConnectFailure("Couldn’t reconnect", error)
            return
        }
        reportUnstartedConnect("Couldn’t reconnect", result)
    }

    /**
     * A connect that started nothing and could not say so through the connection state, shown as a
     * toast: the previous session did not let go in time, or a precondition failed while another
     * session owned that state (setError would clear its ownership, and its loops would then exit
     * without tearing the tunnel down). A skipped connect needs no message.
     */
    private fun reportUnstartedConnect(title: String, result: ConnectVpnResult) {
        val detail = when (result) {
            ConnectVpnResult.PreviousSessionActive -> PREVIOUS_SESSION_ACTIVE_DETAIL
            is ConnectVpnResult.Rejected -> result.message.takeUnless { result.published }
            ConnectVpnResult.Started,
            ConnectVpnResult.Skipped,
            -> null
        } ?: return
        vpnConnectionRepository.appendDiagnostic("$title: $detail")
        notify(title, detail)
    }

    /**
     * A connect that threw. Its error becomes the connection state only while no session owns that
     * state: an owner (the stopping Server session of a restart, a Routes / Auto session) would lose
     * its ownership to setError, and its loops would then exit without tearing the tunnel down. A
     * service that could not start has already released its own state in [ConnectVpnUseCase].
     */
    private fun reportConnectFailure(title: String, error: Exception) {
        val message = error.message ?: "Unknown connection error"
        if (canPublishVpnStartFailure(vpnConnectionRepository.currentState)) {
            vpnConnectionRepository.setError(uiState.value.selectedConfig?.id, message)
        } else {
            vpnConnectionRepository.appendDiagnostic("Connect failed while another session owns the VPN: $message")
            notify(title, message)
        }
    }

    private fun cancelSessionRestart() {
        sessionRestartJob?.cancel()
        sessionRestartJob = null
        restartStoppedSession = null
    }

    private fun notify(message: String, detail: String? = null) {
        noticeSequence += 1
        sessionNotice.value = ServerSessionNotice(noticeSequence, message, detail)
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
        val (output, firstLineNumber) = synchronized(terminalLock) {
            if (terminalGeneration != generation) return
            terminalOutputPublishJob = null
            terminalOutputBuffer.snapshot() to terminalOutputBuffer.droppedLineBreaks
        }
        terminalState.update {
            if (isTerminalGenerationCurrent(generation)) {
                it.copy(
                    isOpen = true,
                    isConnecting = false,
                    output = output,
                    outputRevision = it.outputRevision + 1L,
                    firstLineNumber = firstLineNumber,
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
            val firstLineNumber = terminalOutputBuffer.droppedLineBreaks
            terminalOutputBuffer.clear()
            terminalGeneration += 1
            ClosedTerminalState(terminalGeneration, finalOutput, firstLineNumber)
        }
        terminalState.update {
            if (isTerminalGenerationCurrent(closedState.generation)) {
                it.copy(
                    isOpen = false,
                    isConnecting = false,
                    output = closedState.output,
                    outputRevision = it.outputRevision + 1L,
                    firstLineNumber = closedState.firstLineNumber,
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

    internal companion object {
        const val SETTINGS_CHANGE_DEBOUNCE_MS = 250L
        const val SETTINGS_RECONNECT_DELAY_MS = 450L
        const val TERMINAL_OUTPUT_UI_BATCH_MS = 250L
        const val MAX_TERMINAL_OUTPUT_CHARACTERS = 64 * 1_024
        const val PREVIOUS_SESSION_ACTIVE_DETAIL = "The previous connection is still stopping. Try again in a moment."
    }
}

/**
 * After the restart's pause: reconnect while the Server session it stopped ([stoppedSession]) is still
 * stopping or already gone. The restart is dropped when another mode owns the VPN (the user started it
 * meanwhile) or when a newer Server session was published ([latestSession] moved on): the quick tile
 * or Always-on VPN started one after the stopped session let go, with the current server and settings,
 * whether it is still running or has already ended.
 */
internal fun canContinueServerRestart(
    state: VpnConnectionState,
    stoppedSession: Long,
    latestSession: Long,
): Boolean {
    if (latestSession != stoppedSession) return false
    return state.sessionOwner == null || state.sessionOwner == VpnSessionOwner.SHADOW_SSH
}

private data class TunnelCheckState(
    val isRunning: Boolean = false,
    val result: TunnelCheckResult = TunnelCheckResult.IDLE,
    val latencyMs: Long? = null,
    val target: String? = null,
)

internal data class TunnelCheckSuccess(
    val target: String,
    val latencyMs: Long,
)

/** Reads `Tunnel check succeeded: <host>:<port> reachable through SSH in <n>ms` (SshConnectionManager). */
internal fun parseTunnelCheckSuccess(line: String): TunnelCheckSuccess? {
    val match = TUNNEL_CHECK_SUCCESS.find(line) ?: return null
    val latencyMs = match.groupValues[2].toLongOrNull() ?: return null
    return TunnelCheckSuccess(target = match.groupValues[1], latencyMs = latencyMs)
}

private val TUNNEL_CHECK_SUCCESS = Regex("""Tunnel check succeeded: (\S+) reachable through SSH in (\d+)ms""")

private data class ClosedTerminalState(
    val generation: Long,
    val output: String,
    val firstLineNumber: Long,
)

internal class BoundedTerminalOutputBuffer(
    private val maxCharacters: Int,
) {
    private val chunks = ArrayDeque<StringBuilder>()
    private var characterCount = 0

    /** Line breaks dropped from the front since the last [clear] (the first kept line's number). */
    var droppedLineBreaks: Long = 0L
        private set

    init {
        require(maxCharacters > 0)
    }

    fun append(value: String) {
        if (value.isEmpty()) return
        if (value.length >= maxCharacters) {
            val dropped = chunks.sumOf { it.countLineBreaks(it.length).toLong() } +
                value.countLineBreaks(value.length - maxCharacters)
            chunks.clear()
            val tail = value.takeLast(maxCharacters)
            appendChunked(tail)
            characterCount = tail.length
            droppedLineBreaks += dropped
            return
        }

        appendChunked(value)
        characterCount += value.length
        var charactersToRemove = (characterCount - maxCharacters).coerceAtLeast(0)
        while (charactersToRemove > 0) {
            val first = chunks.removeFirst()
            if (first.length <= charactersToRemove) {
                droppedLineBreaks += first.countLineBreaks(first.length)
                charactersToRemove -= first.length
                characterCount -= first.length
            } else {
                droppedLineBreaks += first.countLineBreaks(charactersToRemove)
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
        droppedLineBreaks = 0L
    }

    /** `\n` in the first [end] characters. */
    private fun CharSequence.countLineBreaks(end: Int): Int {
        var count = 0
        for (index in 0 until end) if (this[index] == '\n') count++
        return count
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
