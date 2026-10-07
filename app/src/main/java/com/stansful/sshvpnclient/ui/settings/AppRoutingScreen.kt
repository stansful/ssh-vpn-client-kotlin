package com.stansful.sshvpnclient.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stansful.sshvpnclient.domain.model.InstalledAppInfo
import com.stansful.sshvpnclient.domain.model.VpnMode
import com.stansful.sshvpnclient.ui.apppicker.AppPickerUiState
import com.stansful.sshvpnclient.ui.designsystem.BannerTone
import com.stansful.sshvpnclient.ui.designsystem.CheckboxRow
import com.stansful.sshvpnclient.ui.designsystem.IconTile
import com.stansful.sshvpnclient.ui.designsystem.InlineBanner
import com.stansful.sshvpnclient.ui.designsystem.MetaTag
import com.stansful.sshvpnclient.ui.designsystem.MetaTagSize
import com.stansful.sshvpnclient.ui.designsystem.ShadowButton
import com.stansful.sshvpnclient.ui.designsystem.ShadowButtonLabel
import com.stansful.sshvpnclient.ui.designsystem.ShadowButtonSize
import com.stansful.sshvpnclient.ui.designsystem.ShadowButtonVariant
import com.stansful.sshvpnclient.ui.designsystem.ShadowCheckbox
import com.stansful.sshvpnclient.ui.designsystem.ShadowFilterChip
import com.stansful.sshvpnclient.ui.designsystem.ShadowFocusIndication
import com.stansful.sshvpnclient.ui.designsystem.ShadowIcons
import com.stansful.sshvpnclient.ui.designsystem.ShadowRadio
import com.stansful.sshvpnclient.ui.designsystem.ShadowSearchField
import com.stansful.sshvpnclient.ui.designsystem.StatusPill
import com.stansful.sshvpnclient.ui.designsystem.StatusTone
import com.stansful.sshvpnclient.ui.designsystem.SubScreenBar
import com.stansful.sshvpnclient.ui.designsystem.SwapText
import com.stansful.sshvpnclient.ui.designsystem.fadeUpIn
import com.stansful.sshvpnclient.ui.designsystem.pressScale
import com.stansful.sshvpnclient.ui.designsystem.strokeIcon
import com.stansful.sshvpnclient.ui.designsystem.toastObstacle
import com.stansful.sshvpnclient.ui.theme.Shadow
import com.stansful.sshvpnclient.ui.theme.ShadowDimens
import com.stansful.sshvpnclient.ui.theme.ShadowMotion
import com.stansful.sshvpnclient.ui.theme.ShadowShapes
import com.stansful.sshvpnclient.ui.theme.mixColors
import com.stansful.sshvpnclient.ui.theme.shadowTween

/** The VPN as the App routing bar shows it. */
internal enum class RoutingConnection(val label: String, val tone: StatusTone) {
    NotConnected("Not connected", StatusTone.Neutral),
    Connecting("Connecting", StatusTone.Progress),
    Reconnecting("Reconnecting", StatusTone.Progress),
    Connected("Connected", StatusTone.Success),
}

@Immutable
internal data class AppRoutingUiState(
    val picker: AppPickerUiState,
    val connection: RoutingConnection = RoutingConnection.NotConnected,
)

/** Callbacks of [AppRoutingScreen]; defaults do nothing (screenshots). */
@Immutable
internal class AppRoutingActions(
    val onBack: () -> Unit = {},
    val onChooseAllApps: () -> Unit = {},
    val onChooseSelectedApps: () -> Unit = {},
    val onEditList: () -> Unit = {},
    val onFinishEditing: () -> Unit = {},
    val onQueryChange: (String) -> Unit = {},
    val onShowSystemAppsChange: (Boolean) -> Unit = {},
    val onTogglePackage: (String) -> Unit = {},
    val onRetry: () -> Unit = {},
)

