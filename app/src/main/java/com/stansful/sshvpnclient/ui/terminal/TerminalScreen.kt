package com.stansful.sshvpnclient.ui.terminal

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stansful.sshvpnclient.ui.designsystem.CenteredToasts
import com.stansful.sshvpnclient.ui.designsystem.DISABLED_ALPHA
import com.stansful.sshvpnclient.ui.designsystem.IconTile
import com.stansful.sshvpnclient.ui.designsystem.LocalToaster
import com.stansful.sshvpnclient.ui.designsystem.PrimaryButton
import com.stansful.sshvpnclient.ui.designsystem.ShadowButtonSize
import com.stansful.sshvpnclient.ui.designsystem.ShadowIconButton
import com.stansful.sshvpnclient.ui.designsystem.ShadowIcons
import com.stansful.sshvpnclient.ui.designsystem.ShadowMenu
import com.stansful.sshvpnclient.ui.designsystem.ShadowMenuDivider
import com.stansful.sshvpnclient.ui.designsystem.ShadowMenuItem
import com.stansful.sshvpnclient.ui.designsystem.ShadowSpinner
import com.stansful.sshvpnclient.ui.designsystem.ShadowTextButton
import com.stansful.sshvpnclient.ui.designsystem.StatusPill
import com.stansful.sshvpnclient.ui.designsystem.StatusTone
import com.stansful.sshvpnclient.ui.designsystem.SubScreenBar
import com.stansful.sshvpnclient.ui.designsystem.fadeUpIn
import com.stansful.sshvpnclient.ui.designsystem.shadowClickable
import com.stansful.sshvpnclient.ui.designsystem.toastObstacle
import com.stansful.sshvpnclient.ui.theme.Shadow
import com.stansful.sshvpnclient.ui.theme.ShadowColors
import com.stansful.sshvpnclient.ui.theme.ShadowMotion
import com.stansful.sshvpnclient.ui.theme.ShadowShapes
import com.stansful.sshvpnclient.ui.theme.mixColors
import com.stansful.sshvpnclient.ui.theme.shadowTween
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withContext

/** What the Terminal screen shows. */
internal enum class TerminalPhase {
    /** Connected; the shell channel is being opened (or about to be). */
    Opening,

    /** The shell is open and accepts commands. */
    Active,

    /** The server closed the shell (`exit`, remote close); its output stays until the user leaves. */
    Ended,

    /** Opening the shell failed; nothing retries on its own. */
    Failed,

    /** No Server-mode SSH connection: the shell cannot exist. */
    NotConnected,

    /** Connected, but the Terminal setting is off. */
    Off,
}

/** No shell can exist (no Server connection, or the setting is off). */
private val TerminalPhase.isUnavailable: Boolean
    get() = this == TerminalPhase.NotConnected || this == TerminalPhase.Off

/** The command field takes typing (Run itself unlocks only when the shell is [TerminalPhase.Active]). */
private val TerminalPhase.acceptsTyping: Boolean
    get() = this == TerminalPhase.Active || this == TerminalPhase.Opening

@Immutable
internal data class TerminalScreenState(
    val phase: TerminalPhase,
    val serverName: String? = null,
    val userAtHost: String? = null,
    val output: String = "",
    val outputRevision: Long = 0L,
    /** Number of the first line of [output] in the shell session (the bounded buffer drops old lines). */
    val firstLineNumber: Long = 0L,
    val errorMessage: String? = null,
)

/** [lines] parsed from [source]; line `i` is line `firstLineNumber + i` of the session (its list key). */
private class ParsedOutput(val source: String, val firstLineNumber: Long, val lines: List<TerminalLine>)

/**
 * The output as display lines. The first frame parses in place (no empty flash when the screen opens
 * over existing output); every later update (up to 64 KB, every 250 ms while the shell streams) is
 * parsed off the main thread.
 */
@Composable
private fun rememberParsedOutput(output: String, firstLineNumber: Long): ParsedOutput {
    val initial = remember { ParsedOutput(output, firstLineNumber, parseTerminalOutput(output)) }
    val parsed by produceState(initial, output, firstLineNumber) {
        if (value.source == output && value.firstLineNumber == firstLineNumber) return@produceState
        value = withContext(Dispatchers.Default) {
            ParsedOutput(output, firstLineNumber, parseTerminalOutput(output))
        }
    }
    return parsed
}

