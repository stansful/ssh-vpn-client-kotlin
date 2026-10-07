package com.stansful.sshvpnclient.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.stansful.sshvpnclient.domain.model.AppThemeMode
import com.stansful.sshvpnclient.domain.model.CustomThemeColors
import com.stansful.sshvpnclient.ui.designsystem.IconTile
import com.stansful.sshvpnclient.ui.designsystem.SecondaryButton
import com.stansful.sshvpnclient.ui.designsystem.ShadowButton
import com.stansful.sshvpnclient.ui.designsystem.ShadowButtonSize
import com.stansful.sshvpnclient.ui.designsystem.ShadowButtonVariant
import com.stansful.sshvpnclient.ui.designsystem.ShadowCard
import com.stansful.sshvpnclient.ui.designsystem.ShadowDialogContainer
import com.stansful.sshvpnclient.ui.designsystem.ShadowIcons
import com.stansful.sshvpnclient.ui.designsystem.StatusPill
import com.stansful.sshvpnclient.ui.designsystem.StatusTone
import com.stansful.sshvpnclient.ui.designsystem.SubScreenBar
import com.stansful.sshvpnclient.ui.designsystem.SwapText
import com.stansful.sshvpnclient.ui.designsystem.fadeUpIn
import com.stansful.sshvpnclient.ui.theme.Shadow
import com.stansful.sshvpnclient.ui.theme.ShadowDimens
import com.stansful.sshvpnclient.ui.theme.ShadowMotion
import com.stansful.sshvpnclient.ui.theme.shadowTween

/** What the Appearance screen shows: the theme, the saved custom palette and the unsaved draft. */
@Immutable
internal data class AppearanceUiState(
    val themeMode: AppThemeMode,
    val saved: CustomThemeColors,
    val draft: CustomThemeColors,
    val openRole: PaletteRole? = null,
    val showDiscardDialog: Boolean = false,
) {
    val changed: List<PaletteRole> get() = draft.changedRoles(saved)
    val dirty: Boolean get() = draft != saved
}

/** Callbacks of [AppearanceScreen]; defaults do nothing (screenshots). */
@Immutable
internal class AppearanceActions(
    val onBack: () -> Unit = {},
    val onThemeModeChange: (AppThemeMode) -> Unit = {},
    val onPreset: (CustomThemeColors) -> Unit = {},
    val onToggleRole: (PaletteRole) -> Unit = {},
    val onRoleColorChange: (PaletteRole, Int) -> Unit = { _, _ -> },
    val onRevert: () -> Unit = {},
    val onApply: () -> Unit = {},
    val onDiscard: () -> Unit = {},
    val onKeepEditing: () -> Unit = {},
)

/**
 * Appearance (sub-screen): four theme tiles with mini previews; under Custom, the palette editor with a
 * live preview of the draft, presets, the 8 roles and Revert / Apply. Only Apply saves the palette.
 */
@Composable
internal fun AppearanceScreen(
    state: AppearanceUiState,
    actions: AppearanceActions,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
) {
    val colors = Shadow.colors
    val custom = state.themeMode == AppThemeMode.CUSTOM
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg)
            .imePadding(),
    ) {
        SubScreenBar(
            title = "Appearance",
            onBack = actions.onBack,
            showDivider = scrollState.canScrollBackward,
            contentPadding = PaddingValues(start = 8.dp, end = 20.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = MAX_CONTENT_WIDTH)
                    .padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 28.dp),
            ) {
                ThemeSection(state, actions)
                if (custom) PaletteSection(state, actions)
            }
            if (!custom) Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
        if (custom) ApplyBar(dirty = state.dirty, onRevert = actions.onRevert, onApply = actions.onApply)
    }
    if (state.showDiscardDialog) {
        DiscardPaletteDialog(
            changed = state.changed.size,
            onDiscard = actions.onDiscard,
            onKeep = actions.onKeepEditing,
        )
    }
}

