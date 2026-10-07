package com.stansful.sshvpnclient.ui.settings

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stansful.sshvpnclient.domain.model.CustomThemeColors
import com.stansful.sshvpnclient.ui.designsystem.ClipboardCopier
import com.stansful.sshvpnclient.ui.designsystem.DayHairlineShadow
import com.stansful.sshvpnclient.ui.designsystem.LocalToaster
import com.stansful.sshvpnclient.ui.designsystem.ShadowButtonLabel
import com.stansful.sshvpnclient.ui.designsystem.ShadowButtonSize
import com.stansful.sshvpnclient.ui.designsystem.ShadowIcons
import com.stansful.sshvpnclient.ui.designsystem.ToastTone
import com.stansful.sshvpnclient.ui.designsystem.rememberClipboardCopier
import com.stansful.sshvpnclient.ui.theme.Shadow
import com.stansful.sshvpnclient.ui.theme.ShadowMotion
import com.stansful.sshvpnclient.ui.theme.ShadowShapes

internal const val GITHUB_REPOSITORY_URL = "https://github.com/stansful/ssh-vpn-client-kotlin/tree/master"
internal const val GITHUB_REPOSITORY_LABEL = "github.com/stansful/ssh-vpn-client-kotlin"
internal const val GITHUB_RELEASES_URL = "https://github.com/stansful/ssh-vpn-client-kotlin/releases"

/** 40 dp buttons inside Settings cards: radius 12, 14/18 600 labels. */
internal val CardButtonSize = ShadowButtonSize(40.dp, 16.dp, 16.dp, 12.dp, ShadowButtonLabel.Medium)

/**
 * Opens links in the browser. When no app can open them (no browser, work profile) the link is copied
 * instead of crashing, and a toast says so.
 */
internal fun interface LinkOpener {
    fun open(url: String)
}

@Composable
internal fun rememberLinkOpener(): LinkOpener {
    val uriHandler = LocalUriHandler.current
    val copier: ClipboardCopier = rememberClipboardCopier()
    val toaster = LocalToaster.current
    return remember(uriHandler, copier, toaster) {
        LinkOpener { url ->
            runCatching { uriHandler.openUri(url) }.onFailure {
                copier.copy(label = "Link", text = url, toast = null)
                toaster.show(
                    message = "Couldn’t open the link",
                    tone = ToastTone.Error,
                    detail = "It’s copied, paste it into a browser.",
                )
            }
        }
    }
}

/** A refresh icon that turns (1.3 s per turn) while [spinning]; still under reduced motion. */
@Composable
internal fun SpinningIcon(
    icon: ImageVector,
    spinning: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
) {
    // Read in the layer block: the turn redraws the layer instead of recomposing every frame.
    val angle = if (spinning && !Shadow.reducedMotion) {
        rememberInfiniteTransition(label = "spin").animateFloat(
            initialValue = 0f,
            targetValue = FULL_TURN,
            animationSpec = infiniteRepeatable(tween(ShadowMotion.Spinner, easing = LinearEasing), RepeatMode.Restart),
            label = "spin-angle",
        )
    } else {
        null
    }
    Icon(
        icon,
        contentDescription = null,
        tint = tint,
        modifier = modifier
            .size(size)
            .graphicsLayer { rotationZ = angle?.value ?: 0f },
    )
}

/** Overlapping round color swatches with a 2 dp ring of the card color (Custom palette rows). */
@Composable
internal fun SwatchStack(
    colors: List<Color>,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    overlap: Dp = 5.dp,
    hairline: Boolean = true,
) {
    val ring = Shadow.colors.surface1
    val edge = Shadow.colors.ink1.copy(alpha = SWATCH_HAIRLINE_ALPHA)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(-overlap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        colors.forEach { color ->
            Box(
                Modifier
                    .size(size)
                    .drawBehind { drawCircle(ring, radius = this.size.minDimension / 2 + RING_WIDTH.toPx()) }
                    .clip(CircleShape)
                    .background(color)
                    .then(if (hairline) Modifier.border(1.dp, edge, CircleShape) else Modifier),
            )
        }
    }
}

/**
 * An error line under an action (BRIEF §5): 16 dp coral warning icon + 13/18 coral text, announced as
 * soon as it appears.
 */
@Composable
internal fun InlineErrorText(text: String, modifier: Modifier = Modifier) {
    val colors = Shadow.colors
    Row(
        modifier = modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Assertive },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = ShadowIcons.Warning,
            contentDescription = null,
            tint = colors.coralText,
            modifier = Modifier
                .padding(top = 1.dp)
                .size(16.dp),
        )
        Text(text = text, style = Shadow.type.bodyS, color = colors.coralText)
    }
}

/**
 * A plain card: surface-1, 1 dp line border, radius 18 (+ the Day hairline shadow). Content starts inside
 * the border, as in the artboards' CSS boxes (border + padding).
 */
@Composable
internal fun Modifier.settingsCard(): Modifier {
    val colors = Shadow.colors
    return this
        .then(if (!colors.isDark) Modifier.dropShadow(ShadowShapes.Card, DayHairlineShadow) else Modifier)
        .clip(ShadowShapes.Card)
        .background(colors.surface1)
        .border(CARD_BORDER, colors.line, ShadowShapes.Card)
        .padding(CARD_BORDER)
}

/** Display name of a saved palette: the preset it equals ("Light" = the never-edited default), else "Your colors". */
internal fun CustomThemeColors.paletteName(): String = when (this) {
    CustomThemeColors.defaultLight() -> "Light"
    else -> PalettePresets.firstOrNull { it.colors == this }?.name ?: "Your colors"
}

internal data class PalettePreset(val name: String, val colors: CustomThemeColors)

internal val PalettePresets: List<PalettePreset> = listOf(
    PalettePreset("Night", CustomThemeColors.night()),
    PalettePreset("Day", CustomThemeColors.day()),
    PalettePreset("Shadow classic", CustomThemeColors.shadowClassic()),
    PalettePreset("Ocean", CustomThemeColors.ocean()),
)

internal fun Int.toColor(): Color = Color(this)

internal const val SWATCH_HAIRLINE_ALPHA = 0.14f
private val RING_WIDTH = 2.dp
private val CARD_BORDER = 1.dp
private const val FULL_TURN = 360f
