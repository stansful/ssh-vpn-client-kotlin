package com.stansful.sshvpnclient.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stansful.sshvpnclient.domain.model.AppThemeMode
import com.stansful.sshvpnclient.domain.model.VpnMode
import com.stansful.sshvpnclient.ui.designsystem.BannerTone
import com.stansful.sshvpnclient.ui.designsystem.CenteredToasts
import com.stansful.sshvpnclient.ui.designsystem.DISABLED_ALPHA
import com.stansful.sshvpnclient.ui.designsystem.IconTile
import com.stansful.sshvpnclient.ui.designsystem.InlineBanner
import com.stansful.sshvpnclient.ui.designsystem.ListGroup
import com.stansful.sshvpnclient.ui.designsystem.ListRow
import com.stansful.sshvpnclient.ui.designsystem.SectionHeader
import com.stansful.sshvpnclient.ui.designsystem.SegmentOption
import com.stansful.sshvpnclient.ui.designsystem.SegmentedSize
import com.stansful.sshvpnclient.ui.designsystem.ShadowButton
import com.stansful.sshvpnclient.ui.designsystem.ShadowButtonVariant
import com.stansful.sshvpnclient.ui.designsystem.ShadowFocusIndication
import com.stansful.sshvpnclient.ui.designsystem.ShadowIconButton
import com.stansful.sshvpnclient.ui.designsystem.ShadowIcons
import com.stansful.sshvpnclient.ui.designsystem.ShadowProgressBar
import com.stansful.sshvpnclient.ui.designsystem.ShadowSegmented
import com.stansful.sshvpnclient.ui.designsystem.StatusTone
import com.stansful.sshvpnclient.ui.designsystem.SwapText
import com.stansful.sshvpnclient.ui.designsystem.SwitchRow
import com.stansful.sshvpnclient.ui.designsystem.TopLevelBar
import com.stansful.sshvpnclient.ui.designsystem.fadeUpIn
import com.stansful.sshvpnclient.ui.designsystem.stateDotColor
import com.stansful.sshvpnclient.ui.shell.LocalWindowWidthClass
import com.stansful.sshvpnclient.ui.shell.WindowWidthClass
import com.stansful.sshvpnclient.ui.theme.JetBrainsMonoFamily
import com.stansful.sshvpnclient.ui.theme.Shadow
import com.stansful.sshvpnclient.ui.theme.ShadowColors
import com.stansful.sshvpnclient.ui.theme.ShadowDimens
import com.stansful.sshvpnclient.ui.theme.ShadowMotion
import com.stansful.sshvpnclient.ui.theme.ShadowShapes
import com.stansful.sshvpnclient.ui.theme.shadowTween
import kotlinx.coroutines.delay

/** Callbacks of [SettingsScreen]; defaults do nothing (previews, screenshots). */
@Immutable
internal class SettingsActions(
    val onOpenAppRouting: () -> Unit = {},
    val onShowConnectionActivityChange: (Boolean) -> Unit = {},
    val onShowTerminalChange: (Boolean) -> Unit = {},
    val onWarnBeforeRoutesChange: (Boolean) -> Unit = {},
    val onRefreshInBackgroundChange: (Boolean) -> Unit = {},
    val onRefreshNow: () -> Unit = {},
    val onCheckEngine: () -> Unit = {},
    val onDownloadEngine: () -> Unit = {},
    val onCancelEngineDownload: () -> Unit = {},
    val onOpenEngineRelease: () -> Unit = {},
    val onThemeModeChange: (AppThemeMode) -> Unit = {},
    val onOpenAppearance: () -> Unit = {},
    val onCheckForUpdates: () -> Unit = {},
    val onViewUpdate: () -> Unit = {},
    val onResumeUpdate: () -> Unit = {},
    val onInstallUpdate: () -> Unit = {},
    val onOpenSource: () -> Unit = {},
    val onCopySourceLink: () -> Unit = {},
)

/** Sections [SettingsScreen] can bring into view. */
internal enum class SettingsSection { Engine, Updates }

/**
 * Settings (top-level): connection, public routes, Xray engine, appearance, updates and about. Every
 * change applies right away. [scrollTo] scrolls the given section into view when it changes.
 */
