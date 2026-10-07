package com.stansful.sshvpnclient.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stansful.sshvpnclient.ui.designsystem.ConnectOrb
import com.stansful.sshvpnclient.ui.designsystem.SegmentOption
import com.stansful.sshvpnclient.ui.designsystem.ShadowDialog
import com.stansful.sshvpnclient.ui.designsystem.ShadowIconButton
import com.stansful.sshvpnclient.ui.designsystem.ShadowIcons
import com.stansful.sshvpnclient.ui.designsystem.ShadowSegmented
import com.stansful.sshvpnclient.ui.designsystem.StatusTone
import com.stansful.sshvpnclient.ui.designsystem.SwapText
import com.stansful.sshvpnclient.ui.designsystem.WordmarkTopBar
import com.stansful.sshvpnclient.ui.designsystem.stateDotColor
import com.stansful.sshvpnclient.ui.shell.LocalWindowWidthClass
import com.stansful.sshvpnclient.ui.shell.WindowWidthClass
import com.stansful.sshvpnclient.ui.system.NoSelectedAppsDialog
import com.stansful.sshvpnclient.ui.theme.Shadow
import com.stansful.sshvpnclient.ui.theme.ShadowMotion
import com.stansful.sshvpnclient.ui.theme.shadowTween

/** Everything the user can do on Home. Defaults are no-ops (previews, screenshots). */
@Immutable
internal class HomeActions(
    val onModeSelect: (HomeMode) -> Unit = {},
    val onOrbClick: () -> Unit = {},
    val onOpenActivity: () -> Unit = {},
    val onOpenAppRouting: () -> Unit = {},
    val onOpenRouteLibrary: () -> Unit = {},
    val onOpenTerminal: () -> Unit = {},
    val onCheckTunnel: () -> Unit = {},
    val onCheckRoute: () -> Unit = {},
    val onSelectServer: (String) -> Unit = {},
    val onManageServers: () -> Unit = {},
    val onOpenKeys: () -> Unit = {},
    val onAddServer: () -> Unit = {},
    val onEditSelectedServer: () -> Unit = {},
    val onSwitchRoute: (String) -> Unit = {},
    val onDownloadEngine: () -> Unit = {},
    val onOpenUpdates: () -> Unit = {},
    val onDismissUpdate: () -> Unit = {},
    val onConfirmSwitch: () -> Unit = {},
    val onCancelSwitch: () -> Unit = {},
    val onPickApps: () -> Unit = {},
    val onDismissNoApps: () -> Unit = {},
    val onCopyActivity: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
)

/** Which Home sheet is open. */
internal enum class HomeSheet { RoutePool, ChooseServer, QuickSwitch }

/**
 * Home, stateless: phone layout (Main.dc.html) below 840 dp, two panes (TabletHome.dc.html) at
 * Expanded width. [sheet] is the open sheet (hoisted so the route can restore it).
 */
@Composable
internal fun HomeScreen(
    state: HomeUiState,
    actions: HomeActions,
    modifier: Modifier = Modifier,
    sheet: HomeSheet? = null,
    onSheetChange: (HomeSheet?) -> Unit = {},
    poolTab: PoolTab = PoolTab.Pool,
) {
    val onChip: (HomeChip) -> Unit = { chip ->
        when (chip) {
            is HomeChip.CheckTunnel -> if (chip.state != CheckState.Running) actions.onCheckTunnel()
            is HomeChip.CheckRoute -> if (chip.state != CheckState.Running) actions.onCheckRoute()
            is HomeChip.Terminal -> actions.onOpenTerminal()
            is HomeChip.AppRouting -> actions.onOpenAppRouting()
            HomeChip.RouteLibrary -> actions.onOpenRouteLibrary()
            is HomeChip.RoutePool -> onSheetChange(HomeSheet.RoutePool)
            HomeChip.Activity -> actions.onOpenActivity()
        }
    }
    val onCard: (CardTarget) -> Unit = { target ->
        when (target) {
            CardTarget.RoutePool -> onSheetChange(HomeSheet.RoutePool)
            CardTarget.ChooseServer -> onSheetChange(HomeSheet.ChooseServer)
            CardTarget.QuickSwitch -> onSheetChange(HomeSheet.QuickSwitch)
            CardTarget.RouteLibrary -> actions.onOpenRouteLibrary()
        }
    }
    if (LocalWindowWidthClass.current == WindowWidthClass.Expanded) {
        HomeTwoPane(state = state, actions = actions, onChip = onChip, modifier = modifier)
    } else {
        HomePhone(state = state, actions = actions, onChip = onChip, onCard = onCard, modifier = modifier)
    }
    HomeOverlays(
        state = state,
        actions = actions,
        sheet = sheet,
        onSheetChange = onSheetChange,
        poolTab = poolTab,
    )
}

