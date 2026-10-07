package com.stansful.sshvpnclient.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.stansful.sshvpnclient.ui.designsystem.CenteredToasts
import com.stansful.sshvpnclient.ui.designsystem.ConnectOrb
import com.stansful.sshvpnclient.ui.designsystem.OrbSize
import com.stansful.sshvpnclient.ui.designsystem.IconTile
import com.stansful.sshvpnclient.ui.designsystem.LatencyMeter
import com.stansful.sshvpnclient.ui.designsystem.ShadowButton
import com.stansful.sshvpnclient.ui.designsystem.ShadowButtonLabel
import com.stansful.sshvpnclient.ui.designsystem.ShadowButtonSize
import com.stansful.sshvpnclient.ui.designsystem.ShadowButtonVariant
import com.stansful.sshvpnclient.ui.designsystem.ShadowCard
import com.stansful.sshvpnclient.ui.designsystem.ShadowIconButton
import com.stansful.sshvpnclient.ui.designsystem.ShadowIcons
import com.stansful.sshvpnclient.ui.designsystem.ShadowProgressBar
import com.stansful.sshvpnclient.ui.designsystem.StatusDot
import com.stansful.sshvpnclient.ui.designsystem.StatusTone
import com.stansful.sshvpnclient.ui.designsystem.SwapText
import com.stansful.sshvpnclient.ui.designsystem.fadeUpIn
import com.stansful.sshvpnclient.ui.designsystem.shadowClickable
import com.stansful.sshvpnclient.ui.designsystem.toneText
import com.stansful.sshvpnclient.ui.designsystem.toneTint
import com.stansful.sshvpnclient.ui.theme.Shadow
import com.stansful.sshvpnclient.ui.theme.ShadowShapes
import java.util.Locale

