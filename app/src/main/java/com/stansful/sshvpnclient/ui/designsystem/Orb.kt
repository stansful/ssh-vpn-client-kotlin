package com.stansful.sshvpnclient.ui.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.stansful.sshvpnclient.ui.theme.Shadow
import com.stansful.sshvpnclient.ui.theme.ShadowColors
import com.stansful.sshvpnclient.ui.theme.ShadowMotion
import com.stansful.sshvpnclient.ui.theme.shadowTween
import kotlin.math.hypot

/** What the connection orb shows. */
@Immutable
sealed interface OrbState {
    /** Idle: neutral ring, "Connect". */
    data object Off : OrbState

    /** Connecting without a measurable fraction: amber spinner + breathing halo. */
    data object Connecting : OrbState

    /** Connecting with a known fraction (e.g. routes tested): amber arc fills to [fraction] (0..1). */
    data class Progress(val fraction: Float) : OrbState

    /** Waiting to retry: calm (slow-breathing) amber halo, half-alpha amber arc at [fraction] of the wait. */
    data class Waiting(val fraction: Float) : OrbState

    /** Connected: mint ring, steady mint halo, one-shot ripple on entry. */
    data object Connected : OrbState

    /** Reconnecting: amber spinner over a faint mint ring, breathing amber halo. */
    data object Reconnecting : OrbState

    /** Disconnecting: dimmed ink, no ring or halo. */
    data object Stopping : OrbState

    /** Failed: coral border and halo, coral-tinted track, one shake on entry. */
    data object Error : OrbState

    /** Not available: the Off look at 40 % opacity, not clickable. */
    data object Disabled : OrbState
}

/**
 * Geometry of a [ConnectOrb]: the button, the ring around it, the halo and the label. [Phone] is
 * Main.dc.html, [Tablet] is TabletHome.dc.html (the label stays a real 16 sp, not a scaled 15).
 */
@Immutable
data class OrbSize(
    /** The round button. */
    val orb: Dp,
    /** The square the track ring, progress arc and spinner are drawn in (= the component's size). */
    val ringBox: Dp,
    /** Radius of the 4 dp track ring (center of the stroke). */
    val ringRadius: Dp,
    /** The radial halo behind everything (drawn beyond the component's bounds). */
    val halo: Dp,
    val icon: Dp,
    /** Between the icon and the label. */
    val gap: Dp,
    val labelSize: TextUnit,
    val labelLineHeight: TextUnit,
    /** The button's drop shadow (offset, blur, connected ring spread) relative to the phone's. */
    val shadowScale: Float,
) {
    companion object {
        /** Main.dc.html: 176 dp orb, 232 dp ring (r 108), 300 dp halo, 40 dp icon, 15/600 label. */
        val Phone = OrbSize(
            orb = 176.dp,
            ringBox = 232.dp,
            ringRadius = 108.dp,
            halo = 300.dp,
            icon = 40.dp,
            gap = 10.dp,
            labelSize = 15.sp,
            labelLineHeight = 20.sp,
            shadowScale = 1f,
        )

        /** TabletHome.dc.html: 220 dp orb, 292 dp ring (r 138), 400 dp halo, 48 dp icon, 16/20 600 label. */
        val Tablet = OrbSize(
            orb = 220.dp,
            ringBox = 292.dp,
            ringRadius = 138.dp,
            halo = 400.dp,
            icon = 48.dp,
            gap = 12.dp,
            labelSize = 16.sp,
            labelLineHeight = 20.sp,
            shadowScale = 1.2f,
        )
    }
}

/**
 * The connection orb of Home (Main.dc.html): 176 dp radial-gradient button with a 1.5 dp state border,
 * 40 dp power icon and a 15/600 [label] (Connect / Stop / Disconnect), inside a 232 dp track ring with
 * a progress arc, a spinner arc while connecting and a 300 dp halo. The component measures
 * [OrbSize.ringBox]; the halo draws beyond its bounds (34 dp on the phone), so leave room (Home uses a
 * 286 dp tall slot). [size] = [OrbSize.Tablet] for the two-pane Home. Respects reduced motion: no
 * spinner/breathing/ripple/shake, the connecting arc shows full at 45 % instead.
 */
