package com.stansful.sshvpnclient.ui.routes

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stansful.sshvpnclient.domain.model.ProxyProfileSummary
import com.stansful.sshvpnclient.domain.model.VpnConnectionStatus
import com.stansful.sshvpnclient.ui.designsystem.ChipTone
import com.stansful.sshvpnclient.ui.designsystem.DISABLED_ALPHA
import com.stansful.sshvpnclient.ui.designsystem.IconTile
import com.stansful.sshvpnclient.ui.designsystem.LatencyMeter
import com.stansful.sshvpnclient.ui.designsystem.LocalToaster
import com.stansful.sshvpnclient.ui.designsystem.PrimaryButton
import com.stansful.sshvpnclient.ui.designsystem.ShadowButton
import com.stansful.sshvpnclient.ui.designsystem.ShadowButtonLabel
import com.stansful.sshvpnclient.ui.designsystem.ShadowButtonSize
import com.stansful.sshvpnclient.ui.designsystem.ShadowButtonVariant
import com.stansful.sshvpnclient.ui.designsystem.ShadowChip
import com.stansful.sshvpnclient.ui.designsystem.ShadowFilterChip
import com.stansful.sshvpnclient.ui.designsystem.ShadowFocusIndication
import com.stansful.sshvpnclient.ui.designsystem.ShadowIcons
import com.stansful.sshvpnclient.ui.designsystem.ShadowProgressBar
import com.stansful.sshvpnclient.ui.designsystem.ShadowSearchField
import com.stansful.sshvpnclient.ui.designsystem.ShadowSpinner
import com.stansful.sshvpnclient.ui.designsystem.ShadowTextButton
import com.stansful.sshvpnclient.ui.designsystem.StatusDot
import com.stansful.sshvpnclient.ui.designsystem.StatusTone
import com.stansful.sshvpnclient.ui.designsystem.SwapText
import com.stansful.sshvpnclient.ui.designsystem.ToastTone
import com.stansful.sshvpnclient.ui.designsystem.ToasterState
import com.stansful.sshvpnclient.ui.designsystem.fadeUpIn
import com.stansful.sshvpnclient.ui.designsystem.shadowClickable
import com.stansful.sshvpnclient.ui.designsystem.toneFill
import com.stansful.sshvpnclient.ui.designsystem.toneText
import com.stansful.sshvpnclient.ui.designsystem.toneTint
import com.stansful.sshvpnclient.ui.opensource.OpenSourceUiState
import com.stansful.sshvpnclient.ui.opensource.RouteStatusFilter
import com.stansful.sshvpnclient.ui.theme.Shadow
import com.stansful.sshvpnclient.ui.theme.ShadowMotion
import com.stansful.sshvpnclient.ui.theme.ShadowShapes
import com.stansful.sshvpnclient.ui.theme.shadowTween

/** TabletRoutes.dc.html: the 440 dp library pane and the route detail (or the selection) pane. */
@Composable
internal fun TabletRoutesScreen(
    state: RoutesScreenState,
    actions: RoutesActions,
    controller: RoutesUiController,
    modifier: Modifier = Modifier,
) {
    val routes = state.routes
    val viewed = controller.viewedRouteId?.let(routes::profileWithId)
        ?: routes.activeProfile
        ?: routes.library.firstOrNull()
    Row(
        modifier = modifier
            .fillMaxSize()
            .background(Shadow.colors.bg),
    ) {
        LibraryPane(
            state = state,
            actions = actions,
            controller = controller,
            viewedId = viewed?.id,
            modifier = Modifier
                .width(440.dp)
                .fillMaxHeight(),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = 2.dp, end = 16.dp, bottom = 16.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(DetailShape)
                    .background(Shadow.colors.surface1)
                    .border(1.dp, Shadow.colors.line, DetailShape)
                    .padding(28.dp),
            ) {
                when {
                    routes.selectionMode -> SelectionPane(routes = routes, actions = actions, controller = controller)
                    viewed == null -> NoRoutePane(onAdd = actions.onAddRoutes)
                    else -> DetailPane(state = state, route = viewed, actions = actions, controller = controller)
                }
            }
        }
    }
}

@Composable
private fun LibraryPane(
    state: RoutesScreenState,
    actions: RoutesActions,
    controller: RoutesUiController,
    viewedId: String?,
    modifier: Modifier = Modifier,
) {
    val routes = state.routes
    val colors = Shadow.colors
    val toaster = LocalToaster.current
    Column(
        modifier = modifier
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 24.dp, end = 16.dp, top = 10.dp),
    ) {
        if (routes.selectionMode) {
            TabletSelectionHeader(routes = routes, actions = actions)
        } else {
            TabletHeader(state = state, actions = actions)
        }
        ShadowSearchField(
            query = routes.query,
            onQueryChange = actions.onQueryChange,
            placeholder = "Search name, host or protocol",
            height = 48.dp,
            modifier = Modifier
                .padding(top = 16.dp)
                .fadeUpIn(2),
        )
        Row(
            modifier = Modifier
                .padding(top = 12.dp)
                .fadeUpIn(3),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RouteStatusFilter.entries.forEach { filter ->
                ShadowFilterChip(
                    label = filter.label(),
                    selected = routes.statusFilter == filter,
                    onClick = { actions.onStatusFilterChange(filter) },
                    icon = if (filter == RouteStatusFilter.PINNED) ShadowIcons.Pin else null,
                )
            }
        }
        TabletTools(routes = routes, actions = actions, toaster = toaster, modifier = Modifier.fadeUpIn(4))
        if (routes.isCheckingAll) TabletCheckProgress(routes)
        if (!routes.xrayCoreAvailable) EngineNote(onInstall = actions.onGetEngine)
        ListOverlineRow(routes = routes, actions = actions, toaster = toaster)
        val active = routes.activeProfile
        val activeHidden = active != null && routes.profiles.isNotEmpty() && routes.profiles.none { it.id == active.id }
        if (!routes.selectionMode && active != null && activeHidden) {
            ActiveHiddenNote(
                name = active.name,
                onShow = {
                    actions.onQueryChange("")
                    actions.onStatusFilterChange(RouteStatusFilter.ALL)
                    controller.viewedRouteId = active.id
                },
            )
        }
        val tunnelId = (routes.session() as? RoutesSession.Routes)
            ?.takeIf { it.phase == VpnConnectionStatus.CONNECTED }?.tunnelId
        val listState = rememberLazyListState()
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 88.dp + scrollJumpClearance(routes.profiles.size)),
            ) {
                // Callbacks capture only `selecting`, not the whole state (it changes with every
                // activity line and check step and would recompose every visible row).
                val selecting = routes.selectionMode
                itemsIndexed(routes.profiles, key = { _, route -> route.id }) { index, route ->
                    TabletRouteRow(
                        route = route,
                        state = route.routeState(routes.checkingRouteId),
                        viewed = !selecting && route.id == viewedId,
                        selecting = selecting,
                        selected = route.id in routes.selectedIds,
                        inUse = route.id == tunnelId,
                        onClick = {
                            if (selecting) {
                                actions.onSelectRoute(route.id)
                            } else {
                                controller.viewedRouteId = route.id
                            }
                        },
                        onLongClick = { if (!selecting) actions.onLongPressRoute(route.id) },
                        onPinChange = { pinned ->
                            actions.onPinChange(route.id, pinned)
                            toaster.show((if (pinned) "Pinned " else "Unpinned ") + route.name, tone = ToastTone.Info)
                        },
                        modifier = Modifier
                            .padding(bottom = 4.dp)
                            .then(if (index < FADE_IN_ITEMS) Modifier.fadeUpIn(index + 5) else Modifier),
                    )
                }
                if (routes.libraryLoaded && routes.profiles.isEmpty()) {
                    item(key = "empty") {
                        TabletEmptyList(routes = routes, actions = actions)
                    }
                }
            }
            // Above the 44 dp toast slot at the foot of the pane.
            ScrollJumpButtons(
                listState = listState,
                routeCount = routes.profiles.size,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = TABLET_JUMP_BOTTOM),
            )
        }
    }
}

