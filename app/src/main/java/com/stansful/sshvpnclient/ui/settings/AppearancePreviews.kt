package com.stansful.sshvpnclient.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stansful.sshvpnclient.domain.model.CustomThemeColors
import com.stansful.sshvpnclient.ui.designsystem.ShadowFocusIndication
import com.stansful.sshvpnclient.ui.designsystem.ShadowIcons
import com.stansful.sshvpnclient.ui.designsystem.pressScale
import com.stansful.sshvpnclient.ui.designsystem.strokeIcon
import com.stansful.sshvpnclient.ui.theme.JetBrainsMonoFamily
import com.stansful.sshvpnclient.ui.theme.Shadow
import com.stansful.sshvpnclient.ui.theme.ShadowMotion
import com.stansful.sshvpnclient.ui.theme.ShadowShapes
import com.stansful.sshvpnclient.ui.theme.mixColors
import com.stansful.sshvpnclient.ui.theme.readableInkOn
import com.stansful.sshvpnclient.ui.theme.shadowTween

/**
 * A theme choice tile: a stage with a mini phone in [base] colors (the System tile cuts [overlay] in
 * diagonally), the name and a radio mark. [edited] shows the "Edited" badge (Custom with a dirty draft).
 */
@Composable
internal fun ThemeTile(
    name: String,
    selected: Boolean,
    base: CustomThemeColors,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    overlay: CustomThemeColors? = null,
    edited: Boolean = false,
) {
    val colors = Shadow.colors
    val border by animateColorAsState(
        targetValue = if (selected) colors.amber else colors.line,
        animationSpec = shadowTween(TILE_MS, ShadowMotion.Ease),
        label = "tile-border",
    )
    val ring by animateColorAsState(
        targetValue = if (selected) colors.amber else colors.amber.copy(alpha = 0f),
        animationSpec = shadowTween(TILE_MS, ShadowMotion.Ease),
        label = "tile-ring",
    )
    val interaction = remember { MutableInteractionSource() }
    val shape = ShadowShapes.Card
    Box(
        modifier = modifier
            .pressScale(interaction, TILE_PRESS_SCALE)
            .drawBehind {
                val stroke = 1.dp.toPx()
                drawRoundRect(
                    color = ring,
                    topLeft = Offset(-stroke / 2, -stroke / 2),
                    size = Size(size.width + stroke, size.height + stroke),
                    cornerRadius = CornerRadius(18.dp.toPx() + stroke / 2),
                    style = Stroke(stroke),
                )
            }
            .clip(shape)
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = ShadowFocusIndication,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .background(colors.surface1)
            .border(1.dp, border, shape)
            .padding(TILE_PADDING),
    ) {
        Column {
            Stage(selected = selected, base = base, overlay = overlay)
            Row(
                modifier = Modifier
                    .heightIn(min = 22.dp)
                    .padding(start = 6.dp, end = 4.dp, top = 9.dp, bottom = 1.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = name,
                    style = Shadow.type.titleS.copy(fontSize = 15.sp, lineHeight = 20.sp),
                    color = colors.ink1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                RadioMark(selected)
            }
        }
        AnimatedVisibility(
            visible = edited,
            enter = scaleIn(shadowTween(POP_MS, ShadowMotion.Spring), initialScale = POP_FROM) +
                fadeIn(shadowTween(POP_MS)),
            exit = scaleOut(shadowTween(ShadowMotion.Small)) + fadeOut(shadowTween(ShadowMotion.Small)),
            modifier = Modifier.padding(start = 2.dp, top = 2.dp),
        ) {
            Box(
                modifier = Modifier
                    .drawBehind {
                        drawRoundRect(
                            color = colors.surface1,
                            topLeft = Offset(-2.dp.toPx(), -2.dp.toPx()),
                            size = Size(size.width + 4.dp.toPx(), size.height + 4.dp.toPx()),
                            cornerRadius = CornerRadius(size.height),
                        )
                    }
                    .height(20.dp)
                    .background(colors.amber, CircleShape)
                    .padding(horizontal = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Edited", style = Shadow.type.overline.copy(letterSpacing = 0.sp), color = colors.onAmber)
            }
        }
    }
}

@Composable
private fun RadioMark(selected: Boolean) {
    val colors = Shadow.colors
    Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
        if (!selected) {
            Box(
                Modifier
                    .size(22.dp)
                    .border(1.5.dp, colors.ink3.copy(alpha = RING_ALPHA), CircleShape),
            )
        }
        AnimatedVisibility(
            visible = selected,
            enter = scaleIn(shadowTween(POP_MS, ShadowMotion.Spring), initialScale = POP_FROM) +
                fadeIn(shadowTween(POP_MS)),
            exit = fadeOut(shadowTween(ShadowMotion.Small)),
        ) {
            Box(
                Modifier
                    .size(22.dp)
                    .background(colors.amber, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(BoldCheck, contentDescription = null, tint = colors.onAmber, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
private fun Stage(selected: Boolean, base: CustomThemeColors, overlay: CustomThemeColors?) {
    val colors = Shadow.colors
    val glow by animateColorAsState(
        targetValue = if (selected) mixColors(colors.amber, colors.surface2, STAGE_GLOW) else colors.surface2,
        animationSpec = shadowTween(TILE_MS, ShadowMotion.Ease),
        label = "stage-glow",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(68.dp)
            .clip(ShadowShapes.Tile)
            .drawBehind {
                drawRect(colors.surface2)
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(glow, colors.surface2),
                        center = Offset(size.width / 2, 0f),
                        radius = size.width * STAGE_GLOW_RADIUS,
                    ),
                )
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        MiniPhone(base, Modifier.offset(y = 10.dp))
        if (overlay != null) {
            MiniPhone(
                overlay,
                Modifier
                    .offset(y = 10.dp)
                    .clip(TopLeftTriangle),
            )
        }
    }
}

/** 64×100 sketch of Home in a palette (status dot, title bar, orb, status bar, a card). */
@Composable
private fun MiniPhone(palette: CustomThemeColors, modifier: Modifier = Modifier) {
    val background = palette.background.toColor()
    val text = palette.onSurface.toColor()
    val primary = palette.primary.toColor()
    val success = palette.secondary.toColor()
    val shape = RoundedCornerShape(13.dp)
    Column(
        modifier = modifier
            .size(width = 64.dp, height = 100.dp)
            .clip(shape)
            .background(background)
            .border(PHONE_BORDER, mixColors(text, background, PHONE_EDGE), shape)
            .padding(PHONE_BORDER),
    ) {
        Row(
            modifier = Modifier.padding(start = 7.dp, top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(4.dp).background(success, CircleShape))
            Box(Modifier.size(width = 17.dp, height = 4.dp).background(text, RoundedCornerShape(2.dp)))
        }
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .size(20.dp)
                .align(Alignment.CenterHorizontally)
                .border(2.dp, primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(6.dp).background(primary, CircleShape))
        }
        Box(
            Modifier
                .padding(top = 4.dp)
                .size(width = 22.dp, height = 4.dp)
                .align(Alignment.CenterHorizontally)
                .background(success, CircleShape),
        )
        Row(
            modifier = Modifier
                .padding(start = 6.dp, end = 6.dp, top = 5.dp)
                .fillMaxWidth()
                .height(13.dp)
                .background(palette.surface.toColor(), RoundedCornerShape(4.dp))
                .border(1.dp, palette.outline.toColor(), RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(5.dp).background(palette.surfaceVariant.toColor(), RoundedCornerShape(2.dp)))
            Box(
                Modifier
                    .size(width = 18.dp, height = 3.dp)
                    .background(text.copy(alpha = MINI_MUTED), RoundedCornerShape(2.dp)),
            )
        }
    }
}

private val TopLeftTriangle = GenericShape { size, _ ->
    moveTo(0f, 0f)
    lineTo(size.width, 0f)
    lineTo(0f, size.height)
    close()
}

/**
 * Live preview of the draft: the wordmark with "Check tunnel", a connected orb with its status, and an
 * unavailable route card — every role of the palette in use.
 */
@Composable
internal fun PaletteLivePreview(draft: CustomThemeColors, modifier: Modifier = Modifier) {
    val background = draft.background.toColor().animated("bg")
    val surface = draft.surface.toColor().animated("surface")
    val surfaceVariant = draft.surfaceVariant.toColor().animated("sv")
    val text = draft.onSurface.toColor().animated("text")
    val outline = draft.outline.toColor().animated("outline")
    val primary = draft.primary.toColor().animated("primary")
    val success = draft.secondary.toColor().animated("success")
    val error = draft.error.toColor().animated("error")
    val muted = text.copy(alpha = PREVIEW_MUTED)
    val onPrimary = readableInkOn(primary)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(ShadowShapes.Tile)
            .background(background)
            .semantics { contentDescription = "Live preview of the draft palette" }
            .drawBehind {
                val radius = 80.dp.toPx()
                drawCircle(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0f to primary.copy(alpha = HALO_ALPHA),
                            HALO_FADE to primary.copy(alpha = 0f),
                        ),
                        center = Offset(-36.dp.toPx() + radius, 20.dp.toPx() + radius),
                        radius = radius,
                    ),
                    radius = radius,
                    center = Offset(-36.dp.toPx() + radius, 20.dp.toPx() + radius),
                )
            }
            .padding(14.dp),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).background(success, CircleShape))
                    Text(
                        text = "shadow",
                        style = Shadow.type.wordmark.copy(fontSize = 17.sp, lineHeight = 22.sp),
                        color = text,
                    )
                }
                Row(
                    modifier = Modifier
                        .height(28.dp)
                        .background(primary, CircleShape)
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(ShadowIcons.Shield, null, tint = onPrimary, modifier = Modifier.size(13.dp))
                    Text("Check tunnel", style = Shadow.type.label.copy(fontSize = 12.sp), color = onPrimary)
                }
            }
            Row(
                modifier = Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .border(3.dp, primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .background(surface, CircleShape)
                            .border(1.dp, outline, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(ShadowIcons.Power, null, tint = primary, modifier = Modifier.size(18.dp))
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier
                            .height(22.dp)
                            .background(success.copy(alpha = SUCCESS_TINT), CircleShape)
                            .padding(horizontal = 9.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(6.dp).background(success, CircleShape))
                        Text("Connected", style = PreviewTag, color = success)
                    }
                    Text(
                        text = "Connected to Home VPS",
                        style = Shadow.type.titleS.copy(fontSize = 15.sp, lineHeight = 20.sp),
                        color = text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "root@vps.example.net:22",
                        style = Shadow.type.monoS.copy(fontSize = 11.sp, lineHeight = 14.sp),
                        color = muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(
                modifier = Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth()
                    .background(surface, RoundedCornerShape(14.dp))
                    .border(1.dp, outline, RoundedCornerShape(14.dp))
                    .padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(surfaceVariant, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(ShadowIcons.Routes, null, tint = muted, modifier = Modifier.size(16.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Vilnius · LT 01",
                        style = Shadow.type.label,
                        color = text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "vmess · GRPC · TLS",
                        style = Shadow.type.monoS.copy(fontSize = 11.sp, lineHeight = 14.sp),
                        color = muted,
                        maxLines = 1,
                    )
                }
                Box(
                    modifier = Modifier
                        .height(22.dp)
                        .background(error.copy(alpha = ERROR_TINT), CircleShape)
                        .padding(horizontal = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Unavailable", style = PreviewTag, color = error)
                }
            }
        }
    }
}

/** "Text on Surface · 13.2:1 · AAA" with an icon, plus the dark/light base pill. */
@Composable
internal fun ContrastRow(draft: CustomThemeColors, modifier: Modifier = Modifier) {
    val colors = Shadow.colors
    val ratio = contrast(draft.onSurface, draft.surface)
    val shown = formatRatio(ratio)
    val (ink, pre, post) = when {
        ratio >= AAA -> Triple(colors.mintText, "", " · AAA")
        ratio >= AA -> Triple(colors.mintText, "", " · AA")
        ratio >= LOW -> Triple(colors.amberText, "Low · ", "")
        else -> Triple(colors.coralText, "Too low · ", "")
    }
    val darkBase = isDarkBase(draft.background)
    Row(
        modifier = modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (ratio >= AA) ShadowIcons.CheckCircle else ShadowIcons.Warning,
            contentDescription = null,
            tint = ink,
            modifier = Modifier.size(16.dp),
        )
        val fadeSpec = shadowTween<Float>(ShadowMotion.Swap)
        val slideSpec = shadowTween<IntOffset>(ShadowMotion.Swap)
        // Only a change of the verdict ("AA · " → "Too low · ") animates; the ratio itself updates in place.
        AnimatedContent(
            targetState = Triple(pre, post, ink),
            transitionSpec = {
                (fadeIn(fadeSpec) + slideInVertically(slideSpec) { it / 3 }).togetherWith(fadeOut(snap()))
            },
            modifier = Modifier.weight(1f),
            label = "contrast",
        ) { (verdictPre, verdictPost, verdictInk) ->
            val annotated = buildAnnotatedString {
                append("Text on Surface · ")
                withStyle(SpanStyle(color = verdictInk, fontWeight = FontWeight.SemiBold)) {
                    append(verdictPre)
                    withStyle(SpanStyle(fontFamily = JetBrainsMonoFamily, fontWeight = FontWeight.Medium)) {
                        append(shown)
                    }
                    append(verdictPost)
                }
            }
            Text(text = annotated, style = Shadow.type.bodyS, color = colors.ink2)
        }
        Box(
            modifier = Modifier
                .height(24.dp)
                .background(colors.surface3, CircleShape)
                .padding(horizontal = 9.dp)
                .semantics {
                    contentDescription = if (darkBase) {
                        "Dark base: background is dark, so system bar icons stay light"
                    } else {
                        "Light base: background is light, so system bar icons turn dark"
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (darkBase) "Dark base" else "Light base",
                style = Shadow.type.caption.copy(fontWeight = FontWeight.SemiBold),
                color = colors.ink2,
            )
        }
    }
}

@Composable
private fun Color.animated(label: String): Color {
    val value by animateColorAsState(this, shadowTween(PREVIEW_MS, ShadowMotion.Ease), label = label)
    return value
}

internal val BoldCheck = strokeIcon("check-bold", "M5 12.5l4.5 4.5L19 7.5", strokeWidth = 2.6f)

private val PreviewTag
    @Composable get() = Shadow.type.overline.copy(letterSpacing = 0.sp)

private val PHONE_BORDER = 1.5.dp

/** 8 dp padding inside the tile's 1 dp border. */
private val TILE_PADDING = 9.dp
private const val TILE_MS = 240
private const val POP_MS = 320
private const val POP_FROM = 0.4f
private const val PREVIEW_MS = 300
private const val TILE_PRESS_SCALE = 0.98f
private const val RING_ALPHA = 0.35f
private const val STAGE_GLOW = 0.22f
private const val STAGE_GLOW_RADIUS = 0.86f
private const val PHONE_EDGE = 0.2f
private const val MINI_MUTED = 0.5f
private const val PREVIEW_MUTED = 0.74f
private const val HALO_ALPHA = 0.26f
private const val HALO_FADE = 0.68f
private const val SUCCESS_TINT = 0.18f
private const val ERROR_TINT = 0.16f
private const val AAA = 7f
private const val AA = 4.5f
private const val LOW = 3f
