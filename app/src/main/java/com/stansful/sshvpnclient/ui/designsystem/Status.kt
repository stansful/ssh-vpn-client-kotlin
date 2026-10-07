package com.stansful.sshvpnclient.ui.designsystem

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.stansful.sshvpnclient.ui.theme.Shadow
import com.stansful.sshvpnclient.ui.theme.ShadowColors
import com.stansful.sshvpnclient.ui.theme.ShadowMotion
import com.stansful.sshvpnclient.ui.theme.shadowTween

/** State color semantics shared by pills, tags, chips, banners and dots. */
enum class StatusTone {
    /** Off / idle: ink-2 on surface-3. */
    Neutral,

    /** Starting, connecting, testing, reconnecting, waiting: amber. */
    Progress,

    /** Connected, available, fast: mint. */
    Success,

    /** Error, unavailable, unsupported, outdated: coral. */
    Error,

    /** Pinned, bulk-selected, hints: sky. */
    Info,
}

/** Readable text/icon color of a tone. */
fun ShadowColors.toneText(tone: StatusTone): Color = when (tone) {
    StatusTone.Neutral -> ink2
    StatusTone.Progress -> amberText
    StatusTone.Success -> mintText
    StatusTone.Error -> coralText
    StatusTone.Info -> skyText
}

/** Fill color of a tone (dots, bars, rings). */
fun ShadowColors.toneFill(tone: StatusTone): Color = when (tone) {
    StatusTone.Neutral -> ink3
    StatusTone.Progress -> amber
    StatusTone.Success -> mint
    StatusTone.Error -> coral
    StatusTone.Info -> sky
}

/** Tinted background of a tone. */
fun ShadowColors.toneTint(tone: StatusTone): Color = when (tone) {
    StatusTone.Neutral -> surface3
    StatusTone.Progress -> amberTint
    StatusTone.Success -> mintTint
    StatusTone.Error -> coralTint
    StatusTone.Info -> skyTint
}

/**
 * 28 dp status pill: tint fill, 8 dp dot (or [icon]) and 13/600 label. Colors crossfade in 400 ms,
 * the label swaps with a 6 dp rise; the dot blinks while [blink] (default: Progress tone).
 */
@Composable
fun StatusPill(
    label: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    blink: Boolean = tone == StatusTone.Progress,
) {
    val colors = Shadow.colors
    val container by animateColorAsState(
        targetValue = colors.toneTint(tone),
        animationSpec = shadowTween(ShadowMotion.ColorFade, ShadowMotion.Ease),
        label = "pill-container",
    )
    val content by animateColorAsState(
        targetValue = colors.toneText(tone),
        animationSpec = shadowTween(ShadowMotion.ColorFade, ShadowMotion.Ease),
        label = "pill-content",
    )
    Row(
        modifier = modifier
            .height(28.dp)
            .clip(CircleShape)
            .background(container)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(if (icon != null) 6.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(14.dp))
        } else {
            StatusDot(color = content, blink = blink)
        }
        SwapText(text = label, style = Shadow.type.label, color = content)
    }
}

