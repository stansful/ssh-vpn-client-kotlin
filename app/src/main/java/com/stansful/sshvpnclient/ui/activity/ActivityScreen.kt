package com.stansful.sshvpnclient.ui.activity

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stansful.sshvpnclient.ui.designsystem.IconTile
import com.stansful.sshvpnclient.ui.designsystem.LocalToaster
import com.stansful.sshvpnclient.ui.designsystem.PrimaryButton
import com.stansful.sshvpnclient.ui.designsystem.ShadowButtonLabel
import com.stansful.sshvpnclient.ui.designsystem.ShadowButtonSize
import com.stansful.sshvpnclient.ui.designsystem.ShadowButtonVariant
import com.stansful.sshvpnclient.ui.designsystem.ShadowDialog
import com.stansful.sshvpnclient.ui.designsystem.ShadowFocusIndication
import com.stansful.sshvpnclient.ui.designsystem.ShadowIconButton
import com.stansful.sshvpnclient.ui.designsystem.ShadowIcons
import com.stansful.sshvpnclient.ui.designsystem.StatusDot
import com.stansful.sshvpnclient.ui.designsystem.StatusPill
import com.stansful.sshvpnclient.ui.designsystem.StatusTone
import com.stansful.sshvpnclient.ui.designsystem.SubScreenBar
import com.stansful.sshvpnclient.ui.designsystem.SwapText
import com.stansful.sshvpnclient.ui.designsystem.fadeUpIn
import com.stansful.sshvpnclient.ui.designsystem.shadowClickable
import com.stansful.sshvpnclient.ui.designsystem.toneText
import com.stansful.sshvpnclient.ui.theme.Shadow
import com.stansful.sshvpnclient.ui.theme.ShadowColors
import com.stansful.sshvpnclient.ui.theme.ShadowDimens
import com.stansful.sshvpnclient.ui.theme.ShadowMotion
import com.stansful.sshvpnclient.ui.theme.ShadowShapes
import com.stansful.sshvpnclient.ui.theme.mixColors
import com.stansful.sshvpnclient.ui.theme.shadowTween
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Mode that owns (or last owned) the VPN session. */
internal enum class SessionMode(val label: String) {
    Auto("Auto"),
    Server("Server"),
    Routes("Routes"),
}

/** The "This session" card. [title] is the server / route name, or "Last session" when idle. */
@Immutable
internal data class ActivitySession(
    val mode: SessionMode?,
    val title: String,
    val subtitle: String,
    val statusLabel: String,
    val statusTone: StatusTone,
)

@Immutable
internal data class ActivityScreenState(
    val lines: List<ActivityLine>,
    val session: ActivitySession?,
    /** A session is running, so new lines keep arriving ("Live"). */
    val live: Boolean,
    /** The mode chip filters SSH lines (Server) rather than route lines (Auto / Routes). */
    val sshMode: Boolean,
    /** Name of the SSH server the highlights talk about. */
    val serverName: String?,
    val canEditServer: Boolean,
    /** The user cleared the log from this screen and nothing new has arrived yet. */
    val cleared: Boolean = false,
)

