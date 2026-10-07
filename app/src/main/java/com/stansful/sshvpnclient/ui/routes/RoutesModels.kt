package com.stansful.sshvpnclient.ui.routes

import androidx.compose.runtime.Immutable
import com.stansful.sshvpnclient.domain.model.ProxyProfileSource
import com.stansful.sshvpnclient.domain.model.ProxyProfileSummary
import com.stansful.sshvpnclient.domain.model.ProxyProtocol
import com.stansful.sshvpnclient.domain.model.ProxyTestStatus
import com.stansful.sshvpnclient.domain.model.ProxyTransport
import com.stansful.sshvpnclient.domain.model.VpnConnectionStatus
import com.stansful.sshvpnclient.domain.model.VpnSessionOwner
import com.stansful.sshvpnclient.ui.designsystem.StatusTone
import com.stansful.sshvpnclient.ui.opensource.OpenSourceUiState
import com.stansful.sshvpnclient.ui.opensource.RouteStatusFilter
import java.util.Locale

/** What a route card shows on its status slot. */
@Immutable
internal sealed interface RouteState {
    data class Available(val latencyMs: Long?) : RouteState
    data object Unavailable : RouteState
    data object Unsupported : RouteState
    data object NotChecked : RouteState
    data object Outdated : RouteState
    data object Checking : RouteState
}

/** Checking (this route's single check) > Outdated > last check result. */
internal fun ProxyProfileSummary.routeState(checkingRouteId: String?): RouteState = when {
    id == checkingRouteId -> RouteState.Checking
    isStale -> RouteState.Outdated
    lastTestStatus == ProxyTestStatus.AVAILABLE -> RouteState.Available(lastLatencyMs)
    lastTestStatus == ProxyTestStatus.UNAVAILABLE -> RouteState.Unavailable
    lastTestStatus == ProxyTestStatus.UNSUPPORTED -> RouteState.Unsupported
    else -> RouteState.NotChecked
}

/** Words for TalkBack and the route sheet ("112 ms", "unavailable", …). */
internal fun RouteState.spoken(): String = when (this) {
    is RouteState.Available -> latencyMs?.let { "$it ms" } ?: "available"
    RouteState.Unavailable -> "unavailable"
    RouteState.Unsupported -> "unsupported"
    RouteState.NotChecked -> "not checked"
    RouteState.Outdated -> "outdated"
    RouteState.Checking -> "checking"
}

/** `vless · 198.51.100.7:8443`. */
internal fun ProxyProfileSummary.endpointLine(): String = "${protocol.scheme} · ${hostPort()}"

/** `198.51.100.7:8443` (IPv6 hosts keep their brackets). */
internal fun ProxyProfileSummary.hostPort(): String = "$host:$port"

/** `XHTTP · REALITY` (the card tag is upper-cased by the tag itself). */
internal fun ProxyProfileSummary.transportLine(): String = "${transport.label()} · ${security.name}"

internal fun ProxyTransport.label(): String = name.replace('_', ' ')

internal fun ProxyProtocol.displayName(): String = when (this) {
    ProxyProtocol.VLESS -> "VLESS"
    ProxyProtocol.VMESS -> "VMess"
    ProxyProtocol.TROJAN -> "Trojan"
    ProxyProtocol.HYSTERIA2 -> "Hysteria 2"
}

/** Added by the user (a link or the clipboard), not from the public list. */
internal val ProxyProfileSummary.isManual: Boolean get() = source != ProxyProfileSource.REMOTE

/** "just now", "12 min ago", "2 h ago", "3 d ago". */
internal fun formatAgo(nowMillis: Long, thenMillis: Long): String {
    val minutes = ((nowMillis - thenMillis).coerceAtLeast(0L)) / MILLIS_PER_MINUTE
    return when {
        minutes < 1 -> "just now"
        minutes < MINUTES_PER_HOUR -> "$minutes min ago"
        minutes < MINUTES_PER_DAY -> "${minutes / MINUTES_PER_HOUR} h ago"
        else -> "${minutes / MINUTES_PER_DAY} d ago"
    }
}

