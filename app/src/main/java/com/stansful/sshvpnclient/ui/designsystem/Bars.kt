package com.stansful.sshvpnclient.ui.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stansful.sshvpnclient.ui.theme.Shadow
import com.stansful.sshvpnclient.ui.theme.ShadowColors
import com.stansful.sshvpnclient.ui.theme.ShadowMotion
import com.stansful.sshvpnclient.ui.theme.shadowTween

/** Color of the wordmark / rail state dot for a connection tone (Off is a dim neutral). */
fun ShadowColors.stateDotColor(tone: StatusTone): Color = when (tone) {
    StatusTone.Neutral -> stateDotOff
    else -> toneFill(tone)
}

/** 10 dp state dot with a soft glow in its own color (no glow when neutral). Animates 400 ms. */
@Composable
fun GlowDot(
    color: Color,
    modifier: Modifier = Modifier,
    glow: Boolean = true,
    size: Dp = 10.dp,
) {
    val night = Shadow.colors.isDark
    val dot by animateColorAsState(color, shadowTween(ShadowMotion.ColorFade, ShadowMotion.Ease), label = "glow-dot")
    val glowColor by animateColorAsState(
        targetValue = if (glow) color.copy(alpha = if (night) GLOW_ALPHA else DAY_GLOW_ALPHA) else color.copy(alpha = 0f),
        animationSpec = shadowTween(ShadowMotion.ColorFade, ShadowMotion.Ease),
        label = "glow-dot-shadow",
    )
    Box(
        modifier = modifier
            .size(size)
            .dropShadow(CircleShape) {
                radius = (if (night) GLOW_RADIUS else DAY_GLOW_RADIUS).toPx()
                this.color = glowColor
            }
            .clip(CircleShape)
            .background(dot),
    )
}

/** The product wordmark: 10 dp state dot (+ glow) and lowercase "shadow" 22/700 -0.03em. */
@Composable
fun Wordmark(
    dotColor: Color,
    modifier: Modifier = Modifier,
    glow: Boolean = true,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlowDot(color = dotColor, glow = glow)
        Text(text = "shadow", style = Shadow.type.wordmark, color = Shadow.colors.ink1)
    }
}

/**
 * Top bar of a top-level screen: display title (32/600) on the left, up to 3 icon buttons right
 * (end 8, 2 dp apart; "Add" = Tonal style). The artboards' 14 px top padding is the status bar, so the
 * 52 dp row starts right under the [windowInsets] (status bar + horizontal cutouts). With [subtitle]
 * (Settings: "Changes apply right away. Nothing to save.") the title sits 6 dp under the inset, the
 * 13/18 ink-3 subtitle 4 dp below it, 4 dp bottom. Use `end = 20.dp` in [contentPadding] when the only
 * action is a Tonal icon button (Servers).
 *
 * [pane]: the tablet (Expanded width) variant, like TabletRoutes.dc.html's header — title-l (24/30)
 * in a 44 dp row 10 dp under the inset, the subtitle 4 dp below the row — so every rail destination
 * titles alike.
 */
@Composable
fun TopLevelBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    windowInsets: WindowInsets = ShadowTopBarInsets,
    contentPadding: PaddingValues = PaddingValues(start = 20.dp, end = 8.dp),
    pane: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = Shadow.colors
    val titleStyle = if (pane) Shadow.type.titleL else Shadow.type.display
    // Onest at 32 sp is taller (41 dp) than its 38 sp line; Android keeps the font height, CSS keeps the
    // line. With a subtitle below, the title takes exactly one line (glyphs centered as in CSS) so the
    // subtitle starts 38 + 4 dp under the title's top, as in Settings.dc.html.
    val titleLine = with(LocalDensity.current) { titleStyle.lineHeight.toDp() }
    val oneLineTitle = subtitle != null && !pane
    Column(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(windowInsets)
            .padding(contentPadding)
            .padding(
                top = when {
                    pane -> 10.dp
                    subtitle != null -> 6.dp
                    else -> 0.dp
                },
                bottom = if (subtitle != null) 4.dp else 0.dp,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    when {
                        pane -> Modifier.height(44.dp)
                        oneLineTitle -> Modifier.heightIn(min = titleLine)
                        else -> Modifier.height(52.dp)
                    },
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = titleStyle,
                color = colors.ink1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (oneLineTitle) {
                            Modifier
                                .height(titleLine)
                                .wrapContentHeight(Alignment.CenterVertically, unbounded = true)
                        } else {
                            Modifier
                        },
                    )
                    .semantics { heading() },
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        }
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = Shadow.type.bodyS,
                color = colors.ink3,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * Home top bar: [Wordmark] with the connection [dotColor] on the left (start 20), actions on the right
 * (end 10) in a 44 dp row directly under the status bar (Main.dc.html header = 14 px + 44).
 * [showWordmark] false keeps only the actions: next to the navigation rail, which carries the wordmark
 * and the state dot (TabletHome.dc.html).
 */
