package com.stansful.sshvpnclient.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.stansful.sshvpnclient.ui.designsystem.LatencyLevel
import com.stansful.sshvpnclient.ui.designsystem.LatencyMeter
import com.stansful.sshvpnclient.ui.designsystem.MetaTag
import com.stansful.sshvpnclient.ui.designsystem.MetaTagSize
import com.stansful.sshvpnclient.ui.designsystem.SecondaryButton
import com.stansful.sshvpnclient.ui.designsystem.SegmentOption
import com.stansful.sshvpnclient.ui.designsystem.ShadowBottomSheet
import com.stansful.sshvpnclient.ui.designsystem.ShadowFocusIndication
import com.stansful.sshvpnclient.ui.designsystem.ShadowIcons
import com.stansful.sshvpnclient.ui.designsystem.ShadowSegmented
import com.stansful.sshvpnclient.ui.designsystem.StatusDot
import com.stansful.sshvpnclient.ui.designsystem.StatusTone
import com.stansful.sshvpnclient.ui.designsystem.fadeUpIn
import com.stansful.sshvpnclient.ui.designsystem.latencyLevel
import com.stansful.sshvpnclient.ui.designsystem.toneText
import com.stansful.sshvpnclient.ui.theme.Shadow
import com.stansful.sshvpnclient.ui.theme.ShadowColors
import com.stansful.sshvpnclient.ui.theme.ShadowMotion
import com.stansful.sshvpnclient.ui.theme.ShadowShapes
import com.stansful.sshvpnclient.ui.theme.shadowTween

/** Tabs of the Auto "Route pool" sheet. */
internal enum class PoolTab { Pool, How }

// region Choose server / Quick switch

/** Server mode: every saved server; picking one while connected reconnects through it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChooseServerSheet(
    options: List<ServerOption>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onManageServers: () -> Unit,
) {
    ShadowBottomSheet(
        onDismissRequest = onDismiss,
        title = "Choose server",
        subtitle = "Switching while connected reconnects through the new server.",
        actions = {
            SecondaryButton(text = "Manage servers", onClick = onManageServers, modifier = Modifier.fillMaxWidth())
        },
    ) {
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
        ) {
            options.forEachIndexed { index, option ->
                PickRow(
                    selected = option.selected,
                    title = option.name,
                    subtitle = option.address,
                    contentDescription = (if (option.selected) "Selected: " else "Use ") +
                        "${option.name}, ${option.signIn.lowercase()} sign-in",
                    onClick = { onSelect(option.id) },
                    modifier = Modifier.fadeUpIn(index),
                ) {
                    MetaTag(text = option.signIn, size = MetaTagSize.Large)
                }
            }
        }
    }
}

/** Routes mode: pinned and fastest library routes; picking one calls `switchToProfile`. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuickSwitchSheet(
    options: List<RouteOption>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onOpenRouteLibrary: () -> Unit,
) {
    ShadowBottomSheet(
        onDismissRequest = onDismiss,
        title = "Quick switch",
        subtitle = "Pinned and fastest routes from your library. Switching while connected restarts the engine.",
        actions = {
            SecondaryButton(
                text = "Open route library",
                onClick = onOpenRouteLibrary,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
        ) {
            if (options.isEmpty()) {
                Text(
                    text = "No pinned or checked routes yet. Pin or check routes in the Route library.",
                    style = Shadow.type.bodyS,
                    color = Shadow.colors.ink3,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                )
            }
            options.forEachIndexed { index, option ->
                PickRow(
                    selected = option.selected,
                    title = option.name,
                    subtitle = option.subtitle,
                    contentDescription = (if (option.selected) "Selected: " else "Use ") + option.name +
                        (if (option.pinned) ", pinned" else "") + ", " +
                        when {
                            option.outdated -> "outdated, can't connect"
                            else -> option.latencyMs?.let { "$it ms" } ?: "not checked"
                        },
                    onClick = { onSelect(option.id) },
                    modifier = Modifier.fadeUpIn(index),
                ) {
                    if (option.pinned) {
                        Icon(
                            imageVector = ShadowIcons.Pin,
                            contentDescription = "Pinned",
                            tint = Shadow.colors.skyText,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Row(
                        modifier = Modifier.widthIn(min = 76.dp),
                        horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (option.outdated) {
                            // The route library's "Outdated" tag.
                            Icon(
                                imageVector = ShadowIcons.Clock,
                                contentDescription = null,
                                tint = Shadow.colors.coralText,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                text = "Outdated",
                                style = Shadow.type.caption.copy(fontWeight = FontWeight.SemiBold),
                                color = Shadow.colors.coralText,
                            )
                        } else {
                            LatencyMeter(latencyMs = option.latencyMs)
                        }
                    }
                }
            }
        }
    }
}

/** Main.dc.html sheet row: check-circle or empty ring, title 15/600 + mono subtitle, trailing. */
@Composable
internal fun PickRow(
    selected: Boolean,
    title: String,
    subtitle: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    minHeight: Dp = 0.dp,
    shape: RoundedCornerShape = RoundedCornerShape(16.dp),
    padding: PaddingValues = PaddingValues(12.dp),
    bottomGap: Dp = 4.dp,
    titleTrailing: (@Composable () -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    val colors = Shadow.colors
    val interaction = remember { MutableInteractionSource() }
    val background by animateColorAsState(
        targetValue = if (selected) colors.amberTint else Color.Transparent,
        animationSpec = shadowTween(ShadowMotion.ColorFade, ShadowMotion.Ease),
        label = "pick-row",
    )
    Row(
        modifier = modifier
            .padding(bottom = bottomGap)
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .clip(shape)
            .background(background)
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = ShadowFocusIndication,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) { this.contentDescription = contentDescription }
            .padding(padding),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SelectionMark(selected = selected, color = colors.amberText)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = RowTitleStrong,
                    color = colors.ink1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                titleTrailing?.invoke()
            }
            Text(
                text = subtitle,
                style = Shadow.type.monoS,
                color = colors.ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing()
    }
}

