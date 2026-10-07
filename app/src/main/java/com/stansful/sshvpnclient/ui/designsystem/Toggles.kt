package com.stansful.sshvpnclient.ui.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.stansful.sshvpnclient.ui.theme.Shadow
import com.stansful.sshvpnclient.ui.theme.ShadowMotion
import com.stansful.sshvpnclient.ui.theme.shadowTween
import kotlin.math.roundToInt

/**
 * 52×32 switch: mint track + mint-ink knob when on, surface-3 + ink-3 when off; the 24 dp knob springs
 * 20 dp in 200 ms. With [onCheckedChange] null it is purely visual (use inside [SwitchRow]).
 */
@Composable
fun ShadowSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    val colors = Shadow.colors
    val track by animateColorAsState(
        targetValue = if (checked) colors.mint else colors.surface3,
        animationSpec = shadowTween(ShadowMotion.Small, ShadowMotion.Ease),
        label = "switch-track",
    )
    val knob by animateColorAsState(
        targetValue = if (checked) colors.onMint else colors.ink3,
        animationSpec = shadowTween(ShadowMotion.Small, ShadowMotion.Ease),
        label = "switch-knob",
    )
    val knobOffset by animateDpAsState(
        targetValue = if (checked) KNOB_TRAVEL else 0.dp,
        animationSpec = shadowTween(ShadowMotion.Small, ShadowMotion.Spring),
        label = "switch-offset",
    )
    val interactive = if (onCheckedChange != null) {
        Modifier
            .minimumInteractiveComponentSize()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                interactionSource = remember { MutableInteractionSource() },
                indication = ShadowFocusIndication,
                onValueChange = onCheckedChange,
            )
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                },
            )
    } else {
        Modifier
    }
    Box(
        modifier = modifier
            .then(interactive)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .size(width = 52.dp, height = 32.dp)
            .clip(CircleShape)
            .background(track)
            .padding(4.dp),
    ) {
        Box(
            Modifier
                .offset { IntOffset(knobOffset.roundToPx(), 0) }
                .size(24.dp)
                .clip(CircleShape)
                .background(knob),
        )
    }
}

/**
 * Settings row whose whole surface is the switch (Role.Switch): optional 40 dp icon tile, title
 * 15/500, optional [badge] tag ("Server mode"), helper text below that swaps when it changes.
 */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    badge: String? = null,
    enabled: Boolean = true,
) {
    val colors = Shadow.colors
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val background by animateColorAsState(
        targetValue = if (pressed) colors.surface3 else colors.surface3.copy(alpha = 0f),
        animationSpec = shadowTween(ROW_PRESS_MS, ShadowMotion.Ease),
        label = "switch-row-press",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                interactionSource = interactionSource,
                indication = ShadowFocusIndication,
                onValueChange = onCheckedChange,
            )
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .background(background)
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = if (subtitle != null) Alignment.Top else Alignment.CenterVertically,
    ) {
        if (icon != null) {
            IconTile(
                icon = icon,
                size = 40.dp,
                iconSize = 20.dp,
                modifier = if (subtitle != null) Modifier.offset(y = (-4).dp) else Modifier,
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                modifier = Modifier.heightIn(min = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        style = Shadow.type.rowTitle,
                        color = colors.ink1,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (badge != null) MetaTag(text = badge, size = MetaTagSize.Compact)
                }
                ShadowSwitch(checked = checked, onCheckedChange = null, enabled = true)
            }
            if (subtitle != null) {
                SwapText(text = subtitle, style = Shadow.type.bodyS, color = colors.ink3, maxLines = Int.MAX_VALUE)
            }
        }
    }
}

/**
 * List row whose whole surface is a checkbox (Role.Checkbox): optional [leading] (e.g. a 36 dp app
 * avatar) or 40 dp [icon] tile, title 15/500 (+ optional [badge] tag), subtitle 13/18 ink-3 (mono 12/16
 * with [subtitleMono]), [ShadowCheckbox] on the right. Checked rows get a 6 % [tone] wash, pressed rows
 * surface-2. Min 60 dp, padding 12/16. Use inside [ListGroup].
 */