@Composable
fun ConnectOrb(
    state: OrbState,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String = label,
    enabled: Boolean = state != OrbState.Disabled,
    size: OrbSize = OrbSize.Phone,
) {
    val colors = Shadow.colors
    val reduced = Shadow.reducedMotion
    val visual = orbVisual(state, colors, reduced)
    val effects = rememberOrbEffects(state, reduced)
    Box(
        modifier = modifier
            .size(size.ringBox)
            .graphicsLayer {
                // ModulateAlpha fades each draw call instead of an offscreen layer, so the halo is
                // not clipped into a visible square around the disabled orb.
                alpha = if (state == OrbState.Disabled) DISABLED_ALPHA else 1f
                compositingStrategy = CompositingStrategy.ModulateAlpha
            },
        contentAlignment = Alignment.Center,
    ) {
        OrbHalo(
            color = visual.halo,
            visible = visual.haloVisible,
            breathing = visual.breathing && !reduced,
            periodMillis = visual.breathPeriod,
            diameter = size.halo,
        )
        val ripple = effects.ripple.value
        if (ripple > 0f && ripple < 1f) {
            Box(
                Modifier
                    .requiredSize(size.ringBox)
                    .graphicsLayer {
                        val scale = RIPPLE_START_SCALE + (RIPPLE_END_SCALE - RIPPLE_START_SCALE) * ripple
                        scaleX = scale
                        scaleY = scale
                        alpha = RIPPLE_START_ALPHA * (1f - ripple)
                    }
                    .border(2.dp, colors.mint, CircleShape),
            )
        }
        OrbRing(visual = visual, state = state, orbSize = size)
        if (visual.spinner && !reduced) OrbSpinner(color = colors.amber, orbSize = size)
        OrbButton(
            visual = visual,
            orbSize = size,
            label = label,
            contentDescription = contentDescription,
            enabled = enabled,
            shakeOffset = { effects.shake.value },
            onClick = onClick,
        )
    }
}

@Immutable
private data class OrbVisual(
    val border: Color,
    val ink: Color,
    val icon: Color,
    val halo: Color,
    val haloVisible: Boolean,
    val breathing: Boolean,
    val breathPeriod: Int,
    val track: Color,
    val arc: Color,
    val arcFraction: Float,
    val spinner: Boolean,
    val shadowRing: Color,
    val shadow: Color,
    val shadowOffsetY: Float,
    val shadowBlur: Float,
)

private class OrbEffects(val ripple: Animatable<Float, *>, val shake: Animatable<Float, *>)

@Composable
private fun rememberOrbEffects(state: OrbState, reduced: Boolean): OrbEffects {
    val effects = remember { OrbEffects(Animatable(0f), Animatable(0f)) }
    val previous = remember { arrayOf(state) }
    LaunchedEffect(state) {
        val before = previous[0]
        previous[0] = state
        if (state != OrbState.Connected) effects.ripple.snapTo(0f)
        if (state != OrbState.Error) effects.shake.snapTo(0f)
        if (reduced || before == state) return@LaunchedEffect
        if (state == OrbState.Connected && before != OrbState.Connected) {
            effects.ripple.snapTo(0f)
            effects.ripple.animateTo(1f, tween(ShadowMotion.Ripple, easing = ShadowMotion.Standard))
        }
        if (state == OrbState.Error && before != OrbState.Error) {
            effects.shake.snapTo(0f)
            effects.shake.animateTo(
                targetValue = 0f,
                animationSpec = keyframes {
                    durationMillis = ShadowMotion.Shake
                    -6f at ShadowMotion.Shake / 5 using ShadowMotion.ShakeEasing
                    5f at ShadowMotion.Shake * 2 / 5 using ShadowMotion.ShakeEasing
                    -3f at ShadowMotion.Shake * 3 / 5 using ShadowMotion.ShakeEasing
                    2f at ShadowMotion.Shake * 4 / 5 using ShadowMotion.ShakeEasing
                },
            )
        }
    }
    return effects
}

