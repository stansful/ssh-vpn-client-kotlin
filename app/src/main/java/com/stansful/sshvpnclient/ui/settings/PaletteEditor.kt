package com.stansful.sshvpnclient.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.stansful.sshvpnclient.domain.model.CustomThemeColors
import com.stansful.sshvpnclient.ui.designsystem.ShadowFocusIndication
import com.stansful.sshvpnclient.ui.designsystem.ShadowIcons
import com.stansful.sshvpnclient.ui.designsystem.pressScale
import com.stansful.sshvpnclient.ui.theme.JetBrainsMonoFamily
import com.stansful.sshvpnclient.ui.theme.Shadow
import com.stansful.sshvpnclient.ui.theme.ShadowMotion
import com.stansful.sshvpnclient.ui.theme.ShadowShapes
import com.stansful.sshvpnclient.ui.theme.shadowTween
import kotlin.math.roundToInt

/** The four preset swatches ("Fills the draft only"). */
@Composable
internal fun PresetRow(
    draft: CustomThemeColors,
    onPick: (CustomThemeColors) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        PalettePresets.forEach { preset ->
            PresetButton(
                preset = preset,
                active = draft == preset.colors,
                onClick = { onPick(preset.colors) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun PresetButton(preset: PalettePreset, active: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val colors = Shadow.colors
    val ring by animateColorAsState(
        targetValue = if (active) colors.amber else colors.amber.copy(alpha = 0f),
        animationSpec = shadowTween(PRESET_RING_MS, ShadowMotion.Ease),
        label = "preset-ring",
    )
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .pressScale(interaction)
            .clip(ShadowShapes.ButtonSmall)
            .clickable(
                interactionSource = interaction,
                indication = ShadowFocusIndication,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics {
                contentDescription = "Fill the draft with the ${preset.name} preset"
                stateDescription = if (active) "In the draft" else ""
            }
            .padding(start = 2.dp, end = 2.dp, top = 4.dp, bottom = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val pageBg = colors.bg
        val hairline = colors.ink1.copy(alpha = PRESET_HAIRLINE)
        Box(
            modifier = Modifier
                .size(44.dp)
                .drawBehind {
                    val r = size.minDimension / 2
                    drawCircle(ring, radius = r + 4.dp.toPx())
                    drawCircle(pageBg, radius = r + 2.dp.toPx())
                    drawCircle(Color(preset.colors.background), radius = r)
                    drawCircle(hairline, radius = r - 0.5.dp.toPx(), style = Stroke(1.dp.toPx()))
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(20.dp)
                    .border(2.5.dp, Color(preset.colors.primary), CircleShape),
            )
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-8).dp, y = 8.dp)
                    .size(8.dp)
                    .background(Color(preset.colors.secondary), CircleShape),
            )
        }
        Text(
            text = preset.name,
            style = Shadow.type.caption.copy(
                lineHeight = 15.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            ),
            color = if (active) colors.ink1 else colors.ink2,
            textAlign = TextAlign.Center,
        )
    }
}

/** One role of the palette: swatch, name, hint (or "Edited · was #…"), hex, and its editor when open. */
@Composable
internal fun PaletteRoleRow(
    role: PaletteRole,
    color: Int,
    savedColor: Int,
    open: Boolean,
    first: Boolean,
    onToggle: () -> Unit,
    onColorChange: (Int) -> Unit,
) {
    val colors = Shadow.colors
    val edited = color != savedColor
    val rowBg by animateColorAsState(
        targetValue = if (open) colors.sheet else colors.sheet.copy(alpha = 0f),
        animationSpec = shadowTween(ROLE_BG_MS, ShadowMotion.Ease),
        label = "role-bg",
    )
    val chevron by animateFloatAsState(
        targetValue = if (open) HALF_TURN else 0f,
        animationSpec = shadowTween(CHEVRON_MS),
        label = "role-chevron",
    )
    val swatch by animateColorAsState(Color(color), shadowTween(SWATCH_MS, ShadowMotion.Ease), label = "role-swatch")
    val divider = colors.line2
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBg)
            .drawBehind {
                if (!first) {
                    drawLine(
                        color = divider,
                        start = Offset(16.dp.toPx(), 0.5.dp.toPx()),
                        end = Offset(size.width, 0.5.dp.toPx()),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
            },
    ) {
        val interaction = remember { MutableInteractionSource() }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = interaction,
                    indication = ShadowFocusIndication,
                    role = Role.Button,
                    onClickLabel = if (open) "Close editor" else "Edit color",
                    onClick = onToggle,
                )
                .pressScale(interaction)
                .heightIn(min = 56.dp)
                .padding(start = 16.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(swatch)
                    .border(1.dp, colors.ink1.copy(alpha = SWATCH_HAIRLINE_ALPHA), RoundedCornerShape(9.dp)),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = role.label,
                    style = Shadow.type.rowTitle,
                    color = colors.ink1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (edited) {
                        buildAnnotatedString {
                            append("Edited · was ")
                            withStyle(SpanStyle(fontFamily = JetBrainsMonoFamily)) { append(savedColor.toHex()) }
                        }
                    } else {
                        buildAnnotatedString { append(role.hint) }
                    },
                    style = Shadow.type.caption,
                    color = if (edited) colors.amberText else colors.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(text = color.toHex(), style = Shadow.type.monoMedium, color = colors.ink2)
            Icon(
                imageVector = ShadowIcons.ChevronDown,
                contentDescription = null,
                tint = colors.ink3,
                modifier = Modifier
                    .size(18.dp)
                    .rotate(chevron),
            )
        }
        AnimatedVisibility(
            visible = open,
            enter = expandVertically(shadowTween(ShadowMotion.Surface), expandFrom = Alignment.Top) +
                fadeIn(shadowTween(ShadowMotion.Small, delayMillis = EDITOR_FADE_DELAY)),
            exit = shrinkVertically(shadowTween(ShadowMotion.Surface), shrinkTowards = Alignment.Top) +
                fadeOut(shadowTween(EDITOR_FADE_OUT)),
        ) {
            ColorEditor(role, color, savedColor, onColorChange)
        }
    }
}

@Composable
private fun ColorEditor(role: PaletteRole, color: Int, savedColor: Int, onColorChange: (Int) -> Unit) {
    val colors = Shadow.colors
    val r = color.red()
    val g = color.green()
    val b = color.blue()
    val edited = color != savedColor
    Column(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier
                    .size(52.dp)
                    .clip(ShadowShapes.TileLarge)
                    .border(1.dp, colors.ink1.copy(alpha = SWATCH_HAIRLINE_ALPHA), ShadowShapes.TileLarge)
                    .semantics {
                        contentDescription = if (edited) {
                            "${role.label}: saved ${savedColor.toHex()}, new ${color.toHex()}"
                        } else {
                            "${role.label}: ${color.toHex()}"
                        }
                    },
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(52.dp)
                        .background(Color(savedColor)),
                )
                Box(
                    Modifier
                        .weight(1f)
                        .height(52.dp)
                        .background(Color(color)),
                )
            }
            HexField(role = role, color = color, onColorChange = onColorChange, modifier = Modifier.weight(1f))
        }
        ChannelSlider(
            label = "H",
            value = color.hue(),
            max = DEGREES - 1,
            valueText = "${color.hue()}°",
            contentDescription = "${role.label} hue",
            track = Brush.horizontalGradient(HueStops),
            onValueChange = { onColorChange(color.withHue(it.toFloat())) },
        )
        ChannelSlider(
            label = "R",
            value = r,
            max = CHANNEL_MAX,
            valueText = r.toString(),
            contentDescription = "${role.label} red channel",
            track = Brush.horizontalGradient(listOf(Color(rgb(0, g, b)), Color(rgb(CHANNEL_MAX, g, b)))),
            onValueChange = { onColorChange(rgb(it, g, b)) },
        )
        ChannelSlider(
            label = "G",
            value = g,
            max = CHANNEL_MAX,
            valueText = g.toString(),
            contentDescription = "${role.label} green channel",
            track = Brush.horizontalGradient(listOf(Color(rgb(r, 0, b)), Color(rgb(r, CHANNEL_MAX, b)))),
            onValueChange = { onColorChange(rgb(r, it, b)) },
        )
        ChannelSlider(
            label = "B",
            value = b,
            max = CHANNEL_MAX,
            valueText = b.toString(),
            contentDescription = "${role.label} blue channel",
            track = Brush.horizontalGradient(listOf(Color(rgb(r, g, 0)), Color(rgb(r, g, CHANNEL_MAX)))),
            onValueChange = { onColorChange(rgb(r, g, it)) },
        )
    }
}

