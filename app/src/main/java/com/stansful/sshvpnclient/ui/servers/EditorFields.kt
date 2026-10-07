package com.stansful.sshvpnclient.ui.servers

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stansful.sshvpnclient.ui.designsystem.ShadowIcons
import com.stansful.sshvpnclient.ui.designsystem.shadowClickable
import com.stansful.sshvpnclient.ui.theme.Shadow
import com.stansful.sshvpnclient.ui.theme.ShadowDimens
import com.stansful.sshvpnclient.ui.theme.ShadowMotion
import com.stansful.sshvpnclient.ui.theme.ShadowShapes
import com.stansful.sshvpnclient.ui.theme.shadowTween

/** Tone of a line under a field: helper (ink-3), error (coral), hint (amber) or confirmation (mint). */
internal enum class FieldTone { Helper, Error, Warning, Success }

/**
 * Label row above a field: 13/600 label (ink-2, ink-1 while [focused]) and an optional 12/16 ink-3 note
 * on the right ("Optional", "15–300 s", "3 saved").
 */
@Composable
internal fun FieldLabelRow(
    label: String,
    modifier: Modifier = Modifier,
    focused: Boolean = false,
    trailing: String? = null,
    trailingMono: Boolean = false,
) {
    val colors = Shadow.colors
    val labelColor by animateColorAsState(
        targetValue = if (focused) colors.ink1 else colors.ink2,
        animationSpec = shadowTween(ShadowMotion.Small, ShadowMotion.Ease),
        label = "field-label",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            style = Shadow.type.label,
            color = labelColor,
            modifier = Modifier
                .weight(1f)
                .alignByBaseline(),
        )
        if (trailing != null) {
            Text(
                text = trailing,
                style = if (trailingMono) Shadow.type.monoS else Shadow.type.caption,
                color = colors.ink3,
                modifier = Modifier.alignByBaseline(),
            )
        }
    }
}

/**
 * Field surface of the editors: surface-2, radius 14, 1 dp line border; focus = 1.5 dp amber + 3 dp
 * soft ring, [isError] = coral, [exposed] (a revealed secret) = 45 % amber, [dashed] = the dashed
 * line-2 border of the "No saved keys yet" box (it stays dashed, in coral, on error).
 * The border is drawn inside the bounds, so content paddings add 1 dp to the artboards' paddings.
 */
@Composable
internal fun fieldFrame(
    focused: Boolean,
    isError: Boolean,
    exposed: Boolean = false,
    dashed: Boolean = false,
): Modifier {
    val colors = Shadow.colors
    val shape = ShadowShapes.Input
    val borderColor by animateColorAsState(
        targetValue = when {
            isError -> colors.coral
            focused -> colors.amber
            exposed -> colors.amber.copy(alpha = EXPOSED_ALPHA)
            dashed -> colors.line2
            else -> colors.line
        },
        animationSpec = shadowTween(ShadowMotion.Small, ShadowMotion.Ease),
        label = "field-border",
    )
    val borderWidth by animateDpAsState(
        targetValue = if (focused && !isError) 1.5.dp else 1.dp,
        animationSpec = shadowTween(ShadowMotion.Small, ShadowMotion.Ease),
        label = "field-border-width",
    )
    val ring by animateColorAsState(
        targetValue = when {
            !focused -> Color.Transparent
            isError -> colors.coral.copy(alpha = FOCUS_RING_ALPHA)
            else -> colors.amber.copy(alpha = FOCUS_RING_ALPHA)
        },
        animationSpec = shadowTween(ShadowMotion.Small, ShadowMotion.Ease),
        label = "field-ring",
    )
    val ringModifier = if (ring.alpha > 0f) {
        Modifier.dropShadow(shape) {
            spread = FOCUS_RING_WIDTH.toPx()
            radius = 0f
            color = ring
        }
    } else {
        Modifier
    }
    val borderModifier = if (dashed && !focused) {
        Modifier.drawBehind {
            val stroke = borderWidth.toPx()
            val radius = 14.dp.toPx() - stroke / 2
            drawRoundRect(
                color = borderColor,
                topLeft = Offset(stroke / 2, stroke / 2),
                size = Size(size.width - stroke, size.height - stroke),
                cornerRadius = CornerRadius(radius, radius),
                style = Stroke(
                    width = stroke,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(DASH.toPx(), DASH.toPx())),
                ),
            )
        }
    } else {
        Modifier.border(borderWidth, borderColor, shape)
    }
    return ringModifier
        .clip(shape)
        .background(colors.surface2)
        .then(borderModifier)
}