@Composable
private fun TabletHeader(state: RoutesScreenState, actions: RoutesActions) {
    val routes = state.routes
    val colors = Shadow.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .fadeUpIn(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Routes", style = Shadow.type.titleL, color = colors.ink1, modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            ShadowTextButton(text = "Select", onClick = actions.onBeginSelection, size = ShadowButtonSize.Regular)
            PlainIconButton(
                icon = ShadowIcons.Activity,
                contentDescription = "Connection activity",
                onClick = actions.onOpenActivity,
            )
            PlainIconButton(
                icon = ShadowIcons.Plus,
                contentDescription = "Add or import routes",
                onClick = actions.onAddRoutes,
                tint = colors.ink1,
                container = colors.surface1,
                iconSize = 20.dp,
                modifier = Modifier.border(1.dp, colors.line, ShadowShapes.IconButton),
            )
        }
    }
    val numbers = Shadow.type.monoS
    Text(
        text = buildAnnotatedString {
            val number = SpanStyle(
                fontFamily = numbers.fontFamily,
                fontSize = numbers.fontSize,
                color = colors.ink2,
            )
            pushStyle(number)
            append(routes.counts.total.toString())
            pop()
            append(" routes · ")
            pushStyle(number)
            append(routes.counts.available.toString())
            pop()
            append(" available")
            state.listSyncedAt?.let { append(" · public list synced ${formatAgo(state.nowMillis, it)}") }
        },
        style = Shadow.type.bodyS,
        color = colors.ink3,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .padding(top = 4.dp)
            .fadeUpIn(1),
    )
}

