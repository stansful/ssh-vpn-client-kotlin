package com.stansful.sshvpnclient.ui.routes

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import com.stansful.sshvpnclient.domain.model.ProxyProfileSummary
import com.stansful.sshvpnclient.domain.usecase.proxy.ProxyShareLinkParser
import com.stansful.sshvpnclient.ui.designsystem.DISABLED_ALPHA
import com.stansful.sshvpnclient.ui.designsystem.IconTile
import com.stansful.sshvpnclient.ui.designsystem.PrimaryButton
import com.stansful.sshvpnclient.ui.designsystem.SegmentOption
import com.stansful.sshvpnclient.ui.designsystem.ShadowBottomSheet
import com.stansful.sshvpnclient.ui.designsystem.ShadowButton
import com.stansful.sshvpnclient.ui.designsystem.ShadowButtonLabel
import com.stansful.sshvpnclient.ui.designsystem.ShadowButtonSize
import com.stansful.sshvpnclient.ui.designsystem.ShadowButtonVariant
import com.stansful.sshvpnclient.ui.designsystem.ShadowDialogContainer
import com.stansful.sshvpnclient.ui.designsystem.ShadowIconButton
import com.stansful.sshvpnclient.ui.designsystem.ShadowIconButtonStyle
import com.stansful.sshvpnclient.ui.designsystem.ShadowIcons
import com.stansful.sshvpnclient.ui.designsystem.ShadowSegmented
import com.stansful.sshvpnclient.ui.designsystem.SwapText
import com.stansful.sshvpnclient.ui.designsystem.fadeUpIn
import com.stansful.sshvpnclient.ui.designsystem.shadowClickable
import com.stansful.sshvpnclient.ui.opensource.OpenSourceUiState
import com.stansful.sshvpnclient.ui.opensource.ProxyEditorState
import com.stansful.sshvpnclient.ui.theme.Shadow
import com.stansful.sshvpnclient.ui.theme.ShadowMotion
import com.stansful.sshvpnclient.ui.theme.ShadowShapes
import com.stansful.sshvpnclient.ui.theme.shadowTween
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class AddTab { Paste, Clipboard }

private enum class HelperTone { Muted, Info, Warn, Error }

private enum class ClipMode { List, Edit, Empty, File }

/**
 * The Add routes / Edit route sheet (RouteImport.dc.html) while the ViewModel's editor or batch
 * import is open; on Expanded width editing uses the TabletRoutes edit dialog instead.
 */