@Composable
private fun HomePhone(
    state: HomeUiState,
    actions: HomeActions,
    onChip: (HomeChip) -> Unit,
    onCard: (CardTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Shadow.colors
    val hero = state.hero
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg)
            .wrapContentWidth()
            .widthIn(max = PHONE_MAX_WIDTH),
    ) {
        WordmarkTopBar(
            dotColor = colors.stateDotColor(hero.tone),
            dotGlow = hero.tone != StatusTone.Neutral,
            // Medium width (landscape phones, small tablets): the rail already shows "● shadow".
            showWordmark = LocalWindowWidthClass.current == WindowWidthClass.Compact,
        ) {
            if (state.showActivity) {
                ShadowIconButton(
                    icon = ShadowIcons.Activity,
                    contentDescription = "Connection activity",
                    onClick = actions.onOpenActivity,
                )
            }
        }
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            PhoneBodyLayout(
                viewport = maxHeight,
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                top = {
                    Column {
                        UpdateBannerSlot(state, actions, Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp))
                        ModeSwitch(
                            mode = state.mode,
                            onSelect = actions.onModeSelect,
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp),
                        )
                    }
                },
                orb = {
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = ORB_SLOT_TOP),
                        contentAlignment = Alignment.Center,
                    ) {
                        // HomeLight: when banners squeeze the slot the orb steps down to 0.94.
                        val scale by animateFloatAsState(
                            targetValue = if (maxHeight < ORB_COMPACT_BELOW) ORB_COMPACT_SCALE else 1f,
                            animationSpec = shadowTween(ShadowMotion.Surface),
                            label = "home-orb-scale",
                        )
                        ConnectOrb(
                            state = hero.orb,
                            label = hero.orbLabel,
                            onClick = actions.onOrbClick,
                            contentDescription = hero.orbDescription,
                            enabled = hero.orbEnabled,
                            modifier = Modifier.graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                            },
                        )
                    }
                },
                middle = {
                    Column {
                        HeroStatus(hero = hero, modifier = Modifier.padding(horizontal = 24.dp))
                        HomeNotes(
                            state = state,
                            actions = actions,
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp),
                        )
                    }
                },
                bottom = { PhoneBottom(state = state, actions = actions, onChip = onChip, onCard = onCard) },
            )
        }
    }
}

/** Card (or first-run card), check failure, chips and the Check tunnel caption. */
@Composable
private fun PhoneBottom(
    state: HomeUiState,
    actions: HomeActions,
    onChip: (HomeChip) -> Unit,
    onCard: (CardTarget) -> Unit,
) {
    // HomeStates "Routes · error": a note with its own fix button replaces the chip row.
    val showChips = state.notes.none { it.hasButton }
    Column {
        Column(
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = if (showChips) 0.dp else 14.dp),
        ) {
            val card = state.card
            when {
                state.firstRun -> FirstRunCard(onAddServer = actions.onAddServer)
                card != null -> HomeModeCard(card = card, onClick = { onCard(card.target) })
            }
            AnimatedVisibility(
                visible = state.checkFailedServer != null,
                enter = fadeIn(shadowTween(ShadowMotion.Swap)) + expandVertically(shadowTween(ShadowMotion.SheetEnter)),
                exit = fadeOut(shadowTween(ShadowMotion.Swap)) + shrinkVertically(shadowTween(ShadowMotion.SheetEnter)),
            ) {
                CheckFailedBanner(
                    serverName = state.checkFailedServer.orEmpty(),
                    onOpenActivity = actions.onOpenActivity,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        if (showChips) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(
                        start = 20.dp,
                        end = 20.dp,
                        top = 12.dp,
                        bottom = if (state.showCheckCaption) 10.dp else 14.dp,
                    ),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HomeChips(chips = state.chips, onClick = onChip)
            }
        }
        AnimatedVisibility(
            visible = state.showCheckCaption,
            enter = fadeIn(shadowTween(ShadowMotion.Swap)) + expandVertically(shadowTween(ShadowMotion.SheetEnter)),
            exit = fadeOut(shadowTween(ShadowMotion.Swap)) + shrinkVertically(shadowTween(ShadowMotion.SheetEnter)),
        ) {
            CheckCaption(modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp))
        }
    }
}