/** 8 dp state dot; blinks (opacity 1 ↔ 0.35, 1.2 s) when [blink] and motion is allowed. */
@Composable
fun StatusDot(
    color: Color,
    modifier: Modifier = Modifier,
    blink: Boolean = false,
    size: Dp = 8.dp,
) {
    val dotModifier = modifier
        .size(size)
        .clip(CircleShape)
    if (blink && !Shadow.reducedMotion) {
        val transition = rememberInfiniteTransition(label = "dot-blink")
        val alpha by transition.animateFloat(
            initialValue = 1f,
            targetValue = BLINK_MIN_ALPHA,
            animationSpec = infiniteRepeatable(
                animation = tween(ShadowMotion.Blink / 2, easing = ShadowMotion.EaseInOut),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "dot-alpha",
        )
        Box(dotModifier.graphicsLayer { this.alpha = alpha }.background(color))
    } else {
        Box(dotModifier.background(color))
    }
}

/** Text that swaps with a fade + 6 dp rise (260 ms) whenever [text] changes. */
@Composable
fun SwapText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
) {
    val reduced = Shadow.reducedMotion
    val risePx = with(LocalDensity.current) { SWAP_RISE.roundToPx() }
    AnimatedContent(
        targetState = text,
        modifier = modifier,
        transitionSpec = {
            if (reduced) {
                fadeIn(tween(0)) togetherWith fadeOut(tween(0))
            } else {
                (
                    fadeIn(tween(ShadowMotion.Swap, easing = ShadowMotion.Standard)) +
                        slideInVertically(tween(ShadowMotion.Swap, easing = ShadowMotion.Standard)) { risePx }
                    ) togetherWith fadeOut(tween(ShadowMotion.Swap / 2))
            }
        },
        label = "swap-text",
    ) { value ->
        Text(
            text = value,
            style = style,
            color = color,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Latency bucket of the meter: ≤ 200 fast, 201–500 medium, > 500 slow, null unknown. */
enum class LatencyLevel { Fast, Medium, Slow, Unknown }

/** Buckets a latency in milliseconds (null or negative → Unknown). */
fun latencyLevel(latencyMs: Long?): LatencyLevel = when {
    latencyMs == null || latencyMs < 0 -> LatencyLevel.Unknown
    latencyMs <= FAST_LATENCY_MS -> LatencyLevel.Fast
    latencyMs <= MEDIUM_LATENCY_MS -> LatencyLevel.Medium
    else -> LatencyLevel.Slow
}

/** "86 ms" or "—". */
fun formatLatency(latencyMs: Long?): String =
    if (latencyMs == null || latencyMs < 0) "—" else "$latencyMs ms"

/**
 * Three-bar latency meter (4 dp bars, 6/10/14 dp, 2 dp gap) + mono 13/500 value.
 * Bars grow in on first show and recolor in 400 ms. [label] overrides the text (e.g. "Unavailable").
 */
@Composable
fun LatencyMeter(
    latencyMs: Long?,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
    label: String? = null,
) {
    val colors = Shadow.colors
    val level = latencyLevel(latencyMs)
    val lit = when (level) {
        LatencyLevel.Fast -> 3
        LatencyLevel.Medium -> 2
        LatencyLevel.Slow -> 1
        LatencyLevel.Unknown -> 0
    }
    val tone = when (level) {
        LatencyLevel.Fast -> StatusTone.Success
        LatencyLevel.Medium -> StatusTone.Progress
        LatencyLevel.Slow -> StatusTone.Error
        LatencyLevel.Unknown -> StatusTone.Neutral
    }
    val text = label ?: formatLatency(latencyMs)
    val labelColor by animateColorAsState(
        targetValue = if (level == LatencyLevel.Unknown) colors.ink3 else colors.toneText(tone),
        animationSpec = shadowTween(ShadowMotion.ColorFade, ShadowMotion.Ease),
        label = "latency-label",
    )
    Row(
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = "Latency $text" },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.height(14.dp),
        ) {
            LATENCY_BAR_HEIGHTS.forEachIndexed { index, height ->
                LatencyBar(
                    height = height.dp,
                    color = if (index < lit) colors.toneFill(tone) else colors.line2,
                    track = colors.line2,
                    delayMillis = index * BAR_STAGGER_MS,
                )
            }
        }
        if (showLabel) {
            Text(text = text, style = Shadow.type.monoMedium, color = labelColor, maxLines = 1)
        }
    }
}

@Composable
private fun LatencyBar(height: Dp, color: Color, track: Color, delayMillis: Int) {
    val reduced = Shadow.reducedMotion
    val grow = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!reduced) {
            grow.animateTo(1f, tween(ShadowMotion.ColorFade, delayMillis, ShadowMotion.Standard))
        }
    }
    val fill by animateColorAsState(
        targetValue = color,
        animationSpec = shadowTween(ShadowMotion.ColorFade, ShadowMotion.Ease),
        label = "latency-bar",
    )
    Box(
        modifier = Modifier
            .width(4.dp)
            .height(height)
            .drawBehind {
                val radius = CornerRadius(2.dp.toPx())
                drawRoundRect(color = track, cornerRadius = radius)
                val filled = size.height * grow.value
                drawRoundRect(
                    color = fill,
                    topLeft = Offset(0f, size.height - filled),
                    size = Size(size.width, filled),
                    cornerRadius = radius,
                )
            },
    )
}

