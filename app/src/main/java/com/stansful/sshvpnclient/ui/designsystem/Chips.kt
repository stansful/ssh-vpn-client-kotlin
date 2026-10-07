package com.stansful.sshvpnclient.ui.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.stansful.sshvpnclient.ui.theme.Shadow
import com.stansful.sshvpnclient.ui.theme.ShadowColors
import com.stansful.sshvpnclient.ui.theme.ShadowMotion
import com.stansful.sshvpnclient.ui.theme.mixColors
import com.stansful.sshvpnclient.ui.theme.shadowTween
import androidx.compose.ui.graphics.shadow.Shadow as DropShadowSpec

/** Look of a [ShadowChip]. */
enum class ChipTone {
    /** surface-1 + 1 dp line border, ink-1 label. */
    Default,

    /** Active filter: ink-1 fill with bg-colored label. */
    Active,

    /** Working ("Checking…"): amber label. */
    Progress,

    /** Result OK ("Tunnel OK"): mint tint. */
    Success,

    /** Result failed: coral tint. */
    Error,

    /** Informational: sky tint. */
    Info,
}

/**
 * 36 dp pill chip: optional 16 dp icon + 13/600 label (+ mono 12/500 [count], e.g. "All 128").
 * [onClick] null renders a static chip. [loading] replaces the icon with a spinner. Press scales to
 * 0.97; colors crossfade in 200 ms.
 */
@Composable
fun ShadowChip(
    label: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tone: ChipTone = ChipTone.Default,
    loading: Boolean = false,
    enabled: Boolean = true,
    trailingIcon: ImageVector? = null,
    count: String? = null,
) {
    val colors = Shadow.colors
    val palette = chipPalette(colors, tone)
    val colorSpec = shadowTween<Color>(ShadowMotion.Small, ShadowMotion.Ease)
    val container by animateColorAsState(palette.container, colorSpec, label = "chip-bg")
    val border by animateColorAsState(palette.border, colorSpec, label = "chip-line")
    val content by animateColorAsState(palette.content, colorSpec, label = "chip-ink")
    val interactionSource = remember { MutableInteractionSource() }
    val dayShadow = !colors.isDark && (tone == ChipTone.Default || tone == ChipTone.Active)
    Row(
        modifier = modifier
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .then(
                if (onClick != null) {
                    Modifier.shadowClickable(
                        interactionSource = interactionSource,
                        enabled = enabled && !loading,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )
            .height(36.dp)
            .then(if (dayShadow) Modifier.dropShadow(CircleShape, DayHairlineShadow) else Modifier)
            .clip(CircleShape)
            .background(container)
            .border(CHIP_LINE, border, CircleShape)
            // The artboards' 14 px padding sits inside the 1 px border (CSS box model).
            .padding(horizontal = CHIP_LINE + 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            loading -> ShadowSpinner(color = content, size = 16.dp)
            icon != null -> Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(COUNT_GAP),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = Shadow.type.label,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (count != null) {
                val countColor by animateColorAsState(palette.count, colorSpec, label = "chip-count")
                Text(text = count, style = Shadow.type.monoS.copy(fontWeight = FontWeight.Medium), color = countColor)
            }
        }
        if (trailingIcon != null) {
            Icon(trailingIcon, contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
        }
    }
}

/**
 * Filter chip: Default when off, Active (ink-1 fill) when [selected]; exposes the selected state.
 * [count] adds the mono count of the Routes filters ("Available 41").
 */
@Composable
fun ShadowFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    count: String? = null,
) {
    ShadowChip(
        label = label,
        onClick = onClick,
        modifier = modifier.semantics { this.selected = selected },
        icon = icon,
        tone = if (selected) ChipTone.Active else ChipTone.Default,
        enabled = enabled,
        count = count,
    )
}

private data class ChipPalette(
    val container: Color,
    val border: Color,
    val content: Color,
    val count: Color,
)

private fun chipPalette(colors: ShadowColors, tone: ChipTone): ChipPalette {
    val borderAlpha = if (colors.isDark) TONE_BORDER_ALPHA_NIGHT else TONE_BORDER_ALPHA_DAY
    return when (tone) {
        ChipTone.Default -> ChipPalette(colors.surface1, colors.line, colors.ink1, colors.ink3)
        ChipTone.Active -> ChipPalette(
            container = colors.ink1,
            border = colors.ink1,
            content = colors.bg,
            count = mixColors(colors.bg, colors.ink1, ACTIVE_COUNT_BLEND),
        )
        ChipTone.Progress -> if (colors.isDark) {
            ChipPalette(colors.surface1, colors.line, colors.amberText, colors.amberText)
        } else {
            tinted(colors.amberTint, colors.amberText, borderAlpha)
        }
        ChipTone.Success -> tinted(colors.mintTint, colors.mintText, borderAlpha)
        ChipTone.Error -> tinted(colors.coralTint, colors.coralText, borderAlpha)
        ChipTone.Info -> tinted(colors.skyTint, colors.skyText, borderAlpha)
    }
}

private fun tinted(tint: Color, ink: Color, borderAlpha: Float) =
    ChipPalette(container = tint, border = ink.copy(alpha = borderAlpha), content = ink, count = ink)

/** Day-theme hairline elevation `0 1px 2px rgba(20,22,26,0.06)` for cards and chips. */
internal val DayHairlineShadow = DropShadowSpec(
    radius = 2.dp,
    color = Color(0x0F14161A),
    offset = DpOffset(0.dp, 1.dp),
)

private const val TONE_BORDER_ALPHA_NIGHT = 0.35f
private const val TONE_BORDER_ALPHA_DAY = 0.28f
private const val ACTIVE_COUNT_BLEND = 0.72f
private val COUNT_GAP = 6.dp
private val CHIP_LINE = 1.dp