/**
 * Stateless Terminal screen (Terminal.dc.html). [command] is the draft in the input bar; [onRun] is
 * only called while the shell is active and the draft is not blank. [onCopyOutput] receives the
 * output as rendered (escape sequences removed).
 */
@Composable
internal fun TerminalScreen(
    state: TerminalScreenState,
    command: String,
    onCommandChange: (String) -> Unit,
    onRun: () -> Unit,
    onBack: () -> Unit,
    onCopyOutput: (String) -> Unit,
    onRestart: () -> Unit,
    onOpenSettings: () -> Unit,
    onViewActivity: () -> Unit,
    onBackToHome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val parsed = rememberParsedOutput(state.output, state.firstLineNumber)
    val lines = parsed.lines
    val focusRequester = remember { FocusRequester() }
    var menuOpen by remember { mutableStateOf(false) }
    val toaster = LocalToaster.current
    // Terminal.dc.html: a centred, content-width toast 14 dp above the input bar.
    CenteredToasts()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Shadow.colors.bg),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TerminalTopBar(
                state = state,
                menuOpen = menuOpen,
                onMenuOpenChange = { menuOpen = it },
                canCopy = lines.isNotEmpty() && !state.phase.isUnavailable,
                onBack = onBack,
                onCopy = {
                    onCopyOutput(lines.plainText())
                    toaster.show("Output copied", icon = ShadowIcons.Check, durationMillis = COPIED_TOAST_MS)
                },
                onRestart = onRestart,
                onOpenSettings = onOpenSettings,
            )
            TerminalWell(
                state = state,
                parsed = parsed,
                focusRequester = focusRequester,
                onRestart = onRestart,
                onViewActivity = onViewActivity,
                onBackToHome = onBackToHome,
                onOpenSettings = onOpenSettings,
                modifier = Modifier.weight(1f),
            )
            TerminalInputBar(
                phase = state.phase,
                command = command,
                onCommandChange = onCommandChange,
                onRun = onRun,
                focusRequester = focusRequester,
            )
        }
        AnimatedVisibility(
            visible = menuOpen,
            enter = fadeIn(shadowTween(ShadowMotion.SheetExit)),
            exit = fadeOut(shadowTween(ShadowMotion.SheetExit)),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Shadow.colors.scrim.copy(alpha = MENU_SCRIM_ALPHA)),
            )
        }
    }
}

@Composable
private fun TerminalTopBar(
    state: TerminalScreenState,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    canCopy: Boolean,
    onBack: () -> Unit,
    onCopy: () -> Unit,
    onRestart: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    SubScreenBar(
        title = "Terminal",
        onBack = onBack,
        subtitle = state.userAtHost,
        status = { TerminalStatusPill(state.phase) },
        showDivider = true,
        contentPadding = PaddingValues(start = 6.dp, end = 8.dp),
        // Terminal.dc.html: 6 + 44 + 4 + 2 = title at 56 dp.
        titleStartPadding = 2.dp,
    ) {
        Box {
            ShadowIconButton(
                icon = ShadowIcons.More,
                contentDescription = "More actions",
                onClick = { onMenuOpenChange(true) },
                selected = menuOpen,
            )
            TerminalMenu(
                expanded = menuOpen,
                canCopy = canCopy,
                canRestart = !state.phase.isUnavailable && state.phase != TerminalPhase.Opening,
                onDismiss = { onMenuOpenChange(false) },
                onCopy = {
                    onMenuOpenChange(false)
                    onCopy()
                },
                onRestart = {
                    onMenuOpenChange(false)
                    onRestart()
                },
                onOpenSettings = {
                    onMenuOpenChange(false)
                    onOpenSettings()
                },
            )
        }
    }
}

