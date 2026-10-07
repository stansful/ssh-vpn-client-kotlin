package com.stansful.sshvpnclient.ui.routes

import android.content.ClipDescription
import android.content.ClipboardManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.stansful.sshvpnclient.ui.designsystem.ToastTone
import com.stansful.sshvpnclient.ui.designsystem.readText
import com.stansful.sshvpnclient.ui.designsystem.rememberClipboardCopier
import com.stansful.sshvpnclient.ui.designsystem.rememberToasterState
import com.stansful.sshvpnclient.ui.opensource.OpenSourceViewModel
import com.stansful.sshvpnclient.ui.shell.Destinations
import com.stansful.sshvpnclient.ui.shell.LocalAppContainer
import com.stansful.sshvpnclient.ui.shell.activityViewModel
import com.stansful.sshvpnclient.ui.shell.navigateTo
import com.stansful.sshvpnclient.ui.shell.navigateTopLevel
import com.stansful.sshvpnclient.ui.shell.rememberConsentGate
import com.stansful.sshvpnclient.ui.shell.rememberVpnPermissionRequester
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Route library (phone list, list-detail on wide screens): the Routes consent gate on entry, the
 * shared [OpenSourceViewModel], the VPN permission flow, clipboard access, ViewModel notices as
 * toasts, and the same lifecycle as the old Public tab (engine re-check on resume, checks cancelled
 * when the app stops or the screen leaves).
 */
