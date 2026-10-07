package com.stansful.sshvpnclient.ui.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.stansful.sshvpnclient.ui.theme.Shadow
import com.stansful.sshvpnclient.ui.theme.ShadowMotion
import com.stansful.sshvpnclient.ui.theme.ShadowShapes
import com.stansful.sshvpnclient.ui.theme.shadowTween
import kotlinx.coroutines.launch

/**
 * Modal bottom sheet of the design (wraps Material3 ModalBottomSheet): sheet color, top radius 28,
 * 36×4 line-2 handle, title 24/600 + 13/18 subtitle (mono 12/16 with [subtitleMono]), an optional
 * [header] slot under them (tags, a status line), optional 40 dp close button, scrim token.
 * [content] has no side padding (rows use 12, text 20). [actions] stick to the bottom (20 dp sides);
 * give long [content] `Modifier.weight(1f, fill = false)` + its own scroll so actions stay visible.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShadowBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    subtitleMono: Boolean = false,
    showClose: Boolean = true,
    closeContentDescription: String = "Close",
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    header: (@Composable ColumnScope.() -> Unit)? = null,
    actions: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = Shadow.colors
    val scope = rememberCoroutineScope()
    val close: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) onDismissRequest()
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        shape = ShadowShapes.Sheet,
        containerColor = colors.sheet,
        contentColor = colors.ink1,
        tonalElevation = 0.dp,
        scrimColor = colors.scrim,
        dragHandle = null,
        // The content pads the insets itself (below), so the hairline can be drawn on the whole surface.
        contentWindowInsets = { WindowInsets(0) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Inside the sheet surface: a modifier on ModalBottomSheet is applied before the sheet
                // is offset, so anything drawn there would float at the top of the window.
                .sheetTopHairline(colors.sheetLine)
                .windowInsetsPadding(BottomSheetDefaults.windowInsets)
                .padding(bottom = 22.dp),
        ) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { SheetHandle() }
            if (title != null || showClose || header != null) {
                SheetHeader(
                    title = title,
                    subtitle = subtitle,
                    subtitleMono = subtitleMono,
                    showClose = showClose,
                    closeContentDescription = closeContentDescription,
                    onClose = close,
                    extra = header,
                )
            }
            content()
            if (actions != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    content = actions,
                )
            }
        }
    }
}

/**
 * The sheets' `border-top: 1px` with 28 px top corners: a 1 dp hairline along the top edge that thins
 * out along the corner arcs to nothing at the sides (the rounded outline minus itself moved down 1 dp).
 * No side or bottom lines, so nothing shows beside or above a navigation bar.
 */
private fun Modifier.sheetTopHairline(color: Color): Modifier = drawWithCache {
    val outline = Path().apply { addOutline(ShadowShapes.Sheet.createOutline(size, layoutDirection, this@drawWithCache)) }
    val shifted = Path().apply {
        addPath(outline)
        translate(Offset(0f, 1.dp.toPx()))
    }
    val hairline = Path().apply { op(outline, shifted, PathOperation.Difference) }
    onDrawWithContent {
        drawContent()
        drawPath(hairline, color)
    }
}

@Composable
private fun SheetHandle() {
    Box(
        modifier = Modifier
            .padding(top = 10.dp, bottom = 14.dp)
            .size(width = 36.dp, height = 4.dp)
            .clip(CircleShape)
            .background(Shadow.colors.line2),
    )
}

@Composable
private fun SheetHeader(
    title: String?,
    subtitle: String?,
    subtitleMono: Boolean,
    showClose: Boolean,
    closeContentDescription: String,
    onClose: () -> Unit,
    extra: (@Composable ColumnScope.() -> Unit)?,
) {
    val colors = Shadow.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 16.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            if (title != null) {
                Text(
                    text = title,
                    style = Shadow.type.titleL,
                    color = colors.ink1,
                    modifier = Modifier.semantics { paneTitle = title },
                )
            }
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = if (subtitleMono) Shadow.type.monoS else Shadow.type.bodyS,
                    color = colors.ink3,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (extra != null) {
                Column(modifier = Modifier.padding(top = 10.dp), content = extra)
            }
        }
        if (showClose) {
            ShadowIconButton(
                icon = ShadowIcons.Close,
                contentDescription = closeContentDescription,
                onClick = onClose,
                style = ShadowIconButtonStyle.Filled,
                size = 40.dp,
                iconSize = 18.dp,
            )
        }
    }
}