/** Stateless Activity screen (Activity.dc.html). */
@Composable
internal fun ActivityScreen(
    state: ActivityScreenState,
    onBack: () -> Unit,
    onCopyAll: () -> Unit,
    onClear: () -> Unit,
    onCopyLine: (ActivityLine) -> Unit,
    onCopyFingerprint: (String) -> Unit,
    onEditServer: () -> Unit,
    onManageServers: () -> Unit,
    onGoHome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lines = state.lines
    val hasLines = lines.isNotEmpty()
    var filter by rememberSaveable { mutableStateOf(ActivityFilter.All) }
    var selectedKey by remember { mutableStateOf<Long?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val follower = rememberLogFollower(listState, newestKey = lines.lastOrNull()?.key ?: -1L)
    val raised by remember(listState) { derivedStateOf { listState.canScrollBackward } }
    val shown = remember(lines, filter, state.sshMode) { lines.filter { it.matches(filter, state.sshMode) } }

    Box(modifier = modifier.fillMaxSize().background(Shadow.colors.bg)) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            val contentModifier = Modifier
                .weight(1f)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .widthIn(max = CONTENT_MAX_WIDTH)
            ActivityTopBar(
                lineCount = lines.size,
                showDivider = hasLines && raised,
                onBack = onBack,
                onAskClear = { selectedKey = null },
                onClear = {
                    follower.follow = true
                    onClear()
                },
                onCopyAll = onCopyAll,
            )
            if (hasLines) {
                ActivityLogList(
                    state = state,
                    shown = shown,
                    filter = filter,
                    onFilter = {
                        filter = it
                        selectedKey = null
                    },
                    listState = listState,
                    selectedKey = selectedKey,
                    onSelect = { key -> selectedKey = if (selectedKey == key) null else key },
                    onCopyLine = { line ->
                        selectedKey = null
                        onCopyLine(line)
                    },
                    onCopyFingerprint = onCopyFingerprint,
                    onEditServer = onEditServer,
                    onManageServers = onManageServers,
                    modifier = contentModifier,
                )
            } else {
                EmptyActivity(state = state, onGoHome = onGoHome, modifier = contentModifier)
            }
        }
        JumpToLatest(
            visible = hasLines && !follower.atEnd,
            fresh = shown.count { it.key > follower.seenKey },
            onClick = {
                selectedKey = null
                scope.launch { follower.jumpToLatest(newestKey = lines.lastOrNull()?.key ?: -1L) }
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/**
 * "Jump to latest" bookkeeping: [atEnd] (within the artboard's 40 px of the end), the newest line
 * seen there ([seenKey], for the "N new" badge) and [follow] — after a jump the list keeps following
 * new lines until the user drags it.
 */
@Stable
private class LogFollower(
    private val listState: LazyListState,
    private val reducedMotion: Boolean,
    endSlop: Float,
    initialSeenKey: Long,
) {
    var follow by mutableStateOf(false)
    var seenKey by mutableLongStateOf(initialSeenKey)
    val atEnd: Boolean by derivedStateOf { listState.layoutInfo.isAtEnd(endSlop) }

    suspend fun jumpToLatest(newestKey: Long) {
        follow = true
        seenKey = newestKey
        listState.scrollToEnd(reducedMotion)
    }
}

@Composable
private fun rememberLogFollower(listState: LazyListState, newestKey: Long): LogFollower {
    val reducedMotion = Shadow.reducedMotion
    val endSlop = with(LocalDensity.current) { END_SLOP.toPx() }
    val follower = remember(listState, reducedMotion, endSlop) {
        LogFollower(listState, reducedMotion, endSlop, newestKey)
    }
    val atEnd = follower.atEnd
    LaunchedEffect(atEnd, newestKey) {
        if (atEnd) follower.seenKey = newestKey
    }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) follower.follow = false
        }
    }
    LaunchedEffect(newestKey) {
        if (follower.follow && newestKey >= 0) listState.scrollToEnd(reducedMotion)
    }
    return follower
}

/** Within [slop] px of the end of the list (the artboard's 40 px), new lines count as seen. */
private fun LazyListLayoutInfo.isAtEnd(slop: Float): Boolean {
    val last = visibleItemsInfo.lastOrNull() ?: return true
    if (last.index < totalItemsCount - 1) return false
    return last.offset + last.size + afterContentPadding - viewportEndOffset <= slop
}

/** Scrolls to the last item once the list has measured the newest items. */
private suspend fun LazyListState.scrollToEnd(reducedMotion: Boolean) {
    withFrameNanos { }
    val last = layoutInfo.totalItemsCount - 1
    if (last < 0) return
    if (reducedMotion) scrollToItem(last) else animateScrollToItem(last)
}

/** Back, title, and the Clear (with its confirmation) and Copy all actions; both disabled without lines. */
@Composable
private fun ActivityTopBar(
    lineCount: Int,
    showDivider: Boolean,
    onBack: () -> Unit,
    onAskClear: () -> Unit,
    onClear: () -> Unit,
    onCopyAll: () -> Unit,
) {
    val enabled = lineCount > 0
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    // Activity.dc.html: 8 + 44 + 2 gap + 4 = title at 58 dp.
    SubScreenBar(title = "Activity", onBack = onBack, showDivider = showDivider, titleStartPadding = 2.dp) {
        ShadowIconButton(
            icon = ShadowIcons.Trash,
            contentDescription = "Clear activity",
            onClick = {
                onAskClear()
                confirmClear = true
            },
            enabled = enabled,
            iconSize = 20.dp,
        )
        Spacer(Modifier.width(2.dp))
        ShadowIconButton(
            icon = ShadowIcons.Copy,
            contentDescription = "Copy all",
            onClick = onCopyAll,
            enabled = enabled,
            iconSize = 20.dp,
        )
    }
    if (confirmClear) {
        ClearActivityDialog(
            count = lineCount,
            onDismiss = { confirmClear = false },
            onConfirm = {
                confirmClear = false
                onClear()
            },
        )
    }
}

