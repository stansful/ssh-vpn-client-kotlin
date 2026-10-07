package com.stansful.sshvpnclient.ui.servers

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.stansful.sshvpnclient.ui.designsystem.BannerTone
import com.stansful.sshvpnclient.ui.designsystem.InlineBanner
import com.stansful.sshvpnclient.ui.designsystem.LocalToaster
import com.stansful.sshvpnclient.ui.designsystem.PrimaryButton
import com.stansful.sshvpnclient.ui.designsystem.ShadowIconButton
import com.stansful.sshvpnclient.ui.designsystem.ShadowIcons
import com.stansful.sshvpnclient.ui.designsystem.SubScreenBar
import com.stansful.sshvpnclient.ui.designsystem.ToastTone
import com.stansful.sshvpnclient.ui.designsystem.fadeUpIn
import com.stansful.sshvpnclient.ui.designsystem.readText
import com.stansful.sshvpnclient.ui.designsystem.rememberClipboardCopier
import com.stansful.sshvpnclient.ui.keyedit.EditKeyForm
import com.stansful.sshvpnclient.ui.keyedit.EditKeyUiState
import com.stansful.sshvpnclient.ui.theme.Shadow
import com.stansful.sshvpnclient.ui.theme.ShadowMotion
import com.stansful.sshvpnclient.ui.theme.shadowTween
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Key editor ("Add key" / "Edit key"): name, the private key (masked, revealable, pasted from the
 * clipboard, copyable as sensitive), optional passphrase and note. A failed save shakes the invalid
 * fields and scrolls the first one into view.
 */
@Composable
internal fun KeyEditorScreen(
    state: EditKeyUiState,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onFormChange: ((EditKeyForm) -> EditKeyForm) -> Unit,
    modifier: Modifier = Modifier,
) {
    val form = state.form
    val scroll = rememberScrollState()
    // Recompose when the bar's divider flips, not on every scrolled pixel.
    val scrolled by remember(scroll) { derivedStateOf { scroll.value > EDITOR_SCROLLED_PX } }
    val nameRequester = remember { BringIntoViewRequester() }
    val keyRequester = remember { BringIntoViewRequester() }
    val nameError = if (state.errors.containsKey("name")) "Name the key to save it." else null
    val keyError = if (state.errors.containsKey("privateKey")) privateKeyProblem(form.privateKey) else null
    var shakeTick by remember { mutableIntStateOf(0) }
    var handledRound by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(state.validationRound) {
        if (state.validationRound > handledRound) {
            handledRound = state.validationRound
            shakeTick += 1
            when {
                nameError != null -> nameRequester.bringIntoView()
                keyError != null -> keyRequester.bringIntoView()
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Shadow.colors.bg),
    ) {
        SubScreenBar(
            title = if (state.isEditing) "Edit key" else "Add key",
            onBack = onBack,
            showDivider = scrolled,
            // Same header as the server editor.
            titleStartPadding = 4.dp,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .windowInsetsPadding(EditorSideInsets)
                .verticalScroll(scroll),
            contentAlignment = Alignment.TopCenter,
        ) {
            KeyEditorFields(
                state = state,
                nameError = nameError,
                keyError = keyError,
                shakeTick = shakeTick,
                nameRequester = nameRequester,
                keyRequester = keyRequester,
                onFormChange = onFormChange,
            )
        }
        KeyEditorFooter(
            state = state,
            onSave = onSave,
            modifier = Modifier.fadeUpIn(6),
        )
    }
}

/** Name, private key, passphrase and note, with the edit-mode note on who uses the key. */
@Composable
private fun KeyEditorFields(
    state: EditKeyUiState,
    nameError: String?,
    keyError: String?,
    shakeTick: Int,
    nameRequester: BringIntoViewRequester,
    keyRequester: BringIntoViewRequester,
    onFormChange: ((EditKeyForm) -> EditKeyForm) -> Unit,
) {
    val form = state.form
    Column(
        modifier = Modifier
            .widthIn(max = EDITOR_MAX_WIDTH)
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 16.dp),
    ) {
        StorageLine(modifier = Modifier.fadeUpIn(0))
        if (state.isEditing) {
            InlineBanner(
                message = editNote(state.usedBy),
                tone = BannerTone.Info,
                tintedMessage = true,
                modifier = Modifier
                    .padding(bottom = 18.dp)
                    .fadeUpIn(1),
            )
        }
        Column(
            Modifier
                .bringIntoViewRequester(nameRequester)
                .fadeUpIn(2),
        ) {
            EditorTextField(
                value = form.name,
                onValueChange = { value -> onFormChange { it.copy(name = value) } },
                label = "Name",
                placeholder = "e.g. work-ed25519",
                error = nameError,
                frameModifier = shakeModifier(if (nameError != null) shakeTick else 0),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
            )
            if (nameError != null) FieldMessage(nameError, FieldTone.Error, topPadding = 8.dp)
        }
        PrivateKeyField(
            value = form.privateKey,
            error = keyError,
            shakeTrigger = if (keyError != null) shakeTick else 0,
            onValueChange = { value -> onFormChange { it.copy(privateKey = value) } },
            modifier = Modifier
                .padding(top = 18.dp)
                .bringIntoViewRequester(keyRequester)
                .fadeUpIn(3),
        )
        PassphraseField(
            value = form.passphrase,
            onValueChange = { value -> onFormChange { it.copy(passphrase = value) } },
            modifier = Modifier
                .padding(top = 14.dp)
                .fadeUpIn(4),
        )
        EditorTextField(
            value = form.note,
            onValueChange = { value -> onFormChange { it.copy(note = value) } },
            modifier = Modifier
                .padding(top = 18.dp)
                .fadeUpIn(5),
            label = "Note",
            labelTrailing = "Optional",
            placeholder = "What it is for, where it came from",
            textStyle = Shadow.type.body.copy(lineHeight = 20.sp),
            singleLine = false,
            minHeight = NOTE_MIN_HEIGHT,
            contentPadding = PaddingValues(horizontal = FIELD_TEXT_INSET, vertical = NOTE_TEXT_TOP),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )
    }
}