@Composable
fun CheckboxRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleMono: Boolean = false,
    icon: ImageVector? = null,
    leading: (@Composable () -> Unit)? = null,
    badge: String? = null,
    tone: CheckboxTone = CheckboxTone.Amber,
    enabled: Boolean = true,
) {
    val colors = Shadow.colors
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val wash = if (tone == CheckboxTone.Sky) colors.sky else colors.amber
    val background by animateColorAsState(
        targetValue = when {
            pressed -> colors.surface2
            checked -> wash.copy(alpha = CHECKED_WASH_ALPHA)
            else -> wash.copy(alpha = 0f)
        },
        animationSpec = shadowTween(ShadowMotion.Small, ShadowMotion.Ease),
        label = "checkbox-row",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                interactionSource = interactionSource,
                indication = ShadowFocusIndication,
                onValueChange = onCheckedChange,
            )
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .background(background)
            .heightIn(min = 60.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            leading != null -> leading()
            icon != null -> IconTile(icon = icon, size = 40.dp, iconSize = 20.dp)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = Shadow.type.rowTitle,
                    color = colors.ink1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (badge != null) MetaTag(text = badge, size = MetaTagSize.Compact)
            }
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
        ShadowCheckbox(checked = checked, onCheckedChange = null, tone = tone)
    }
}

/** One option of a [ShadowSegmented] control: label, optional 18 dp icon, optional mono [count] ("3"). */
@Immutable
data class SegmentOption(
    val label: String,
    val icon: ImageVector? = null,
    val count: String? = null,
)

/** Size preset of [ShadowSegmented]. */
enum class SegmentedSize(
    val height: Dp,
    val trackRadius: Dp,
    val thumbRadius: Dp,
) {
    /** 48 dp on surface-1, radius 16 / thumb 12 — Home mode switch, Servers · Keys. */
    Regular(48.dp, 16.dp, 12.dp),

    /** 44 dp on bg, radius 14 / thumb 10 — inside cards (theme picker). */
    Compact(44.dp, 14.dp, 10.dp),
}

/**
 * Segmented control with a sliding surface-3 thumb (360 ms Spring), 18 dp icons + 14/600 labels;
 * active ink-1, inactive ink-3. Exactly the Home mode switch of Main.dc.html.
 */
