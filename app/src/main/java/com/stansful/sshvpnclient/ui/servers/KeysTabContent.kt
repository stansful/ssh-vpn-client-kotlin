package com.stansful.sshvpnclient.ui.servers

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.stansful.sshvpnclient.ui.configs.ConfigListItem
import com.stansful.sshvpnclient.ui.designsystem.BannerTone
import com.stansful.sshvpnclient.ui.designsystem.IconTile
import com.stansful.sshvpnclient.ui.designsystem.InlineBanner
import com.stansful.sshvpnclient.ui.designsystem.ShadowDialog
import com.stansful.sshvpnclient.ui.designsystem.ShadowIconButton
import com.stansful.sshvpnclient.ui.designsystem.ShadowIcons
import com.stansful.sshvpnclient.ui.designsystem.ShadowMenu
import com.stansful.sshvpnclient.ui.designsystem.ShadowMenuItem
import com.stansful.sshvpnclient.ui.designsystem.ShadowMenuStyle
import com.stansful.sshvpnclient.ui.designsystem.fadeUpIn
import com.stansful.sshvpnclient.ui.designsystem.shadowClickable
import com.stansful.sshvpnclient.ui.keys.KeyDeleteError
import com.stansful.sshvpnclient.ui.keys.KeyListItem
import com.stansful.sshvpnclient.ui.keys.KeyListUiState
import com.stansful.sshvpnclient.ui.theme.Shadow
import com.stansful.sshvpnclient.ui.theme.ShadowMotion
import com.stansful.sshvpnclient.ui.theme.ShadowShapes
import com.stansful.sshvpnclient.ui.theme.shadowTween
import java.time.LocalDate

@Composable
internal fun KeysTabContent(
    state: KeyListUiState,
    freshKeyId: String?,
    today: LocalDate,
    actions: ServersActions,
    listState: LazyListState,
) {
    val items = state.items
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
    ) {
        if (!state.isLoaded) return@LazyColumn
        if (items.isEmpty()) {
            item(key = "empty") {
                ListEmptyState(
                    icon = ShadowIcons.Key,
                    title = "No keys yet",
                    message = "Add a private key to sign in to your servers without a password.",
                    actionLabel = "Add key",
                    onAction = actions.onAddKey,
                    modifier = Modifier.fadeUpIn(2),
                )
            }
        } else {
            item(key = "header") {
                ListHeader(title = "Private keys", note = "Recently updated first", modifier = Modifier.fadeUpIn(2))
            }
            itemsIndexed(items, key = { _, item -> item.key.id }) { index, item ->
                KeyCard(
                    item = item,
                    today = today,
                    fresh = item.key.id == freshKeyId,
                    onEdit = { actions.onEditKey(item.key.id) },
                    onDelete = { actions.onAskDeleteKey(item.key.id) },
                    modifier = Modifier
                        .animateItem(
                            fadeInSpec = null,
                            placementSpec = shadowTween(ShadowMotion.Surface),
                            fadeOutSpec = shadowTween(LEAVE_FADE_MS, ShadowMotion.Exit),
                        )
                        .fadeUpIn(3 + index),
                )
            }
        }
        item(key = "footnote") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 4.dp, top = 12.dp)
                    .fadeUpIn(3 + items.size),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = ShadowIcons.Lock,
                    contentDescription = null,
                    tint = Shadow.colors.ink3,
                    modifier = Modifier
                        .padding(top = 1.dp)
                        .size(14.dp),
                )
                Text(
                    text = "Keys and passphrases are encrypted on this device and never included in backups.",
                    style = Shadow.type.caption,
                    color = Shadow.colors.ink3,
                )
            }
        }
    }
}

@Composable
private fun KeyCard(
    item: KeyListItem,
    today: LocalDate,
    fresh: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Shadow.colors
    val reduced = Shadow.reducedMotion
    val flash = remember { Animatable(0f) }
    LaunchedEffect(fresh) {
        if (fresh && !reduced) {
            flash.snapTo(1f)
            flash.animateTo(0f, tween(FLASH_MS, easing = ShadowMotion.Standard))
        }
    }
    val border = lerp(colors.line, colors.amber.copy(alpha = FLASH_BORDER_ALPHA), flash.value)
    val container = lerp(
        colors.surface1,
        colors.amber.copy(alpha = FLASH_FILL_ALPHA).compositeOver(colors.surface1),
        flash.value,
    )
    Box(modifier = modifier.padding(bottom = 12.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .dayCardShadow(colors)
                .clip(ShadowShapes.Card)
                .background(container)
                .border(1.dp, border, ShadowShapes.Card),
        ) {
            KeyCardBody(item = item, today = today, onEdit = onEdit)
            KeyCardMenu(
                name = item.key.name,
                onEdit = onEdit,
                onDelete = onDelete,
                // top 10 / right 8 inside the 1 dp border, as drawn.
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 11.dp, end = 9.dp),
            )
        }
    }
}

