package com.stansful.sshvpnclient.ui.routes

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.stansful.sshvpnclient.domain.model.ProxyProfileSummary
import com.stansful.sshvpnclient.ui.designsystem.CheckboxTone
import com.stansful.sshvpnclient.ui.designsystem.DISABLED_ALPHA
import com.stansful.sshvpnclient.ui.designsystem.DayHairlineShadow
import com.stansful.sshvpnclient.ui.designsystem.LatencyLevel
import com.stansful.sshvpnclient.ui.designsystem.LatencyMeter
import com.stansful.sshvpnclient.ui.designsystem.MetaTag
import com.stansful.sshvpnclient.ui.designsystem.MetaTagSize
import com.stansful.sshvpnclient.ui.designsystem.ShadowCheckbox
import com.stansful.sshvpnclient.ui.designsystem.ShadowFocusIndication
import com.stansful.sshvpnclient.ui.designsystem.ShadowIcons
import com.stansful.sshvpnclient.ui.designsystem.StatusTone
import com.stansful.sshvpnclient.ui.designsystem.latencyLevel
import com.stansful.sshvpnclient.ui.designsystem.pressScale
import com.stansful.sshvpnclient.ui.designsystem.shadowClickable
import com.stansful.sshvpnclient.ui.designsystem.strokeIcon
import com.stansful.sshvpnclient.ui.theme.Shadow
import com.stansful.sshvpnclient.ui.theme.ShadowMotion
import com.stansful.sshvpnclient.ui.theme.ShadowShapes
import com.stansful.sshvpnclient.ui.theme.shadowTween
import kotlin.math.PI

/** Everything a route card shows besides the route itself. */
internal data class RouteCardFlags(
    val active: Boolean,
    val inUse: Boolean,
    val selecting: Boolean,
    val selected: Boolean,
)

