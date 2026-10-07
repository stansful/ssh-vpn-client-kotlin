package com.stansful.sshvpnclient.ui.servers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stansful.sshvpnclient.domain.model.AuthType
import com.stansful.sshvpnclient.ui.configs.ConfigListItem
import com.stansful.sshvpnclient.ui.configs.ConfigListUiState
import com.stansful.sshvpnclient.ui.designsystem.BannerTone
import com.stansful.sshvpnclient.ui.designsystem.GlowDot
import com.stansful.sshvpnclient.ui.designsystem.InlineBanner
import com.stansful.sshvpnclient.ui.designsystem.ShadowButtonLabel
import com.stansful.sshvpnclient.ui.designsystem.ShadowButtonSize
import com.stansful.sshvpnclient.ui.designsystem.ShadowDialog
import com.stansful.sshvpnclient.ui.designsystem.ShadowFocusIndication
import com.stansful.sshvpnclient.ui.designsystem.ShadowIconButton
import com.stansful.sshvpnclient.ui.designsystem.ShadowIcons
import com.stansful.sshvpnclient.ui.designsystem.ShadowMenu
import com.stansful.sshvpnclient.ui.designsystem.ShadowMenuItem
import com.stansful.sshvpnclient.ui.designsystem.ShadowMenuStyle
import com.stansful.sshvpnclient.ui.designsystem.ShadowRadio
import com.stansful.sshvpnclient.ui.designsystem.ShadowTextButton
import com.stansful.sshvpnclient.ui.designsystem.StatusDot
import com.stansful.sshvpnclient.ui.designsystem.SwapText
import com.stansful.sshvpnclient.ui.designsystem.fadeUpIn
import com.stansful.sshvpnclient.ui.designsystem.pressScale
import com.stansful.sshvpnclient.ui.theme.Shadow
import com.stansful.sshvpnclient.ui.theme.ShadowMotion
import com.stansful.sshvpnclient.ui.theme.ShadowShapes
import com.stansful.sshvpnclient.ui.theme.shadowTween

@Composable
internal fun ServersTabContent(
    state: ConfigListUiState,
    connection: ServerConnection?,
    shake: ShakeRequest?,
    actions: ServersActions,
    listState: LazyListState,
) {
    val items = state.items
    val activeId = connection?.configId
    val activeItem = items.firstOrNull { it.config.id == activeId }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .selectableGroup(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
    ) {
        item(key = "strip") {
            ConnectionStrip(
                connection = connection,
                serverName = activeItem?.config?.name ?: activeId?.let { state.removedNames[it] },
                serverDeleted = state.isLoaded && activeId != null && activeItem == null,
                onDisconnect = actions.onDisconnect,
                modifier = Modifier.fadeUpIn(2),
            )
        }
        if (!state.isLoaded) return@LazyColumn
        if (items.isEmpty()) {
            item(key = "empty") {
                ListEmptyState(
                    icon = ShadowIcons.Server,
                    title = "No servers yet",
                    message = "Add your first SSH server to use Server mode.",
                    actionLabel = "Add server",
                    onAction = actions.onAddServer,
                    modifier = Modifier.fadeUpIn(3),
                    messageMaxWidth = 260.dp,
                )
            }
            return@LazyColumn
        }
        item(key = "header") {
            ListHeader(
                title = serversCountLabel(items.size),
                note = "Recently edited first",
                modifier = Modifier.fadeUpIn(3),
            )
        }
        itemsIndexed(items, key = { _, item -> item.config.id }) { index, item ->
            ServerCard(
                item = item,
                live = connection?.phase?.takeIf { item.isSelected && item.config.id == activeId },
                shakeTrigger = shake?.takeIf { it.id == item.config.id }?.nonce ?: 0,
                onSelect = { actions.onSelectServer(item) },
                onEdit = { actions.onEditServer(item.config.id) },
                onDelete = { actions.onAskDeleteServer(item.config.id) },
                modifier = Modifier
                    .animateItem(
                        fadeInSpec = null,
                        placementSpec = shadowTween(ShadowMotion.Surface),
                        fadeOutSpec = shadowTween(LEAVE_FADE_MS, ShadowMotion.Exit),
                    )
                    .fadeUpIn(4 + index),
            )
        }
        item(key = "footer") {
            if (items.any { it.isSelected }) {
                SelectionHint(modifier = Modifier.fadeUpIn(4 + items.size))
            } else {
                InlineBanner(
                    message = "No server is used for Server mode yet. Tap one to pick it.",
                    tone = BannerTone.Warning,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .fadeUpIn(4 + items.size),
                )
            }
        }
    }
}

