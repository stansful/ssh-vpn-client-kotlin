package com.stansful.sshvpnclient.ui.servers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stansful.sshvpnclient.ui.configedit.EditConfigForm
import com.stansful.sshvpnclient.ui.designsystem.LocalToaster
import com.stansful.sshvpnclient.ui.designsystem.SectionHeader
import com.stansful.sshvpnclient.ui.designsystem.ShadowIconButton
import com.stansful.sshvpnclient.ui.designsystem.ShadowIcons
import com.stansful.sshvpnclient.ui.designsystem.ToastTone
import com.stansful.sshvpnclient.ui.designsystem.readText
import com.stansful.sshvpnclient.ui.designsystem.shadowClickable
import com.stansful.sshvpnclient.ui.theme.Shadow
import com.stansful.sshvpnclient.ui.theme.ShadowShapes
import com.stansful.sshvpnclient.ui.theme.shadowTween
import kotlinx.coroutines.launch
import java.util.Base64

@Composable
internal fun IdentitySection(
    form: EditConfigForm,
    onFormChange: ((EditConfigForm) -> EditConfigForm) -> Unit,
    onOpenActivity: () -> Unit,
    modifier: Modifier,
) {
    val colors = Shadow.colors
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val empty = form.fingerprint.isBlank()
    val kind = remember(form.fingerprint) { fingerprintKind(form.fingerprint) }
    Column(modifier) {
        SectionHeader("Host identity")
        EditorTextField(
            value = form.fingerprint,
            onValueChange = { value -> onFormChange { it.copy(fingerprint = value) } },
            label = "Fingerprint",
            labelTrailing = "Optional · recommended",
            placeholder = "SHA256:…",
            textStyle = Shadow.type.monoMedium,
            contentPadding = PaddingValues(start = FIELD_TEXT_INSET, end = 8.dp),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Ascii,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
            ),
            trailing = {
                if (empty) {
                    PasteChip(
                        onClick = {
                            scope.launch {
                                val text = clipboard.getClipEntry()?.clipData.readText(context)?.trim()
                                if (text.isNullOrEmpty()) {
                                    toaster.show("Clipboard is empty", tone = ToastTone.Info)
                                } else {
                                    onFormChange { it.copy(fingerprint = text) }
                                }
                            }
                        },
                    )
                } else {
                    if (kind != null) FingerprintBadge(kind)
                    ShadowIconButton(
                        icon = ShadowIcons.Close,
                        contentDescription = "Clear fingerprint",
                        onClick = { onFormChange { it.copy(fingerprint = "") } },
                        tint = colors.ink3,
                        iconSize = 18.dp,
                    )
                }
            },
        )
        when {
            empty -> FieldMessage(
                "Paste SHA256 or MD5. We check it before sending your password or key.",
                FieldTone.Helper,
            )
            kind != null -> FieldMessage(
                "${kind.label} fingerprint. We check it before sending your password or key.",
                FieldTone.Success,
            )
            else -> FieldMessage(
                "This is not SHA256 or MD5, so the identity check would stop the connection.",
                FieldTone.Warning,
            )
        }
        AnimatedVisibility(
            visible = empty,
            enter = expandVertically(shadowTween(EDITOR_EXPAND_MS)) + fadeIn(shadowTween(EDITOR_EXPAND_FADE_MS)),
            exit = shrinkVertically(shadowTween(EDITOR_EXPAND_MS)) + fadeOut(shadowTween(EDITOR_EXPAND_FADE_MS)),
        ) {
            FingerprintWarning(onOpenActivity = onOpenActivity)
        }
    }
}

@Composable
private fun PasteChip(onClick: () -> Unit) {
    val colors = Shadow.colors
    Box(
        modifier = Modifier
            .height(44.dp)
            .shadowClickable(remember { MutableInteractionSource() }, onClick = onClick)
            .semantics { contentDescription = "Paste fingerprint" }
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .height(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.surface3)
                .padding(start = 10.dp, end = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(ShadowIcons.Clipboard, contentDescription = null, tint = colors.ink1, modifier = Modifier.size(16.dp))
            Text(text = "Paste", style = Shadow.type.label, color = colors.ink1)
        }
    }
}