@Composable
private fun ThemeSection(state: AppearanceUiState, actions: AppearanceActions) {
    val colors = Shadow.colors
    val dirty = state.dirty
    Column {
        HeaderRow(
            title = "Theme",
            trailing = "Applies instantly, system bars too",
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 10.dp),
        )
        ThemeTiles.chunked(2).forEachIndexed { rowIndex, row ->
            Row(
                modifier = Modifier.padding(top = if (rowIndex == 0) 0.dp else 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                row.forEachIndexed { columnIndex, tile ->
                    ThemeTile(
                        name = tile.name,
                        selected = state.themeMode == tile.mode,
                        base = tile.base ?: state.saved,
                        overlay = tile.overlay,
                        edited = tile.mode == AppThemeMode.CUSTOM && dirty,
                        onClick = { if (state.themeMode != tile.mode) actions.onThemeModeChange(tile.mode) },
                        modifier = Modifier
                            .weight(1f)
                            .fadeUpIn(rowIndex * 2 + columnIndex),
                    )
                }
            }
        }
        Row(
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = ShadowIcons.Info,
                contentDescription = null,
                tint = colors.ink3,
                modifier = Modifier
                    .padding(top = 1.dp)
                    .size(16.dp),
            )
            SwapText(text = themeNote(state.themeMode), style = Shadow.type.bodyS, color = colors.ink3, maxLines = 3)
        }
        AnimatedVisibility(
            visible = state.themeMode != AppThemeMode.CUSTOM,
            enter = fadeIn(shadowTween(ShadowMotion.FadeUp)),
            exit = fadeOut(shadowTween(0)),
        ) {
            KeptPaletteCard(
                saved = state.saved,
                dirty = dirty,
                onClick = { actions.onThemeModeChange(AppThemeMode.CUSTOM) },
            )
        }
    }
}

@Composable
private fun KeptPaletteCard(saved: CustomThemeColors, dirty: Boolean, onClick: () -> Unit) {
    val colors = Shadow.colors
    ShadowCard(
        onClick = onClick,
        contentPadding = PaddingValues(KEPT_CARD_PADDING),
        modifier = Modifier
            .padding(top = 20.dp)
            .fillMaxWidth(),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon = ShadowIcons.Palette, tint = if (dirty) colors.amberText else colors.ink2)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "Custom palette kept",
                    style = Shadow.type.rowTitle.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.ink1,
                )
                Text(
                    text = if (dirty) "Unsaved edits are kept too." else "Pick Custom to use it again.",
                    style = Shadow.type.bodyS,
                    color = if (dirty) colors.amberText else colors.ink3,
                )
            }
            SwatchStack(
                colors = listOf(saved.background, saved.surface, saved.primary, saved.secondary).map { it.toColor() },
                size = 16.dp,
            )
            Icon(ShadowIcons.ChevronRight, null, tint = colors.ink3, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun PaletteSection(state: AppearanceUiState, actions: AppearanceActions) {
    val colors = Shadow.colors
    val changed = state.changed.size
    Column(Modifier.fadeUpIn(4)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, top = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "CUSTOM PALETTE",
                style = Shadow.type.overline,
                color = colors.ink3,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
            )
            StatusPill(
                label = if (changed > 0) "Unsaved · ${colorsCount(changed)}" else "Saved",
                tone = if (changed > 0) StatusTone.Progress else StatusTone.Neutral,
                blink = false,
            )
        }
        Column(
            modifier = Modifier
                .padding(top = 10.dp)
                .fillMaxWidth()
                .settingsCard()
                .padding(6.dp),
        ) {
            PaletteLivePreview(state.draft)
            ContrastRow(state.draft)
        }
        Row(
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 20.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Start from a preset",
                style = Shadow.type.label,
                color = colors.ink2,
                modifier = Modifier.weight(1f),
            )
            Text("Fills the draft only", style = Shadow.type.caption, color = colors.ink3)
        }
        PresetRow(draft = state.draft, onPick = actions.onPreset)
        HeaderRow(
            title = "Colors",
            trailing = "Tap a color to fine-tune",
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 24.dp, bottom = 10.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .settingsCard(),
        ) {
            PaletteRole.entries.forEachIndexed { index, role ->
                PaletteRoleRow(
                    role = role,
                    color = state.draft[role],
                    savedColor = state.saved[role],
                    open = state.openRole == role,
                    first = index == 0,
                    onToggle = { actions.onToggleRole(role) },
                    onColorChange = { actions.onRoleColorChange(role, it) },
                )
            }
        }
    }
}