@Composable
private fun ActivityLogList(
    state: ActivityScreenState,
    shown: List<ActivityLine>,
    filter: ActivityFilter,
    onFilter: (ActivityFilter) -> Unit,
    listState: LazyListState,
    selectedKey: Long?,
    onSelect: (Long) -> Unit,
    onCopyLine: (ActivityLine) -> Unit,
    onCopyFingerprint: (String) -> Unit,
    onEditServer: () -> Unit,
    onManageServers: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Shadow.colors
    val highlights = remember(state.lines) { activityHighlights(state.lines) }
    var intro by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(INTRO_MS)
        intro = false
    }
    val showHighlights = filter == ActivityFilter.All && !highlights.isEmpty
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .drawBehind { drawLogCard(listState.layoutInfo, colors) },
        contentPadding = PaddingValues(
            top = 4.dp,
            bottom = LIST_BOTTOM_ROOM + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
        ),
    ) {
        state.session?.let { session ->
            item(key = "session") {
                SessionCard(
                    session = session,
                    lineCount = state.lines.size,
                    modifier = Modifier
                        .padding(horizontal = GUTTER)
                        .fadeUpIn(0),
                )
            }
        }
        stickyHeader(key = "filters") {
            FilterRow(
                filter = filter,
                sshMode = state.sshMode,
                onFilter = onFilter,
                modifier = Modifier.fadeUpIn(2),
            )
        }
        if (showHighlights) {
            highlightItems(
                highlights = highlights,
                serverName = state.serverName,
                canEditServer = state.canEditServer,
                onCopyFingerprint = onCopyFingerprint,
                onEditServer = onEditServer,
                onManageServers = onManageServers,
            )
        }
        logItems(
            shown = shown,
            filter = filter,
            sshMode = state.sshMode,
            live = state.live,
            topGap = if (showHighlights) 24.dp else 12.dp,
            selectedKey = selectedKey,
            intro = intro,
            onSelect = onSelect,
            onCopyLine = onCopyLine,
        )
        item(key = "privacy") { PrivacyNote(Modifier.padding(horizontal = GUTTER)) }
    }
}

/**
 * Draws the log card (surface-1, 1 dp line border, radius 18) behind the lazy items it spans, as one
 * shape: from the top cap to the bottom cap, extended past the viewport while either is scrolled away.
 */
private fun DrawScope.drawLogCard(info: LazyListLayoutInfo, colors: ShadowColors) {
    val items = info.visibleItemsInfo
    if (items.none { it.key.isLogCardKey() }) return
    val shift = -info.viewportStartOffset.toFloat()
    val radius = CARD_RADIUS.toPx()
    val top = items.firstOrNull { it.key == LOG_TOP_KEY }?.let { it.offset + shift } ?: -radius * 2
    val bottom = items.firstOrNull { it.key == LOG_BOTTOM_KEY }?.let { it.offset + it.size + shift }
        ?: (size.height + radius * 2)
    val left = GUTTER.toPx()
    val width = size.width - left * 2
    val stroke = 1.dp.toPx()
    // The parts scrolled away must not paint over the top bar above the list.
    clipRect {
        drawRoundRect(
            color = colors.surface1,
            topLeft = Offset(left, top),
            size = Size(width, bottom - top),
            cornerRadius = CornerRadius(radius),
        )
        drawRoundRect(
            color = colors.line,
            topLeft = Offset(left + stroke / 2, top + stroke / 2),
            size = Size(width - stroke, bottom - top - stroke),
            cornerRadius = CornerRadius(radius - stroke / 2),
            style = Stroke(width = stroke),
        )
    }
}

private fun Any.isLogCardKey(): Boolean = this is Long || this in LOG_CARD_KEYS

@Composable
private fun SessionCard(session: ActivitySession, lineCount: Int, modifier: Modifier = Modifier) {
    val colors = Shadow.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(ShadowShapes.Card)
            .background(colors.surface1)
            .border(1.dp, colors.line, ShadowShapes.Card)
            .semantics(mergeDescendants = true) { }
            .padding(SMALL_CARD_PADDING),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(
                icon = session.mode.icon(),
                tint = if (session.statusTone == StatusTone.Neutral) {
                    colors.ink2
                } else {
                    colors.toneText(session.statusTone)
                },
                container = colors.surface2,
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = session.title,
                        style = Shadow.type.titleS,
                        color = colors.ink1,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    StatusPill(label = session.statusLabel, tone = session.statusTone, blink = false)
                }
                Text(
                    text = session.subtitle,
                    style = Shadow.type.bodyS,
                    color = colors.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        LineBudget(lineCount)
    }
}

/** "N of 500 lines · Clears on next connect" and the budget bar. */
@Composable
private fun LineBudget(lineCount: Int) {
    val colors = Shadow.colors
    val fraction by animateFloatAsState(
        targetValue = (lineCount.coerceAtMost(MAX_LINES).toFloat() / MAX_LINES),
        animationSpec = shadowTween(ShadowMotion.ColorFade),
        label = "line-budget",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f, fill = false)
                .padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SwapText(
                text = lineCount.toString(),
                style = Shadow.type.monoS.copy(fontWeight = FontWeight.Medium),
                color = colors.ink1,
            )
            Text(text = " of $MAX_LINES lines", style = Shadow.type.monoS, color = colors.ink2, maxLines = 1)
        }
        Text(
            text = "Clears on next connect",
            style = Shadow.type.caption,
            color = colors.ink3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    Box(
        modifier = Modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
            .height(4.dp)
            .clip(ShadowShapes.Pill)
            .background(colors.line2),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .fillMaxHeight()
                .clip(ShadowShapes.Pill)
                .background(colors.ink3),
        )
    }
}

