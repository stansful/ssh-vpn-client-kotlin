package com.stansful.sshvpnclient.ui.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stansful.sshvpnclient.ui.theme.LocalReducedMotion
import com.stansful.sshvpnclient.ui.theme.LocalShadowColors
import com.stansful.sshvpnclient.ui.theme.ShadowMotion
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Staggered entrance: fade + 12 dp rise over 360 ms, delayed `min(index, 8) × 30 ms`.
 * Plays once when the node attaches; instant under reduced motion.
 */
fun Modifier.fadeUpIn(index: Int = 0): Modifier = this then FadeUpElement(index)

/** Scales the element to [pressedScale] while [interactionSource] reports a press (120 ms, Standard). */
fun Modifier.pressScale(
    interactionSource: InteractionSource,
    pressedScale: Float = ShadowMotion.PressScale,
): Modifier = this then PressScaleElement(interactionSource, pressedScale)

/**
 * `clickable` without ripple plus the design's press scale (0.97) and the keyboard focus ring
 * ([ShadowFocusIndication]). Pass a remembered interaction source.
 */
fun Modifier.shadowClickable(
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true,
    role: Role? = Role.Button,
    onClickLabel: String? = null,
    pressedScale: Float = ShadowMotion.PressScale,
    onClick: () -> Unit,
): Modifier = this
    .pressScale(interactionSource, pressedScale)
    .clickable(
        interactionSource = interactionSource,
        indication = ShadowFocusIndication,
        enabled = enabled,
        onClickLabel = onClickLabel,
        role = role,
        onClick = onClick,
    )

/**
 * The design system's indication: no ripple or press overlay (presses scale instead), only a 2 dp
 * amber-ink focus ring (with a 1.5 dp bg-colored ring inside it, so it shows on amber fills too) while
 * the element has keyboard / D-pad focus (`clickable`, `toggleable` and `selectable` take focus only
 * outside touch mode). The ring is inset 3 dp with corners of at most 16 dp, so it stays inside the
 * element's own clip (pill, circle or rounded card). Use it instead of `indication = null`.
 */
val ShadowFocusIndication: IndicationNodeFactory = FocusRingIndication(maxRadius = 16.dp)

/** [ShadowFocusIndication] for round elements (the orb): the ring is a circle. */
val ShadowRoundFocusIndication: IndicationNodeFactory = FocusRingIndication(Dp.Infinity)

private class FocusRingIndication(private val maxRadius: Dp) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        FocusRingNode(interactionSource, maxRadius)

    override fun equals(other: Any?): Boolean = other is FocusRingIndication && other.maxRadius == maxRadius

    override fun hashCode(): Int = maxRadius.hashCode()
}

