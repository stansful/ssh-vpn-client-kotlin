package com.stansful.sshvpnclient.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stansful.sshvpnclient.ui.designsystem.ChipTone
import com.stansful.sshvpnclient.ui.designsystem.DISABLED_ALPHA
import com.stansful.sshvpnclient.ui.designsystem.IconTile
import com.stansful.sshvpnclient.ui.designsystem.LatencyMeter
import com.stansful.sshvpnclient.ui.designsystem.PrimaryButton
import com.stansful.sshvpnclient.ui.designsystem.ShadowButton
import com.stansful.sshvpnclient.ui.designsystem.ShadowButtonSize
import com.stansful.sshvpnclient.ui.designsystem.ShadowCard
import com.stansful.sshvpnclient.ui.designsystem.ShadowChip
import com.stansful.sshvpnclient.ui.designsystem.ShadowIconButton
import com.stansful.sshvpnclient.ui.designsystem.ShadowIcons
import com.stansful.sshvpnclient.ui.designsystem.StatusDot
import com.stansful.sshvpnclient.ui.designsystem.StatusPill
import com.stansful.sshvpnclient.ui.designsystem.StatusTone
import com.stansful.sshvpnclient.ui.designsystem.fadeUpIn
import com.stansful.sshvpnclient.ui.designsystem.formatLatency
import com.stansful.sshvpnclient.ui.designsystem.shadowClickable
import com.stansful.sshvpnclient.ui.designsystem.toneText
import com.stansful.sshvpnclient.ui.designsystem.toneTint
import com.stansful.sshvpnclient.ui.theme.JetBrainsMonoFamily
import com.stansful.sshvpnclient.ui.theme.Shadow
import com.stansful.sshvpnclient.ui.theme.ShadowColors
import com.stansful.sshvpnclient.ui.theme.ShadowDimens
import com.stansful.sshvpnclient.ui.theme.ShadowMotion
import com.stansful.sshvpnclient.ui.theme.ShadowShapes
import com.stansful.sshvpnclient.ui.theme.shadowTween

// region Status

/** The pill of [hero]; the retry pill sets its clock in mono (Onest elsewhere). */
@Composable
internal fun HeroPill(hero: HomeHero, modifier: Modifier = Modifier) {
    val clock = hero.pillClock
    if (clock == null) {
        StatusPill(label = hero.pillLabel, tone = hero.tone, blink = hero.pillBlink, modifier = modifier)
        return
    }
    val colors = Shadow.colors
    val ink = colors.toneText(hero.tone)
    Row(
        modifier = modifier
            .height(28.dp)
            .clip(CircleShape)
            .background(colors.toneTint(hero.tone))
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(color = ink, blink = hero.pillBlink)
        Text(
            text = buildAnnotatedString {
                append(hero.pillLabel)
                append(" ")
                withStyle(SpanStyle(fontFamily = JetBrainsMonoFamily, fontWeight = FontWeight.Medium)) { append(clock) }
            },
            style = Shadow.type.label,
            color = ink,
            maxLines = 1,
        )
    }
}

/** The subline under the headline: ink-3 (or amber-muted), numbers in mono ink-2. */
@Composable
internal fun HeroSubline(
    parts: List<TextPart>,
    tone: SublineTone,
    modifier: Modifier = Modifier,
    large: Boolean = false,
) {
    val colors = Shadow.colors
    val color by animateColorAsState(
        targetValue = if (tone == SublineTone.Warning) colors.amberMuted else colors.ink3,
        animationSpec = shadowTween(ShadowMotion.ColorFade, ShadowMotion.Ease),
        label = "home-subline",
    )
    Text(
        text = monoText(parts, colors.ink2, monoSize = if (large) 14 else 12),
        style = if (large) Shadow.type.body else Shadow.type.bodyS,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.heightIn(min = if (large) 22.dp else 18.dp),
    )
}