@Composable
private fun StorageLine(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(ShadowIcons.Lock, contentDescription = null, tint = Shadow.colors.ink3, modifier = Modifier.size(14.dp))
        Text(text = "Encrypted on this device. No backups.", style = Shadow.type.bodyS, color = Shadow.colors.ink3)
    }
}

/** What the private key box says about its content on the right of its label. */
private enum class KeyStatus { Hidden, Visible, Pasted }

/**
 * The private key: masked as a block of bullets (one row per line) until revealed, editable when empty
 * or revealed. A whole key arriving at once (clipboard button or keyboard paste) is hidden right away;
 * typing by hand keeps the text visible.
 */
@Composable
private fun PrivateKeyField(
    value: String,
    error: String?,
    shakeTrigger: Int,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Shadow.colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    // Revealed only while on screen (a rotation or leaving the screen masks it again).
    var shown by remember { mutableStateOf(false) }
    var pastedTick by remember { mutableIntStateOf(0) }
    var pasted by remember { mutableStateOf(false) }
    LaunchedEffect(pastedTick) {
        if (pastedTick == 0) return@LaunchedEffect
        pasted = true
        delay(PASTED_STATUS_MS)
        pasted = false
    }
    val empty = value.isBlank()
    val shownNow = shown && !empty
    val lines = if (empty) 0 else value.trim().lines().size
    val linesLabel = if (lines == 1) "1 line" else "$lines lines"
    val status = when {
        empty -> null
        pasted -> KeyStatus.Pasted
        shown -> KeyStatus.Visible
        else -> KeyStatus.Hidden
    }

    fun accept(text: String) {
        val wasEmpty = value.isBlank()
        when {
            wasEmpty && !shown && text.trim().length > 1 -> pastedTick += 1
            wasEmpty && !shown && text.isNotBlank() -> shown = true
            else -> pasted = false
        }
        onValueChange(text)
    }

    Column(modifier) {
        KeyLabelRow(status = status, linesLabel = linesLabel, focused = focused)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(shakeModifier(shakeTrigger))
                .then(fieldFrame(focused = focused, isError = error != null, exposed = shownNow)),
        ) {
            if (!empty && !shown) {
                MaskedKey(value = value, lines = linesLabel)
            } else {
                BasicTextField(
                    value = value,
                    onValueChange = ::accept,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(KEY_BOX_HEIGHT),
                    textStyle = Shadow.type.monoS.copy(color = colors.ink1),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                    interactionSource = source,
                    cursorBrush = SolidColor(colors.amber),
                    decorationBox = { inner ->
                        Box(Modifier.padding(KeyTextPadding)) {
                            if (value.isEmpty()) {
                                Text(text = KEY_PLACEHOLDER, style = Shadow.type.monoS, color = colors.ink3)
                            }
                            inner()
                        }
                    },
                )
            }
            KeyBoxActions(
                value = value,
                shown = shownNow,
                onPasted = { text ->
                    shown = false
                    pastedTick += 1
                    onValueChange(text)
                },
                onToggleShown = {
                    pasted = false
                    shown = !shown
                },
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp),
        ) {
            if (error != null) {
                FieldMessage(error, FieldTone.Error, topPadding = 8.dp)
            } else {
                FieldMessage(
                    "OpenSSH or RSA PEM. Paste the private key, not the .pub file.",
                    FieldTone.Helper,
                    topPadding = 8.dp,
                )
            }
        }
    }
}