/** The darker output area between the bar and the input: output, opening/end states, or "unavailable". */
@Composable
private fun TerminalWell(
    state: TerminalScreenState,
    parsed: ParsedOutput,
    focusRequester: FocusRequester,
    onRestart: () -> Unit,
    onViewActivity: () -> Unit,
    onBackToHome: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val well = terminalWell(Shadow.colors)
    val keyboard = LocalSoftwareKeyboardController.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(well)
            .windowInsetsPadding(HorizontalSafeInsets),
    ) {
        if (state.phase.isUnavailable) {
            TerminalUnavailable(
                off = state.phase == TerminalPhase.Off,
                well = well,
                onBackToHome = onBackToHome,
                onOpenSettings = onOpenSettings,
            )
        } else {
            TerminalOutputList(
                state = state,
                parsed = parsed,
                onRetry = onRestart,
                onViewActivity = onViewActivity,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = state.phase.acceptsTyping,
                    onClickLabel = "Type a command",
                ) {
                    focusRequester.requestFocus()
                    keyboard?.show()
                },
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .background(Brush.verticalGradient(listOf(well, well.copy(alpha = 0f)))),
            )
        }
        if (state.phase == TerminalPhase.Opening) {
            OpeningProgressLine(Modifier.align(Alignment.TopCenter))
        }
    }
}

/** Side cutouts and system bars in landscape (the top bar and the input bar pad the rest). */
private val HorizontalSafeInsets: WindowInsets
    @Composable get() = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)

/** The darker well behind the shell output (Night #07080A, derived from the tokens). */
private fun terminalWell(colors: ShadowColors): Color =
    if (colors.isDark) mixColors(colors.scrim.copy(alpha = 1f), colors.bg, WELL_SHADE) else colors.surface2

/** Skeleton bars of the opening state: one step above the well. */
private fun skeletonColor(colors: ShadowColors): Color = if (colors.isDark) colors.surface2 else colors.surface3

/** The server's SGR colours, drawn with the theme's text tokens. */
private fun ShadowColors.toneColor(tone: AnsiTone): Color = when (tone) {
    AnsiTone.Gray -> ink3
    AnsiTone.Red -> coralText
    AnsiTone.Green -> mintText
    AnsiTone.Yellow -> amberText
    AnsiTone.Blue, AnsiTone.Cyan, AnsiTone.Magenta -> skyText
    AnsiTone.White -> ink1
}

/**
 * Words the ViewModel's terminal errors the way the design does ("Shell closed by the server",
 * "Couldn’t open a shell: …"); any other text (an exception message) is shown as it is.
 */
internal fun terminalErrorText(raw: String): String = when {
    raw == REMOTE_CLOSED -> "Shell closed by the server"
    raw.startsWith(OPEN_FAILED_PREFIX) -> "Couldn’t open a shell: " + raw.removePrefix(OPEN_FAILED_PREFIX)
    raw == SESSION_NOT_CONNECTED -> "Couldn’t open a shell: the SSH session is not connected"
    raw == OPEN_FAILED -> "Couldn’t open a shell"
    raw == NOT_CONNECTED -> "No shell is open"
    raw == WRITE_CLOSED -> "The shell is closed, so the command wasn’t sent"
    raw == WRITE_FAILED -> "Couldn’t send the command"
    else -> raw
}

@Composable
private fun TerminalStatusPill(phase: TerminalPhase) {
    val (label, tone) = when (phase) {
        TerminalPhase.Opening -> "Opening shell…" to StatusTone.Progress
        TerminalPhase.Active -> "Shell active" to StatusTone.Success
        TerminalPhase.Ended -> "Shell ended" to StatusTone.Neutral
        TerminalPhase.Failed -> "Shell failed" to StatusTone.Error
        TerminalPhase.NotConnected -> "Not connected" to StatusTone.Neutral
        TerminalPhase.Off -> "Terminal off" to StatusTone.Neutral
    }
    StatusPill(
        label = label,
        tone = tone,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun TerminalMenu(
    expanded: Boolean,
    canCopy: Boolean,
    canRestart: Boolean,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onRestart: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    ShadowMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.width(MENU_WIDTH),
    ) {
        ShadowMenuItem(text = "Copy output", onClick = onCopy, icon = ShadowIcons.Copy, enabled = canCopy)
        ShadowMenuItem(
            text = "Restart shell",
            onClick = onRestart,
            icon = ShadowIcons.Refresh,
            subtitle = "Opens a fresh shell and clears the output",
            enabled = canRestart,
        )
        ShadowMenuItem(
            text = "Terminal settings",
            onClick = onOpenSettings,
            icon = ShadowIcons.Settings,
            trailingIcon = ShadowIcons.ChevronRight,
        )
        ShadowMenuDivider()
        AboutTerminal()
    }
}

@Composable
private fun AboutTerminal() {
    val colors = Shadow.colors
    val mono = SpanStyle(fontFamily = Shadow.type.monoS.fontFamily, color = colors.ink2)
    val notes = listOf(
        AnnotatedString("Line mode: no Tab, arrow or Ctrl keys, and no command history"),
        AnnotatedString("Full-screen apps like vim or top won’t work"),
        buildAnnotatedString {
            append("Keeps the last ")
            withStyle(mono) { append("65,536") }
            append(" characters of output")
        },
        buildAnnotatedString {
            append("Type ")
            withStyle(mono) { append("exit") }
            append(" to end the shell")
        },
    )
    Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 10.dp)) {
        Text(text = "ABOUT THIS TERMINAL", style = Shadow.type.overline, color = colors.ink3)
        Column(
            modifier = Modifier.padding(top = 10.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            notes.forEach { note ->
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Box(
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .size(4.dp)
                            .clip(CircleShape)
                            .background(colors.ink3),
                    )
                    Text(text = note, style = Shadow.type.caption, color = colors.ink3)
                }
            }
        }
    }
}

