package com.stansful.sshvpnclient.ui.routes

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.stansful.sshvpnclient.domain.model.ProxyProfileSummary
import com.stansful.sshvpnclient.domain.usecase.proxy.ProxyShareLinkParser
import com.stansful.sshvpnclient.ui.designsystem.ChipTone
import com.stansful.sshvpnclient.ui.designsystem.DISABLED_ALPHA
import com.stansful.sshvpnclient.ui.designsystem.GlowDot
import com.stansful.sshvpnclient.ui.designsystem.IconTile
import com.stansful.sshvpnclient.ui.designsystem.LocalToaster
import com.stansful.sshvpnclient.ui.designsystem.PrimaryButton
import com.stansful.sshvpnclient.ui.designsystem.SecondaryButton
import com.stansful.sshvpnclient.ui.designsystem.ShadowButton
import com.stansful.sshvpnclient.ui.designsystem.ShadowButtonLabel
import com.stansful.sshvpnclient.ui.designsystem.ShadowButtonSize
import com.stansful.sshvpnclient.ui.designsystem.ShadowButtonVariant
import com.stansful.sshvpnclient.ui.designsystem.ShadowCard
import com.stansful.sshvpnclient.ui.designsystem.ShadowChip
import com.stansful.sshvpnclient.ui.designsystem.ShadowFilterChip
import com.stansful.sshvpnclient.ui.designsystem.ShadowFocusIndication
import com.stansful.sshvpnclient.ui.designsystem.ShadowIconButton
import com.stansful.sshvpnclient.ui.designsystem.ShadowIcons
import com.stansful.sshvpnclient.ui.designsystem.ShadowMenu
import com.stansful.sshvpnclient.ui.designsystem.ShadowMenuDivider
import com.stansful.sshvpnclient.ui.designsystem.ShadowMenuItem
import com.stansful.sshvpnclient.ui.designsystem.ShadowProgressBar
import com.stansful.sshvpnclient.ui.designsystem.ShadowSearchField
import com.stansful.sshvpnclient.ui.designsystem.ShadowTextButton
import com.stansful.sshvpnclient.ui.designsystem.StatusTone
import com.stansful.sshvpnclient.ui.designsystem.SubScreenBar
import com.stansful.sshvpnclient.ui.designsystem.SwapText
import com.stansful.sshvpnclient.ui.designsystem.ToastHost
import com.stansful.sshvpnclient.ui.designsystem.toastObstacle
import com.stansful.sshvpnclient.ui.designsystem.ToastTone
import com.stansful.sshvpnclient.ui.designsystem.ToasterState
import com.stansful.sshvpnclient.ui.designsystem.TopLevelBar
import com.stansful.sshvpnclient.ui.designsystem.fadeUpIn
import com.stansful.sshvpnclient.ui.designsystem.rememberToasterState
import com.stansful.sshvpnclient.ui.designsystem.shadowClickable
import com.stansful.sshvpnclient.ui.designsystem.stateDotColor
import com.stansful.sshvpnclient.ui.designsystem.toneText
import com.stansful.sshvpnclient.ui.opensource.OpenSourceUiState
import com.stansful.sshvpnclient.ui.opensource.RouteStatusFilter
import com.stansful.sshvpnclient.ui.opensource.RoutesNotice
import com.stansful.sshvpnclient.ui.opensource.RoutesNoticeTone
import com.stansful.sshvpnclient.ui.shell.LocalWindowWidthClass
import com.stansful.sshvpnclient.ui.shell.WindowWidthClass
import com.stansful.sshvpnclient.ui.theme.Shadow
import com.stansful.sshvpnclient.ui.theme.ShadowMotion
import com.stansful.sshvpnclient.ui.theme.ShadowShapes
import com.stansful.sshvpnclient.ui.theme.shadowTween
import kotlinx.coroutines.launch

/**
 * The route library. Phone (and the rail's single pane): the Routes.dc.html list with the mini
 * connection bar; Expanded width: the TabletRoutes.dc.html list-detail. Stateless apart from
 * [controller] (search field, menu, overlays) — everything else comes from [state] and goes out
 * through [actions].
 */
@Composable
internal fun RoutesScreen(
    state: RoutesScreenState,
    actions: RoutesActions,
    modifier: Modifier = Modifier,
    controller: RoutesUiController = rememberRoutesUiController(),
    linkParser: ProxyShareLinkParser = remember { ProxyShareLinkParser() },
    toaster: ToasterState = rememberToasterState(),
) {
    val expanded = LocalWindowWidthClass.current == WindowWidthClass.Expanded
    val routes = state.routes
    BackHandler(enabled = routes.selectionMode) { actions.onClearSelection() }
    BackHandler(enabled = !routes.selectionMode && controller.searchOpen && !expanded) {
        controller.searchOpen = false
        actions.onQueryChange("")
    }
    // Operation results arrive from the ViewModel as notices and leave as toasts.
    val notice = routes.notice
    LaunchedEffect(notice?.id) {
        if (notice != null) {
            toaster.showNotice(notice)
            actions.onNoticeShown()
        }
    }
    // The library places its own toasts: above the connection bar on phones (Routes.dc.html), at
    // the foot of the library pane on tablets (TabletRoutes.dc.html).
    CompositionLocalProvider(LocalToaster provides toaster) {
        Box(modifier = modifier.fillMaxSize()) {
            if (expanded) {
                TabletRoutesScreen(state = state, actions = actions, controller = controller)
            } else {
                PhoneRoutesScreen(state = state, actions = actions, controller = controller)
            }
            RoutesToastHost(
                toaster = toaster,
                tablet = expanded,
                aboveDock = !expanded && !routes.selectionMode,
            )
        }
        RoutesOverlays(state = state, actions = actions, controller = controller, tablet = expanded)
        AddRoutesHost(
            state = state,
            actions = actions,
            controller = controller,
            linkParser = linkParser,
            tablet = expanded,
        )
    }
}