/**
 * Main.dc.html's column: [top], the orb slot (286 dp + 4), [middle], free space, then [bottom]
 * pinned to the bottom of the [viewport]. When banners leave less room the orb slot gives up to
 * 50 dp first (the halo may overlap its neighbours); only then does the column scroll.
 */
@Composable
private fun PhoneBodyLayout(
    viewport: Dp,
    top: @Composable () -> Unit,
    orb: @Composable () -> Unit,
    middle: @Composable () -> Unit,
    bottom: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Layout(contents = listOf(top, orb, middle, bottom), modifier = modifier) { measurables, constraints ->
        val (topMeasurables, orbMeasurables, middleMeasurables, bottomMeasurables) = measurables
        val width = constraints.maxWidth
        val loose = Constraints(minWidth = width, maxWidth = width)
        val topPlaceables = topMeasurables.map { it.measure(loose) }
        val middlePlaceables = middleMeasurables.map { it.measure(loose) }
        val bottomPlaceables = bottomMeasurables.map { it.measure(loose) }
        val topHeight = topPlaceables.sumOf { it.height }
        val middleHeight = middlePlaceables.sumOf { it.height }
        val bottomHeight = bottomPlaceables.sumOf { it.height }
        val gap = BOTTOM_GAP_MIN.roundToPx()
        val viewportPx = viewport.roundToPx()
        val orbHeight = (viewportPx - topHeight - middleHeight - bottomHeight - gap)
            .coerceIn(ORB_SLOT_MIN.roundToPx(), ORB_SLOT.roundToPx())
        val orbPlaceables = orbMeasurables.map { it.measure(Constraints.fixed(width, orbHeight)) }
        val height = maxOf(viewportPx, topHeight + orbHeight + middleHeight + gap + bottomHeight)
        layout(width, height) {
            var y = 0
            topPlaceables.forEach {
                it.place(0, y)
                y += it.height
            }
            orbPlaceables.forEach { it.place(0, y) }
            y += orbHeight
            middlePlaceables.forEach {
                it.place(0, y)
                y += it.height
            }
            var bottomY = height - bottomHeight
            bottomPlaceables.forEach {
                it.place(0, bottomY)
                bottomY += it.height
            }
        }
    }
}

/** The 3-way mode switch and the mode's one-line description. */
@Composable
internal fun ModeSwitch(
    mode: HomeMode,
    onSelect: (HomeMode) -> Unit,
    modifier: Modifier = Modifier,
    hintTopPadding: Dp = 10.dp,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        ShadowSegmented(
            options = ModeOptions,
            selectedIndex = mode.ordinal,
            onSelect = { index -> onSelect(HomeMode.entries[index]) },
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, top = hintTopPadding),
            contentAlignment = Alignment.Center,
        ) {
            SwapText(text = mode.hint, style = Shadow.type.bodyS, color = Shadow.colors.ink3)
        }
    }
}

private val ModeOptions: List<SegmentOption>
    get() = listOf(
        SegmentOption(HomeMode.Auto.label, ShadowIcons.Auto),
        SegmentOption(HomeMode.Server.label, ShadowIcons.Server),
        SegmentOption(HomeMode.Routes.label, ShadowIcons.Routes),
    )

