package com.stansful.sshvpnclient.ui.servers

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.stansful.sshvpnclient.domain.model.AuthType
import com.stansful.sshvpnclient.ui.configedit.EditConfigForm
import com.stansful.sshvpnclient.ui.configedit.EditConfigUiState
import com.stansful.sshvpnclient.ui.designsystem.SectionHeader
import com.stansful.sshvpnclient.ui.designsystem.SegmentOption
import com.stansful.sshvpnclient.ui.designsystem.ShadowButtonSize
import com.stansful.sshvpnclient.ui.designsystem.ShadowIconButton
import com.stansful.sshvpnclient.ui.designsystem.ShadowIcons
import com.stansful.sshvpnclient.ui.designsystem.ShadowSegmented
import com.stansful.sshvpnclient.ui.designsystem.ShadowTextButton
import com.stansful.sshvpnclient.ui.designsystem.SubScreenBar
import com.stansful.sshvpnclient.ui.designsystem.fadeUpIn
import com.stansful.sshvpnclient.ui.theme.Shadow
import com.stansful.sshvpnclient.ui.theme.ShadowMotion
import com.stansful.sshvpnclient.ui.theme.shadowTween
import kotlinx.coroutines.launch

/** Fields of the server form in screen order, with the design's error copy for each rule. */
internal enum class ServerField(val key: String, val label: String, val message: String) {
    Name("name", "Name", "Give this server a name"),
    Host("host", "Host", "Enter a domain or IP address"),
    Port("port", "Port", "Port must be between 1 and 65535"),
    Username("username", "Username", "Enter the username you sign in with"),
    Password("password", "Password", "Enter the password for this account"),
    Key("privateKeyId", "Key", "Add a key first, or sign in with a password"),
    Keepalive("keepAliveIntervalSec", "Keepalive", "Choose a keepalive interval above 0 s"),
}

internal enum class SavePhase { Idle, Saving, Saved, Failed }

/**
 * Server editor ("New server" / "Edit server"): profile, connection, sign-in (password or a saved key),
 * host fingerprint, keepalive and note, with Save in the top bar and in the pinned footer.
 */
@Composable
internal fun ServerEditorScreen(
    state: EditConfigUiState,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onFormChange: ((EditConfigForm) -> EditConfigForm) -> Unit,
    onAuthTypeChange: (AuthType) -> Unit,
    onKeySelect: (String) -> Unit,
    onAddKey: () -> Unit,
    onOpenActivity: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val form = state.form
    val scroll = rememberScrollState()
    // Recompose when the bar's divider flips, not on every scrolled pixel.
    val scrolled by remember(scroll) { derivedStateOf { scroll.value > EDITOR_SCROLLED_PX } }
    val scope = rememberCoroutineScope()
    val requesters = remember { ServerField.entries.associateWith { BringIntoViewRequester() } }
    var shakeField by remember { mutableStateOf<ServerField?>(null) }
    var shakeTick by remember { mutableIntStateOf(0) }
    val invalid = ServerField.entries.filter { state.errors.containsKey(it.key) }
    fun errorOf(field: ServerField): String? = field.message.takeIf { field in invalid }
    fun shakeOf(field: ServerField): Int = if (field == shakeField) shakeTick else 0
    fun nudge() {
        val first = invalid.firstOrNull() ?: return
        shakeField = first
        shakeTick += 1
        scope.launch { requesters.getValue(first).bringIntoView() }
    }
    var handledRound by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(state.validationRound) {
        if (state.validationRound > handledRound) {
            handledRound = state.validationRound
            nudge()
        }
    }
    val phase = when {
        state.isSaved -> SavePhase.Saved
        state.isSaving -> SavePhase.Saving
        state.message != null -> SavePhase.Failed
        else -> SavePhase.Idle
    }
    val busy = phase == SavePhase.Saving || phase == SavePhase.Saved

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Shadow.colors.bg),
    ) {
        SubScreenBar(
            title = if (state.isEditing) "Edit server" else "New server",
            onBack = onBack,
            showDivider = scrolled,
            contentPadding = PaddingValues(start = 8.dp, end = 12.dp),
            // ServerEditor.dc.html: the title has a 4 px left margin (60 dp from the start).
            titleStartPadding = 4.dp,
        ) {
            ShadowTextButton(text = "Save", onClick = onSave, size = ShadowButtonSize.Medium, enabled = !busy)
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .windowInsetsPadding(EditorSideInsets)
                .verticalScroll(scroll),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = EDITOR_MAX_WIDTH)
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
            ) {
                ProfileSection(form, ::errorOf, ::shakeOf, requesters, onFormChange, Modifier.fadeUpIn(0))
                ConnectionSection(form, ::errorOf, ::shakeOf, requesters, onFormChange, Modifier.fadeUpIn(1))
                SignInSection(
                    state = state,
                    errorOf = ::errorOf,
                    shakeOf = ::shakeOf,
                    requesters = requesters,
                    onFormChange = onFormChange,
                    onAuthTypeChange = onAuthTypeChange,
                    onKeySelect = onKeySelect,
                    onAddKey = onAddKey,
                    modifier = Modifier.fadeUpIn(2),
                )
                IdentitySection(form, onFormChange, onOpenActivity, Modifier.fadeUpIn(3))
                AdvancedSection(form, ::errorOf, requesters, onFormChange, Modifier.fadeUpIn(4))
            }
        }
        EditorFooter(
            phase = phase,
            invalid = invalid,
            savedLabel = "${form.name.trim().ifEmpty { "Server" }} is now first in Servers",
            onFix = ::nudge,
            onSave = onSave,
            onOpenServers = onBack,
            modifier = Modifier.fadeUpIn(5),
        )
    }
}