/** Shows a ViewModel notice with its tone. */
internal fun ToasterState.showNotice(notice: RoutesNotice) {
    when (notice.tone) {
        RoutesNoticeTone.Success -> show(notice.text, detail = notice.detail)
        RoutesNoticeTone.Info -> show(notice.text, tone = ToastTone.Info, detail = notice.detail)
        RoutesNoticeTone.Error -> show(notice.text, tone = ToastTone.Error, detail = notice.detail)
        RoutesNoticeTone.Locked -> show(
            notice.text,
            tone = ToastTone.Neutral,
            icon = ShadowIcons.Lock,
            detail = notice.detail,
        )
    }
}

/**
 * The library's toast slot. Phone: 10 dp above the connection bar (12 dp above the nav while
 * selecting, when the bar is gone); tablet: the 400 dp foot of the library pane; always above the IME.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoxScope.RoutesToastHost(toaster: ToasterState, tablet: Boolean, aboveDock: Boolean) {
    val imeVisible = WindowInsets.isImeVisible
    val bottom = when {
        imeVisible -> TOAST_IME_GAP
        tablet -> TOAST_TABLET_BOTTOM
        aboveDock -> TOAST_ABOVE_DOCK
        else -> TOAST_IME_GAP
    }
    Box(
        modifier = Modifier
            .matchParentSize()
            .imePadding(),
    ) {
        ToastHost(
            state = toaster,
            modifier = if (tablet) {
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = TOAST_TABLET_START, bottom = bottom)
                    .width(TOAST_TABLET_SLOT)
            } else {
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = bottom)
            },
        )
    }
}

@Composable
private fun PhoneRoutesScreen(
    state: RoutesScreenState,
    actions: RoutesActions,
    controller: RoutesUiController,
    modifier: Modifier = Modifier,
) {
    val routes = state.routes
    val listState = rememberLazyListState()
    val selecting = routes.selectionMode
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Shadow.colors.bg),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (selecting) {
                SelectionHeader(routes = routes, actions = actions, controller = controller)
            } else {
                LibraryHeader(state = state, actions = actions, controller = controller)
            }
            PhoneLibraryList(
                state = state,
                actions = actions,
                controller = controller,
                listState = listState,
                modifier = Modifier.weight(1f),
            )
        }
        if (!selecting) {
            ConnectionDock(
                model = routes.connectionBar(state.serverName),
                activeRouteId = routes.activeProfile?.id,
                actions = actions,
                controller = controller,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        ScrollJumpButtons(
            listState = listState,
            routeCount = routes.profiles.size,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = if (selecting) SCROLL_JUMP_BOTTOM else SCROLL_JUMP_ABOVE_DOCK),
        )
    }
}

@Composable
private fun LibraryHeader(state: RoutesScreenState, actions: RoutesActions, controller: RoutesUiController) {
    val routes = state.routes
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (routes.query.isNotBlank()) controller.searchOpen = true
    }
    TopLevelBar(title = "Routes") {
        ShadowIconButton(
            icon = ShadowIcons.Search,
            contentDescription = "Search routes",
            onClick = {
                if (controller.searchOpen) {
                    controller.searchOpen = false
                    actions.onQueryChange("")
                } else {
                    controller.searchOpen = true
                    controller.menuOpen = false
                }
            },
            selected = controller.searchOpen,
            modifier = Modifier.semantics { stateDescription = if (controller.searchOpen) "Expanded" else "Collapsed" },
        )
        // RouteImport.dc.html: the button stays pressed (surface-3) while the Add routes sheet is open.
        val adding = routes.editor?.profileId == null && (routes.editor != null || routes.showBulkImport)
        ShadowIconButton(
            icon = ShadowIcons.Plus,
            contentDescription = "Add routes",
            onClick = actions.onAddRoutes,
            selected = adding,
        )
        Box {
            ShadowIconButton(
                icon = ShadowIcons.More,
                contentDescription = "More actions",
                onClick = { controller.menuOpen = !controller.menuOpen },
                selected = controller.menuOpen,
            )
            LibraryMenu(routes = routes, actions = actions, controller = controller)
        }
    }
    AnimatedVisibility(
        visible = controller.searchOpen,
        enter = expandVertically(shadowTween(ShadowMotion.Surface)) + fadeIn(shadowTween(ShadowMotion.Swap)),
        exit = shrinkVertically(shadowTween(ShadowMotion.Surface)) + fadeOut(shadowTween(ShadowMotion.Swap)),
    ) {
        ShadowSearchField(
            query = routes.query,
            onQueryChange = actions.onQueryChange,
            placeholder = "Name, host, protocol or transport",
            onClose = {
                controller.searchOpen = false
                actions.onQueryChange("")
            },
            modifier = Modifier
                .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp)
                .focusRequester(focusRequester),
        )
        LaunchedEffect(Unit) {
            if (routes.query.isEmpty()) runCatching { focusRequester.requestFocus() }
        }
    }
}

@Composable
private fun LibraryMenu(routes: OpenSourceUiState, actions: RoutesActions, controller: RoutesUiController) {
    val close = { controller.menuOpen = false }
    val removeNote = routes.removeUnavailableNote()
    // Routes.dc.html: the menu's top is 64 dp down and its end 12 dp from the window edge.
    ShadowMenu(expanded = controller.menuOpen, onDismissRequest = close, offset = MENU_OFFSET) {
        ShadowMenuItem(
            text = "Import from clipboard",
            icon = ShadowIcons.Clipboard,
            onClick = {
                close()
                actions.onImportFromClipboard()
            },
        )
        ShadowMenuItem(
            text = if (routes.isSyncing) "Refreshing…" else "Refresh from source",
            icon = ShadowIcons.Refresh,
            enabled = !routes.isSyncing && !routes.isRemovingUnavailable,
            onClick = {
                close()
                actions.onRefresh()
            },
        )
        ShadowMenuItem(
            text = "Connection activity",
            icon = ShadowIcons.Activity,
            onClick = {
                close()
                actions.onOpenActivity()
            },
        )
        ShadowMenuDivider()
        RemoveUnavailableMenuItem(
            count = routes.unavailableUnpinnedCount,
            note = removeNote,
            enabled = removeNote == REMOVE_UNAVAILABLE_READY,
            onClick = {
                close()
                actions.onRequestRemoveUnavailable()
            },
        )
    }
}

/** ShadowMenuItem's destructive row with the count in mono: "Remove unavailable (63)" + the reason line. */
@Composable
private fun RemoveUnavailableMenuItem(count: Int, note: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = Shadow.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressedFill by animateColorAsState(
        targetValue = if (pressed) colors.surface3 else colors.surface3.copy(alpha = 0f),
        animationSpec = shadowTween(ShadowMotion.Press, ShadowMotion.Ease),
        label = "remove-item-press",
    )
    val mono = Shadow.type.mono
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clip(ShadowShapes.MenuItem)
            .clickable(
                interactionSource = interaction,
                indication = ShadowFocusIndication,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .background(pressedFill)
            .heightIn(min = 56.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(ShadowIcons.Trash, contentDescription = null, tint = colors.coralText, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = buildAnnotatedString {
                    append("Remove unavailable ")
                    withStyle(SpanStyle(fontFamily = mono.fontFamily, fontSize = mono.fontSize)) {
                        append("($count)")
                    }
                },
                style = Shadow.type.rowTitle,
                color = colors.coralText,
                maxLines = 1,
            )
            Text(note, style = Shadow.type.caption, color = colors.ink3)
        }
    }
}