/** "Connected via Home VPS" strip with Disconnect, shown while a Server-mode session is up. */
@Composable
private fun ConnectionStrip(
    connection: ServerConnection?,
    serverName: String?,
    serverDeleted: Boolean,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lastShown = remember { arrayOfNulls<ServerConnection>(1) }
    if (connection != null) lastShown[0] = connection
    AnimatedVisibility(
        visible = connection != null,
        modifier = modifier,
        enter = expandVertically(shadowTween(ShadowMotion.Surface)) + fadeIn(shadowTween(ShadowMotion.Small)),
        exit = shrinkVertically(shadowTween(ShadowMotion.Surface)) + fadeOut(shadowTween(ShadowMotion.Small)),
    ) {
        (connection ?: lastShown[0])?.let { session ->
            StripContent(
                phase = session.phase,
                serverName = serverName,
                serverDeleted = serverDeleted,
                onDisconnect = onDisconnect,
            )
        }
    }
}

@Composable
private fun StripContent(
    phase: ConnectionPhase,
    serverName: String?,
    serverDeleted: Boolean,
    onDisconnect: () -> Unit,
) {
    val colors = Shadow.colors
    val connected = phase == ConnectionPhase.Connected
    val tone = if (connected) colors.mint else colors.amber
    val line by animateColorAsState(
        targetValue = tone.copy(alpha = if (connected) MINT_LINE_ALPHA else AMBER_LINE_ALPHA),
        animationSpec = shadowTween(ShadowMotion.ColorFade, ShadowMotion.Ease),
        label = "strip-line",
    )
    val title = when (phase) {
        ConnectionPhase.Connected -> serverName?.let { "Connected via $it" } ?: "Connected"
        ConnectionPhase.Connecting -> "Connecting…"
        ConnectionPhase.Reconnecting -> "Reconnecting…"
    }
    val subtitle = when {
        serverDeleted -> "Deleted · stays up until you disconnect"
        !connected && serverName != null -> "Through $serverName"
        else -> null
    }
    Box(modifier = Modifier.padding(bottom = 16.dp)) {
        // Paddings include the 1 dp border, which the artboard draws outside its 6/6/6/16 padding.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = STRIP_MIN_HEIGHT)
                .dayCardShadow(colors)
                .clip(ShadowShapes.Card)
                .background(colors.surface1)
                .border(1.dp, line, ShadowShapes.Card)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .padding(start = 17.dp, end = 5.dp, top = 5.dp, bottom = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StripDot(color = tone, phase = phase)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                SwapText(
                    text = title,
                    style = Shadow.type.segment.copy(lineHeight = 20.sp),
                    color = colors.ink1,
                    maxLines = 2,
                )
                if (subtitle != null) {
                    SwapText(text = subtitle, style = Shadow.type.caption, color = colors.ink3, maxLines = 2)
                }
            }
            ShadowTextButton(
                text = if (connected) "Disconnect" else "Stop",
                onClick = onDisconnect,
                size = StripActionSize,
            )
        }
    }
}

