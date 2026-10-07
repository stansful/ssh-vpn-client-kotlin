package com.stansful.sshvpnclient.ui.activity

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.stansful.sshvpnclient.AppContainer
import com.stansful.sshvpnclient.domain.model.VpnConnectionState
import com.stansful.sshvpnclient.domain.model.VpnConnectionStatus
import com.stansful.sshvpnclient.domain.model.VpnSessionOwner
import com.stansful.sshvpnclient.ui.designsystem.LocalToaster
import com.stansful.sshvpnclient.ui.designsystem.StatusTone
import com.stansful.sshvpnclient.ui.designsystem.rememberClipboardCopier
import com.stansful.sshvpnclient.ui.shell.Destinations
import com.stansful.sshvpnclient.ui.shell.LocalAppContainer
import com.stansful.sshvpnclient.ui.shell.navigateBack
import com.stansful.sshvpnclient.ui.shell.navigateTo
import com.stansful.sshvpnclient.ui.shell.navigateTopLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Connection activity: the shared diagnostics log of every mode (kept by the connection repository,
 * up to 500 lines, cleared on each new connect), with highlights, filters and copy actions.
 */
@Composable
fun ActivityRoute(
    navController: NavHostController,
) {
    val container = LocalAppContainer.current
    val repository = container.vpnConnectionRepository
    val vpnState by repository.state.collectAsStateWithLifecycle(initialValue = repository.currentState)
    val lastOwner by container.lastVpnSessionStore.lastOwner.collectAsStateWithLifecycle()
    val activeOwner = vpnState.sessionOwner.takeIf { vpnState.status in ACTIVE_STATUSES }
    val activeId = vpnState.activeConfigId.takeIf { activeOwner != null }
    val sessionName by remember(container, activeOwner, activeId) { sessionNameFlow(container, activeOwner, activeId) }
        .collectAsStateWithLifecycle(initialValue = null)
    val selectedServer by remember(container) { container.sshConfigRepository.observeSelectedSummary() }
        .collectAsStateWithLifecycle(initialValue = null)
    val tracker = remember { ActivityLogTracker() }
    val lines = remember(vpnState.diagnostics) { tracker.update(vpnState.diagnostics) }
    var cleared by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(lines.isNotEmpty()) {
        if (lines.isNotEmpty()) cleared = false
    }
    val copier = rememberClipboardCopier()
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val owner = activeOwner ?: lastOwner
    val sshSession = activeOwner == VpnSessionOwner.SHADOW_SSH
    val serverId = if (sshSession) activeId else selectedServer?.id
    val serverName = if (sshSession) sessionName else selectedServer?.name

    ActivityScreen(
        state = ActivityScreenState(
            lines = lines,
            session = activitySession(
                vpnState = vpnState,
                owner = owner,
                active = activeOwner != null,
                name = sessionName,
                startedAt = lines.firstOrNull()?.time,
                hasLines = lines.isNotEmpty(),
            ),
            live = activeOwner != null,
            sshMode = owner == null || owner == VpnSessionOwner.SHADOW_SSH,
            serverName = serverName,
            canEditServer = serverId != null,
            cleared = cleared,
        ),
        onBack = { navController.navigateBack() },
        onCopyAll = {
            val snapshot = vpnState.diagnostics
            scope.launch {
                val text = withContext(Dispatchers.Default) { snapshot.joinToString(separator = "\n") }
                copier.copy(label = DIAGNOSTICS_CLIP_LABEL, text = text, toast = null)
                toaster.show(
                    message = if (snapshot.size == 1) "Copied 1 line" else "Copied ${snapshot.size} lines",
                    detail = "May contain IP addresses. Check before sharing.",
                )
            }
        },
        onClear = {
            repository.clearDiagnostics()
            cleared = true
            toaster.show("Activity cleared")
        },
        onCopyLine = { line ->
            copier.copy(label = DIAGNOSTICS_CLIP_LABEL, text = line.raw, toast = null)
            toaster.show("Line copied")
        },
        onCopyFingerprint = { fingerprint ->
            copier.copy(label = "Server host key", text = fingerprint, sensitive = false, toast = null)
            toaster.show("Fingerprint copied")
        },
        onEditServer = { serverId?.let { navController.navigateTo(Destinations.serverEdit(it)) } },
        onManageServers = { navController.navigateTopLevel(Destinations.servers()) },
        onGoHome = { navController.navigateTopLevel(Destinations.HOME) },
    )
}

/**
 * The session card: the running session (its server / route name, mode and state), or — while
 * idle — the last session whose log is still kept. Null when there is nothing to describe.
 */
internal fun activitySession(
    vpnState: VpnConnectionState,
    owner: VpnSessionOwner?,
    active: Boolean,
    name: String?,
    startedAt: String?,
    hasLines: Boolean,
): ActivitySession? {
    if (!active && !hasLines) return null
    val mode = owner?.toSessionMode()
    val (label, tone) = when (vpnState.status) {
        VpnConnectionStatus.CONNECTING -> "Connecting" to StatusTone.Progress
        VpnConnectionStatus.CONNECTED -> "Connected" to StatusTone.Success
        VpnConnectionStatus.RECONNECTING -> "Reconnecting" to StatusTone.Progress
        VpnConnectionStatus.DISCONNECTING -> "Stopping" to StatusTone.Progress
        VpnConnectionStatus.ERROR -> "Error" to StatusTone.Error
        VpnConnectionStatus.DISCONNECTED -> "Not connected" to StatusTone.Neutral
    }
    val subtitle = listOfNotNull(mode?.label, startedAt?.takeIf { it.isNotEmpty() }?.let { "started $it" })
        .joinToString(" · ")
        .replaceFirstChar { it.uppercase() }
    return ActivitySession(
        mode = mode,
        title = if (active) name ?: mode?.label ?: "This session" else "Last session",
        subtitle = subtitle,
        statusLabel = label,
        statusTone = tone,
    )
}

private fun VpnSessionOwner.toSessionMode(): SessionMode = when (this) {
    VpnSessionOwner.SHADOW_SSH -> SessionMode.Server
    VpnSessionOwner.SMART_CONNECT -> SessionMode.Auto
    VpnSessionOwner.OPEN_SOURCE -> SessionMode.Routes
}

/** Name of the server (Server) or route (Auto / Routes) the session runs through. */
private fun sessionNameFlow(container: AppContainer, owner: VpnSessionOwner?, id: String?): Flow<String?> {
    if (owner == null || id == null) return flowOf(null)
    val name: Flow<String?> = when (owner) {
        VpnSessionOwner.SHADOW_SSH -> container.sshConfigRepository.observeSummaries()
            .map { list -> list.firstOrNull { it.id == id }?.name }
        VpnSessionOwner.SMART_CONNECT -> container.smartProxyProfileRepository.observeSummaries()
            .map { list -> list.firstOrNull { it.id == id }?.name }
        VpnSessionOwner.OPEN_SOURCE -> container.proxyProfileRepository.observeSummaries()
            .map { list -> list.firstOrNull { it.id == id }?.name }
    }
    return name.distinctUntilChanged()
}

private val ACTIVE_STATUSES = setOf(
    VpnConnectionStatus.CONNECTING,
    VpnConnectionStatus.CONNECTED,
    VpnConnectionStatus.RECONNECTING,
    VpnConnectionStatus.DISCONNECTING,
)

/** Clip label of copied log text (unchanged from the old diagnostics panels). */
private const val DIAGNOSTICS_CLIP_LABEL = "Connection diagnostics"