@Composable
private fun ProfileSection(
    form: EditConfigForm,
    errorOf: (ServerField) -> String?,
    shakeOf: (ServerField) -> Int,
    requesters: Map<ServerField, BringIntoViewRequester>,
    onFormChange: ((EditConfigForm) -> EditConfigForm) -> Unit,
    modifier: Modifier,
) {
    Column(modifier) {
        SectionHeader("Profile", topPadding = 8.dp)
        val error = errorOf(ServerField.Name)
        Column(Modifier.bringIntoViewRequester(requesters.getValue(ServerField.Name))) {
            EditorTextField(
                value = form.name,
                onValueChange = { value -> onFormChange { it.copy(name = value) } },
                label = "Name",
                placeholder = "e.g. Home VPS",
                error = error,
                frameModifier = shakeModifier(shakeOf(ServerField.Name)),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Next,
                ),
            )
            if (error != null) FieldMessage(error, FieldTone.Error)
        }
    }
}

@Composable
private fun ConnectionSection(
    form: EditConfigForm,
    errorOf: (ServerField) -> String?,
    shakeOf: (ServerField) -> Int,
    requesters: Map<ServerField, BringIntoViewRequester>,
    onFormChange: ((EditConfigForm) -> EditConfigForm) -> Unit,
    modifier: Modifier,
) {
    Column(modifier) {
        SectionHeader("Connection")
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val hostError = errorOf(ServerField.Host)
            Column(Modifier.bringIntoViewRequester(requesters.getValue(ServerField.Host))) {
                EditorTextField(
                    value = form.host,
                    onValueChange = { value -> onFormChange { it.copy(host = value) } },
                    label = "Host",
                    placeholder = "vps.example.net or 203.0.113.10",
                    textStyle = Shadow.type.monoInput,
                    error = hostError,
                    frameModifier = shakeModifier(shakeOf(ServerField.Host)),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Next,
                    ),
                )
                val host = form.host.trim()
                when {
                    hostError != null -> FieldMessage(hostError, FieldTone.Error)
                    '@' in host -> FieldMessage("Put the username in the Username field.", FieldTone.Warning)
                    HOST_WITH_PORT.matches(host) -> FieldMessage("Put the port in the Port field.", FieldTone.Warning)
                    else -> FieldMessage("Domain or IP address", FieldTone.Helper)
                }
            }
            PortAndUsername(
                form = form,
                errorOf = errorOf,
                shakeOf = shakeOf,
                requesters = requesters,
                onFormChange = onFormChange,
            )
        }
    }
}