/** "Private key" label with the box's status on the right: Hidden · N lines / Visible / Pasted. */
@Composable
private fun KeyLabelRow(status: KeyStatus?, linesLabel: String, focused: Boolean) {
    val colors = Shadow.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 18.dp)
            .padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Private key",
            style = Shadow.type.label,
            color = if (focused) colors.ink1 else colors.ink2,
            modifier = Modifier.weight(1f),
        )
        val statusFade = shadowTween<Float>(ShadowMotion.Swap)
        AnimatedContent(
            targetState = status,
            transitionSpec = { fadeIn(statusFade) togetherWith fadeOut(statusFade) },
            label = "key-status",
        ) { current ->
            when (current) {
                KeyStatus.Hidden -> KeyStatusLabel(ShadowIcons.Lock, "Hidden · $linesLabel", colors.ink3)
                KeyStatus.Visible -> KeyStatusLabel(ShadowIcons.Eye, "Visible on screen", colors.amberText)
                KeyStatus.Pasted -> KeyStatusLabel(ShadowIcons.CheckCircle, "Pasted · $linesLabel", colors.mintText)
                null -> Spacer(Modifier.height(16.dp))
            }
        }
    }
}

/** Bottom row of the key box: "Paste from clipboard", copy (sensitive) and show/hide. */
@Composable
private fun KeyBoxActions(
    value: String,
    shown: Boolean,
    onPasted: (String) -> Unit,
    onToggleShown: () -> Unit,
) {
    val colors = Shadow.colors
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val toaster = LocalToaster.current
    val copier = rememberClipboardCopier()
    val scope = rememberCoroutineScope()
    val empty = value.isBlank()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 4 4 4 8 inside the 1 dp border, with 44 dp touch targets around the 40 dp buttons.
            .padding(start = 9.dp, end = 5.dp, top = 3.dp, bottom = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TonalPillButton(
            text = "Paste from clipboard",
            icon = ShadowIcons.Clipboard,
            onClick = {
                scope.launch {
                    val text = clipboard.getClipEntry()?.clipData.readText(context)
                    if (text.isNullOrBlank()) {
                        toaster.show("Clipboard is empty", tone = ToastTone.Info)
                    } else {
                        onPasted(text)
                    }
                }
            },
        )
        Spacer(Modifier.weight(1f))
        ShadowIconButton(
            icon = ShadowIcons.Copy,
            contentDescription = "Copy private key",
            onClick = { copier.copy("Private key", value, sensitive = true, toast = COPIED_SENSITIVE) },
            enabled = !empty,
            tint = colors.ink2,
            iconSize = 19.dp,
        )
        ShadowIconButton(
            icon = if (shown) ShadowIcons.EyeOff else ShadowIcons.Eye,
            contentDescription = if (shown) "Hide private key" else "Show private key",
            onClick = onToggleShown,
            enabled = !empty,
            tint = if (shown) colors.amberText else colors.ink2,
            iconSize = 20.dp,
        )
    }
}

@Composable
private fun KeyStatusLabel(icon: ImageVector, text: String, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Text(text = text, style = Shadow.type.caption.copy(fontWeight = FontWeight.Medium), color = color)
    }
}