@Composable
private fun TerminalOutputList(
    state: TerminalScreenState,
    parsed: ParsedOutput,
    onRetry: () -> Unit,
    onViewActivity: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val phase = state.phase
    val lines = parsed.lines
    val showCursor = phase == TerminalPhase.Active
    val shown = if (!showCursor && lines.lastOrNull()?.plain?.isEmpty() == true) lines.dropLast(1) else lines
    val cursorOnOwnLine = showCursor && shown.isEmpty()
    val showEndCard = phase == TerminalPhase.Ended || phase == TerminalPhase.Failed
    val itemCount = 1 + shown.size +
        (if (cursorOnOwnLine) 1 else 0) +
        (if (state.errorMessage != null) 1 else 0) +
        (if (phase == TerminalPhase.Opening || showEndCard) 1 else 0)
    val listState = rememberLazyListState()
    // Keyed on the parse (not the revision): it lands a frame later, off the main thread.
    StickToBottom(listState, parsed, phase, state.errorMessage, itemCount)
    val textStyle = terminalTextStyle()
    val reducedMotion = Shadow.reducedMotion
    SelectionContainer(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 20.dp),
        ) {
            item(key = "channel") {
                PrivateChannelChip(
                    serverName = state.serverName,
                    modifier = Modifier
                        .padding(bottom = 16.dp)
                        .fadeUpIn(0),
                )
            }
            // Session line numbers: when the bounded buffer drops old lines, the kept ones keep their
            // keys (and remembered text) instead of every row's content shifting by index.
            itemsIndexed(shown, key = { index, _ -> parsed.firstLineNumber + index }) { index, line ->
                TerminalLineText(
                    line = line,
                    cursor = showCursor && index == shown.lastIndex,
                    style = textStyle,
                    modifier = Modifier
                        .animateItem(
                            fadeInSpec = if (reducedMotion) null else shadowTween(ShadowMotion.Swap),
                            placementSpec = null,
                            fadeOutSpec = null,
                        )
                        .padding(top = if (line.isPrompt && index > 0) COMMAND_GAP else 0.dp),
                )
            }
            if (cursorOnOwnLine) {
                item(key = "cursor") {
                    TerminalLineText(line = TerminalLine("", ""), cursor = true, style = textStyle)
                }
            }
            state.errorMessage?.let { message ->
                item(key = "error") {
                    Text(text = terminalErrorText(message), style = textStyle, color = Shadow.colors.coralText)
                }
            }
            if (phase == TerminalPhase.Opening) {
                item(key = "opening") { OpeningBlock(serverName = state.serverName) }
            }
            if (showEndCard) {
                item(key = "end") {
                    ShellEndCard(
                        failed = phase == TerminalPhase.Failed,
                        onRetry = onRetry,
                        onViewActivity = onViewActivity,
                        modifier = Modifier
                            .padding(top = 18.dp)
                            .fadeUpIn(3),
                    )
                }
            }
        }
    }
}

/**
 * Scrolls to the newest output whenever any of [keys] changes (as the old panel did on every update),
 * and keeps the prompt in view when the keyboard (or a window resize) shrinks the well.
 */