@Composable
internal fun SettingsScreen(
    state: SettingsUiState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
    scrollTo: SettingsSection? = null,
    scrollState: ScrollState = rememberScrollState(),
) {
    val colors = Shadow.colors
    var engineTop by remember { mutableIntStateOf(-1) }
    var updatesTop by remember { mutableIntStateOf(-1) }
    val density = LocalDensity.current
    val topInset = WindowInsets.statusBars.getTop(density) + with(density) { SCROLL_MARGIN.roundToPx() }
    var scrolledTo by remember { mutableStateOf<SettingsSection?>(null) }
    LaunchedEffect(scrollTo, engineTop >= 0, updatesTop >= 0) {
        if (scrollTo == null || scrollTo == scrolledTo) return@LaunchedEffect
        val target = if (scrollTo == SettingsSection.Engine) engineTop else updatesTop
        if (target < 0) return@LaunchedEffect
        scrolledTo = scrollTo
        scrollState.animateScrollTo((target - topInset).coerceAtLeast(0))
    }
    // Settings.dc.html: centred, content-width toasts ("Link copied").
    CenteredToasts()
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = MAX_CONTENT_WIDTH)
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
            ) {
                TopLevelBar(
                    title = "Settings",
                    subtitle = "Changes apply right away. Nothing to save.",
                    pane = LocalWindowWidthClass.current == WindowWidthClass.Expanded,
                    modifier = Modifier.fadeUpIn(0),
                )
                val gutter = Modifier.padding(horizontal = ShadowDimens.Gutter)
                ConnectionSection(state, actions, gutter.fadeUpIn(1))
                PublicRoutesSection(state, actions, gutter.fadeUpIn(2))
                EngineSection(
                    engine = state.engine,
                    nowMs = state.nowMs,
                    actions = actions,
                    // Measured outside the entrance layer, so its rise doesn't shift the scroll target.
                    modifier = gutter
                        .onGloballyPositioned { engineTop = it.positionInParent().y.toInt() }
                        .fadeUpIn(3),
                )
                AppearanceSection(state, actions, gutter.fadeUpIn(4))
                UpdatesSection(
                    state = state,
                    actions = actions,
                    modifier = gutter
                        .onGloballyPositioned { updatesTop = it.positionInParent().y.toInt() }
                        .fadeUpIn(5),
                )
                AboutSection(actions, gutter.fadeUpIn(6))
                SettingsFooter(state.versionName, gutter.fadeUpIn(7))
            }
        }
        // The header scrolls away like the artboard's; this keeps the status bar over the bg.
        Box(
            Modifier
                .fillMaxWidth()
                .windowInsetsTopHeight(WindowInsets.statusBars)
                .background(colors.bg),
        )
    }
}

@Composable
private fun ConnectionSection(state: SettingsUiState, actions: SettingsActions, modifier: Modifier) {
    val selected = state.vpnMode == VpnMode.SELECTED_APPS
    Column(modifier) {
        SectionHeader("Connection")
        ListGroup {
            ListRow(
                title = "App routing",
                subtitle = if (selected) "Only selected apps" else "All apps",
                leadingIcon = ShadowIcons.Apps,
                value = if (selected) appsCount(state.selectedAppsCount) else null,
                showChevron = true,
                onClick = actions.onOpenAppRouting,
            )
            SwitchRow(
                title = "Show connection activity",
                checked = state.showConnectionActivity,
                onCheckedChange = actions.onShowConnectionActivityChange,
                icon = ShadowIcons.Activity,
                subtitle = if (state.showConnectionActivity) {
                    "Adds Activity to Home. The log is kept for every mode, up to 500 lines."
                } else {
                    "Activity stays off Home. The log is still kept, up to 500 lines."
                },
            )
            SwitchRow(
                title = "Terminal",
                checked = state.showTerminal,
                onCheckedChange = actions.onShowTerminalChange,
                icon = ShadowIcons.Server,
                badge = "Server mode",
                subtitle = if (state.showTerminal) {
                    "Shown on Home while connected. Turning it off ends the session."
                } else {
                    "Open a shell on the connected SSH server from Home."
                },
            )
        }
    }
}