/** 10 dp glowing state dot; blinks while connecting, rings once when the session comes up. */
@Composable
private fun StripDot(color: Color, phase: ConnectionPhase) {
    val reduced = Shadow.reducedMotion
    val ripple = remember { Animatable(1f) }
    var previous by remember { mutableStateOf(phase) }
    LaunchedEffect(phase) {
        val arrived = phase == ConnectionPhase.Connected && previous != ConnectionPhase.Connected
        previous = phase
        if (arrived && !reduced) {
            ripple.snapTo(0f)
            ripple.animateTo(1f, tween(ShadowMotion.Ripple, easing = ShadowMotion.Standard))
        }
    }
    // Read in the layer block: the blink redraws the layer instead of recomposing every frame.
    val blink = if (phase != ConnectionPhase.Connected && !reduced) {
        rememberInfiniteTransition(label = "strip-blink").animateFloat(
            initialValue = 1f,
            targetValue = BLINK_MIN_ALPHA,
            animationSpec = infiniteRepeatable(
                animation = tween(ShadowMotion.Blink / 2, easing = ShadowMotion.EaseInOut),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "strip-blink-alpha",
        )
    } else {
        null
    }
    val ringColor = Shadow.colors.mint
    Box(modifier = Modifier.size(10.dp), contentAlignment = Alignment.Center) {
        if (ripple.value < 1f) {
            Box(
                modifier = Modifier
                    .requiredSize(24.dp)
                    .graphicsLayer {
                        val progress = ripple.value
                        val scale = RIPPLE_START_SCALE + (RIPPLE_END_SCALE - RIPPLE_START_SCALE) * progress
                        scaleX = scale
                        scaleY = scale
                        alpha = RIPPLE_START_ALPHA * (1f - progress)
                    }
                    .border(2.dp, ringColor, CircleShape),
            )
        }
        GlowDot(color = color, modifier = Modifier.graphicsLayer { alpha = blink?.value ?: 1f })
    }
}

@Composable
private fun ServerCard(
    item: ConfigListItem,
    live: ConnectionPhase?,
    shakeTrigger: Int,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Shadow.colors
    val selected = item.isSelected
    val border by animateColorAsState(
        targetValue = if (selected) colors.amber.copy(alpha = SELECTED_BORDER_ALPHA) else colors.line,
        animationSpec = shadowTween(CARD_BORDER_MS, ShadowMotion.Ease),
        label = "server-card-border",
    )
    val glow by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = shadowTween(CARD_GLOW_MS, ShadowMotion.Ease),
        label = "server-card-glow",
    )
    val glowColor = colors.amber.copy(alpha = SELECTED_GLOW_ALPHA)
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .padding(bottom = 12.dp)
            .then(shakeModifier(shakeTrigger)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .pressScale(interactionSource, CARD_PRESS_SCALE)
                .dayCardShadow(colors)
                .clip(ShadowShapes.Card)
                .background(colors.surface1)
                .drawBehind {
                    if (glow > 0f) {
                        drawRect(
                            brush = Brush.verticalGradient(
                                0f to glowColor.copy(alpha = glowColor.alpha * glow),
                                GLOW_STOP to Color.Transparent,
                            ),
                        )
                    }
                }
                .border(1.dp, border, ShadowShapes.Card),
        ) {
            ServerCardBody(
                item = item,
                live = live,
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = selected,
                        interactionSource = interactionSource,
                        indication = ShadowFocusIndication,
                        role = Role.RadioButton,
                        onClick = onSelect,
                    )
                    // 14 dp padding inside the 1 dp border, as drawn.
                    .padding(15.dp),
            )
            ServerCardMenu(
                name = item.config.name,
                onEdit = onEdit,
                onDelete = onDelete,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp),
            )
        }
    }
}

