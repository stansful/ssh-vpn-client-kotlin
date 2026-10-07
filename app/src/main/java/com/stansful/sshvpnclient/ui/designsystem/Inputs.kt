package com.stansful.sshvpnclient.ui.designsystem

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stansful.sshvpnclient.ui.theme.Shadow
import com.stansful.sshvpnclient.ui.theme.ShadowDimens
import com.stansful.sshvpnclient.ui.theme.ShadowMotion
import com.stansful.sshvpnclient.ui.theme.ShadowShapes
import com.stansful.sshvpnclient.ui.theme.shadowTween
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Text field of the design: label above (13/600 ink-2, ink-1 while focused), 52 dp surface-2 field
 * with radius 14 and a 1 dp line border; focused = 1.5 dp amber border + soft ring; [error] = coral
 * border + coral helper with a warning icon (replaces [helper]). [mono] for hosts, ports, keys, URIs.
 * Multi-line when `singleLine = false` (grows from 52 dp). Label, helper and trailing buttons are
 * part of the field's decoration, so TalkBack reads the label with the value; put a
 * `focusRequester` on [modifier].
 */
@Composable
fun ShadowTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    helper: String? = null,
    error: String? = null,
    mono: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    leadingIcon: ImageVector? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null,
) {
    val colors = Shadow.colors
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val isError = error != null
    val textStyle = if (mono) Shadow.type.monoInput else Shadow.type.body
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .semantics { if (error != null) error(error) },
        enabled = enabled,
        readOnly = readOnly,
        textStyle = textStyle.copy(color = colors.ink1),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        visualTransformation = visualTransformation,
        interactionSource = source,
        cursorBrush = SolidColor(colors.amber),
        decorationBox = { inner ->
            FieldDecoration(
                label = label,
                focused = focused,
                isError = isError,
                helper = helper,
                error = error,
                leadingIcon = leadingIcon,
                trailing = trailing,
                singleLine = singleLine,
            ) {
                if (value.isEmpty() && placeholder != null) {
                    Text(
                        text = placeholder,
                        style = textStyle,
                        color = colors.ink3,
                        maxLines = if (singleLine) 1 else Int.MAX_VALUE,
                    )
                }
                inner()
            }
        },
    )
}

@Composable
private fun FieldDecoration(
    label: String?,
    focused: Boolean,
    isError: Boolean,
    helper: String?,
    error: String?,
    leadingIcon: ImageVector?,
    trailing: (@Composable RowScope.() -> Unit)?,
    singleLine: Boolean,
    input: @Composable () -> Unit,
) {
    val colors = Shadow.colors
    val labelColor by animateColorAsState(
        targetValue = if (focused) colors.ink1 else colors.ink2,
        animationSpec = shadowTween(ShadowMotion.Small, ShadowMotion.Ease),
        label = "field-label",
    )
    Column {
        if (label != null) {
            Text(
                text = label,
                style = Shadow.type.label,
                color = labelColor,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
            )
        }
        FieldFrame(focused = focused, isError = isError) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = colors.ink3,
                    modifier = Modifier
                        .padding(start = 16.dp)
                        .size(20.dp),
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(
                        start = if (leadingIcon != null) 10.dp else 16.dp,
                        end = if (trailing != null) 4.dp else 16.dp,
                        top = if (singleLine) 0.dp else 15.dp,
                        bottom = if (singleLine) 0.dp else 15.dp,
                    ),
                contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
            ) {
                input()
            }
            if (trailing != null) {
                Row(
                    modifier = Modifier.padding(end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = trailing,
                )
            }
        }
        FieldMessage(helper = helper, error = error)
    }
}

@Composable
private fun FieldFrame(
    focused: Boolean,
    isError: Boolean,
    minHeight: Dp = ShadowDimens.Field,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = Shadow.colors
    val shape = ShadowShapes.Input
    val borderColor by animateColorAsState(
        targetValue = when {
            isError -> colors.coral
            focused -> colors.amber
            else -> colors.line
        },
        animationSpec = shadowTween(ShadowMotion.Small, ShadowMotion.Ease),
        label = "field-border",
    )
    val borderWidth by animateDpAsState(
        targetValue = if (focused && !isError) 1.5.dp else 1.dp,
        animationSpec = shadowTween(ShadowMotion.Small, ShadowMotion.Ease),
        label = "field-border-width",
    )
    val ring by animateColorAsState(
        targetValue = when {
            !focused -> Color.Transparent
            isError -> colors.coral.copy(alpha = FOCUS_RING_ALPHA)
            else -> colors.amber.copy(alpha = FOCUS_RING_ALPHA)
        },
        animationSpec = shadowTween(ShadowMotion.Small, ShadowMotion.Ease),
        label = "field-ring",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .then(
                if (ring.alpha > 0f) {
                    Modifier.dropShadow(shape) {
                        spread = FOCUS_RING_WIDTH.toPx()
                        radius = 0f
                        color = ring
                    }
                } else {
                    Modifier
                },
            )
            .clip(shape)
            .background(colors.surface2)
            .border(borderWidth, borderColor, shape),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun FieldMessage(helper: String?, error: String?) {
    val colors = Shadow.colors
    when {
        error != null -> Row(
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = ShadowIcons.Warning,
                contentDescription = null,
                tint = colors.coralText,
                modifier = Modifier
                    .padding(top = 1.dp)
                    .size(14.dp),
            )
            Text(text = error, style = Shadow.type.caption, color = colors.coralText)
        }
        helper != null -> Text(
            text = helper,
            style = Shadow.type.caption,
            color = colors.ink3,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp),
        )
    }
}