@Composable
private fun FingerprintBadge(kind: FingerprintKind) {
    Box(
        modifier = Modifier
            .height(24.dp)
            .clip(ShadowShapes.Pill)
            .background(Shadow.colors.mintTint)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = kind.label,
            style = TagMonoStyle.copy(fontSize = 11.sp, lineHeight = 14.sp),
            color = Shadow.colors.mintText,
        )
    }
}

/** Amber note shown while no fingerprint is set, with the way to find one in Activity. */
@Composable
private fun FingerprintWarning(onOpenActivity: () -> Unit) {
    val colors = Shadow.colors
    Column(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
            .clip(ShadowShapes.Banner)
            .background(colors.amberTint),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(ShadowIcons.Warning, contentDescription = null, tint = colors.amberText, modifier = NoteIconSize)
            Text(
                text = "Without a fingerprint any server can pretend to be this one.",
                style = Shadow.type.bodyS,
                color = colors.ink1,
                modifier = Modifier.weight(1f),
            )
        }
        Box(
            modifier = Modifier
                .padding(horizontal = 14.dp)
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.amber.copy(alpha = WARNING_DIVIDER_ALPHA)),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .shadowClickable(remember { MutableInteractionSource() }, onClick = onOpenActivity)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = ShadowIcons.Activity,
                contentDescription = null,
                tint = colors.amberText,
                modifier = Modifier
                    .align(Alignment.Top)
                    .size(18.dp),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = "Where do I find it?", style = Shadow.type.label, color = colors.amberText)
                Text(
                    text = "Connect once, open Activity and copy the line “Server host key”.",
                    style = Shadow.type.bodyS,
                    color = colors.ink2,
                )
            }
            Icon(ShadowIcons.ChevronRight, contentDescription = null, tint = colors.ink3, modifier = NoteIconSize)
        }
    }
}

/** Kind of host-key fingerprint, as the connection's identity check understands it. */
internal enum class FingerprintKind(val label: String) { Sha256("SHA256"), Md5("MD5") }

/**
 * Mirrors the connection's fingerprint parser: `SHA256:` + base64 of 32 bytes (prefix optional, `=`
 * optional) or 32 hex digits of MD5 (`:`/space separators and the `MD5:` prefix optional).
 */
internal fun fingerprintKind(value: String): FingerprintKind? {
    val trimmed = value.trim()
    return when {
        trimmed.isEmpty() -> null
        isSha256Fingerprint(trimmed) -> FingerprintKind.Sha256
        isMd5Fingerprint(trimmed) -> FingerprintKind.Md5
        else -> null
    }
}

private fun isSha256Fingerprint(value: String): Boolean {
    val encoded = when {
        value.startsWith("SHA256:", ignoreCase = true) -> value.substringAfter(':').trim()
        ':' !in value -> value
        else -> return false
    }.trimEnd('=')
    if (encoded.isBlank()) return false
    val padded = encoded + "=".repeat((4 - encoded.length % 4) % 4)
    return runCatching { Base64.getDecoder().decode(padded) }.getOrNull()?.size == SHA256_BYTES
}

private fun isMd5Fingerprint(value: String): Boolean {
    val withoutPrefix = if (value.startsWith("MD5:", ignoreCase = true)) value.substringAfter(':') else value
    return MD5_HEX.matches(withoutPrefix.replace(":", "").replace(" ", ""))
}

private val MD5_HEX = Regex("(?i)^[0-9a-f]{32}$")
private const val SHA256_BYTES = 32
private const val WARNING_DIVIDER_ALPHA = 0.16f
private val NoteIconSize = Modifier.size(18.dp)