@Composable
internal fun AddRoutesHost(
    state: RoutesScreenState,
    actions: RoutesActions,
    controller: RoutesUiController,
    linkParser: ProxyShareLinkParser,
    tablet: Boolean,
) {
    val routes = state.routes
    val editor = routes.editor
    val open = editor != null || routes.showBulkImport
    if (tablet && editor?.profileId != null) {
        EditRouteDialog(routes = routes, editor = editor, actions = actions, linkParser = linkParser)
        return
    }
    // After a save the ViewModel closes the editor at once: keep showing what the sheet last showed
    // while it slides away, then leave the composition.
    val lastOpen = remember { arrayOfNulls<RoutesScreenState>(1) }
    if (open) lastOpen[0] = state
    var dismissed by remember { mutableStateOf(true) }
    LaunchedEffect(open) { if (open) dismissed = false }
    val shown = when {
        open -> state
        !dismissed -> lastOpen[0]
        else -> null
    } ?: return
    AddRoutesSheet(
        state = shown,
        actions = actions,
        controller = controller,
        linkParser = linkParser,
        closing = !open,
        onClosed = { dismissed = true },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddRoutesSheet(
    state: RoutesScreenState,
    actions: RoutesActions,
    controller: RoutesUiController,
    linkParser: ProxyShareLinkParser,
    closing: Boolean,
    onClosed: () -> Unit,
) {
    val routes = state.routes
    val editor = routes.editor
    val editingId = editor?.profileId
    val editing = editingId != null
    val editedRoute = routes.profileWithId(editingId)
    var tab by rememberSaveable {
        mutableStateOf(if (routes.showBulkImport && editor == null) AddTab.Clipboard else AddTab.Paste)
    }
    LaunchedEffect(routes.showBulkImport) {
        if (routes.showBulkImport && !editing) tab = AddTab.Clipboard
    }
    val known = remember(routes.library, editingId) { routes.library.namesByFingerprint(editingId) }
    val linkText = editor?.rawUri.orEmpty()
    val analysis = remember(linkText, known, editing) { linkParser.analyzeLink(linkText, known, editing) }

    // Only a short batch survives recreation; a long one (up to 10 000 links) would overflow the
    // saved-state Bundle, so it is dropped and the clipboard is read again.
    var clipText by rememberSaveable(stateSaver = ClipTextSaver) { mutableStateOf<String?>(null) }
    var clipSource by rememberSaveable { mutableStateOf(SOURCE_CLIPBOARD) }
    var clipEditing by rememberSaveable { mutableStateOf(false) }
    var clipFilter by rememberSaveable { mutableStateOf<BatchCategory?>(null) }
    val readClipboardBatch: suspend () -> Unit = {
        clipText = actions.readClipboard().orEmpty()
        clipSource = SOURCE_CLIPBOARD
        clipEditing = false
        clipFilter = null
    }
    LaunchedEffect(tab) {
        if (tab == AddTab.Clipboard && clipText == null) readClipboardBatch()
    }
    val batch = rememberBatchAnalysis(linkParser, clipText.orEmpty(), known)
    val trimmedClip = clipText.orEmpty().trim()
    val clipMode = when {
        clipEditing -> ClipMode.Edit
        trimmedClip.isEmpty() -> ClipMode.Empty
        trimmedClip.startsWith("content://", ignoreCase = true) -> ClipMode.File
        else -> ClipMode.List
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val hideThen: (() -> Unit) -> Unit = { next ->
        scope.launch { sheetState.hide() }.invokeOnCompletion { next() }
    }
    LaunchedEffect(closing) {
        if (closing) {
            sheetState.hide()
            onClosed()
        }
    }
    ShadowBottomSheet(
        onDismissRequest = actions.onDismissAddSheet,
        sheetState = sheetState,
        title = if (editing) "Edit route" else "Add routes",
        subtitle = when {
            !editing -> "Paste one link or import a copied batch."
            editedRoute == null -> null
            editedRoute.isManual -> "${editedRoute.name} · your own route"
            else -> "${editedRoute.name} · from the public list"
        },
    ) {
        Column(modifier = Modifier.height(addSheetContentHeight())) {
            if (!editing) {
                ShadowSegmented(
                    options = listOf(
                        SegmentOption("Paste link", ShadowIcons.Link),
                        SegmentOption("From clipboard", ShadowIcons.Clipboard),
                    ),
                    selectedIndex = tab.ordinal,
                    onSelect = { index ->
                        tab = AddTab.entries[index]
                        // Opened as "Import from clipboard": the Paste tab needs the editor.
                        if (tab == AddTab.Paste && editor == null) actions.onAddRoutes()
                    },
                    modifier = Modifier
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 14.dp),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
            ) {
                if (tab == AddTab.Paste || editing) {
                    PastePanel(
                        state = state,
                        editor = editor,
                        editedRoute = editedRoute,
                        analysis = analysis,
                        actions = actions,
                        onReview = {
                            clipText = linkText
                            clipSource = SOURCE_PASTED
                            clipEditing = false
                            clipFilter = null
                            tab = AddTab.Clipboard
                        },
                        onShowDuplicate = { name ->
                            hideThen {
                                actions.onDismissAddSheet()
                                controller.searchOpen = true
                                actions.onQueryChange(name)
                            }
                        },
                    )
                } else {
                    ClipboardPanel(
                        batch = batch,
                        mode = clipMode,
                        text = clipText.orEmpty(),
                        source = clipSource,
                        filter = clipFilter,
                        onFilterChange = { clipFilter = it },
                        onReadAgain = { scope.launch { readClipboardBatch() } },
                        onToggleEdit = { clipEditing = !clipEditing },
                        onTypeLinks = {
                            if (clipMode == ClipMode.File) clipText = ""
                            clipSource = SOURCE_TYPED
                            clipEditing = true
                        },
                        onTextChange = {
                            clipText = it
                            clipSource = SOURCE_EDITED
                        },
                    )
                }
            }
            AddSheetFooter {
                when {
                    editing -> PrimaryButton(
                        text = "Save changes",
                        onClick = actions.onEditorSave,
                        icon = BoldCheck,
                        enabled = analysis.acceptable && !editor.loading,
                        loading = editor.saving,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    tab == AddTab.Paste -> PrimaryButton(
                        text = if (analysis.acceptable && analysis.route?.unknownTransport == true) {
                            "Add anyway"
                        } else {
                            "Add route"
                        },
                        onClick = actions.onEditorSave,
                        icon = ShadowIcons.Plus,
                        enabled = analysis.acceptable,
                        loading = editor?.saving == true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    else -> {
                        val newCount = batch.newCount
                        PrimaryButton(
                            text = when {
                                newCount > 0 -> "Import $newCount ${if (newCount == 1) "route" else "routes"}"
                                batch.rows.isNotEmpty() && clipMode != ClipMode.File -> "Nothing new to import"
                                else -> "Import routes"
                            },
                            onClick = { hideThen { actions.onImportText(clipText.orEmpty()) } },
                            icon = ShadowIcons.Download,
                            enabled = newCount > 0 && (clipMode == ClipMode.List || clipMode == ClipMode.Edit),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

/**
 * RouteImport.dc.html's sheet is 776 of 844 dp: the window minus 68 dp, minus the sheet's handle,
 * header and padding — and minus the keyboard while it is open, so the header and the link field stay
 * on screen while typing.
 */
@Composable
private fun addSheetContentHeight(): Dp {
    val density = LocalDensity.current
    val window = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
    val navigationBar = WindowInsets.navigationBars.asPaddingValues(density).calculateBottomPadding()
    val keyboard = WindowInsets.ime.asPaddingValues(density).calculateBottomPadding()
    return (window - SHEET_TOP_GAP - SHEET_CHROME - max(navigationBar, keyboard)).coerceAtLeast(MIN_SHEET_CONTENT)
}

/**
 * The sticky footer: a hairline, the primary button and the encryption note, flush with the sheet's
 * bottom edge (it reaches into the 22 dp the sheet pads below its content, as the artboard's footer
 * ends the sheet with its own 16 dp).
 */
@Composable
private fun AddSheetFooter(primary: @Composable () -> Unit) {
    val colors = Shadow.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val overlap = SHEET_BOTTOM_PADDING.roundToPx().coerceAtMost(placeable.height)
                layout(placeable.width, placeable.height - overlap) { placeable.place(0, 0) }
            }
            .drawBehind { drawLine(colors.line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
            .background(colors.sheet)
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp),
    ) {
        primary()
        Row(
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                ShadowIcons.Lock,
                contentDescription = null,
                tint = colors.ink3,
                modifier = Modifier
                    .padding(top = 1.dp)
                    .size(14.dp),
            )
            Text(
                "Links are stored encrypted. Duplicates are matched by server and settings, not by name.",
                style = Shadow.type.caption,
                color = colors.ink3,
            )
        }
    }
}

@Composable
private fun PastePanel(
    state: RoutesScreenState,
    editor: ProxyEditorState?,
    editedRoute: ProxyProfileSummary?,
    analysis: LinkAnalysis,
    actions: RoutesActions,
    onReview: () -> Unit,
    onShowDuplicate: (String) -> Unit,
) {
    val colors = Shadow.colors
    val scope = rememberCoroutineScope()
    val editing = editor?.profileId != null
    val text = editor?.rawUri.orEmpty()
    var masked by rememberSaveable(editor?.profileId) { mutableStateOf(editing) }
    val hasText = text.isNotBlank()
    val helper = pasteHelper(analysis, editing, editor?.error)
    Column(modifier = Modifier.fadeUpIn()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Route link", style = Shadow.type.label, color = colors.ink2)
            val meta = when {
                analysis.linkCount > 1 -> "${analysis.linkCount} links"
                else -> analysis.route?.profile?.protocol?.scheme.orEmpty()
            }
            Text(meta, style = Shadow.type.monoS, color = colors.ink3)
        }
        LinkField(
            text = text,
            masked = masked && hasText,
            loading = editor?.loading == true,
            tone = helper.tone,
            onTextChange = actions.onEditorChange,
            pasteEnabled = state.clipboardHasText,
            onPaste = { scope.launch { actions.readClipboard()?.let(actions.onEditorChange) } },
            onToggleMask = { masked = !masked },
            onClear = {
                actions.onEditorChange("")
                masked = false
            },
        )
        HelperLine(
            text = helper.text,
            tone = helper.tone,
            action = helper.duplicateOf?.let { name -> "Show it" to { onShowDuplicate(name) } },
        )
        if (!editing && analysis.linkCount > 1) {
            ReviewLinksButton(count = analysis.linkCount, onClick = onReview, modifier = Modifier.padding(top = 12.dp))
        }
    }
    Column(
        modifier = Modifier
            .padding(top = 20.dp)
            .fadeUpIn(2),
    ) {
        Overline("Preview")
        val route = analysis.route
        if (route != null) {
            PreviewCard(route = route, duplicateOf = analysis.duplicateOf)
        } else {
            PreviewPlaceholder(
                when {
                    analysis.linkCount == 0 -> "A preview appears as soon as the link can be read."
                    analysis.linkCount > 1 -> "Several links here. Review them together in the batch view."
                    analysis.parse is LinkParse.UnsupportedScheme -> "No preview for ${analysis.parse.scheme}:// links."
                    else -> "Nothing to preview until the link can be read."
                },
            )
        }
    }
    if (!editing) {
        Column(
            modifier = Modifier
                .padding(top = 20.dp)
                .fadeUpIn(4),
        ) {
            Overline("More ways to add")
            PublicListCard(state = state, onRefresh = actions.onRefresh)
        }
    } else {
        WhenYouSaveNote(
            carrying = editedRoute != null && editedRoute.id == state.routes.tunnelRoute()?.id,
            modifier = Modifier
                .padding(top = 20.dp)
                .fadeUpIn(4),
        )
    }
}

/**
 * The batch view of [text]. Short texts are read right away; long ones (up to 10,000 links, each
 * parsed and fingerprinted) off the main thread, keeping the last result on screen meanwhile.
 */
@Composable
private fun rememberBatchAnalysis(
    parser: ProxyShareLinkParser,
    text: String,
    known: Map<String, String>,
): BatchAnalysis {
    val inline = text.length <= INLINE_BATCH_CHARS
    val quick = if (inline) remember(text, known) { parser.analyzeBatch(text, known) } else null
    val background by produceState<BatchAnalysis?>(null, text, known, inline) {
        if (!inline) value = withContext(Dispatchers.Default) { parser.analyzeBatch(text, known) }
    }
    return quick ?: background ?: EmptyBatch
}

/** "Review 2 links →": the sky tonal button that opens the batch view (40 dp, radius 12). */
@Composable
private fun ReviewLinksButton(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Shadow.colors
    Row(
        modifier = modifier
            .height(40.dp)
            .clip(ShadowShapes.Tile)
            .shadowClickable(remember { MutableInteractionSource() }, onClick = onClick)
            .background(colors.skyTint)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Review $count links", style = Shadow.type.label, color = colors.skyText)
        Icon(ShadowIcons.Send, contentDescription = null, tint = colors.skyText, modifier = Modifier.size(16.dp))
    }
}

private class Helper(val text: String, val tone: HelperTone, val duplicateOf: String? = null)

private fun pasteHelper(analysis: LinkAnalysis, editing: Boolean, error: String?): Helper {
    if (error != null) return Helper(error, HelperTone.Error)
    val parse = analysis.parse
    return when {
        analysis.linkCount == 0 -> Helper(
            if (editing) {
                "Paste the new link for this route."
            } else {
                "Paste one link. To add a batch, use From clipboard."
            },
            HelperTone.Muted,
        )
        analysis.linkCount > 1 && editing -> Helper("Editing takes one link. Remove the extra lines.", HelperTone.Error)
        analysis.linkCount > 1 -> Helper(
            "Found ${analysis.linkCount} links. Add them together in the batch view.",
            HelperTone.Info,
        )
        parse is LinkParse.UnsupportedScheme -> Helper(
            "Only vless://, vmess:// and trojan:// links are supported.",
            HelperTone.Error,
        )
        parse is LinkParse.Invalid -> Helper(parse.problem.message, HelperTone.Error)
        analysis.duplicateOf != null -> Helper(
            if (editing) {
                "This link matches ${analysis.duplicateOf}, which you already have."
            } else {
                "You already have this route as ${analysis.duplicateOf}."
            },
            HelperTone.Warn,
            duplicateOf = analysis.duplicateOf,
        )
        analysis.route?.unknownTransport == true -> Helper(
            "This transport isn’t supported yet. The route will be saved but can’t connect.",
            HelperTone.Error,
        )
        else -> Helper(
            if (editing) {
                "Checked as you type. Changes apply when you save."
            } else {
                "Checked as you type. Not saved until you add it."
            },
            HelperTone.Muted,
        )
    }
}

/** The mono link box: 84 dp text area, then a 44 dp row with Paste / show-hide / clear. */
@Composable
private fun LinkField(
    text: String,
    masked: Boolean,
    loading: Boolean,
    tone: HelperTone,
    onTextChange: (String) -> Unit,
    pasteEnabled: Boolean,
    onPaste: () -> Unit,
    onToggleMask: () -> Unit,
    onClear: () -> Unit,
) {
    val colors = Shadow.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val border by animateColorAsState(
        targetValue = when (tone) {
            HelperTone.Error -> colors.coral
            HelperTone.Warn -> colors.amber.copy(alpha = WARN_BORDER_ALPHA)
            else -> if (focused) colors.amber else colors.line
        },
        animationSpec = shadowTween(ShadowMotion.Small, ShadowMotion.Ease),
        label = "link-border",
    )
    val textStyle = Shadow.type.mono.copy(lineHeight = 20.sp, color = colors.ink1)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ShadowShapes.Input)
            .background(colors.surface2)
            .border(1.dp, border, ShadowShapes.Input)
            .padding(OUTLINE),
    ) {
        Column {
            if (masked) {
                Text(
                    text = text.lineSequence().joinToString("\n") { maskLink(it) },
                    style = textStyle,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(84.dp)
                        .padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 4.dp)
                        .semantics { contentDescription = "Route link, hidden" },
                )
            } else {
                BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    textStyle = textStyle,
                    cursorBrush = SolidColor(colors.amber),
                    interactionSource = interaction,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(84.dp)
                        .padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 4.dp)
                        .semantics { contentDescription = "Route link" },
                    decorationBox = { inner ->
                        if (text.isEmpty()) {
                            Text("vless://, vmess:// or trojan://…", style = textStyle, color = colors.ink3)
                        }
                        inner()
                    },
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .padding(start = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (text.isBlank()) {
                    PillAction(
                        text = "Paste",
                        icon = ShadowIcons.Clipboard,
                        enabled = pasteEnabled,
                        onClick = onPaste,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (text.isNotBlank()) {
                    PlainIconButton(
                        icon = if (masked) ShadowIcons.Eye else ShadowIcons.EyeOff,
                        contentDescription = if (masked) "Show secret" else "Hide secret",
                        onClick = onToggleMask,
                        iconSize = 20.dp,
                        shape = ShadowShapes.Tile,
                    )
                    PlainIconButton(
                        icon = ShadowIcons.Close,
                        contentDescription = "Clear link",
                        onClick = onClear,
                        iconSize = 20.dp,
                        shape = ShadowShapes.Tile,
                    )
                }
            }
        }
        if (loading) DecryptingOverlay()
    }
}

/** "Decrypting link…" with three blinking bars over the field while the stored link loads. */
@Composable
private fun BoxScope.DecryptingOverlay() {
    val colors = Shadow.colors
    Column(
        modifier = Modifier
            .matchParentSize()
            .background(colors.surface2)
            .padding(horizontal = 14.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(ShadowIcons.Lock, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(14.dp))
            Text("Decrypting link…", style = Shadow.type.caption, color = colors.ink3)
        }
        listOf(DECRYPT_BAR_1, DECRYPT_BAR_2, DECRYPT_BAR_3).forEach { fraction ->
            Spacer(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(10.dp)
                    .clip(CircleShape)
                    .blinking(true)
                    .background(colors.surface3),
            )
        }
    }
}

@Composable
private fun PillAction(text: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    val colors = Shadow.colors
    Row(
        modifier = Modifier
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .height(36.dp)
            .clip(CircleShape)
            .shadowClickable(remember { MutableInteractionSource() }, enabled = enabled, onClick = onClick)
            .background(colors.surface3)
            .border(1.dp, colors.line2, CircleShape)
            .padding(horizontal = 14.dp + OUTLINE),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = colors.ink1, modifier = Modifier.size(16.dp))
        Text(text, style = Shadow.type.label, color = colors.ink1)
    }
}

@Composable
private fun HelperLine(text: String, tone: HelperTone, action: Pair<String, () -> Unit>?) {
    val colors = Shadow.colors
    val ink = when (tone) {
        HelperTone.Muted -> colors.ink3
        HelperTone.Info -> colors.skyText
        HelperTone.Warn -> colors.amberText
        HelperTone.Error -> colors.coralText
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        when (tone) {
            HelperTone.Warn, HelperTone.Error ->
                Icon(ShadowIcons.Warning, contentDescription = null, tint = ink, modifier = Modifier.size(16.dp))
            HelperTone.Info -> Icon(
                ShadowIcons.Info,
                contentDescription = null,
                tint = ink,
                modifier = Modifier.size(16.dp),
            )
            HelperTone.Muted -> Unit
        }
        SwapText(text = text, style = Shadow.type.caption, color = ink, maxLines = 4, modifier = Modifier.weight(1f))
        if (action != null) {
            // RouteImport.dc.html: a 32 dp text button with margins -8 / -6 / -8 / 0, on the first line.
            ShadowButton(
                text = action.first,
                onClick = action.second,
                variant = ShadowButtonVariant.Text,
                size = ShadowButtonSize(32.dp, 10.dp, 14.dp, 10.dp, ShadowButtonLabel.Small),
                modifier = Modifier.negativeMargins(vertical = 8.dp, end = 6.dp),
            )
        }
    }
}

@Composable
private fun Overline(text: String) {
    Text(
        text = text.uppercase(),
        style = Shadow.type.overline,
        color = Shadow.colors.ink3,
        modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
    )
}

/** Live preview: name, host, protocol and transport tags, and "Looks good" / "Already added" / "Can't connect". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PreviewCard(route: LinkParse.Route, duplicateOf: String?) {
    val colors = Shadow.colors
    val profile = route.profile
    SheetGroup {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconTile(icon = ShadowIcons.Routes)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        profile.name,
                        style = Shadow.type.titleS,
                        color = colors.ink1,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${profile.host}:${profile.port}",
                        style = Shadow.type.mono,
                        color = colors.ink2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(
                Modifier
                    .padding(vertical = 12.dp)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.line),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                CodeTag(profile.protocol.name, colors.ink1)
                CodeTag(
                    "${profile.transport.label()} · ${profile.security.name}",
                    if (route.unknownTransport) colors.coralText else colors.ink2,
                )
                Spacer(Modifier.weight(1f))
                when {
                    duplicateOf != null -> PreviewStatus(
                        "Already added",
                        ShadowIcons.Warning,
                        colors.amberText,
                        colors.amberTint,
                    )
                    route.unknownTransport -> PreviewStatus(
                        "Can’t connect",
                        ShadowIcons.Warning,
                        colors.coralText,
                        colors.coralTint,
                    )
                    else -> PreviewStatus("Looks good", BoldCheck, colors.mintText, colors.mintTint)
                }
            }
            if (route.unnamed) {
                Text(
                    "The link has no name, so its address is used instead.",
                    style = Shadow.type.caption,
                    color = colors.ink3,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
    }
}

/** 24 dp mono tag, radius 8, surface-3 (protocol, transport · security). */
@Composable
internal fun CodeTag(text: String, ink: Color, fontSize: Int = 11, height: Dp = 24.dp, horizontalPadding: Dp = 9.dp) {
    Box(
        modifier = Modifier
            .height(height)
            .clip(RoundedCornerShape(8.dp))
            .background(Shadow.colors.surface3)
            .padding(horizontal = horizontalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = Shadow.type.monoMedium.copy(fontSize = fontSize.sp, letterSpacing = 0.04.em),
            color = ink,
            maxLines = 1,
        )
    }
}

@Composable
private fun PreviewStatus(text: String, icon: ImageVector, ink: Color, fill: Color) {
    Row(
        modifier = Modifier
            .height(28.dp)
            .clip(CircleShape)
            .background(fill)
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(14.dp))
        Text(text, style = TagLabel, color = ink)
    }
}

/** Dashed placeholder while there is nothing to preview. */
@Composable
private fun PreviewPlaceholder(text: String) {
    val colors = Shadow.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 126.dp)
            .drawBehind {
                val stroke = 1.dp.toPx()
                drawRoundRect(
                    color = colors.line2,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(18.dp.toPx()),
                    style = Stroke(
                        width = stroke,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())),
                    ),
                )
            }
            .padding(horizontal = 28.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Icon(ShadowIcons.Link, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(22.dp))
        SwapText(
            text = text,
            style = Shadow.type.bodyS.copy(textAlign = TextAlign.Center),
            color = colors.ink3,
            maxLines = 3,
        )
    }
}

/** "More ways to add" → the public list with its own Refresh. */
@Composable
private fun PublicListCard(state: RoutesScreenState, onRefresh: () -> Unit) {
    val colors = Shadow.colors
    val routes = state.routes
    val syncing = routes.isSyncing
    SheetGroup {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(icon = ShadowIcons.Download)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Public list", style = Shadow.type.button, color = colors.ink1)
                SwapText(
                    text = when {
                        syncing -> "Fetching the list…"
                        state.listSyncedAt != null -> "List updated ${formatAgo(state.nowMillis, state.listSyncedAt)}"
                        else -> "Not fetched yet"
                    },
                    style = Shadow.type.bodyS,
                    color = if (syncing) colors.amberText else colors.ink3,
                )
            }
            ShadowButton(
                text = if (syncing) "Refreshing…" else "Refresh",
                onClick = onRefresh,
                variant = ShadowButtonVariant.Secondary,
                icon = ShadowIcons.Refresh,
                loading = syncing,
                enabled = !routes.isRemovingUnavailable,
                size = ShadowButtonSize(40.dp, 14.dp, 16.dp, 12.dp, ShadowButtonLabel.Small),
                contentDescription = if (syncing) "Refreshing the public list" else "Refresh the public list",
            )
        }
    }
}

@Composable
private fun WhenYouSaveNote(carrying: Boolean, modifier: Modifier = Modifier) {
    val colors = Shadow.colors
    val points = listOf(
        "Public list updates won’t change it again.",
        "Outdated is cleared and it moves to the top.",
        "Pin, active state and last check stay.",
        if (carrying) {
            "Your live connection updates after you reconnect."
        } else {
            "A live connection updates after reconnect."
        },
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(ShadowShapes.Banner)
            .background(colors.skyTint)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier.padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(ShadowIcons.Info, contentDescription = null, tint = colors.skyText, modifier = Modifier.size(18.dp))
            Text("When you save", style = Shadow.type.label, color = colors.skyText)
        }
        // A list indented 28 dp; its bullets hang in the indent, as list markers do.
        Column(
            modifier = Modifier.padding(start = 28.dp - BULLET_SLOT),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            points.forEach { point ->
                Row {
                    // A 4 dp disc 7 dp before the text, centred on the first 18 dp line.
                    Box(
                        modifier = Modifier
                            .width(BULLET_SLOT)
                            .padding(top = 7.dp, end = 7.dp),
                        contentAlignment = Alignment.TopEnd,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(4.dp)
                                .clip(CircleShape)
                                .background(colors.skySoft),
                        )
                    }
                    Text(point, style = Shadow.type.bodyS, color = colors.skySoft)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClipboardPanel(
    batch: BatchAnalysis,
    mode: ClipMode,
    text: String,
    source: String,
    filter: BatchCategory?,
    onFilterChange: (BatchCategory?) -> Unit,
    onReadAgain: () -> Unit,
    onToggleEdit: () -> Unit,
    onTypeLinks: () -> Unit,
    onTextChange: (String) -> Unit,
) {
    val colors = Shadow.colors
    if (mode == ClipMode.Empty || mode == ClipMode.File) {
        ClipboardEmpty(
            file = mode == ClipMode.File,
            text = text.trim(),
            onReadAgain = onReadAgain,
            onTypeLinks = onTypeLinks,
        )
        return
    }
    Row(modifier = Modifier.fadeUpIn(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp, top = 2.dp),
        ) {
            val found = batch.found
            SwapText(
                text = if (found == 0) "No links found" else "$found ${if (found == 1) "link" else "links"} found",
                style = Shadow.type.titleS,
                color = colors.ink1,
            )
            Text(
                text = buildList {
                    add(source)
                    if (batch.comments > 0) {
                        add("${batch.comments} ${if (batch.comments == 1) "comment line" else "comment lines"} skipped")
                    }
                    if (batch.overflow > 0) add("only the first 10,000 are read")
                }.joinToString(" · "),
                style = Shadow.type.bodyS,
                color = colors.ink3,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        PlainIconButton(
            icon = ShadowIcons.Refresh,
            contentDescription = "Read clipboard again",
            onClick = onReadAgain,
            iconSize = 20.dp,
            shape = ShadowShapes.Tile,
        )
        val editing = mode == ClipMode.Edit
        ShadowButton(
            text = if (editing) "Done" else "Edit",
            onClick = onToggleEdit,
            variant = if (editing) ShadowButtonVariant.Tonal else ShadowButtonVariant.Secondary,
            icon = if (editing) BoldCheck else ShadowIcons.Edit,
            size = ShadowButtonSize(40.dp, 12.dp, 16.dp, 12.dp, ShadowButtonLabel.Small),
            modifier = Modifier
                .padding(top = 2.dp)
                .semantics { stateDescription = if (editing) "Editing" else "Not editing" },
        )
    }
    val categories = BatchCategory.entries.filter { batch.count(it) > 0 }
    if (categories.isNotEmpty()) {
        FlowRow(
            modifier = Modifier
                .padding(top = 14.dp)
                .fadeUpIn(1),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            categories.forEach { category ->
                CategoryChip(
                    category = category,
                    count = batch.count(category),
                    active = filter == category && mode == ClipMode.List,
                    onClick = {
                        if (mode == ClipMode.Edit) onToggleEdit()
                        onFilterChange(if (filter == category && mode == ClipMode.List) null else category)
                    },
                )
            }
        }
    }
    if (mode == ClipMode.Edit) {
        Text(
            "Links, one per line",
            style = Shadow.type.label,
            color = colors.ink2,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 16.dp, bottom = 8.dp),
        )
        BatchTextField(text = text, onTextChange = onTextChange)
        Text(
            "Lines starting with # are skipped. Up to 10,000 links at once. Subscription blocks (one long base64 " +
                "text) aren’t supported, so paste the links themselves.",
            style = Shadow.type.caption,
            color = colors.ink3,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp),
        )
    } else {
        BatchList(batch = batch, filter = filter, onShowAll = { onFilterChange(null) })
    }
}

@Composable
private fun CategoryChip(category: BatchCategory, count: Int, active: Boolean, onClick: () -> Unit) {
    val colors = Shadow.colors
    val label = when (category) {
        BatchCategory.New -> "$count new"
        BatchCategory.Duplicate -> "$count ${if (count == 1) "duplicate" else "duplicates"}"
        BatchCategory.Unsupported -> "$count unsupported"
        BatchCategory.Invalid -> "$count can’t read"
    }
    val dot = when (category) {
        BatchCategory.New -> colors.mint
        BatchCategory.Duplicate -> colors.ink3
        BatchCategory.Unsupported, BatchCategory.Invalid -> colors.coral
    }
    Row(
        modifier = Modifier
            .height(36.dp)
            .clip(CircleShape)
            .shadowClickable(
                remember { MutableInteractionSource() },
                onClickLabel = if (active) "Show all" else "Show only $label",
                onClick = onClick,
            )
            .background(if (active) colors.ink1 else colors.surface1)
            .border(1.dp, if (active) colors.ink1 else colors.line2, CircleShape)
            .semantics { stateDescription = if (active) "Filtering" else "" }
            .padding(horizontal = 12.dp + OUTLINE),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(if (active) colors.bg else dot),
        )
        Text(label, style = Shadow.type.label, color = if (active) colors.bg else colors.ink1)
    }
}

@Composable
private fun BatchList(batch: BatchAnalysis, filter: BatchCategory?, onShowAll: () -> Unit) {
    val colors = Shadow.colors
    val visible = batch.rows.filter { filter == null || it.category == filter }
    val shown = visible.take(MAX_BATCH_ROWS_SHOWN)
    SheetGroup(modifier = Modifier.padding(top = 14.dp)) {
        shown.forEachIndexed { index, row ->
            Column(modifier = Modifier.fadeUpIn(index)) {
                if (index > 0) {
                    Spacer(
                        Modifier
                            .padding(horizontal = 16.dp)
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(colors.line2),
                    )
                }
                BatchRowItem(row)
            }
        }
        if (visible.size > shown.size) {
            BatchFooterText("and ${visible.size - shown.size} more")
        }
        if (filter != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind { drawLine(colors.line2, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
                    .height(48.dp)
                    .shadowClickable(remember { MutableInteractionSource() }, onClick = onShowAll),
                contentAlignment = Alignment.Center,
            ) {
                val total = batch.rows.size
                Text(
                    "Show all $total ${if (total == 1) "link" else "links"}",
                    style = Shadow.type.label,
                    color = colors.amberText,
                )
            }
        }
        if (visible.isEmpty()) {
            Text(
                "No links in this text yet.",
                style = Shadow.type.bodyS,
                color = colors.ink3,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 20.dp),
            )
        }
    }
}

@Composable
private fun BatchFooterText(text: String) {
    val colors = Shadow.colors
    Text(
        text,
        style = Shadow.type.bodyS,
        color = colors.ink3,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind { drawLine(colors.line2, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun BatchRowItem(row: BatchRow) {
    val colors = Shadow.colors
    val dimmed = row.category != BatchCategory.New
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .padding(start = 16.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                row.name,
                style = Shadow.type.button,
                color = if (dimmed) colors.ink2 else colors.ink1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                row.subtitle,
                style = if (row.subtitleMono) Shadow.type.monoS else Shadow.type.caption,
                color = if (row.unknownTransport) colors.coralText else colors.ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when (row.category) {
            BatchCategory.New -> Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(colors.mintTint)
                    .semantics { contentDescription = "New" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    ShadowIcons.Plus,
                    contentDescription = null,
                    tint = colors.mintText,
                    modifier = Modifier.size(16.dp),
                )
            }
            BatchCategory.Duplicate -> Text("Duplicate", style = TagLabel, color = colors.ink3)
            BatchCategory.Unsupported, BatchCategory.Invalid -> Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    ShadowIcons.Warning,
                    contentDescription = null,
                    tint = colors.coralText,
                    modifier = Modifier.size(14.dp),
                )
                Text(row.label, style = TagLabel, color = colors.coralText)
            }
        }
    }
}

@Composable
private fun BatchTextField(text: String, onTextChange: (String) -> Unit) {
    val colors = Shadow.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val style = Shadow.type.monoS.copy(lineHeight = 18.sp, color = colors.ink1)
    // One link per line, as typed: lines don't wrap, the field scrolls sideways (white-space: pre).
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(236.dp + OUTLINE * 2)
            .clip(ShadowShapes.Input)
            .background(colors.surface2)
            .border(1.dp, if (focused) colors.amber else colors.line, ShadowShapes.Input)
            .padding(OUTLINE),
    ) {
        val viewport = maxWidth
        BasicTextField(
            value = text,
            onValueChange = onTextChange,
            textStyle = style,
            cursorBrush = SolidColor(colors.amber),
            interactionSource = interaction,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
            modifier = Modifier
                .fillMaxHeight()
                .horizontalScroll(rememberScrollState())
                .widthIn(min = viewport)
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .semantics { contentDescription = "Links, one per line" },
            decorationBox = { inner ->
                if (text.isEmpty()) Text("vless://… one link per line", style = style, color = colors.ink3)
                inner()
            },
        )
    }
}

@Composable
private fun ClipboardEmpty(file: Boolean, text: String, onReadAgain: () -> Unit, onTypeLinks: () -> Unit) {
    val colors = Shadow.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 56.dp, bottom = 8.dp)
            .fadeUpIn(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconTile(icon = ShadowIcons.Clipboard, size = 56.dp, iconSize = 26.dp, cornerRadius = 16.dp)
        Text(
            if (file) "That’s not a link" else "Nothing to import",
            style = Shadow.type.titleS,
            color = colors.ink1,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            if (file) {
                "Your clipboard holds a file or an image. Copy the route links as text, then read it again."
            } else {
                "Your clipboard is empty, or Android didn’t let shadow read it. Copy some route links and read " +
                    "it again."
            },
            style = Shadow.type.bodyS,
            color = colors.ink3,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(top = 6.dp)
                .widthIn(max = 290.dp),
        )
        if (file) {
            Text(
                text,
                style = Shadow.type.monoS,
                color = colors.ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = 14.dp)
                    .clip(ShadowShapes.Tile)
                    .background(colors.surface1)
                    .border(1.dp, colors.line, ShadowShapes.Tile)
                    .padding(horizontal = 12.dp + OUTLINE, vertical = 10.dp + OUTLINE),
            )
        }
        Row(modifier = Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ShadowButton(
                text = "Read again",
                onClick = onReadAgain,
                variant = ShadowButtonVariant.Secondary,
                icon = ShadowIcons.Refresh,
                size = ShadowButtonSize(40.dp, 16.dp, 16.dp, 12.dp, ShadowButtonLabel.Small),
            )
            ShadowButton(
                text = "Type links",
                onClick = onTypeLinks,
                variant = ShadowButtonVariant.Text,
                size = ShadowButtonSize(40.dp, 14.dp, 16.dp, 12.dp, ShadowButtonLabel.Small),
            )
        }
    }
}