@Composable
private fun StickToBottom(listState: LazyListState, vararg keys: Any?) {
    LaunchedEffect(*keys) { listState.scrollToEnd() }
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.viewportSize.height }
            .distinctUntilChanged()
            .drop(1)
            .collect { listState.scrollToEnd() }
    }
}

/** Jumps past the last item, so the bottom padding below the newest line shows too. */
private suspend fun LazyListState.scrollToEnd() {
    val last = layoutInfo.totalItemsCount - 1
    if (last >= 0) scrollToItem(last)
    scrollBy(SCROLL_TO_END_PX)
}

@Composable
private fun terminalTextStyle(): TextStyle = Shadow.type.mono.copy(
    fontSize = 12.5.sp,
    lineHeight = 19.sp,
    textIndent = TextIndent(firstLine = 0.sp, restLine = HANGING_INDENT),
    fontFeatureSettings = "liga 0, calt 0",
)

@Composable
private fun TerminalLineText(
    line: TerminalLine,
    cursor: Boolean,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val colors = Shadow.colors
    val text = remember(line, cursor, colors) {
        buildAnnotatedString {
            append(line.plain)
            line.spans.forEach { span ->
                addStyle(
                    SpanStyle(
                        color = span.tone?.let { colors.toneColor(it) } ?: Color.Unspecified,
                        fontWeight = if (span.bold) FontWeight.Medium else null,
                    ),
                    span.start,
                    span.end,
                )
            }
            if (line.isPrompt) {
                val end = line.prompt.length
                addStyle(SpanStyle(color = colors.amberText, fontWeight = FontWeight.Medium), 0, end)
                addStyle(SpanStyle(color = colors.ink1, fontWeight = FontWeight.Medium), end, line.plain.length)
            }
            if (cursor) appendInlineContent(CURSOR_ID, " ")
        }
    }
    Text(
        text = text,
        style = style,
        color = colors.ink2,
        inlineContent = if (cursor) mapOf(CURSOR_ID to cursorContent()) else emptyMap(),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun cursorContent(): InlineTextContent = InlineTextContent(
    placeholder = Placeholder(
        width = 7.5.sp,
        height = 15.sp,
        placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
    ),
) {
    val blink = blinkAlpha(delayMillis = 0)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = blink.value }
            .clip(RoundedCornerShape(1.dp))
            .background(Shadow.colors.ink1),
    )
}

/**
 * Opacity 1 ↔ 0.35 over 1.2 s (static under reduced motion). Read it in a layer block, so the blink
 * redraws the layer instead of recomposing every frame.
 */
@Composable
private fun blinkAlpha(delayMillis: Int): State<Float> {
    if (Shadow.reducedMotion) return SteadyAlpha
    val transition = rememberInfiniteTransition(label = "terminal-blink")
    return transition.animateFloat(
        initialValue = 1f,
        targetValue = BLINK_MIN_ALPHA,
        animationSpec = infiniteRepeatable(
            animation = tween(ShadowMotion.Blink / 2, easing = ShadowMotion.EaseInOut),
            repeatMode = RepeatMode.Reverse,
            initialStartOffset = StartOffset(delayMillis),
        ),
        label = "terminal-blink-alpha",
    )
}

/** Full opacity, never changing (reduced motion). */
private object SteadyAlpha : State<Float> {
    override val value: Float get() = 1f
}

