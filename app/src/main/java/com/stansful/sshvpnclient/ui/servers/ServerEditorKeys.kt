package com.stansful.sshvpnclient.ui.servers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.stansful.sshvpnclient.domain.model.SshPrivateKeySummary
import com.stansful.sshvpnclient.ui.designsystem.ShadowIcons
import com.stansful.sshvpnclient.ui.designsystem.shadowClickable
import com.stansful.sshvpnclient.ui.keys.SshKeyFormat
import com.stansful.sshvpnclient.ui.keys.SshKeyTraits
import com.stansful.sshvpnclient.ui.theme.Shadow
import com.stansful.sshvpnclient.ui.theme.ShadowShapes
import com.stansful.sshvpnclient.ui.theme.shadowTween

/** Saved key chooser: the chosen key in a field-like button and the inline list of every saved key. */
@Composable
internal fun KeyPicker(
    keys: List<SshPrivateKeySummary>,
    traits: Map<String, SshKeyTraits>,
    selectedId: String,
    error: String?,
    shakeTrigger: Int,
    onSelect: (String) -> Unit,
    onAddKey: () -> Unit,
    modifier: Modifier,
) {
    // The inline list starts closed again after a rotation or a trip to another screen.
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        FieldLabelRow(label = "Key", trailing = "${keys.size} saved")
        KeyPickerField(
            current = keys.firstOrNull { it.id == selectedId },
            format = traits[selectedId]?.format,
            open = open,
            isError = error != null,
            shakeTrigger = shakeTrigger,
            onToggle = { open = !open },
        )
        AnimatedVisibility(
            visible = open,
            enter = expandVertically(shadowTween(EDITOR_EXPAND_MS)) + fadeIn(shadowTween(EDITOR_EXPAND_FADE_MS)),
            exit = shrinkVertically(shadowTween(EDITOR_EXPAND_MS)) + fadeOut(shadowTween(EDITOR_EXPAND_FADE_MS)),
        ) {
            SavedKeyList(
                keys = keys,
                traits = traits,
                selectedId = selectedId,
                onSelect = { id ->
                    onSelect(id)
                    open = false
                },
                onAddKey = onAddKey,
            )
        }
        if (error != null) {
            FieldMessage(error, FieldTone.Error)
        } else {
            FieldMessage("The passphrase is stored with the key.", FieldTone.Helper)
        }
    }
}

/** The field-like button showing the chosen key ("work-ed25519 · OpenSSH") with a turning chevron. */
@Composable
private fun KeyPickerField(
    current: SshPrivateKeySummary?,
    format: SshKeyFormat?,
    open: Boolean,
    isError: Boolean,
    shakeTrigger: Int,
    onToggle: () -> Unit,
) {
    val colors = Shadow.colors
    val chevron by animateFloatAsState(
        targetValue = if (open) 180f else 0f,
        animationSpec = shadowTween(CHEVRON_MS),
        label = "key-picker-chevron",
    )
    val summary = when {
        current == null -> "none chosen"
        format != null -> "${current.name}, ${format.label}"
        else -> current.name
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(shakeModifier(shakeTrigger))
            .shadowClickable(
                interactionSource = remember { MutableInteractionSource() },
                pressedScale = PICKER_PRESS_SCALE,
                onClick = onToggle,
            )
            .semantics {
                contentDescription = "Key: $summary"
                stateDescription = if (open) "Saved keys shown" else "Saved keys hidden"
            }
            .height(52.dp)
            .then(fieldFrame(focused = false, isError = isError))
            // 8 / 12 dp inside the 1 dp border, as drawn.
            .padding(start = 9.dp, end = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(KeyTileShape)
                .background(colors.surface3),
            contentAlignment = Alignment.Center,
        ) {
            Icon(ShadowIcons.Key, contentDescription = null, tint = colors.amberText, modifier = Modifier.size(18.dp))
        }
        Text(
            text = buildAnnotatedString {
                if (current == null) {
                    withStyle(Shadow.type.body.toSpanStyle().copy(color = colors.ink1)) { append("Choose a key") }
                } else {
                    withStyle(Shadow.type.monoInput.toSpanStyle().copy(color = colors.ink1)) { append(current.name) }
                    if (format != null) {
                        withStyle(Shadow.type.bodyS.toSpanStyle().copy(color = colors.ink3)) {
                            append(" · ${format.label}")
                        }
                    }
                }
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = ShadowIcons.ChevronDown,
            contentDescription = null,
            tint = colors.ink3,
            modifier = Modifier
                .size(18.dp)
                .rotate(chevron),
        )
    }
}

/** The inline list of every saved key (radio-like rows) with "Add new key" below a rule. */
@Composable
private fun SavedKeyList(
    keys: List<SshPrivateKeySummary>,
    traits: Map<String, SshKeyTraits>,
    selectedId: String,
    onSelect: (String) -> Unit,
    onAddKey: () -> Unit,
) {
    val colors = Shadow.colors
    Column(
        modifier = Modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
            .clip(ShadowShapes.Card)
            .background(colors.surface1)
            .border(1.dp, colors.line, ShadowShapes.Card)
            // 6 dp inside the 1 dp border, as drawn.
            .padding(7.dp),
    ) {
        keys.forEach { key ->
            KeyOption(
                key = key,
                traits = traits[key.id],
                selected = key.id == selectedId,
                onClick = { onSelect(key.id) },
            )
            Spacer(Modifier.height(2.dp))
        }
        Box(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.line2),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(ShadowShapes.SegmentThumb)
                .shadowClickable(remember { MutableInteractionSource() }, onClick = onAddKey)
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = ShadowIcons.Plus,
                    contentDescription = null,
                    tint = colors.amberText,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(text = "Add new key", style = Shadow.type.button, color = colors.amberText)
        }
    }
}

