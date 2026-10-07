package com.stansful.sshvpnclient.ui.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stansful.sshvpnclient.ui.theme.Shadow
import com.stansful.sshvpnclient.ui.theme.ShadowMotion
import com.stansful.sshvpnclient.ui.theme.ShadowShapes
import com.stansful.sshvpnclient.ui.theme.mixColors
import com.stansful.sshvpnclient.ui.theme.shadowTween

/**
 * Card: surface-1, 1 dp line border, radius 18, 16 dp padding. Clickable when [onClick] is set
 * (press scale 0.97). [selected] draws a 1.5 dp [accent] (amber) border, a 6 % accent fill over the
 * page and a 4 dp ring; with `role = Role.RadioButton`/`Role.Tab` it also reports the selected state.
 */
@Composable
fun ShadowCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    selected: Boolean = false,
    accent: Color = Color.Unspecified,
    container: Color = Color.Unspecified,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    enabled: Boolean = true,
    role: Role? = Role.Button,
    onClickLabel: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = Shadow.colors
    val accentColor = if (accent != Color.Unspecified) accent else colors.amber
    val baseContainer = if (container != Color.Unspecified) container else colors.surface1
    val selectedBase = if (container != Color.Unspecified) container else colors.bg
    val fill by animateColorAsState(
        targetValue = if (selected) mixColors(accentColor, selectedBase, SELECTED_FILL_ALPHA) else baseContainer,
        animationSpec = shadowTween(SELECTION_MS, ShadowMotion.Ease),
        label = "card-fill",
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) accentColor else colors.line,
        animationSpec = shadowTween(SELECTION_MS, ShadowMotion.Ease),
        label = "card-border",
    )
    val ringColor by animateColorAsState(
        targetValue = if (selected) accentColor.copy(alpha = RING_ALPHA) else accentColor.copy(alpha = 0f),
        animationSpec = shadowTween(SELECTION_MS, ShadowMotion.Ease),
        label = "card-ring",
    )
    val shape = ShadowShapes.Card
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .then(
                if (onClick != null) {
                    Modifier
                        .shadowClickable(
                            interactionSource = interactionSource,
                            enabled = enabled,
                            role = role,
                            onClickLabel = onClickLabel,
                            onClick = onClick,
                        )
                        .then(
                            if (role == Role.RadioButton || role == Role.Tab) {
                                Modifier.semantics { this.selected = selected }
                            } else {
                                Modifier
                            },
                        )
                } else {
                    Modifier
                },
            )
            .then(
                if (ringColor.alpha > 0f) {
                    Modifier.dropShadow(shape) {
                        spread = RING_WIDTH.toPx()
                        radius = 0f
                        color = ringColor
                    }
                } else {
                    Modifier
                },
            )
            .then(if (!colors.isDark) Modifier.dropShadow(shape, DayHairlineShadow) else Modifier)
            .clip(shape)
            .background(fill)
            .border(if (selected) 1.5.dp else 1.dp, borderColor, shape)
            .padding(contentPadding),
        content = content,
    )
}

/**
 * The route/server card of Main.dc.html and Foundations: [ShadowCard] (padding 14) holding a 44 dp
 * [IconTile] (or a custom [leading]), title 16/600 + mono 12/16 ink-3 [subtitle] (both one line,
 * ellipsized, 4 dp apart), a [trailing] slot (e.g. `LatencyMeter`, a `MetaTag`) and a 18 dp ink-3
 * [chevron] (`ShadowIcons.ChevronDown` when the card opens a sheet; null hides it). Clickable when
 * [onClick] is set; [contentDescription] replaces what TalkBack reads (e.g. "Auto-picked route:
 * Amsterdam · NL 03. Show options").
 */