/**
 * `HEX # FF9F1C` field. Keeps only hex digits (max 6, upper case); a complete value goes to the draft
 * at once, an incomplete one shows "n/6" and is dropped when the field loses focus.
 */
@Composable
private fun HexField(role: PaletteRole, color: Int, onColorChange: (Int) -> Unit, modifier: Modifier) {
    val colors = Shadow.colors
    val current = color.toHex().drop(1)
    var text by remember(current) { mutableStateOf(current) }
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val border by animateColorAsState(
        targetValue = if (focused) colors.amber else colors.line2,
        animationSpec = shadowTween(ShadowMotion.Small, ShadowMotion.Ease),
        label = "hex-border",
    )
    val ring = colors.amber.copy(alpha = if (focused) FOCUS_RING_ALPHA else 0f)
    val shape = ShadowShapes.Input
    val mono = Shadow.type.monoInput.copy(fontSize = 15.sp, fontWeight = FontWeight.Normal)
    Row(
        modifier = modifier
            .drawBehind {
                val spread = 3.dp.toPx()
                drawRoundRect(
                    color = ring,
                    topLeft = Offset(-spread, -spread),
                    size = Size(size.width + spread * 2, size.height + spread * 2),
                    cornerRadius = CornerRadius(14.dp.toPx() + spread),
                )
            }
            .height(52.dp)
            .clip(shape)
            .background(colors.surface2)
            .border(1.dp, border, shape)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("HEX", style = Shadow.type.overline, color = colors.ink3)
        Text("#", style = mono, color = colors.ink3)
        BasicTextField(
            value = text,
            onValueChange = { input ->
                val clean = sanitizeHex(input)
                text = clean
                parseHex(clean)?.let(onColorChange)
            },
            singleLine = true,
            textStyle = mono.copy(color = colors.ink1, letterSpacing = 0.04.em),
            cursorBrush = SolidColor(colors.amber),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { state ->
                    if (focused && !state.isFocused) text = current
                    focused = state.isFocused
                }
                .semantics { contentDescription = "${role.label} hex value" },
        )
        if (text.length != HEX_LENGTH) {
            Text("${text.length}/$HEX_LENGTH", style = Shadow.type.monoS, color = colors.amberText)
        }
    }
}