/** Read-only bullets in the key's shape (each line 6–34 bullets), scrollable inside the 128 dp box. */
@Composable
private fun MaskedKey(value: String, lines: String) {
    val masked = remember(value) {
        value.trim().lines().joinToString("\n") { line ->
            val length = line.trim().length
            if (length == 0) "" else "•".repeat(length.coerceIn(MASK_MIN, MASK_MAX))
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(KEY_BOX_HEIGHT)
            .verticalScroll(rememberScrollState())
            .semantics { contentDescription = "Private key, hidden, $lines" }
            .padding(KeyTextPadding),
    ) {
        Text(
            text = masked,
            style = Shadow.type.monoS.copy(letterSpacing = MASK_LETTER_SPACING),
            color = Shadow.colors.ink2,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
    }
}

@Composable
private fun PassphraseField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Shadow.colors
    val copier = rememberClipboardCopier()
    var shown by remember { mutableStateOf(false) }
    val shownNow = shown && value.isNotEmpty()
    Column(modifier) {
        EditorTextField(
            value = value,
            onValueChange = onValueChange,
            label = "Passphrase",
            labelTrailing = "Optional",
            placeholder = "Only if the key has one",
            textStyle = Shadow.type.monoInput,
            exposed = shownNow,
            contentPadding = PaddingValues(start = FIELD_TEXT_INSET, end = 2.dp),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Next,
            ),
            visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
            trailing = {
                ShadowIconButton(
                    icon = ShadowIcons.Copy,
                    contentDescription = "Copy passphrase",
                    onClick = { copier.copy("Passphrase", value, sensitive = true, toast = COPIED_SENSITIVE) },
                    enabled = value.isNotEmpty(),
                    tint = colors.ink2,
                    iconSize = 19.dp,
                )
                Spacer(Modifier.width(2.dp))
                ShadowIconButton(
                    icon = if (shown) ShadowIcons.EyeOff else ShadowIcons.Eye,
                    contentDescription = if (shown) "Hide passphrase" else "Show passphrase",
                    onClick = { shown = !shown },
                    tint = if (shownNow) colors.amberText else colors.ink2,
                    iconSize = 20.dp,
                )
            },
        )
        FieldMessage("Checked when you connect. Leave empty if none.", FieldTone.Helper, topPadding = 8.dp)
    }
}

@Composable
private fun KeyEditorFooter(
    state: EditKeyUiState,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val line = Shadow.colors.line
    Column(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind { drawLine(line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime).only(WindowInsetsSides.Bottom))
            .windowInsetsPadding(EditorSideInsets)
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = EDITOR_MAX_WIDTH - 40.dp)
                .fillMaxWidth(),
        ) {
            if (state.message != null) {
                SaveErrorBanner(
                    message = "Couldn’t save the key. The encrypted storage on this phone didn’t accept it. " +
                        "Try again.",
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            PrimaryButton(
                text = when {
                    state.isSaving -> "Saving"
                    state.isEditing -> "Save changes"
                    else -> "Save key"
                },
                onClick = onSave,
                modifier = Modifier.fillMaxWidth(),
                loading = state.isSaving,
            )
        }
    }
}

/** Info note of the edit mode: who signs in with the key and when changes apply. */
private fun editNote(usedBy: List<String>): String = when (usedBy.size) {
    0 -> "No server uses this key yet."
    1 -> "${usedBy.first()} signs in with this key. Changes apply the next time you connect."
    else -> "${usedBy.first()} and ${usedBy.size - 1} more sign in with this key. " +
        "Changes apply the next time you connect."
}

/** Design wording of the validator's private-key error for the text that failed. */
private fun privateKeyProblem(value: String): String {
    val trimmed = value.trim()
    return when {
        trimmed.isEmpty() -> "Paste a private key to save it."
        PUBLIC_KEY_PREFIXES.any { trimmed.startsWith(it) } ->
            "This looks like a public key (.pub). Paste the private key."
        else -> "We couldn’t read this key. Use OpenSSH or RSA PEM."
    }
}

private val PUBLIC_KEY_PREFIXES = listOf(
    "ssh-rsa ",
    "ssh-ed25519 ",
    "ecdsa-sha2-nistp256 ",
    "ecdsa-sha2-nistp384 ",
    "ecdsa-sha2-nistp521 ",
)
private const val KEY_PLACEHOLDER = "-----BEGIN OPENSSH PRIVATE KEY-----\n...\n-----END OPENSSH PRIVATE KEY-----"
private const val COPIED_SENSITIVE = "Copied · hidden from clipboard preview"
private val KEY_BOX_HEIGHT = 128.dp

/** The key text sits 12 / 16 dp inside the box's 1 dp border (4 dp above the action row). */
private val KeyTextPadding = PaddingValues(start = 17.dp, end = 17.dp, top = 13.dp, bottom = 4.dp)

/** The note's 72 dp textarea plus its 1 dp border; text 14 dp in from the border. */
private val NOTE_MIN_HEIGHT = 74.dp
private val NOTE_TEXT_TOP = 15.dp
private val MASK_LETTER_SPACING = 0.06.em
private const val MASK_MIN = 6
private const val MASK_MAX = 34
private const val PASTED_STATUS_MS = 1_800L