private fun SessionMode?.icon(): ImageVector = when (this) {
    SessionMode.Auto -> ShadowIcons.Auto
    SessionMode.Server -> ShadowIcons.Server
    SessionMode.Routes -> ShadowIcons.Routes
    null -> ShadowIcons.Activity
}

@Composable
private fun FilterRow(
    filter: ActivityFilter,
    sshMode: Boolean,
    onFilter: (ActivityFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Shadow.colors
    val fade = Brush.verticalGradient(
        0f to colors.bg,
        STICKY_SOLID_STOP to colors.bg,
        1f to colors.bg.copy(alpha = 0f),
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(fade)
            .horizontalScroll(rememberScrollState())
            .padding(start = GUTTER, end = GUTTER, top = 16.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ActivityFilter.entries.forEach { option ->
            FilterChip(
                label = option.label(sshMode),
                selected = option == filter,
                problemDot = option == ActivityFilter.Problems,
                onClick = { if (option != filter) onFilter(option) },
            )
        }
    }
}

private fun ActivityFilter.label(sshMode: Boolean): String = when (this) {
    ActivityFilter.All -> "All"
    ActivityFilter.Problems -> "Problems"
    ActivityFilter.Network -> "Network"
    ActivityFilter.Mode -> if (sshMode) "SSH" else "Routes"
}

/** The DS filter chip plus the coral "problems" dot the artboard draws before the label. */
@Composable
private fun FilterChip(
    label: String,
    selected: Boolean,
    problemDot: Boolean,
    onClick: () -> Unit,
) {
    val colors = Shadow.colors
    val spec = shadowTween<Color>(ShadowMotion.Small, ShadowMotion.Ease)
    val container by animateColorAsState(if (selected) colors.ink1 else colors.surface1, spec, label = "chip-bg")
    val border by animateColorAsState(if (selected) colors.ink1 else colors.line, spec, label = "chip-line")
    val content by animateColorAsState(if (selected) colors.bg else colors.ink1, spec, label = "chip-ink")
    val dot by animateColorAsState(
        if (selected) mixColors(colors.coral, colors.bg, ACTIVE_DOT_MIX) else colors.coral,
        spec,
        label = "chip-dot",
    )
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .semantics { this.selected = selected }
            .shadowClickable(interactionSource = interactionSource, role = Role.Tab, onClick = onClick)
            .height(36.dp)
            .clip(ShadowShapes.Pill)
            .background(container)
            .border(1.dp, border, ShadowShapes.Pill)
            // The artboard's 14 dp padding sits inside the chip's 1 dp border.
            .padding(horizontal = 15.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (problemDot) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(dot),
            )
        }
        Text(text = label, style = Shadow.type.label, color = content, maxLines = 1)
    }
}

private fun LazyListScope.highlightItems(
    highlights: ActivityHighlights,
    serverName: String?,
    canEditServer: Boolean,
    onCopyFingerprint: (String) -> Unit,
    onEditServer: () -> Unit,
    onManageServers: () -> Unit,
) {
    item(key = "highlights-head") {
        Overline(
            text = "Highlights",
            modifier = Modifier
                .padding(start = GUTTER + 4.dp, end = GUTTER + 4.dp, top = 12.dp, bottom = 8.dp)
                .fadeUpIn(3),
        )
    }
    highlights.fingerprint?.let { fingerprint ->
        item(key = "highlight-fingerprint") {
            FingerprintCard(
                highlight = fingerprint,
                canEditServer = canEditServer,
                onCopy = onCopyFingerprint,
                onEditServer = onEditServer,
                modifier = Modifier
                    .padding(horizontal = GUTTER)
                    .fadeUpIn(4),
            )
        }
    }
    if (highlights.telegramCallsBlocked) {
        item(key = "highlight-telegram") {
            TelegramCard(
                serverName = serverName,
                onManageServers = onManageServers,
                modifier = Modifier
                    .padding(start = GUTTER, end = GUTTER, top = if (highlights.fingerprint != null) 12.dp else 0.dp)
                    .fadeUpIn(5),
            )
        }
    }
}

@Composable
private fun Overline(text: String, modifier: Modifier = Modifier) {
    Text(text = text.uppercase(), style = Shadow.type.overline, color = Shadow.colors.ink3, modifier = modifier)
}