/** RoutesSelect.dc.html's contextual bar: close, count, Select all, delete — and the sky hint. */
@Composable
private fun SelectionHeader(routes: OpenSourceUiState, actions: RoutesActions, controller: RoutesUiController) {
    val colors = Shadow.colors
    val count = routes.selectedIds.size
    val allSelected = routes.allSelectableSelected()
    SubScreenBar(
        title = selectionTitle(count),
        onBack = actions.onClearSelection,
        navigationIcon = ShadowIcons.Close,
        backContentDescription = "Clear selection",
        container = colors.surface2,
        showDivider = true,
        // RoutesSelect.dc.html: 14 dp status + 52 dp row + 4 dp = 70 dp.
        contentPadding = PaddingValues(start = 6.dp, end = 8.dp, bottom = 4.dp),
        titleStartPadding = 4.dp,
        modifier = Modifier.fadeUpIn(),
    ) {
        ShadowTextButton(
            text = "Select all",
            onClick = actions.onSelectAll,
            size = ShadowButtonSize.Regular,
            enabled = !allSelected,
        )
        PlainIconButton(
            icon = ShadowIcons.Trash,
            contentDescription = "Delete selected",
            onClick = { controller.overlay = RoutesOverlay.DeleteSelected },
            tint = colors.coralText,
            container = colors.coralTint,
            iconSize = 21.dp,
            enabled = count > 0,
        )
    }
    Row(
        modifier = Modifier
            .padding(start = 20.dp, end = 20.dp, top = 12.dp)
            .fillMaxWidth()
            .clip(ShadowShapes.Banner)
            .background(colors.skyTint)
            .padding(horizontal = 14.dp, vertical = 11.dp)
            .fadeUpIn(1),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(ShadowIcons.Info, contentDescription = null, tint = colors.skySoft, modifier = Modifier.size(18.dp))
        SwapText(
            text = routes.selectionHint(),
            style = Shadow.type.bodyS,
            color = colors.skySoft,
            maxLines = 3,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PhoneLibraryList(
    state: RoutesScreenState,
    actions: RoutesActions,
    controller: RoutesUiController,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val routes = state.routes
    val selecting = routes.selectionMode
    val libraryEmpty = routes.libraryLoaded && routes.counts.total == 0
    val toaster = LocalToaster.current
    val tunnelId = (routes.session() as? RoutesSession.Routes)?.tunnelId
    val reduced = Shadow.reducedMotion
    // The card callbacks read the newest state when tapped instead of capturing it: the state changes
    // with every activity line and check step, and a captured copy would recompose every visible card.
    val latestRoutes by rememberUpdatedState(routes)
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            top = if (selecting) 0.dp else 6.dp,
            bottom = (if (selecting) 24.dp else 96.dp) + scrollJumpClearance(routes.profiles.size),
        ),
    ) {
        if (!routes.libraryLoaded) return@LazyColumn
        if (!selecting) {
            if (!controller.noticeHidden) {
                item(key = "notice") {
                    RiskNotice(
                        expanded = routes.appSettings.openSourceRiskBannerExpanded,
                        onExpandedChange = actions.onRiskExpandedChange,
                        onHide = {
                            controller.noticeHidden = true
                            toaster.show(
                                message = "Notice hidden",
                                tone = ToastTone.Neutral,
                                icon = null,
                                actionLabel = "Undo",
                                onAction = { controller.noticeHidden = false },
                                durationMillis = UNDO_TOAST_MS,
                            )
                        },
                        modifier = Modifier
                            .padding(horizontal = 20.dp)
                            .padding(bottom = 20.dp)
                            .fadeUpIn(),
                    )
                }
            }
            if (libraryEmpty) {
                item(key = "empty") {
                    EmptyLibraryCard(
                        syncing = routes.isSyncing,
                        onFetch = actions.onRefresh,
                        onAdd = actions.onAddRoutes,
                        modifier = Modifier
                            .padding(horizontal = 20.dp)
                            .fadeUpIn(),
                    )
                }
            } else {
                item(key = "summary") {
                    LibrarySummary(state = state, onRefresh = actions.onRefresh, modifier = Modifier.fadeUpIn(1))
                }
            }
            if (!routes.xrayCoreAvailable) {
                item(key = "engine") {
                    EngineMissingCard(
                        downloadSize = routes.engineDownloadSize(),
                        onInstall = actions.onGetEngine,
                        modifier = Modifier
                            .padding(start = 20.dp, end = 20.dp, top = 16.dp)
                            .fadeUpIn(2),
                    )
                }
            }
            if (!libraryEmpty) {
                item(key = "tools") {
                    if (routes.isCheckingAll) {
                        CheckProgressCard(routes = routes, onCancel = actions.onCancelCheck)
                    } else {
                        CheckAllTool(blockReason = routes.checkAllBlockReason(), onCheckAll = actions.onCheckAll)
                    }
                }
            }
        }
        if (!libraryEmpty) {
            item(key = "filters") {
                FilterChips(
                    routes = routes,
                    onSelect = actions.onStatusFilterChange,
                    modifier = Modifier
                        .padding(top = if (selecting) 16.dp else 20.dp)
                        .fadeUpIn(3),
                )
            }
            if (selecting) {
                item(key = "overline") { SelectionOverline(routes) }
            }
            itemsIndexed(routes.profiles, key = { _, route -> route.id }) { index, route ->
                RouteCard(
                    route = route,
                    state = route.routeState(routes.checkingRouteId),
                    flags = RouteCardFlags(
                        active = route.isSelected,
                        inUse = route.id == tunnelId,
                        selecting = selecting,
                        selected = route.id in routes.selectedIds,
                    ),
                    onClick = {
                        if (!selecting) showActivationToast(toaster, route, latestRoutes)
                        actions.onSelectRoute(route.id)
                    },
                    onLongClick = {
                        if (selecting) actions.onSelectRoute(route.id) else actions.onLongPressRoute(route.id)
                    },
                    onPinChange = { pinned ->
                        actions.onPinChange(route.id, pinned)
                        toaster.show(
                            message = (if (pinned) "Pinned " else "Unpinned ") + route.name,
                            tone = ToastTone.Info,
                        )
                    },
                    onMore = { controller.overlay = RoutesOverlay.RouteActions(route.id) },
                    modifier = Modifier
                        .then(if (reduced) Modifier else Modifier.animateItem())
                        .padding(horizontal = 20.dp)
                        .padding(top = if (index == 0) (if (selecting) 0.dp else 16.dp) else 8.dp)
                        .then(if (index < FADE_IN_ITEMS) Modifier.fadeUpIn(index + 4) else Modifier),
                )
            }
            if (routes.profiles.isEmpty()) {
                item(key = "no-match") {
                    NoMatch(
                        query = routes.query.trim(),
                        filter = routes.statusFilter,
                        onClearSearch = { actions.onQueryChange("") },
                        onShowAll = { actions.onStatusFilterChange(RouteStatusFilter.ALL) },
                    )
                }
            } else if (!selecting) {
                item(key = "hold-hint") {
                    HoldToSelectHint(onClick = actions.onBeginSelection)
                }
            }
        }
    }
}

/** Toasts of Routes.dc.html when a route becomes active (the connection itself never changes). */
private fun showActivationToast(
    toaster: ToasterState,
    route: ProxyProfileSummary,
    routes: OpenSourceUiState,
) {
    if (route.isSelected) return
    val tunnelId = (routes.session() as? RoutesSession.Routes)?.tunnelId
    when {
        route.isStale -> toaster.show(
            message = "${route.name} is outdated",
            detail = "It left the public list. You can check or edit it, but not connect through it.",
            tone = ToastTone.Info,
        )
        tunnelId != null && route.id != tunnelId -> toaster.show("${route.name} is now active. Reconnect to use it.")
        tunnelId != null -> toaster.show(
            message = "${route.name} is active again.",
            detail = "It’s the route you’re connected through.",
        )
        else -> toaster.show("${route.name} is now active.")
    }
}

/** Collapsible public-route risk notice (expansion persisted) with a hide button. */
@Composable
private fun RiskNotice(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Shadow.colors
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = shadowTween(ShadowMotion.Dialog),
        label = "notice-chevron",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(ShadowShapes.Banner)
            .background(colors.amberTint),
        verticalAlignment = Alignment.Top,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(ShadowShapes.Banner)
                .shadowClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    onClickLabel = if (expanded) "Collapse" else "Expand",
                    onClick = { onExpandedChange(!expanded) },
                )
                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                .padding(start = 14.dp, end = 2.dp, top = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                ShadowIcons.Warning,
                contentDescription = null,
                tint = colors.amberText,
                modifier = Modifier.size(18.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .animateContentSize(shadowTween(ShadowMotion.Surface)),
            ) {
                Text(
                    text = "Public routes come from third parties. Use them at your own risk.",
                    style = Shadow.type.bodyS,
                    color = colors.ink1,
                )
                if (expanded) {
                    Text(
                        text = "The shadow developer can’t vouch for their safety or for the safety of your data.",
                        style = Shadow.type.bodyS,
                        color = colors.ink2,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            Icon(
                ShadowIcons.ChevronDown,
                contentDescription = null,
                tint = colors.amberText,
                modifier = Modifier
                    .size(18.dp)
                    .rotate(rotation),
            )
        }
        PlainIconButton(
            icon = ShadowIcons.Close,
            contentDescription = "Hide notice",
            onClick = onHide,
            iconSize = 16.dp,
            modifier = Modifier.padding(top = 0.dp),
        )
    }
}

@Composable
private fun EmptyLibraryCard(
    syncing: Boolean,
    onFetch: () -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Shadow.colors
    ShadowCard(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 21.dp, end = 21.dp, top = 33.dp, bottom = 21.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            IconTile(icon = ShadowIcons.Routes, size = 56.dp, iconSize = 26.dp, cornerRadius = 16.dp)
            Text(
                text = "Your library is empty",
                style = Shadow.type.titleS,
                color = colors.ink1,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                text = "Fetch the public route list, or add a route from a vless, vmess or trojan link.",
                style = Shadow.type.bodyS,
                color = colors.ink3,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .widthIn(max = 270.dp),
            )
            PrimaryButton(
                text = if (syncing) "Fetching…" else "Fetch public list",
                onClick = onFetch,
                icon = ShadowIcons.Refresh,
                loading = syncing,
                modifier = Modifier
                    .padding(top = 20.dp)
                    .fillMaxWidth(),
            )
            SecondaryButton(
                text = "Add from a link",
                onClick = onAdd,
                icon = ShadowIcons.Link,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth(),
            )
        }
    }
}

/** "128 routes · 41 available", the list's freshness, and the Refresh chip. */
@Composable
private fun LibrarySummary(state: RoutesScreenState, onRefresh: () -> Unit, modifier: Modifier = Modifier) {
    val routes = state.routes
    val colors = Shadow.colors
    val (line, lineColor) = when {
        routes.isSyncing -> "Fetching the public list…" to colors.amberText
        routes.isRemovingUnavailable -> "Removing unavailable routes…" to colors.amberText
        state.listSyncedAt != null -> {
            val ago = formatAgo(state.nowMillis, state.listSyncedAt)
            val fresh = routes.counts.total > 0 && routes.counts.notChecked == routes.counts.total
            (if (fresh) "Fetched $ago · not checked yet" else "List updated $ago") to colors.ink3
        }
        else -> null to colors.ink3
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = countsLine(
                    routes.counts.total,
                    routes.counts.available,
                    Shadow.type.monoMedium.copy(fontSize = Shadow.type.segment.fontSize),
                ),
                style = Shadow.type.bodyMedium.copy(fontWeight = Shadow.type.label.fontWeight),
                color = colors.ink1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (line != null) {
                SwapText(text = line, style = Shadow.type.bodyS, color = lineColor)
            }
        }
        ShadowChip(
            label = if (routes.isSyncing) "Refreshing…" else "Refresh",
            onClick = onRefresh,
            icon = ShadowIcons.Refresh,
            tone = if (routes.isSyncing) ChipTone.Progress else ChipTone.Default,
            loading = routes.isSyncing,
            enabled = !routes.isRemovingUnavailable,
            modifier = Modifier.semantics {
                contentDescription = if (routes.isSyncing) "Refreshing the public list" else "Refresh the public list"
            },
        )
    }
}