/**
 * App routing (sub-screen): "All apps" / "Only selected apps", then the app list (search, count, system
 * apps toggle, checked apps first). Under All apps the saved list shows as a summary that can be edited
 * without changing how apps connect.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AppRoutingScreen(
    state: AppRoutingUiState,
    actions: AppRoutingActions,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val colors = Shadow.colors
    val picker = state.picker
    val selectedMode = picker.vpnMode == VpnMode.SELECTED_APPS || picker.pendingSelectedApps
    val editing = !selectedMode && picker.editingWhileAllApps
    val showPicker = selectedMode || editing
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg)
            .imePadding(),
    ) {
        SubScreenBar(
            title = "App routing",
            onBack = actions.onBack,
            showDivider = listState.canScrollBackward,
            contentPadding = PaddingValues(start = 8.dp, end = 20.dp),
            status = {
                StatusPill(
                    label = state.connection.label,
                    tone = state.connection.tone,
                    blink = state.connection == RoutingConnection.Reconnecting,
                )
            },
        )
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item(key = "intro") {
                Text(
                    text = "Pick which apps use the VPN. One setting for every mode: Auto, Server and Routes.",
                    style = Shadow.type.bodyS,
                    color = colors.ink2,
                    modifier = Modifier
                        .contentWidth()
                        .fadeUpIn(0)
                        .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 16.dp),
                )
            }
            item(key = "choices") {
                RoutingChoices(
                    selectedMode = selectedMode,
                    pending = picker.pendingSelectedApps,
                    onAll = actions.onChooseAllApps,
                    onSelected = actions.onChooseSelectedApps,
                    modifier = Modifier.contentWidth(),
                )
            }
            if (!showPicker) {
                item(key = "summary") {
                    AppListSummary(picker, actions.onEditList, Modifier.contentWidth().fadeUpIn(3))
                }
            } else {
                appPicker(state, editing, actions)
            }
        }
        RoutingFooter()
    }
}

@Composable
private fun RoutingChoices(
    selectedMode: Boolean,
    pending: Boolean,
    onAll: () -> Unit,
    onSelected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .selectableGroup()
            .semantics { contentDescription = "Which apps use the VPN" },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ChoiceCard(
            title = "All apps",
            description = "Everything on this phone goes through the VPN.",
            icon = ShadowIcons.Apps,
            selected = !selectedMode,
            onClick = onAll,
            modifier = Modifier.fadeUpIn(1),
        )
        ChoiceCard(
            title = "Only selected apps",
            description = if (pending) {
                "Turns on as soon as you pick an app."
            } else {
                "Just the apps you choose; the rest go direct."
            },
            descriptionColor = if (pending) Shadow.colors.amberText else Shadow.colors.ink3,
            icon = AppsSelectedIcon,
            selected = selectedMode,
            pending = pending,
            onClick = onSelected,
            modifier = Modifier.fadeUpIn(2),
        )
    }
}

/** Radio card: 44 dp tile, title + description, radio; selected = amber edge, wash and ring (dashed while pending). */
@Composable
private fun ChoiceCard(
    title: String,
    description: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    pending: Boolean = false,
    descriptionColor: Color = Shadow.colors.ink3,
) {
    val colors = Shadow.colors
    val fill by animateColorAsState(
        targetValue = if (selected) mixColors(colors.amber, colors.bg, CHOICE_WASH) else colors.surface1,
        animationSpec = shadowTween(CHOICE_MS, ShadowMotion.Ease),
        label = "choice-fill",
    )
    val edge by animateColorAsState(
        targetValue = if (selected) colors.amber else colors.line,
        animationSpec = shadowTween(CHOICE_MS, ShadowMotion.Ease),
        label = "choice-edge",
    )
    val ring by animateColorAsState(
        targetValue = colors.amber.copy(alpha = if (selected && !pending) CHOICE_RING else 0f),
        animationSpec = shadowTween(ShadowMotion.Surface),
        label = "choice-ring",
    )
    val interaction = remember { MutableInteractionSource() }
    val shape = ShadowShapes.Card
    Row(
        modifier = modifier
            .fillMaxWidth()
            .pressScale(interaction, CHOICE_PRESS)
            .drawBehind {
                val spread = 4.dp.toPx()
                drawRoundRect(
                    color = ring,
                    topLeft = Offset(-spread, -spread),
                    size = Size(size.width + spread * 2, size.height + spread * 2),
                    cornerRadius = CornerRadius(18.dp.toPx() + spread),
                )
            }
            .clip(shape)
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = ShadowFocusIndication,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .background(fill)
            .drawBehind {
                val stroke = CHOICE_BORDER.toPx()
                drawRoundRect(
                    color = edge,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(18.dp.toPx() - stroke / 2),
                    style = Stroke(
                        width = stroke,
                        pathEffect = if (pending) {
                            PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))
                        } else {
                            null
                        },
                    ),
                )
            }
            .padding(CHOICE_BORDER + 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IconTile(
            icon = icon,
            tint = if (selected) colors.amberText else colors.ink2,
            container = if (selected) colors.amberTint else colors.surface2,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = 1.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = Shadow.type.titleS, color = colors.ink1)
            SwapText(text = description, style = Shadow.type.bodyS, color = descriptionColor, maxLines = 3)
        }
        ShadowRadio(selected = selected, onClick = null, modifier = Modifier.padding(top = 11.dp))
    }
}

