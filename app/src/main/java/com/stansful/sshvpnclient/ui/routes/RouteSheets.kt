package com.stansful.sshvpnclient.ui.routes

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stansful.sshvpnclient.domain.model.ProxyProfileSummary
import com.stansful.sshvpnclient.ui.designsystem.DISABLED_ALPHA
import com.stansful.sshvpnclient.ui.designsystem.IconTile
import com.stansful.sshvpnclient.ui.designsystem.MetaTag
import com.stansful.sshvpnclient.ui.designsystem.MetaTagSize
import com.stansful.sshvpnclient.ui.designsystem.SecondaryButton
import com.stansful.sshvpnclient.ui.designsystem.ShadowBottomSheet
import com.stansful.sshvpnclient.ui.designsystem.ShadowButton
import com.stansful.sshvpnclient.ui.designsystem.ShadowButtonVariant
import com.stansful.sshvpnclient.ui.designsystem.ShadowDialog
import com.stansful.sshvpnclient.ui.designsystem.ShadowFocusIndication
import com.stansful.sshvpnclient.ui.designsystem.ShadowIcons
import com.stansful.sshvpnclient.ui.opensource.OpenSourceUiState
import com.stansful.sshvpnclient.ui.system.NoSelectedAppsDialog
import com.stansful.sshvpnclient.ui.theme.Shadow
import com.stansful.sshvpnclient.ui.theme.ShadowMotion
import com.stansful.sshvpnclient.ui.theme.ShadowShapes
import com.stansful.sshvpnclient.ui.theme.shadowTween
import kotlinx.coroutines.launch

/** Every sheet and dialog of the library (phone: Routes / RoutesSelect; tablet: TabletRoutes). */
@Composable
internal fun RoutesOverlays(
    state: RoutesScreenState,
    actions: RoutesActions,
    controller: RoutesUiController,
    tablet: Boolean,
) {
    val routes = state.routes
    val close = { controller.overlay = null }
    val overlay = controller.overlay
    val routeId = (overlay as? RoutesOverlay.RouteActions)?.routeId ?: (overlay as? RoutesOverlay.DeleteRoute)?.routeId
    val route = routes.profileWithId(routeId)
    val stale = (routeId != null && route == null) ||
        (overlay == RoutesOverlay.DeleteSelected && routes.selectedIds.isEmpty())
    // The route (or the selection) went away under the overlay, e.g. deleted elsewhere.
    LaunchedEffect(stale) { if (stale) close() }
    when {
        stale -> Unit
        overlay is RoutesOverlay.RouteActions && route != null ->
            RouteActionsSheet(route = route, routes = routes, actions = actions, controller = controller)
        overlay is RoutesOverlay.DeleteRoute && route != null ->
            DeleteRouteDialog(route = route, routes = routes, tablet = tablet, onDismiss = close) {
                close()
                actions.onDeleteRoute(route.id)
            }
        overlay == RoutesOverlay.DeleteSelected -> if (tablet) {
            DeleteSelectedDialog(routes = routes, onDismiss = close) {
                close()
                actions.onDeleteSelected()
            }
        } else {
            DeleteSelectedSheet(routes = routes, onDismiss = close) {
                close()
                actions.onDeleteSelected()
            }
        }
        overlay is RoutesOverlay.SwitchMode -> SwitchModeDialog(
            routes = routes,
            serverName = state.serverName,
            route = routes.profileWithId(overlay.routeId),
            tablet = tablet,
            onDismiss = close,
        ) {
            close()
            when {
                overlay.routeId != null -> actions.onConnectThrough(overlay.routeId)
                routes.isChecking -> actions.onStopCheckAndConnect()
                else -> actions.onConnect()
            }
        }
        overlay == RoutesOverlay.StopCheckAndConnect -> ShadowDialog(
            onDismissRequest = close,
            title = "Stop the check and connect?",
            message = "Routes can’t be tested while connected. The check stops at ${routes.checkCompleted} " +
                "of ${routes.checkTotal}; results from earlier checks are kept.",
            dismissLabel = "Keep checking",
            confirmLabel = "Stop & connect",
            onConfirm = {
                close()
                actions.onStopCheckAndConnect()
            },
        )
    }
    if (routes.showRemoveUnavailableConfirmation) {
        if (tablet) {
            RemoveUnavailableDialog(routes, actions.onDismissRemoveUnavailable, actions.onConfirmRemoveUnavailable)
        } else {
            RemoveUnavailableSheet(routes, actions.onDismissRemoveUnavailable, actions.onConfirmRemoveUnavailable)
        }
    }
    if (routes.showNoSelectedAppsDialog) {
        NoSelectedAppsDialog(onDismiss = actions.onDismissNoApps, onPickApps = actions.onChooseApps)
    }
}