private fun orbVisual(state: OrbState, colors: ShadowColors, reduced: Boolean): OrbVisual {
    val night = colors.isDark
    // Day shadows are tinted with the palette's ink / amber / mint, so custom palettes keep their hues.
    val calmShadow = if (night) Color(0x59000000) else colors.ink1.copy(alpha = DAY_CALM_SHADOW_ALPHA)
    val amberHalo = colors.amber.copy(alpha = if (night) 0.22f else 0.20f)
    val base = OrbVisual(
        border = colors.line2,
        ink = colors.ink1,
        icon = colors.ink1,
        halo = amberHalo,
        haloVisible = false,
        breathing = false,
        breathPeriod = ShadowMotion.Breathe,
        track = colors.orbTrack,
        arc = colors.mint,
        arcFraction = 0f,
        spinner = false,
        shadowRing = Color.Transparent,
        shadow = calmShadow,
        shadowOffsetY = if (night) 18f else 16f,
        shadowBlur = if (night) 50f else 40f,
    )
    val busy = base.copy(
        border = colors.amber,
        ink = colors.amberText,
        icon = colors.amberText,
        haloVisible = true,
        breathing = true,
        arc = colors.amber,
        shadowRing = if (night) Color.Transparent else colors.amber.copy(alpha = 0.08f),
        shadow = if (night) calmShadow else colors.amberText.copy(alpha = DAY_BUSY_SHADOW_ALPHA),
        shadowOffsetY = 18f,
        shadowBlur = if (night) 50f else 46f,
    )
    return when (state) {
        OrbState.Off, OrbState.Disabled -> base
        OrbState.Connecting -> if (reduced) {
            busy.copy(arc = colors.amber.copy(alpha = REDUCED_ARC_ALPHA), arcFraction = 1f)
        } else {
            busy.copy(spinner = true)
        }
        is OrbState.Progress -> busy.copy(arcFraction = state.fraction.coerceIn(0f, 1f))
        is OrbState.Waiting -> busy.copy(
            halo = colors.amber.copy(alpha = if (night) 0.14f else 0.12f),
            // HomeStates `.calm`: the same breathing, slower (2.4 s).
            breathPeriod = ShadowMotion.BreatheCalm,
            arc = colors.amber.copy(alpha = 0.5f),
            arcFraction = state.fraction.coerceIn(0f, 1f),
        )
        OrbState.Reconnecting -> busy.copy(
            track = colors.mint.copy(alpha = 0.26f),
            spinner = !reduced,
            arc = colors.amber.copy(alpha = REDUCED_ARC_ALPHA),
            arcFraction = if (reduced) 1f else 0f,
        )
        OrbState.Connected -> base.copy(
            border = colors.mintText,
            ink = colors.mintText,
            icon = colors.mintText,
            halo = colors.mint.copy(alpha = if (night) 0.20f else 0.18f),
            haloVisible = true,
            arcFraction = 1f,
            shadowRing = colors.mint.copy(alpha = if (night) 0.05f else 0.08f),
            shadow = if (night) {
                colors.mint.copy(alpha = 0.22f)
            } else {
                colors.mintText.copy(alpha = DAY_CONNECTED_SHADOW_ALPHA)
            },
            shadowOffsetY = if (night) 22f else 20f,
            shadowBlur = if (night) 70f else 54f,
        )
        OrbState.Stopping -> base.copy(ink = colors.ink3, icon = colors.ink3)
        // HomeStates: coral border, ring and power icon; the "Connect" label stays ink-1.
        OrbState.Error -> base.copy(
            border = colors.coral,
            icon = colors.coralText,
            halo = colors.coral.copy(alpha = if (night) 0.15f else 0.12f),
            haloVisible = true,
            track = colors.coral.copy(alpha = 0.28f),
            arc = colors.coral,
            shadowRing = colors.coral.copy(alpha = 0.05f),
        )
    }
}