@Composable
private fun AppListSummary(picker: AppPickerUiState, onEdit: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Shadow.colors
    val kept = remember(picker.installedApps, picker.selectedPackages) {
        picker.installedApps.filter { it.packageName in picker.selectedPackages }
    }
    val uninstalled = if (picker.isLoading || picker.loadFailed) {
        0
    } else {
        picker.selectedPackages.count { it !in picker.installedPackages }
    }
    val count = picker.selectedCount
    val names = kept.map { it.label } + if (uninstalled > 0) listOf("$uninstalled uninstalled") else emptyList()
    Column(modifier.padding(top = 20.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Overline("Your app list", Modifier.weight(1f))
            ShadowButton(
                text = "Edit list",
                onClick = onEdit,
                variant = ShadowButtonVariant.Text,
                size = ShadowButtonSize.Compact,
                icon = ShadowIcons.Edit,
                modifier = Modifier.offset(x = 8.dp),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .settingsCard()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (kept.isNotEmpty()) {
                AvatarStack(kept)
            } else {
                IconTile(icon = ShadowIcons.Apps, tint = colors.ink3)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = when (count) {
                        0 -> "No apps picked yet"
                        1 -> "1 app on your list"
                        else -> "$count apps on your list"
                    },
                    style = Shadow.type.rowTitle,
                    color = colors.ink1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (count == 0) "Tap Edit list to choose some." else names.joinToString(", "),
                    style = Shadow.type.bodyS,
                    color = colors.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            text = "The list stays saved while All apps is on. You can edit it now without changing how apps connect.",
            style = Shadow.type.bodyS,
            color = colors.ink3,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 10.dp),
        )
    }
}

/** Up to four overlapping 32 dp app avatars (2 dp card-colored ring), then "+N". */
@Composable
private fun AvatarStack(apps: List<InstalledAppInfo>) {
    val colors = Shadow.colors
    val ring = Modifier
        .size(32.dp)
        .clip(RoundedCornerShape(11.dp))
        .background(colors.surface1)
        .padding(2.dp)
    Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
        apps.take(MAX_AVATARS).forEach { app ->
            Box(ring) {
                AppAvatar(packageName = app.packageName, label = app.label, size = 28.dp, cornerRadius = 9.dp)
            }
        }
        if (apps.size > MAX_AVATARS) {
            Box(ring) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(colors.surface3),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("+${apps.size - MAX_AVATARS}", style = Shadow.type.label, color = colors.ink2)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun LazyListScope.appPicker(state: AppRoutingUiState, editing: Boolean, actions: AppRoutingActions) {
    val picker = state.picker
    val list = picker.list
    val query = picker.query.trim()
    val ready = !picker.isLoading && !picker.loadFailed
    val hasTop = list.selected.isNotEmpty() || list.uninstalled.isNotEmpty()
    val noResults = ready && query.isNotEmpty() && !hasTop && list.others.isEmpty()
    if (editing) {
        item(key = "editing") { EditingHeader(actions.onFinishEditing, Modifier.contentWidth()) }
    } else {
        item(key = "picker-gap") { Spacer(Modifier.height(20.dp)) }
    }
    stickyHeader(key = "search") {
        SearchBlock(state, editing, actions, Modifier.contentWidth())
    }
    when {
        picker.isLoading -> item(key = "loading") { SkeletonList(Modifier.contentWidth().padding(top = 8.dp)) }
        picker.loadFailed -> item(key = "error") { ListError(actions.onRetry, Modifier.contentWidth()) }
        noResults -> item(key = "empty") {
            NoMatches(
                query = query,
                hiddenSystem = list.hiddenSystemMatches,
                showSystem = picker.showSystemApps,
                onShowSystem = { actions.onShowSystemAppsChange(true) },
                onClear = { actions.onQueryChange("") },
                modifier = Modifier.contentWidth(),
            )
        }
        else -> {
            if (hasTop) {
                item(key = "top-title") { GroupTitle("Selected", top = 8.dp) }
                val topCount = list.selected.size + list.uninstalled.size
                itemsIndexed(list.selected, key = { _, app -> "top-${app.packageName}" }) { index, app ->
                    AppRow(
                        app = app,
                        checked = app.packageName in picker.selectedPackages,
                        first = index == 0,
                        last = index == topCount - 1,
                        index = index,
                        onToggle = actions.onTogglePackage,
                    )
                }
                itemsIndexed(list.uninstalled, key = { _, pkg -> "gone-$pkg" }) { index, packageName ->
                    val position = list.selected.size + index
                    UninstalledRow(
                        packageName = packageName,
                        checked = packageName in picker.selectedPackages,
                        first = position == 0,
                        last = position == topCount - 1,
                        index = position,
                        onToggle = actions.onTogglePackage,
                    )
                }
                if (list.uninstalled.isNotEmpty()) {
                    item(key = "gone-note") {
                        Text(
                            text = "Uninstalled apps stay on your list and in the count until you uncheck them. " +
                                "They're skipped when you connect.",
                            style = Shadow.type.caption,
                            color = Shadow.colors.ink3,
                            modifier = Modifier
                                .contentWidth()
                                .padding(start = 4.dp, end = 4.dp, top = 8.dp),
                        )
                    }
                }
            }
            if (list.others.isNotEmpty()) {
                item(key = "rest-title") {
                    GroupTitle(if (hasTop) "Other apps" else "Installed apps", top = if (hasTop) 24.dp else 8.dp)
                }
                val offset = if (hasTop) list.selected.size + list.uninstalled.size else 0
                itemsIndexed(list.others, key = { _, app -> "app-${app.packageName}" }) { index, app ->
                    AppRow(
                        app = app,
                        checked = app.packageName in picker.selectedPackages,
                        first = index == 0,
                        last = index == list.others.lastIndex,
                        index = offset + index,
                        onToggle = actions.onTogglePackage,
                    )
                }
            }
            if (!picker.showSystemApps && list.hiddenSystemMatches > 0 && query.isNotEmpty()) {
                item(key = "hidden-hint") {
                    val hidden = list.hiddenSystemMatches
                    ShadowButton(
                        text = if (hidden == 1) {
                            "1 system app also matches · Show"
                        } else {
                            "$hidden system apps also match · Show"
                        },
                        onClick = { actions.onShowSystemAppsChange(true) },
                        variant = ShadowButtonVariant.Text,
                        size = ShadowButtonSize(44.dp, 14.dp, 16.dp, 14.dp, ShadowButtonLabel.Small),
                        modifier = Modifier
                            .contentWidth()
                            .padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun EditingHeader(onDone: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(top = 20.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Overline("Your app list", Modifier.weight(1f))
            ShadowButton(
                text = "Done",
                onClick = onDone,
                variant = ShadowButtonVariant.Text,
                size = ShadowButtonSize.Compact,
                modifier = Modifier.offset(x = 8.dp),
            )
        }
        InlineBanner(
            message = "All apps is on, so this list isn't used yet. Edits are saved for when you switch to " +
                "Only selected apps — no reconnect.",
            tone = BannerTone.Info,
            tintedMessage = true,
        )
    }
}

/** Sticky search, "N selected" counter, the system apps toggle and the empty/pending notices. */
@Composable
private fun SearchBlock(
    state: AppRoutingUiState,
    editing: Boolean,
    actions: AppRoutingActions,
    modifier: Modifier = Modifier,
) {
    val colors = Shadow.colors
    val picker = state.picker
    val selectedMode = picker.vpnMode == VpnMode.SELECTED_APPS && !picker.pendingSelectedApps
    val usable = picker.usableCount
    val count = picker.selectedCount
    val (countFill, countInk) = when {
        usable > 0 -> colors.amberTint to colors.amberText
        picker.pendingSelectedApps || editing -> colors.surface3 to colors.ink2
        else -> colors.coralTint to colors.coralText
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.bg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(modifier.padding(top = 8.dp, bottom = 12.dp)) {
            ShadowSearchField(
                query = picker.query,
                onQueryChange = actions.onQueryChange,
                placeholder = "Search apps",
            )
            Row(
                modifier = Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth()
                    .heightIn(min = 36.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val countSpec = shadowTween<Color>(COUNT_MS, ShadowMotion.Ease)
                    val fill by animateColorAsState(countFill, countSpec, label = "count-fill")
                    val ink by animateColorAsState(countInk, countSpec, label = "count-ink")
                    Box(
                        modifier = Modifier
                            .heightIn(min = 28.dp)
                            .widthIn(min = 28.dp)
                            .background(fill, ShadowShapes.Pill)
                            .padding(horizontal = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        SwapText(text = count.toString(), style = Shadow.type.monoMedium, color = ink)
                    }
                    Text("selected", style = Shadow.type.label, color = colors.ink2)
                }
                ShadowFilterChip(
                    label = "Show system apps",
                    selected = picker.showSystemApps,
                    onClick = { actions.onShowSystemAppsChange(!picker.showSystemApps) },
                    icon = if (picker.showSystemApps) ShadowIcons.Check else null,
                )
            }
            val showEmptyError = selectedMode && usable == 0
            AnimatedVisibility(visible = showEmptyError, enter = noticeIn(), exit = noticeOut()) {
                EmptySelectionNotice(
                    connected = state.connection != RoutingConnection.NotConnected,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            AnimatedVisibility(visible = picker.pendingSelectedApps, enter = noticeIn(), exit = noticeOut()) {
                InlineBanner(
                    message = "Pick an app below to switch over. Until then, every app keeps using the VPN.",
                    tone = BannerTone.Info,
                    tintedMessage = true,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

/** "Pick at least one app — otherwise you can't connect." (+ why the running VPN still works). */
@Composable
private fun EmptySelectionNotice(connected: Boolean, modifier: Modifier = Modifier) {
    val colors = Shadow.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.coralTint, ShadowShapes.Banner)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Assertive }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(ShadowIcons.Warning, null, tint = colors.coralText, modifier = Modifier.size(18.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "Pick at least one app — otherwise you can't connect.",
                style = Shadow.type.bodyS.copy(fontWeight = FontWeight.SemiBold),
                color = colors.coralText,
            )
            if (connected) {
                Text(
                    text = "The current connection keeps your previous list until you do.",
                    style = Shadow.type.bodyS,
                    color = colors.coralText,
                )
            }
        }
    }
}

@Composable
private fun GroupTitle(title: String, top: Dp) {
    Overline(
        title,
        Modifier
            .contentWidth()
            .padding(start = 4.dp, end = 4.dp, top = top, bottom = 8.dp),
    )
}

@Composable
private fun Overline(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = Shadow.type.overline,
        color = Shadow.colors.ink3,
        modifier = modifier.semantics { heading() },
    )
}

@Composable
private fun AppRow(
    app: InstalledAppInfo,
    checked: Boolean,
    first: Boolean,
    last: Boolean,
    index: Int,
    onToggle: (String) -> Unit,
) {
    CheckboxRow(
        title = app.label,
        checked = checked,
        onCheckedChange = { onToggle(app.packageName) },
        subtitle = app.packageName,
        subtitleMono = true,
        badge = if (app.isSystem) "System" else null,
        leading = { AppAvatar(packageName = app.packageName, label = app.label) },
        modifier = Modifier
            .contentWidth()
            .entrance(index)
            .groupCell(first = first, last = last),
    )
}

/** A saved package that is no longer installed: still counted, can be unchecked. */
@Composable
private fun UninstalledRow(
    packageName: String,
    checked: Boolean,
    first: Boolean,
    last: Boolean,
    index: Int,
    onToggle: (String) -> Unit,
) {
    val colors = Shadow.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val wash by animateColorAsState(
        targetValue = when {
            pressed -> colors.surface2
            checked -> colors.amber.copy(alpha = CHECKED_WASH)
            else -> colors.amber.copy(alpha = 0f)
        },
        animationSpec = shadowTween(ShadowMotion.Small, ShadowMotion.Ease),
        label = "gone-wash",
    )
    val dash = colors.line2
    Row(
        modifier = Modifier
            .contentWidth()
            .entrance(index)
            .groupCell(first = first, last = last)
            .toggleable(
                value = checked,
                interactionSource = interaction,
                indication = ShadowFocusIndication,
                role = Role.Checkbox,
                onValueChange = { onToggle(packageName) },
            )
            .semantics(mergeDescendants = true) {
                contentDescription = "Uninstalled app, $packageName, still on your list"
            }
            .background(wash)
            .heightIn(min = 60.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(ShadowShapes.Tile)
                .background(colors.surface2)
                .drawBehind {
                    val stroke = 1.dp.toPx()
                    drawRoundRect(
                        color = dash,
                        topLeft = Offset(stroke / 2, stroke / 2),
                        size = Size(size.width - stroke, size.height - stroke),
                        cornerRadius = CornerRadius(12.dp.toPx()),
                        style = Stroke(
                            width = stroke,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())),
                        ),
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(ShadowIcons.Apps, null, tint = colors.ink3, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Uninstalled app",
                    style = Shadow.type.rowTitle,
                    color = colors.ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                MetaTag(text = "Not installed", tone = StatusTone.Error, size = MetaTagSize.Compact)
            }
            Text(
                text = packageName,
                style = Shadow.type.monoS,
                color = colors.ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ShadowCheckbox(checked = checked, onCheckedChange = null)
    }
}

/** Five placeholder rows while the installed apps load (shimmer stops under reduced motion). */
@Composable
private fun SkeletonList(modifier: Modifier = Modifier) {
    val colors = Shadow.colors
    val base = colors.surface2
    val highlight = mixColors(colors.ink1, colors.surface2, SHIMMER_HIGHLIGHT)
    // Read in the draw block: the sweep redraws instead of recomposing every frame.
    val shiftState = if (Shadow.reducedMotion) {
        null
    } else {
        rememberInfiniteTransition(label = "shimmer").animateFloat(
            initialValue = SHIMMER_FROM,
            targetValue = SHIMMER_TO,
            animationSpec = infiniteRepeatable(tween(ShadowMotion.Spinner, easing = LinearEasing), RepeatMode.Restart),
            label = "shimmer-shift",
        )
    }
    val shimmer = Modifier.drawBehind {
        val width = size.width.coerceAtLeast(1f)
        val shift = shiftState?.value ?: 0f
        drawRect(
            Brush.horizontalGradient(
                colors = listOf(base, highlight, base),
                startX = width * shift - width,
                endX = width * shift + width,
            ),
        )
    }
    Column(
        modifier = modifier
            .settingsCard()
            .semantics { contentDescription = "Loading apps" },
    ) {
        SkeletonWidths.forEachIndexed { index, (first, second) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .drawBehind {
                        if (index > 0) {
                            drawLine(
                                colors.line2,
                                Offset(16.dp.toPx(), 0f),
                                Offset(size.width - 16.dp.toPx(), 0f),
                                1.dp.toPx(),
                            )
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(36.dp).clip(ShadowShapes.Tile).then(shimmer))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(first, 10.dp).clip(ShadowShapes.Pill).then(shimmer))
                    Box(Modifier.size(second, 8.dp).clip(ShadowShapes.Pill).then(shimmer))
                }
                Box(Modifier.size(22.dp).clip(RoundedCornerShape(7.dp)).then(shimmer))
            }
        }
    }
}

@Composable
private fun NoMatches(
    query: String,
    hiddenSystem: Int,
    showSystem: Boolean,
    onShowSystem: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CenteredState(
        icon = ShadowIcons.Search,
        title = "No apps match “$query”",
        message = when {
            hiddenSystem == 1 -> "1 system app matches, but system apps are hidden."
            hiddenSystem > 1 -> "$hiddenSystem system apps match, but system apps are hidden."
            else -> "Search looks at app names and package names."
        },
        modifier = modifier,
    ) {
        if (hiddenSystem > 0 && !showSystem) {
            ShadowButton(
                text = "Show system apps",
                onClick = onShowSystem,
                variant = ShadowButtonVariant.Secondary,
                size = ShadowButtonSize.Regular,
            )
        }
        ShadowButton(
            text = "Clear search",
            onClick = onClear,
            variant = ShadowButtonVariant.Text,
            size = ShadowButtonSize.Regular,
        )
    }
}

@Composable
private fun ListError(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Shadow.colors
    CenteredState(
        icon = ShadowIcons.Warning,
        iconTint = colors.coralText,
        iconContainer = colors.coralTint,
        title = "Couldn't read your installed apps",
        message = "Android didn't return the app list. Your saved choices are safe and still apply.",
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Assertive },
    ) {
        ShadowButton(
            text = "Try again",
            onClick = onRetry,
            variant = ShadowButtonVariant.Secondary,
            size = ShadowButtonSize.Regular,
            icon = ShadowIcons.Refresh,
        )
    }
}

@Composable
private fun CenteredState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    iconTint: Color = Shadow.colors.ink3,
    iconContainer: Color = Shadow.colors.surface2,
    actions: @Composable () -> Unit,
) {
    val colors = Shadow.colors
    Column(
        modifier = modifier
            .fadeUpIn()
            .padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconTile(icon = icon, size = 56.dp, iconSize = 24.dp, tint = iconTint, container = iconContainer)
        Text(
            text = title,
            style = Shadow.type.titleS,
            color = colors.ink1,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 14.dp),
        )
        Text(
            text = message,
            style = Shadow.type.bodyS,
            color = colors.ink3,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(top = 6.dp)
                .widthIn(max = 290.dp),
        )
        Row(
            modifier = Modifier.padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) { actions() }
    }
}

@Composable
private fun RoutingFooter() {
    val colors = Shadow.colors
    val edge = colors.surface2
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // AppRouting.dc.html: the toast's bottom is 82 px up, 14 above the 68 dp footer.
            .toastObstacle(gap = 14.dp)
            .background(colors.navBg)
            .drawBehind { drawRect(edge, size = Size(size.width, 1.dp.toPx())) }
            .navigationBarsPadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Row(
            modifier = Modifier
                .widthIn(max = MAX_CONTENT_WIDTH)
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                ShadowIcons.Info,
                null,
                tint = colors.ink3,
                modifier = Modifier
                    .padding(top = 1.dp)
                    .size(16.dp),
            )
            Text(
                text = "Changes apply right away. An active connection reconnects to pick them up.",
                style = Shadow.type.bodyS,
                color = colors.ink3,
            )
        }
    }
}

/**
 * One cell of a list group spread over lazy items: surface-1 with the group's 1 dp line border (rounded
 * on the first and last cell) and a line-2 divider inset 16 dp above every cell but the first. The row
 * itself sits inside the border.
 */
@Composable
private fun Modifier.groupCell(first: Boolean, last: Boolean): Modifier {
    val colors = Shadow.colors
    val fill = colors.surface1
    val edge = colors.line
    val divider = colors.line2
    val shape = RoundedCornerShape(
        topStart = if (first) 18.dp else 0.dp,
        topEnd = if (first) 18.dp else 0.dp,
        bottomStart = if (last) 18.dp else 0.dp,
        bottomEnd = if (last) 18.dp else 0.dp,
    )
    return this
        .clip(shape)
        .drawWithCache {
            val stroke = 1.dp.toPx()
            val radius = 18.dp.toPx()
            val top = if (first) 0f else -radius * 2
            val bottom = if (last) size.height else size.height + radius * 2
            val outline = Path().apply {
                addRoundRect(
                    RoundRect(
                        rect = Rect(stroke / 2, top + stroke / 2, size.width - stroke / 2, bottom - stroke / 2),
                        cornerRadius = CornerRadius(radius - stroke / 2),
                    ),
                )
            }
            val inset = stroke + 16.dp.toPx()
            onDrawBehind {
                drawRect(fill)
                clipRect { drawPath(outline, edge, style = Stroke(stroke)) }
                if (!first) drawLine(divider, Offset(inset, stroke / 2), Offset(size.width - inset, stroke / 2), stroke)
            }
        }
        .padding(
            start = GROUP_BORDER,
            end = GROUP_BORDER,
            top = if (first) GROUP_BORDER else 0.dp,
            bottom = if (last) GROUP_BORDER else 0.dp,
        )
}

/** The staggered entrance of the first screenful of rows only. */
private fun Modifier.entrance(index: Int): Modifier =
    if (index < ShadowMotion.StaggerMax) fadeUpIn(index) else this

/** Keeps a lazy item to the content column (max 640 dp, centered) on wide windows. */
private fun Modifier.contentWidth(): Modifier = this
    .widthIn(max = MAX_CONTENT_WIDTH)
    .fillMaxWidth()

@Composable
private fun noticeIn(): EnterTransition =
    expandVertically(shadowTween(ShadowMotion.Surface), expandFrom = Alignment.Top) +
        fadeIn(shadowTween(ShadowMotion.Swap))

@Composable
private fun noticeOut(): ExitTransition =
    shrinkVertically(shadowTween(ShadowMotion.Surface), shrinkTowards = Alignment.Top) +
        fadeOut(shadowTween(ShadowMotion.Small))

/** Apps grid with a tick in the last cell ("Only selected apps"). */
private val AppsSelectedIcon = strokeIcon(
    "apps-selected",
    "M5.8 4h2.9a1.8 1.8 0 0 1 1.8 1.8v2.9a1.8 1.8 0 0 1 -1.8 1.8h-2.9a1.8 1.8 0 0 1 -1.8 -1.8" +
        "v-2.9a1.8 1.8 0 0 1 1.8 -1.8z",
    "M15.3 4h2.9a1.8 1.8 0 0 1 1.8 1.8v2.9a1.8 1.8 0 0 1 -1.8 1.8h-2.9a1.8 1.8 0 0 1 -1.8 -1.8" +
        "v-2.9a1.8 1.8 0 0 1 1.8 -1.8z",
    "M5.8 13.5h2.9a1.8 1.8 0 0 1 1.8 1.8v2.9a1.8 1.8 0 0 1 -1.8 1.8h-2.9a1.8 1.8 0 0 1 -1.8 -1.8" +
        "v-2.9a1.8 1.8 0 0 1 1.8 -1.8z",
    "M14 17.2l2.1 2.1 4.2-4.6",
)

private val SkeletonWidths = listOf(
    96.dp to 156.dp,
    128.dp to 184.dp,
    84.dp to 132.dp,
    140.dp to 170.dp,
    108.dp to 150.dp,
)

private val MAX_CONTENT_WIDTH = ShadowDimens.PaneMaxWidth
private val GROUP_BORDER = 1.dp
private const val MAX_AVATARS = 4
private val CHOICE_BORDER = 1.5.dp
private const val CHOICE_WASH = 0.06f
private const val CHOICE_RING = 0.10f
private const val CHOICE_PRESS = 0.98f
private const val CHOICE_MS = 260
private const val COUNT_MS = 300
private const val CHECKED_WASH = 0.06f
private const val SHIMMER_HIGHLIGHT = 0.06f
private const val SHIMMER_FROM = 1.5f
private const val SHIMMER_TO = -0.5f