/**
 * Dialog: sheet color, radius 24, padding 24, optional 48 dp [icon] tile, title 20/600, [message]
 * 15/22 ink-2, extra [content] (e.g. an `InlineBanner`), then actions.
 * - Inline (default): right-aligned ghost dismiss + confirm, 44 dp ("Stay" / "Stop & switch"; Routes'
 *   "Cancel" / "Delete" with `confirmVariant = ShadowButtonVariant.DangerTonal`).
 * - [stacked]: full-width confirm above a secondary dismiss ([stackedSize], 52 dp; 48 = `Tall`).
 * - [destructive] = stacked + coral `Danger` confirm + coral icon tile ("Delete server").
 * [confirmVariant] defaults to Danger when destructive, else Primary; the icon tile follows it (coral
 * for Danger/DangerTonal, amber otherwise). Scales in from 0.94 with the Spring curve over the scrim.
 */
@Composable
fun ShadowDialog(
    onDismissRequest: () -> Unit,
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
    dismissLabel: String? = "Cancel",
    onDismiss: () -> Unit = onDismissRequest,
    destructive: Boolean = false,
    stacked: Boolean = destructive,
    confirmVariant: ShadowButtonVariant = if (destructive) ShadowButtonVariant.Danger else ShadowButtonVariant.Primary,
    confirmIcon: ImageVector? = null,
    stackedSize: ShadowButtonSize = ShadowButtonSize.Large,
    icon: ImageVector? = null,
    confirmEnabled: Boolean = true,
    confirmLoading: Boolean = false,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val danger = confirmVariant == ShadowButtonVariant.Danger || confirmVariant == ShadowButtonVariant.DangerTonal
    ShadowDialogContainer(onDismissRequest = onDismissRequest, modifier = modifier) {
        DialogBody(title = title, message = message, icon = icon, danger = danger)
        content?.invoke(this)
        if (stacked) {
            Column(
                modifier = Modifier.padding(top = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ShadowButton(
                    text = confirmLabel,
                    onClick = onConfirm,
                    modifier = Modifier.fillMaxWidth(),
                    variant = confirmVariant,
                    size = stackedSize,
                    icon = confirmIcon,
                    enabled = confirmEnabled,
                    loading = confirmLoading,
                )
                if (dismissLabel != null) {
                    SecondaryButton(
                        text = dismissLabel,
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                        size = stackedSize,
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 22.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                if (dismissLabel != null) {
                    ShadowButton(
                        text = dismissLabel,
                        onClick = onDismiss,
                        variant = ShadowButtonVariant.Ghost,
                        size = ShadowButtonSize.Medium,
                    )
                }
                ShadowButton(
                    text = confirmLabel,
                    onClick = onConfirm,
                    variant = confirmVariant,
                    size = ShadowButtonSize.Medium,
                    icon = confirmIcon,
                    enabled = confirmEnabled,
                    loading = confirmLoading,
                )
            }
        }
    }
}

@Composable
private fun DialogBody(title: String, message: String?, icon: ImageVector?, danger: Boolean) {
    val colors = Shadow.colors
    if (icon != null) {
        IconTile(
            icon = icon,
            size = 48.dp,
            iconSize = 22.dp,
            tint = if (danger) colors.coralText else colors.amberText,
            container = if (danger) colors.coralTint else colors.amberTint,
        )
        Spacer(Modifier.height(16.dp))
    }
    Text(
        text = title,
        style = Shadow.type.titleM,
        color = colors.ink1,
        modifier = Modifier.semantics { paneTitle = title },
    )
    if (message != null) {
        Text(
            text = message,
            style = Shadow.type.body,
            color = colors.ink2,
            modifier = Modifier.padding(top = if (icon != null) 8.dp else 10.dp),
        )
    }
}

/**
 * Bare dialog surface (sheet color, 1 dp border, radius 24, padding 24, max 560 dp wide) over the
 * scrim token, with the 0.94 → 1 spring scale-in. Use for custom dialog layouts.
 */
@Composable
fun ShadowDialogContainer(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    dismissOnScrimTap: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = Shadow.colors
    val reduced = Shadow.reducedMotion
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0f) }
        val appear = remember { Animatable(if (reduced) 1f else 0f) }
        LaunchedEffect(Unit) {
            if (!reduced) appear.animateTo(1f, tween(ShadowMotion.Dialog, easing = ShadowMotion.Spring))
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = appear.value.coerceIn(0f, 1f) }
                .background(colors.scrim)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = dismissOnScrimTap,
                    onClick = onDismissRequest,
                )
                .safeDrawingPadding()
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .graphicsLayer {
                        val scale = DIALOG_START_SCALE + (1f - DIALOG_START_SCALE) * appear.value
                        scaleX = scale
                        scaleY = scale
                    }
                    .clip(ShadowShapes.Dialog)
                    .background(colors.sheet)
                    .border(1.dp, colors.sheetLine, ShadowShapes.Dialog)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .padding(24.dp),
                content = content,
            )
        }
    }
}