@Composable
private fun PublicRoutesSection(state: SettingsUiState, actions: SettingsActions, modifier: Modifier) {
    val colors = Shadow.colors
    Column(modifier) {
        SectionHeader("Public routes", trailingText = "Routes mode", trailingIcon = ShadowIcons.Routes)
        ListGroup {
            SwitchRow(
                title = "Warn before opening public routes",
                checked = state.warnBeforeRoutes,
                onCheckedChange = actions.onWarnBeforeRoutesChange,
                icon = ShadowIcons.Shield,
                subtitle = if (state.warnBeforeRoutes) {
                    "Shows the third-party notice the first time you open Routes after launch."
                } else {
                    "The notice is off. Public routes still come from third parties."
                },
            )
            SwitchRow(
                title = "Refresh in background",
                checked = state.refreshInBackground,
                onCheckedChange = actions.onRefreshInBackgroundChange,
                icon = ShadowIcons.Clock,
                subtitle = "About every 12 h, on Wi-Fi or another unmetered network, when the battery isn’t low.",
            )
            val refresh = state.libraryRefresh
            val running = refresh == LibraryRefresh.Running
            val failed = refresh is LibraryRefresh.Done && refresh.failureMessage != null
            val (value, valueColor) = when {
                running -> "Refreshing…" to colors.amberText
                failed -> "Couldn’t refresh" to colors.coralText
                refresh is LibraryRefresh.Done -> "updated just now" to colors.mintText
                refresh is LibraryRefresh.Idle && refresh.lastUpdatedAtMs != null ->
                    "updated ${relativeTime(refresh.lastUpdatedAtMs, state.nowMs)}" to colors.ink3
                else -> "never updated" to colors.ink3
            }
            val iconTint = when {
                running -> colors.amberText
                failed -> colors.coralText
                refresh is LibraryRefresh.Done -> colors.mintText
                else -> colors.ink2
            }
            SettingsActionRow(
                title = "Refresh now",
                subtitle = "Library · ${routesCount(state.libraryRouteCount)}",
                onClick = actions.onRefreshNow,
                leading = { RowTile { SpinningIcon(ShadowIcons.Refresh, spinning = running, tint = iconTint) } },
                trailing = {
                    SwapText(
                        text = value,
                        style = Shadow.type.bodyS.copy(fontWeight = FontWeight.Medium),
                        color = valueColor,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                },
            )
        }
        val failure = (state.libraryRefresh as? LibraryRefresh.Done)?.failureMessage
        if (failure != null) {
            SwapText(
                text = failure,
                style = Shadow.type.bodyS,
                color = colors.coralText,
                maxLines = 3,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp),
            )
        }
    }
}

@Composable
private fun EngineSection(engine: EngineUi, nowMs: Long, actions: SettingsActions, modifier: Modifier) {
    val colors = Shadow.colors
    Column(modifier) {
        SectionHeader("Xray engine")
        ListGroup {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 68.dp)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconTile(icon = ShadowIcons.Engine, size = 40.dp, iconSize = 20.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = if (engine.installed) "Installed" else "Not installed",
                            style = Shadow.type.titleS,
                            color = colors.ink1,
                        )
                        Text(text = engine.runtimeAbi, style = Shadow.type.monoS, color = colors.ink3)
                    }
                    RingDot(
                        color = if (engine.installed) colors.mint else colors.coral,
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
                AnimatedVisibility(visible = engine.inUse, enter = revealIn(), exit = revealOut()) {
                    InlineBanner(
                        message = "Auto or Routes is using the engine. Disconnect first to update it.",
                        tone = BannerTone.Warning,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                    )
                }
            }
            val checking = engine.isChecking
            SettingsActionRow(
                title = when {
                    checking -> "Checking…"
                    engine.release != null -> "Check again"
                    !engine.installed -> "Find the engine for this phone"
                    else -> "Check for engine updates"
                },
                titleColor = if (checking) colors.amberText else colors.ink1,
                onClick = actions.onCheckEngine,
                enabled = !engine.isDownloading,
                busy = checking,
                leading = {
                    RowTile {
                        SpinningIcon(
                            icon = ShadowIcons.Refresh,
                            spinning = checking,
                            tint = if (checking) colors.amberText else colors.ink2,
                        )
                    }
                },
                trailing = {
                    val checkedAt = engine.checkedAtMs
                    if (!checking && engine.release != null && checkedAt != null) {
                        Text(relativeTime(checkedAt, nowMs), style = Shadow.type.bodyS, color = colors.ink3)
                    }
                },
            )
            AnimatedVisibility(visible = engine.release != null, enter = revealIn(), exit = revealOut()) {
                engine.release?.let { EngineRelease(engine, actions) }
            }
        }
        SwapText(
            text = engineHelp(engine),
            style = Shadow.type.bodyS,
            color = when (engine.outcome) {
                is EngineOutcome.Error -> colors.coralText
                EngineOutcome.Installed, EngineOutcome.NeedsRestart -> colors.mintText
                else -> colors.ink3
            },
            maxLines = 4,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp),
        )
    }
}