@Composable
fun WordmarkTopBar(
    dotColor: Color,
    modifier: Modifier = Modifier,
    dotGlow: Boolean = true,
    showWordmark: Boolean = true,
    windowInsets: WindowInsets = ShadowTopBarInsets,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(windowInsets)
            .padding(start = 20.dp, end = 10.dp)
            .height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showWordmark) {
            Wordmark(dotColor = dotColor, glow = dotGlow, modifier = Modifier.weight(1f))
        } else {
            Spacer(Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/**
 * Sub-screen top bar (56 dp + status-bar inset): [navigationIcon] button (44 dp; back chevron "Back",
 * or `ShadowIcons.Close` for a selection bar), title 16/600, optional mono [subtitle], [status] (e.g. a
 * StatusPill) and trailing [actions]. [showDivider] fades in a 1 dp line under the bar (use when content
 * scrolls beneath it). [container] replaces the bg fill (RoutesSelect's surface-2 selection bar).
 * The title starts 4 dp after the 44 dp button plus [titleStartPadding] (Appearance / App routing: 0,
 * i.e. 56 dp from the start with the default 8 dp padding; Activity, Terminal: 2; editors, selection: 4).
 */
@Composable
fun SubScreenBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleMono: Boolean = true,
    status: (@Composable () -> Unit)? = null,
    showDivider: Boolean = false,
    navigationIcon: ImageVector = ShadowIcons.Back,
    backContentDescription: String = "Back",
    container: Color = Color.Unspecified,
    windowInsets: WindowInsets = ShadowTopBarInsets,
    contentPadding: PaddingValues = PaddingValues(start = 8.dp, end = 8.dp),
    titleStartPadding: Dp = 0.dp,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = Shadow.colors
    val dividerAlpha by animateFloatAsState(
        targetValue = if (showDivider) 1f else 0f,
        animationSpec = shadowTween(ShadowMotion.Small, ShadowMotion.Ease),
        label = "bar-divider",
    )
    val divider = colors.line
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(if (container != Color.Unspecified) container else colors.bg)
            .drawBehind {
                val stroke = 1.dp.toPx()
                drawLine(
                    color = divider.copy(alpha = divider.alpha * dividerAlpha),
                    start = Offset(0f, size.height - stroke / 2),
                    end = Offset(size.width, size.height - stroke / 2),
                    strokeWidth = stroke,
                )
            }
            .windowInsetsPadding(windowInsets)
            .height(56.dp)
            .padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShadowIconButton(
            icon = navigationIcon,
            contentDescription = backContentDescription,
            onClick = onBack,
            tint = colors.ink1,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = titleStartPadding),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = title,
                style = Shadow.type.titleS,
                color = colors.ink1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = if (subtitleMono) Shadow.type.monoS else Shadow.type.bodyS,
                    color = colors.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        status?.invoke()
        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** A top-level destination for [ShadowBottomNav] / [ShadowNavRail]. */
@Immutable
data class ShadowNavItem(
    val key: String,
    val label: String,
    val icon: ImageVector,
)

/**
 * Bottom navigation: 72 dp + navigation-bar inset, nav-bg, 1 dp top hairline, equal items with a
 * 56×30 surface-3 pill behind the active icon (animated) and 12/600 labels (ink-1 active, ink-3 idle).
 */
@Composable
fun ShadowBottomNav(
    items: List<ShadowNavItem>,
    selectedKey: String?,
    onSelect: (ShadowNavItem) -> Unit,
    modifier: Modifier = Modifier,
    windowInsets: WindowInsets = WindowInsets.navigationBars,
) {
    val colors = Shadow.colors
    val hairline = colors.navLine
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.navBg)
            .drawBehind {
                drawLine(hairline, Offset(0f, 0.5.dp.toPx()), Offset(size.width, 0.5.dp.toPx()), 1.dp.toPx())
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .padding(start = 8.dp, end = 8.dp, top = 7.dp, bottom = 10.dp)
                .selectableGroup(),
        ) {
            items.forEach { item ->
                NavEntry(
                    item = item,
                    selected = item.key == selectedKey,
                    pillHeight = 30.dp,
                    onClick = { onSelect(item) },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
            }
        }
        Spacer(Modifier.windowInsetsBottomHeight(windowInsets))
    }
}

/**
 * Navigation rail (tablet): 88 dp wide, nav-bg, 1 dp end hairline, header with the state dot and a
 * 16/700 "shadow" wordmark ([dotColor]; [dotGlow] = false when Off), then items (72 dp wide, 56×32
 * pill) 12 dp apart.
 */
@Composable
fun ShadowNavRail(
    items: List<ShadowNavItem>,
    selectedKey: String?,
    onSelect: (ShadowNavItem) -> Unit,
    modifier: Modifier = Modifier,
    dotColor: Color? = null,
    dotGlow: Boolean = true,
    windowInsets: WindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start),
) {
    val colors = Shadow.colors
    val hairline = colors.navLine
    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(colors.navBg)
            .drawBehind {
                val x = size.width - 0.5.dp.toPx()
                drawLine(hairline, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
            }
            .windowInsetsPadding(windowInsets)
            .width(88.dp)
            .padding(top = 24.dp, bottom = 20.dp)
            .selectableGroup(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (dotColor != null) {
            Column(
                modifier = Modifier.padding(bottom = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GlowDot(color = dotColor, glow = dotGlow)
                Text(
                    text = "shadow",
                    style = Shadow.type.wordmark.copy(fontSize = RailWordmarkSize, lineHeight = RailWordmarkLine),
                    color = colors.ink1,
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items.forEach { item ->
                NavEntry(
                    item = item,
                    selected = item.key == selectedKey,
                    pillHeight = 32.dp,
                    onClick = { onSelect(item) },
                    modifier = Modifier
                        .width(72.dp)
                        .padding(vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun NavEntry(
    item: ShadowNavItem,
    selected: Boolean,
    pillHeight: Dp,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val colors = Shadow.colors
    val progress by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = shadowTween(ShadowMotion.Small, ShadowMotion.Standard),
        label = "nav-pill",
    )
    val ink by animateColorAsState(
        targetValue = if (selected) colors.ink1 else colors.ink3,
        animationSpec = shadowTween(ShadowMotion.Small, ShadowMotion.Ease),
        label = "nav-ink",
    )
    val pill = colors.surface3
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .selectable(
                selected = selected,
                role = Role.Tab,
                interactionSource = interactionSource,
                indication = ShadowFocusIndication,
                onClick = onClick,
            )
            .pressScale(interactionSource),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Box(
            modifier = Modifier.size(width = 56.dp, height = pillHeight),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(width = 56.dp, height = pillHeight)
                    .graphicsLayer {
                        alpha = progress
                        scaleX = PILL_START_SCALE + (1f - PILL_START_SCALE) * progress
                    }
                    .clip(CircleShape)
                    .background(pill),
            )
            Icon(item.icon, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
        }
        Text(text = item.label, style = Shadow.type.navLabel, color = ink, maxLines = 1)
    }
}

/** Insets every top bar pads: status bar + horizontal system bars and cutouts (0 sideways on phones). */
val ShadowTopBarInsets: WindowInsets
    @Composable
    get() = WindowInsets.systemBars
        .union(WindowInsets.displayCutout)
        .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)

private val GLOW_RADIUS = 14.dp
private val DAY_GLOW_RADIUS = 12.dp
private const val GLOW_ALPHA = 0.6f
private const val DAY_GLOW_ALPHA = 0.55f
private const val PILL_START_SCALE = 0.6f
private val RailWordmarkSize = 16.sp
private val RailWordmarkLine = 20.sp