/** 22 dp check-circle in [color], or a 16 dp empty ring. */
@Composable
internal fun SelectionMark(selected: Boolean, color: Color) {
    Box(modifier = Modifier.size(22.dp), contentAlignment = Alignment.Center) {
        if (selected) {
            Icon(ShadowIcons.CheckCircle, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
        } else {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .border(1.5.dp, Shadow.colors.emptyRing(), CircleShape),
            )
        }
    }
}

/** The unselected ring: #3A414D on Night (ink-3 at 35 %), ink-3 on Day. */
internal fun ShadowColors.emptyRing(): Color = if (isDark) ink3.copy(alpha = EMPTY_RING_ALPHA) else ink3

private val RowTitleStrong
    @Composable get() = Shadow.type.rowTitle.copy(fontWeight = FontWeight.SemiBold)

// endregion

// region Route pool (AutoRoutes.dc.html)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RoutePoolSheet(
    pool: PoolSheet,
    initialTab: PoolTab,
    onDismiss: () -> Unit,
    onOpenRouteLibrary: () -> Unit,
    onOpenActivity: () -> Unit,
) {
    val colors = Shadow.colors
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    // AutoRoutes draws a 700 dp sheet: handle 28 + header 68 + bottom padding 22 around this body.
    val bodyHeight = min(POOL_SHEET_HEIGHT, windowHeight - POOL_SHEET_TOP_GAP) - POOL_SHEET_CHROME
    ShadowBottomSheet(
        onDismissRequest = onDismiss,
        title = "Route pool",
        closeContentDescription = "Close route pool",
        header = {
            Text(
                text = strongText(pool.subtitleStrong, "", colors.ink2).let {
                    buildAnnotatedString {
                        append(it)
                        append(pool.subtitleRest)
                    }
                },
                style = Shadow.type.bodyS,
                color = colors.ink3,
                // The header slot sits 10 dp under the title; the artboard's subtitle sits 6 dp under it.
                modifier = Modifier.layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    val lift = HEADER_LIFT.roundToPx()
                    layout(placeable.width, placeable.height - lift) { placeable.place(0, -lift) }
                },
            )
        },
    ) {
        Column(modifier = Modifier.height(bodyHeight.coerceAtLeast(POOL_BODY_MIN))) {
            ShadowSegmented(
                options = listOf(
                    SegmentOption(label = "Pool", count = pool.workingCount.toString()),
                    SegmentOption(label = "How Auto works"),
                ),
                selectedIndex = tab.ordinal,
                onSelect = { tab = PoolTab.entries[it] },
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 12.dp),
            )
            Box(modifier = Modifier.weight(1f)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 36.dp),
                ) {
                    when (tab) {
                        PoolTab.Pool -> PoolTabContent(pool, onOpenRouteLibrary)
                        PoolTab.How -> HowAutoWorks(pool, onOpenActivity)
                    }
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(36.dp)
                        .background(Brush.verticalGradient(listOf(colors.sheet.copy(alpha = 0f), colors.sheet))),
                )
            }
            if (tab == PoolTab.Pool) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .topDivider(colors.line)
                        .padding(start = 24.dp, end = 24.dp, top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = ShadowIcons.Refresh,
                        contentDescription = null,
                        tint = colors.ink3,
                        modifier = Modifier
                            .padding(top = 1.dp)
                            .size(16.dp),
                    )
                    Text(
                        text = "Routes with failed checks are removed; the list is refreshed every pass.",
                        style = Shadow.type.bodyS,
                        color = colors.ink3,
                    )
                }
            }
        }
    }
}