@Composable
private fun EngineRelease(engine: EngineUi, actions: SettingsActions) {
    val colors = Shadow.colors
    val release = engine.release ?: return
    val asset = engine.asset
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(
                icon = ShadowIcons.Download,
                size = 40.dp,
                iconSize = 20.dp,
                tint = if (asset == null) colors.coralText else colors.amberText,
                container = if (asset == null) colors.coralTint else colors.amberTint,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = when {
                        asset == null -> "No ${engine.runtimeAbi} build in ${release.versionName}"
                        engine.isDownloading -> "Downloading ${release.versionName}…"
                        engine.installed -> "${release.versionName} available"
                        else -> "${release.versionName} for ${engine.runtimeAbi}"
                    },
                    style = Shadow.type.rowTitle.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.ink1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = when {
                        asset == null && engine.installed -> "The installed engine keeps working"
                        asset == null -> "Nothing to download for this phone yet"
                        asset.universal -> "Universal AAR · ${formatFileSize(asset.sizeBytes)}"
                        else -> "${asset.name} · ${formatFileSize(asset.sizeBytes)}"
                    },
                    style = Shadow.type.monoS,
                    color = colors.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (engine.isDownloading) {
            ShadowProgressBar(
                progress = null,
                modifier = Modifier
                    .padding(top = 14.dp)
                    .fillMaxWidth()
                    .semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate },
            )
        }
        Row(
            modifier = Modifier.padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                engine.isDownloading -> ShadowButton(
                    text = "Cancel",
                    onClick = actions.onCancelEngineDownload,
                    modifier = Modifier.weight(1f),
                    variant = ShadowButtonVariant.Secondary,
                    size = CardButtonSize,
                    icon = ShadowIcons.Close,
                )
                asset != null -> ShadowButton(
                    text = "Download",
                    onClick = actions.onDownloadEngine,
                    modifier = Modifier.weight(1f),
                    size = CardButtonSize,
                    icon = ShadowIcons.Download,
                    enabled = !engine.inUse && !engine.isChecking,
                )
            }
            ShadowButton(
                text = "Release page",
                onClick = actions.onOpenEngineRelease,
                modifier = Modifier.weight(1f),
                variant = ShadowButtonVariant.Secondary,
                size = CardButtonSize,
                trailingIcon = ShadowIcons.External,
            )
        }
    }
}

private fun engineHelp(engine: EngineUi): String = when (val outcome = engine.outcome) {
    is EngineOutcome.Error -> outcome.message
    EngineOutcome.NeedsRestart -> "Installed — restart shadow to use it."
    EngineOutcome.Installed -> "Installed · ready to use. Auto and Routes can connect."
    EngineOutcome.Cancelled -> "Download cancelled. Nothing changed."
    null -> when {
        engine.isDownloading -> "Keeps going if you leave Settings. Closing shadow from Recents stops it."
        !engine.installed -> "Auto and Routes can’t connect until the engine is installed."
        else -> "Used by Auto and Routes. Restart shadow after an update."
    }
}

@Composable
private fun AppearanceSection(state: SettingsUiState, actions: SettingsActions, modifier: Modifier) {
    val colors = Shadow.colors
    val custom = state.themeMode == AppThemeMode.CUSTOM
    Column(modifier) {
        SectionHeader("Appearance")
        ListGroup {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 14.dp)) {
                Text("Theme", style = Shadow.type.rowTitle, color = colors.ink1)
                Spacer(Modifier.height(12.dp))
                ShadowSegmented(
                    options = ThemeOptions,
                    selectedIndex = AppThemeMode.entries.indexOf(state.themeMode),
                    onSelect = { actions.onThemeModeChange(AppThemeMode.entries[it]) },
                    size = SegmentedSize.Compact,
                    role = Role.RadioButton,
                )
                SwapText(
                    text = themeHint(state.themeMode),
                    style = Shadow.type.bodyS,
                    color = colors.ink3,
                    modifier = Modifier.padding(start = 2.dp, end = 2.dp, top = 10.dp),
                )
            }
            SettingsActionRow(
                title = "Custom palette",
                subtitle = "${state.customPalette.paletteName()} · ${if (custom) "in use" else "saved"}",
                onClick = actions.onOpenAppearance,
                leading = {
                    val tint by animateColorAsState(
                        targetValue = if (custom) colors.amberText else colors.ink2,
                        animationSpec = shadowTween(ShadowMotion.Small, ShadowMotion.Ease),
                        label = "palette-tint",
                    )
                    RowTile { Icon(ShadowIcons.Palette, null, tint = tint, modifier = Modifier.size(20.dp)) }
                },
                trailing = {
                    SwatchStack(
                        colors = listOf(
                            state.customPalette.primary.toColor(),
                            state.customPalette.secondary.toColor(),
                            state.customPalette.background.toColor(),
                        ),
                    )
                },
                showChevron = true,
            )
        }
    }
}