/** Routes.dc.html's route sheet: check, copy, edit, delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RouteActionsSheet(
    route: ProxyProfileSummary,
    routes: OpenSourceUiState,
    actions: RoutesActions,
    controller: RoutesUiController,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val hideThen: (() -> Unit) -> Unit = { next ->
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            controller.overlay = null
            next()
        }
    }
    val (status, statusColor) = sheetStatus(route, routes)
    val checkBlock = routes.singleCheckBlockReason()
    ShadowBottomSheet(
        onDismissRequest = { controller.overlay = null },
        sheetState = sheetState,
        title = route.name,
        subtitle = route.endpointLine(),
        subtitleMono = true,
        header = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                MetaTag(text = route.transportLine(), size = MetaTagSize.Compact, uppercase = true)
                Text(status, style = Shadow.type.label, color = statusColor)
            }
        },
    ) {
        // Routes.dc.html: 18 dp under the header (the sheet header ends 14 dp below), 24 dp above the
        // sheet's bottom edge (the sheet pads 22).
        SheetGroup(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 2.dp)) {
            SheetActionRow(
                icon = ShadowIcons.Shield,
                title = "Check this route",
                subtitle = checkBlock ?: "Tests it through the Xray engine",
                enabled = checkBlock == null,
                onClick = { hideThen { actions.onCheckRoute(route.id) } },
            )
            SheetDivider()
            SheetActionRow(
                icon = ShadowIcons.Copy,
                title = "Copy link",
                subtitle = "Kept out of clipboard previews",
                onClick = { hideThen { actions.onCopyLink(route) } },
            )
            SheetDivider()
            SheetActionRow(
                icon = ShadowIcons.Edit,
                title = "Edit",
                subtitle = "Paste a new link for this route",
                chevron = true,
                onClick = { hideThen { actions.onEditRoute(route.id) } },
            )
            SheetDivider()
            SheetActionRow(
                icon = ShadowIcons.Trash,
                title = "Delete route",
                destructive = true,
                onClick = { hideThen { controller.overlay = RoutesOverlay.DeleteRoute(route.id) } },
            )
        }
    }
}

@Composable
private fun sheetStatus(route: ProxyProfileSummary, routes: OpenSourceUiState): Pair<String, Color> {
    val colors = Shadow.colors
    val (text, color) = when (val state = route.routeState(routes.checkingRouteId)) {
        RouteState.Checking -> "Checking…" to colors.amberText
        RouteState.Outdated -> "Outdated · left the public list" to colors.coralText
        is RouteState.Available ->
            (state.latencyMs?.let { "Available · $it ms" } ?: "Available") to colors.mintText
        RouteState.Unavailable -> "Unavailable on the last check" to colors.coralText
        RouteState.Unsupported -> "Not supported by the Xray engine" to colors.coralText
        RouteState.NotChecked -> "Not checked yet" to colors.ink3
    }
    return (if (route.isSelected) "$text · active" else text) to color
}

/**
 * One surface-1 group (radius 18, line border) holding sheet rows. As in the artboards (CSS), the
 * 1 dp border sits outside the rows.
 */
@Composable
internal fun SheetGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = Shadow.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(ShadowShapes.Card)
            .background(colors.surface1)
            .border(1.dp, colors.line, ShadowShapes.Card)
            .padding(OUTLINE),
        content = content,
    )
}