/** Pill, headline and subline (a polite live region). */
@Composable
internal fun HeroStatus(
    hero: HomeHero,
    modifier: Modifier = Modifier,
    large: Boolean = false,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (large) 10.dp else 8.dp),
    ) {
        HeroPill(hero = hero)
        SwapText(
            text = (if (large) hero.tabletHeadline else null) ?: hero.headline,
            style = if (large) Shadow.type.titleL else Shadow.type.titleM,
            color = Shadow.colors.ink1,
        )
        HeroSubline(
            parts = (if (large) hero.tabletSubline else null) ?: hero.subline,
            tone = hero.sublineTone,
            large = large,
        )
    }
}

@Composable
internal fun HomeNotes(
    state: HomeUiState,
    actions: HomeActions,
    modifier: Modifier = Modifier,
) {
    if (state.notes.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.notes.forEach { note -> HomeNoteView(note = note, actions = actions) }
    }
}

@Composable
internal fun UpdateBannerSlot(state: HomeUiState, actions: HomeActions, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = state.updateBanner != null,
        enter = fadeIn(shadowTween(ShadowMotion.Swap)) + expandVertically(shadowTween(ShadowMotion.SheetEnter)),
        exit = fadeOut(shadowTween(ShadowMotion.Swap)) + shrinkVertically(shadowTween(ShadowMotion.SheetEnter)),
    ) {
        state.updateBanner?.let { banner ->
            UpdateBannerView(
                banner = banner,
                onOpen = actions.onOpenUpdates,
                onDismiss = actions.onDismissUpdate,
                modifier = modifier,
            )
        }
    }
}

/** Sheets and dialogs shared by both layouts. */
@Composable
private fun HomeOverlays(
    state: HomeUiState,
    actions: HomeActions,
    sheet: HomeSheet?,
    onSheetChange: (HomeSheet?) -> Unit,
    poolTab: PoolTab,
) {
    val close = { onSheetChange(null) }
    when (sheet) {
        HomeSheet.RoutePool -> state.pool?.let { pool ->
            RoutePoolSheet(
                pool = pool,
                initialTab = poolTab,
                onDismiss = close,
                onOpenRouteLibrary = {
                    close()
                    actions.onOpenRouteLibrary()
                },
                onOpenActivity = {
                    close()
                    actions.onOpenActivity()
                },
            )
        }
        HomeSheet.ChooseServer -> ChooseServerSheet(
            options = state.serverOptions,
            onDismiss = close,
            onSelect = { id ->
                close()
                actions.onSelectServer(id)
            },
            onManageServers = {
                close()
                actions.onManageServers()
            },
        )
        HomeSheet.QuickSwitch -> QuickSwitchSheet(
            options = state.routeOptions,
            onDismiss = close,
            onSelect = { id ->
                close()
                actions.onSwitchRoute(id)
            },
            onOpenRouteLibrary = {
                close()
                actions.onOpenRouteLibrary()
            },
        )
        null -> Unit
    }
    state.pendingSwitch?.let { target ->
        ShadowDialog(
            onDismissRequest = actions.onCancelSwitch,
            title = "Switch to ${target.label}?",
            message = state.switchMessage,
            confirmLabel = "Stop & switch",
            onConfirm = actions.onConfirmSwitch,
            dismissLabel = "Stay",
        )
    }
    if (state.showNoAppsDialog) {
        NoSelectedAppsDialog(onDismiss = actions.onDismissNoApps, onPickApps = actions.onPickApps)
    }
}

/** Orb slot: 4 dp margin + 286 dp (the halo overflows the 232 dp orb by 34 dp). */
private val ORB_SLOT = 290.dp
private val ORB_SLOT_TOP = 4.dp
private val ORB_SLOT_MIN = 240.dp
private val BOTTOM_GAP_MIN = 12.dp

/**
 * HomeLight's `transform: scale(.94)`: with the update banner and a failed check the orb region is
 * 244 dp and the orb steps down; at 278 dp (banner, passed check) it keeps its size.
 */
private const val ORB_COMPACT_SCALE = 0.94f
private val ORB_COMPACT_BELOW = 262.dp

/** Medium width (600–840 dp, rail + one pane): the phone column stays this wide, centred. */
private val PHONE_MAX_WIDTH = 560.dp