private val ThemeOptions = listOf(
    SegmentOption("System"),
    SegmentOption("Light"),
    SegmentOption("Dark"),
    SegmentOption("Custom"),
)

private fun themeHint(mode: AppThemeMode): String = when (mode) {
    AppThemeMode.SYSTEM -> "Follows your phone’s light or dark mode."
    AppThemeMode.LIGHT -> "Always light, day and night."
    AppThemeMode.DARK -> "Always dark."
    AppThemeMode.CUSTOM -> "Your own colors. Edit them in Custom palette."
}

@Composable
private fun UpdatesSection(state: SettingsUiState, actions: SettingsActions, modifier: Modifier) {
    val colors = Shadow.colors
    val update = state.update
    val phase = update.phase
    val status = updateStatus(update, state.nowMs)
    Column(modifier) {
        SectionHeader("Updates")
        ListGroup {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (phase != UpdatePhase.None) {
                                // Reopens the update sheet (e.g. after "Hide" during a download).
                                Modifier.clickable(
                                    interactionSource = null,
                                    indication = ShadowFocusIndication,
                                    role = Role.Button,
                                    onClickLabel = "Open update",
                                    onClick = actions.onViewUpdate,
                                )
                            } else {
                                Modifier
                            },
                        )
                        .heightIn(min = 68.dp)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconTile(
                        icon = status.icon,
                        size = 40.dp,
                        iconSize = 20.dp,
                        tint = colors.toneInk(status.tone),
                        container = colors.toneContainer(status.tone),
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = buildAnnotatedString {
                                append("shadow ")
                                withStyle(
                                    SpanStyle(
                                        fontFamily = JetBrainsMonoFamily,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = colors.ink2,
                                    ),
                                ) { append(state.versionName) }
                            },
                            style = Shadow.type.titleS,
                            color = colors.ink1,
                            maxLines = 1,
                        )
                        SwapText(
                            text = status.text,
                            style = Shadow.type.bodyS,
                            color = colors.toneInk(status.tone).takeIf { status.tone != StatusTone.Neutral }
                                ?: colors.ink3,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }
                UpdateInlineBlock(update, actions)
            }
            SettingsActionRow(
                title = when {
                    update.isChecking -> "Checking…"
                    phase is UpdatePhase.Available && update.checkedJustNow -> "Check again"
                    update.errorMessage != null && phase == UpdatePhase.None -> "Try again"
                    else -> "Check for updates"
                },
                titleColor = if (update.isChecking) colors.amberText else colors.ink1,
                onClick = actions.onCheckForUpdates,
                busy = update.isChecking,
                leading = {
                    RowTile {
                        SpinningIcon(
                            icon = ShadowIcons.Refresh,
                            spinning = update.isChecking,
                            tint = if (update.isChecking) colors.amberText else colors.ink2,
                        )
                    }
                },
                trailing = {
                    if (!update.isChecking && phase is UpdatePhase.Available && update.checkedJustNow) {
                        Text("just now", style = Shadow.type.bodyS, color = colors.ink3)
                    }
                },
            )
            AnimatedVisibility(
                visible = phase is UpdatePhase.Available,
                enter = revealIn(),
                exit = revealOut(),
            ) {
                (phase as? UpdatePhase.Available)?.let { available ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconTile(
                            icon = ShadowIcons.Upload,
                            size = 40.dp,
                            iconSize = 20.dp,
                            tint = colors.amberText,
                            container = colors.amberTint,
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "${available.update.versionName} is available",
                                style = Shadow.type.rowTitle.copy(fontWeight = FontWeight.SemiBold),
                                color = colors.ink1,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (available.update.apkSizeBytes > 0L) {
                                Text(
                                    text = formatFileSize(available.update.apkSizeBytes),
                                    style = Shadow.type.monoS,
                                    color = colors.ink3,
                                )
                            }
                        }
                        ShadowButton(
                            text = "View",
                            onClick = actions.onViewUpdate,
                            size = CardButtonSize.copy(horizontalPadding = 18.dp),
                        )
                    }
                }
            }
        }
        val checkError = update.errorMessage.takeIf { phase == UpdatePhase.None }
        SwapText(
            text = checkError ?: "We check GitHub once a day.",
            style = Shadow.type.bodyS,
            color = if (checkError != null) colors.coralText else colors.ink3,
            maxLines = 4,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp),
        )
    }
}