/** Radio, name, `user@host:port`, the tags, the note and (selected) the "Used for Server mode" foot. */
@Composable
private fun ServerCardBody(item: ConfigListItem, live: ConnectionPhase?, modifier: Modifier) {
    val colors = Shadow.colors
    val config = item.config
    Column(modifier) {
        Row(
            modifier = Modifier.padding(end = 36.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ShadowRadio(selected = item.isSelected, onClick = null)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = config.name,
                    style = Shadow.type.titleS,
                    color = colors.ink1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${config.username}@${config.host}:${config.port}",
                    style = Shadow.type.monoS,
                    color = colors.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        FlowRow(
            modifier = Modifier.padding(top = 12.dp, start = 34.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AuthTag(item)
            HostIdentityTag(pinned = !config.fingerprint.isNullOrBlank())
            InfoTag(icon = ShadowIcons.Clock) {
                Text(text = "Keepalive", style = TagTextStyle, color = colors.ink2)
                Text(text = "${config.keepAliveIntervalSec} s", style = TagMonoStyle, color = colors.ink2)
            }
        }
        val note = config.note?.takeIf { it.isNotBlank() }
        if (note != null) {
            Text(
                text = note,
                style = Shadow.type.bodyS,
                color = colors.ink3,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 10.dp, start = 34.dp),
            )
        }
        AnimatedVisibility(
            visible = item.isSelected,
            enter = expandVertically(shadowTween(ShadowMotion.Surface)) + fadeIn(shadowTween(FOOT_FADE_MS)),
            exit = shrinkVertically(shadowTween(ShadowMotion.Surface)) + fadeOut(shadowTween(FOOT_FADE_MS)),
        ) {
            SelectedFoot(live = live)
        }
    }
}

/** The card's ⋮ button and its Edit / Delete menu. */
@Composable
private fun ServerCardMenu(
    name: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier,
) {
    val colors = Shadow.colors
    var menuOpen by remember { mutableStateOf(false) }
    Box(modifier) {
        ShadowIconButton(
            icon = ShadowIcons.More,
            contentDescription = "Server actions, $name",
            onClick = { menuOpen = true },
            selected = menuOpen,
            tint = if (menuOpen) colors.ink1 else colors.ink3,
            iconSize = 20.dp,
        )
        ShadowMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            style = ShadowMenuStyle.Compact,
            modifier = Modifier.width(SERVER_MENU_WIDTH),
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

@Composable
private fun AuthTag(item: ConfigListItem) {
    val colors = Shadow.colors
    when {
        item.config.authType == AuthType.PASSWORD -> InfoTag(icon = ShadowIcons.Lock) {
            Text(text = "Password", style = TagTextStyle, color = colors.ink2)
        }
        item.keyName != null -> InfoTag(icon = ShadowIcons.Key) {
            Text(
                text = item.keyName,
                style = TagMonoStyle,
                color = colors.ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        else -> InfoTag(icon = ShadowIcons.Key, iconTint = colors.coralText, container = colors.coralTint) {
            Text(text = "Missing key", style = TagTextStyle, color = colors.coralText)
        }
    }
}

@Composable
private fun HostIdentityTag(pinned: Boolean) {
    val colors = Shadow.colors
    if (pinned) {
        InfoTag(icon = ShadowIcons.Fingerprint, iconTint = colors.mintText) {
            Text(text = "Host verified", style = TagTextStyle, color = colors.ink2)
        }
    } else {
        InfoTag(icon = ShadowIcons.Warning, iconTint = colors.amberText, container = colors.amberTint) {
            Text(text = "Host not pinned", style = TagTextStyle, color = colors.amberText)
        }
    }
}

/** 22 dp pill tag of the server card: 14 dp icon + 12/16 600 text (surface-3 by default). */
@Composable
private fun InfoTag(
    icon: ImageVector,
    iconTint: Color = Shadow.colors.ink3,
    container: Color = Shadow.colors.surface3,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier
            .height(22.dp)
            .clip(ShadowShapes.Pill)
            .background(container)
            .padding(start = 7.dp, end = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(14.dp))
        content()
    }
}

/** "Used for Server mode" foot of the selected card, with the live session pill. */
@Composable
private fun SelectedFoot(live: ConnectionPhase?) {
    val colors = Shadow.colors
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .padding(top = 12.dp)
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.line),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Used for Server mode",
                style = Shadow.type.label,
                color = colors.amberText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 34.dp),
            )
            if (live != null) LivePill(phase = live)
        }
    }
}