/**
 * Secret field (password, passphrase, private key): masked with `•` until the eye is tapped
 * ("Show password"/"Hide password"); optional copy button ([copyLabel]) copies with
 * `ClipDescription.EXTRA_IS_SENSITIVE` on API 33+ and confirms with a toast on older systems
 * (or calls [onCopied]). Mono by default.
 */
@Composable
fun SecretTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    helper: String? = null,
    error: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    mono: Boolean = true,
    copyLabel: String? = null,
    onCopied: (() -> Unit)? = null,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions(
        keyboardType = KeyboardType.Password,
        autoCorrectEnabled = false,
    ),
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    val copier = rememberClipboardCopier()
    val noun = label.lowercase()
    ShadowTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = label,
        placeholder = placeholder,
        helper = helper,
        error = error,
        mono = mono,
        singleLine = singleLine,
        minLines = minLines,
        enabled = enabled,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        // A real PasswordVisualTransformation: Compose then turns off Copy/Cut in the text toolbar and
        // marks the field as a password for accessibility and autofill.
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailing = {
            if (copyLabel != null) {
                ShadowIconButton(
                    icon = ShadowIcons.Copy,
                    contentDescription = "Copy $noun",
                    onClick = {
                        copier.copy(
                            label = copyLabel,
                            text = value,
                            sensitive = true,
                            toast = if (onCopied == null) COPIED_MESSAGE else null,
                        )
                        onCopied?.invoke()
                    },
                    enabled = enabled && value.isNotEmpty(),
                    iconSize = 20.dp,
                )
            }
            ShadowIconButton(
                icon = if (visible) ShadowIcons.EyeOff else ShadowIcons.Eye,
                contentDescription = if (visible) "Hide $noun" else "Show $noun",
                onClick = { visible = !visible },
                enabled = enabled,
                iconSize = 20.dp,
            )
        },
    )
}

/**
 * Search input: [height] (52 dp; 48 on tablet panes) surface-2 field.
 * - Without [onClose] (App routing, tablet Routes): a 20 dp search icon centered in a square of the
 *   field's height (16 dp in from the edge at 52, 14 at 48), text 11 dp after it, and a "Clear search"
 *   button while [query] is not empty, as far from the end as the icon is from the top.
 * - With [onClose] (Routes' toggled search): an 18 dp icon 14 dp inside the border, text 10 dp after it,
 *   and a 44 dp "Close search" x 3 dp inside the border, always shown.
 * Put a `focusRequester` on [modifier].
 */