@Composable
private fun OrbHalo(color: Color, visible: Boolean, breathing: Boolean, periodMillis: Int, diameter: Dp) {
    val haloColor by animateColorAsState(color, shadowTween(ShadowMotion.Glow, ShadowMotion.Ease), label = "halo")
    val opacity by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = shadowTween(ShadowMotion.Glow, ShadowMotion.Ease),
        label = "halo-opacity",
    )
    // Read in the layer block: the breath redraws the layer instead of recomposing every frame.
    val breathState = if (breathing) breathValue(periodMillis) else null
    Box(
        Modifier
            .requiredSize(diameter)
            .graphicsLayer {
                val breath = breathState?.value ?: 1f
                alpha = opacity * (BREATH_MIN_ALPHA + (1f - BREATH_MIN_ALPHA) * breath)
                val scale = if (breathing) BREATH_MIN_SCALE + (BREATH_MAX_SCALE - BREATH_MIN_SCALE) * breath else 1f
                scaleX = scale
                scaleY = scale
            }
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to haloColor,
                        HALO_FADE_STOP to haloColor.copy(alpha = 0f),
                        center = center,
                        // CSS `radial-gradient(circle, …)` on the 300 dp square: farthest corner = r·√2.
                        radius = size.minDimension / 2f * SQRT_2,
                    ),
                )
            },
    )
}

@Composable
private fun breathValue(periodMillis: Int): State<Float> {
    val transition = rememberInfiniteTransition(label = "halo-breath")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(periodMillis / 2, easing = ShadowMotion.EaseInOut),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "halo-breath-value",
    )
}

@Composable
private fun OrbRing(visual: OrbVisual, state: OrbState, orbSize: OrbSize) {
    val colorSpec = shadowTween<Color>(ShadowMotion.ColorFade, ShadowMotion.Ease)
    val track by animateColorAsState(visual.track, colorSpec, label = "orb-track")
    val arc by animateColorAsState(visual.arc, colorSpec, label = "orb-arc")
    val fraction by animateFloatAsState(
        targetValue = visual.arcFraction,
        animationSpec = if (state == OrbState.Connected) {
            shadowTween(ShadowMotion.Glow, ShadowMotion.Standard)
        } else {
            shadowTween(ARC_STEP_MS, LinearEasing)
        },
        label = "orb-arc-fraction",
    )
    Canvas(Modifier.requiredSize(orbSize.ringBox)) {
        val stroke = RING_STROKE.toPx()
        val radius = orbSize.ringRadius.toPx()
        drawCircle(color = track, radius = radius, style = Stroke(width = stroke))
        if (fraction > 0f) drawRingArc(arc, -QUARTER_TURN, FULL_TURN * fraction, radius, stroke)
    }
}

@Composable
private fun OrbSpinner(color: Color, orbSize: OrbSize) {
    val transition = rememberInfiniteTransition(label = "orb-spinner")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = FULL_TURN,
        animationSpec = infiniteRepeatable(tween(ShadowMotion.Spinner, easing = LinearEasing)),
        label = "orb-spinner-angle",
    )
    Canvas(
        Modifier
            .requiredSize(orbSize.ringBox)
            .graphicsLayer { rotationZ = angle },
    ) {
        drawRingArc(color, -QUARTER_TURN, SPINNER_SWEEP, orbSize.ringRadius.toPx(), RING_STROKE.toPx())
    }
}

private fun DrawScope.drawRingArc(color: Color, start: Float, sweep: Float, radius: Float, stroke: Float) {
    drawArc(
        color = color,
        startAngle = start,
        sweepAngle = sweep,
        useCenter = false,
        topLeft = Offset(center.x - radius, center.y - radius),
        size = Size(radius * 2, radius * 2),
        style = Stroke(width = stroke, cap = StrokeCap.Round),
    )
}