@Composable
private fun LivePill(phase: ConnectionPhase) {
    val colors = Shadow.colors
    val connected = phase == ConnectionPhase.Connected
    val ink = if (connected) colors.mintText else colors.amberText
    Row(
        modifier = Modifier
            .height(22.dp)
            .clip(ShadowShapes.Pill)
            .background(if (connected) colors.mintTint else colors.amberTint)
            .padding(horizontal = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(color = ink, blink = !connected, size = 6.dp)
        Text(
            text = when (phase) {
                ConnectionPhase.Connected -> "Connected"
                ConnectionPhase.Connecting -> "Connecting"
                ConnectionPhase.Reconnecting -> "Reconnecting"
            },
            style = TagTextStyle,
            color = ink,
        )
    }
}

@Composable
private fun SelectionHint(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(ShadowIcons.Info, contentDescription = null, tint = Shadow.colors.ink3, modifier = Modifier.size(16.dp))
        Text(
            text = "Tap a server to use it in Server mode.",
            style = Shadow.type.bodyS,
            color = Shadow.colors.ink3,
            textAlign = TextAlign.Center,
        )
    }
}

/** Delete confirmation of the server the user picked in its menu. */
@Composable
internal fun ServerDeleteDialogHost(
    state: ConfigListUiState,
    connection: ServerConnection?,
    actions: ServersActions,
) {
    val item = state.items.firstOrNull { it.config.id == state.pendingDeleteId } ?: return
    val config = item.config
    val body = if (config.authType == AuthType.PRIVATE_KEY && item.keyName != null) {
        "Key ${item.keyName} stays in Keys, so you can reuse it."
    } else {
        "Its saved password is removed too. Keys stay in Keys."
    }
    val note = when {
        connection != null && connection.configId == config.id ->
            "You stay connected through it until you disconnect. Then pick another server."
        item.isSelected -> "Server mode will have no server until you pick another one."
        else -> null
    }
    ShadowDialog(
        onDismissRequest = actions.onCancelDeleteServer,
        title = "Delete ${config.name}?",
        message = body,
        confirmLabel = "Delete",
        onConfirm = actions.onConfirmDeleteServer,
        destructive = true,
        icon = ShadowIcons.Trash,
        confirmLoading = state.isDeleting,
    ) {
        if (note != null) {
            InlineBanner(
                message = note,
                tone = BannerTone.Info,
                tintedMessage = true,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        if (state.deleteFailed) {
            InlineBanner(
                message = "Couldn’t delete it: storage didn’t respond. Try again.",
                tone = BannerTone.Error,
                tintedMessage = true,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

/** Amber text action of the connection strip (15/600, 12 dp side padding, 44 dp touch height). */
private val StripActionSize = ShadowButtonSize(44.dp, 12.dp, 18.dp, 12.dp, ShadowButtonLabel.Large)
private val STRIP_MIN_HEIGHT = 54.dp
private val SERVER_MENU_WIDTH = 196.dp
private const val CARD_PRESS_SCALE = 0.985f
private const val SELECTED_BORDER_ALPHA = 0.42f
private const val SELECTED_GLOW_ALPHA = 0.07f
private const val GLOW_STOP = 0.64f
private const val CARD_BORDER_MS = 360
private const val CARD_GLOW_MS = 420
private const val FOOT_FADE_MS = 260
private const val LEAVE_FADE_MS = 220
private const val MINT_LINE_ALPHA = 0.22f
private const val AMBER_LINE_ALPHA = 0.26f
private const val BLINK_MIN_ALPHA = 0.35f
private const val RIPPLE_START_SCALE = 0.78f
private const val RIPPLE_END_SCALE = 1.45f
private const val RIPPLE_START_ALPHA = 0.7f