/**
 * Library card (Routes.dc.html): name + status, mono endpoint, tags (transport · security, Manual,
 * Active, In use), pin toggle and "Route actions". Tap = [onClick], press and hold = [onLongClick].
 * While [RouteCardFlags.selecting] a sky checkbox slides in and the actions button hides.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun RouteCard(
    route: ProxyProfileSummary,
    state: RouteState,
    flags: RouteCardFlags,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPinChange: (Boolean) -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Shadow.colors
    val shape = ShadowShapes.Card
    val highlightActive = flags.active && !flags.selecting
    val border by animateColorAsState(
        targetValue = when {
            flags.selected -> colors.sky.copy(alpha = SELECTED_BORDER_ALPHA)
            highlightActive -> colors.amber
            else -> colors.line
        },
        animationSpec = shadowTween(CARD_COLOR_MS, ShadowMotion.Ease),
        label = "route-card-border",
    )
    val selectedFill by animateFloatAsState(
        targetValue = if (flags.selected) 1f else 0f,
        animationSpec = shadowTween(CARD_COLOR_MS, ShadowMotion.Ease),
        label = "route-card-selected",
    )
    val haptics = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val description = routeDescription(route, state, flags)
    Row(
        modifier = modifier
            .pressScale(interactionSource, CARD_PRESS_SCALE)
            .then(if (!colors.isDark) Modifier.dropShadow(shape, DayHairlineShadow) else Modifier)
            .clip(shape)
            .background(if (flags.selected || selectedFill > 0f) colors.bg else colors.surface1)
            .drawBehind {
                if (highlightActive) {
                    drawRect(
                        Brush.verticalGradient(
                            0f to colors.amber.copy(alpha = ACTIVE_GLOW_ALPHA),
                            ACTIVE_GLOW_STOP to colors.amber.copy(alpha = 0f),
                        ),
                    )
                }
                if (selectedFill > 0f) drawRect(colors.sky.copy(alpha = SELECTED_FILL_ALPHA * selectedFill))
            }
            .border(1.dp, border, shape)
            // The artboard's 1 px border sits outside the padding (CSS content-box).
            .padding(1.dp)
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.Top,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(shape)
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = ShadowFocusIndication,
                    onClickLabel = if (flags.selecting) null else "Make active",
                    onLongClickLabel = if (flags.selecting) null else "Select",
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongClick()
                    },
                    onClick = onClick,
                )
                .semantics(mergeDescendants = true) {
                    contentDescription = description
                    if (flags.selecting) {
                        role = Role.Checkbox
                        toggleableState = ToggleableState(flags.selected)
                    }
                }
                .padding(start = 16.dp, end = 4.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            SelectionSlot(visible = flags.selecting, checked = flags.selected)
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.heightIn(min = 22.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = route.name,
                        style = Shadow.type.titleS,
                        color = colors.ink1,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    RouteStateSlot(state)
                }
                Text(
                    text = route.endpointLine(),
                    style = Shadow.type.monoS,
                    color = colors.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
                FlowRow(
                    modifier = Modifier.padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    MetaTag(
                        text = route.transportLine(),
                        size = MetaTagSize.Compact,
                        uppercase = true,
                        container = colors.surface2,
                    )
                    if (route.isManual) ManualTag()
                    if (flags.active) {
                        MetaTag(
                            text = "Active",
                            tone = StatusTone.Progress,
                            icon = BoldCheck,
                            size = MetaTagSize.Compact,
                        )
                    }
                    if (flags.inUse) InUseTag()
                }
            }
        }
        // Pin and actions stack in the middle of the card (top-aligned pin alone while selecting).
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(top = 4.dp, bottom = 4.dp, end = 4.dp),
            verticalArrangement = if (flags.selecting) Arrangement.Top else Arrangement.Center,
        ) {
            PinToggle(pinned = route.isPinned, routeName = route.name, onPinChange = onPinChange)
            AnimatedVisibility(
                visible = !flags.selecting,
                enter = fadeIn(shadowTween(ShadowMotion.Swap)),
                exit = fadeOut(shadowTween(ShadowMotion.Small)),
            ) {
                PlainIconButton(
                    icon = ShadowIcons.More,
                    contentDescription = "Actions for ${route.name}",
                    tint = colors.ink3,
                    iconSize = 20.dp,
                    onClick = onMore,
                )
            }
        }
    }
}

private fun routeDescription(route: ProxyProfileSummary, state: RouteState, flags: RouteCardFlags): String {
    if (flags.selecting) {
        return route.name + (if (flags.selected) ", selected" else ", not selected") +
            (if (route.isPinned) ", pinned" else "")
    }
    return buildString {
        append(route.name).append(", ").append(state.spoken())
        append(if (flags.active) ", active route" else ", tap to make active")
        if (flags.inUse) append(", in use")
        if (route.isPinned) append(", pinned")
        if (route.isManual) append(", added manually")
    }
}

/** The 22 dp sky checkbox that slides in (34 dp with its gap) while selecting. */
@Composable
internal fun SelectionSlot(visible: Boolean, checked: Boolean) {
    AnimatedVisibility(
        visible = visible,
        enter = expandHorizontally(shadowTween(SLOT_MS)) + fadeIn(shadowTween(SLOT_FADE_MS)),
        exit = shrinkHorizontally(shadowTween(SLOT_MS)) + fadeOut(shadowTween(SLOT_FADE_MS)),
    ) {
        Box(modifier = Modifier.width(34.dp)) {
            ShadowCheckbox(checked = checked, onCheckedChange = null, tone = CheckboxTone.Sky)
        }
    }
}