/** Downloading / ready / stopped / failed details under the version row (indented to the text). */
@Composable
private fun UpdateInlineBlock(update: UpdatesUi, actions: SettingsActions) {
    val colors = Shadow.colors
    val phase = update.phase
    val blockModifier = Modifier.padding(start = 68.dp, end = 16.dp, bottom = 16.dp)
    when (phase) {
        is UpdatePhase.Downloading -> Column(blockModifier) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = if (phase.verifying) "Checking the download" else "Downloading ${phase.versionName}",
                    style = Shadow.type.label,
                    color = colors.ink1,
                    modifier = Modifier.weight(1f),
                )
                phase.percent?.let {
                    Text("$it%", style = Shadow.type.monoMedium, color = colors.amberText)
                }
            }
            ShadowProgressBar(
                progress = phase.fraction,
                modifier = Modifier
                    .padding(vertical = 8.dp)
                    .fillMaxWidth()
                    .semantics {
                        progressBarRangeInfo = ProgressBarRangeInfo(phase.fraction ?: 0f, 0f..1f)
                    },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = phase.totalBytes?.let {
                        "${formatMebibytes(phase.downloadedBytes)} of ${formatFileSize(it)}"
                    } ?: formatFileSize(phase.downloadedBytes),
                    style = Shadow.type.monoS,
                    color = colors.ink3,
                )
                Text(
                    text = "Runs in background",
                    style = Shadow.type.caption,
                    color = colors.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        is UpdatePhase.Ready -> Column(blockModifier) {
            Text(
                text = buildAnnotatedString {
                    append("Downloaded and verified")
                    update.sizeBytes?.takeIf { it > 0L }?.let { size ->
                        append(" · ")
                        withStyle(SpanStyle(fontFamily = JetBrainsMonoFamily, fontSize = 12.sp)) {
                            append(formatFileSize(size))
                        }
                    }
                },
                style = Shadow.type.bodyS,
                color = colors.ink2,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            ShadowButton(
                text = "Install ${phase.versionName}",
                onClick = actions.onInstallUpdate,
                modifier = Modifier.fillMaxWidth(),
                size = CardButtonSize,
                icon = ShadowIcons.Download,
            )
            update.errorMessage?.let {
                InlineErrorText(text = it, modifier = Modifier.padding(top = 8.dp))
            }
        }
        is UpdatePhase.Stopped -> Column(blockModifier) {
            val saved = phase.savedBytes
            val total = phase.totalBytes
            StoppedBanner(
                message = if (saved != null && total != null) {
                    buildAnnotatedString {
                        append("The download stopped at ")
                        withStyle(SpanStyle(fontFamily = JetBrainsMonoFamily, fontSize = 12.sp)) {
                            append("${formatMebibytes(saved)} of ${formatFileSize(total)}")
                        }
                        append(". Resume picks up from there.")
                    }
                } else {
                    AnnotatedString(phase.message)
                },
            )
            ShadowButton(
                text = "Resume download",
                onClick = actions.onResumeUpdate,
                modifier = Modifier
                    .padding(top = 10.dp)
                    .fillMaxWidth(),
                size = CardButtonSize,
                icon = ShadowIcons.Refresh,
            )
        }
        is UpdatePhase.Failed -> Column(blockModifier) {
            InlineBanner(
                message = if (phase.rejected) {
                    "The download failed the safety check and was deleted. Nothing was installed."
                } else {
                    phase.message
                },
                tone = BannerTone.Error,
            )
        }
        UpdatePhase.None, is UpdatePhase.Available -> Unit
    }
}

private data class UpdateStatus(val text: String, val tone: StatusTone, val icon: ImageVector)