/** TabletRoutes.dc.html's 560 dp "Edit route" dialog. */
@Composable
private fun EditRouteDialog(
    routes: OpenSourceUiState,
    editor: ProxyEditorState,
    actions: RoutesActions,
    linkParser: ProxyShareLinkParser,
) {
    val colors = Shadow.colors
    val route = routes.profileWithId(editor.profileId)
    val known = remember(routes.library, editor.profileId) { routes.library.namesByFingerprint(editor.profileId) }
    val analysis = remember(editor.rawUri, known) { linkParser.analyzeLink(editor.rawUri, known, editing = true) }
    val error = editor.error ?: tabletEditError(analysis)
    var reveal by rememberSaveable { mutableStateOf(false) }
    val carrying = route != null && route.id == routes.tunnelRoute()?.id
    ShadowDialogContainer(onDismissRequest = actions.onDismissAddSheet, modifier = Modifier.widthIn(max = 560.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Edit route", style = Shadow.type.titleM, color = colors.ink1)
                Text(
                    "${route?.name ?: "Route"} · one vless://, vmess:// or trojan:// link",
                    style = Shadow.type.bodyS,
                    color = colors.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            ShadowIconButton(
                icon = ShadowIcons.Close,
                contentDescription = "Close",
                onClick = actions.onDismissAddSheet,
                style = ShadowIconButtonStyle.Filled,
                size = 40.dp,
                iconSize = 18.dp,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Route link", style = Shadow.type.label, color = colors.ink2)
            ShadowButton(
                text = if (reveal) "Hide" else "Show",
                onClick = { reveal = !reveal },
                variant = ShadowButtonVariant.Text,
                icon = if (reveal) ShadowIcons.EyeOff else ShadowIcons.Eye,
                size = ShadowButtonSize(36.dp, 12.dp, 16.dp, 12.dp, ShadowButtonLabel.Small),
                modifier = Modifier.negativeMargins(end = 8.dp),
            )
        }
        val fieldStyle = Shadow.type.monoS.copy(lineHeight = 18.sp)
        if (!reveal || editor.loading) {
            Text(
                text = if (editor.loading) "Decrypting link…" else maskLink(editor.rawUri),
                style = fieldStyle,
                color = colors.ink2,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 112.dp)
                    .clip(ShadowShapes.Input)
                    .background(colors.surface2)
                    .border(1.dp, colors.line, ShadowShapes.Input)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        } else {
            BasicTextField(
                value = editor.rawUri,
                onValueChange = actions.onEditorChange,
                textStyle = fieldStyle.copy(color = colors.ink1),
                cursorBrush = SolidColor(colors.amber),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(112.dp)
                    .clip(ShadowShapes.Input)
                    .background(colors.surface2)
                    .border(1.dp, if (error != null) colors.coral else colors.line, ShadowShapes.Input)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .semantics { contentDescription = "Route link" },
            )
        }
        Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (error != null) {
                Icon(
                    ShadowIcons.Warning,
                    contentDescription = null,
                    tint = colors.coralText,
                    modifier = Modifier
                        .padding(top = 1.dp)
                        .size(14.dp),
                )
            }
            SwapText(
                text = error ?: "Links stay encrypted on this device.",
                style = Shadow.type.caption,
                color = if (error != null) colors.coralText else colors.ink3,
                maxLines = 3,
            )
        }
        InfoNote(
            text = buildString {
                append(
                    if (route?.isManual == false) {
                        "Saving makes it your own route: a refresh won’t change it, Outdated is cleared and it " +
                            "moves " +
                            "to the top. Its pin, active state and last check stay."
                    } else {
                        "Its pin, active state and last check stay, even if the address changes."
                    },
                )
                if (carrying) append(" Your current connection keeps the old settings until you reconnect.")
            },
            modifier = Modifier.padding(top = 16.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            ShadowButton(
                text = "Cancel",
                onClick = actions.onDismissAddSheet,
                variant = ShadowButtonVariant.Ghost,
                size = ShadowButtonSize.Medium,
            )
            ShadowButton(
                text = "Save",
                onClick = actions.onEditorSave,
                size = ShadowButtonSize(44.dp, 20.dp, 18.dp, 14.dp, ShadowButtonLabel.Large),
                enabled = error == null && !editor.loading,
                loading = editor.saving,
            )
        }
    }
}

private fun tabletEditError(analysis: LinkAnalysis): String? {
    val parse = analysis.parse
    return when {
        analysis.linkCount == 0 -> "Paste a route link."
        analysis.linkCount > 1 -> "Edit takes one link. To add several, use Add routes."
        parse is LinkParse.UnsupportedScheme -> "Use a vless://, vmess:// or trojan:// link."
        parse is LinkParse.Invalid -> when (parse.problem) {
            LinkProblem.TooLong -> "This link is too long to be a route."
            LinkProblem.NotLink, LinkProblem.Subscription -> "Use a vless://, vmess:// or trojan:// link."
            LinkProblem.NoUserId, LinkProblem.NoPassword, LinkProblem.NoVmessId ->
                "The link is missing its UUID or password."
            LinkProblem.NoHost, LinkProblem.NoPort -> "The link needs a host and a port, like @host:443."
            else -> parse.problem.message
        }
        analysis.duplicateOf != null ->
            "This link is already in your library as ${analysis.duplicateOf}. Change it or cancel."
        else -> null
    }
}

/** Saves the batch text only while it is short: route links carry credentials and can run to megabytes. */
private val ClipTextSaver = Saver<String?, String>(
    save = { text -> text?.takeIf { it.length <= MAX_SAVED_CLIP_CHARS } },
    restore = { it },
)
private const val MAX_SAVED_CLIP_CHARS = 16_000

private const val SOURCE_CLIPBOARD = "From your clipboard"
private const val SOURCE_PASTED = "Pasted text"
private const val SOURCE_EDITED = "Edited text"
private const val SOURCE_TYPED = "Typed text"
private const val WARN_BORDER_ALPHA = 0.7f
private const val DECRYPT_BAR_1 = 0.88f
private const val DECRYPT_BAR_2 = 0.66f
private const val DECRYPT_BAR_3 = 0.44f
private const val MAX_BATCH_ROWS_SHOWN = 200

/** About 30 links: analysed during composition; longer texts are analysed in the background. */
private const val INLINE_BATCH_CHARS = 8_000
private val EmptyBatch = BatchAnalysis(rows = emptyList(), comments = 0, overflow = 0)
private val BULLET_SLOT = 16.dp
private val SHEET_TOP_GAP = 68.dp

/** What ShadowBottomSheet pads below its content. */
private val SHEET_BOTTOM_PADDING = 22.dp

/** Handle (28), header (title + subtitle + gaps, 68) and the sheet's bottom padding (22). */
private val SHEET_CHROME = 118.dp
private val MIN_SHEET_CONTENT = 320.dp