/**
 * 24 dp gradient track with a 26 dp thumb (tap or drag; vertical drags scroll the page). Exposes a
 * slider to TalkBack ([setProgress]).
 */
@Composable
private fun ChannelSlider(
    label: String,
    value: Int,
    max: Int,
    valueText: String,
    contentDescription: String,
    track: Brush,
    onValueChange: (Int) -> Unit,
) {
    val colors = Shadow.colors
    val currentOnChange by rememberUpdatedState(onValueChange)
    val thumbFill = colors.ink1
    val thumbEdge = colors.bg
    val trackEdge = colors.ink1.copy(alpha = TRACK_HAIRLINE)
    val thumbRing = colors.ink1.copy(alpha = THUMB_RING)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = Shadow.type.monoS.copy(fontWeight = FontWeight.Medium),
            color = colors.ink3,
            modifier = Modifier.width(14.dp),
        )
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .height(28.dp),
        ) {
            val density = LocalDensity.current
            val thumbPx = with(density) { THUMB.toPx() }
            val widthPx = constraints.maxWidth.toFloat()
            fun valueAt(x: Float): Int {
                val travel = (widthPx - thumbPx).coerceAtLeast(1f)
                return (((x - thumbPx / 2) / travel).coerceIn(0f, 1f) * max).roundToInt()
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .requiredHeight(TOUCH_HEIGHT)
                    .semantics {
                        this.contentDescription = contentDescription
                        stateDescription = valueText
                        progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), 0f..max.toFloat(), max - 1)
                        setProgress { target ->
                            currentOnChange(target.roundToInt().coerceIn(0, max))
                            true
                        }
                    }
                    .pointerInput(max, widthPx) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                            if (drag != null) {
                                currentOnChange(valueAt(drag.position.x))
                                horizontalDrag(drag.id) { change ->
                                    currentOnChange(valueAt(change.position.x))
                                    change.consume()
                                }
                            } else {
                                val up = currentEvent.changes.firstOrNull { it.id == down.id }
                                if (up != null && up.changedToUp() && !up.isConsumed) {
                                    currentOnChange(valueAt(down.position.x))
                                }
                            }
                        }
                    }
                    .drawBehind {
                        val trackHeight = 24.dp.toPx()
                        val top = (size.height - trackHeight) / 2
                        val radius = CornerRadius(trackHeight / 2)
                        drawRoundRect(track, Offset(0f, top), Size(size.width, trackHeight), radius)
                        drawRoundRect(
                            color = trackEdge,
                            topLeft = Offset(0.5.dp.toPx(), top + 0.5.dp.toPx()),
                            size = Size(size.width - 1.dp.toPx(), trackHeight - 1.dp.toPx()),
                            cornerRadius = radius,
                            style = Stroke(1.dp.toPx()),
                        )
                        val fraction = value / max.toFloat()
                        val center = Offset(thumbPx / 2 + fraction * (size.width - thumbPx), size.height / 2)
                        val r = thumbPx / 2
                        drawCircle(thumbRing, radius = r + 1.dp.toPx(), center = center)
                        drawCircle(thumbEdge, radius = r, center = center)
                        drawCircle(thumbFill, radius = r - 3.dp.toPx(), center = center)
                    },
            )
        }
        Text(
            text = valueText,
            style = Shadow.type.mono,
            color = colors.ink2,
            textAlign = TextAlign.End,
            modifier = Modifier.width(40.dp),
        )
    }
}

/** Spectrum of the hue strip (computed, not theme colors). */
private val HueStops: List<Color> = (0..DEGREES step HUE_STOP_STEP).map { hue ->
    Color.hsl(hue.toFloat() % DEGREES, HUE_STRIP_SATURATION, HUE_STRIP_LIGHTNESS)
}

private val THUMB = 26.dp
private val TOUCH_HEIGHT = 40.dp
private const val HEX_LENGTH = 6
private const val HALF_TURN = 180f
private const val PRESET_RING_MS = 240
private const val ROLE_BG_MS = 240
private const val CHEVRON_MS = 300
private const val SWATCH_MS = 300
private const val EDITOR_FADE_DELAY = 90
private const val EDITOR_FADE_OUT = 180
private const val PRESET_HAIRLINE = 0.16f
private const val FOCUS_RING_ALPHA = 0.16f
private const val TRACK_HAIRLINE = 0.10f
private const val THUMB_RING = 0.35f
private const val HUE_STOP_STEP = 60
private const val HUE_STRIP_SATURATION = 0.9f
private const val HUE_STRIP_LIGHTNESS = 0.66f
