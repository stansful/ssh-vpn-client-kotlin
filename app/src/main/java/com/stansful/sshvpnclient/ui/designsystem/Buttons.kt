package com.stansful.sshvpnclient.ui.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stansful.sshvpnclient.ui.theme.Shadow
import com.stansful.sshvpnclient.ui.theme.ShadowColors
import com.stansful.sshvpnclient.ui.theme.ShadowDimens
import com.stansful.sshvpnclient.ui.theme.ShadowMotion
import com.stansful.sshvpnclient.ui.theme.shadowTween

/** Fill/ink pairing of a [ShadowButton]. */
enum class ShadowButtonVariant {
    /** Amber fill, amber-ink text — the one main action of a screen. */
    Primary,

    /** surface-3 fill, ink-1 text — Cancel, Manage, secondary full-width actions. */
    Secondary,

    /** Coral fill — the confirm button of a destructive dialog. */
    Danger,

    /** Coral tint fill, coral text — "Delete key" style actions. */
    DangerTonal,

    /** Amber tint fill, amber text. */
    Tonal,

    /** No fill, amber text — "Release notes", "Save" in a top bar. */
    Text,

    /** No fill, ink-1 text — dialog dismiss ("Stay", "Cancel"). */
    Ghost,
}

/** Label style of a [ShadowButtonSize]: 15/20 600, 14/18 600 or 13/18 600. */
enum class ShadowButtonLabel {
    /** 15/20 600 (`Shadow.type.button`). */
    Large,

    /** 14/18 600 (`Shadow.type.segment`) — Settings card actions. */
    Medium,

    /** 13/18 600 (`Shadow.type.label`). */
    Small,
}

/**
 * Height, horizontal padding, icon size, corner radius and label style of a [ShadowButton]. Use a
 * preset; construct one only for an artboard that draws a different combination.
 */
@Immutable
data class ShadowButtonSize(
    val height: Dp,
    val horizontalPadding: Dp,
    val iconSize: Dp,
    val cornerRadius: Dp,
    val label: ShadowButtonLabel,
) {
    companion object {
        /** 52 dp, radius 16, 15/600 — primary full-width buttons, stacked dialog actions. */
        val Large = ShadowButtonSize(52.dp, 24.dp, 18.dp, 16.dp, ShadowButtonLabel.Large)

        /** 48 dp, radius 14, 15/600 — tablet pane actions, stacked dialog actions on tablets. */
        val Tall = ShadowButtonSize(48.dp, 22.dp, 18.dp, 14.dp, ShadowButtonLabel.Large)

        /** 44 dp, radius 14, 15/600 — inline dialog actions, top-bar "Save". */
        val Medium = ShadowButtonSize(44.dp, 18.dp, 18.dp, 14.dp, ShadowButtonLabel.Large)

        /** 40 dp, radius 14, 15/600 — empty-state and "Try again" buttons. */
        val Regular = ShadowButtonSize(40.dp, 18.dp, 18.dp, 14.dp, ShadowButtonLabel.Large)

        /** 40 dp, radius 14, 13/600 — small / tonal buttons ("Cancel", "Delete key"). */
        val Small = ShadowButtonSize(40.dp, 16.dp, 16.dp, 14.dp, ShadowButtonLabel.Small)

        /** 40 dp, radius 12, 13/600 — actions inside cards, rows and banners ("Download engine"). */
        val Compact = ShadowButtonSize(40.dp, 16.dp, 16.dp, 12.dp, ShadowButtonLabel.Small)

        /** 36 dp, radius 12, 13/600 — inline card actions ("Connect", "Switch" on route cards). */
        val Mini = ShadowButtonSize(36.dp, 14.dp, 16.dp, 12.dp, ShadowButtonLabel.Small)
    }
}

/**
 * The design's button. Width wraps its content: pass `Modifier.fillMaxWidth()` for full-width buttons.
 * Disabled renders at 40 % opacity; [loading] swaps the icon for a spinner and ignores taps.
 */
@Composable
fun ShadowButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ShadowButtonVariant = ShadowButtonVariant.Primary,
    size: ShadowButtonSize = ShadowButtonSize.Large,
    icon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    contentDescription: String? = null,
) {
    val colors = Shadow.colors
    val container by animateColorAsState(
        targetValue = variant.containerColor(colors),
        animationSpec = shadowTween(ShadowMotion.Small, ShadowMotion.Ease),
        label = "button-container",
    )
    val content by animateColorAsState(
        targetValue = variant.contentColor(colors),
        animationSpec = shadowTween(ShadowMotion.Small, ShadowMotion.Ease),
        label = "button-content",
    )
    val interactionSource = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(size.cornerRadius)
    val padding = variant.horizontalPadding(size)
    Row(
        modifier = modifier
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .shadowClickable(
                interactionSource = interactionSource,
                enabled = enabled && !loading,
                onClick = onClick,
            )
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                },
            )
            .height(size.height)
            .clip(shape)
            .background(container, shape)
            .padding(horizontal = padding),
        horizontalArrangement = Arrangement.spacedBy(BUTTON_GAP, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ButtonLeading(icon = icon, loading = loading, color = content, size = size.iconSize)
        Text(
            text = text,
            style = size.label.style(),
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (trailingIcon != null) {
            Icon(trailingIcon, contentDescription = null, tint = content, modifier = Modifier.size(size.iconSize))
        }
    }
}

@Composable
private fun ButtonLeading(icon: ImageVector?, loading: Boolean, color: Color, size: Dp) {
    when {
        loading -> ShadowSpinner(color = color, size = size)
        icon != null -> Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(size))
    }
}