@Composable
private fun FingerprintCard(
    highlight: FingerprintHighlight,
    canEditServer: Boolean,
    onCopy: (String) -> Unit,
    onEditServer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Shadow.colors
    HighlightCard(tint = colors.skyTint, border = colors.sky.copy(alpha = SKY_BORDER_ALPHA), modifier = modifier) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HighlightTile(icon = ShadowIcons.Fingerprint, tint = colors.skyText, container = colors.sky)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Server host key",
                    style = Shadow.type.titleS,
                    color = colors.ink1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = shortFingerprint(highlight.fingerprint),
                    style = Shadow.type.monoMedium,
                    color = colors.ink1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(
            modifier = Modifier.padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CopyFingerprintButton(onCopy = { onCopy(highlight.fingerprint) })
            if (canEditServer) EditServerLink(onEditServer)
        }
        Text(
            text = when (highlight.pin) {
                HostKeyPin.NotPinned -> "Not pinned yet. Paste it into the server’s Host identity to pin it."
                HostKeyPin.Matched -> "Pinned · it matches the server’s Host identity."
                HostKeyPin.Mismatch -> "Doesn’t match the pinned Host identity, so sign-in was stopped."
                HostKeyPin.Unknown -> "Paste it into the server’s Host identity to pin it."
            },
            style = Shadow.type.caption,
            color = if (highlight.pin == HostKeyPin.Mismatch) colors.coralText else colors.ink3,
            modifier = Modifier.padding(start = 2.dp, end = 2.dp, top = 8.dp),
        )
    }
}

/** Sky tonal "Copy fingerprint" button that turns into a mint "Copied" for 2.2 s. */
@Composable
private fun CopyFingerprintButton(onCopy: () -> Unit) {
    val colors = Shadow.colors
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(COPIED_LABEL_MS)
            copied = false
        }
    }
    val spec = shadowTween<Color>(ShadowMotion.Small, ShadowMotion.Ease)
    val fill by animateColorAsState(
        if (copied) colors.mintTint else colors.sky.copy(alpha = TILE_TINT_ALPHA),
        spec,
        label = "fp-fill",
    )
    val ink by animateColorAsState(if (copied) colors.mintText else colors.skyText, spec, label = "fp-ink")
    val source = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .shadowClickable(source) {
                copied = true
                onCopy()
            }
            .height(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(fill)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (copied) ShadowIcons.Check else ShadowIcons.Copy,
            contentDescription = null,
            tint = ink,
            modifier = Modifier.size(16.dp),
        )
        SwapText(text = if (copied) "Copied" else "Copy fingerprint", style = Shadow.type.label, color = ink)
    }
}