/** 43_201_331 → "41.2 MiB". */
internal fun formatMebibytes(bytes: Long): String =
    String.format(Locale.US, "%.1f MiB", bytes / BYTES_PER_MEBIBYTE)

/** Who holds the VPN right now, as the Routes screens see it. */
@Immutable
internal sealed interface RoutesSession {
    data object Off : RoutesSession
    data class Routes(val phase: VpnConnectionStatus, val tunnelId: String?) : RoutesSession
    data object Server : RoutesSession
    data object Auto : RoutesSession

    /** A Routes start failed for [routeId] (only failures of a library route are shown here). */
    data class Failed(val routeId: String, val message: String) : RoutesSession
}

private val LiveStatuses = setOf(
    VpnConnectionStatus.CONNECTING,
    VpnConnectionStatus.CONNECTED,
    VpnConnectionStatus.RECONNECTING,
)

internal fun OpenSourceUiState.session(): RoutesSession {
    val vpn = vpnState
    return when {
        xrayConnected -> RoutesSession.Routes(vpn.status, vpn.activeConfigId)
        sshActive -> RoutesSession.Server
        vpn.sessionOwner == VpnSessionOwner.SMART_CONNECT && vpn.status in LiveStatuses -> RoutesSession.Auto
        vpn.status == VpnConnectionStatus.ERROR && vpn.activeTransport == null &&
            vpn.activeConfigId != null && vpn.activeConfigId in allProfileIds ->
            RoutesSession.Failed(vpn.activeConfigId, vpn.errorMessage ?: "Unknown connection error")
        else -> RoutesSession.Off
    }
}

/** The route a running Routes session carries, if it is still in the library. */
internal fun OpenSourceUiState.tunnelRoute(): ProxyProfileSummary? {
    val session = session() as? RoutesSession.Routes ?: return null
    return profileWithId(session.tunnelId)
}

internal fun OpenSourceUiState.profileWithId(id: String?): ProxyProfileSummary? {
    if (id == null) return null
    if (activeProfile?.id == id) return activeProfile
    return library.firstOrNull { it.id == id }
}

/** The selected routes, hidden ones included, in library order. */
internal fun OpenSourceUiState.selectedRoutes(): List<ProxyProfileSummary> =
    library.filter { it.id in selectedIds }

/**
 * The route that becomes active when [removed] routes leave the library: the active one if it
 * stays and is current, else the newest current route (the repository's fallback rule).
 */
internal fun OpenSourceUiState.nextActiveAfter(removed: Set<String>): ProxyProfileSummary? {
    val active = activeProfile
    if (active != null && active.id !in removed && !active.isStale) return active
    return library.filter { it.id !in removed && !it.isStale }.maxByOrNull { it.updatedAt }
}

/** Why "Check all" can't run now, or null. */
internal fun OpenSourceUiState.checkAllBlockReason(): String? = when {
    !xrayCoreAvailable -> "Install the Xray engine to run checks"
    anyXrayRuntimeActive -> "Disconnect to run checks"
    isRemovingUnavailable -> "Removing unavailable routes…"
    isChecking && checkingRouteId != null -> "One route is being checked…"
    counts.total == 0 -> "Add routes to check them"
    else -> null
}

/** Why one route can't be checked now, or null. */
internal fun OpenSourceUiState.singleCheckBlockReason(): String? = when {
    !xrayCoreAvailable -> "Install the Xray engine first"
    anyXrayRuntimeActive -> "Disconnect to run checks"
    isCheckingAll -> "Available when the full check ends"
    isRemovingUnavailable -> "Wait until the cleanup finishes"
    isChecking -> "Another route is being checked"
    else -> null
}