@Composable
fun RoutesRoute(
    navController: NavHostController,
) {
    val viewModel = activityViewModel<OpenSourceViewModel>()
    val routes by viewModel.uiState.collectAsStateWithLifecycle()
    val container = LocalAppContainer.current
    val context = LocalContext.current
    // The library shows its own toasts (above the connection bar), so it owns their state.
    val toaster = rememberToasterState()
    val scope = rememberCoroutineScope()
    val copier = rememberClipboardCopier()

    val consent = rememberConsentGate()
    LaunchedEffect(consent) {
        consent.requestRoutes(onGranted = {}, onDenied = { navController.navigateTopLevel(Destinations.HOME) })
    }

    // The engine can be installed elsewhere (Settings, Auto): re-read it on entry and on every resume.
    LaunchedEffect(viewModel) { viewModel.refreshXrayCoreAvailability() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshXrayCoreAvailability() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.cancelChecks() }
    DisposableEffect(viewModel) { onDispose { viewModel.cancelChecks() } }

    var pendingConnect by rememberSaveable { mutableStateOf<String?>(null) }
    val runPendingConnect = {
        val pending = pendingConnect
        pendingConnect = null
        when {
            pending == PENDING_ACTIVE -> viewModel.connectFromLibrary()
            pending == PENDING_STOP_CHECK -> viewModel.stopChecksAndConnect()
            pending?.startsWith(PENDING_THROUGH) == true ->
                viewModel.connectThrough(pending.removePrefix(PENDING_THROUGH))
        }
    }
    val permission = rememberVpnPermissionRequester(
        onGranted = runPendingConnect,
        onDenied = {
            pendingConnect = null
            toaster.show("VPN permission was denied. Tap Connect to ask again.", tone = ToastTone.Error)
        },
        onCancelled = { pendingConnect = null },
    )
    val connectAfterPermission: (String) -> Unit = { pending ->
        pendingConnect = pending
        permission.request()
    }

    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    val clipboardHasText = rememberClipboardHasText(clipboard)
    // Read off the main thread by the data layer; null until it arrives (and when never synced).
    val listSyncedAt by remember(container) { container.proxySourceSynchronizer.lastSuccessfulSyncAt() }
        .collectAsStateWithLifecycle(initialValue = null)
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(CLOCK_TICK_MS)
            value = System.currentTimeMillis()
        }
    }
    val serverName by produceState<String?>(null, routes.sshActive, routes.vpnState.activeConfigId) {
        val id = routes.vpnState.activeConfigId
        value = if (routes.sshActive && id != null) {
            runCatching { container.sshConfigRepository.getById(id)?.name }.getOrNull()
        } else {
            null
        }
    }

    val actions = remember(viewModel, navController, toaster, copier, clipboard) {
        RoutesActions(
            onQueryChange = viewModel::setQuery,
            onStatusFilterChange = viewModel::setStatusFilter,
            onSelectRoute = viewModel::selectProfile,
            onBeginSelection = viewModel::beginSelection,
            onLongPressRoute = viewModel::beginBulkSelection,
            onSelectAll = {
                if (viewModel.uiState.value.profiles.none { !it.isPinned }) {
                    toaster.show("Nothing to select. Pinned routes are always skipped.", tone = ToastTone.Info)
                }
                viewModel.selectAll()
            },
            onClearSelection = viewModel::clearSelection,
            onDeleteSelected = viewModel::deleteSelected,
            onPinChange = viewModel::setPinned,
            onCopyLink = { route ->
                scope.launch {
                    val link = runCatching { viewModel.rawUri(route.id) }.getOrDefault("")
                    if (link.isEmpty()) {
                        toaster.show("Couldn’t read this route’s link", tone = ToastTone.Error)
                    } else {
                        copier.copy(label = "Route link", text = link, sensitive = true, toast = null)
                        toaster.show(
                            message = "Link copied",
                            detail = "Marked as sensitive, so Android 13+ hides it in clipboard previews.",
                        )
                    }
                }
            },
            onEditRoute = { id -> viewModel.openEditor(id) },
            onDeleteRoute = viewModel::deleteProfile,
            onCheckRoute = viewModel::checkRoute,
            onRefresh = { viewModel.synchronize(force = true) },
            onCheckAll = viewModel::checkAll,
            onCancelCheck = viewModel::cancelChecks,
            onRequestRemoveUnavailable = viewModel::requestRemoveUnavailable,
            onConfirmRemoveUnavailable = viewModel::removeUnavailableExceptPinned,
            onDismissRemoveUnavailable = viewModel::dismissRemoveUnavailableConfirmation,
            onRiskExpandedChange = viewModel::setOpenSourceRiskBannerExpanded,
            onAddRoutes = { viewModel.openEditor() },
            onImportFromClipboard = viewModel::showBulkImport,
            onEditorChange = viewModel::updateEditor,
            onEditorSave = viewModel::saveEditor,
            onImportText = { text ->
                viewModel.importClipboard(text)
                viewModel.dismissAddRoutes()
            },
            onDismissAddSheet = viewModel::dismissAddRoutes,
            readClipboard = { clipboard?.primaryClip.readText(context) },
            onOpenActivity = { navController.navigateTo(Destinations.ACTIVITY) },
            onOpenHome = { navController.navigateTopLevel(Destinations.HOME) },
            onGetEngine = { navController.navigateTopLevel(Destinations.settings(Destinations.SECTION_ENGINE)) },
            onConnect = { connectAfterPermission(PENDING_ACTIVE) },
            onConnectThrough = { id -> connectAfterPermission(PENDING_THROUGH + id) },
            onStopCheckAndConnect = { connectAfterPermission(PENDING_STOP_CHECK) },
            onReconnectThrough = viewModel::switchToProfile,
            onDisconnect = viewModel::disconnect,
            onDismissNoApps = viewModel::dismissNoSelectedAppsDialog,
            onChooseApps = {
                viewModel.dismissNoSelectedAppsDialog()
                navController.navigateTo(Destinations.APP_ROUTING)
            },
            lastCheckedAt = viewModel::lastCheckedAt,
            onNoticeShown = viewModel::clearMessage,
        )
    }

    RoutesScreen(
        state = RoutesScreenState(
            routes = routes,
            nowMillis = now,
            listSyncedAt = listSyncedAt,
            serverName = serverName,
            clipboardHasText = clipboardHasText,
        ),
        actions = actions,
        linkParser = container.proxyShareLinkParser,
        toaster = toaster,
    )
}

/** Whether the clipboard holds text, kept current while the screen is shown (reading it is not needed). */
@Composable
private fun rememberClipboardHasText(clipboard: ClipboardManager?): Boolean {
    var hasText by remember(clipboard) { mutableStateOf(clipboard.holdsText()) }
    DisposableEffect(clipboard) {
        val listener = ClipboardManager.OnPrimaryClipChangedListener { hasText = clipboard.holdsText() }
        clipboard?.addPrimaryClipChangedListener(listener)
        onDispose { clipboard?.removePrimaryClipChangedListener(listener) }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { hasText = clipboard.holdsText() }
    return hasText
}

private fun ClipboardManager?.holdsText(): Boolean {
    val description = this?.takeIf { it.hasPrimaryClip() }?.primaryClipDescription ?: return false
    return description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) ||
        description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML) ||
        description.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST)
}

private const val PENDING_ACTIVE = "active"
private const val PENDING_STOP_CHECK = "stop-check"
private const val PENDING_THROUGH = "through:"
private const val CLOCK_TICK_MS = 60_000L