/** TabletHome.dc.html: the connection pane and the 420 dp mode-details pane (Expanded width). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HomeTwoPane(
    state: HomeUiState,
    actions: HomeActions,
    onChip: (HomeChip) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Shadow.colors
    val hero = state.hero
    // TabletHome.dc.html: centred, content-width toasts.
    CenteredToasts()
    Row(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 24.dp, end = 24.dp, top = PANE_TOP, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            UpdateBannerSlot(state, actions, Modifier.widthIn(max = MODE_SWITCH_MAX).padding(bottom = 12.dp))
            ModeSwitch(
                mode = state.mode,
                onSelect = actions.onModeSelect,
                hintTopPadding = 12.dp,
                modifier = Modifier.widthIn(max = MODE_SWITCH_MAX),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(TABLET_ORB_SLOT),
                    contentAlignment = Alignment.Center,
                ) {
                    ConnectOrb(
                        state = hero.orb,
                        label = hero.orbLabel,
                        onClick = actions.onOrbClick,
                        contentDescription = hero.orbDescription,
                        enabled = hero.orbEnabled,
                        size = OrbSize.Tablet,
                    )
                }
                HeroStatus(
                    hero = hero,
                    large = true,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .widthIn(max = STATUS_MAX),
                )
                HomeNotes(
                    state = state,
                    actions = actions,
                    modifier = Modifier
                        .padding(top = 16.dp)
                        .widthIn(max = STATUS_MAX),
                )
                FlowRow(
                    modifier = Modifier
                        .padding(top = 28.dp)
                        .widthIn(max = CHIPS_MAX),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HomeChips(chips = state.tabletChips, onClick = onChip, tablet = true)
                }
                SwapText(
                    text = state.tabletChipNote,
                    style = Shadow.type.caption,
                    color = colors.ink3,
                    maxLines = 2,
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .widthIn(max = STATUS_MAX)
                        .heightIn(min = 16.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        Box(
            modifier = Modifier
                .width(ASIDE_WIDTH)
                .fillMaxHeight(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (state.mode) {
                    HomeMode.Auto -> AutoDetails(state)
                    HomeMode.Server -> ServerDetails(state, actions)
                    HomeMode.Routes -> RouteDetails(state, actions)
                }
                ActivityPanel(state, actions)
                AppRoutingCard(state, actions.onOpenAppRouting)
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(40.dp)
                    .background(Brush.verticalGradient(listOf(colors.bg.copy(alpha = 0f), colors.bg))),
            )
        }
    }
}

// region Auto

@Composable
private fun AutoDetails(state: HomeUiState) {
    val colors = Shadow.colors
    val auto = state.tabletAuto ?: return
    val card = state.card
    val ink = colors.statusInk(state.hero.tone)
    Column {
        SectionHeading(title = "Auto-picked route", caption = auto.caption)
        DetailCard {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconTile(icon = (card?.icon ?: CardIcon.Auto).vector(), tint = ink)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SwapText(text = card?.title.orEmpty(), style = Shadow.type.titleS, color = colors.ink1)
                    Text(
                        text = card?.subtitle.orEmpty(),
                        style = if (card?.subtitleMono != false) Shadow.type.monoS else Shadow.type.caption,
                        color = if (card?.subtitleWarning == true) colors.amberMuted else colors.ink3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (card?.showMeter == true) LatencyMeter(latencyMs = card.latencyMs)
            }
            auto.testingLabel?.let { label ->
                Column(modifier = Modifier.padding(top = 14.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(text = label, style = Shadow.type.caption, color = colors.ink2)
                        Text(
                            text = "${((auto.testingFraction ?: 0f) * 100).toInt()}%",
                            style = Shadow.type.monoS,
                            color = colors.amberText,
                        )
                    }
                    ShadowProgressBar(progress = auto.testingFraction, modifier = Modifier.padding(top = 8.dp))
                }
            }
            DetailGrid(
                cells = listOf(
                    DetailCell("Latency when picked", auto.pickedAt, mono = true),
                    DetailCell(
                        "Live check · youtube.com",
                        auto.live,
                        mono = true,
                        color = if (auto.liveTone == StatusTone.Neutral) {
                            colors.ink3
                        } else {
                            colors.toneText(auto.liveTone)
                        },
                    ),
                ),
                weights = listOf(1f, 1f),
            )
            if (auto.showUnprotected) {
                NoteBanner(
                    tone = StatusTone.Progress,
                    icon = ShadowIcons.Warning,
                    text = AnnotatedString("Traffic skips the VPN until Auto has connected."),
                    textColor = colors.amberText,
                    modifier = Modifier
                        .padding(top = 14.dp)
                        .fadeUpIn(),
                )
            }
        }
    }
    PaneCard {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Route pool",
                    style = Shadow.type.titleS,
                    color = colors.ink1,
                    modifier = Modifier.semantics { heading() },
                )
                SwapText(
                    text = auto.poolCaption,
                    style = Shadow.type.bodyS,
                    color = colors.ink3,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Row(
                modifier = Modifier
                    .height(28.dp)
                    .clip(CircleShape)
                    .background(colors.surface2)
                    .padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(ShadowIcons.Bolt, contentDescription = null, tint = colors.ink2, modifier = Modifier.size(14.dp))
                Text(
                    text = "Fastest first",
                    style = Shadow.type.label.copy(fontSize = Shadow.type.caption.fontSize),
                    color = colors.ink2,
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            auto.pool.forEachIndexed { index, row ->
                val picked = row.rank == auto.pickedRank
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(ShadowShapes.Banner)
                        .background(if (picked) colors.mint.copy(alpha = PICKED_ROW_ALPHA) else Color.Transparent)
                        .semantics(mergeDescendants = true) {
                            val latency = row.latencyMs?.let { "$it ms" } ?: "latency unknown"
                            contentDescription = "${row.name}, $latency" + if (picked) ", picked automatically" else ""
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .fadeUpIn(index),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                        if (picked) {
                            Icon(
                                ShadowIcons.CheckCircle,
                                contentDescription = null,
                                tint = colors.mintText,
                                modifier = Modifier.size(22.dp),
                            )
                        } else {
                            Text(
                                text = String.format(Locale.US, "%02d", row.rank),
                                style = Shadow.type.monoS,
                                color = colors.ink3,
                            )
                        }
                    }
                    RouteTexts(name = row.name, subtitle = row.subtitle, modifier = Modifier.weight(1f))
                    Row(modifier = Modifier.widthIn(min = 76.dp), horizontalArrangement = Arrangement.End) {
                        LatencyMeter(latencyMs = row.latencyMs)
                    }
                }
            }
        }
        NoteBanner(
            tone = StatusTone.Info,
            icon = ShadowIcons.Info,
            text = AnnotatedString("Auto picks for you and switches on its own if the route stops answering."),
            textColor = colors.skySoft,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
        )
    }
}

// endregion

// region Server

@Composable
private fun ServerDetails(state: HomeUiState, actions: HomeActions) {
    val colors = Shadow.colors
    val server = state.tabletServer
    if (state.firstRun || server == null) {
        Column {
            SectionHeading(title = "Selected server", caption = "Saved across restarts")
            FirstRunCard(onAddServer = actions.onAddServer)
        }
    } else {
        Column {
            SectionHeading(title = "Selected server", caption = "Saved across restarts")
            DetailCard {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconTile(icon = ShadowIcons.Server, tint = colors.statusInk(state.hero.tone))
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SwapText(text = server.name, style = Shadow.type.titleS, color = colors.ink1)
                        Text(
                            text = server.address,
                            style = Shadow.type.monoS,
                            color = colors.ink3,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    server.statusLabel?.let { label -> DotTag(label = label, tone = server.statusTone) }
                }
                DetailGrid(
                    cells = listOf(
                        DetailCell("Sign-in", server.signIn),
                        DetailCell(
                            label = "Fingerprint",
                            value = if (server.fingerprintSaved) "Saved" else "Missing",
                            color = if (server.fingerprintSaved) colors.mintText else colors.amberText,
                            icon = if (server.fingerprintSaved) ShadowIcons.Shield else ShadowIcons.Warning,
                        ),
                        DetailCell("DNS", "Via server"),
                    ),
                    weights = listOf(1.4f, 1f, 1f),
                )
                if (!server.fingerprintSaved) {
                    NoteBanner(
                        tone = StatusTone.Progress,
                        icon = ShadowIcons.Warning,
                        text = buildAnnotatedString {
                            append(
                                "No fingerprint saved, so this server's identity isn't checked. Connect once, " +
                                    "copy the fingerprint from Activity and paste it in ",
                            )
                            withLink(
                                LinkAnnotation.Clickable(
                                    tag = "edit",
                                    styles = TextLinkStyles(
                                        SpanStyle(color = colors.amberText, fontWeight = FontWeight.SemiBold),
                                    ),
                                ) { actions.onEditSelectedServer() },
                            ) { append("Edit server") }
                            append(".")
                        },
                        textColor = colors.amberText,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                }
            }
        }
    }
    if (state.serverOptions.isEmpty()) return
    PaneCard {
        PaneCardHeader(title = "Your servers", hint = server?.hint.orEmpty())
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            state.serverOptions.forEachIndexed { index, option ->
                PickRow(
                    selected = option.selected,
                    title = option.name,
                    subtitle = option.address,
                    contentDescription = "${option.name}, ${option.address}" +
                        if (option.verified) "" else ", host not verified",
                    onClick = { if (!option.selected) actions.onSelectServer(option.id) },
                    minHeight = 56.dp,
                    shape = ShadowShapes.Banner,
                    padding = PANE_ROW_PADDING,
                    bottomGap = 0.dp,
                    modifier = Modifier.fadeUpIn(index),
                ) {
                    if (!option.verified) {
                        Row(
                            modifier = Modifier
                                .height(24.dp)
                                .clip(CircleShape)
                                .background(colors.amberTint)
                                .padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                ShadowIcons.Warning,
                                contentDescription = null,
                                tint = colors.amberText,
                                modifier = Modifier.size(12.dp),
                            )
                            Text(text = "Unverified", style = TagLabel, color = colors.amberText)
                        }
                    }
                    PlainTag(option.signIn)
                }
            }
        }
        Row(
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ShadowButton(
                text = "Manage servers",
                onClick = actions.onManageServers,
                variant = ShadowButtonVariant.Secondary,
                size = PaneButton,
                icon = ShadowIcons.Server,
                modifier = Modifier.weight(1f),
            )
            ShadowButton(
                text = "Keys",
                onClick = actions.onOpenKeys,
                variant = ShadowButtonVariant.Secondary,
                size = PaneButton,
                icon = ShadowIcons.Key,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// endregion

// region Routes

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RouteDetails(state: HomeUiState, actions: HomeActions) {
    val colors = Shadow.colors
    val route = state.tabletRoute
    Column {
        SectionHeading(title = "Active route", caption = "From your library")
        val card = state.card
        if (route == null) {
            if (card != null) {
                HomeModeCard(card = card, onClick = actions.onOpenRouteLibrary, showOverline = false)
            }
        } else {
            DetailCard {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconTile(icon = ShadowIcons.Routes, tint = colors.statusInk(state.hero.tone))
                    RouteTexts(
                        name = route.name,
                        subtitle = route.subtitle,
                        large = true,
                        modifier = Modifier.weight(1f),
                    )
                    LatencyMeter(latencyMs = route.latencyMs)
                }
                FlowRow(
                    modifier = Modifier
                        .padding(top = 14.dp)
                        .fillMaxWidth()
                        .topDivider(colors.line2)
                        .padding(top = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .height(24.dp)
                            .clip(CircleShape)
                            .background(colors.surface2)
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = route.transport, style = Shadow.type.monoS, color = colors.ink2)
                    }
                    if (route.pinned) {
                        Row(
                            modifier = Modifier
                                .height(24.dp)
                                .clip(CircleShape)
                                .background(colors.skyTint)
                                .padding(horizontal = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                ShadowIcons.Pin,
                                contentDescription = null,
                                tint = colors.skyText,
                                modifier = Modifier.size(12.dp),
                            )
                            Text(text = "Pinned", style = TagLabel, color = colors.skyText)
                        }
                    }
                    if (route.manual) PlainTag("Added by you", height = 24)
                    Spacer(Modifier.weight(1f))
                    Text(text = route.note, style = Shadow.type.caption, color = colors.ink3, maxLines = 1)
                }
            }
        }
    }
    PaneCard {
        PaneCardHeader(title = "Pinned and fastest", hint = route?.hint ?: "Pinned first, then by latency")
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            state.routeOptions.forEachIndexed { index, option ->
                PickRow(
                    selected = option.selected,
                    title = option.name,
                    subtitle = option.subtitle,
                    contentDescription = option.name + (if (option.pinned) ", pinned" else "") + ", " +
                        (option.latencyMs?.let { "$it ms" } ?: "not checked"),
                    onClick = { if (!option.selected) actions.onSwitchRoute(option.id) },
                    minHeight = 56.dp,
                    shape = ShadowShapes.Banner,
                    padding = PANE_ROW_PADDING,
                    bottomGap = 0.dp,
                    modifier = Modifier.fadeUpIn(index),
                    titleTrailing = if (option.pinned) {
                        {
                            Icon(
                                ShadowIcons.Pin,
                                contentDescription = null,
                                tint = colors.skyText,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    } else {
                        null
                    },
                ) {
                    Row(modifier = Modifier.widthIn(min = 76.dp), horizontalArrangement = Arrangement.End) {
                        LatencyMeter(latencyMs = option.latencyMs)
                    }
                }
            }
        }
        route?.counts?.let {
            Text(
                text = it,
                style = Shadow.type.caption,
                color = colors.ink3,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            )
        }
        ShadowButton(
            text = "Open route library",
            onClick = actions.onOpenRouteLibrary,
            variant = ShadowButtonVariant.Secondary,
            size = PaneButton,
            icon = ShadowIcons.Routes,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, bottom = 4.dp, top = if (route == null) 8.dp else 0.dp),
        )
    }
}

// endregion

// region Activity and app routing

@Composable
private fun ActivityPanel(state: HomeUiState, actions: HomeActions) {
    val colors = Shadow.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ShadowShapes.Card)
            .background(colors.surface1)
            .border(1.dp, colors.line, ShadowShapes.Card)
            .padding(start = 17.dp, end = 13.dp, top = 13.dp, bottom = 15.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon = ShadowIcons.Activity, size = 36.dp, iconSize = 18.dp, cornerRadius = 12.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Activity",
                    style = Shadow.type.titleS,
                    color = colors.ink1,
                    modifier = Modifier.semantics { heading() },
                )
                SwapText(
                    text = "${lineCount(state.activityLineCount)} · all modes",
                    style = Shadow.type.bodyS,
                    color = colors.ink3,
                )
            }
            if (state.showActivity) {
                ShadowIconButton(
                    icon = ShadowIcons.Copy,
                    contentDescription = "Copy activity",
                    onClick = actions.onCopyActivity,
                    iconSize = 20.dp,
                )
            }
            val interaction = remember { MutableInteractionSource() }
            Row(
                modifier = Modifier
                    .height(44.dp)
                    .clip(ShadowShapes.IconButton)
                    .shadowClickable(interactionSource = interaction, onClick = actions.onOpenActivity)
                    .padding(start = 10.dp, end = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "Open activity", style = Shadow.type.label, color = colors.amberText)
                Icon(
                    ShadowIcons.ChevronRight,
                    contentDescription = null,
                    tint = colors.amberText,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        if (state.showActivity) {
            Column(
                modifier = Modifier
                    .padding(top = 10.dp)
                    .fillMaxWidth()
                    .clip(ShadowShapes.Tile)
                    .background(colors.navBg)
                    .border(1.dp, colors.navLine, ShadowShapes.Tile)
                    .semantics { contentDescription = "Last ${state.activityLines.size} lines of connection activity" }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                if (state.activityLines.isEmpty()) {
                    Text(
                        text = "Nothing yet. Steps appear here when you connect.",
                        style = LogText,
                        color = colors.ink3,
                    )
                }
                state.activityLines.forEach { line ->
                    val time = line.substringBefore(' ', "")
                    val message = if (time.length == LOG_TIME_LENGTH) line.substringAfter(' ') else line
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (time.length == LOG_TIME_LENGTH) Text(text = time, style = LogText, color = colors.ink3)
                        Text(
                            text = message,
                            style = LogText,
                            color = logColor(message),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        } else {
            NoteBanner(
                tone = StatusTone.Info,
                icon = ShadowIcons.Info,
                text = buildAnnotatedString {
                    append("Activity is still recorded, just not shown on Home. Turn on ")
                    withLink(
                        LinkAnnotation.Clickable(
                            tag = "settings",
                            styles = TextLinkStyles(
                                SpanStyle(color = colors.skyText, fontWeight = FontWeight.SemiBold),
                            ),
                        ) { actions.onOpenSettings() },
                    ) { append("Show connection activity") }
                    append(" in Settings.")
                },
                textColor = colors.skySoft,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun logColor(message: String): Color {
    val colors = Shadow.colors
    return when {
        message.contains("WARNING") -> colors.amberText
        message.contains("succeeded") || message.contains("is connected") || message.contains("verified") ->
            colors.mintText
        else -> colors.ink2
    }
}

private fun lineCount(count: Int): String = if (count == 1) "1 line" else "$count lines"

@Composable
private fun AppRoutingCard(state: HomeUiState, onClick: () -> Unit) {
    val colors = Shadow.colors
    ShadowCard(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "App routing: ${state.appRoutingSummary}"
            },
        onClick = onClick,
        role = Role.Button,
        contentPadding = PaddingValues(15.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon = ShadowIcons.Apps)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = "App routing", style = Shadow.type.titleS, color = colors.ink1)
                Text(
                    text = state.appRoutingSummary,
                    style = Shadow.type.bodyS,
                    color = colors.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            PlainTag(state.appRoutingTag)
            Icon(
                ShadowIcons.ChevronRight,
                contentDescription = null,
                tint = colors.ink3,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

// endregion

// region Pieces

// Card paddings below are the artboard's padding + its 1 px border (CSS draws the border outside the
// padding; Compose's `border` draws inside the bounds).

@Composable
private fun SectionHeading(title: String, caption: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Overline(text = title, modifier = Modifier.semantics { heading() })
        SwapText(text = caption, style = Shadow.type.caption, color = Shadow.colors.ink3)
    }
}

@Composable
private fun DetailCard(content: @Composable () -> Unit) {
    val colors = Shadow.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ShadowShapes.Card)
            .background(colors.surface1)
            .border(1.dp, colors.line, ShadowShapes.Card)
            .padding(17.dp),
    ) { content() }
}

@Composable
private fun PaneCard(content: @Composable () -> Unit) {
    val colors = Shadow.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ShadowShapes.Card)
            .background(colors.surface1)
            .border(1.dp, colors.line, ShadowShapes.Card)
            .padding(9.dp),
    ) { content() }
}

@Composable
private fun PaneCardHeader(title: String, hint: String) {
    Column(modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 10.dp)) {
        Text(
            text = title,
            style = Shadow.type.titleS,
            color = Shadow.colors.ink1,
            modifier = Modifier.semantics { heading() },
        )
        SwapText(
            text = hint,
            style = Shadow.type.bodyS,
            color = Shadow.colors.ink3,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

private data class DetailCell(
    val label: String,
    val value: String,
    val mono: Boolean = false,
    val color: Color = Color.Unspecified,
    val icon: ImageVector? = null,
)

@Composable
private fun DetailGrid(cells: List<DetailCell>, weights: List<Float>) {
    val colors = Shadow.colors
    Row(
        modifier = Modifier
            .padding(top = 14.dp)
            .fillMaxWidth()
            .topDivider(colors.line2)
            .padding(top = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        cells.forEachIndexed { index, cell ->
            Column(modifier = Modifier.weight(weights[index])) {
                Text(
                    text = cell.label,
                    style = Shadow.type.caption,
                    color = colors.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val valueColor = if (cell.color == Color.Unspecified) colors.ink1 else cell.color
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    cell.icon?.let { icon ->
                        Icon(icon, contentDescription = null, tint = valueColor, modifier = Modifier.size(14.dp))
                    }
                    SwapText(
                        text = cell.value,
                        style = if (cell.mono) Shadow.type.monoMedium else Shadow.type.label,
                        color = valueColor,
                    )
                }
            }
        }
    }
}

@Composable
private fun DotTag(label: String, tone: StatusTone) {
    val colors = Shadow.colors
    Row(
        modifier = Modifier
            .height(24.dp)
            .clip(CircleShape)
            .background(colors.toneTint(tone))
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(color = colors.toneText(tone), size = 6.dp)
        Text(text = label, style = TagLabel, color = colors.toneText(tone))
    }
}

@Composable
private fun PlainTag(text: String, height: Int? = null) {
    val colors = Shadow.colors
    Box(
        modifier = Modifier
            .then(if (height != null) Modifier.height(height.dp) else Modifier)
            .clip(CircleShape)
            .background(colors.surface3)
            .padding(horizontal = 10.dp, vertical = if (height == null) 4.dp else 0.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = TagLabel, color = colors.ink2, maxLines = 1)
    }
}

@Composable
private fun RouteTexts(name: String, subtitle: String, modifier: Modifier = Modifier, large: Boolean = false) {
    val colors = Shadow.colors
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(if (large) 4.dp else 3.dp)) {
        Text(
            text = name,
            style = if (large) Shadow.type.titleS else Shadow.type.rowTitle.copy(fontWeight = FontWeight.SemiBold),
            color = colors.ink1,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = subtitle,
            style = Shadow.type.monoS,
            color = colors.ink3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val TagLabel
    @Composable get() = Shadow.type.label.copy(
        fontSize = Shadow.type.caption.fontSize,
        lineHeight = Shadow.type.caption.lineHeight,
    )

private val LogText
    @Composable get() = Shadow.type.monoS.copy(lineHeight = Shadow.type.label.lineHeight)

/** TabletHome's 40 dp pane buttons (radius 14, 13/600). */
private val PaneButton = ShadowButtonSize(40.dp, 16.dp, 16.dp, 14.dp, ShadowButtonLabel.Small)

// endregion

private val PANE_TOP = 42.dp
private val MODE_SWITCH_MAX = 460.dp
private val STATUS_MAX = 560.dp
private val CHIPS_MAX = 620.dp
private val ASIDE_WIDTH = 420.dp
private val TABLET_ORB_SLOT = 336.dp

/** TabletHome's pane rows: `min-height:56px; padding:8px 10px`. */
private val PANE_ROW_PADDING = PaddingValues(horizontal = 10.dp, vertical = 8.dp)

private const val PICKED_ROW_ALPHA = 0.08f
private const val LOG_TIME_LENGTH = 8