@Composable
private fun PrivateChannelChip(serverName: String?, modifier: Modifier = Modifier) {
    val colors = Shadow.colors
    Row(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .height(28.dp)
                .clip(ShadowShapes.Pill)
                .background(colors.surface1)
                .border(1.dp, colors.line, ShadowShapes.Pill)
                .padding(start = 11.dp, end = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(ShadowIcons.Lock, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(14.dp))
            Text(
                text = "Private channel · inside your ${serverName ?: "SSH"} tunnel",
                style = Shadow.type.caption.copy(fontWeight = FontWeight.Medium),
                color = colors.ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun OpeningBlock(serverName: String?) {
    val colors = Shadow.colors
    Column(modifier = Modifier.fadeUpIn(1)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ShadowSpinner(color = colors.amber, size = 16.dp)
            Text(
                text = if (serverName != null) "Opening a shell on $serverName…" else "Opening a shell…",
                style = Shadow.type.bodyS,
                color = colors.ink2,
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp)
                .clearAndSetSemantics { },
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SKELETON.forEachIndexed { index, bar ->
                val blink = blinkAlpha(delayMillis = index * SKELETON_STAGGER_MS)
                Box(
                    modifier = Modifier
                        .padding(top = if (bar.gapAbove) 12.dp else 0.dp)
                        .fillMaxWidth(bar.fraction)
                        .height(9.dp)
                        .graphicsLayer { alpha = blink.value }
                        .clip(RoundedCornerShape(5.dp))
                        .background(skeletonColor(colors)),
                )
            }
        }
        Text(
            text = buildAnnotatedString {
                append("Usually takes a moment and stops waiting after ")
                withStyle(SpanStyle(fontFamily = Shadow.type.monoS.fontFamily)) { append("10 s") }
                append(". You can start typing now; Run unlocks when the shell is ready.")
            },
            style = Shadow.type.caption,
            color = colors.ink3,
            modifier = Modifier.padding(top = 22.dp),
        )
    }
}

@Composable
private fun ShellEndCard(
    failed: Boolean,
    onRetry: () -> Unit,
    onViewActivity: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Shadow.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(ShadowShapes.Card)
            .background(colors.surface1)
            .border(1.dp, colors.line, ShadowShapes.Card)
            .padding(CARD_PADDING),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(
                icon = if (failed) ShadowIcons.Warning else ShadowIcons.Server,
                size = 36.dp,
                iconSize = 18.dp,
                cornerRadius = 12.dp,
                tint = if (failed) colors.coralText else colors.ink2,
                container = if (failed) colors.coralTint else colors.surface2,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (failed) "The shell didn’t start" else "Shell ended",
                    style = Shadow.type.rowTitle.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.ink1,
                )
                Text(
                    text = if (failed) {
                        "Your tunnel is still connected and protecting traffic. Nothing retries on its own, " +
                            "so try again when you’re ready."
                    } else {
                        "The server closed this shell. Your tunnel is still connected, and the output stays " +
                            "here until you leave."
                    },
                    style = Shadow.type.bodyS,
                    color = colors.ink3,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        FlowRow(
            modifier = Modifier.padding(start = 48.dp, top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PrimaryButton(
                text = if (failed) "Try again" else "Open new shell",
                onClick = onRetry,
                icon = ShadowIcons.Refresh,
                size = ShadowButtonSize.Compact,
            )
            ShadowTextButton(text = "View activity", onClick = onViewActivity)
        }
    }
}

@Composable
private fun OpeningProgressLine(modifier: Modifier = Modifier) {
    val colors = Shadow.colors
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(colors.amberTint)
            .clearAndSetSemantics { },
    ) {
        if (Shadow.reducedMotion) {
            Box(
                Modifier
                    .fillMaxSize()
                    .alpha(REDUCED_PROGRESS_ALPHA)
                    .background(colors.amber),
            )
        } else {
            val segment = maxWidth * PROGRESS_SEGMENT
            val transition = rememberInfiniteTransition(label = "terminal-progress")
            val shift by transition.animateFloat(
                initialValue = -1f,
                targetValue = PROGRESS_TRAVEL,
                animationSpec = infiniteRepeatable(
                    animation = tween(ShadowMotion.Spinner, easing = PROGRESS_EASING),
                ),
                label = "terminal-progress-shift",
            )
            Box(
                modifier = Modifier
                    .width(segment)
                    .fillMaxHeight()
                    .graphicsLayer { translationX = shift * segment.toPx() }
                    .clip(CircleShape)
                    .background(colors.amber),
            )
        }
    }
}

@Composable
private fun TerminalUnavailable(
    off: Boolean,
    well: Color,
    onBackToHome: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val colors = Shadow.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        UnavailableTile(
            off = off,
            well = well,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .fadeUpIn(0),
        )
        UnavailableBanner(off = off, modifier = Modifier.padding(top = 24.dp).fadeUpIn(2))
        Text(
            text = if (off) {
                "Terminal opens a shell on the connected SSH server. It is off in Settings."
            } else {
                "The shell runs inside your Server-mode SSH tunnel, so it opens only while that connection is up."
            },
            style = Shadow.type.bodyS,
            color = colors.ink3,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(start = 8.dp, end = 8.dp, top = 14.dp)
                .fadeUpIn(4)
                .fillMaxWidth(),
        )
        PrimaryButton(
            text = if (off) "Open Settings" else "Back to Home",
            onClick = if (off) onOpenSettings else onBackToHome,
            icon = if (off) ShadowIcons.Settings else ShadowIcons.Home,
            modifier = Modifier
                .padding(top = 24.dp)
                .fadeUpIn(6)
                .fillMaxWidth(),
        )
    }
}

/** 56 dp terminal tile with the state badge (coral: not connected, amber: off) cut out of the well. */
@Composable
private fun UnavailableTile(off: Boolean, well: Color, modifier: Modifier = Modifier) {
    val colors = Shadow.colors
    Box(modifier = modifier) {
        IconTile(
            icon = ShadowIcons.Server,
            size = 56.dp,
            iconSize = 26.dp,
            cornerRadius = 16.dp,
            tint = colors.ink3,
            container = colors.surface1,
            bordered = true,
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = BADGE_OVERHANG, y = -BADGE_OVERHANG)
                .size(18.dp)
                .clip(CircleShape)
                .background(well)
                .padding(3.dp)
                .clip(CircleShape)
                .background(if (off) colors.amber else colors.coral),
        )
    }
}

@Composable
private fun UnavailableBanner(off: Boolean, modifier: Modifier = Modifier) {
    val colors = Shadow.colors
    val tone = if (off) colors.amberText else colors.coralText
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(ShadowShapes.Banner)
            .background(if (off) colors.amberTint else colors.coralTint)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = ShadowIcons.Warning,
            contentDescription = null,
            tint = tone,
            modifier = Modifier
                .offset(y = (-1).dp)
                .size(20.dp),
        )
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = tone, fontWeight = FontWeight.SemiBold)) {
                    append(if (off) "Terminal is off" else "Terminal unavailable")
                }
                append(if (off) " — turn it on in Settings" else " — connect to a server first")
            },
            style = Shadow.type.bodyS,
            color = colors.ink1,
        )
    }
}