@Composable
internal fun SheetDivider() {
    Spacer(
        Modifier
            .padding(start = 16.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(Shadow.colors.line2),
    )
}

/** Sheet row: 20 dp icon, 15/20 title, 13/18 subtitle, optional chevron; coral when [destructive]. */
@Composable
private fun SheetActionRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
    chevron: Boolean = false,
    destructive: Boolean = false,
) {
    val colors = Shadow.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressedFill by animateColorAsState(
        targetValue = if (pressed) colors.surface2 else colors.surface2.copy(alpha = 0f),
        animationSpec = shadowTween(ShadowMotion.Small, ShadowMotion.Ease),
        label = "sheet-row-press",
    )
    val ink = if (destructive) colors.coralText else colors.ink1
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clickable(
                interactionSource = interaction,
                indication = ShadowFocusIndication,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .background(pressedFill)
            .heightIn(min = if (subtitle != null) 64.dp else 56.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (destructive) colors.coralText else colors.ink2,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = Shadow.type.rowTitle, color = ink)
            if (subtitle != null) Text(subtitle, style = Shadow.type.bodyS, color = colors.ink3)
        }
        if (chevron) {
            Icon(
                ShadowIcons.ChevronRight,
                contentDescription = null,
                tint = colors.ink3,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** Routes.dc.html (phone, inline Cancel / Delete) or TabletRoutes (stacked, with notes). */
@Composable
private fun DeleteRouteDialog(
    route: ProxyProfileSummary,
    routes: OpenSourceUiState,
    tablet: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val carrying = route.id == (routes.session() as? RoutesSession.Routes)?.tunnelId
    if (!tablet) {
        ShadowDialog(
            onDismissRequest = onDismiss,
            title = "Delete ${route.name}?",
            message = if (carrying) {
                "You’re connected through this route. The connection keeps running until you disconnect."
            } else {
                "It’s removed from your library. You can add it again from its link."
            },
            confirmLabel = "Delete",
            onConfirm = onConfirm,
            confirmVariant = ShadowButtonVariant.DangerTonal,
        )
        return
    }
    val notes = buildList {
        if (route.isSelected) {
            val next = routes.nextActiveAfter(setOf(route.id))
            add(
                "This is your active route. " +
                    (next?.let { "${it.name} takes over." } ?: "No other up-to-date route can take over."),
            )
        }
        if (carrying) add("Your current connection keeps running until you disconnect.")
    }
    ShadowDialog(
        onDismissRequest = onDismiss,
        title = "Delete ${route.name}?",
        message = if (route.isManual) {
            "It leaves your library. You added it yourself, so only its link can bring it back."
        } else {
            "It leaves your library. Public routes can come back with the next refresh."
        },
        confirmLabel = "Delete route",
        onConfirm = onConfirm,
        destructive = true,
        icon = ShadowIcons.Trash,
        modifier = Modifier.widthIn(max = TABLET_DIALOG_WIDTH),
        content = { DialogNotes(notes) },
    )
}

@Composable
private fun DeleteSelectedDialog(routes: OpenSourceUiState, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val picked = routes.selectedRoutes()
    val count = picked.size
    val hidden = routes.hiddenSelectedCount()
    val tunnelId = (routes.session() as? RoutesSession.Routes)?.tunnelId
    val notes = buildList {
        if (hidden > 0) add("Includes $hidden ${if (hidden == 1) "route" else "routes"} hidden by the current filter.")
        if (picked.any { it.isSelected }) add("Your active route is included. Another up-to-date route takes over.")
        if (tunnelId != null && picked.any { it.id == tunnelId }) {
            add("Your current connection keeps running until you disconnect.")
        }
    }
    ShadowDialog(
        onDismissRequest = onDismiss,
        title = "Delete $count ${if (count == 1) "route" else "routes"}?",
        message = "They leave your library. Public routes can come back with the next refresh.",
        confirmLabel = "Delete $count ${if (count == 1) "route" else "routes"}",
        onConfirm = onConfirm,
        destructive = true,
        icon = ShadowIcons.Trash,
        modifier = Modifier.widthIn(max = TABLET_DIALOG_WIDTH),
        content = { DialogNotes(notes) },
    )
}

@Composable
private fun RemoveUnavailableDialog(routes: OpenSourceUiState, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val count = routes.unavailableUnpinnedCount
    ShadowDialog(
        onDismissRequest = onDismiss,
        title = "Remove $count unavailable ${if (count == 1) "route" else "routes"}?",
        message = "Routes that failed their last check leave your library, outdated ones included. Pinned, " +
            "unsupported and unchecked routes stay.",
        confirmLabel = "Remove $count ${if (count == 1) "route" else "routes"}",
        onConfirm = onConfirm,
        destructive = true,
        icon = ShadowIcons.Trash,
        modifier = Modifier.widthIn(max = TABLET_DIALOG_WIDTH),
    )
}

@Composable
private fun SwitchModeDialog(
    routes: OpenSourceUiState,
    serverName: String?,
    route: ProxyProfileSummary?,
    tablet: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val server = routes.session() == RoutesSession.Server
    if (tablet) {
        val owner = if (server) "Server mode" + (serverName?.let { " ($it)" } ?: "") else "Auto"
        ShadowDialog(
            onDismissRequest = onDismiss,
            title = "Switch to ${route?.name ?: "this route"}?",
            message = "$owner is connected. Only one connection runs at a time, so switching stops it first.",
            confirmLabel = "Stop and connect",
            dismissLabel = "Stay connected",
            onConfirm = onConfirm,
            stacked = true,
            icon = ShadowIcons.Power,
            modifier = Modifier.widthIn(max = TABLET_DIALOG_WIDTH),
        )
    } else {
        val connected = if (server) {
            "Server mode is connected" + (serverName?.let { " through $it" } ?: "") + "."
        } else {
            "Auto is connected."
        }
        ShadowDialog(
            onDismissRequest = onDismiss,
            title = "Switch to Routes?",
            message = "$connected Only one connection can run at a time, so switching stops it first." +
                if (routes.isChecking) " The route check stops too." else "",
            dismissLabel = "Stay",
            confirmLabel = "Stop & switch",
            onConfirm = onConfirm,
        )
    }
}

/** Sky info notes inside a tablet dialog. */
@Composable
private fun DialogNotes(notes: List<String>) {
    notes.forEach { note -> InfoNote(text = note, modifier = Modifier.padding(top = 12.dp)) }
}

/** Sky tint note: info icon + 13/18 sky-soft text. */
@Composable
internal fun InfoNote(text: String, modifier: Modifier = Modifier) {
    val colors = Shadow.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(ShadowShapes.Banner)
            .background(colors.skyTint)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(ShadowIcons.Info, contentDescription = null, tint = colors.skySoft, modifier = Modifier.size(18.dp))
        Text(text, style = Shadow.type.bodyS, color = colors.skySoft, modifier = Modifier.weight(1f))
    }
}

/** RoutesSelect.dc.html's delete sheet: the first three routes, "and N more", notes, two buttons. */
@Composable
private fun DeleteSelectedSheet(routes: OpenSourceUiState, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val colors = Shadow.colors
    val picked = routes.selectedRoutes()
    val count = picked.size
    val hidden = routes.hiddenSelectedCount()
    val active = picked.firstOrNull { it.isSelected }
    val connected = routes.session() is RoutesSession.Routes
    val note = buildList {
        if (active != null) {
            add(
                "${active.name} is your active route. Another up-to-date route takes over." +
                    if (connected) " Your current connection keeps running." else "",
            )
        }
        if (hidden > 0) add("Includes $hidden ${if (hidden == 1) "route" else "routes"} hidden by the filter.")
    }.joinToString(" ")
    ConfirmSheet(
        title = "Delete $count ${if (count == 1) "route" else "routes"}?",
        message = "They’ll leave your library. Routes from the public list can come back after the next refresh.",
        confirmLabel = "Delete $count ${if (count == 1) "route" else "routes"}",
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    ) {
        SheetGroup(modifier = Modifier.padding(top = 20.dp)) {
            picked.take(SHEET_ROWS).forEachIndexed { index, route ->
                if (index > 0) SheetDivider()
                val (text, color) = routeStatusWord(route)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        route.name,
                        style = Shadow.type.rowTitle,
                        color = colors.ink1,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(text, style = TagLabel, color = color)
                }
            }
            if (count > SHEET_ROWS) {
                SheetDivider()
                Box(
                    modifier = Modifier
                        .heightIn(min = 44.dp)
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text("and ${count - SHEET_ROWS} more", style = Shadow.type.bodyS, color = colors.ink3)
                }
            }
        }
        if (note.isNotEmpty()) InfoNote(text = note, modifier = Modifier.padding(top = 12.dp))
    }
}

/** A route's last result in one word, coloured: "112 ms" (meter colour), "Unavailable", "Not checked"… */
@Composable
internal fun routeStatusWord(route: ProxyProfileSummary): Pair<String, Color> {
    val colors = Shadow.colors
    return when (val state = route.routeState(null)) {
        is RouteState.Available -> (state.latencyMs?.let { "$it ms" } ?: "Available") to latencyColor(state.latencyMs)
        RouteState.Unavailable -> "Unavailable" to colors.coralText
        RouteState.Unsupported -> "Unsupported" to colors.coralText
        RouteState.Outdated -> "Outdated" to colors.coralText
        RouteState.NotChecked, RouteState.Checking -> "Not checked" to colors.ink3
    }
}

/** RoutesSelect.dc.html's "Remove unavailable" sheet: removed vs kept, then two buttons. */
@Composable
private fun RemoveUnavailableSheet(routes: OpenSourceUiState, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val colors = Shadow.colors
    val count = routes.unavailableUnpinnedCount
    val kept = (routes.counts.total - count).coerceAtLeast(0)
    ConfirmSheet(
        title = "Remove $count unavailable ${if (count == 1) "route" else "routes"}?",
        message = "Pinned routes stay.",
        confirmLabel = "Remove $count ${if (count == 1) "route" else "routes"}",
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    ) {
        SheetGroup(modifier = Modifier.padding(top = 20.dp)) {
            OutcomeRow(
                icon = ShadowIcons.Close,
                tint = colors.coralText,
                container = colors.coralTint,
                title = "$count unavailable",
                subtitle = "Whole library, outdated ones included",
                outcome = "Removed",
            )
            SheetDivider()
            OutcomeRow(
                icon = ShadowIcons.Check,
                tint = colors.mintText,
                container = colors.mintTint,
                title = "$kept other ${if (kept == 1) "route" else "routes"}",
                subtitle = "Available, unsupported, not checked and pinned",
                outcome = "Kept",
            )
        }
    }
}

@Composable
private fun OutcomeRow(
    icon: ImageVector,
    tint: Color,
    container: Color,
    title: String,
    subtitle: String,
    outcome: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon = icon, size = 36.dp, iconSize = 18.dp, tint = tint, container = container, cornerRadius = 12.dp)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = Shadow.type.rowTitle, color = Shadow.colors.ink1)
            Text(subtitle, style = Shadow.type.bodyS, color = Shadow.colors.ink3)
        }
        Text(outcome, style = TagLabel, color = tint)
    }
}