@Composable
private fun PoolTabContent(pool: PoolSheet, onOpenRouteLibrary: () -> Unit) {
    val colors = Shadow.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Overline("Fastest first")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(ShadowIcons.Auto, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(14.dp))
            Text(text = "Auto chooses", style = Shadow.type.caption, color = colors.ink3)
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ShadowShapes.Card)
            .background(colors.surface1)
            .border(1.dp, colors.line, ShadowShapes.Card)
            .padding(1.dp),
    ) {
        val pick = pool.pick
        if (pick == null && pool.others.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(text = "No routes tested yet", style = Shadow.type.titleS, color = colors.ink1)
                Text(
                    text = "Auto downloads and tests the public list when you connect.",
                    style = Shadow.type.bodyS,
                    color = colors.ink3,
                )
            }
        }
        if (pick != null) PickedRoute(pick)
        pool.others.forEachIndexed { index, row ->
            PoolOtherRow(row, modifier = Modifier.fadeUpIn(index + 2))
        }
        if (pool.moreCount > 0) {
            Row(
                modifier = Modifier
                    .padding(start = 16.dp)
                    .fillMaxWidth()
                    .topDivider(colors.line2)
                    .heightIn(min = 44.dp)
                    .padding(end = 16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = buildAnnotatedString {
                        val mono = SpanStyle(
                            fontFamily = Shadow.type.monoMedium.fontFamily,
                            fontWeight = FontWeight.Medium,
                            color = colors.ink2,
                        )
                        withStyle(mono) {
                            append("+${pool.moreCount}")
                        }
                        append(" more working routes, slower than these")
                    },
                    style = Shadow.type.bodyS,
                    color = colors.ink3,
                )
            }
        }
    }
    Row(
        modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = ShadowIcons.Routes,
            contentDescription = null,
            tint = colors.ink3,
            modifier = Modifier
                .padding(top = 1.dp)
                .size(16.dp),
        )
        Text(
            text = buildAnnotatedString {
                append("Auto chooses for you, so rows here can’t be picked. To choose a route yourself, use the ")
                withLink(
                    LinkAnnotation.Clickable(
                        tag = "library",
                        styles = TextLinkStyles(SpanStyle(color = colors.amberText, fontWeight = FontWeight.SemiBold)),
                    ) { onOpenRouteLibrary() },
                ) { append("Route library") }
                append(".")
            },
            style = Shadow.type.bodyS,
            color = colors.ink3,
        )
    }
}