/** Right side of the card's first line: meter, coral pill, "Not checked", "Outdated" or "Checking". */
@Composable
internal fun RouteStateSlot(state: RouteState, modifier: Modifier = Modifier) {
    val colors = Shadow.colors
    when (state) {
        is RouteState.Available -> RouteMeter(latencyMs = state.latencyMs, modifier = modifier)
        RouteState.Unavailable -> MetaTag("Unavailable", modifier, tone = StatusTone.Error, size = MetaTagSize.Large)
        RouteState.Unsupported -> MetaTag("Unsupported", modifier, tone = StatusTone.Error, size = MetaTagSize.Large)
        RouteState.NotChecked -> OutlinedPill("Not checked", modifier)
        RouteState.Outdated -> Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(ShadowIcons.Clock, contentDescription = null, tint = colors.coralText, modifier = Modifier.size(14.dp))
            Text("Outdated", style = TagLabel, color = colors.coralText)
        }
        RouteState.Checking -> Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RingSpinner(size = 14.dp, strokeWidth = 2.6f, color = colors.amber)
            Text("Checking", style = TagLabel, color = colors.amberText)
        }
    }
}

/** The meter colour of a latency as text ink: mint ≤ 200 ms (or unknown but available), amber ≤ 500, coral. */
@Composable
internal fun latencyColor(latencyMs: Long?): Color {
    val colors = Shadow.colors
    return when (latencyLevel(latencyMs)) {
        LatencyLevel.Fast, LatencyLevel.Unknown -> colors.mintText
        LatencyLevel.Medium -> colors.amberText
        LatencyLevel.Slow -> colors.coralText
    }
}

/** Latency meter; without a latency the bars stay off and the label reads "Available" in mint. */
@Composable
internal fun RouteMeter(latencyMs: Long?, modifier: Modifier = Modifier) {
    if (latencyMs != null) {
        LatencyMeter(latencyMs = latencyMs, modifier = modifier)
    } else {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LatencyMeter(latencyMs = null, showLabel = false)
            Text("Available", style = Shadow.type.monoMedium, color = Shadow.colors.mintText)
        }
    }
}

/** "Manual" tag: 20 dp, 1 dp line-2 outline around 8 dp padding, ink-2 11/14 600. */
@Composable
internal fun ManualTag() {
    val colors = Shadow.colors
    Box(
        modifier = Modifier
            .height(20.dp)
            .clip(CircleShape)
            .border(1.dp, colors.line2, CircleShape)
            .padding(horizontal = 8.dp + OUTLINE),
        contentAlignment = Alignment.Center,
    ) {
        Text("Manual", style = Shadow.type.overline.copy(letterSpacing = 0.em), color = colors.ink2)
    }
}

/** "In use" tag: 20 dp mint tint, 6 dp mint dot, 11/14 600. */
@Composable
private fun InUseTag() {
    val colors = Shadow.colors
    Row(
        modifier = Modifier
            .height(20.dp)
            .clip(CircleShape)
            .background(colors.mintTint)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(colors.mint),
        )
        Text("In use", style = Shadow.type.overline.copy(letterSpacing = 0.em), color = colors.mintText)
    }
}

/** 24 dp outlined status pill ("Not checked"): 1 dp line-2 outline around 10 dp padding, ink-3 12/600. */
@Composable
private fun OutlinedPill(text: String, modifier: Modifier = Modifier) {
    val colors = Shadow.colors
    Box(
        modifier = modifier
            .height(24.dp)
            .clip(CircleShape)
            .border(1.dp, colors.line2, CircleShape)
            .padding(horizontal = 10.dp + OUTLINE),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = TagLabel, color = colors.ink3, maxLines = 1)
    }
}

/**
 * 44 dp pin toggle. Phone cards: a 34 dp tile that turns sky when pinned; [tile] = false (tablet
 * rows): a bare 20 dp pin, filled when pinned.
 */