private fun updateStatus(update: UpdatesUi, nowMs: Long): UpdateStatus {
    val checked = update.lastCheckedAtMs?.let { relativeDay(it, nowMs) }
    return when (val phase = update.phase) {
        is UpdatePhase.Downloading -> UpdateStatus(
            "Updating to ${phase.versionName}",
            StatusTone.Progress,
            ShadowIcons.Download,
        )
        is UpdatePhase.Ready -> UpdateStatus(
            "${phase.versionName} is ready to install",
            StatusTone.Progress,
            ShadowIcons.Download,
        )
        is UpdatePhase.Stopped -> UpdateStatus(
            phase.versionName?.let { "Update to $it paused" } ?: "Update paused",
            StatusTone.Error,
            ShadowIcons.Warning,
        )
        is UpdatePhase.Failed -> UpdateStatus("Update download failed", StatusTone.Error, ShadowIcons.Warning)
        is UpdatePhase.Available -> UpdateStatus(
            if (update.checkedJustNow || checked == null) {
                "Update available · checked just now"
            } else {
                "Update available · checked $checked"
            },
            StatusTone.Progress,
            ShadowIcons.Download,
        )
        UpdatePhase.None -> when {
            update.isChecking -> UpdateStatus("Checking GitHub…", StatusTone.Neutral, ShadowIcons.CheckCircle)
            update.errorMessage != null -> UpdateStatus(
                "Couldn’t reach GitHub · try again",
                StatusTone.Error,
                ShadowIcons.Warning,
            )
            update.upToDate || checked != null -> UpdateStatus(
                "Up to date · checked ${if (update.upToDate) "just now" else checked}",
                StatusTone.Success,
                ShadowIcons.CheckCircle,
            )
            else -> UpdateStatus("Not checked yet", StatusTone.Neutral, ShadowIcons.CheckCircle)
        }
    }
}

@Composable
private fun AboutSection(actions: SettingsActions, modifier: Modifier) {
    val colors = Shadow.colors
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(COPIED_MILLIS)
            copied = false
        }
    }
    Column(modifier) {
        SectionHeader("About")
        ListGroup {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SettingsActionRow(
                    title = "Source code",
                    subtitle = GITHUB_REPOSITORY_LABEL,
                    subtitleMono = true,
                    onClick = actions.onOpenSource,
                    modifier = Modifier.weight(1f),
                    endPadding = 12.dp,
                    leading = { IconTile(icon = ShadowIcons.Code, size = 40.dp, iconSize = 20.dp) },
                    trailing = {
                        Icon(ShadowIcons.External, null, tint = colors.ink3, modifier = Modifier.size(16.dp))
                    },
                )
                Box(
                    Modifier
                        .width(1.dp)
                        .height(28.dp)
                        .background(colors.line2),
                )
                val copyTint by animateColorAsState(
                    targetValue = if (copied) colors.mintText else colors.ink2,
                    animationSpec = shadowTween(ShadowMotion.Small, ShadowMotion.Ease),
                    label = "copy-tint",
                )
                ShadowIconButton(
                    icon = if (copied) ShadowIcons.Check else ShadowIcons.Copy,
                    contentDescription = "Copy link",
                    onClick = {
                        actions.onCopySourceLink()
                        copied = true
                    },
                    tint = copyTint,
                    iconSize = 20.dp,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
        Text(
            text = "The source code is public on GitHub. No account needed.",
            style = Shadow.type.bodyS,
            color = colors.ink3,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp),
        )
    }
}

@Composable
private fun SettingsFooter(versionName: String, modifier: Modifier) {
    val colors = Shadow.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(colors.stateDotColor(StatusTone.Neutral), CircleShape),
            )
            Text(
                text = "shadow",
                style = Shadow.type.wordmark.copy(fontSize = 17.sp, lineHeight = 22.sp),
                color = colors.ink2,
            )
        }
        Text("$versionName · Android 8.0+", style = Shadow.type.monoS, color = colors.ink3)
    }
}

/** 10 dp state dot with a 4 dp ring (14 %) and a 12 dp glow (45 %) of its color (the engine status). */
@Composable
private fun RingDot(color: Color, modifier: Modifier = Modifier) {
    val dot by animateColorAsState(
        targetValue = color,
        animationSpec = shadowTween(ShadowMotion.ColorFade, ShadowMotion.Ease),
        label = "ring-dot",
    )
    Box(
        modifier = modifier
            .size(10.dp)
            .dropShadow(CircleShape) {
                radius = DOT_GLOW.toPx()
                this.color = dot.copy(alpha = DOT_GLOW_ALPHA)
            }
            .drawBehind {
                drawCircle(dot.copy(alpha = DOT_RING_ALPHA), radius = size.minDimension / 2 + DOT_RING.toPx())
            }
            .clip(CircleShape)
            .background(dot),
    )
}