@Composable
private fun PickedRoute(pick: PoolPick) {
    val colors = Shadow.colors
    val (rowTint, accent, tagFill, tagInk, strip) = when (pick.tag) {
        PickTag.InUse -> PickColors(
            colors.mint.copy(alpha = PICK_ROW_ALPHA), colors.mintText, colors.mint, colors.onMint,
            colors.mint.copy(alpha = STRIP_LINE_ALPHA),
        )
        PickTag.Verifying -> PickColors(
            colors.amber.copy(alpha = PICK_ROW_ALPHA), colors.amberText, colors.amber, colors.onAmber,
            colors.amber.copy(alpha = STRIP_LINE_ALPHA),
        )
        PickTag.LastPick -> PickColors(colors.surface2, colors.ink2, colors.surface3, colors.ink2, colors.line2)
    }
    val liveColor = latencyColor(colors, pick.liveMs)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowTint)
            .semantics(mergeDescendants = true) {
                contentDescription = "${pick.name}, picked automatically, ${pick.tag.label}, " +
                    "${pick.pickedAtMs?.let { "$it ms" } ?: "latency unknown"}, ${pick.subtitle}"
            }
            .fadeUpIn(0),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                Icon(ShadowIcons.CheckCircle, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = pick.name,
                        style = RowTitleStrong,
                        color = colors.ink1,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Box(
                        modifier = Modifier
                            .height(20.dp)
                            .clip(CircleShape)
                            .background(tagFill)
                            .padding(horizontal = 7.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = pick.tag.label, style = TagText, color = tagInk, maxLines = 1)
                    }
                }
                Text(
                    text = pick.subtitle,
                    style = Shadow.type.monoS,
                    color = colors.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            LatencyMeter(latencyMs = pick.pickedAtMs)
        }
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .clip(ShadowShapes.Banner)
                .background(colors.bg.copy(alpha = STRIP_FILL_ALPHA))
                .border(1.dp, strip, ShadowShapes.Banner),
        ) {
            StripCell(
                title = "Picked at",
                value = pick.pickedAtMs?.toString() ?: "—",
                unit = if (pick.pickedAtMs != null) "ms" else "",
                valueColor = latencyColor(colors, pick.pickedAtMs),
                caption = "Selection test",
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(strip),
            )
            StripCell(
                title = "Live check",
                value = pick.liveMs?.toString() ?: "—",
                unit = if (pick.liveMs != null) "ms" else "",
                valueColor = if (pick.liveMs != null) liveColor else colors.ink3,
                caption = if (pick.liveRunning) "Through the tunnel" else "Runs once connected",
                dotColor = if (pick.liveRunning) colors.mint else null,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            text = "Measured at different times, so the two can differ.",
            style = Shadow.type.caption,
            color = colors.ink3,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 14.dp),
        )
    }
}

private data class PickColors(
    val row: Color,
    val accent: Color,
    val tagFill: Color,
    val tagInk: Color,
    val strip: Color,
)