@Composable
private fun KeyOption(
    key: SshPrivateKeySummary,
    traits: SshKeyTraits?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = Shadow.colors
    val usage = keyUsageLabel(key.usageCount)
    val subtitle = listOfNotNull(
        traits?.format?.label,
        "passphrase".takeIf { traits?.hasPassphrase == true },
        usage,
    ).let { parts ->
        if (parts.size > 1) {
            (parts.dropLast(1) + usage.replaceFirstChar { it.lowercase() }).joinToString(" · ")
        } else {
            usage
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(ShadowShapes.SegmentThumb)
            .background(if (selected) colors.amberTint else colors.amberTint.copy(alpha = 0f))
            .shadowClickable(
                interactionSource = remember { MutableInteractionSource() },
                role = Role.RadioButton,
                onClick = onClick,
            )
            .semantics { this.selected = selected }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            if (selected) {
                Icon(
                    imageVector = ShadowIcons.CheckCircle,
                    contentDescription = null,
                    tint = colors.amberText,
                    modifier = Modifier.size(22.dp),
                )
            } else {
                Box(
                    Modifier
                        .size(16.dp)
                        .border(1.5.dp, colors.ink3, CircleShape),
                )
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = key.name,
                style = Shadow.type.monoInput,
                color = colors.ink1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = Shadow.type.bodyS,
                color = colors.ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun NoKeysBlock(
    error: String?,
    shakeTrigger: Int,
    onAddKey: () -> Unit,
    modifier: Modifier,
) {
    val colors = Shadow.colors
    Column(modifier) {
        FieldLabelRow(label = "Key")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(shakeModifier(shakeTrigger))
                .then(fieldFrame(focused = false, isError = error != null, dashed = true))
                // 14 / 12 dp inside the 1 dp border, as drawn.
                .padding(horizontal = 13.dp, vertical = 15.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.Top)
                    .size(36.dp)
                    .clip(KeyTileShape)
                    .background(colors.surface3),
                contentAlignment = Alignment.Center,
            ) {
                Icon(ShadowIcons.Key, contentDescription = null, tint = colors.ink2, modifier = Modifier.size(18.dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "No saved keys yet",
                    style = Shadow.type.rowTitle.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.ink1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(text = "Add one and it is picked here for you.", style = Shadow.type.bodyS, color = colors.ink3)
            }
            TonalPillButton(
                text = "Add key",
                icon = ShadowIcons.Plus,
                onClick = onAddKey,
                startPadding = 10.dp,
                gap = 6.dp,
            )
        }
        if (error != null) FieldMessage(error, FieldTone.Error)
    }
}

private const val PICKER_PRESS_SCALE = 0.985f
private const val CHEVRON_MS = 280