/** Port (118 dp, warning icon on error) and Username side by side, their errors stacked below. */
@Composable
private fun PortAndUsername(
    form: EditConfigForm,
    errorOf: (ServerField) -> String?,
    shakeOf: (ServerField) -> Int,
    requesters: Map<ServerField, BringIntoViewRequester>,
    onFormChange: ((EditConfigForm) -> EditConfigForm) -> Unit,
) {
    Column(Modifier.bringIntoViewRequester(requesters.getValue(ServerField.Port))) {
        val portError = errorOf(ServerField.Port)
        val userError = errorOf(ServerField.Username)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            EditorTextField(
                value = form.port,
                onValueChange = { value -> onFormChange { it.copy(port = value) } },
                modifier = Modifier.width(PORT_WIDTH),
                label = "Port",
                placeholder = "22",
                textStyle = Shadow.type.monoInput,
                error = portError,
                frameModifier = shakeModifier(shakeOf(ServerField.Port)),
                contentPadding = PaddingValues(
                    start = FIELD_TEXT_INSET,
                    end = if (portError != null) 0.dp else FIELD_TEXT_INSET,
                ),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Next,
                ),
                trailing = if (portError != null) {
                    {
                        Icon(
                            imageVector = ShadowIcons.Warning,
                            contentDescription = null,
                            tint = Shadow.colors.coralText,
                            // 4 / 12 dp beside the icon, inside the 1 dp border.
                            modifier = Modifier
                                .padding(start = 4.dp, end = 9.dp)
                                .size(18.dp),
                        )
                    }
                } else {
                    null
                },
            )
            EditorTextField(
                value = form.username,
                onValueChange = { value -> onFormChange { it.copy(username = value) } },
                modifier = Modifier
                    .weight(1f)
                    .bringIntoViewRequester(requesters.getValue(ServerField.Username)),
                label = "Username",
                placeholder = "e.g. root",
                textStyle = Shadow.type.monoInput,
                error = userError,
                frameModifier = shakeModifier(shakeOf(ServerField.Username)),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Next,
                ),
            )
        }
        if (portError != null) FieldMessage(portError, FieldTone.Error)
        if (userError != null) FieldMessage(userError, FieldTone.Error)
    }
}

@Composable
private fun SignInSection(
    state: EditConfigUiState,
    errorOf: (ServerField) -> String?,
    shakeOf: (ServerField) -> Int,
    requesters: Map<ServerField, BringIntoViewRequester>,
    onFormChange: ((EditConfigForm) -> EditConfigForm) -> Unit,
    onAuthTypeChange: (AuthType) -> Unit,
    onKeySelect: (String) -> Unit,
    onAddKey: () -> Unit,
    modifier: Modifier,
) {
    val form = state.form
    Column(modifier) {
        SectionHeader("Sign-in")
        ShadowSegmented(
            options = listOf(
                SegmentOption("Password", ShadowIcons.Lock),
                SegmentOption("Key", ShadowIcons.Key),
            ),
            selectedIndex = if (form.authType == AuthType.PASSWORD) 0 else 1,
            onSelect = { index -> onAuthTypeChange(if (index == 0) AuthType.PASSWORD else AuthType.PRIVATE_KEY) },
            role = Role.RadioButton,
        )
        val swapFade = shadowTween<Float>(ShadowMotion.Swap)
        val swapRise = shadowTween<IntOffset>(ShadowMotion.Swap)
        val resize = shadowTween<IntSize>(ShadowMotion.Surface)
        val riseDistance = with(LocalDensity.current) { SWAP_RISE.roundToPx() }
        AnimatedContent(
            targetState = form.authType,
            transitionSpec = {
                (fadeIn(swapFade) + slideInVertically(swapRise) { riseDistance })
                    .togetherWith(fadeOut(snap()))
                    .using(SizeTransform(clip = false) { _, _ -> resize })
            },
            label = "sign-in",
            modifier = Modifier.padding(top = 16.dp),
        ) { authType ->
            when {
                authType == AuthType.PASSWORD -> PasswordBlock(
                    password = form.password,
                    error = errorOf(ServerField.Password),
                    shakeTrigger = shakeOf(ServerField.Password),
                    onChange = { value -> onFormChange { it.copy(password = value) } },
                    modifier = Modifier.bringIntoViewRequester(requesters.getValue(ServerField.Password)),
                )
                state.keys.isEmpty() -> NoKeysBlock(
                    error = errorOf(ServerField.Key),
                    shakeTrigger = shakeOf(ServerField.Key),
                    onAddKey = onAddKey,
                    modifier = Modifier.bringIntoViewRequester(requesters.getValue(ServerField.Key)),
                )
                else -> KeyPicker(
                    keys = state.keys,
                    traits = state.keyTraits,
                    selectedId = form.privateKeyId,
                    error = errorOf(ServerField.Key),
                    shakeTrigger = shakeOf(ServerField.Key),
                    onSelect = onKeySelect,
                    onAddKey = onAddKey,
                    modifier = Modifier.bringIntoViewRequester(requesters.getValue(ServerField.Key)),
                )
            }
        }
    }
}