@Composable
private fun TerminalInputBar(
    phase: TerminalPhase,
    command: String,
    onCommandChange: (String) -> Unit,
    onRun: () -> Unit,
    focusRequester: FocusRequester,
) {
    val colors = Shadow.colors
    val divider = colors.line
    Column(
        modifier = Modifier
            // The "Output copied" toast sits 14 dp above the bar (and rises with it over the keyboard).
            .toastObstacle(gap = 14.dp)
            .fillMaxWidth()
            .background(colors.bg)
            .drawBehind { drawRect(divider, size = Size(size.width, 1.dp.toPx())) }
            .navigationBarsPadding()
            .windowInsetsPadding(HorizontalSafeInsets)
            .imePadding()
            // 1 dp more on top for the hairline, which the artboard's CSS border adds outside the padding.
            .padding(start = 16.dp, end = 16.dp, top = 13.dp, bottom = 16.dp),
    ) {
        if (!phase.isUnavailable) {
            Row(
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                    text = "Leaving this screen or the app closes the shell and clears its output. " +
                        "Commands are never logged.",
                    style = Shadow.type.caption,
                    color = colors.ink3,
                )
            }
        }
        CommandField(
            phase = phase,
            command = command,
            onCommandChange = onCommandChange,
            onRun = onRun,
            focusRequester = focusRequester,
        )
    }
}

@Composable
private fun CommandField(
    phase: TerminalPhase,
    command: String,
    onCommandChange: (String) -> Unit,
    onRun: () -> Unit,
    focusRequester: FocusRequester,
) {
    val colors = Shadow.colors
    val typing = phase.acceptsTyping
    val canRun = phase == TerminalPhase.Active && command.isNotBlank()
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val border by animateColorAsState(
        targetValue = if (focused && typing) colors.amber.copy(alpha = FOCUS_BORDER_ALPHA) else colors.line,
        animationSpec = shadowTween(ShadowMotion.Small, ShadowMotion.Ease),
        label = "command-border",
    )
    val dollar by animateColorAsState(
        targetValue = if (typing) colors.amberText else colors.ink3,
        animationSpec = shadowTween(ShadowMotion.Small, ShadowMotion.Ease),
        label = "command-dollar",
    )
    val inputStyle = Shadow.type.mono.copy(fontSize = 15.sp, lineHeight = 20.sp, color = colors.ink1)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(fieldAlpha(phase))
            .height(52.dp)
            .clip(ShadowShapes.Input)
            .background(colors.surface2)
            .border(1.dp, border, ShadowShapes.Input)
            // The artboard's 16 / 5 dp padding sits inside its 1 dp border.
            .padding(start = 17.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$",
            style = inputStyle.copy(fontWeight = FontWeight.Medium),
            color = dollar,
            modifier = Modifier.clearAndSetSemantics { },
        )
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = command,
            onValueChange = onCommandChange,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .semantics { contentDescription = "Command" },
            enabled = typing,
            textStyle = inputStyle,
            singleLine = true,
            cursorBrush = SolidColor(colors.amber),
            interactionSource = interactionSource,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Send,
            ),
            keyboardActions = KeyboardActions(onSend = { if (canRun) onRun() }),
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (command.isEmpty()) {
                        Text(
                            text = placeholder(phase),
                            style = Shadow.type.body,
                            color = colors.ink3,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
            },
        )
        Spacer(Modifier.width(6.dp))
        RunButton(enabled = canRun, onClick = onRun)
    }
}