@Composable
private fun EditServerLink(onEditServer: () -> Unit) {
    val colors = Shadow.colors
    val source = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .shadowClickable(source, onClick = onEditServer)
            .height(40.dp)
            .padding(start = 12.dp, end = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "Edit server", style = Shadow.type.label, color = colors.ink1)
        Icon(
            imageVector = ShadowIcons.ChevronRight,
            contentDescription = null,
            tint = colors.ink1,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun TelegramCard(serverName: String?, onManageServers: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Shadow.colors
    HighlightCard(
        tint = colors.amberTint,
        border = colors.amber.copy(alpha = AMBER_BORDER_ALPHA),
        modifier = modifier,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HighlightTile(icon = ShadowIcons.Warning, tint = colors.amberText, container = colors.amber)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Telegram calls won’t work through ${serverName ?: "this server"}",
                    style = Shadow.type.titleS,
                    color = colors.ink1,
                )
                Text(
                    text = "It can’t open TCP to Telegram’s call servers. Messages still work.",
                    style = Shadow.type.bodyS,
                    color = colors.ink2,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    text = "Allow outbound TCP to Telegram on the server, or switch servers.",
                    style = Shadow.type.caption,
                    color = colors.ink3,
                    modifier = Modifier.padding(top = 6.dp),
                )
                val source = remember { MutableInteractionSource() }
                Row(
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .trimBottom(LINK_OVERHANG)
                        .offset(x = -LINK_OVERHANG)
                        .shadowClickable(source, onClick = onManageServers)
                        .height(40.dp)
                        .padding(horizontal = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = "Manage servers", style = Shadow.type.label, color = colors.amberText)
                    Icon(
                        imageVector = ShadowIcons.ChevronRight,
                        contentDescription = null,
                        tint = colors.amberText,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun HighlightCard(
    tint: Color,
    border: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(ShadowShapes.Card)
            .background(tint)
            .border(1.dp, border, ShadowShapes.Card)
            .padding(SMALL_CARD_PADDING),
    ) { content() }
}

@Composable
private fun HighlightTile(icon: ImageVector, tint: Color, container: Color) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(container.copy(alpha = TILE_TINT_ALPHA)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

private fun LazyListScope.logItems(
    shown: List<ActivityLine>,
    filter: ActivityFilter,
    sshMode: Boolean,
    live: Boolean,
    topGap: Dp,
    selectedKey: Long?,
    intro: Boolean,
    onSelect: (Long) -> Unit,
    onCopyLine: (ActivityLine) -> Unit,
) {
    item(key = "log-head") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = GUTTER + 4.dp, end = GUTTER + 4.dp, top = topGap, bottom = 8.dp)
                .fadeUpIn(7),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Overline(text = "Log", modifier = Modifier.weight(1f))
            Text(text = "Tap a line to copy it", style = Shadow.type.caption, color = Shadow.colors.ink3)
        }
    }
    item(key = LOG_TOP_KEY) { Spacer(Modifier.height(CARD_CAP)) }
    itemsIndexed(items = shown, key = { _, line -> line.key }) { index, line ->
        val reduced = Shadow.reducedMotion
        LogLineRow(
            line = line,
            selected = line.key == selectedKey,
            onClick = { onSelect(line.key) },
            onCopy = { onCopyLine(line) },
            modifier = Modifier
                .animateItem(
                    fadeInSpec = if (reduced) null else shadowTween(ShadowMotion.Swap),
                    placementSpec = null,
                    fadeOutSpec = null,
                )
                .then(if (intro && index < INTRO_LINES) Modifier.fadeUpIn(INTRO_LINE_STAGGER) else Modifier),
        )
    }
    if (shown.isEmpty()) {
        item(key = LOG_FILTER_EMPTY_KEY) { FilterEmptyText(filter = filter, sshMode = sshMode) }
    }
    if (live) {
        item(key = LOG_LIVE_KEY) {
            Row(
                modifier = Modifier
                    .insideLogCard()
                    .padding(start = 84.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(color = Shadow.colors.mint, blink = true, size = 6.dp)
                LiveText(rest = " · new lines appear here")
            }
        }
    }
    item(key = LOG_BOTTOM_KEY) { Spacer(Modifier.height(CARD_CAP)) }
}

@Composable
private fun FilterEmptyText(filter: ActivityFilter, sshMode: Boolean) {
    Text(
        text = when (filter) {
            ActivityFilter.Problems -> "No warnings or errors in this session."
            ActivityFilter.Network -> "No network events yet."
            ActivityFilter.Mode -> if (sshMode) "No SSH events yet." else "No route events yet."
            ActivityFilter.All -> ""
        },
        style = Shadow.type.bodyS,
        color = Shadow.colors.ink3,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .insideLogCard()
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 12.dp),
    )
}

@Composable
private fun LiveText(rest: String) {
    val colors = Shadow.colors
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = colors.mintText, fontWeight = FontWeight.SemiBold)) { append("Live") }
            append(rest)
        },
        style = Shadow.type.caption,
        color = colors.ink3,
    )
}

/** Full width minus the gutter and the card's 1 dp border (the card itself is drawn by [drawLogCard]). */
private fun Modifier.insideLogCard(): Modifier = this
    .fillMaxWidth()
    .padding(horizontal = GUTTER + 1.dp)

@Composable
private fun LogLineRow(
    line: ActivityLine,
    selected: Boolean,
    onClick: () -> Unit,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Shadow.colors
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val fill by animateColorAsState(
        targetValue = if (selected || pressed) colors.surface2 else colors.surface2.copy(alpha = 0f),
        animationSpec = shadowTween(ShadowMotion.Small, ShadowMotion.Ease),
        label = "log-line-fill",
    )
    val textStyle = Shadow.type.monoS.copy(lineHeight = 18.sp, fontFeatureSettings = NO_LIGATURES)
    Column(modifier = modifier.insideLogCard()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(fill)
                .clickable(
                    interactionSource = interactionSource,
                    indication = ShadowFocusIndication,
                    role = Role.Button,
                    onClickLabel = if (selected) "Hide copy action" else "Show copy action",
                    onClick = onClick,
                )
                .semantics(mergeDescendants = true) {
                    contentDescription = listOf(line.time, "${line.severity.spoken}: ${line.message}")
                        .filter { it.isNotEmpty() }
                        .joinToString(", ")
                    this.selected = selected
                }
                .padding(horizontal = 14.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(text = line.time, style = textStyle, color = colors.ink3, modifier = Modifier.width(TIME_COLUMN))
            Box(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(severityColor(colors, line.severity)),
            )
            Text(
                text = line.message,
                style = textStyle,
                color = if (line.severity == LogSeverity.Error) colors.ink1 else colors.ink2,
                modifier = Modifier.weight(1f),
            )
        }
        AnimatedVisibility(
            visible = selected,
            enter = fadeIn(shadowTween(ShadowMotion.Swap)) +
                slideInVertically(shadowTween(ShadowMotion.Swap)) { it / 4 },
            exit = fadeOut(shadowTween(ShadowMotion.Small)),
        ) {
            CopyLineAction(onCopy)
        }
    }
}

