package com.stansful.sshvpnclient.ui.servers

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stansful.sshvpnclient.ui.designsystem.DISABLED_ALPHA
import com.stansful.sshvpnclient.ui.designsystem.ShadowIcons
import com.stansful.sshvpnclient.ui.designsystem.SwapText
import com.stansful.sshvpnclient.ui.designsystem.shadowClickable
import com.stansful.sshvpnclient.ui.designsystem.strokeIcon
import com.stansful.sshvpnclient.ui.theme.Shadow
import com.stansful.sshvpnclient.ui.theme.ShadowMotion
import com.stansful.sshvpnclient.ui.theme.ShadowShapes
import com.stansful.sshvpnclient.ui.theme.shadowTween

/**
 * Keepalive interval as a stepper over the values the connection honours (15–300 s). A stored value
 * outside the steps (older configurations) is shown as it is and steps to its neighbours.
 */
@Composable
internal fun KeepaliveStepper(
    seconds: Int,
    error: String?,
    onChange: (Int) -> Unit,
    modifier: Modifier,
) {
    val colors = Shadow.colors
    val lower = KEEPALIVE_STEPS.lastOrNull { it < seconds }
    val higher = KEEPALIVE_STEPS.firstOrNull { it > seconds }
    val fill by animateFloatAsState(
        targetValue = keepaliveFraction(seconds),
        animationSpec = shadowTween(ShadowMotion.ColorFade),
        label = "keepalive-fill",
    )
    val effective = seconds.coerceIn(KEEPALIVE_STEPS.first(), KEEPALIVE_STEPS.last())
    val help = if (effective < SCREEN_OFF_KEEPALIVE) {
        "We ping every $effective s and reconnect after 3 missed replies. With the screen off we wait at least 120 s."
    } else {
        "We ping every $effective s and reconnect after 3 missed replies, with the screen on or off."
    }
    Column(modifier) {
        FieldLabelRow(label = "Keepalive", trailing = "15–300 s", trailingMono = true)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(ShadowShapes.Input)
                .background(colors.surface2)
                .border(1.dp, if (error != null) colors.coral else colors.line, ShadowShapes.Input)
                // 3 dp inside the 1 dp border; the 52 dp height already counts the border.
                .padding(horizontal = 4.dp, vertical = 3.dp),
        ) {
            Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                StepperButton(
                    icon = MinusIcon,
                    contentDescription = "Shorter interval",
                    enabled = lower != null,
                    onClick = { lower?.let(onChange) },
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(bottom = 4.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    contentAlignment = Alignment.Center,
                ) {
                    SwapText(
                        text = "$seconds s",
                        style = Shadow.type.monoMedium.copy(fontSize = 16.sp, lineHeight = 22.sp),
                        color = colors.ink1,
                    )
                }
                StepperButton(
                    icon = ShadowIcons.Plus,
                    contentDescription = "Longer interval",
                    enabled = higher != null,
                    onClick = { higher?.let(onChange) },
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = TRACK_INSET, end = TRACK_INSET, bottom = 6.dp)
                    .fillMaxWidth()
                    .height(2.dp)
                    .clip(ShadowShapes.Pill)
                    .background(colors.line2),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fill)
                        .clip(ShadowShapes.Pill)
                        .background(colors.ink3),
                )
            }
        }
        SwapText(
            text = help,
            style = Shadow.type.caption,
            color = colors.ink3,
            maxLines = 3,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp),
        )
        if (error != null) FieldMessage(error, FieldTone.Error)
    }
}

@Composable
private fun StepperButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .shadowClickable(remember { MutableInteractionSource() }, enabled = enabled, onClick = onClick)
            .semantics { this.contentDescription = contentDescription }
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clip(StepperButtonShape)
            .background(Shadow.colors.surface3),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Shadow.colors.ink1, modifier = Modifier.size(18.dp))
    }
}

/** Position of [seconds] along the keepalive steps (0 = 15 s, 1 = 300 s), interpolated between steps. */
private fun keepaliveFraction(seconds: Int): Float {
    val steps = KEEPALIVE_STEPS
    if (seconds <= steps.first()) return 0f
    if (seconds >= steps.last()) return 1f
    val upper = steps.indexOfFirst { it >= seconds }
    val low = steps[upper - 1]
    val high = steps[upper]
    val position = (upper - 1) + (seconds - low).toFloat() / (high - low)
    return position / (steps.size - 1)
}

private val MinusIcon by lazy { strokeIcon("minus", "M5 12h14") }
private val KEEPALIVE_STEPS = listOf(15, 20, 30, 45, 60, 90, 120, 180, 240, 300)
/** Track 64 dp in from the inner border edge = 65 dp from the frame, minus the frame's 4 dp padding. */
private val TRACK_INSET = 61.dp
private val StepperButtonShape = RoundedCornerShape(11.dp)
private const val SCREEN_OFF_KEEPALIVE = 120