/** Look of a [ShadowMenu] and its items. */
enum class ShadowMenuStyle {
    /** Top-bar overflow (Routes, Terminal): sheet color, sheet-line border, radius 18, 48 dp rows, 20 dp icons. */
    Sheet,

    /** Card overflow (Servers, Keys): surface-2, line-2 border, radius 16, 44 dp rows, 18 dp icons. */
    Compact,
}

private val LocalMenuStyle = staticCompositionLocalOf { ShadowMenuStyle.Sheet }

/**
 * Dropdown menu: [style] picks the top-bar ([ShadowMenuStyle.Sheet], min 264 dp wide) or card
 * ([ShadowMenuStyle.Compact], min 184 dp) look; 6 dp inner padding. Fill it with [ShadowMenuItem]s
 * (and [ShadowMenuDivider]); anchor it in a `Box` with its trigger
 * (`ShadowIconButton(ShadowIcons.More, "More actions", …)`).
 */
@Composable
fun ShadowMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    style: ShadowMenuStyle = ShadowMenuStyle.Sheet,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = Shadow.colors
    val sheet = style == ShadowMenuStyle.Sheet
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier
            .widthIn(min = if (sheet) SHEET_MENU_MIN_WIDTH else COMPACT_MENU_MIN_WIDTH)
            .padding(horizontal = 6.dp),
        offset = offset,
        shape = if (sheet) RoundedCornerShape(18.dp) else ShadowShapes.Menu,
        containerColor = if (sheet) colors.sheet else colors.surface2,
        tonalElevation = 0.dp,
        shadowElevation = 12.dp,
        border = BorderStroke(1.dp, if (sheet) colors.sheetLine else colors.line2),
    ) {
        CompositionLocalProvider(LocalMenuStyle provides style) {
            content()
        }
    }
}

/**
 * Menu row: [ShadowMenuStyle.Sheet] min 48 dp (56 with [subtitle]), radius 12, 20 dp ink-2 icon;
 * [ShadowMenuStyle.Compact] 44 dp, radius 10, 18 dp icon. 15/500 label, 12/16 ink-3 subtitle, optional
 * trailing icon (e.g. `ShadowIcons.ChevronRight`). [destructive] paints label and icon coral.
 */
@Composable
fun ShadowMenuItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null,
    destructive: Boolean = false,
    enabled: Boolean = true,
    trailingIcon: ImageVector? = null,
) {
    val colors = Shadow.colors
    val compact = LocalMenuStyle.current == ShadowMenuStyle.Compact
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val background by animateColorAsState(
        targetValue = if (pressed) colors.surface3 else colors.surface3.copy(alpha = 0f),
        animationSpec = shadowTween(MENU_PRESS_MS, ShadowMotion.Ease),
        label = "menu-item-press",
    )
    val ink = if (destructive) colors.coralText else colors.ink1
    val iconTint = if (destructive) colors.coralText else colors.ink2
    val minHeight = when {
        compact -> 44.dp
        subtitle != null -> 56.dp
        else -> 48.dp
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clip(RoundedCornerShape(if (compact) 10.dp else 12.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = ShadowFocusIndication,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .background(background)
            .heightIn(min = minHeight)
            .padding(horizontal = 12.dp, vertical = if (compact) 0.dp else 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(if (compact) 18.dp else 20.dp),
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = text, style = Shadow.type.rowTitle, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(text = subtitle, style = Shadow.type.caption, color = colors.ink3)
            }
        }
        if (trailingIcon != null) {
            Icon(trailingIcon, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(16.dp))
        }
    }
}

/** 1 dp line-2 rule between menu groups (6 dp above/below, 12 dp inset), e.g. before "Remove unavailable". */
@Composable
fun ShadowMenuDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .height(1.dp)
            .background(Shadow.colors.line2),
    )
}

private const val DIALOG_START_SCALE = 0.94f
private const val MENU_PRESS_MS = 160
private val SHEET_MENU_MIN_WIDTH = 264.dp
private val COMPACT_MENU_MIN_WIDTH = 184.dp