/**
 * Text field of the server and key editors (the design's input recipe with room for the editors'
 * label notes, inline badges and buttons). [error] colors the border and is announced by TalkBack;
 * the visible message is drawn by the caller with [FieldMessage] (the Port/Username pair shares one
 * message line under both fields). [frameModifier] applies to the field box only (e.g. its shake).
 */
@Composable
internal fun EditorTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    labelTrailing: String? = null,
    labelTrailingMono: Boolean = false,
    placeholder: String? = null,
    textStyle: TextStyle = Shadow.type.body,
    error: String? = null,
    exposed: Boolean = false,
    frameModifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minHeight: Dp = ShadowDimens.Field,
    contentPadding: PaddingValues = PaddingValues(horizontal = FIELD_TEXT_INSET),
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = Shadow.colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .semantics { if (error != null) error(error) },
        textStyle = textStyle.copy(color = colors.ink1),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        minLines = 1,
        visualTransformation = visualTransformation,
        interactionSource = source,
        cursorBrush = SolidColor(colors.amber),
        decorationBox = { inner ->
            Column {
                if (label != null) {
                    FieldLabelRow(
                        label = label,
                        focused = focused,
                        trailing = labelTrailing,
                        trailingMono = labelTrailingMono,
                    )
                }
                Row(
                    modifier = frameModifier
                        .fillMaxWidth()
                        .heightIn(min = minHeight)
                        .then(fieldFrame(focused = focused, isError = error != null, exposed = exposed)),
                    verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(contentPadding),
                        contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
                    ) {
                        if (value.isEmpty() && placeholder != null) {
                            Text(
                                text = placeholder,
                                style = textStyle,
                                color = colors.ink3,
                                maxLines = if (singleLine) 1 else Int.MAX_VALUE,
                            )
                        }
                        inner()
                    }
                    if (trailing != null) {
                        Row(
                            modifier = Modifier.padding(end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            content = trailing,
                        )
                    }
                }
            }
        },
    )
}

/** Message line under a field: 12/16 text in the [tone] color with a 14 dp icon (none for helpers). */
@Composable
internal fun FieldMessage(
    text: String,
    tone: FieldTone,
    modifier: Modifier = Modifier,
    topPadding: Dp = 6.dp,
    icon: ImageVector? = tone.defaultIcon(),
) {
    val colors = Shadow.colors
    val color = when (tone) {
        FieldTone.Helper -> colors.ink3
        FieldTone.Error -> colors.coralText
        FieldTone.Warning -> colors.amberText
        FieldTone.Success -> colors.mintText
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = topPadding)
            .semantics { if (tone == FieldTone.Error) liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier
                    .padding(top = 1.dp)
                    .size(14.dp),
            )
        }
        Text(text = text, style = Shadow.type.caption, color = color)
    }
}

private fun FieldTone.defaultIcon(): ImageVector? = when (this) {
    FieldTone.Helper -> null
    FieldTone.Error, FieldTone.Warning -> ShadowIcons.Warning
    FieldTone.Success -> ShadowIcons.CheckCircle
}

/**
 * Horizontal error shake (420 ms: -6, 5, -3, 2 dp) played whenever [trigger] changes to a positive
 * value; nothing under reduced motion.
 */