/** The "Copy line" pill revealed under a tapped line, aligned with the message column. */
@Composable
private fun CopyLineAction(onCopy: () -> Unit) {
    val colors = Shadow.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface2)
            .padding(start = 100.dp, end = 14.dp, bottom = 10.dp),
    ) {
        val source = remember { MutableInteractionSource() }
        Row(
            modifier = Modifier
                .shadowClickable(source, onClick = onCopy)
                .height(36.dp)
                .clip(ShadowShapes.Pill)
                .background(colors.surface3)
                .padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = ShadowIcons.Copy,
                contentDescription = null,
                tint = colors.ink1,
                modifier = Modifier.size(14.dp),
            )
            Text(text = "Copy line", style = Shadow.type.label.copy(fontSize = 12.sp), color = colors.ink1)
        }
    }
}

/** Lets the next sibling overlap the last [amount] of this element (a CSS negative bottom margin). */
private fun Modifier.trimBottom(amount: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val height = (placeable.height - amount.roundToPx()).coerceAtLeast(0)
    layout(placeable.width, height) { placeable.place(0, 0) }
}

private fun severityColor(colors: ShadowColors, severity: LogSeverity): Color = when (severity) {
    LogSeverity.Info -> colors.ink3
    LogSeverity.Ok -> colors.mint
    LogSeverity.Warning -> colors.amber
    LogSeverity.Error -> colors.coral
}

