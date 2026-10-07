package com.stansful.sshvpnclient.ui.servers

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.stansful.sshvpnclient.domain.model.VpnConnectionStatus
import com.stansful.sshvpnclient.ui.designsystem.ShadowButton
import com.stansful.sshvpnclient.ui.designsystem.ShadowButtonLabel
import com.stansful.sshvpnclient.ui.designsystem.ShadowButtonSize
import com.stansful.sshvpnclient.ui.designsystem.strokeIcon
import com.stansful.sshvpnclient.ui.main.MainUiState
import com.stansful.sshvpnclient.ui.shell.Destinations
import com.stansful.sshvpnclient.ui.theme.Shadow
import com.stansful.sshvpnclient.ui.theme.ShadowColors
import com.stansful.sshvpnclient.ui.theme.ShadowMotion
import com.stansful.sshvpnclient.ui.theme.ShadowShapes
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.ui.graphics.shadow.Shadow as DropShadow

/** The two halves of the Servers destination (`servers?tab=servers|keys`). */
internal enum class ServersTab(val arg: String) {
    Servers(Destinations.TAB_SERVERS),
    Keys(Destinations.TAB_KEYS),
    ;

    companion object {
        fun fromArg(arg: String?): ServersTab? = entries.firstOrNull { it.arg == arg }
    }
}

/** Phase of an active Server-mode session, as the connection strip and the live tag show it. */
internal enum class ConnectionPhase { Connecting, Connected, Reconnecting }

/** The SSH session that is up right now and the server it runs through ([configId]). */
internal data class ServerConnection(
    val phase: ConnectionPhase,
    val configId: String?,
)

/** The active Server-mode session, or null when Server mode is not connecting or connected. */
internal fun MainUiState.serverConnection(): ServerConnection? {
    val phase = when (sshStatus) {
        VpnConnectionStatus.CONNECTING -> ConnectionPhase.Connecting
        VpnConnectionStatus.CONNECTED -> ConnectionPhase.Connected
        VpnConnectionStatus.RECONNECTING -> ConnectionPhase.Reconnecting
        else -> return null
    }
    return ServerConnection(phase = phase, configId = vpnState.activeConfigId ?: selectedConfig?.id)
}

internal fun serversCountLabel(count: Int): String = if (count == 1) "1 server" else "$count servers"

/** "Not used by any server" · "Used by 1 server" · "Used by 3 servers". */
internal fun keyUsageLabel(count: Int): String = when (count) {
    0 -> "Not used by any server"
    1 -> "Used by 1 server"
    else -> "Used by $count servers"
}

/** "3 Oct" in the current year, "3 Oct 2025" otherwise (English, like the rest of the UI). */
internal fun formatShortDate(timestamp: Long, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): String {
    val date = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
    val pattern = if (date.year == today.year) "d MMM" else "d MMM yyyy"
    return DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH).format(date)
}

/** Overline count on the left and a 12/16 ink-3 note on the right ("3 servers · Recently edited first"). */
@Composable
internal fun ListHeader(title: String, note: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = title.uppercase(),
            style = Shadow.type.overline,
            color = Shadow.colors.ink3,
            modifier = Modifier
                .weight(1f)
                .alignByBaseline()
                .semantics { heading() },
        )
        Text(
            text = note,
            style = Shadow.type.caption,
            color = Shadow.colors.ink3,
            modifier = Modifier.alignByBaseline(),
        )
    }
}

/**
 * Empty list: a 56 dp tile inside a breathing dashed 96 dp ring, 16/600 title, 13/18 ink-3 message and
 * a 52 dp primary button with a plus.
 */
@Composable
internal fun ListEmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    messageMaxWidth: Dp = 270.dp,
) {
    val colors = Shadow.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 36.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.size(96.dp), contentAlignment = Alignment.Center) {
            BreathingDashedRing(modifier = Modifier.fillMaxSize())
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(ShadowShapes.Card)
                    .background(colors.surface2)
                    .border(1.dp, colors.line, ShadowShapes.Card),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = colors.ink2, modifier = Modifier.size(26.dp))
            }
        }
        Text(
            text = title,
            style = Shadow.type.titleS,
            color = colors.ink1,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = message,
            style = Shadow.type.bodyS,
            color = colors.ink3,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(top = 6.dp)
                .widthIn(max = messageMaxWidth),
        )
        ShadowButton(
            text = actionLabel,
            onClick = onAction,
            size = EmptyActionSize,
            icon = BoldPlusIcon,
            modifier = Modifier.padding(top = 24.dp),
        )
    }
}

@Composable
private fun BreathingDashedRing(modifier: Modifier) {
    val ringColor = Shadow.colors.line2
    val layer = if (Shadow.reducedMotion) {
        Modifier
    } else {
        val transition = rememberInfiniteTransition(label = "empty-breathe")
        val phase by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(ShadowMotion.Breathe / 2, easing = ShadowMotion.EaseInOut),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "empty-breathe-phase",
        )
        Modifier.graphicsLayer {
            alpha = BREATHE_MIN_ALPHA + (1f - BREATHE_MIN_ALPHA) * phase
            val scale = BREATHE_MIN_SCALE + (BREATHE_MAX_SCALE - BREATHE_MIN_SCALE) * phase
            scaleX = scale
            scaleY = scale
        }
    }
    Box(
        modifier = modifier
            .then(layer)
            .drawBehind {
                val stroke = 1.dp.toPx()
                drawCircle(
                    color = ringColor,
                    radius = size.minDimension / 2 - stroke / 2,
                    style = Stroke(
                        width = stroke,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())),
                    ),
                )
            },
    )
}

/**
 * The Day palette's hairline under custom cards (0 1 2 ink-1 at 6 %, like the design-system cards);
 * nothing at night.
 */
internal fun Modifier.dayCardShadow(colors: ShadowColors, shape: Shape = ShadowShapes.Card): Modifier {
    if (colors.isDark) return this
    return dropShadow(
        shape,
        DropShadow(radius = 2.dp, color = colors.ink1.copy(alpha = DAY_SHADOW_ALPHA), offset = DpOffset(0.dp, 1.dp)),
    )
}

/** 36 dp tile radius of the key tiles in the editors' key picker and "No saved keys yet" box. */
internal val KeyTileShape = RoundedCornerShape(10.dp)

/** The empty states' 52 dp primary button carries a 20 dp plus drawn at stroke 2 (Servers / Keys boards). */
private val EmptyActionSize = ShadowButtonSize(52.dp, 24.dp, 20.dp, 16.dp, ShadowButtonLabel.Large)
private val BoldPlusIcon by lazy { strokeIcon("plus-bold", "M12 5v14", "M5 12h14", strokeWidth = 2f) }
private const val DAY_SHADOW_ALPHA = 0.06f
private const val BREATHE_MIN_ALPHA = 0.45f
private const val BREATHE_MIN_SCALE = 0.9f
private const val BREATHE_MAX_SCALE = 1.04f