@Composable
private fun OrbButton(
    visual: OrbVisual,
    orbSize: OrbSize,
    label: String,
    contentDescription: String,
    enabled: Boolean,
    shakeOffset: () -> Float,
    onClick: () -> Unit,
) {
    val colors = Shadow.colors
    val colorSpec = shadowTween<Color>(ShadowMotion.ColorFade, ShadowMotion.Ease)
    val border by animateColorAsState(visual.border, colorSpec, label = "orb-border")
    val ink by animateColorAsState(visual.ink, colorSpec, label = "orb-ink")
    val iconInk by animateColorAsState(visual.icon, colorSpec, label = "orb-icon")
    val ring by animateColorAsState(visual.shadowRing, shadowTween(ShadowMotion.Glow), label = "orb-shadow-ring")
    val glow by animateColorAsState(visual.shadow, shadowTween(ShadowMotion.Glow), label = "orb-shadow")
    val blur by animateFloatAsState(visual.shadowBlur, shadowTween(ShadowMotion.Glow), label = "orb-shadow-blur")
    val offsetY by animateFloatAsState(visual.shadowOffsetY, shadowTween(ShadowMotion.Glow), label = "orb-shadow-y")
    val interactionSource = remember { MutableInteractionSource() }
    val night = colors.isDark
    val fillTop = colors.orbFillTop
    val fillBottom = colors.orbFillBottom
    Box(
        modifier = Modifier
            .offset { IntOffset(shakeOffset().dp.roundToPx(), 0) }
            .pressScale(interactionSource, ShadowMotion.OrbPressScale)
            .size(orbSize.orb)
            .then(
                if (ring.alpha > 0f) {
                    // CSS `0 0 0 10px` on a circle: a wider circle. (A spread drop shadow keeps the
                    // circle's corner radius, which turns the ring into a rounded square.)
                    Modifier.drawBehind {
                        val spread = (if (night) 10.dp else 8.dp).toPx() * orbSize.shadowScale
                        drawCircle(color = ring, radius = size.minDimension / 2f + spread)
                    }
                } else {
                    Modifier
                },
            )
            .dropShadow(CircleShape) {
                radius = blur.dp.toPx() * orbSize.shadowScale
                offset = Offset(0f, offsetY.dp.toPx() * orbSize.shadowScale)
                color = glow
            }
            .clip(CircleShape)
            .drawBehind {
                val stops = if (night) {
                    arrayOf(0f to fillTop, NIGHT_FILL_STOP to fillBottom, 1f to fillBottom)
                } else {
                    arrayOf(0f to fillTop, DAY_FILL_STOP to fillTop, 1f to fillBottom)
                }
                val gradientCenter = Offset(size.width / 2f, size.height * if (night) 0.30f else 0.28f)
                drawRect(
                    Brush.radialGradient(
                        *stops,
                        center = gradientCenter,
                        // CSS `radial-gradient(circle at …)` sizes to the farthest corner.
                        radius = hypot(size.width / 2f, size.height - gradientCenter.y),
                    ),
                )
            }
            .border(1.5.dp, border, CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = ShadowRoundFocusIndication,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.clearAndSetSemantics {},
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(orbSize.gap),
        ) {
            Icon(ShadowIcons.Power, contentDescription = null, tint = iconInk, modifier = Modifier.size(orbSize.icon))
            SwapText(
                text = label,
                style = Shadow.type.button.copy(
                    fontSize = orbSize.labelSize,
                    lineHeight = orbSize.labelLineHeight,
                    letterSpacing = 0.01.em,
                ),
                color = ink,
            )
        }
    }
}

private val RING_STROKE = 4.dp
private const val FULL_TURN = 360f
private const val QUARTER_TURN = 90f
private const val SPINNER_SWEEP = 110f / 678.58f * 360f
private const val ARC_STEP_MS = 260
private const val REDUCED_ARC_ALPHA = 0.45f
private const val HALO_FADE_STOP = 0.66f
private const val SQRT_2 = 1.4142135f
private const val BREATH_MIN_ALPHA = 0.45f
private const val BREATH_MIN_SCALE = 0.9f
private const val BREATH_MAX_SCALE = 1.04f
private const val RIPPLE_START_SCALE = 0.78f
private const val RIPPLE_END_SCALE = 1.45f
private const val RIPPLE_START_ALPHA = 0.7f
private const val NIGHT_FILL_STOP = 0.72f
private const val DAY_FILL_STOP = 0.38f

/** Day orb shadows (HomeLight.dc.html): ink-1 at 0x1A, amber text at 0x24, mint text at 0x38. */
private const val DAY_CALM_SHADOW_ALPHA = 0x1A / 255f
private const val DAY_BUSY_SHADOW_ALPHA = 0x24 / 255f
private const val DAY_CONNECTED_SHADOW_ALPHA = 0x38 / 255f