@Composable
private fun PrivacyNote(modifier: Modifier = Modifier) {
    val colors = Shadow.colors
    Row(
        modifier = modifier.padding(start = 4.dp, end = 4.dp, top = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(ShadowIcons.Lock, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(16.dp))
        Text(
            text = "Passwords, keys and terminal commands are never logged. Some IP addresses stay visible, " +
                "so check before sharing.",
            style = Shadow.type.caption,
            color = colors.ink3,
        )
    }
}

@Composable
private fun EmptyActivity(state: ActivityScreenState, onGoHome: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(start = GUTTER, end = GUTTER, top = 4.dp, bottom = LIST_BOTTOM_ROOM),
    ) {
        state.session?.let { session ->
            SessionCard(session = session, lineCount = 0, modifier = Modifier.fadeUpIn(0))
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(top = 48.dp, bottom = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            EmptyActivityMessage(cleared = state.cleared, live = state.live, onGoHome = onGoHome)
        }
        PrivacyNote()
    }
}

@Composable
private fun EmptyActivityMessage(cleared: Boolean, live: Boolean, onGoHome: () -> Unit) {
    val colors = Shadow.colors
    Column(
        modifier = Modifier
            .fadeUpIn(2)
            .padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconTile(
            icon = ShadowIcons.Activity,
            size = 56.dp,
            iconSize = 26.dp,
            cornerRadius = 16.dp,
            tint = colors.ink2,
            container = colors.surface2,
            bordered = true,
        )
        SwapText(
            text = if (cleared) "Activity cleared" else "No activity yet",
            style = Shadow.type.titleS,
            color = colors.ink1,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = if (live) {
                "New steps from this connection will show up here."
            } else {
                "Connect once and every step shows up here."
            },
            style = Shadow.type.bodyS,
            color = colors.ink3,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(top = 6.dp)
                .widthIn(max = 260.dp),
        )
        if (live) {
            Row(
                modifier = Modifier
                    .padding(top = 18.dp)
                    .height(28.dp)
                    .clip(ShadowShapes.Pill)
                    .background(colors.surface1)
                    .border(1.dp, colors.line, ShadowShapes.Pill)
                    .padding(horizontal = 13.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(color = colors.mint, blink = true, size = 6.dp)
                LiveText(rest = " · listening for new lines")
            }
        } else {
            PrimaryButton(
                text = "Go to Home",
                onClick = onGoHome,
                size = EMPTY_BUTTON,
                modifier = Modifier.padding(top = 24.dp),
            )
        }
    }
}

@Composable
private fun JumpToLatest(
    visible: Boolean,
    fresh: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val toast = LocalToaster.current.current
    val lift by animateDpAsState(
        targetValue = when {
            toast == null -> 0.dp
            toast.detail != null -> TOAST_LIFT_TALL
            else -> TOAST_LIFT
        },
        animationSpec = shadowTween(ShadowMotion.Surface),
        label = "jump-lift",
    )
    // Aligned to the end of the (centered, width-capped) log column, not of a wide tablet window.
    Box(
        modifier = modifier
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .widthIn(max = CONTENT_MAX_WIDTH)
            .fillMaxWidth(),
        contentAlignment = Alignment.BottomEnd,
    ) {
        JumpPill(visible = visible, fresh = fresh, lift = lift, onClick = onClick)
    }
}

@Composable
private fun JumpPill(visible: Boolean, fresh: Int, lift: Dp, onClick: () -> Unit) {
    val colors = Shadow.colors
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier
            .navigationBarsPadding()
            .padding(end = GUTTER, bottom = JUMP_BOTTOM + lift),
        enter = fadeIn(shadowTween(JUMP_FADE_MS)) +
            scaleIn(shadowTween(ShadowMotion.Surface), initialScale = JUMP_HIDDEN_SCALE) +
            slideInVertically(shadowTween(ShadowMotion.Surface)) { it / 3 },
        exit = fadeOut(shadowTween(JUMP_FADE_MS)) +
            scaleOut(shadowTween(ShadowMotion.Surface), targetScale = JUMP_HIDDEN_SCALE) +
            slideOutVertically(shadowTween(ShadowMotion.Surface)) { it / 3 },
    ) {
        val source = remember { MutableInteractionSource() }
        Row(
            modifier = Modifier
                .shadowClickable(source, pressedScale = ShadowMotion.OrbPressScale, onClick = onClick)
                .shadow(JUMP_ELEVATION, ShadowShapes.Pill)
                .height(44.dp)
                .clip(ShadowShapes.Pill)
                .background(colors.surface3)
                .border(1.dp, colors.line2, ShadowShapes.Pill)
                // The artboard's 12 / 16 dp padding sits inside the pill's 1 dp border.
                .padding(start = 13.dp, end = 17.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = ShadowIcons.ChevronDown,
                contentDescription = null,
                tint = colors.ink1,
                modifier = Modifier.size(18.dp),
            )
            Text(text = "Jump to latest", style = Shadow.type.label, color = colors.ink1)
            if (fresh > 0) FreshBadge(fresh)
        }
    }
}

@Composable
private fun FreshBadge(fresh: Int) {
    val colors = Shadow.colors
    Box(
        modifier = Modifier
            .height(20.dp)
            .clip(ShadowShapes.Pill)
            .background(colors.amber)
            .padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        SwapText(
            text = "$fresh new",
            style = Shadow.type.monoS.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
            color = colors.onAmber,
        )
    }
}

@Composable
private fun ClearActivityDialog(count: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    ShadowDialog(
        onDismissRequest = onDismiss,
        title = "Clear activity?",
        message = (if (count == 1) "This line is" else "All $count lines are") +
            " removed from this phone, along with the highlights built from them. " +
            "New lines keep arriving while you’re connected.",
        confirmLabel = "Clear activity",
        onConfirm = onConfirm,
        stacked = true,
        confirmVariant = ShadowButtonVariant.DangerTonal,
        icon = ShadowIcons.Trash,
    )
}

private const val MAX_LINES = 500
private const val INTRO_MS = 1_000L
private const val INTRO_LINES = 12
private const val INTRO_LINE_STAGGER = 8
private const val NO_LIGATURES = "liga 0, calt 0"
private const val STICKY_SOLID_STOP = 0.78f
private const val ACTIVE_DOT_MIX = 0.7f
private const val TILE_TINT_ALPHA = 0.16f
private const val SKY_BORDER_ALPHA = 0.22f
private const val AMBER_BORDER_ALPHA = 0.24f
private const val COPIED_LABEL_MS = 2_200L
private const val JUMP_FADE_MS = 220
private const val JUMP_HIDDEN_SCALE = 0.94f
private const val LOG_TOP_KEY = "log-top"
private const val LOG_BOTTOM_KEY = "log-bottom"
private const val LOG_LIVE_KEY = "log-live"
private const val LOG_FILTER_EMPTY_KEY = "log-filter-empty"
private val LOG_CARD_KEYS = setOf(LOG_TOP_KEY, LOG_BOTTOM_KEY, LOG_LIVE_KEY, LOG_FILTER_EMPTY_KEY)
private val GUTTER = 20.dp

/** Reading width of the log on tablets (the phone layout, centered). */
private val CONTENT_MAX_WIDTH = ShadowDimens.PaneMaxWidth
private val LINK_OVERHANG = 10.dp
private val CARD_RADIUS = 18.dp

/** The artboard's 14 dp card padding plus its 1 dp border (CSS draws the border outside the padding). */
private val SMALL_CARD_PADDING = 15.dp

/** Log card top / bottom: 6 dp padding plus the 1 dp border. */
private val CARD_CAP = 7.dp
private val TIME_COLUMN = 60.dp
private val LIST_BOTTOM_ROOM = 96.dp
private val END_SLOP = 40.dp
private val JUMP_BOTTOM = 24.dp
private val TOAST_LIFT = 56.dp
private val TOAST_LIFT_TALL = 72.dp
private val JUMP_ELEVATION = 12.dp
private val EMPTY_BUTTON = ShadowButtonSize(52.dp, 28.dp, 18.dp, 16.dp, ShadowButtonLabel.Large)