/** [parts] as one string with the mono runs in JetBrains Mono [monoSize]/500 [monoColor]. */
internal fun monoText(parts: List<TextPart>, monoColor: Color, monoSize: Int = 12): AnnotatedString =
    buildAnnotatedString {
        parts.forEach { part ->
            if (part.mono) {
                withStyle(
                    SpanStyle(
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = monoSize.sp,
                        fontWeight = FontWeight.Medium,
                        color = monoColor,
                    ),
                ) { append(part.text) }
            } else {
                append(part.text)
            }
        }
    }

internal fun ShadowColors.statusInk(tone: StatusTone): Color = when (tone) {
    StatusTone.Neutral -> ink2
    else -> toneText(tone)
}

// endregion

// region Card

/** The mode card: overline, 44 dp tile, title + subtitle, optional latency meter, chevron. */
@Composable
internal fun HomeModeCard(
    card: HomeCard,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showOverline: Boolean = true,
) {
    val colors = Shadow.colors
    Column(modifier = modifier) {
        if (showOverline) {
            Overline(text = card.overline, modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp))
        }
        ShadowCard(
            modifier = Modifier
                .fillMaxWidth()
                .clearAndSetSemantics {
                    contentDescription = card.contentDescription
                    role = Role.Button
                    onClick {
                        onClick()
                        true
                    }
                },
            onClick = onClick,
            // The artboard's 1 px border sits outside its 14 px padding: content starts 15 dp in (74 dp card).
            contentPadding = PaddingValues(MODE_CARD_PADDING),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconTile(icon = card.icon.vector(), tint = colors.statusInk(card.tone))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = card.title,
                        style = Shadow.type.titleS,
                        color = colors.ink1,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = card.subtitle,
                        style = if (card.subtitleMono) Shadow.type.monoS else Shadow.type.caption,
                        color = if (card.subtitleWarning) colors.amberMuted else colors.ink3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (card.showMeter) LatencyMeter(latencyMs = card.latencyMs)
                Icon(
                    imageVector = if (card.target == CardTarget.RouteLibrary) {
                        ShadowIcons.ChevronRight
                    } else {
                        ShadowIcons.ChevronDown
                    },
                    contentDescription = null,
                    tint = colors.ink3,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

internal fun CardIcon.vector(): ImageVector = when (this) {
    CardIcon.Auto -> ShadowIcons.Auto
    CardIcon.Server -> ShadowIcons.Server
    CardIcon.Routes -> ShadowIcons.Routes
    CardIcon.Clock -> ShadowIcons.Clock
}

/** HomeStates "First run · no servers": tile, copy and the "Add server" button in one card. */
@Composable
internal fun FirstRunCard(onAddServer: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Shadow.colors
    ShadowCard(modifier = modifier.fillMaxWidth(), role = null, contentPadding = PaddingValues(FIRST_RUN_PADDING)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(
                icon = ShadowIcons.Server,
                size = 56.dp,
                iconSize = 26.dp,
                cornerRadius = 14.dp,
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = "No servers yet", style = Shadow.type.titleS, color = colors.ink1)
                Text(
                    text = "Connect through your own SSH server with a password or key.",
                    style = Shadow.type.bodyS,
                    color = colors.ink3,
                )
            }
        }
        PrimaryButton(
            text = "Add server",
            onClick = onAddServer,
            icon = ShadowIcons.Plus,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp),
        )
    }
}

@Composable
internal fun Overline(text: String, modifier: Modifier = Modifier, color: Color = Shadow.colors.ink3) {
    Text(
        text = text.uppercase(),
        style = Shadow.type.overline,
        color = color,
        maxLines = 1,
        modifier = modifier,
    )
}

// endregion

// region Chips