private fun fieldAlpha(phase: TerminalPhase): Float = when (phase) {
    TerminalPhase.NotConnected, TerminalPhase.Off -> DISABLED_ALPHA
    TerminalPhase.Ended, TerminalPhase.Failed -> ENDED_FIELD_ALPHA
    TerminalPhase.Opening, TerminalPhase.Active -> 1f
}

private fun placeholder(phase: TerminalPhase): String = when (phase) {
    TerminalPhase.Opening, TerminalPhase.Active -> "Type a command"
    TerminalPhase.Ended -> "Shell ended"
    TerminalPhase.Failed -> "No shell open"
    TerminalPhase.NotConnected -> "Not connected"
    TerminalPhase.Off -> "Terminal is off"
}

@Composable
private fun RunButton(enabled: Boolean, onClick: () -> Unit) {
    val colors = Shadow.colors
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(44.dp)
            .shadowClickable(
                interactionSource = interactionSource,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { contentDescription = "Run command" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .alpha(if (enabled) 1f else DISABLED_ALPHA)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.amber),
            contentAlignment = Alignment.Center,
        ) {
            Icon(ShadowIcons.Send, contentDescription = null, tint = colors.onAmber, modifier = Modifier.size(20.dp))
        }
    }
}

private class SkeletonBar(val fraction: Float, val gapAbove: Boolean = false)

private val SKELETON = listOf(
    SkeletonBar(0.78f),
    SkeletonBar(0.56f),
    SkeletonBar(0.18f, gapAbove = true),
    SkeletonBar(0.92f),
    SkeletonBar(0.64f),
    SkeletonBar(0.26f, gapAbove = true),
    SkeletonBar(0.44f),
)

/** Terminal errors the ViewModel / SSH layer report (see [terminalErrorText]). */
private const val REMOTE_CLOSED = "remote shell closed"
private const val OPEN_FAILED_PREFIX = "SSH terminal failed: "
private const val SESSION_NOT_CONNECTED = "Terminal unavailable: SSH session is not connected"
private const val OPEN_FAILED = "Terminal connection failed"
private const val NOT_CONNECTED = "Terminal is not connected"
private const val WRITE_CLOSED = "Terminal is closed"
private const val WRITE_FAILED = "Terminal command failed"

private const val CURSOR_ID = "cursor"
private const val COPIED_TOAST_MS = 1_800L
private const val WELL_SHADE = 0.7f
private const val MENU_SCRIM_ALPHA = 0.28f
private const val BLINK_MIN_ALPHA = 0.35f
private const val SKELETON_STAGGER_MS = 120
private const val FOCUS_BORDER_ALPHA = 0.55f
private const val ENDED_FIELD_ALPHA = 0.55f
private const val REDUCED_PROGRESS_ALPHA = 0.45f
private const val PROGRESS_SEGMENT = 0.3f
private const val PROGRESS_TRAVEL = 3.4f
private const val SCROLL_TO_END_PX = 100_000f
private val PROGRESS_EASING = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
private val MENU_WIDTH = 280.dp
private val COMMAND_GAP = 14.dp
private val HANGING_INDENT = 15.sp
private val BADGE_OVERHANG = 3.dp

/** The artboard's 16 dp card padding plus its 1 dp border (CSS draws the border outside the padding). */
private val CARD_PADDING = 17.dp