@Composable
internal fun PinToggle(
    pinned: Boolean,
    routeName: String,
    onPinChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    tile: Boolean = true,
) {
    val colors = Shadow.colors
    val tileFill by animateColorAsState(
        targetValue = if (pinned) colors.skyTint else colors.skyTint.copy(alpha = 0f),
        animationSpec = shadowTween(ShadowMotion.Small, ShadowMotion.Ease),
        label = "pin-tile",
    )
    val ink by animateColorAsState(
        targetValue = if (pinned) colors.skyText else colors.ink3,
        animationSpec = shadowTween(ShadowMotion.Small, ShadowMotion.Ease),
        label = "pin-ink",
    )
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(ShadowShapes.IconButton)
            .shadowClickable(remember { MutableInteractionSource() }) { onPinChange(!pinned) }
            .semantics {
                contentDescription = if (pinned) "Unpin $routeName" else "Pin $routeName"
                stateDescription = if (pinned) "Pinned" else "Not pinned"
                toggleableState = ToggleableState(pinned)
            },
        contentAlignment = Alignment.Center,
    ) {
        if (tile) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(ShadowShapes.Tile)
                    .background(tileFill),
                contentAlignment = Alignment.Center,
            ) {
                Icon(ShadowIcons.Pin, contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
            }
        } else {
            PinGlyph(pinned = pinned, tint = ink, size = 20.dp)
        }
    }
}

/** The pin icon; when [pinned] its head is filled with a 22 % wash of [tint]. */
@Composable
internal fun PinGlyph(pinned: Boolean, tint: Color, size: Dp, modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(size)) {
        if (pinned) {
            Icon(
                PinHead,
                contentDescription = null,
                tint = tint.copy(alpha = PIN_FILL_ALPHA),
                modifier = Modifier.size(size),
            )
        }
        Icon(ShadowIcons.Pin, contentDescription = null, tint = tint, modifier = Modifier.size(size))
    }
}

/** The filled head of the pin icon (for the pinned wash). */
private val PinHead: ImageVector by lazy {
    ImageVector.Builder("pin-head", 24.dp, 24.dp, 24f, 24f)
        .addPath(PathParser().parsePathString("M9 4h6l-1 5 3 3H7l3-3z").toNodes(), fill = SolidColor(Color.Black))
        .build()
}

/** Transparent 44 dp icon button with an explicit tint and icon size. */
@Composable
internal fun PlainIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Shadow.colors.ink2,
    container: Color = Color.Transparent,
    iconSize: Dp = 22.dp,
    size: Dp = 44.dp,
    shape: androidx.compose.ui.graphics.Shape = ShadowShapes.IconButton,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .size(size)
            .clip(shape)
            .background(container)
            .shadowClickable(remember { MutableInteractionSource() }, enabled = enabled, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/** Busy ring: a 16/41 amber arc over a line-2 track, one turn per 1.3 s (static when motion is reduced). */
@Composable
internal fun RingSpinner(
    size: Dp,
    strokeWidth: Float,
    color: Color,
    modifier: Modifier = Modifier,
    track: Color = Shadow.colors.line2,
    radius: Float = SPINNER_RADIUS,
) {
    // Read in the layer block: the turn redraws the layer instead of recomposing every frame.
    val angle = if (Shadow.reducedMotion) {
        null
    } else {
        rememberInfiniteTransition(label = "ring-spinner").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(ShadowMotion.Spinner, easing = LinearEasing), RepeatMode.Restart),
            label = "ring-angle",
        )
    }
    Canvas(modifier = modifier.size(size).graphicsLayer { rotationZ = angle?.value ?: 0f }) {
        val scale = this.size.minDimension / SPINNER_VIEWBOX
        val stroke = strokeWidth * scale
        val radiusPx = radius * scale
        val topLeft = Offset(center.x - radiusPx, center.y - radiusPx)
        val arcSize = Size(radiusPx * 2, radiusPx * 2)
        drawCircle(track, radius = radiusPx, style = Stroke(stroke))
        drawArc(
            color = color,
            startAngle = 0f,
            sweepAngle = SPINNER_ARC / (2f * PI.toFloat() * radius) * FULL_TURN_DEGREES,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
    }
}

/** A dot that blinks (1.2 s) while [blink] and motion is allowed. */
@Composable
internal fun Modifier.blinking(blink: Boolean): Modifier {
    if (!blink || Shadow.reducedMotion) return this
    val transition = rememberInfiniteTransition(label = "blink")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = BLINK_ALPHA,
        animationSpec = infiniteRepeatable(
            tween(ShadowMotion.Blink / 2, easing = ShadowMotion.EaseInOut),
            RepeatMode.Reverse,
        ),
        label = "blink-alpha",
    )
    return graphicsLayer { this.alpha = alpha }
}