/** Key tile, name, format/passphrase tags, "Used by N servers · updated d MMM" and the note; tap = edit. */
@Composable
private fun KeyCardBody(item: KeyListItem, today: LocalDate, onEdit: () -> Unit) {
    val colors = Shadow.colors
    val key = item.key
    val format = item.traits?.format
    val hasPassphrase = item.traits?.hasPassphrase == true
    val usage = keyUsageLabel(item.usageCount)
    val updated = formatShortDate(key.updatedAt, today)
    val note = key.note?.takeIf { it.isNotBlank() }
    val description = buildString {
        append(key.name)
        format?.let { append(", ${it.label}") }
        if (hasPassphrase) append(", protected with a passphrase")
        append(". $usage, updated $updated.")
        note?.let { append(" $it.") }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shadowClickable(
                interactionSource = remember { MutableInteractionSource() },
                onClickLabel = "Edit key",
                onClick = onEdit,
            )
            .semantics { contentDescription = description }
            // 16 / 60 dp padding inside the 1 dp border, as drawn.
            .padding(start = 17.dp, top = 17.dp, bottom = 17.dp, end = 61.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IconTile(icon = ShadowIcons.Key)
        Column(
            modifier = Modifier
                .weight(1f)
                .clearAndSetSemantics {},
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(text = key.name, style = Shadow.type.titleS, color = colors.ink1)
            if (format != null || hasPassphrase) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (format != null) KeyTag(text = format.label, mono = true)
                    if (hasPassphrase) KeyTag(text = "Passphrase", mono = false, withLock = true)
                }
            }
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = colors.ink2)) { append(usage) }
                    append(" · updated $updated")
                },
                style = Shadow.type.bodyS,
                color = colors.ink3,
            )
            if (note != null) {
                Text(text = note, style = Shadow.type.bodyS, color = colors.ink3)
            }
        }
    }
}

/** The card's ⋮ button and its Edit / Delete menu. */
@Composable
private fun KeyCardMenu(
    name: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box(modifier) {
        ShadowIconButton(
            icon = ShadowIcons.More,
            contentDescription = "Actions for $name",
            onClick = { menuOpen = true },
            selected = menuOpen,
            tint = Shadow.colors.ink2,
            iconSize = 20.dp,
        )
        ShadowMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            style = ShadowMenuStyle.Compact,
            modifier = Modifier.width(KEY_MENU_WIDTH),
        ) {
            ShadowMenuItem(
                text = "Edit",
                onClick = {
                    menuOpen = false
                    onEdit()
                },
                icon = ShadowIcons.Edit,
            )
            ShadowMenuItem(
                text = "Delete",
                onClick = {
                    menuOpen = false
                    onDelete()
                },
                icon = ShadowIcons.Trash,
                destructive = true,
            )
        }
    }
}

/** 24 dp surface-2 pill: the key format (mono) or "Passphrase" with a lock. */
@Composable
private fun KeyTag(text: String, mono: Boolean, withLock: Boolean = false) {
    val colors = Shadow.colors
    Row(
        modifier = Modifier
            .height(24.dp)
            .clip(ShadowShapes.Pill)
            .background(colors.surface2)
            .padding(start = if (withLock) 7.dp else 9.dp, end = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (withLock) {
            Icon(ShadowIcons.Lock, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(14.dp))
        }
        Text(
            text = text,
            style = if (mono) TagMonoStyle else Shadow.type.caption.copy(fontWeight = FontWeight.Medium),
            color = colors.ink2,
        )
    }
}

/**
 * Delete flow of the key picked in its menu: a key still used by servers gets "Key is in use" naming
 * them (with a shortcut to the first one), a free key gets the destructive confirmation.
 */
@Composable
internal fun KeyDeleteDialogHost(
    keys: KeyListUiState,
    servers: List<ConfigListItem>,
    actions: ServersActions,
) {
    val item = keys.items.firstOrNull { it.key.id == keys.pendingDeleteId } ?: return
    val usedBy = servers.filter { it.config.privateKeyId == item.key.id }
    if (usedBy.isNotEmpty()) {
        val first = usedBy.first().config
        val body = if (usedBy.size > 1) {
            "${first.name} and ${usedBy.size - 1} more sign in with this key. " +
                "Switch them to another key or password first."
        } else {
            "${first.name} signs in with this key. Switch it to another key or password first."
        }
        ShadowDialog(
            onDismissRequest = actions.onCancelDeleteKey,
            title = "Key is in use",
            message = body,
            confirmLabel = "Open ${first.name}",
            onConfirm = { actions.onOpenServer(first.id) },
            dismissLabel = "OK",
            stacked = true,
            confirmIcon = ShadowIcons.Server,
            icon = ShadowIcons.Link,
        )
        return
    }
    val error = keys.deleteError
    ShadowDialog(
        onDismissRequest = actions.onCancelDeleteKey,
        title = "Delete ${item.key.name}?",
        message = "No server uses it. The key is erased from this device and cannot be restored.",
        confirmLabel = if (error == KeyDeleteError.Storage) "Try again" else "Delete",
        onConfirm = actions.onConfirmDeleteKey,
        destructive = true,
        icon = ShadowIcons.Trash,
        confirmLoading = keys.isDeleting,
    ) {
        val message = when (error) {
            KeyDeleteError.Storage -> "Couldn’t delete it: storage didn’t respond. Try again."
            is KeyDeleteError.InUse -> error.message
            null -> null
        }
        if (message != null) {
            InlineBanner(
                message = message,
                tone = BannerTone.Error,
                tintedMessage = true,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

private val KEY_MENU_WIDTH = 184.dp
private const val LEAVE_FADE_MS = 220
private const val FLASH_MS = 1_600
private const val FLASH_BORDER_ALPHA = 0.75f
private const val FLASH_FILL_ALPHA = 0.10f