@Composable
private fun EngineMissingCard(downloadSize: String?, onInstall: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Shadow.colors
    ShadowCard(modifier = modifier.fillMaxWidth(), contentPadding = PaddingValues(15.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(
                icon = ShadowIcons.Engine,
                size = 40.dp,
                iconSize = 20.dp,
                tint = colors.amberText,
                container = colors.amberTint,
                cornerRadius = 12.dp,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Xray engine isn’t installed",
                    style = Shadow.type.bodyMedium.copy(fontWeight = Shadow.type.label.fontWeight),
                    color = colors.ink1,
                )
                val sizeStyle = Shadow.type.monoS
                Text(
                    text = buildAnnotatedString {
                        append("Routes need it to connect and to run checks.")
                        if (downloadSize != null) {
                            append(" Download size ")
                            withStyle(SpanStyle(fontFamily = sizeStyle.fontFamily, fontSize = sizeStyle.fontSize)) {
                                append(downloadSize)
                            }
                            append(".")
                        }
                    },
                    style = Shadow.type.bodyS,
                    color = colors.ink3,
                    modifier = Modifier.padding(top = 2.dp),
                )
                ShadowButton(
                    text = "Install engine",
                    onClick = onInstall,
                    icon = ShadowIcons.Download,
                    size = ShadowButtonSize(40.dp, 16.dp, 16.dp, 14.dp, ShadowButtonLabel.Medium),
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

/** "Testing 54 of 128 · 21 available so far" with Cancel and the progress bar. */
@Composable
private fun CheckProgressCard(routes: OpenSourceUiState, onCancel: () -> Unit) {
    val colors = Shadow.colors
    val number = Shadow.type.monoMedium.copy(fontSize = Shadow.type.segment.fontSize)
    ShadowCard(
        modifier = Modifier
            .padding(start = 20.dp, end = 20.dp, top = 16.dp)
            .fillMaxWidth()
            .semantics(mergeDescendants = false) { contentDescription = "Route check in progress" },
        contentPadding = PaddingValues(start = 17.dp, end = 9.dp, top = 15.dp, bottom = 17.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            RingSpinner(size = 20.dp, strokeWidth = 2.4f, color = colors.amber)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = buildAnnotatedString {
                        append("Testing ")
                        withStyleNumber(number, colors.amberText) { append(routes.checkCompleted.toString()) }
                        append(" of ")
                        withStyleNumber(number, colors.ink1) { append(routes.checkTotal.toString()) }
                    },
                    style = Shadow.type.button,
                    color = colors.ink1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildAnnotatedString {
                        withStyleNumber(
                            Shadow.type.monoS,
                            colors.mintText,
                        ) { append(routes.checkAvailableSoFar.toString()) }
                        append(" available so far")
                    },
                    style = Shadow.type.caption,
                    color = colors.ink3,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            ShadowButton(
                text = "Cancel",
                onClick = onCancel,
                variant = ShadowButtonVariant.Text,
                size = ShadowButtonSize(40.dp, 12.dp, 16.dp, 12.dp, ShadowButtonLabel.Medium),
                contentDescription = "Cancel route check",
            )
        }
        ShadowProgressBar(
            progress = if (routes.checkTotal > 0) routes.checkCompleted.toFloat() / routes.checkTotal else 0f,
            modifier = Modifier
                .padding(start = 32.dp, end = 8.dp, top = 14.dp)
                .semantics { contentDescription = "Route check progress" },
        )
        Text(
            text = "Route cards update when the check ends.",
            style = Shadow.type.caption,
            color = colors.ink3,
            modifier = Modifier.padding(start = 32.dp, end = 8.dp, top = 10.dp),
        )
    }
}

private inline fun AnnotatedString.Builder.withStyleNumber(
    style: TextStyle,
    color: Color,
    block: AnnotatedString.Builder.() -> Unit,
) {
    withStyle(
        SpanStyle(
            fontFamily = style.fontFamily,
            fontSize = style.fontSize,
            fontWeight = style.fontWeight,
            color = color,
        ),
        block,
    )
}

/** "Check all" and the line under it: what it does, or why it is off (with a lock). */
@Composable
private fun CheckAllTool(blockReason: String?, onCheckAll: () -> Unit) {
    val colors = Shadow.colors
    Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp)) {
        ShadowChip(
            label = "Check all",
            onClick = onCheckAll,
            icon = ShadowIcons.Shield,
            enabled = blockReason == null,
        )
        Row(
            modifier = Modifier.padding(start = 4.dp, top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (blockReason != null) {
                Icon(ShadowIcons.Lock, contentDescription = null, tint = colors.ink2, modifier = Modifier.size(14.dp))
            }
            SwapText(
                text = blockReason ?: "Tests every route, usually in about 10 s",
                style = Shadow.type.caption,
                color = if (blockReason != null) colors.ink2 else colors.ink3,
            )
        }
    }
}

@Composable
private fun FilterChips(
    routes: OpenSourceUiState,
    onSelect: (RouteStatusFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val counts = routes.counts
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .semantics { contentDescription = "Filter routes" },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RouteStatusFilter.entries.forEach { filter ->
            val count = when (filter) {
                RouteStatusFilter.ALL -> counts.total
                RouteStatusFilter.AVAILABLE -> counts.available
                RouteStatusFilter.PINNED -> counts.pinned
                RouteStatusFilter.NOT_CHECKED -> counts.notChecked
            }
            ShadowFilterChip(
                label = filter.label(),
                selected = routes.statusFilter == filter,
                onClick = { onSelect(filter) },
                count = count.toString(),
            )
        }
        Spacer(Modifier.width(12.dp))
    }
}

@Composable
private fun SelectionOverline(routes: OpenSourceUiState) {
    val colors = Shadow.colors
    val counts = routes.counts
    val meta = when {
        counts.total == 0 -> "0 routes"
        routes.statusFilter != RouteStatusFilter.ALL || routes.query.isNotBlank() ->
            "${routes.profiles.size} of ${counts.total}"
        else -> "${counts.total} · ${counts.available} available"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(routes.listOverline().uppercase(), style = Shadow.type.overline, color = colors.ink3)
        SwapText(text = meta, style = Shadow.type.monoS, color = colors.ink3)
    }
}

/** Empty result of a search or filter. */
@Composable
private fun NoMatch(
    query: String,
    filter: RouteStatusFilter,
    onClearSearch: () -> Unit,
    onShowAll: () -> Unit,
) {
    val colors = Shadow.colors
    val searching = query.isNotEmpty()
    Column(
        // The (empty) list keeps its 16 dp top margin above the 28 dp padding of the message.
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 40.dp, end = 40.dp, top = 16.dp + 28.dp, bottom = 8.dp)
            .fadeUpIn(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconTile(icon = ShadowIcons.Search, size = 56.dp, iconSize = 24.dp, tint = colors.ink3, cornerRadius = 16.dp)
        Text(
            text = if (searching) "Nothing matches “$query”" else "No routes in this view",
            style = Shadow.type.titleS,
            color = colors.ink1,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(top = 14.dp)
                .widthIn(max = 300.dp),
        )
        Text(
            text = when {
                searching -> "Search looks at names, hosts, protocols and transports such as XHTTP or REALITY."
                filter == RouteStatusFilter.PINNED ->
                    "Pinned routes are skipped by Select all and by Remove unavailable."
                else -> "Nothing in your library fits this filter right now."
            },
            style = Shadow.type.bodyS,
            color = colors.ink3,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(top = 6.dp)
                .widthIn(max = 280.dp),
        )
        SecondaryButton(
            text = if (searching) "Clear search" else "Show all routes",
            onClick = if (searching) onClearSearch else onShowAll,
            size = ShadowButtonSize(40.dp, 18.dp, 16.dp, 14.dp, ShadowButtonLabel.Medium),
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@Composable
private fun HoldToSelectHint(onClick: () -> Unit) {
    val colors = Shadow.colors
    Row(
        modifier = Modifier
            .padding(start = 20.dp, end = 20.dp, top = 12.dp)
            .fillMaxWidth()
            .height(44.dp)
            .clip(ShadowShapes.Tile)
            .shadowClickable(
                remember { MutableInteractionSource() },
                onClickLabel = "Select several routes",
                onClick = onClick,
            ),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(ShadowIcons.CheckCircle, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(16.dp))
        Text("Press and hold to select several", style = Shadow.type.bodyS, color = colors.ink3)
    }
}

/** The fade above the nav and the mini connection bar (status dot, active route, one action). */
@Composable
private fun ConnectionDock(
    model: ConnectionBarModel,
    activeRouteId: String?,
    actions: RoutesActions,
    controller: RoutesUiController,
    modifier: Modifier = Modifier,
) {
    val colors = Shadow.colors
    val toaster = LocalToaster.current
    Box(modifier = modifier.fillMaxWidth()) {
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(104.dp)
                .background(Brush.verticalGradient(0f to colors.bg.copy(alpha = 0f), DOCK_FADE_STOP to colors.bg)),
        )
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
                .fillMaxWidth()
                .height(60.dp)
                // Shell toasts (the library's own sit at TOAST_ABOVE_DOCK) clear the bar by 10 dp too.
                .toastObstacle(gap = 10.dp)
                .clip(ShadowShapes.Segmented)
                .background(colors.surface2)
                .border(1.dp, colors.surface3, ShadowShapes.Segmented)
                .padding(start = 4.dp, end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
                    .clip(ShadowShapes.Tile)
                    .shadowClickable(remember { MutableInteractionSource() }, onClick = actions.onOpenHome)
                    .semantics(mergeDescendants = true) { contentDescription = model.description }
                    .padding(start = 12.dp, end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlowDot(
                    color = colors.stateDotColor(model.dotTone),
                    glow = model.dotTone != StatusTone.Neutral,
                    modifier = Modifier.blinking(model.dotBlink),
                )
                Column(modifier = Modifier.weight(1f)) {
                    SwapText(
                        text = model.status,
                        style = Shadow.type.caption.copy(fontWeight = Shadow.type.bodyMedium.fontWeight),
                        color = colors.toneText(model.statusTone),
                    )
                    Text(
                        text = model.route,
                        style = RouteLineText,
                        color = colors.ink1,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            DockButton(
                button = model.button,
                onClick = {
                    when (model.button) {
                        BarButton.Connect, BarButton.TryAgain -> actions.onConnect()
                        BarButton.AskStopCheck -> controller.overlay = RoutesOverlay.StopCheckAndConnect
                        BarButton.Switch -> controller.overlay = RoutesOverlay.SwitchMode()
                        BarButton.GetEngine -> actions.onGetEngine()
                        BarButton.Reconnect -> activeRouteId?.let(actions.onReconnectThrough)
                        BarButton.Disconnect, BarButton.Stop -> {
                            actions.onDisconnect()
                            toaster.show(
                                "Disconnected",
                                detail = "Checks are available again.",
                                tone = ToastTone.Neutral,
                            )
                        }
                        BarButton.Disabled -> Unit
                    }
                },
            )
        }
    }
}

/** The bar's one action: 36 dp, radius 12, padding 12 / 14, 16 dp 2-stroke icon, 13/600 label. */
@Composable
private fun DockButton(button: BarButton, onClick: () -> Unit) {
    val colors = Shadow.colors
    val (label, icon) = when (button) {
        BarButton.Connect, BarButton.AskStopCheck, BarButton.Disabled -> "Connect" to BarPower
        BarButton.TryAgain -> "Try again" to BarPower
        BarButton.GetEngine -> "Get engine" to BarDownload
        BarButton.Reconnect -> "Reconnect" to BarRefresh
        BarButton.Switch -> "Switch" to BarPower
        BarButton.Disconnect -> "Disconnect" to BarPower
        BarButton.Stop -> "Stop" to BarPower
    }
    val danger = button == BarButton.Disconnect || button == BarButton.Stop
    val enabled = button != BarButton.Disabled
    val ink = if (danger) colors.coralText else colors.onAmber
    Row(
        modifier = Modifier
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .height(DOCK_BUTTON_HEIGHT)
            .clip(ShadowShapes.Tile)
            .shadowClickable(remember { MutableInteractionSource() }, enabled = enabled, onClick = onClick)
            .background(if (danger) colors.coralTint else colors.amber)
            .padding(start = 12.dp, end = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(16.dp))
        Text(label, style = Shadow.type.label, color = ink, maxLines = 1)
    }
}

/**
 * Quick scrolling for long lists (more than 8 routes, as before the redesign): once the list is
 * scrolled, a 44 dp surface-3 pill (centered by the caller, clear of the cards' pin and menu column)
 * offers "Scroll to top" and — until the end is reached — "Scroll to bottom".
 */
@Composable
internal fun ScrollJumpButtons(listState: LazyListState, routeCount: Int, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val reduced = Shadow.reducedMotion
    val colors = Shadow.colors
    val scrolled by remember(routeCount) {
        derivedStateOf {
            routeCount > SCROLL_JUMP_MIN_ROUTES && listState.firstVisibleItemIndex > SCROLL_JUMP_AFTER_ITEMS
        }
    }
    val canGoDown by remember { derivedStateOf { listState.canScrollForward } }
    AnimatedVisibility(
        visible = scrolled,
        modifier = modifier,
        enter = fadeIn(shadowTween(ShadowMotion.Small)),
        exit = fadeOut(shadowTween(ShadowMotion.Small)),
    ) {
        Row(
            modifier = Modifier
                .height(44.dp)
                .clip(ShadowShapes.Pill)
                .background(colors.surface3)
                .border(1.dp, colors.line2, ShadowShapes.Pill),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            JumpButton(icon = ShadowIcons.ChevronUp, description = "Scroll to top") {
                scope.launch { if (reduced) listState.scrollToItem(0) else listState.animateScrollToItem(0) }
            }
            AnimatedVisibility(
                visible = canGoDown,
                enter = fadeIn(shadowTween(ShadowMotion.Small)) + expandHorizontally(shadowTween(ShadowMotion.Small)),
                exit = fadeOut(shadowTween(ShadowMotion.Small)) + shrinkHorizontally(shadowTween(ShadowMotion.Small)),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(20.dp)
                            .background(colors.line2),
                    )
                    JumpButton(icon = ShadowIcons.ChevronDown, description = "Scroll to bottom") {
                        scope.launch {
                            val last = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                            if (reduced) listState.scrollToItem(last) else listState.animateScrollToItem(last)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun JumpButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    PlainIconButton(
        icon = icon,
        contentDescription = description,
        onClick = onClick,
        tint = Shadow.colors.ink1,
        shape = ShadowShapes.Pill,
        iconSize = 20.dp,
        modifier = Modifier.width(52.dp),
    )
}

private const val DOCK_FADE_STOP = 0.72f
private val MENU_OFFSET = DpOffset((-4).dp, 2.dp)
private val DOCK_BUTTON_HEIGHT = 36.dp
private const val UNDO_TOAST_MS = 5_000L
private const val SCROLL_JUMP_MIN_ROUTES = 8

/** Extra list end padding, so the last card clears the scroll pill of a long list. */
internal fun scrollJumpClearance(routeCount: Int): Dp = if (routeCount > SCROLL_JUMP_MIN_ROUTES) 56.dp else 0.dp
private const val SCROLL_JUMP_AFTER_ITEMS = 6

/** Clears the connection bar (60 dp, 10 dp above the nav) by 12 dp. */
private val SCROLL_JUMP_ABOVE_DOCK = 82.dp
private val SCROLL_JUMP_BOTTOM = 24.dp

/** Routes.dc.html: the toast's bottom is 152 px above the window, the nav is 72 → 80 dp above the content. */
private val TOAST_ABOVE_DOCK = 80.dp
private val TOAST_IME_GAP = 12.dp
private val TOAST_TABLET_BOTTOM = 20.dp

/** TabletRoutes.dc.html: a 400 dp toast 24 dp into the library pane (ToastHost adds 20 dp each side). */
private val TOAST_TABLET_START = 4.dp
private val TOAST_TABLET_SLOT = 440.dp

/** Only the first screenful of cards rises in. */
internal const val FADE_IN_ITEMS = 8