@Composable
private fun TabletSelectionHeader(routes: OpenSourceUiState, actions: RoutesActions) {
    val colors = Shadow.colors
    val hidden = routes.hiddenSelectedCount()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .bleedStart(SELECTION_BLEED)
            .fadeUpIn(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlainIconButton(
            icon = ShadowIcons.Close,
            contentDescription = "Clear selection",
            onClick = actions.onClearSelection,
            tint = colors.ink1,
        )
        SwapText(
            text = selectionTitle(routes.selectedIds.size),
            style = Shadow.type.titleL,
            color = colors.ink1,
            modifier = Modifier.weight(1f),
        )
        ShadowTextButton(
            text = "Select all",
            onClick = actions.onSelectAll,
            size = ShadowButtonSize.Regular,
            enabled = !routes.allSelectableSelected(),
        )
    }
    SwapText(
        text = if (hidden > 0) "$hidden hidden by the filter, still counted" else "Select all skips pinned routes",
        style = Shadow.type.bodyS,
        color = colors.skyText,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun TabletTools(
    routes: OpenSourceUiState,
    actions: RoutesActions,
    toaster: ToasterState,
    modifier: Modifier = Modifier,
) {
    val checking = routes.isCheckingAll
    val locked = !checking && routes.anyXrayRuntimeActive
    val disabled = !checking && !locked && (
        !routes.xrayCoreAvailable || routes.isRemovingUnavailable || routes.isChecking || routes.profiles.isEmpty()
        )
    Row(modifier = modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ShadowChip(
            label = if (checking) "Cancel check" else "Check all",
            onClick = {
                when {
                    checking -> actions.onCancelCheck()
                    locked -> toaster.show(
                        "Disconnect first. Checks need the engine your connection is using.",
                        tone = ToastTone.Neutral,
                        icon = ShadowIcons.Lock,
                    )
                    else -> actions.onCheckAll()
                }
            },
            icon = when {
                checking -> ShadowIcons.Close
                locked -> ShadowIcons.Lock
                else -> ShadowIcons.Shield
            },
            tone = if (checking) ChipTone.Error else ChipTone.Default,
            enabled = !disabled,
        )
        ShadowChip(
            label = if (routes.isSyncing) "Refreshing…" else "Refresh",
            onClick = actions.onRefresh,
            icon = ShadowIcons.Refresh,
            tone = if (routes.isSyncing) ChipTone.Progress else ChipTone.Default,
            loading = routes.isSyncing,
            enabled = !routes.isRemovingUnavailable,
        )
    }
}

@Composable
private fun TabletCheckProgress(routes: OpenSourceUiState) {
    val colors = Shadow.colors
    Column(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
            .clip(ShadowShapes.Banner)
            .background(colors.amber.copy(alpha = PROGRESS_FILL_ALPHA))
            .border(1.dp, colors.amber.copy(alpha = PROGRESS_BORDER_ALPHA), ShadowShapes.Banner)
            .padding(horizontal = 14.dp + OUTLINE, vertical = 12.dp + OUTLINE),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Checking every route", style = Shadow.type.label, color = colors.amberText)
            Text(
                "${routes.checkCompleted} / ${routes.checkTotal}",
                style = Shadow.type.monoS.copy(fontWeight = Shadow.type.monoMedium.fontWeight),
                color = colors.amberText,
            )
        }
        ShadowProgressBar(
            progress = if (routes.checkTotal > 0) routes.checkCompleted.toFloat() / routes.checkTotal else 0f,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            "Results land all at once when it ends, usually in about 10 s.",
            style = Shadow.type.caption,
            color = colors.ink3,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun EngineNote(onInstall: () -> Unit) {
    val colors = Shadow.colors
    Row(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
            .clip(ShadowShapes.Banner)
            .background(colors.coralTint)
            .padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(ShadowIcons.Engine, contentDescription = null, tint = colors.coralText, modifier = Modifier.size(20.dp))
        Text(
            "The Xray engine isn’t installed. You need it to check routes and to connect.",
            style = Shadow.type.bodyS,
            color = colors.ink1,
            modifier = Modifier.weight(1f),
        )
        ShadowButton(
            text = "Install",
            onClick = onInstall,
            variant = ShadowButtonVariant.Danger,
            size = ShadowButtonSize(36.dp, 14.dp, 16.dp, 18.dp, ShadowButtonLabel.Small),
        )
    }
}

@Composable
private fun ListOverlineRow(routes: OpenSourceUiState, actions: RoutesActions, toaster: ToasterState) {
    val colors = Shadow.colors
    val locked = routes.anyXrayRuntimeActive && !routes.isRemovingUnavailable
    val blocked = routes.isRemovingUnavailable || routes.isChecking || routes.isSyncing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .padding(start = 4.dp, end = 4.dp, top = 14.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SwapText(
            text = routes.listOverline().uppercase(),
            style = Shadow.type.overline,
            color = colors.ink3,
            modifier = Modifier.weight(1f),
        )
        if (routes.unavailableUnpinnedCount > 0 || routes.isRemovingUnavailable) {
            // TabletRoutes.dc.html: margin-right -10 px, so the label lines up with the row edge.
            Row(
                modifier = Modifier
                    .negativeMargins(end = 10.dp)
                    .height(36.dp)
                    .clip(ShadowShapes.Tile)
                    .shadowClickable(
                        remember { MutableInteractionSource() },
                        enabled = !(blocked && !locked),
                    ) {
                        if (locked) {
                            toaster.show(
                                "Disconnect first. Routes can’t be removed while the engine is running.",
                                tone = ToastTone.Neutral,
                                icon = ShadowIcons.Lock,
                            )
                        } else {
                            actions.onRequestRemoveUnavailable()
                        }
                    }
                    .alpha(if (blocked && !routes.isRemovingUnavailable && !locked) DISABLED_ALPHA else 1f)
                    .padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val ink = if (locked) colors.ink2 else colors.coralText
                when {
                    routes.isRemovingUnavailable -> ShadowSpinner(color = ink, size = 16.dp)
                    locked -> Icon(
                        ShadowIcons.Lock,
                        contentDescription = null,
                        tint = ink,
                        modifier = Modifier.size(16.dp),
                    )
                    else -> Icon(
                        ShadowIcons.Trash,
                        contentDescription = null,
                        tint = ink,
                        modifier = Modifier.size(16.dp),
                    )
                }
                SwapText(
                    text = if (routes.isRemovingUnavailable) {
                        "Removing unavailable…"
                    } else {
                        "Remove unavailable · ${routes.unavailableUnpinnedCount}"
                    },
                    style = Shadow.type.label,
                    color = ink,
                )
            }
        }
    }
}

@Composable
private fun ActiveHiddenNote(name: String, onShow: () -> Unit) {
    val colors = Shadow.colors
    Row(
        modifier = Modifier
            .padding(bottom = 8.dp)
            .fillMaxWidth()
            .clip(ShadowShapes.Banner)
            .background(colors.skyTint)
            .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(ShadowIcons.Info, contentDescription = null, tint = colors.skySoft, modifier = Modifier.size(18.dp))
        Text(
            "$name is active but hidden",
            style = Shadow.type.bodyS,
            color = colors.skySoft,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .height(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .shadowClickable(
                    remember { MutableInteractionSource() },
                    onClickLabel = "Show the active route",
                    onClick = onShow,
                )
                .background(colors.sky.copy(alpha = SHOW_FILL_ALPHA))
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Show", style = Shadow.type.label, color = colors.skySoft)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabletRouteRow(
    route: ProxyProfileSummary,
    state: RouteState,
    viewed: Boolean,
    selecting: Boolean,
    selected: Boolean,
    inUse: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPinChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Shadow.colors
    val shape = RoundedCornerShape(16.dp)
    val haptics = LocalHapticFeedback.current
    val fill = when {
        selected -> colors.sky.copy(alpha = SELECTED_FILL_ALPHA)
        viewed -> colors.surface2
        else -> Color.Transparent
    }
    val line = when {
        selected -> colors.sky.copy(alpha = SELECTED_BORDER_ALPHA)
        viewed -> colors.line2
        else -> Color.Transparent
    }
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(fill)
            .border(1.dp, line, shape)
            // The row's (usually transparent) 1 dp border sits outside its padding, as in the artboard.
            .padding(OUTLINE),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(shape)
                .combinedClickable(
                    interactionSource = interaction,
                    indication = ShadowFocusIndication,
                    onClickLabel = if (selecting) null else "Show details",
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongClick()
                    },
                    onClick = onClick,
                )
                .semantics(mergeDescendants = true) {
                    contentDescription = if (selecting) {
                        route.name + (if (selected) ", selected" else ", not selected") +
                            if (route.isPinned) ", pinned" else ""
                    } else {
                        route.name + ", " + state.spoken() + (if (route.isSelected) ", active route" else "") +
                            if (inUse) ", carrying your traffic" else ""
                    }
                    if (selecting) {
                        role = Role.Checkbox
                        toggleableState = ToggleableState(selected)
                    } else {
                        this.selected = viewed
                    }
                }
                .padding(start = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SelectionSlot(visible = selecting, checked = selected)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (inUse) {
                        StatusDot(color = colors.mint, modifier = Modifier.semantics { contentDescription = "In use" })
                    }
                    Text(
                        route.name,
                        style = Shadow.type.button,
                        color = colors.ink1,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (route.isSelected) ActiveTag()
                }
                Text(
                    route.endpointLine(),
                    style = Shadow.type.monoS,
                    color = colors.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(modifier = Modifier.padding(start = 12.dp)) { TabletRowStatus(state) }
        }
        PinToggle(
            pinned = route.isPinned,
            routeName = route.name,
            onPinChange = onPinChange,
            tile = false,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

/** 20 dp amber "Active" tag of the tablet list. */
@Composable
private fun ActiveTag() {
    val colors = Shadow.colors
    Box(
        modifier = Modifier
            .height(20.dp)
            .clip(CircleShape)
            .background(colors.amberTint)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("Active", style = TagLabel, color = colors.amberText)
    }
}

@Composable
private fun TabletRowStatus(state: RouteState) {
    val colors = Shadow.colors
    when (state) {
        is RouteState.Available -> RouteMeter(latencyMs = state.latencyMs)
        RouteState.NotChecked -> Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LatencyMeter(latencyMs = null, showLabel = false)
            Text("Not checked", style = TagLabel, color = colors.ink3)
        }
        RouteState.Checking -> RouteStateSlot(state)
        RouteState.Unavailable, RouteState.Unsupported, RouteState.Outdated -> Box(
            modifier = Modifier
                .height(22.dp)
                .clip(CircleShape)
                .background(colors.coralTint)
                .padding(horizontal = 9.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                when (state) {
                    RouteState.Unavailable -> "Unavailable"
                    RouteState.Unsupported -> "Unsupported"
                    else -> "Outdated"
                },
                style = TagLabel,
                color = colors.coralText,
            )
        }
    }
}

@Composable
private fun TabletEmptyList(routes: OpenSourceUiState, actions: RoutesActions) {
    val colors = Shadow.colors
    val query = routes.query.trim()
    val (title, body, action) = when {
        query.isNotEmpty() -> Triple(
            "No routes match “$query”",
            "Search looks at names, hosts and protocol, transport or security names like VLESS, GRPC or REALITY.",
            "Clear search",
        )
        routes.statusFilter == RouteStatusFilter.AVAILABLE -> Triple(
            "No available routes",
            "Run Check all to find the routes that answer right now.",
            "Show all routes",
        )
        routes.statusFilter == RouteStatusFilter.PINNED -> Triple(
            "No pinned routes",
            "Pinned routes are kept by Select all and by Remove unavailable.",
            "Show all routes",
        )
        routes.statusFilter == RouteStatusFilter.NOT_CHECKED -> Triple(
            "Every route has been checked",
            "New routes from the next refresh will show up here.",
            "Show all routes",
        )
        else -> Triple("Your library is empty", "Refresh the public list or add a route link you trust.", "Refresh")
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 40.dp, bottom = 8.dp)
            .fadeUpIn(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconTile(
            icon = ShadowIcons.Search,
            size = 56.dp,
            iconSize = 26.dp,
            tint = colors.skyText,
            cornerRadius = 16.dp,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Text(
            title,
            style = Shadow.type.titleS,
            color = colors.ink1,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 300.dp),
        )
        Text(
            body,
            style = Shadow.type.bodyS,
            color = colors.ink3,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 300.dp),
        )
        ShadowButton(
            text = action,
            onClick = {
                when {
                    query.isNotEmpty() -> actions.onQueryChange("")
                    routes.statusFilter != RouteStatusFilter.ALL -> actions.onStatusFilterChange(RouteStatusFilter.ALL)
                    else -> actions.onRefresh()
                }
            },
            variant = ShadowButtonVariant.Secondary,
            size = ShadowButtonSize.Regular,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun ColumnScope.DetailPane(
    state: RoutesScreenState,
    route: ProxyProfileSummary,
    actions: RoutesActions,
    controller: RoutesUiController,
) {
    val routes = state.routes
    val colors = Shadow.colors
    val toaster = LocalToaster.current
    val routeState = route.routeState(routes.checkingRouteId)
    val tunnelId = (routes.session() as? RoutesSession.Routes)
        ?.takeIf { it.phase == VpnConnectionStatus.CONNECTED }
        ?.tunnelId
    Column(
        modifier = Modifier
            .weight(1f)
            .verticalScroll(rememberScrollState()),
    ) {
        Column(modifier = Modifier.fadeUpIn()) {
            Row(
                modifier = Modifier.heightIn(min = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CodeTag(route.protocol.name, colors.ink2, fontSize = 12, horizontalPadding = 8.dp)
                if (route.isSelected) DetailPill("Active route", BoldCheck, colors.amberText, colors.amberTint)
                if (route.id == tunnelId) {
                    DetailPill("Carrying your traffic", null, colors.mintText, colors.mintTint, dot = true)
                }
                if (route.isPinned) DetailPill("Pinned", null, colors.skyText, colors.skyTint, pin = true)
            }
            Text(
                route.name,
                style = Shadow.type.titleL,
                color = colors.ink1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                route.hostPort(),
                style = Shadow.type.mono,
                color = colors.ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        FactsGrid(
            route = route,
            checkingRouteId = routes.checkingRouteId,
            nowMillis = state.nowMillis,
            actions = actions,
        )
        if (routeState in ProblemStates) {
            ProblemBanner(state = routeState, onEdit = { actions.onEditRoute(route.id) })
        }
        DetailActions(route = route, routes = routes, actions = actions, controller = controller, toaster = toaster)
        if (!route.isManual) {
            RiskDisclosure(
                expanded = routes.appSettings.openSourceRiskBannerExpanded,
                onExpandedChange = actions.onRiskExpandedChange,
            )
        }
    }
    ConnectionCard(
        model = routes.tabletCta(route, state.serverName),
        onPrimary = { primary ->
            when (primary) {
                CtaPrimary.Connect -> actions.onConnectThrough(route.id)
                CtaPrimary.ReconnectHere -> actions.onReconnectThrough(route.id)
                CtaPrimary.Switch -> controller.overlay = RoutesOverlay.SwitchMode(route.id)
            }
        },
        onSecondary = actions.onDisconnect,
        onInstall = actions.onGetEngine,
    )
}

@Composable
private fun DetailPill(
    text: String,
    icon: ImageVector?,
    ink: Color,
    fill: Color,
    dot: Boolean = false,
    pin: Boolean = false,
) {
    Row(
        modifier = Modifier
            .height(24.dp)
            .clip(CircleShape)
            .background(fill)
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            dot -> StatusDot(color = ink, size = 6.dp, blink = true)
            pin -> PinGlyph(pinned = true, tint = ink, size = 13.dp)
            icon != null -> Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(14.dp))
        }
        Text(text, style = TagLabel, color = ink)
    }
}

/** Protocol · Transport · Security, then Source and Last check (span 2). */
@Composable
private fun FactsGrid(route: ProxyProfileSummary, checkingRouteId: String?, nowMillis: Long, actions: RoutesActions) {
    // The last check result, whether or not the route has left the public list since.
    val lastResult = route.copy(isStale = false).routeState(checkingRouteId)
    val colors = Shadow.colors
    val lastChecked by produceState<Long?>(null, route.id, route.lastTestStatus, route.lastLatencyMs) {
        value = actions.lastCheckedAt(route.id)
    }
    BoxWithConstraints(modifier = Modifier.padding(top = 24.dp)) {
        val column = (maxWidth - GRID_GAP * 2) / 3
        Column(verticalArrangement = Arrangement.spacedBy(GRID_GAP)) {
            Row(modifier = Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(GRID_GAP)) {
                FactTile("Protocol", column, 1) { FactMono(route.protocol.displayName()) }
                FactTile("Transport", column, 2) { FactMono(route.transport.label()) }
                FactTile("Security", column, 3) { FactMono(route.security.name) }
            }
            Row(modifier = Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(GRID_GAP)) {
                FactTile("Source", column, 4) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (route.isManual) ShadowIcons.Link else ShadowIcons.Routes,
                            contentDescription = null,
                            tint = if (route.isManual) colors.skyText else colors.ink2,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            if (route.isManual) "Added by you" else "Public list",
                            style = Shadow.type.bodyMedium.copy(lineHeight = Shadow.type.rowTitle.lineHeight),
                            color = colors.ink1,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        if (route.isManual) "A refresh never changes it" else "Each refresh can update it",
                        style = Shadow.type.caption,
                        color = colors.ink3,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                FactTile("Last check", column * 2 + GRID_GAP, 5) {
                    LastCheckLine(routeState = lastResult, nowMillis = nowMillis, lastChecked = lastChecked)
                    Text(
                        "A request to youtube.com through this route · 5 s limit",
                        style = Shadow.type.caption,
                        color = colors.ink3,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FactTile(label: String, width: Dp, index: Int, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .clip(RoundedCornerShape(16.dp))
            .background(Shadow.colors.surface2)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .fadeUpIn(index),
    ) {
        Text(label.uppercase(), style = Shadow.type.overline, color = Shadow.colors.ink3)
        Column(modifier = Modifier.padding(top = 6.dp), content = content)
    }
}

@Composable
private fun FactMono(text: String) {
    Text(
        text,
        style = Shadow.type.monoMedium.copy(lineHeight = Shadow.type.rowTitle.lineHeight),
        color = Shadow.colors.ink1,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun LastCheckLine(routeState: RouteState, nowMillis: Long, lastChecked: Long?) {
    val colors = Shadow.colors
    val textStyle = Shadow.type.bodyMedium.copy(lineHeight = Shadow.type.rowTitle.lineHeight)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        val (main, color) = when (routeState) {
            RouteState.Checking -> {
                ShadowSpinner(color = colors.amberText, size = 16.dp)
                "Checking…" to colors.amberText
            }
            is RouteState.Available -> {
                val latency = routeState.latencyMs
                LatencyMeter(latencyMs = latency, showLabel = false)
                (latency?.let { "$it ms" } ?: "Available") to latencyColor(latency)
            }
            RouteState.Unavailable -> {
                StatusDot(color = colors.coral)
                "Unavailable" to colors.coralText
            }
            RouteState.Unsupported -> {
                StatusDot(color = colors.coral)
                "Unsupported" to colors.coralText
            }
            RouteState.NotChecked, RouteState.Outdated -> "Not checked yet" to colors.ink2
        }
        val mono = routeState is RouteState.Available && routeState.latencyMs != null
        Text(
            main,
            style = if (mono) Shadow.type.monoMedium.copy(lineHeight = textStyle.lineHeight) else textStyle,
            color = color,
        )
        if (routeState != RouteState.Checking && lastChecked != null && routeState != RouteState.NotChecked) {
            Text(
                "· ${formatAgo(nowMillis, lastChecked)}",
                style = Shadow.type.body.copy(lineHeight = textStyle.lineHeight),
                color = colors.ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ProblemBanner(state: RouteState, onEdit: () -> Unit) {
    val colors = Shadow.colors
    val (title, text) = when (state) {
        RouteState.Outdated -> "Outdated" to
            "This route left the public list. You can’t connect through it. Edit it to keep it as your own route."
        RouteState.Unavailable -> "Unavailable" to
            "It didn’t answer the last check: a request to youtube.com through it failed or took longer than 5 s. " +
            "Check again or pick another route."
        else -> "Unsupported" to
            "The engine rejected this route’s settings, so connecting through it keeps failing. Check it " +
            "again after an engine update."
    }
    Row(
        modifier = Modifier
            .padding(top = 16.dp)
            .fillMaxWidth()
            .clip(ShadowShapes.Banner)
            .background(colors.coralTint)
            .padding(start = 16.dp, end = 14.dp, top = 14.dp, bottom = 14.dp)
            .fadeUpIn(6),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            ShadowIcons.Warning,
            contentDescription = null,
            tint = colors.coralText,
            modifier = Modifier
                .padding(top = 1.dp)
                .size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = Shadow.type.button, color = colors.coralText)
            Text(text, style = Shadow.type.bodyS, color = colors.ink2, modifier = Modifier.padding(top = 2.dp))
        }
        if (state == RouteState.Outdated) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .height(36.dp)
                    .clip(CircleShape)
                    .shadowClickable(remember { MutableInteractionSource() }, onClick = onEdit)
                    .background(colors.coral.copy(alpha = EDIT_FILL_ALPHA))
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Edit to keep", style = Shadow.type.label, color = colors.coralText)
            }
        }
    }
}

@Composable
private fun DetailActions(
    route: ProxyProfileSummary,
    routes: OpenSourceUiState,
    actions: RoutesActions,
    controller: RoutesUiController,
    toaster: ToasterState,
) {
    val colors = Shadow.colors
    val active = route.isSelected
    val checkingThis = routes.checkingRouteId == route.id
    val locked = !checkingThis && routes.anyXrayRuntimeActive
    val checkDisabled = !checkingThis && !locked &&
        (!routes.xrayCoreAvailable || routes.isChecking || routes.isRemovingUnavailable)
    Row(
        modifier = Modifier
            .padding(top = 24.dp)
            .fillMaxWidth()
            .fadeUpIn(7),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShadowButton(
            text = if (active) "Active route" else "Use this route",
            onClick = {
                if (!active) {
                    actions.onSelectRoute(route.id)
                    val tunnel = (routes.session() as? RoutesSession.Routes)?.tunnelId
                    if (tunnel != null && tunnel != route.id) {
                        toaster.show(
                            "${route.name} is active. Reconnect to move your traffic to it.",
                            tone = ToastTone.Info,
                        )
                    } else {
                        toaster.show("${route.name} is now your active route")
                    }
                }
            },
            variant = if (active) ShadowButtonVariant.Tonal else ShadowButtonVariant.Primary,
            icon = if (active) BoldCheck else null,
            size = ShadowButtonSize(52.dp, 22.dp, 18.dp, 16.dp, ShadowButtonLabel.Large),
            enabled = !route.isStale,
        )
        ShadowButton(
            text = if (checkingThis) "Checking…" else "Check route",
            onClick = {
                if (locked) {
                    toaster.show(
                        "Disconnect first. Checks need the engine your connection is using.",
                        tone = ToastTone.Neutral,
                        icon = ShadowIcons.Lock,
                    )
                } else if (!checkingThis) {
                    actions.onCheckRoute(route.id)
                }
            },
            variant = ShadowButtonVariant.Secondary,
            icon = if (locked) ShadowIcons.Lock else ShadowIcons.Shield,
            loading = checkingThis,
            size = ShadowButtonSize(52.dp, 20.dp, 18.dp, 16.dp, ShadowButtonLabel.Large),
            enabled = !checkDisabled,
        )
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(ShadowShapes.IconButton)
                .background(if (route.isPinned) colors.skyTint else colors.surface2)
                .shadowClickable(remember { MutableInteractionSource() }) {
                    actions.onPinChange(route.id, !route.isPinned)
                    toaster.show((if (route.isPinned) "Unpinned " else "Pinned ") + route.name, tone = ToastTone.Info)
                }
                .semantics {
                    contentDescription = if (route.isPinned) "Unpin route" else "Pin route"
                    stateDescription = if (route.isPinned) "Pinned" else "Not pinned"
                },
            contentAlignment = Alignment.Center,
        ) {
            PinGlyph(pinned = route.isPinned, tint = if (route.isPinned) colors.skyText else colors.ink2, size = 20.dp)
        }
        PlainIconButton(
            icon = ShadowIcons.Copy,
            contentDescription = "Copy route link",
            onClick = { actions.onCopyLink(route) },
            container = colors.surface2,
            iconSize = 20.dp,
        )
        PlainIconButton(
            icon = ShadowIcons.Edit,
            contentDescription = "Edit route",
            onClick = { actions.onEditRoute(route.id) },
            container = colors.surface2,
            iconSize = 20.dp,
        )
        PlainIconButton(
            icon = ShadowIcons.Trash,
            contentDescription = "Delete route",
            onClick = { controller.overlay = RoutesOverlay.DeleteRoute(route.id) },
            tint = colors.coralText,
            container = colors.coralTint,
            iconSize = 20.dp,
        )
    }
}

@Composable
private fun RiskDisclosure(expanded: Boolean, onExpandedChange: (Boolean) -> Unit) {
    val colors = Shadow.colors
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = shadowTween(ShadowMotion.Dialog),
        label = "risk-chevron",
    )
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .padding(top = 24.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(colors.amber.copy(alpha = RISK_FILL_ALPHA))
            .border(1.dp, colors.amber.copy(alpha = RISK_BORDER_ALPHA), shape)
            .padding(OUTLINE)
            .animateContentSize(shadowTween(ShadowMotion.Surface))
            .fadeUpIn(8),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(shape)
                .shadowClickable(remember { MutableInteractionSource() }) { onExpandedChange(!expanded) }
                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                ShadowIcons.ShieldAlert,
                contentDescription = null,
                tint = colors.amberText,
                modifier = Modifier.size(20.dp),
            )
            Text(
                "Public routes come from third parties",
                style = Shadow.type.segment.copy(lineHeight = RouteLineText.lineHeight),
                color = colors.ink1,
                modifier = Modifier.weight(1f),
            )
            Icon(
                ShadowIcons.ChevronDown,
                contentDescription = null,
                tint = colors.ink3,
                modifier = Modifier
                    .size(18.dp)
                    .rotate(rotation),
            )
        }
        if (expanded) {
            Text(
                "Use them at your own risk. The app’s developer can’t vouch for their safety or for the safety " +
                    "of your data.",
                style = Shadow.type.bodyS,
                color = colors.ink2,
                modifier = Modifier.padding(start = 48.dp, end = 16.dp, bottom = 14.dp),
            )
        }
    }
}

internal enum class CtaPrimary { Connect, ReconnectHere, Switch }

/** The connection card at the bottom of the tablet detail pane. */
@Immutable
internal data class CtaModel(
    val state: String,
    val tone: StatusTone,
    val blink: Boolean,
    val title: String,
    val subtitle: String,
    val subtitleColor: CtaInk,
    val spinner: Boolean,
    val secondary: String?,
    val secondaryDanger: Boolean,
    val primary: CtaPrimary?,
    val primaryLabel: String,
    val primaryEnabled: Boolean,
    val install: Boolean,
)

internal enum class CtaInk { Muted, Amber, Coral, Ink2 }

internal fun OpenSourceUiState.tabletCta(route: ProxyProfileSummary, serverName: String?): CtaModel {
    val blocked = isChecking || isRemovingUnavailable
    val base = CtaModel(
        state = "Not connected", tone = StatusTone.Neutral, blink = false,
        title = "Connect via ${route.name}", subtitle = "", subtitleColor = CtaInk.Muted, spinner = false,
        secondary = null, secondaryDanger = false, primary = null, primaryLabel = "Connect", primaryEnabled = true,
        install = false,
    )
    return when (val session = session()) {
        is RoutesSession.Failed -> {
            val same = session.routeId == route.id
            base.copy(
                state = "Couldn’t connect",
                tone = StatusTone.Error,
                title = if (same) {
                    "${route.name} didn’t start"
                } else {
                    "Last try via ${profileWithId(session.routeId)?.name ?: "a route"} failed"
                },
                subtitle = if (route.isStale) OUTDATED_CANT_CONNECT else session.message,
                subtitleColor = if (route.isStale) CtaInk.Coral else CtaInk.Ink2,
                primary = CtaPrimary.Connect,
                primaryLabel = if (same) "Try again" else "Connect here",
                primaryEnabled = !route.isStale && !blocked && xrayCoreAvailable,
                install = !xrayCoreAvailable,
            )
        }
        RoutesSession.Off -> when {
            !xrayCoreAvailable -> base.copy(
                state = "Engine needed",
                tone = StatusTone.Error,
                subtitle = "Routes run on the Xray engine. Install it once, then connect.",
                install = true,
            )
            else -> base.copy(
                subtitle = when {
                    route.isStale -> OUTDATED_CANT_CONNECT
                    isRemovingUnavailable -> "Wait for the cleanup to finish."
                    isChecking -> "Finish or cancel the route check first."
                    route.routeState(null) == RouteState.Unsupported ->
                        "Unsupported route. The engine will likely keep retrying."
                    route.isSelected -> "Your active route · Routes mode"
                    else -> "Connecting also makes it your active route."
                },
                subtitleColor = if (route.isStale) CtaInk.Coral else CtaInk.Muted,
                primary = CtaPrimary.Connect,
                primaryEnabled = !route.isStale && !blocked,
            )
        }
        is RoutesSession.Routes -> routesCta(base, session, route, blocked)
        RoutesSession.Server, RoutesSession.Auto -> {
            val server = session == RoutesSession.Server
            if (!xrayCoreAvailable) {
                base.copy(
                    state = if (server) "Connected · Server mode" else "Connected · Auto mode",
                    tone = StatusTone.Success,
                    title = if (server) "${serverName ?: "Your server"} is connected" else "Auto is connected",
                    subtitle = "Install the Xray engine to use routes.",
                    install = true,
                )
            } else {
                base.copy(
                    state = if (server) "Connected · Server mode" else "Connected · Auto mode",
                    tone = StatusTone.Success,
                    title = if (server) "${serverName ?: "Your server"} is connected" else "Auto is connected",
                    subtitle = when {
                        route.isStale -> "Outdated routes can’t connect."
                        server -> "One connection at a time. Switching stops Server mode."
                        else -> "Switching stops Auto. Route checks wait while it runs."
                    },
                    primary = CtaPrimary.Switch,
                    primaryLabel = "Switch to this route",
                    primaryEnabled = !route.isStale && !blocked,
                )
            }
        }
    }
}

private fun OpenSourceUiState.routesCta(
    base: CtaModel,
    session: RoutesSession.Routes,
    route: ProxyProfileSummary,
    blocked: Boolean,
): CtaModel {
    val tunnelName = profileWithId(session.tunnelId)?.name ?: "your route"
    val same = session.tunnelId == route.id
    val model = when (session.phase) {
        VpnConnectionStatus.CONNECTING -> base.copy(
            state = "Connecting",
            tone = StatusTone.Progress,
            blink = true,
            title = "Connecting via $tunnelName",
            subtitle = "Traffic isn’t protected yet",
            subtitleColor = CtaInk.Amber,
            spinner = true,
            secondary = "Stop",
        )
        VpnConnectionStatus.RECONNECTING -> base.copy(
            state = "Reconnecting",
            tone = StatusTone.Progress,
            blink = true,
            title = "Reconnecting via $tunnelName",
            subtitle = "The connection dropped. Trying again on its own.",
            subtitleColor = CtaInk.Amber,
            spinner = true,
            secondary = "Stop",
        )
        else -> base.copy(
            state = "Connected",
            tone = StatusTone.Success,
            title = "Connected via $tunnelName",
            subtitle = when {
                same -> "Traffic goes through this route · Routes mode"
                route.isStale -> "Outdated routes can’t connect."
                else -> "Reconnect to move your traffic to ${route.name}."
            },
            secondary = "Disconnect",
            secondaryDanger = true,
        )
    }
    return if (!same && session.phase != VpnConnectionStatus.CONNECTING && !route.isStale) {
        model.copy(primary = CtaPrimary.ReconnectHere, primaryLabel = "Reconnect here", primaryEnabled = !blocked)
    } else {
        model
    }
}

@Composable
private fun ConnectionCard(
    model: CtaModel,
    onPrimary: (CtaPrimary) -> Unit,
    onSecondary: () -> Unit,
    onInstall: () -> Unit,
) {
    val colors = Shadow.colors
    val shape = RoundedCornerShape(20.dp)
    val tone = model.tone
    val line = if (tone == StatusTone.Neutral) colors.line2 else colors.toneFill(tone).copy(alpha = CTA_BORDER_ALPHA)
    Row(
        modifier = Modifier
            .padding(top = 20.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface2)
            .border(1.dp, line, shape)
            .padding(OUTLINE)
            .padding(start = 18.dp, end = 16.dp, top = 16.dp, bottom = 16.dp)
            .semantics(mergeDescendants = false) { contentDescription = "Connection" },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.Center) {
            IconTile(
                icon = ShadowIcons.Power,
                size = 48.dp,
                iconSize = 22.dp,
                tint = if (tone == StatusTone.Neutral) colors.ink2 else colors.toneText(tone),
                container = colors.toneTint(tone),
                cornerRadius = 16.dp,
            )
            if (model.spinner) {
                RingSpinner(
                    size = 48.dp,
                    strokeWidth = 1f,
                    color = colors.amber,
                    track = Color.Transparent,
                    radius = CTA_RING_RADIUS,
                )
            }
        }
        Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                val stateInk = if (tone == StatusTone.Neutral) colors.ink2 else colors.toneText(tone)
                StatusDot(color = stateInk, size = 6.dp, blink = model.blink)
                SwapText(text = model.state, style = TagLabel, color = stateInk)
            }
            SwapText(
                text = model.title,
                style = Shadow.type.titleS,
                color = colors.ink1,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (model.subtitle.isNotEmpty()) {
                SwapText(
                    text = model.subtitle,
                    style = Shadow.type.bodyS,
                    color = when (model.subtitleColor) {
                        CtaInk.Muted -> colors.ink3
                        CtaInk.Amber -> colors.amberMuted
                        CtaInk.Coral -> colors.coralText
                        CtaInk.Ink2 -> colors.ink2
                    },
                    maxLines = 2,
                )
            }
        }
        if (model.secondary != null) {
            ShadowButton(
                text = model.secondary,
                onClick = onSecondary,
                variant = if (model.secondaryDanger) ShadowButtonVariant.DangerTonal else ShadowButtonVariant.Secondary,
                size = ShadowButtonSize(48.dp, 18.dp, 18.dp, 14.dp, ShadowButtonLabel.Large),
            )
        }
        when {
            model.install -> ShadowButton(
                text = "Install engine",
                onClick = onInstall,
                icon = ShadowIcons.Download,
                size = ShadowButtonSize.Tall,
            )
            model.primary != null -> ShadowButton(
                text = model.primaryLabel,
                onClick = { onPrimary(model.primary) },
                icon = ShadowIcons.Power,
                size = ShadowButtonSize(48.dp, 20.dp, 18.dp, 14.dp, ShadowButtonLabel.Large),
                enabled = model.primaryEnabled,
            )
        }
    }
}

/** Selection mode's right pane: what is selected and the bulk delete. */
@Composable
private fun ColumnScope.SelectionPane(
    routes: OpenSourceUiState,
    actions: RoutesActions,
    controller: RoutesUiController,
) {
    val colors = Shadow.colors
    val picked = routes.selectedRoutes()
    val count = picked.size
    val visibleIds = remember(routes.profiles) { routes.profiles.mapTo(hashSetOf()) { it.id } }
    Column(modifier = Modifier.weight(1f)) {
        SelectionSummary(routes = routes, picked = picked, visibleIds = visibleIds)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShadowButton(
            text = if (count == 0) "Delete" else "Delete $count ${if (count == 1) "route" else "routes"}",
            onClick = { controller.overlay = RoutesOverlay.DeleteSelected },
            variant = ShadowButtonVariant.Danger,
            icon = ShadowIcons.Trash,
            size = ShadowButtonSize(52.dp, 22.dp, 18.dp, 16.dp, ShadowButtonLabel.Large),
            enabled = count > 0,
        )
        ShadowButton(
            text = "Done",
            onClick = actions.onClearSelection,
            variant = ShadowButtonVariant.Secondary,
            size = ShadowButtonSize(52.dp, 20.dp, 18.dp, 16.dp, ShadowButtonLabel.Large),
        )
        Spacer(Modifier.weight(1f))
        Text("Pinning still works while selecting.", style = Shadow.type.bodyS, color = colors.ink3)
    }
}

@Composable
private fun ColumnScope.SelectionSummary(
    routes: OpenSourceUiState,
    picked: List<ProxyProfileSummary>,
    visibleIds: Set<String>,
) {
    val colors = Shadow.colors
    val count = picked.size
    IconTile(
        icon = ShadowIcons.CheckCircle,
        size = 48.dp,
        iconSize = 24.dp,
        tint = colors.skyText,
        container = colors.skyTint,
        cornerRadius = 16.dp,
        modifier = Modifier.fadeUpIn(),
    )
    Text("SELECTION", style = Shadow.type.overline, color = colors.ink3, modifier = Modifier.padding(top = 20.dp))
    SwapText(
        text = if (count == 0) "Select routes" else "$count ${if (count == 1) "route" else "routes"} selected",
        style = Shadow.type.titleL,
        color = colors.ink1,
        modifier = Modifier.padding(top = 6.dp),
    )
    Text(
        if (count == 0) {
            "Tap routes on the left to select them. Select all picks every route you can see, except pinned ones."
        } else {
            "Pinned routes are never picked by Select all, but you can still tap them in. Deleting can’t be undone."
        },
        style = Shadow.type.body,
        color = colors.ink2,
        modifier = Modifier
            .padding(top = 8.dp)
            .widthIn(max = 560.dp),
    )
    if (count > 0) {
        Column(
            modifier = Modifier
                .padding(top = 24.dp)
                .weight(1f, fill = false)
                .clip(ShadowShapes.Card)
                .background(colors.surface2)
                .border(1.dp, colors.line2, ShadowShapes.Card)
                .padding(OUTLINE)
                .verticalScroll(rememberScrollState()),
        ) {
            // Each 52 dp row carries a 1 dp top rule (transparent on the first).
            picked.forEachIndexed { index, route ->
                if (index > 0) SheetDivider() else Spacer(Modifier.height(OUTLINE))
                BulkRow(route = route, hidden = route.id !in visibleIds)
            }
        }
    }
    val notes = buildList {
        val hidden = routes.hiddenSelectedCount()
        if (hidden > 0) {
            val subject = if (hidden == 1) "selected route is" else "selected routes are"
            add("$hidden $subject hidden by the filter and still counted.")
        }
        if (picked.any { it.isSelected }) {
            add("Your active route is selected. Another up-to-date route takes over if you delete it.")
        }
    }
    if (notes.isNotEmpty()) InfoNote(text = notes.joinToString(" "), modifier = Modifier.padding(top = 12.dp))
}

@Composable
private fun BulkRow(route: ProxyProfileSummary, hidden: Boolean) {
    val colors = Shadow.colors
    val (status, color) = routeStatusWord(route)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                route.name,
                style = Shadow.type.rowTitle,
                color = colors.ink1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (route.isPinned) {
                PinGlyph(
                    pinned = true,
                    tint = colors.skyText,
                    size = 14.dp,
                    modifier = Modifier.semantics { contentDescription = "Pinned" },
                )
            }
            if (hidden) {
                Box(
                    modifier = Modifier
                        .height(20.dp)
                        .clip(CircleShape)
                        .background(colors.surface3)
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Hidden by filter", style = TagLabel, color = colors.ink2)
                }
            }
        }
        Text("${route.protocol.scheme} · ${route.host}", style = Shadow.type.monoS, color = colors.ink3, maxLines = 1)
        Text(status, style = TagLabel, color = color, textAlign = TextAlign.End, modifier = Modifier.width(96.dp))
    }
}

@Composable
private fun ColumnScope.NoRoutePane(onAdd: () -> Unit) {
    val colors = Shadow.colors
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .fadeUpIn(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        IconTile(
            icon = ShadowIcons.Routes,
            size = 56.dp,
            iconSize = 26.dp,
            cornerRadius = 16.dp,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Text("Your library is empty", style = Shadow.type.titleS, color = colors.ink1)
        Text(
            "Refresh the public list or add a route link you trust.",
            style = Shadow.type.bodyS,
            color = colors.ink3,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 320.dp),
        )
        PrimaryButton(
            text = "Add routes",
            onClick = onAdd,
            size = ShadowButtonSize(52.dp, 22.dp, 18.dp, 16.dp, ShadowButtonLabel.Large),
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/** Widens the element by [bleed] to the start (the artboard's negative start margin). */
private fun Modifier.bleedStart(bleed: Dp): Modifier = layout { measurable, constraints ->
    val extra = bleed.roundToPx()
    val placeable = measurable.measure(
        constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra),
    )
    layout(placeable.width - extra, placeable.height) { placeable.place(-extra, 0) }
}

private val DetailShape = RoundedCornerShape(24.dp)
private val TABLET_JUMP_BOTTOM = 76.dp
private val ProblemStates = setOf(RouteState.Outdated, RouteState.Unavailable, RouteState.Unsupported)
private const val OUTDATED_CANT_CONNECT = "Outdated routes can’t connect. Edit it to keep it, or pick another."
private val SELECTION_BLEED = 10.dp
private val GRID_GAP = 8.dp
private const val SELECTED_FILL_ALPHA = 0.08f
private const val SELECTED_BORDER_ALPHA = 0.42f
private const val PROGRESS_FILL_ALPHA = 0.08f
private const val PROGRESS_BORDER_ALPHA = 0.18f
private const val SHOW_FILL_ALPHA = 0.16f
private const val EDIT_FILL_ALPHA = 0.16f
private const val RISK_FILL_ALPHA = 0.07f
private const val RISK_BORDER_ALPHA = 0.16f
private const val CTA_BORDER_ALPHA = 0.32f
private const val CTA_RING_RADIUS = 10.5f