/** Amber primary button (52 dp by default). */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    size: ShadowButtonSize = ShadowButtonSize.Large,
    enabled: Boolean = true,
    loading: Boolean = false,
) = ShadowButton(text, onClick, modifier, ShadowButtonVariant.Primary, size, icon, null, enabled, loading)

/** surface-3 secondary button. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    size: ShadowButtonSize = ShadowButtonSize.Large,
    enabled: Boolean = true,
    loading: Boolean = false,
) = ShadowButton(text, onClick, modifier, ShadowButtonVariant.Secondary, size, icon, null, enabled, loading)

/** Coral-tint destructive button (40 dp by default). */
@Composable
fun DangerTonalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = ShadowIcons.Trash,
    size: ShadowButtonSize = ShadowButtonSize.Small,
    enabled: Boolean = true,
    loading: Boolean = false,
) = ShadowButton(text, onClick, modifier, ShadowButtonVariant.DangerTonal, size, icon, null, enabled, loading)

/** Amber text button without fill (optional trailing icon, e.g. ShadowIcons.External). */
@Composable
fun ShadowTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailingIcon: ImageVector? = null,
    size: ShadowButtonSize = ShadowButtonSize.Small,
    enabled: Boolean = true,
) = ShadowButton(text, onClick, modifier, ShadowButtonVariant.Text, size, null, trailingIcon, enabled, false)

/** Visual style of a [ShadowIconButton]. */
enum class ShadowIconButtonStyle {
    /** Transparent, radius 14, ink-2 icon (surface-3 fill when `selected`). */
    Plain,

    /** Amber tint fill, amber icon, radius 14 — the "Add" action of a top bar. */
    Tonal,

    /** surface-3 circle, ink-2 icon — sheet close button (use size 40 / icon 18). */
    Filled,
}

/** 44 dp icon-only button. Always give [contentDescription] unless the icon is purely decorative. */
@Composable
fun ShadowIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ShadowIconButtonStyle = ShadowIconButtonStyle.Plain,
    tint: Color = Color.Unspecified,
    enabled: Boolean = true,
    selected: Boolean = false,
    size: Dp = 44.dp,
    iconSize: Dp = 22.dp,
) {
    val colors = Shadow.colors
    val shape: Shape = if (style == ShadowIconButtonStyle.Filled) CircleShape else RoundedCornerShape(14.dp)
    val targetContainer = when (style) {
        ShadowIconButtonStyle.Plain -> if (selected) colors.surface3 else Color.Transparent
        ShadowIconButtonStyle.Tonal -> colors.amberTint
        ShadowIconButtonStyle.Filled -> colors.surface3
    }
    val container by animateColorAsState(
        targetValue = targetContainer,
        animationSpec = shadowTween(ShadowMotion.Small, ShadowMotion.Ease),
        label = "icon-button-container",
    )
    val iconTint = when {
        tint != Color.Unspecified -> tint
        style == ShadowIconButtonStyle.Tonal -> colors.amberText
        selected -> colors.ink1
        else -> colors.ink2
    }
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .shadowClickable(interactionSource = interactionSource, enabled = enabled, onClick = onClick)
            .size(size)
            .clip(shape)
            .background(container, shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = iconTint, modifier = Modifier.size(iconSize))
    }
}

private fun ShadowButtonVariant.containerColor(colors: ShadowColors): Color = when (this) {
    ShadowButtonVariant.Primary -> colors.amber
    ShadowButtonVariant.Secondary -> colors.surface3
    ShadowButtonVariant.Danger -> colors.coral
    ShadowButtonVariant.DangerTonal -> colors.coralTint
    ShadowButtonVariant.Tonal -> colors.amberTint
    ShadowButtonVariant.Text, ShadowButtonVariant.Ghost -> Color.Transparent
}

private fun ShadowButtonVariant.contentColor(colors: ShadowColors): Color = when (this) {
    ShadowButtonVariant.Primary -> colors.onAmber
    ShadowButtonVariant.Secondary, ShadowButtonVariant.Ghost -> colors.ink1
    ShadowButtonVariant.Danger -> colors.onCoral
    ShadowButtonVariant.DangerTonal -> colors.coralText
    ShadowButtonVariant.Tonal, ShadowButtonVariant.Text -> colors.amberText
}

/** Text buttons sit tighter than filled ones: 12 dp below 44 dp height, 14 (text) / 16 (ghost) above. */
private fun ShadowButtonVariant.horizontalPadding(size: ShadowButtonSize): Dp = when {
    this != ShadowButtonVariant.Text && this != ShadowButtonVariant.Ghost -> size.horizontalPadding
    size.height < ShadowDimens.TouchTarget -> TEXT_BUTTON_PADDING_SMALL
    this == ShadowButtonVariant.Ghost -> GHOST_BUTTON_PADDING
    else -> TEXT_BUTTON_PADDING
}

@Composable
@ReadOnlyComposable
private fun ShadowButtonLabel.style(): TextStyle = when (this) {
    ShadowButtonLabel.Large -> Shadow.type.button
    ShadowButtonLabel.Medium -> Shadow.type.segment
    ShadowButtonLabel.Small -> Shadow.type.label
}

/** Opacity of disabled controls (never just grey text). */
const val DISABLED_ALPHA: Float = 0.4f
private val BUTTON_GAP = 8.dp
private val TEXT_BUTTON_PADDING_SMALL = 12.dp
private val TEXT_BUTTON_PADDING = 14.dp
private val GHOST_BUTTON_PADDING = 16.dp