/** Coral "download stopped" notice (network icon, 13/18 ink-1 text with the saved size in mono). */
@Composable
private fun StoppedBanner(message: AnnotatedString) {
    val colors = Shadow.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.coralTint, ShadowShapes.Banner)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Assertive }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(ShadowIcons.Network, null, tint = colors.coralText, modifier = Modifier.size(18.dp))
        Text(text = message, style = Shadow.type.bodyS, color = colors.ink1)
    }
}

/**
 * A pressable settings row (min 64, padding 12/16, gap 12) whose title swaps when it changes; pressed
 * rows tint to surface-3 like `ListRow`.
 */
@Composable
private fun SettingsActionRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleMono: Boolean = false,
    titleColor: Color = Color.Unspecified,
    enabled: Boolean = true,
    busy: Boolean = false,
    showChevron: Boolean = false,
    endPadding: Dp = 16.dp,
    leading: @Composable () -> Unit,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val colors = Shadow.colors
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val background by animateColorAsState(
        targetValue = if (pressed) colors.surface3 else colors.surface3.copy(alpha = 0f),
        animationSpec = shadowTween(ROW_PRESS_MS, ShadowMotion.Ease),
        label = "settings-row-press",
    )
    val ink by animateColorAsState(
        targetValue = if (titleColor != Color.Unspecified) titleColor else colors.ink1,
        animationSpec = shadowTween(ShadowMotion.Small, ShadowMotion.Ease),
        label = "settings-row-ink",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clickable(
                interactionSource = interactionSource,
                indication = ShadowFocusIndication,
                enabled = enabled && !busy,
                role = Role.Button,
                onClick = onClick,
            )
            .background(background)
            .heightIn(min = 64.dp)
            .padding(start = 16.dp, end = endPadding, top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SwapText(text = title, style = Shadow.type.rowTitle, color = ink)
            if (subtitle != null) {
                SwapText(
                    text = subtitle,
                    style = if (subtitleMono) Shadow.type.monoS else Shadow.type.bodyS,
                    color = colors.ink3,
                )
            }
        }
        trailing()
        if (showChevron) {
            Icon(ShadowIcons.ChevronRight, null, tint = colors.ink3, modifier = Modifier.size(18.dp))
        }
    }
}

/** 40 dp surface-2 row tile holding a custom icon. */
@Composable
private fun RowTile(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(Shadow.colors.surface2, ShadowShapes.Tile),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun revealIn(): EnterTransition =
    expandVertically(shadowTween(ShadowMotion.Surface), expandFrom = Alignment.Top) +
        fadeIn(shadowTween(ShadowMotion.Small))

@Composable
private fun revealOut(): ExitTransition =
    shrinkVertically(shadowTween(ShadowMotion.Surface), shrinkTowards = Alignment.Top) +
        fadeOut(shadowTween(ShadowMotion.Small))

private fun ShadowColors.toneInk(tone: StatusTone): Color = when (tone) {
    StatusTone.Neutral -> ink2
    StatusTone.Progress -> amberText
    StatusTone.Success -> mintText
    StatusTone.Error -> coralText
    StatusTone.Info -> skyText
}

private fun ShadowColors.toneContainer(tone: StatusTone): Color = when (tone) {
    StatusTone.Neutral -> surface2
    StatusTone.Progress -> amberTint
    StatusTone.Success -> mintTint
    StatusTone.Error -> coralTint
    StatusTone.Info -> skyTint
}

internal fun appsCount(count: Int): String = if (count == 1) "1 app" else "$count apps"

private fun routesCount(count: Int): String = if (count == 1) "1 route" else "$count routes"

private val MAX_CONTENT_WIDTH = ShadowDimens.PaneMaxWidth
private val SCROLL_MARGIN = 8.dp
private const val ROW_PRESS_MS = 160
private const val COPIED_MILLIS = 1_800L
private val DOT_RING = 4.dp
private val DOT_GLOW = 12.dp
private const val DOT_RING_ALPHA = 0.14f
private const val DOT_GLOW_ALPHA = 0.45f