@Composable
fun ShadowSegmented(
    options: List<SegmentOption>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    size: SegmentedSize = SegmentedSize.Regular,
    enabled: Boolean = true,
    role: Role = Role.Tab,
) {
    val colors = Shadow.colors
    val trackShape = RoundedCornerShape(size.trackRadius)
    val thumbShape = RoundedCornerShape(size.thumbRadius)
    val position by animateFloatAsState(
        targetValue = selectedIndex.coerceIn(0, (options.size - 1).coerceAtLeast(0)).toFloat(),
        animationSpec = shadowTween(ShadowMotion.Thumb, ShadowMotion.Spring),
        label = "segmented-thumb",
    )
    val trackColor = if (size == SegmentedSize.Compact) colors.bg else colors.surface1
    Box(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .height(size.height)
            .then(if (!colors.isDark) Modifier.dropShadow(trackShape, DayHairlineShadow) else Modifier)
            .clip(trackShape)
            .background(trackColor)
            .border(1.dp, colors.line, trackShape)
            // 1 dp border + 4 dp padding: the thumb is 38 dp tall in the 48 dp track, as drawn.
            .padding(5.dp),
    ) {
        if (options.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(1f / options.size)
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, placeable.height) {
                            placeable.placeRelative((position * placeable.width).roundToInt(), 0)
                        }
                    }
                    .clip(thumbShape)
                    .background(colors.surface3),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxSize()
                .selectableGroup(),
        ) {
            options.forEachIndexed { index, option ->
                Segment(
                    option = option,
                    selected = index == selectedIndex,
                    enabled = enabled,
                    role = role,
                    shape = thumbShape,
                    onClick = { onSelect(index) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun Segment(
    option: SegmentOption,
    selected: Boolean,
    enabled: Boolean,
    role: Role,
    shape: RoundedCornerShape,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val colors = Shadow.colors
    val ink by animateColorAsState(
        targetValue = if (selected) colors.ink1 else colors.ink3,
        animationSpec = shadowTween(ShadowMotion.Small, ShadowMotion.Ease),
        label = "segment-ink",
    )
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxHeight()
            .pressScale(interactionSource)
            .clip(shape)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = role,
                interactionSource = interactionSource,
                indication = ShadowFocusIndication,
                onClick = onClick,
            ),
        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (option.icon != null) {
            Icon(option.icon, contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
        }
        Text(
            text = option.label,
            style = Shadow.type.segment,
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (option.count != null) {
            val countInk by animateColorAsState(
                targetValue = if (selected) colors.ink2 else colors.ink3,
                animationSpec = shadowTween(ShadowMotion.Small, ShadowMotion.Ease),
                label = "segment-count",
            )
            Text(text = option.count, style = Shadow.type.monoS.copy(fontWeight = FontWeight.Medium), color = countInk)
        }
    }
}

/** Fill color of a checked [ShadowCheckbox]. */
enum class CheckboxTone {
    /** Amber — "use this app/route". */
    Amber,

    /** Sky — bulk selection (distinct from amber/mint). */
    Sky,
}

/**
 * 22 dp checkbox, radius 7: 1.5 dp ink-3 outline when off, [tone] fill with a bold tick when on
 * (tick pops in with a spring). With [onCheckedChange] null it is purely visual (the row toggles).
 */
@Composable
fun ShadowCheckbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    tone: CheckboxTone = CheckboxTone.Amber,
    enabled: Boolean = true,
) {
    val colors = Shadow.colors
    val fillTarget = if (tone == CheckboxTone.Sky) colors.sky else colors.amber
    val tickColor = if (tone == CheckboxTone.Sky) colors.bg else colors.onAmber
    val fill by animateColorAsState(
        targetValue = if (checked) fillTarget else fillTarget.copy(alpha = 0f),
        animationSpec = shadowTween(ShadowMotion.Small, ShadowMotion.Ease),
        label = "checkbox-fill",
    )
    val border by animateColorAsState(
        targetValue = if (checked) fillTarget else colors.ink3,
        animationSpec = shadowTween(ShadowMotion.Small, ShadowMotion.Ease),
        label = "checkbox-border",
    )
    val pop by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = shadowTween(POP_MS, ShadowMotion.Spring),
        label = "checkbox-pop",
    )
    val shape = RoundedCornerShape(7.dp)
    val interactive = if (onCheckedChange != null) {
        Modifier
            .minimumInteractiveComponentSize()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                interactionSource = remember { MutableInteractionSource() },
                indication = ShadowFocusIndication,
                onValueChange = onCheckedChange,
            )
    } else {
        Modifier
    }
    Box(
        modifier = modifier
            .then(interactive)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .size(22.dp)
            .clip(shape)
            .background(fill)
            .border(1.5.dp, border, shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = BoldCheck,
            contentDescription = null,
            tint = tickColor,
            modifier = Modifier
                .size(15.dp)
                .graphicsLayer {
                    alpha = pop.coerceIn(0f, 1f)
                    val scale = POP_START_SCALE + (1f - POP_START_SCALE) * pop
                    scaleX = scale
                    scaleY = scale
                },
        )
    }
}

/**
 * 22 dp radio: 2 dp ring (ink-3 → amber) with a 10 dp amber dot that springs in when [selected].
 * With [onClick] null it is purely visual (the card/row is the radio).
 */
@Composable
fun ShadowRadio(
    selected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = Shadow.colors
    val ring by animateColorAsState(
        targetValue = if (selected) colors.amber else colors.ink3,
        animationSpec = shadowTween(RADIO_MS, ShadowMotion.Ease),
        label = "radio-ring",
    )
    val dot by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = shadowTween(RADIO_MS, ShadowMotion.Spring),
        label = "radio-dot",
    )
    val interactive = if (onClick != null) {
        Modifier
            .minimumInteractiveComponentSize()
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                interactionSource = remember { MutableInteractionSource() },
                indication = ShadowFocusIndication,
                onClick = onClick,
            )
    } else {
        Modifier
    }
    Box(
        modifier = modifier
            .then(interactive)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .size(22.dp)
            .border(2.dp, ring, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .graphicsLayer {
                    scaleX = dot
                    scaleY = dot
                }
                .clip(CircleShape)
                .background(colors.amber.copy(alpha = if (dot > 0f) 1f else 0f)),
        )
    }
}

private val BoldCheck by lazy { strokeIcon("check-bold", "M5 12.5l4.5 4.5L19 7.5", strokeWidth = 2.6f) }
private val KNOB_TRAVEL = 20.dp
private const val ROW_PRESS_MS = 160
private const val POP_MS = 260
private const val RADIO_MS = 260
private const val POP_START_SCALE = 0.4f
private const val CHECKED_WASH_ALPHA = 0.06f