/** The subtitle of "Remove unavailable": the reason it is off, or "Pinned routes are kept". */
internal fun OpenSourceUiState.removeUnavailableNote(): String = when {
    isRemovingUnavailable -> "Removing…"
    isChecking -> "Available when the check ends"
    isSyncing -> "Available when the refresh ends"
    anyXrayRuntimeActive -> "Disconnect first"
    unavailableUnpinnedCount == 0 -> "Nothing to remove"
    else -> REMOVE_UNAVAILABLE_READY
}

internal const val REMOVE_UNAVAILABLE_READY = "Pinned routes are kept"

internal fun RouteStatusFilter.label(): String = when (this) {
    RouteStatusFilter.ALL -> "All"
    RouteStatusFilter.AVAILABLE -> "Available"
    RouteStatusFilter.PINNED -> "Pinned"
    RouteStatusFilter.NOT_CHECKED -> "Not checked"
}

/** Overline above the list: "All routes", "Pinned", "Search results", "Results · Pinned". */
internal fun OpenSourceUiState.listOverline(): String {
    val filterLabel = if (statusFilter == RouteStatusFilter.ALL) "All routes" else statusFilter.label()
    return when {
        query.isBlank() -> filterLabel
        statusFilter == RouteStatusFilter.ALL -> "Search results"
        else -> "Results · ${statusFilter.label()}"
    }
}

/** Selected routes that the current search or filter hides (still counted, still deleted). */
internal fun OpenSourceUiState.hiddenSelectedCount(): Int {
    if (selectedIds.isEmpty()) return 0
    val visible = profiles.mapTo(hashSetOf()) { it.id }
    return selectedIds.count { it !in visible }
}

/** "Select routes" / "3 selected". */
internal fun selectionTitle(count: Int): String = if (count == 0) "Select routes" else "$count selected"

/** The sky hint under the selection bar. */
internal fun OpenSourceUiState.selectionHint(): String {
    val hidden = hiddenSelectedCount()
    return when {
        selectedIds.isEmpty() -> "Tap routes to select them. Select all skips pinned routes."
        hidden > 0 -> "Select all skips pinned routes. $hidden selected " +
            (if (hidden == 1) "route is" else "routes are") + " hidden by the filter and still counted."
        else -> "Select all skips pinned routes."
    }
}

/** Every visible unpinned route is already selected. */
internal fun OpenSourceUiState.allSelectableSelected(): Boolean {
    val selectable = profiles.filterNot(ProxyProfileSummary::isPinned)
    return selectable.isNotEmpty() && selectable.all { it.id in selectedIds } && selectedIds.size == selectable.size
}

/** Mini connection bar of the phone library. */
@Immutable
internal data class ConnectionBarModel(
    val status: String,
    val statusTone: StatusTone,
    val route: String,
    val dotTone: StatusTone,
    val dotBlink: Boolean,
    val button: BarButton,
) {
    val description: String get() = "Open Home · $status · $route"
}

internal enum class BarButton {
    Connect,
    AskStopCheck,
    Disabled,
    GetEngine,
    Reconnect,
    Switch,
    Disconnect,
    Stop,
    TryAgain,
}

internal fun OpenSourceUiState.connectionBar(serverName: String?): ConnectionBarModel {
    val active = activeProfile
    val activeName = active?.name ?: "No route selected"
    return when (val session = session()) {
        is RoutesSession.Routes -> routesSessionBar(session, active)
        RoutesSession.Server, RoutesSession.Auto -> {
            val owner = if (session == RoutesSession.Server) "Server mode" else "Auto mode"
            ConnectionBarModel(
                status = if (session == RoutesSession.Server && serverName != null) {
                    "$owner connected · $serverName"
                } else {
                    "$owner connected"
                },
                statusTone = StatusTone.Success,
                route = activeName,
                dotTone = StatusTone.Success,
                dotBlink = false,
                button = when {
                    active == null || active.isStale -> BarButton.Disabled
                    !xrayCoreAvailable -> BarButton.GetEngine
                    isRemovingUnavailable || (isChecking && checkingRouteId != null) -> BarButton.Disabled
                    else -> BarButton.Switch
                },
            )
        }
        is RoutesSession.Failed -> idleBar(
            active = active,
            failure = session.takeIf { it.routeId == active?.id }?.message,
        )
        RoutesSession.Off -> idleBar(active = active, failure = null)
    }
}

