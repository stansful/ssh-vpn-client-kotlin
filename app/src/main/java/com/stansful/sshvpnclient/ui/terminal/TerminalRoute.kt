package com.stansful.sshvpnclient.ui.terminal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.stansful.sshvpnclient.ui.designsystem.rememberClipboardCopier
import com.stansful.sshvpnclient.ui.main.MainViewModel
import com.stansful.sshvpnclient.ui.main.TerminalUiState
import com.stansful.sshvpnclient.ui.shell.Destinations
import com.stansful.sshvpnclient.ui.shell.LocalAppContainer
import com.stansful.sshvpnclient.ui.shell.activityViewModel
import com.stansful.sshvpnclient.ui.shell.navigateBack
import com.stansful.sshvpnclient.ui.shell.navigateTo
import com.stansful.sshvpnclient.ui.shell.navigateTopLevel

/**
 * SSH terminal of the connected server. The shell lives exactly as long as this screen is started
 * and the Server-mode session is connected: it opens on entry (and again after returning from the
 * background or after a reconnect), and closes on leave, `ON_STOP` and disconnect — closing never
 * touches the VPN. The typed command stays in memory only and is never saved.
 */
@Composable
fun TerminalRoute(
    navController: NavHostController,
) {
    val viewModel = activityViewModel<MainViewModel>()
    val container = LocalAppContainer.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val summaries by remember(container) { container.sshConfigRepository.observeSummaries() }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val copier = rememberClipboardCopier()
    val connected = uiState.isConnected
    val enabled = uiState.appSettings.showTerminalOnMain
    val server = summaries.firstOrNull { it.id == uiState.vpnState.activeConfigId } ?: uiState.selectedConfig
    var started by remember { mutableStateOf(false) }
    var command by remember { mutableStateOf("") }

    LifecycleStartEffect(viewModel) {
        started = true
        onStopOrDispose {
            started = false
            viewModel.closeTerminal()
        }
    }
    LaunchedEffect(started, connected, enabled) {
        when {
            started && connected && enabled -> viewModel.openTerminal()
            !connected -> viewModel.closeTerminal()
        }
    }

    val phase = terminalPhase(connected = connected, enabled = enabled, terminal = uiState.terminalState)
    LaunchedEffect(phase) {
        if (phase != TerminalPhase.Active && phase != TerminalPhase.Opening) command = ""
    }

    TerminalScreen(
        state = TerminalScreenState(
            phase = phase,
            serverName = server?.name,
            userAtHost = server?.let { "${it.username}@${it.host}" },
            output = uiState.terminalState.output,
            outputRevision = uiState.terminalState.outputRevision,
            firstLineNumber = uiState.terminalState.firstLineNumber,
            errorMessage = uiState.terminalState.errorMessage,
        ),
        command = command,
        onCommandChange = { command = it },
        onRun = {
            viewModel.setTerminalInput(command)
            viewModel.submitTerminalInput()
            command = ""
        },
        onBack = { navController.navigateBack() },
        onCopyOutput = { output -> copier.copy(label = "Terminal output", text = output, toast = null) },
        onRestart = {
            viewModel.closeTerminal()
            if (connected && enabled) viewModel.openTerminal()
        },
        onOpenSettings = { navController.navigateTopLevel(Destinations.settings()) },
        onViewActivity = { navController.navigateTo(Destinations.ACTIVITY) },
        onBackToHome = { navController.navigateTopLevel(Destinations.HOME) },
    )
}

/**
 * Maps the ViewModel's terminal state to the screen. A closed shell that produced output is "ended"
 * (the server closed it); one that never produced any failed to open.
 */
internal fun terminalPhase(connected: Boolean, enabled: Boolean, terminal: TerminalUiState): TerminalPhase = when {
    !connected -> TerminalPhase.NotConnected
    !enabled -> TerminalPhase.Off
    terminal.isConnecting -> TerminalPhase.Opening
    terminal.isOpen -> TerminalPhase.Active
    terminal.errorMessage != null ->
        if (terminal.outputRevision > 0L || terminal.output.isNotEmpty()) TerminalPhase.Ended else TerminalPhase.Failed
    else -> TerminalPhase.Opening
}