/**
 * 4 dp progress bar on a line-2 track. [progress] in 0..1, or null for indeterminate (a sliding
 * segment; a static full-width fill at 45 % opacity under reduced motion). Fill is amber, mint when
 * [complete]; width changes animate over 400 ms.
 */
@Composable
fun ShadowProgressBar(
    progress: Float?,
    modifier: Modifier = Modifier,
    complete: Boolean = false,
    color: Color = Color.Unspecified,
) {
    val colors = Shadow.colors
    val fillColor by animateColorAsState(
        targetValue = when {
            color != Color.Unspecified -> color
            complete -> colors.mint
            else -> colors.amber
        },
        animationSpec = shadowTween(ShadowMotion.ColorFade, ShadowMotion.Ease),
        label = "progress-color",
    )
    val track = colors.line2
    val barModifier = modifier
        .fillMaxWidth()
        .height(4.dp)
        .clip(CircleShape)
    if (progress != null) {
        val animated by animateFloatAsState(
            targetValue = progress.coerceIn(0f, 1f),
            animationSpec = shadowTween(ShadowMotion.ColorFade),
            label = "progress-value",
        )
        Box(
            barModifier.drawBehind {
                drawRect(track)
                drawRoundRect(
                    color = fillColor,
                    size = Size(size.width * animated, size.height),
                    cornerRadius = CornerRadius(size.height / 2),
                )
            },
        )
    } else if (Shadow.reducedMotion) {
        Box(
            barModifier.drawBehind {
                drawRect(track)
                drawRect(fillColor.copy(alpha = REDUCED_INDETERMINATE_ALPHA))
            },
        )
    } else {
        IndeterminateBar(modifier = barModifier, track = track, fill = fillColor)
    }
}

@Composable
private fun IndeterminateBar(modifier: Modifier, track: Color, fill: Color) {
    val transition = rememberInfiniteTransition(label = "progress-indeterminate")
    val position by transition.animateFloat(
        initialValue = -INDETERMINATE_SEGMENT,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(INDETERMINATE_MS, easing = ShadowMotion.EaseInOut)),
        label = "progress-position",
    )
    Box(
        modifier.drawBehind {
            drawRect(track)
            drawRoundRect(
                color = fill,
                topLeft = Offset(size.width * position, 0f),
                size = Size(size.width * INDETERMINATE_SEGMENT, size.height),
                cornerRadius = CornerRadius(size.height / 2),
            )
        },
    )
}

/** Height preset of a [MetaTag]. */
enum class MetaTagSize(
    val height: Dp,
    val horizontalPadding: Dp,
    val iconSize: Dp,
) {
    /** 20 dp, padding 8, 11/14 600 — "Server mode", "Manual", "Active", protocol tags. */
    Compact(20.dp, 8.dp, 12.dp),

    /** 22 dp, padding 9, 12/16 600 — server card tags ("Password", "Host verified"). */
    Regular(22.dp, 9.dp, 14.dp),

    /** 24 dp, padding 10, 12/16 600 — route status tags ("Unavailable", "Not checked"). */
    Large(24.dp, 10.dp, 14.dp),
}

/**
 * Small metadata tag: Neutral = surface-3 + ink-2 (icon ink-3); other tones use their tint and text
 * color. [container] overrides the fill (e.g. surface-2 for protocol tags on cards); [outlined] draws a
 * 1 dp line-2 border on no fill ("Manual", "Not checked"); [dot] adds a 6 dp dot ("In use");
 * [uppercase] = 11/14 +0.06em caps (`VLESS`). With an icon or dot the start padding is 2 dp tighter.
 */
