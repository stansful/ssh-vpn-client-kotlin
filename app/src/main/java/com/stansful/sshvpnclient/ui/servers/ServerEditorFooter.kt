package com.stansful.sshvpnclient.ui.servers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stansful.sshvpnclient.ui.designsystem.ShadowIcons
import com.stansful.sshvpnclient.ui.designsystem.ShadowSpinner
import com.stansful.sshvpnclient.ui.designsystem.SwapText
import com.stansful.sshvpnclient.ui.designsystem.shadowClickable
import com.stansful.sshvpnclient.ui.theme.Shadow
import com.stansful.sshvpnclient.ui.theme.ShadowMotion
import com.stansful.sshvpnclient.ui.theme.ShadowShapes
import com.stansful.sshvpnclient.ui.theme.shadowTween

/**
 * The server editor's pinned footer: the "Fix N fields" chip (jumps to the first error), the mint
 * [savedLabel] link while saved, the save failure, the save button and the storage note. Pads the
 * navigation bar and the keyboard.
 */
@Composable
internal fun EditorFooter(
    phase: SavePhase,
    invalid: List<ServerField>,
    savedLabel: String,
    onFix: () -> Unit,
    onSave: () -> Unit,
    onOpenServers: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Shadow.colors
    val navLine = colors.navLine
    val showFix = invalid.isNotEmpty() && phase != SavePhase.Saving && phase != SavePhase.Saved
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.navBg)
            .drawBehind {
                drawLine(navLine, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
            }
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime).only(WindowInsetsSides.Bottom))
            .windowInsetsPadding(EditorSideInsets)
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = EDITOR_MAX_WIDTH - 40.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AnimatedVisibility(
                visible = showFix,
                enter = expandVertically(shadowTween(ShadowMotion.Small)) + fadeIn(shadowTween(ShadowMotion.Small)),
                exit = shrinkVertically(shadowTween(ShadowMotion.Small)) + fadeOut(shadowTween(ShadowMotion.Small)),
            ) {
                FixFieldsChip(
                    invalid = invalid,
                    onClick = onFix,
                    modifier = Modifier.verticalMargins(top = (-6).dp, bottom = 4.dp),
                )
            }
            AnimatedVisibility(
                visible = phase == SavePhase.Saved,
                enter = expandVertically(shadowTween(ShadowMotion.Small)) + fadeIn(shadowTween(ShadowMotion.Small)),
                exit = shrinkVertically(shadowTween(ShadowMotion.Small)) + fadeOut(shadowTween(ShadowMotion.Small)),
            ) {
                SavedLink(
                    label = savedLabel,
                    onClick = onOpenServers,
                    modifier = Modifier.verticalMargins(top = (-6).dp, bottom = 4.dp),
                )
            }
            if (phase == SavePhase.Failed) {
                SaveErrorBanner(
                    message = "Couldn’t save. The encrypted storage on this phone didn’t accept the data. " +
                        "Try again.",
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            SaveServerButton(phase = phase, onClick = onSave)
            Row(
                modifier = Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            ) {
                Icon(
                    imageVector = ShadowIcons.Lock,
                    contentDescription = null,
                    tint = colors.ink3,
                    modifier = Modifier
                        .padding(top = 1.dp)
                        .size(14.dp),
                )
                Text(
                    text = "Saved on this device. Passwords and keys are encrypted.",
                    style = Shadow.type.caption,
                    color = colors.ink3,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun FixFieldsChip(invalid: List<ServerField>, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Shadow.colors
    val count = invalid.size
    val label = if (count == 1) "Fix 1 field" else "Fix $count fields"
    val target = invalid.firstOrNull()?.label ?: "the form"
    Box(
        modifier = modifier
            .height(44.dp)
            .shadowClickable(remember { MutableInteractionSource() }, onClick = onClick)
            .semantics { contentDescription = "$label. Go to $target" }
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .height(32.dp)
                .clip(ShadowShapes.Pill)
                .background(colors.coralTint)
                .border(1.dp, colors.coral.copy(alpha = FIX_BORDER_ALPHA), ShadowShapes.Pill)
                .padding(start = 10.dp, end = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(ShadowIcons.Warning, contentDescription = null, tint = colors.coralText, modifier = ChipIconSize)
            SwapText(text = label, style = Shadow.type.label, color = colors.coralText)
            Icon(ShadowIcons.Upload, contentDescription = null, tint = colors.coralText, modifier = ChipIconSize)
        }
    }
}

/** Mint "<name> is now first in Servers ›" link shown with the saved state; opens the list. */
@Composable
private fun SavedLink(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Shadow.colors
    Box(
        modifier = modifier
            .height(44.dp)
            .shadowClickable(remember { MutableInteractionSource() }, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .height(32.dp)
                .clip(ShadowShapes.Pill)
                .background(colors.mintTint)
                .padding(start = 12.dp, end = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = Shadow.type.label,
                color = colors.mintText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(ShadowIcons.ChevronRight, contentDescription = null, tint = colors.mintText, modifier = ChipIconSize)
        }
    }
}

/** The footer's 52 dp save button: amber "Save server" → spinner "Saving…" → mint "Saved". */
@Composable
private fun SaveServerButton(phase: SavePhase, onClick: () -> Unit) {
    val colors = Shadow.colors
    val saved = phase == SavePhase.Saved
    val container by animateColorAsState(
        targetValue = if (saved) colors.mint else colors.amber,
        animationSpec = shadowTween(ShadowMotion.ColorFade, ShadowMotion.Ease),
        label = "save-container",
    )
    val ink by animateColorAsState(
        targetValue = if (saved) colors.onMint else colors.onAmber,
        animationSpec = shadowTween(ShadowMotion.ColorFade, ShadowMotion.Ease),
        label = "save-ink",
    )
    val label = when (phase) {
        SavePhase.Idle -> "Save server"
        SavePhase.Saving -> "Saving…"
        SavePhase.Saved -> "Saved"
        SavePhase.Failed -> "Try again"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shadowClickable(
                interactionSource = remember { MutableInteractionSource() },
                enabled = phase != SavePhase.Saving && !saved,
                onClick = onClick,
            )
            .height(52.dp)
            .clip(ShadowShapes.Button)
            .background(container),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (phase) {
            SavePhase.Saving -> ShadowSpinner(color = ink, size = 18.dp)
            SavePhase.Saved -> {
                Icon(ShadowIcons.Check, contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
            }
            else -> Unit
        }
        SwapText(text = label, style = Shadow.type.button, color = ink)
    }
}

/** Lays the node out with margins that may be negative (the "Fix N fields" chip overlaps by 6 dp). */
private fun Modifier.verticalMargins(top: Dp, bottom: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val topPx = top.roundToPx()
    val height = (placeable.height + topPx + bottom.roundToPx()).coerceAtLeast(0)
    layout(placeable.width, height) { placeable.placeRelative(0, topPx) }
}

private const val FIX_BORDER_ALPHA = 0.28f
private val ChipIconSize = Modifier.size(16.dp)