@Composable
fun ItemCard(
    title: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleMono: Boolean = true,
    icon: ImageVector? = null,
    iconTint: Color = Color.Unspecified,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    chevron: ImageVector? = ShadowIcons.ChevronRight,
    selected: Boolean = false,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    val colors = Shadow.colors
    ShadowCard(
        modifier = modifier.then(
            if (contentDescription != null) {
                Modifier.clearAndSetSemantics {
                    this.contentDescription = contentDescription
                    if (onClick != null) {
                        role = Role.Button
                        if (enabled) {
                            onClick(label = null) {
                                onClick()
                                true
                            }
                        } else {
                            disabled()
                        }
                    }
                }
            } else {
                Modifier
            },
        ),
        onClick = onClick,
        selected = selected,
        contentPadding = PaddingValues(14.dp),
        enabled = enabled,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                leading != null -> leading()
                icon != null -> IconTile(icon = icon, tint = iconTint)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = title,
                    style = Shadow.type.titleS,
                    color = colors.ink1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = if (subtitleMono) Shadow.type.monoS else Shadow.type.bodyS,
                        color = colors.ink3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            trailing?.invoke(this)
            if (chevron != null) {
                Icon(chevron, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/**
 * List group: one surface-1 card (radius 18, 1 dp line border) whose children are separated by
 * 1 dp line-2 dividers inset [dividerInset] from the inside of the border. Like the artboards' CSS
 * boxes, the border and every divider take up their 1 dp: children sit inside the border (a row's
 * 16 dp padding starts 17 dp from the outer edge) and each divider adds 1 dp between two rows. Put
 * [ListRow]/[SwitchRow] or any composables inside; children that emit nothing get no divider.
 * [showDividers] = false stacks the children directly (no divider, no gap).
 */
@Composable
fun ListGroup(
    modifier: Modifier = Modifier,
    dividerInset: Dp = 16.dp,
    showDividers: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = Shadow.colors
    val shape = ShadowShapes.Card
    val dividerPositions = remember { mutableStateOf(IntArray(0)) }
    val dividerColor = colors.line2
    Layout(
        content = content,
        modifier = modifier
            .then(if (!colors.isDark) Modifier.dropShadow(shape, DayHairlineShadow) else Modifier)
            .clip(shape)
            .background(colors.surface1)
            .border(GROUP_LINE, colors.line, shape)
            .drawWithContent {
                drawContent()
                if (showDividers) {
                    val inset = GROUP_LINE.toPx() + dividerInset.toPx()
                    val stroke = GROUP_LINE.toPx()
                    dividerPositions.value.forEach { y ->
                        drawLine(
                            color = dividerColor,
                            start = Offset(inset, y + stroke / 2),
                            end = Offset(size.width - inset, y + stroke / 2),
                            strokeWidth = stroke,
                        )
                    }
                }
            },
    ) { measurables, constraints ->
        val line = GROUP_LINE.roundToPx()
        val divider = if (showDividers) line else 0
        val bounded = constraints.hasBoundedWidth
        val innerMaxWidth = if (bounded) (constraints.maxWidth - 2 * line).coerceAtLeast(0) else constraints.maxWidth
        val childConstraints = constraints.copy(
            minWidth = if (bounded) innerMaxWidth else 0,
            maxWidth = innerMaxWidth,
            minHeight = 0,
            maxHeight = if (constraints.hasBoundedHeight) {
                (constraints.maxHeight - 2 * line).coerceAtLeast(0)
            } else {
                constraints.maxHeight
            },
        )
        val placeables = measurables.map { it.measure(childConstraints) }
        val shown = placeables.count { it.height > 0 }
        val width = if (bounded) constraints.maxWidth else (placeables.maxOfOrNull { it.width } ?: 0) + 2 * line
        val content = placeables.sumOf { it.height } + divider * (shown - 1).coerceAtLeast(0)
        val height = (content + 2 * line).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            var y = line
            val boundaries = ArrayList<Int>()
            placeables.forEach { placeable ->
                if (placeable.height > 0) {
                    if (y > line) {
                        if (showDividers) boundaries.add(y)
                        y += divider
                    }
                    placeable.placeRelative(line, y)
                    y += placeable.height
                } else {
                    placeable.placeRelative(line, y)
                }
            }
            val next = boundaries.toIntArray()
            if (!next.contentEquals(dividerPositions.value)) dividerPositions.value = next
        }
    }
}

/**
 * List row (min 56 dp, 64 with a tile): optional leading icon tile (40 dp, surface-2) or custom
 * [leading], title 15/500, subtitle 13 ink-3 (mono when [subtitleMono]), trailing [value] 13/600 ink-2,
 * custom [trailing] and/or chevron. Pressed rows tint to surface-3.
 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leadingIcon: ImageVector? = null,
    leading: (@Composable () -> Unit)? = null,
    value: String? = null,
    showChevron: Boolean = false,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    titleColor: Color = Color.Unspecified,
    subtitleMono: Boolean = false,
    subtitleMaxLines: Int = 1,
    role: Role? = Role.Button,
) {
    val colors = Shadow.colors
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val background by animateColorAsState(
        targetValue = if (pressed && onClick != null) colors.surface3 else colors.surface3.copy(alpha = 0f),
        animationSpec = shadowTween(ROW_PRESS_MS, ShadowMotion.Ease),
        label = "row-press",
    )
    val hasTile = leading != null || leadingIcon != null
    Row(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = ShadowFocusIndication,
                        enabled = enabled,
                        role = role,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )
            .background(background)
            .heightIn(min = if (hasTile) 64.dp else ShadowDimensRowMin)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            leading != null -> leading()
            leadingIcon != null -> IconTile(icon = leadingIcon, size = 40.dp, iconSize = 20.dp)
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = Shadow.type.rowTitle,
                color = if (titleColor != Color.Unspecified) titleColor else colors.ink1,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = if (subtitleMono) Shadow.type.monoS else Shadow.type.bodyS,
                    color = colors.ink3,
                    maxLines = subtitleMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (value != null) {
            Text(text = value, style = Shadow.type.label, color = colors.ink2, maxLines = 1)
        }
        trailing?.invoke(this)
        if (showChevron) {
            Icon(
                imageVector = ShadowIcons.ChevronRight,
                contentDescription = null,
                tint = colors.ink3,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * Section header: 11/14 600 caps overline in ink-3, margins 24 top · 4 sides · 8 bottom.
 * Optional [trailingText]/[trailingIcon] on the right (e.g. "Routes mode"), or a custom [trailing].
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    topPadding: Dp = 24.dp,
    trailingText: String? = null,
    trailingIcon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = Shadow.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = topPadding, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title.uppercase(),
            style = Shadow.type.overline,
            color = colors.ink3,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        if (trailingText != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (trailingIcon != null) {
                    Icon(trailingIcon, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(12.dp))
                }
                Text(
                    text = trailingText,
                    style = Shadow.type.overline.copy(letterSpacing = Shadow.type.label.letterSpacing),
                    color = colors.ink3,
                )
            }
        }
        trailing?.invoke()
    }
}

/**
 * Rounded icon tile (surface-2 by default): 44 dp/radius 14 for cards, 40 or 36 dp/radius 12 for rows,
 * 56 dp/radius 16 for empty states ([cornerRadius] overrides). [bordered] adds the 1 dp line border of
 * the empty-state tiles. Tint and container changes animate 400 ms.
 */
@Composable
fun IconTile(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = 22.dp,
    tint: Color = Color.Unspecified,
    container: Color = Color.Unspecified,
    cornerRadius: Dp = Dp.Unspecified,
    bordered: Boolean = false,
) {
    val colors = Shadow.colors
    val iconTint by animateColorAsState(
        targetValue = if (tint != Color.Unspecified) tint else colors.ink2,
        animationSpec = shadowTween(ShadowMotion.ColorFade, ShadowMotion.Ease),
        label = "tile-tint",
    )
    val fill by animateColorAsState(
        targetValue = if (container != Color.Unspecified) container else colors.surface2,
        animationSpec = shadowTween(ShadowMotion.ColorFade, ShadowMotion.Ease),
        label = "tile-fill",
    )
    val radius = when {
        cornerRadius != Dp.Unspecified -> cornerRadius
        size >= 56.dp -> 16.dp
        size >= 44.dp -> 14.dp
        else -> 12.dp
    }
    val shape = RoundedCornerShape(radius)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(fill)
            .then(if (bordered) Modifier.border(1.dp, colors.line, shape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(iconSize))
    }
}

/** A standalone 1 dp line-2 divider inset 16 dp (for custom groups). */
@Composable
fun ListDivider(modifier: Modifier = Modifier, inset: Dp = 16.dp) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = inset)
            .heightIn(min = 1.dp, max = 1.dp)
            .background(Shadow.colors.line2),
    )
}

private val ShadowDimensRowMin = 56.dp
private val GROUP_LINE = 1.dp
private val RING_WIDTH = 4.dp
private const val RING_ALPHA = 0.10f
private const val SELECTED_FILL_ALPHA = 0.06f
private const val SELECTION_MS = 260
private const val ROW_PRESS_MS = 160