@Composable
private fun PasswordBlock(
    password: String,
    error: String?,
    shakeTrigger: Int,
    onChange: (String) -> Unit,
    modifier: Modifier,
) {
    // Revealed only while the field is on screen: a rotation or a trip to Key masks it again.
    var visible by remember { mutableStateOf(false) }
    Column(modifier) {
        EditorTextField(
            value = password,
            onValueChange = onChange,
            label = "Password",
            placeholder = "Server account password",
            error = error,
            frameModifier = shakeModifier(shakeTrigger),
            contentPadding = PaddingValues(start = FIELD_TEXT_INSET, end = 8.dp),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
            ),
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            trailing = {
                ShadowIconButton(
                    icon = if (visible) ShadowIcons.EyeOff else ShadowIcons.Eye,
                    contentDescription = if (visible) "Hide password" else "Show password",
                    onClick = { visible = !visible },
                    tint = Shadow.colors.ink2,
                    iconSize = 20.dp,
                )
            },
        )
        if (error != null) {
            FieldMessage(error, FieldTone.Error)
        } else {
            FieldMessage("Kept exactly as typed, spaces included.", FieldTone.Helper)
        }
    }
}

@Composable
private fun AdvancedSection(
    form: EditConfigForm,
    errorOf: (ServerField) -> String?,
    requesters: Map<ServerField, BringIntoViewRequester>,
    onFormChange: ((EditConfigForm) -> EditConfigForm) -> Unit,
    modifier: Modifier,
) {
    Column(modifier) {
        SectionHeader("Advanced")
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            KeepaliveStepper(
                seconds = form.keepAliveIntervalSec.toIntOrNull() ?: DEFAULT_KEEPALIVE,
                error = errorOf(ServerField.Keepalive),
                onChange = { value -> onFormChange { it.copy(keepAliveIntervalSec = value.toString()) } },
                modifier = Modifier.bringIntoViewRequester(requesters.getValue(ServerField.Keepalive)),
            )
            EditorTextField(
                value = form.note,
                onValueChange = { value -> onFormChange { it.copy(note = value) } },
                label = "Note",
                labelTrailing = "Shown on the server card",
                placeholder = "Anything that helps you tell servers apart",
                singleLine = false,
                minHeight = NOTE_MIN_HEIGHT,
                contentPadding = PaddingValues(horizontal = FIELD_TEXT_INSET, vertical = NOTE_TEXT_TOP),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
        }
    }
}

private val HOST_WITH_PORT = Regex("^[^:]+:\\d+$")
private val PORT_WIDTH = 118.dp
/** The note's 94 dp textarea plus its 1 dp border; text 14 dp in from the border. */
private val NOTE_MIN_HEIGHT = 96.dp
private val NOTE_TEXT_TOP = 15.dp
private val SWAP_RISE = 6.dp
private const val DEFAULT_KEEPALIVE = 30