@Composable
internal fun shakeModifier(trigger: Int): Modifier {
    val reduced = Shadow.reducedMotion
    val offset = remember { Animatable(0f) }
    LaunchedEffect(trigger) {
        if (trigger <= 0 || reduced) return@LaunchedEffect
        offset.snapTo(0f)
        offset.animateTo(
            targetValue = 0f,
            animationSpec = keyframes {
                durationMillis = SHAKE_MS
                0f at 0 using ShadowMotion.ShakeEasing
                -6f at SHAKE_MS / 5 using ShadowMotion.ShakeEasing
                5f at SHAKE_MS * 2 / 5 using ShadowMotion.ShakeEasing
                -3f at SHAKE_MS * 3 / 5 using ShadowMotion.ShakeEasing
                2f at SHAKE_MS * 4 / 5 using ShadowMotion.ShakeEasing
            },
        )
    }
    return Modifier.graphicsLayer { translationX = offset.value * density }
}

/**
 * Amber-tint pill action drawn 40 dp tall inside a 44 dp touch target ("Paste from clipboard", "Add key").
 */
@Composable
internal fun TonalPillButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    startPadding: Dp = 14.dp,
    endPadding: Dp = 14.dp,
    gap: Dp = 8.dp,
) {
    val colors = Shadow.colors
    Box(
        modifier = modifier
            .height(ShadowDimens.TouchTarget)
            .shadowClickable(remember { MutableInteractionSource() }, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .height(PILL_HEIGHT)
                .clip(ShadowShapes.SegmentThumb)
                .background(colors.amberTint)
                .padding(start = startPadding, end = endPadding),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = colors.amberText, modifier = Modifier.size(16.dp))
            Text(text = text, style = Shadow.type.label, color = colors.amberText, maxLines = 1)
        }
    }
}

/**
 * Save failure above an editor's save button: coral tint, radius 14, 10/14 padding (tighter than
 * [com.stansful.sshvpnclient.ui.designsystem.InlineBanner]'s 12), 18 dp warning icon, 13/18 coral
 * text; announced assertively.
 */
@Composable
internal fun SaveErrorBanner(message: String, modifier: Modifier = Modifier) {
    val colors = Shadow.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(ShadowShapes.Banner)
            .background(colors.coralTint)
            .semantics { liveRegion = LiveRegionMode.Assertive }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(ShadowIcons.Warning, contentDescription = null, tint = colors.coralText, modifier = Modifier.size(18.dp))
        Text(text = message, style = Shadow.type.bodyS, color = colors.coralText, modifier = Modifier.weight(1f))
    }
}

/** 12/16 600 (tags, small pills). */
internal val TagTextStyle: TextStyle
    @Composable get() = Shadow.type.caption.copy(fontWeight = FontWeight.SemiBold)

/** 12/16 500 mono (key names in tags, keepalive values, counts). */
internal val TagMonoStyle: TextStyle
    @Composable get() = Shadow.type.monoS.copy(fontWeight = FontWeight.Medium)

/** Max width of the editor forms (tablets); phones use the full width. */
internal val EDITOR_MAX_WIDTH = ShadowDimens.PaneMaxWidth

/** Scroll offset after which the editors' top bar shows its divider. */
internal const val EDITOR_SCROLLED_PX = 4

/** Expand/collapse of inline panels (key list, fingerprint note). */
internal const val EDITOR_EXPAND_MS = 360
internal const val EDITOR_EXPAND_FADE_MS = 260

/** Side system bars and display cutouts (landscape) that the editors' content keeps clear of. */
internal val EditorSideInsets: WindowInsets
    @Composable get() = WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal)

/** Text inset of the editor fields: the artboards' 16 dp padding plus the 1 dp border. */
internal val FIELD_TEXT_INSET = 17.dp

private const val SHAKE_MS = 420
private val PILL_HEIGHT = 40.dp
private const val EXPOSED_ALPHA = 0.45f
private const val FOCUS_RING_ALPHA = 0.16f
private val FOCUS_RING_WIDTH = 3.dp
private val DASH = 3.dp