/** One quick-action chip; [onClick] receives the chip. */
@Composable
internal fun HomeChipView(
    chip: HomeChip,
    onClick: (HomeChip) -> Unit,
    modifier: Modifier = Modifier,
    tablet: Boolean = false,
) {
    val click = { onClick(chip) }
    when (chip) {
        is HomeChip.CheckTunnel -> CheckChip(
            state = chip.state,
            idleLabel = "Check tunnel",
            okLabel = "Tunnel OK",
            failedLabel = "Check failed",
            idleIcon = ShadowIcons.Shield,
            latencyMs = chip.latencyMs,
            enabled = chip.enabled,
            onClick = click,
            modifier = modifier,
        )
        is HomeChip.CheckRoute -> CheckChip(
            state = chip.state,
            idleLabel = "Check route",
            okLabel = "Available",
            failedLabel = "Unavailable",
            idleIcon = ShadowIcons.CheckCircle,
            latencyMs = chip.latencyMs,
            enabled = chip.enabled,
            onClick = click,
            modifier = modifier,
        )
        is HomeChip.Terminal -> ShadowChip(
            label = "Terminal",
            onClick = click,
            icon = ShadowIcons.Server,
            enabled = chip.enabled,
            modifier = modifier,
        )
        is HomeChip.AppRouting -> ShadowChip(
            label = if (tablet) chip.tabletLabel else chip.label,
            onClick = click,
            icon = ShadowIcons.Apps,
            modifier = modifier.clearAndSetSemantics {
                contentDescription = chip.contentDescription
                role = Role.Button
                onClick {
                    click()
                    true
                }
            },
        )
        HomeChip.RouteLibrary -> ShadowChip(
            label = "Route library",
            onClick = click,
            icon = ShadowIcons.Routes,
            modifier = modifier,
        )
        is HomeChip.RoutePool -> ShadowChip(
            label = if (chip.count > 0) "Route pool · ${chip.count}" else "Route pool",
            onClick = click,
            icon = ShadowIcons.Bolt,
            modifier = modifier,
        )
        HomeChip.Activity -> ShadowChip(
            label = "Activity",
            onClick = click,
            icon = ShadowIcons.Activity,
            modifier = modifier,
        )
    }
}

@Composable
private fun CheckChip(
    state: CheckState,
    idleLabel: String,
    okLabel: String,
    failedLabel: String,
    idleIcon: ImageVector,
    latencyMs: Long?,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val chipModifier = modifier.semantics { liveRegion = LiveRegionMode.Polite }
    when (state) {
        CheckState.Idle -> ShadowChip(
            label = idleLabel,
            onClick = onClick,
            icon = idleIcon,
            enabled = enabled,
            modifier = chipModifier,
        )
        CheckState.Running -> ShadowChip(
            label = "Checking…",
            onClick = onClick,
            tone = ChipTone.Progress,
            loading = true,
            modifier = chipModifier,
        )
        CheckState.Ok -> if (latencyMs == null) {
            ShadowChip(
                label = okLabel,
                onClick = onClick,
                icon = ShadowIcons.Check,
                tone = ChipTone.Success,
                enabled = enabled,
                modifier = chipModifier,
            )
        } else {
            OkResultChip(
                label = okLabel,
                latencyMs = latencyMs,
                onClick = onClick,
                enabled = enabled,
                modifier = chipModifier,
            )
        }
        CheckState.Failed -> ShadowChip(
            label = failedLabel,
            onClick = onClick,
            icon = ShadowIcons.Close,
            tone = ChipTone.Error,
            enabled = enabled,
            modifier = chipModifier,
        )
    }
}

/**
 * HomeLight's "Tunnel OK · 84 ms": [ChipTone.Success] chip whose middle dot is Onest at 60 % and
 * whose latency is mono 12/500 (ShadowChip's `count` would set the dot in mono too).
 */