private fun OpenSourceUiState.routesSessionBar(
    session: RoutesSession.Routes,
    active: ProxyProfileSummary?,
): ConnectionBarModel {
    val tunnel = profileWithId(session.tunnelId)
    val tunnelName = tunnel?.name ?: active?.name ?: "your route"
    return when (session.phase) {
        VpnConnectionStatus.CONNECTING -> ConnectionBarModel(
            status = "Connecting…",
            statusTone = StatusTone.Progress,
            route = tunnelName,
            dotTone = StatusTone.Progress,
            dotBlink = true,
            button = BarButton.Stop,
        )
        VpnConnectionStatus.RECONNECTING -> ConnectionBarModel(
            status = "Reconnecting…",
            statusTone = StatusTone.Progress,
            route = tunnelName,
            dotTone = StatusTone.Progress,
            dotBlink = true,
            button = BarButton.Disconnect,
        )
        else -> if (active != null && session.tunnelId != null && active.id != session.tunnelId) {
            ConnectionBarModel(
                status = "Connected · $tunnelName",
                statusTone = StatusTone.Success,
                route = if (active.isStale) "${active.name} is outdated" else "Next: ${active.name}",
                dotTone = StatusTone.Success,
                dotBlink = false,
                button = if (active.isStale) BarButton.Disconnect else BarButton.Reconnect,
            )
        } else {
            ConnectionBarModel(
                status = "Connected",
                statusTone = StatusTone.Success,
                route = tunnelName,
                dotTone = StatusTone.Success,
                dotBlink = false,
                button = BarButton.Disconnect,
            )
        }
    }
}

private fun OpenSourceUiState.idleBar(active: ProxyProfileSummary?, failure: String?): ConnectionBarModel {
    var status = "Not connected"
    var tone = StatusTone.Neutral
    val button = when {
        active == null -> BarButton.Disabled
        active.isStale -> {
            status = "Outdated · pick a current route"
            tone = StatusTone.Error
            BarButton.Disabled
        }
        !xrayCoreAvailable -> {
            status = "Needs the Xray engine"
            tone = StatusTone.Progress
            BarButton.GetEngine
        }
        isRemovingUnavailable -> {
            status = "Wait for the cleanup to finish"
            BarButton.Disabled
        }
        isChecking && checkingRouteId != null -> {
            status = "Wait for the route check"
            BarButton.Disabled
        }
        isChecking -> BarButton.AskStopCheck
        failure != null -> BarButton.TryAgain
        else -> BarButton.Connect
    }
    if (failure != null && button != BarButton.Disabled) {
        status = "Couldn’t connect · $failure"
        tone = StatusTone.Error
    }
    return ConnectionBarModel(
        status = status,
        statusTone = tone,
        route = active?.name ?: "No route selected",
        dotTone = if (failure != null) StatusTone.Error else StatusTone.Neutral,
        dotBlink = false,
        button = button,
    )
}

/** Download size of the Xray engine for this device, when the release has been looked up. */
internal fun OpenSourceUiState.engineDownloadSize(): String? {
    val update = xrayCoreUpdateState
    val release = update.release ?: return null
    val asset = release.assets.firstOrNull { it.abi == update.runtimeAbi } ?: return null
    return formatMebibytes(asset.sizeBytes)
}

private const val MILLIS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60L
private const val MINUTES_PER_DAY = 1_440L
private const val BYTES_PER_MEBIBYTE = 1_048_576.0
