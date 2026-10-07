package com.stansful.sshvpnclient.ui.routes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.stansful.sshvpnclient.domain.model.ProxyProfileSummary
import com.stansful.sshvpnclient.ui.opensource.OpenSourceUiState
import com.stansful.sshvpnclient.ui.opensource.RouteStatusFilter

/** Data the route library shows besides the ViewModel state. */
@Immutable
internal data class RoutesScreenState(
    val routes: OpenSourceUiState,
    /** "Now" for "list updated 2 h ago" (ticks once a minute). */
    val nowMillis: Long,
    /** Last successful public-list sync, when one happened. */
    val listSyncedAt: Long? = null,
    /** Name of the server a running Server-mode session goes through. */
    val serverName: String? = null,
    /** The clipboard holds text (enables the Add sheet's "Paste"). */
    val clipboardHasText: Boolean = false,
)

/** Everything the library can ask for; the route wires these to the ViewModel and navigation. */
@Immutable
internal class RoutesActions(
    val onQueryChange: (String) -> Unit = {},
    val onStatusFilterChange: (RouteStatusFilter) -> Unit = {},
    val onSelectRoute: (String) -> Unit = {},
    val onBeginSelection: () -> Unit = {},
    val onLongPressRoute: (String) -> Unit = {},
    val onSelectAll: () -> Unit = {},
    val onClearSelection: () -> Unit = {},
    val onDeleteSelected: () -> Unit = {},
    val onPinChange: (String, Boolean) -> Unit = { _, _ -> },
    val onCopyLink: (ProxyProfileSummary) -> Unit = {},
    val onEditRoute: (String) -> Unit = {},
    val onDeleteRoute: (String) -> Unit = {},
    val onCheckRoute: (String) -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onCheckAll: () -> Unit = {},
    val onCancelCheck: () -> Unit = {},
    val onRequestRemoveUnavailable: () -> Unit = {},
    val onConfirmRemoveUnavailable: () -> Unit = {},
    val onDismissRemoveUnavailable: () -> Unit = {},
    val onRiskExpandedChange: (Boolean) -> Unit = {},
    val onAddRoutes: () -> Unit = {},
    val onImportFromClipboard: () -> Unit = {},
    val onEditorChange: (String) -> Unit = {},
    val onEditorSave: () -> Unit = {},
    val onImportText: (String) -> Unit = {},
    val onDismissAddSheet: () -> Unit = {},
    /** The clipboard's text, read off the main thread (a copied file is read as text). */
    val readClipboard: suspend () -> String? = { null },
    val onOpenActivity: () -> Unit = {},
    val onOpenHome: () -> Unit = {},
    val onGetEngine: () -> Unit = {},
    /** Connect through the active route (after the VPN permission). */
    val onConnect: () -> Unit = {},
    /** Make [String] active and connect through it (tablet "Connect" / "Switch to this route"). */
    val onConnectThrough: (String) -> Unit = {},
    val onStopCheckAndConnect: () -> Unit = {},
    /** Restart the running Routes session through route [String]. */
    val onReconnectThrough: (String) -> Unit = {},
    val onDisconnect: () -> Unit = {},
    val onDismissNoApps: () -> Unit = {},
    val onChooseApps: () -> Unit = {},
    val lastCheckedAt: suspend (String) -> Long? = { null },
    /** A ViewModel notice has been shown as a toast. */
    val onNoticeShown: () -> Unit = {},
)

/** Overlays the library opens itself (the add sheet and remove confirmation are ViewModel state). */
@Immutable
internal sealed interface RoutesOverlay {
    data class RouteActions(val routeId: String) : RoutesOverlay
    data class DeleteRoute(val routeId: String) : RoutesOverlay
    data object DeleteSelected : RoutesOverlay

    /** "Switch to Routes?" while Server or Auto is connected; [routeId] = the route to connect (tablet). */
    data class SwitchMode(val routeId: String? = null) : RoutesOverlay
    data object StopCheckAndConnect : RoutesOverlay
}

/** UI-only state of the library: search field, menu, hidden notice, open overlay, viewed route. */
@Stable
internal class RoutesUiController(
    searchOpen: Boolean = false,
    menuOpen: Boolean = false,
    noticeHidden: Boolean = false,
    overlay: RoutesOverlay? = null,
    viewedRouteId: String? = null,
) {
    var searchOpen by mutableStateOf(searchOpen)
    var menuOpen by mutableStateOf(menuOpen)
    var noticeHidden by mutableStateOf(noticeHidden)
    var overlay by mutableStateOf(overlay)
    var viewedRouteId by mutableStateOf(viewedRouteId)

    companion object {
        val Saver: Saver<RoutesUiController, Any> = Saver(
            save = { listOf(it.searchOpen, it.noticeHidden, it.viewedRouteId) },
            restore = { saved ->
                val values = saved as List<*>
                RoutesUiController(
                    searchOpen = values[0] as Boolean,
                    noticeHidden = values[1] as Boolean,
                    viewedRouteId = values[2] as String?,
                )
            },
        )
    }
}

@Composable
internal fun rememberRoutesUiController(): RoutesUiController =
    rememberSaveable(saver = RoutesUiController.Saver) { RoutesUiController() }