/** Overline header with a 12/16 note on the right ("Applies instantly, system bars too"). */
@Composable
private fun HeaderRow(title: String, trailing: String, modifier: Modifier = Modifier) {
    val colors = Shadow.colors
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = title.uppercase(),
            style = Shadow.type.overline,
            color = colors.ink3,
            modifier = Modifier
                .weight(1f)
                .alignByBaseline()
                .semantics { heading() },
        )
        Text(
            text = trailing,
            style = Shadow.type.caption,
            color = colors.ink3,
            modifier = Modifier.alignByBaseline(),
        )
    }
}

/** Revert / Apply, pinned above the navigation bar while the Custom theme is on. */
@Composable
private fun ApplyBar(dirty: Boolean, onRevert: () -> Unit, onApply: () -> Unit) {
    val colors = Shadow.colors
    val bg = colors.bg
    val edge = colors.surface2
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val fade = 24.dp.toPx()
                drawRect(
                    brush = Brush.verticalGradient(listOf(bg.copy(alpha = 0f), bg), startY = -fade - 1f, endY = -1f),
                    topLeft = Offset(0f, -fade - 1f),
                    size = Size(size.width, fade),
                )
                drawRect(bg)
                drawRect(edge, size = Size(size.width, 1.dp.toPx()))
            }
            .navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.widthIn(max = MAX_CONTENT_WIDTH),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SecondaryButton(text = "Revert", onClick = onRevert, enabled = dirty, modifier = Modifier.weight(1f))
            ShadowButton(
                text = "Apply",
                onClick = onApply,
                enabled = dirty,
                icon = BoldCheck,
                modifier = Modifier.weight(APPLY_WEIGHT),
            )
        }
        Text(
            text = "Changes apply to the whole app, including system bars.",
            style = Shadow.type.caption,
            color = colors.ink3,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun DiscardPaletteDialog(changed: Int, onDiscard: () -> Unit, onKeep: () -> Unit) {
    val colors = Shadow.colors
    ShadowDialogContainer(onDismissRequest = onKeep) {
        IconTile(
            icon = ShadowIcons.Palette,
            tint = colors.amberText,
            container = colors.amberTint,
        )
        Text(
            text = "Discard palette changes?",
            style = Shadow.type.titleM,
            color = colors.ink1,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = "You changed ${colorsCount(changed)} in the draft. Leaving drops them; your saved palette " +
                "stays as it is.",
            style = Shadow.type.body,
            color = colors.ink2,
            modifier = Modifier.padding(top = 10.dp),
        )
        Column(
            modifier = Modifier.padding(top = 22.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ShadowButton(
                text = "Discard",
                onClick = onDiscard,
                variant = ShadowButtonVariant.DangerTonal,
                size = ShadowButtonSize.Tall,
                modifier = Modifier.fillMaxWidth(),
            )
            SecondaryButton(
                text = "Keep editing",
                onClick = onKeep,
                size = ShadowButtonSize.Tall,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private data class ThemeTileSpec(
    val mode: AppThemeMode,
    val name: String,
    /** Palette of the mini phone; null = the saved custom palette. */
    val base: CustomThemeColors?,
    val overlay: CustomThemeColors? = null,
)

private val ThemeTiles = listOf(
    ThemeTileSpec(AppThemeMode.SYSTEM, "System", CustomThemeColors.night(), CustomThemeColors.day()),
    ThemeTileSpec(AppThemeMode.LIGHT, "Light", CustomThemeColors.day()),
    ThemeTileSpec(AppThemeMode.DARK, "Dark", CustomThemeColors.night()),
    ThemeTileSpec(AppThemeMode.CUSTOM, "Custom", null),
)

private fun themeNote(mode: AppThemeMode): String = when (mode) {
    AppThemeMode.SYSTEM -> "Follows your phone’s light or dark mode."
    AppThemeMode.LIGHT -> "Day palette · warm paper, amber accent."
    AppThemeMode.DARK -> "Night palette · deep graphite, amber accent."
    AppThemeMode.CUSTOM -> "Your 8 colors · Background sets light or dark."
}

private fun colorsCount(count: Int): String = if (count == 1) "1 color" else "$count colors"

private val MAX_CONTENT_WIDTH = ShadowDimens.PaneMaxWidth

/** 14 dp padding inside the card's 1 dp border. */
private val KEPT_CARD_PADDING = 15.dp
private const val APPLY_WEIGHT = 1.5f