@Composable
private fun OkResultChip(
    label: String,
    latencyMs: Long,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = Shadow.colors
    val ink = colors.mintText
    val interaction = remember { MutableInteractionSource() }
    val latency = formatLatency(latencyMs)
    Row(
        modifier = modifier
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .semantics { contentDescription = "$label, $latency. Check again" }
            .shadowClickable(interactionSource = interaction, enabled = enabled, onClick = onClick)
            .height(ShadowDimens.Chip)
            .clip(ShadowShapes.Pill)
            .background(colors.mintTint)
            .border(
                width = 1.dp,
                color = ink.copy(alpha = if (colors.isDark) OK_BORDER_ALPHA_NIGHT else OK_BORDER_ALPHA_DAY),
                shape = ShadowShapes.Pill,
            )
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(ShadowIcons.Check, contentDescription = null, tint = ink, modifier = Modifier.size(16.dp))
        Row(
            modifier = Modifier.clearAndSetSemantics {},
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, style = Shadow.type.label, color = ink, maxLines = 1)
            Text(text = "·", style = Shadow.type.label, color = ink.copy(alpha = OK_DOT_ALPHA))
            Text(
                text = latency,
                style = Shadow.type.monoS.copy(fontWeight = FontWeight.Medium),
                color = ink,
                maxLines = 1,
            )
        }
    }
}

/** Chips with a staggered entrance (HomeLight: newly shown chips fade up). */
@Composable
internal fun HomeChips(
    chips: List<HomeChip>,
    onClick: (HomeChip) -> Unit,
    tablet: Boolean = false,
) {
    chips.forEachIndexed { index, chip ->
        key(chip::class) {
            HomeChipView(chip = chip, onClick = onClick, tablet = tablet, modifier = Modifier.fadeUpIn(index))
        }
    }
}

// endregion

// region Notes and banners

/** One HomeStates note: tint, 18 dp tone icon, 13/18 ink-2 text (strong part in the tone ink). */
@Composable
internal fun NoteBanner(
    tone: StatusTone,
    icon: ImageVector,
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    textColor: Color = Shadow.colors.ink2,
    live: LiveRegionMode? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val colors = Shadow.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(ShadowShapes.Banner)
            .background(colors.toneTint(tone))
            .then(if (live != null) Modifier.semantics { liveRegion = live } else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, contentDescription = null, tint = colors.toneText(tone), modifier = Modifier.size(18.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(text = text, style = Shadow.type.bodyS, color = textColor)
            action?.invoke()
        }
    }
}

/** "Strong." + rest, strong in [strongColor] 600. */
internal fun strongText(strong: String, rest: String, strongColor: Color, monoColor: Color? = null): AnnotatedString =
    buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = strongColor)) { append(strong) }
        if (rest.isNotEmpty()) {
            append(" ")
            if (monoColor != null) withStyle(SpanStyle(color = monoColor)) { append(rest) } else append(rest)
        }
    }