@Composable
private fun StripCell(
    title: String,
    value: String,
    unit: String,
    valueColor: Color,
    caption: String,
    modifier: Modifier = Modifier,
    dotColor: Color? = null,
) {
    val colors = Shadow.colors
    Column(modifier = modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 11.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Overline(title)
            if (dotColor != null) StatusDot(color = dotColor, size = 6.dp)
        }
        Row(
            modifier = Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(text = value, style = BigMono, color = valueColor)
            if (unit.isNotEmpty()) {
                Text(
                    text = unit,
                    style = Shadow.type.monoS,
                    color = colors.ink3,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
        }
        Text(
            text = caption,
            style = Shadow.type.caption,
            color = colors.ink3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun PoolOtherRow(row: PoolRow, modifier: Modifier = Modifier) {
    val colors = Shadow.colors
    Row(
        modifier = modifier
            .padding(start = 16.dp)
            .fillMaxWidth()
            .topDivider(colors.line2)
            .heightIn(min = 60.dp)
            .semantics(mergeDescendants = true) {
                val latency = row.latencyMs?.let { "$it ms" } ?: "latency unknown"
                contentDescription = "Number ${row.rank}, ${row.name}, $latency, ${row.subtitle}"
            }
            .padding(top = 10.dp, bottom = 10.dp, end = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = row.rank.toString(),
            style = Shadow.type.monoS.copy(fontWeight = FontWeight.Medium),
            color = colors.ink3,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(24.dp),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = row.name,
                style = RowTitleStrong,
                color = colors.ink1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = row.subtitle,
                style = Shadow.type.monoS,
                color = colors.ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(modifier = Modifier.widthIn(min = 76.dp), horizontalArrangement = Arrangement.End) {
            LatencyMeter(latencyMs = row.latencyMs)
        }
    }
}

private val HowSteps = listOf(
    HowStep(
        ShadowIcons.Download,
        "Update the list",
        "Waits for a network, then downloads the list from the public source. If the download fails, the saved " +
            "list is used.",
    ),
    HowStep(
        ShadowIcons.Bolt,
        "Test every route",
        "All routes are tested at once: up to 5 s each, 60 s for the whole pass.",
    ),
    HowStep(ShadowIcons.Trash, "Drop dead routes", "Routes that failed or left the source are removed from the pool."),
    HowStep(ShadowIcons.Auto, "Pick the lowest latency", "The fastest working route wins. Ties go by name."),
    HowStep(
        ShadowIcons.Shield,
        "Verify through youtube.com",
        "The tunnel opens and youtube.com must answer with a 2xx. Two tries; if both fail, Auto starts over " +
            "without that route.",
    ),
    HowStep(
        ShadowIcons.Activity,
        "Watch & switch",
        "Keeps checking the open tunnel. A failing route is re-checked for 30–45 s, then replaced; until then " +
            "the status still says Connected.",
    ),
)

private data class HowStep(val icon: ImageVector, val title: String, val description: String)

private val RetryLadder = listOf(
    Triple("30 s", 12, 0.26f),
    Triple("1 min", 18, 0.36f),
    Triple("2 min", 25, 0.5f),
    Triple("5 min", 34, 0.66f),
    Triple("15 min", 44, 0.86f),
)

private val GoodToKnow = listOf(
    ShadowIcons.Shield to "Live checks run every 10 s in the first minute, then every 30 s (2 min with the " +
        "screen off, 5 min on Battery Saver).",
    ShadowIcons.Clock to "A route that fails sits out for 15 min and leaves the pool at the next clean-up.",
    ShadowIcons.Network to "Switching networks reconnects the same route, no new pass.",
    ShadowIcons.Power to "Starting Auto stops a Server or Routes connection first.",
    ShadowIcons.Refresh to "After a phone restart, Auto resumes when you open shadow or tap its quick tile.",
)

@Composable
private fun HowAutoWorks(pool: PoolSheet, onOpenActivity: () -> Unit) {
    val colors = Shadow.colors
    val connected = pool.runState == AutoRunState.Connected
    val stateInk = when (pool.runState) {
        AutoRunState.Connected -> colors.mintText
        AutoRunState.Searching -> colors.amberText
        AutoRunState.Stopped -> colors.ink3
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Overline("Every pass, in six steps")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(color = stateInk, size = 6.dp, blink = pool.runState == AutoRunState.Searching)
            Text(text = pool.runState.label, style = Shadow.type.caption, color = stateInk)
        }
    }
    Column(modifier = Modifier.padding(horizontal = 4.dp)) {
        HowSteps.forEachIndexed { index, step ->
            val current = index == pool.currentStep
            val done = pool.currentStep >= 0 && index < pool.currentStep
            val toneInk = if (connected) colors.mintText else colors.amberText
            val toneTint = if (connected) colors.mintTint else colors.amberTint
            val ink = when {
                current -> toneInk
                done -> colors.mintText
                else -> colors.ink2
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .fadeUpIn(index),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(
                    modifier = Modifier
                        .width(40.dp)
                        .fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    StepTile(
                        icon = step.icon,
                        ink = ink,
                        fill = if (current) toneTint else colors.surface2,
                        current = current,
                    )
                    if (index < HowSteps.lastIndex) {
                        Box(
                            modifier = Modifier
                                .padding(vertical = 6.dp)
                                .width(2.dp)
                                .weight(1f)
                                .heightIn(min = 12.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(if (done) colors.mint.copy(alpha = DONE_LINE_ALPHA) else colors.line2),
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = 9.dp, bottom = if (index < HowSteps.lastIndex) 18.dp else 8.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "0${index + 1}",
                            style = Shadow.type.monoS.copy(fontWeight = FontWeight.Medium, lineHeight = 22.sp),
                            color = colors.ink3,
                        )
                        Text(text = step.title, style = Shadow.type.titleS.copy(fontSize = 15.sp), color = colors.ink1)
                        if (current) {
                            Box(
                                modifier = Modifier
                                    .height(20.dp)
                                    .clip(CircleShape)
                                    .background(toneTint)
                                    .padding(horizontal = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = if (connected) "Now · watching" else "Now",
                                    style = TagText,
                                    color = toneInk,
                                )
                            }
                        }
                    }
                    Text(
                        text = step.description,
                        style = Shadow.type.bodyS,
                        color = colors.ink3,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
    RetryLadderCard()
    UnprotectedNote(searching = pool.runState == AutoRunState.Searching)
    Row(
        modifier = Modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
            .clip(ShadowShapes.Banner)
            .background(colors.skyTint)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(ShadowIcons.Info, contentDescription = null, tint = colors.skyText, modifier = Modifier.size(18.dp))
        Text(text = "Routes whose name has the RU flag are never used.", style = Shadow.type.bodyS, color = colors.ink1)
    }
    Overline("Good to know", modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 24.dp, bottom = 8.dp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ShadowShapes.Card)
            .background(colors.surface1)
            .border(1.dp, colors.line, ShadowShapes.Card)
            .padding(1.dp),
    ) {
        GoodToKnow.forEachIndexed { index, (icon, text) ->
            Row(
                modifier = Modifier
                    .padding(start = 16.dp)
                    .fillMaxWidth()
                    .then(if (index > 0) Modifier.topDivider(colors.line2) else Modifier)
                    .padding(top = 14.dp, bottom = 14.dp, end = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(icon, contentDescription = null, tint = colors.ink2, modifier = Modifier.size(20.dp))
                Text(text = text, style = Body14, color = colors.ink1)
            }
        }
        val interaction = remember { MutableInteractionSource() }
        Row(
            modifier = Modifier
                .padding(start = 16.dp)
                .fillMaxWidth()
                .topDivider(colors.line2)
                .heightIn(min = 56.dp)
                .selectable(
                    selected = false,
                    interactionSource = interaction,
                    indication = ShadowFocusIndication,
                    role = Role.Button,
                    onClick = onOpenActivity,
                )
                .padding(top = 10.dp, bottom = 10.dp, end = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = ShadowIcons.Activity,
                contentDescription = null,
                tint = colors.amberText,
                modifier = Modifier.size(20.dp),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = "Why did Auto retry?", style = Shadow.type.rowTitle, color = colors.ink1)
                Text(text = "Every reason is in Connection activity", style = Shadow.type.bodyS, color = colors.ink3)
            }
            Icon(
                imageVector = ShadowIcons.ChevronRight,
                contentDescription = null,
                tint = colors.ink3,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun StepTile(icon: ImageVector, ink: Color, fill: Color, current: Boolean) {
    val reduced = Shadow.reducedMotion
    Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
        if (current) {
            // Read in the layer block: the breath redraws the layer instead of recomposing every frame.
            val breathState = if (reduced) {
                null
            } else {
                rememberInfiniteTransition(label = "step-breathe").animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(ShadowMotion.Breathe / 2, easing = ShadowMotion.EaseInOut),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "step-breathe-value",
                )
            }
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .graphicsLayer {
                        val breath = breathState?.value ?: 1f
                        alpha = BREATH_MIN_ALPHA + (1f - BREATH_MIN_ALPHA) * breath
                        val scale = BREATH_MIN_SCALE + (BREATH_MAX_SCALE - BREATH_MIN_SCALE) * breath
                        scaleX = scale
                        scaleY = scale
                    }
                    .border(1.5.dp, ink, RoundedCornerShape(17.dp)),
            )
        }
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(fill),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun RetryLadderCard() {
    val colors = Shadow.colors
    Column(
        modifier = Modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
            .clip(ShadowShapes.Card)
            .background(colors.surface1)
            .border(1.dp, colors.line, ShadowShapes.Card)
            .padding(17.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(ShadowShapes.Tile)
                    .background(colors.surface2),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = ShadowIcons.Clock,
                    contentDescription = null,
                    tint = colors.amberText,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "If no route works", style = RowTitleStrong, color = colors.ink1)
                Text(
                    text = "Auto retries until you stop it, waiting longer each time.",
                    style = Shadow.type.bodyS,
                    color = colors.ink3,
                )
            }
        }
        Row(
            modifier = Modifier
                .padding(top = 16.dp)
                .fillMaxWidth()
                .height(66.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = "Waits between retries: 30 seconds, 1 minute, 2 minutes, 5 minutes, " +
                        "then 15 minutes"
                },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            RetryLadder.forEach { (label, height, alpha) ->
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(height.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(colors.amber.copy(alpha = alpha)),
                    )
                    Text(text = label, style = Shadow.type.monoS, color = colors.ink2, maxLines = 1)
                }
            }
        }
        Text(
            text = "Then every 15 min. A new network, the screen turning on or off, Battery Saver or an " +
                "app-routing change starts the next try right away.",
            style = Shadow.type.caption,
            color = colors.ink3,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun UnprotectedNote(searching: Boolean) {
    val colors = Shadow.colors
    Row(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
            .clip(ShadowShapes.Banner)
            .background(colors.amberTint)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = ShadowIcons.Warning,
            contentDescription = null,
            tint = colors.amberText,
            modifier = Modifier
                .padding(top = 1.dp)
                .size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            if (searching) {
                Row(
                    modifier = Modifier
                        .padding(bottom = 6.dp)
                        .height(20.dp)
                        .clip(CircleShape)
                        .background(colors.amber)
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusDot(color = colors.onAmber, size = 6.dp, blink = true)
                    Text(text = "Happening now", style = TagText, color = colors.onAmber)
                }
            }
            Text(
                text = "While Auto searches, traffic goes outside the VPN.",
                style = Shadow.type.segment.copy(lineHeight = 20.sp),
                color = colors.amberText,
            )
            Text(
                text = "That covers tests, retries and route switches. To block it, turn on “Block connections " +
                    "without VPN” in Android’s VPN settings.",
                style = Shadow.type.bodyS,
                color = colors.ink2,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** Mono value color of a latency (meter bucket), ink-3 when unknown. */
internal fun latencyColor(colors: ShadowColors, latencyMs: Long?): Color = when (latencyLevel(latencyMs)) {
    LatencyLevel.Fast -> colors.toneText(StatusTone.Success)
    LatencyLevel.Medium -> colors.toneText(StatusTone.Progress)
    LatencyLevel.Slow -> colors.toneText(StatusTone.Error)
    LatencyLevel.Unknown -> colors.ink3
}

/** 1 dp rule along the top edge (rows inside a card). */
internal fun Modifier.topDivider(color: Color): Modifier = drawBehind {
    drawLine(
        color = color,
        start = Offset(0f, 0f),
        end = Offset(size.width, 0f),
        strokeWidth = 1.dp.toPx(),
    )
}

private val TagText
    @Composable get() = Shadow.type.label.copy(fontSize = 11.sp, lineHeight = 20.sp)

private val BigMono
    @Composable get() = Shadow.type.monoMedium.copy(fontSize = 20.sp, lineHeight = 24.sp)

private val Body14
    @Composable get() = Shadow.type.body.copy(fontSize = 14.sp, lineHeight = 20.sp)

// endregion

private val POOL_SHEET_HEIGHT = 700.dp
private val POOL_SHEET_TOP_GAP = 48.dp
private val POOL_SHEET_CHROME = 118.dp
private val POOL_BODY_MIN = 320.dp
private val HEADER_LIFT = 4.dp
private const val EMPTY_RING_ALPHA = 0.35f
private const val PICK_ROW_ALPHA = 0.12f
private const val STRIP_LINE_ALPHA = 0.16f
private const val STRIP_FILL_ALPHA = 0.45f
private const val DONE_LINE_ALPHA = 0.35f
private const val BREATH_MIN_ALPHA = 0.45f
private const val BREATH_MIN_SCALE = 0.9f
private const val BREATH_MAX_SCALE = 1.04f