@Composable
fun MetaTag(
    text: String,
    modifier: Modifier = Modifier,
    tone: StatusTone = StatusTone.Neutral,
    icon: ImageVector? = null,
    size: MetaTagSize = MetaTagSize.Regular,
    uppercase: Boolean = false,
    outlined: Boolean = false,
    dot: Boolean = false,
    container: Color = Color.Unspecified,
) {
    val colors = Shadow.colors
    val content = colors.toneText(tone)
    val compactText = size == MetaTagSize.Compact || uppercase
    val style = when {
        uppercase -> Shadow.type.overline.copy(letterSpacing = 0.06.em)
        compactText -> Shadow.type.overline.copy(letterSpacing = 0.em)
        else -> Shadow.type.caption.copy(fontWeight = Shadow.type.label.fontWeight)
    }
    val fill = when {
        container != Color.Unspecified -> container
        outlined -> Color.Transparent
        else -> colors.toneTint(tone)
    }
    val shape = CircleShape
    val leadingGap = if (icon != null || dot) TAG_LEADING_INSET else 0.dp
    Row(
        modifier = modifier
            .height(size.height)
            .clip(shape)
            .background(fill)
            .then(if (outlined) Modifier.border(1.dp, colors.line2, shape) else Modifier)
            .padding(start = size.horizontalPadding - leadingGap, end = size.horizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            icon != null -> Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (tone == StatusTone.Neutral) colors.ink3 else content,
                modifier = Modifier.size(size.iconSize),
            )
            dot -> StatusDot(color = content, size = TAG_DOT)
        }
        Text(
            text = if (uppercase) text.uppercase() else text,
            style = style,
            color = if (outlined && tone == StatusTone.Neutral) colors.ink3 else content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Small busy spinner: 90° arc over a 25 % track, one turn per 1.3 s (static under reduced motion). */
@Composable
fun ShadowSpinner(
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current,
    size: Dp = 16.dp,
) {
    // Read in the layer block: the turn redraws the layer instead of recomposing every frame.
    val angle = if (Shadow.reducedMotion) {
        null
    } else {
        rememberInfiniteTransition(label = "spinner").animateFloat(
            initialValue = 0f,
            targetValue = FULL_TURN,
            animationSpec = infiniteRepeatable(tween(ShadowMotion.Spinner, easing = LinearEasing)),
            label = "spinner-angle",
        )
    }
    Canvas(
        modifier = modifier
            .size(size)
            .graphicsLayer { rotationZ = angle?.value ?: 0f },
    ) {
        val stroke = this.size.minDimension * SPINNER_STROKE_RATIO
        val inset = this.size.minDimension * SPINNER_INSET_RATIO
        val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
        drawArc(
            color = color.copy(alpha = SPINNER_TRACK_ALPHA),
            startAngle = 0f,
            sweepAngle = FULL_TURN,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = stroke),
        )
        drawArc(
            color = color,
            startAngle = -QUARTER_TURN,
            sweepAngle = QUARTER_TURN,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

private val LATENCY_BAR_HEIGHTS = listOf(6, 10, 14)
private const val BAR_STAGGER_MS = 70
private const val FAST_LATENCY_MS = 200L
private const val MEDIUM_LATENCY_MS = 500L
private const val BLINK_MIN_ALPHA = 0.35f
private const val REDUCED_INDETERMINATE_ALPHA = 0.45f
private const val INDETERMINATE_SEGMENT = 0.3f
private const val INDETERMINATE_MS = 1400
private const val FULL_TURN = 360f
private const val QUARTER_TURN = 90f
private const val SPINNER_STROKE_RATIO = 2f / 24f
private const val SPINNER_INSET_RATIO = 3f / 24f
private const val SPINNER_TRACK_ALPHA = 0.25f
private val SWAP_RISE = 6.dp
private val TAG_LEADING_INSET = 2.dp
private val TAG_DOT = 6.dp