/** Renders one [HomeNote]. */
@Composable
internal fun HomeNoteView(
    note: HomeNote,
    actions: HomeActions,
    modifier: Modifier = Modifier,
) {
    val colors = Shadow.colors
    when (note) {
        HomeNote.PermissionDenied -> PermissionDeniedBanner(modifier)
        is HomeNote.EngineMissing -> NoteBanner(
            tone = StatusTone.Error,
            icon = ShadowIcons.Warning,
            text = strongText(
                strong = "Xray engine isn't installed.",
                rest = "Download it once — Auto and Routes share it.",
                strongColor = colors.coralText,
            ),
            live = LiveRegionMode.Polite,
            modifier = modifier,
            action = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ShadowButton(
                        text = "Download engine",
                        onClick = actions.onDownloadEngine,
                        size = ShadowButtonSize.Compact,
                        icon = ShadowIcons.Download,
                    )
                    note.sizeLabel?.let {
                        Text(text = it, style = Shadow.type.monoS, color = colors.ink2, maxLines = 1)
                    }
                }
            },
        )
        is HomeNote.Failure -> FailureBanner(note, actions, modifier)
        HomeNote.AutoSearching -> NoteBanner(
            tone = StatusTone.Progress,
            icon = ShadowIcons.Warning,
            text = strongText(
                strong = "Traffic is not protected",
                rest = "while Auto searches — apps go direct for now.",
                strongColor = colors.amberText,
            ),
            modifier = modifier,
        )
        is HomeNote.LastPass -> NoteBanner(
            tone = StatusTone.Info,
            icon = ShadowIcons.Info,
            text = buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = colors.skyText)) {
                    append("Last pass: ")
                    val mono = SpanStyle(fontFamily = JetBrainsMonoFamily, fontSize = 12.sp)
                    withStyle(mono) { append("0") }
                    append(" of ")
                    withStyle(mono) { append(note.tested.toString()) }
                    append(" routes answered.")
                }
                append(" Your network may be blocking them.")
            },
            modifier = modifier,
        )
        HomeNote.ServerReconnecting -> NoteBanner(
            tone = StatusTone.Info,
            icon = ShadowIcons.Lock,
            text = strongText(
                strong = "Apps stay on the VPN.",
                rest = "New connections wait for the tunnel; open ones drop.",
                strongColor = colors.skyText,
            ),
            modifier = modifier,
        )
        is HomeNote.Info -> NoteBanner(
            tone = StatusTone.Info,
            icon = ShadowIcons.Info,
            text = AnnotatedString(note.message),
            modifier = modifier,
        )
    }
}

@Composable
private fun FailureBanner(note: HomeNote.Failure, actions: HomeActions, modifier: Modifier) {
    val colors = Shadow.colors
    val text = buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = colors.coralText)) { append(note.title) }
        append(" ")
        append(note.message)
        if (note.action == NoteAction.OpenActivity) {
            append(" ")
            withLink(
                LinkAnnotation.Clickable(
                    tag = "activity",
                    styles = TextLinkStyles(SpanStyle(color = colors.coralText, fontWeight = FontWeight.SemiBold)),
                ) { actions.onOpenActivity() },
            ) { append("See activity") }
        }
    }
    NoteBanner(
        tone = StatusTone.Error,
        icon = ShadowIcons.Warning,
        text = text,
        live = LiveRegionMode.Assertive,
        modifier = modifier,
        action = when (note.action) {
            NoteAction.EditServer -> {
                {
                    ShadowButton(
                        text = "Edit server",
                        onClick = actions.onEditSelectedServer,
                        size = ShadowButtonSize.Compact,
                        icon = ShadowIcons.Edit,
                    )
                }
            }
            NoteAction.PickApps -> {
                {
                    ShadowButton(
                        text = "Pick apps",
                        onClick = actions.onOpenAppRouting,
                        size = ShadowButtonSize.Compact,
                        icon = ShadowIcons.Apps,
                    )
                }
            }
            NoteAction.OpenActivity, null -> null
        },
    )
}

/** System.dc.html: coral tint over surface-1 with a coral hairline; title 14/20 600 + ink-2 line. */
@Composable
private fun PermissionDeniedBanner(modifier: Modifier) {
    val colors = Shadow.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(ShadowShapes.Banner)
            .background(colors.surface1)
            .background(colors.coralTint)
            .border(1.dp, colors.coral.copy(alpha = DENIED_BORDER_ALPHA), ShadowShapes.Banner)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            // 12/14 padding inside the artboard's 1 px border.
            .padding(horizontal = 15.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = ShadowIcons.Warning,
            contentDescription = null,
            tint = colors.coralText,
            modifier = Modifier
                .padding(top = 1.dp)
                .size(18.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "VPN permission was denied.",
                style = Shadow.type.segment.copy(lineHeight = 20.sp),
                color = colors.coralText,
            )
            Text(text = "Tap Connect to ask again.", style = Shadow.type.bodyS, color = colors.ink2)
        }
    }
}