@Composable
fun ShadowSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search",
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    onClose: (() -> Unit)? = null,
    closeContentDescription: String = "Close search",
    height: Dp = 52.dp,
) {
    val colors = Shadow.colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        textStyle = Shadow.type.body.copy(color = colors.ink1),
        singleLine = true,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
        keyboardActions = keyboardActions,
        interactionSource = source,
        cursorBrush = SolidColor(colors.amber),
        decorationBox = { inner ->
            FieldFrame(focused = focused, isError = false, minHeight = height) {
                // Artboard CSS: the field's 1 px border takes up space, so insets measured inside it get + 1.
                val iconSize = if (onClose != null) 18.dp else 20.dp
                val iconStart = if (onClose != null) SEARCH_LINE + 14.dp else (height - iconSize) / 2
                Icon(
                    imageVector = ShadowIcons.Search,
                    contentDescription = null,
                    tint = colors.ink3,
                    modifier = Modifier
                        .padding(start = iconStart)
                        .size(iconSize),
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(
                            start = if (onClose != null) 10.dp else 11.dp,
                            end = if (onClose != null) 10.dp else 4.dp,
                        ),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (query.isEmpty()) {
                        Text(placeholder, style = Shadow.type.body, color = colors.ink3, maxLines = 1)
                    }
                    inner()
                }
                if (onClose != null) {
                    ShadowIconButton(
                        icon = ShadowIcons.Close,
                        contentDescription = closeContentDescription,
                        onClick = onClose,
                        modifier = Modifier.padding(end = SEARCH_LINE + 3.dp),
                        tint = colors.ink3,
                        iconSize = 18.dp,
                    )
                } else {
                    AnimatedVisibility(
                        visible = query.isNotEmpty(),
                        enter = fadeIn(shadowTween(ShadowMotion.Small)),
                        exit = fadeOut(shadowTween(ShadowMotion.Small)),
                    ) {
                        ClearSearchButton(
                            onClick = { onQueryChange("") },
                            modifier = Modifier.padding(end = ((height - CLEAR_BUTTON_SIZE) / 2).coerceAtLeast(0.dp)),
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun ClearSearchButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Shadow.colors
    Box(
        modifier = modifier
            .size(CLEAR_BUTTON_SIZE)
            .clip(ShadowShapes.Tile)
            .shadowClickable(remember { MutableInteractionSource() }, onClick = onClick)
            .semantics { contentDescription = "Clear search" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(colors.surface3),
            contentAlignment = Alignment.Center,
        ) {
            Icon(ClearGlyph, contentDescription = null, tint = colors.ink2, modifier = Modifier.size(14.dp))
        }
    }
}

/**
 * Text of the clip's first item, read on [Dispatchers.IO]: a copied file (content URI) is read as a
 * text stream of at most [maxChars] characters (its URI when it can't be read, like
 * `ClipData.Item.coerceToText`); plain text is capped at [maxChars] too. `null` for an empty clip.
 */
suspend fun ClipData?.readText(context: Context, maxChars: Int = CLIP_TEXT_MAX_CHARS): String? {
    val item = this?.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return null
    return withContext(Dispatchers.IO) {
        val uri = item.uri
        if (item.text == null && uri != null) {
            readTextStream(context, uri, maxChars) ?: uri.toString()
        } else {
            runCatching { item.coerceToText(context)?.toString() }.getOrNull()?.take(maxChars)
        }
    }
}

private fun readTextStream(context: Context, uri: Uri, maxChars: Int): String? = runCatching {
    context.contentResolver.openTypedAssetFileDescriptor(uri, "text/*", null)?.use { descriptor ->
        descriptor.createInputStream().reader(Charsets.UTF_8).use { reader ->
            val buffer = CharArray(CLIP_READ_BUFFER)
            val text = StringBuilder()
            while (text.length < maxChars) {
                val read = reader.read(buffer, 0, minOf(buffer.size, maxChars - text.length))
                if (read < 0) break
                text.appendRange(buffer, 0, read)
            }
            text.toString()
        }
    }
}.getOrNull()

/** Enough for the route importer's 10 000-link batches. */
const val CLIP_TEXT_MAX_CHARS = 4_000_000
private const val CLIP_READ_BUFFER = 8_192

/** Plain-text clip; on API 33+ flagged `EXTRA_IS_SENSITIVE` when [sensitive] (hidden from previews). */
fun clipDataFor(label: String, value: String, sensitive: Boolean): ClipData {
    return ClipData.newPlainText(label, value).also { clipData ->
        if (sensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clipData.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
    }
}

/** Copies text to the clipboard and shows the "Copied to clipboard" toast (pre-Android 13 only). */
@Stable
class ClipboardCopier internal constructor(
    private val clipboard: Clipboard,
    private val scope: CoroutineScope,
    private val toaster: ToasterState,
) {
    /** Copies [text]; [toast] null suppresses the confirmation. Android 13+ shows its own. */
    fun copy(label: String, text: String, sensitive: Boolean = false, toast: String? = COPIED_MESSAGE) {
        scope.launch {
            clipboard.setClipEntry(ClipEntry(clipDataFor(label, text, sensitive)))
        }
        if (toast != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            toaster.show(toast, tone = ToastTone.Neutral, icon = ShadowIcons.Copy)
        }
    }
}

/** Remembers a [ClipboardCopier] bound to `LocalClipboard` and `LocalToaster`. */
@Composable
fun rememberClipboardCopier(): ClipboardCopier {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    return remember(clipboard, scope, toaster) { ClipboardCopier(clipboard, scope, toaster) }
}

private val ClearGlyph by lazy { strokeIcon("clear", "M6 6l12 12", "M18 6L6 18", strokeWidth = 2.2f) }
private val FOCUS_RING_WIDTH = 3.dp
private val SEARCH_LINE = 1.dp
private val CLEAR_BUTTON_SIZE = 44.dp
private const val FOCUS_RING_ALPHA = 0.16f
private const val COPIED_MESSAGE = "Copied to clipboard"