/**
 * A confirmation sheet of RoutesSelect.dc.html: 48 dp coral trash tile, 24/30 title, message, [body],
 * then a coral confirm above a Cancel button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfirmSheet(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    body: @Composable ColumnScope.() -> Unit,
) {
    val colors = Shadow.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val hideThen: (() -> Unit) -> Unit = { next ->
        scope.launch { sheetState.hide() }.invokeOnCompletion { next() }
    }
    ShadowBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, showClose = false) {
        Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 2.dp)) {
            IconTile(
                icon = ShadowIcons.Trash,
                size = 48.dp,
                iconSize = 24.dp,
                tint = colors.coralText,
                container = colors.coralTint,
                cornerRadius = 16.dp,
            )
            Text(title, style = Shadow.type.titleL, color = colors.ink1, modifier = Modifier.padding(top = 16.dp))
            Text(message, style = Shadow.type.body, color = colors.ink2, modifier = Modifier.padding(top = 8.dp))
            body()
            Column(modifier = Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ShadowButton(
                    text = confirmLabel,
                    onClick = { hideThen(onConfirm) },
                    variant = ShadowButtonVariant.Danger,
                    modifier = Modifier.fillMaxWidth(),
                )
                SecondaryButton(text = "Cancel", onClick = { hideThen(onDismiss) }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

private const val SHEET_ROWS = 3
private val TABLET_DIALOG_WIDTH = 440.dp