/** HomeLight: the "Check tunnel" failure under the server card. */
@Composable
internal fun CheckFailedBanner(serverName: String, onOpenActivity: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Shadow.colors
    val text = buildAnnotatedString {
        append("The server didn’t forward traffic. Make sure ")
        withStyle(SpanStyle(fontFamily = JetBrainsMonoFamily, fontSize = 12.sp, fontWeight = FontWeight.Medium)) {
            append("AllowTcpForwarding")
        }
        append(" is enabled on $serverName. ")
        withLink(
            LinkAnnotation.Clickable(
                tag = "activity",
                styles = TextLinkStyles(SpanStyle(color = colors.coralText, fontWeight = FontWeight.SemiBold)),
            ) { onOpenActivity() },
        ) { append("See activity") }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(ShadowShapes.Banner)
            .background(colors.coralTint)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(ShadowIcons.Warning, contentDescription = null, tint = colors.coralText, modifier = Modifier.size(18.dp))
        Text(text = text, style = Shadow.type.bodyS, color = colors.ink1, modifier = Modifier.weight(1f))
    }
}

/** HomeLight's caption under the chips while Check tunnel is available. */
@Composable
internal fun CheckCaption(modifier: Modifier = Modifier) {
    val colors = Shadow.colors
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            imageVector = ShadowIcons.Info,
            contentDescription = null,
            tint = colors.ink3,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(14.dp),
        )
        Text(
            text = "Check tunnel tests port forwarding on the server, not your whole internet connection.",
            style = Shadow.type.bodyS,
            color = colors.ink3,
        )
    }
}

/** HomeLight's slim update banner: amber tint, download icon, text, action, dismiss. */
@Composable
internal fun UpdateBannerView(
    banner: UpdateBanner,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Shadow.colors
    val mono = SpanStyle(fontFamily = JetBrainsMonoFamily, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    val text = buildAnnotatedString {
        when (banner.kind) {
            UpdateBanner.Kind.Ready -> {
                append("Update ")
                withStyle(mono) { append(banner.version) }
                append(" is ready to install")
            }
            UpdateBanner.Kind.Available -> {
                append("Update ")
                withStyle(mono) { append(banner.version) }
                append(" is available")
            }
            UpdateBanner.Kind.Downloading -> {
                append("Downloading ")
                withStyle(mono) { append(banner.version) }
                banner.percent?.let {
                    append(" · ")
                    withStyle(mono) { append("$it%") }
                }
            }
        }
    }
    val actionLabel = if (banner.kind == UpdateBanner.Kind.Ready) "Install" else "View"
    val interaction = remember { MutableInteractionSource() }
    // HomeLight: 44 dp tall with 4 dp padding; the 44 dp Install and dismiss targets overlap that padding.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(ShadowShapes.Banner)
            .background(colors.amberTint)
            .padding(start = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(ShadowIcons.Download, contentDescription = null, tint = colors.amberText, modifier = Modifier.size(18.dp))
        Text(
            text = text,
            style = Shadow.type.bodyS.copy(fontWeight = FontWeight.Medium),
            color = colors.ink1,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 4.dp),
            maxLines = 2,
        )
        Box(
            modifier = Modifier
                .height(44.dp)
                .clip(ShadowShapes.Tile)
                .shadowClickable(
                    interactionSource = interaction,
                    onClickLabel = "$actionLabel update ${banner.version}",
                    onClick = onOpen,
                )
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = actionLabel, style = Shadow.type.label, color = colors.amberText)
        }
        ShadowIconButton(
            icon = ShadowIcons.Close,
            contentDescription = "Dismiss update notice",
            onClick = onDismiss,
            tint = colors.ink2,
            iconSize = 16.dp,
        )
    }
}

// endregion

private const val DENIED_BORDER_ALPHA = 0.22f
private const val OK_DOT_ALPHA = 0.6f
private const val OK_BORDER_ALPHA_NIGHT = 0.35f
private const val OK_BORDER_ALPHA_DAY = 0.28f
private val MODE_CARD_PADDING = 15.dp
private val FIRST_RUN_PADDING = 17.dp