private class FocusRingNode(
    private val interactionSource: InteractionSource,
    private val maxRadius: Dp,
) :
    Modifier.Node(),
    DrawModifierNode,
    CompositionLocalConsumerModifierNode {
    private var focused = false

    override fun onAttach() {
        coroutineScope.launch {
            val focuses = mutableListOf<FocusInteraction.Focus>()
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is FocusInteraction.Focus -> focuses.add(interaction)
                    is FocusInteraction.Unfocus -> focuses.remove(interaction.focus)
                }
                val now = focuses.isNotEmpty()
                if (now != focused) {
                    focused = now
                    invalidateDraw()
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (!focused) return
        val colors = currentValueOf(LocalShadowColors)
        val ring = FOCUS_RING_STROKE.toPx()
        // The amber ring, then a thin bg-colored ring inside it: visible on amber fills too.
        drawRing(colors.amberText, start = FOCUS_RING_INSET.toPx(), stroke = ring)
        drawRing(colors.bg, start = FOCUS_RING_INSET.toPx() + ring, stroke = FOCUS_RING_CONTRAST.toPx())
    }

    /** A rounded-rect stroke [stroke] wide whose outer edge is [start] px inside the bounds. */
    private fun ContentDrawScope.drawRing(color: Color, start: Float, stroke: Float) {
        val inset = start + stroke / 2
        val width = size.width - inset * 2
        val height = size.height - inset * 2
        if (width <= 0f || height <= 0f) return
        val half = minOf(width, height) / 2
        val outerRadius = if (maxRadius == Dp.Infinity) Float.POSITIVE_INFINITY else maxRadius.toPx()
        // Concentric with the outer ring: inner rings lose the corner radius they are inset by.
        val radius = minOf(outerRadius - (start - FOCUS_RING_INSET.toPx()), half).coerceAtLeast(0f)
        drawRoundRect(
            color = color,
            topLeft = Offset(inset, inset),
            size = Size(width, height),
            cornerRadius = CornerRadius(radius),
            style = Stroke(width = stroke),
        )
    }
}

private data class FadeUpElement(val index: Int) : ModifierNodeElement<FadeUpNode>() {
    override fun create() = FadeUpNode(index)

    override fun update(node: FadeUpNode) {
        node.index = index
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "fadeUpIn"
        properties["index"] = index
    }
}

private class FadeUpNode(var index: Int) :
    Modifier.Node(),
    LayoutModifierNode,
    CompositionLocalConsumerModifierNode {
    private val progress = Animatable(0f)
    private var started = false
    private var reduced = false

    /** Runs once, on the first measure (reduced motion is read there, not in onAttach). */
    private fun start() {
        started = true
        reduced = currentValueOf(LocalReducedMotion)
        if (!reduced) rise()
    }

    override fun onAttach() {
        // Re-attached before the entrance finished (e.g. a reused lazy item): finish the rise.
        if (started && !reduced && progress.value < 1f) rise()
    }

    private fun rise() {
        coroutineScope.launch {
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = ShadowMotion.FadeUp,
                    delayMillis = index.coerceIn(0, ShadowMotion.StaggerMax) * ShadowMotion.StaggerStep,
                    easing = ShadowMotion.Standard,
                ),
            )
        }
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        if (!started) start()
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(0, 0) {
                val value = if (reduced) 1f else progress.value
                alpha = value
                translationY = (1f - value) * FADE_UP_DISTANCE.dp.toPx()
            }
        }
    }
}

private data class PressScaleElement(
    val interactionSource: InteractionSource,
    val pressedScale: Float,
) : ModifierNodeElement<PressScaleNode>() {
    override fun create() = PressScaleNode(interactionSource, pressedScale)

    override fun update(node: PressScaleNode) {
        node.pressedScale = pressedScale
        if (node.interactionSource != interactionSource) {
            node.interactionSource = interactionSource
            node.restart()
        }
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "pressScale"
        properties["pressedScale"] = pressedScale
    }
}

private class PressScaleNode(
    var interactionSource: InteractionSource,
    var pressedScale: Float,
) : Modifier.Node(),
    LayoutModifierNode,
    CompositionLocalConsumerModifierNode {
    private val scale = Animatable(1f)
    private var collectJob: Job? = null

    override fun onAttach() {
        restart()
    }

    fun restart() {
        if (!isAttached) return
        collectJob?.cancel()
        collectJob = coroutineScope.launch {
            val presses = mutableListOf<PressInteraction.Press>()
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> presses.add(interaction)
                    is PressInteraction.Release -> presses.remove(interaction.press)
                    is PressInteraction.Cancel -> presses.remove(interaction.press)
                }
                val target = if (presses.isEmpty()) 1f else pressedScale
                launch { animateTo(target) }
            }
        }
    }

    private suspend fun animateTo(target: Float) {
        if (currentValueOf(LocalReducedMotion)) {
            scale.snapTo(target)
        } else {
            scale.animateTo(target, tween(ShadowMotion.Press, easing = ShadowMotion.Standard))
        }
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(0, 0) {
                scaleX = scale.value
                scaleY = scale.value
            }
        }
    }
}

private const val FADE_UP_DISTANCE = 12
private val FOCUS_RING_STROKE = 2.dp
private val FOCUS_RING_INSET = 3.dp
private val FOCUS_RING_CONTRAST = 1.5.dp