/** "128 routes · 41 available" with mono numbers (available in mint). */
@Composable
internal fun countsLine(total: Int, available: Int, numberStyle: TextStyle) = buildAnnotatedString {
    val number = SpanStyle(
        fontFamily = numberStyle.fontFamily,
        fontSize = numberStyle.fontSize,
        fontWeight = numberStyle.fontWeight,
    )
    withStyle(number) { append(total.toString()) }
    append(if (total == 1) " route · " else " routes · ")
    withStyle(number.copy(color = Shadow.colors.mintText)) { append(available.toString()) }
    append(" available")
}

/** 13/600 status words next to icons ("Outdated", "Checking"). */
internal val TagLabel: TextStyle
    @Composable get() = Shadow.type.caption.copy(fontWeight = FontWeight.SemiBold)

/** 14/20 600 (the bar's route name). */
internal val RouteLineText: TextStyle
    @Composable get() = Shadow.type.segment.copy(lineHeight = 20.sp)

/** The bold tick of the "Active" tag. */
internal val BoldCheck: ImageVector by lazy {
    strokeIcon("bold-check", "M5 12.5l4.5 4.5L19 7.5", strokeWidth = BOLD_TICK_STROKE)
}

/** The 2-stroke icons of the connection bar's action button. */
internal val BarPower: ImageVector by lazy {
    strokeIcon("bar-power", "M12 3v8", "M6.3 6.8a8 8 0 1 0 11.4 0", strokeWidth = BAR_ICON_STROKE)
}
internal val BarDownload: ImageVector by lazy {
    strokeIcon("bar-download", "M12 4v11", "M7.5 10.5L12 15l4.5-4.5", "M5 19.5h14", strokeWidth = BAR_ICON_STROKE)
}
internal val BarRefresh: ImageVector by lazy {
    strokeIcon(
        "bar-refresh",
        "M20 11a8 8 0 0 0-14.5-4.5L4 8",
        "M4 4v4h4",
        "M4 13a8 8 0 0 0 14.5 4.5L20 16",
        "M20 20v-4h-4",
        strokeWidth = BAR_ICON_STROKE,
    )
}

/**
 * CSS-like negative margins: the element keeps its size but takes up [vertical] less room above and
 * below and [end] less at its end (where it then overhangs).
 */
internal fun Modifier.negativeMargins(vertical: Dp = 0.dp, end: Dp = 0.dp): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
        val dy = vertical.roundToPx()
        val dx = end.roundToPx()
        val width = (placeable.width - dx).coerceAtLeast(0)
        layout(width, (placeable.height - dy * 2).coerceAtLeast(0)) {
            placeable.placeRelative(0, -dy)
        }
    }

/** A CSS border sits outside the padding: outlined boxes pad by this much more than the artboard says. */
internal val OUTLINE = 1.dp

private const val CARD_PRESS_SCALE = 0.985f
private const val CARD_COLOR_MS = 300
private const val ACTIVE_GLOW_ALPHA = 0.07f
private const val ACTIVE_GLOW_STOP = 0.7f
private const val SELECTED_FILL_ALPHA = 0.08f
private const val SELECTED_BORDER_ALPHA = 0.42f
private const val SLOT_MS = 320
private const val SLOT_FADE_MS = 220
private const val SPINNER_VIEWBOX = 24f
private const val SPINNER_RADIUS = 9f
private const val SPINNER_ARC = 16f
private const val FULL_TURN_DEGREES = 360f
private const val BLINK_ALPHA = 0.35f
private const val PIN_FILL_ALPHA = 0.22f
private const val BOLD_TICK_STROKE = 2.4f
private const val BAR_ICON_STROKE = 2f
