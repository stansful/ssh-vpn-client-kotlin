package com.stansful.sshvpnclient.ui.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.stansful.sshvpnclient.ui.theme.Shadow
import com.stansful.sshvpnclient.ui.theme.ShadowColors
import com.stansful.sshvpnclient.ui.theme.ShadowMotion
import com.stansful.sshvpnclient.ui.theme.ShadowShapes
import com.stansful.sshvpnclient.ui.theme.shadowTween
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.shadow.Shadow as DropShadowSpec

/** Tone of an [InlineBanner]. */
enum class BannerTone {
    /** Mint tint, check-circle. */
    Success,

    /** Coral tint, warning triangle. */
    Error,

    /** Sky tint, info circle. */
    Info,

    /** Amber tint, warning triangle. */
    Warning,
}

/**
 * Inline message: radius 14, tone tint, 18 dp tone icon + 13/18 text, padding 12/14. With [title] the
 * title is set 600 in the tone color and [message] follows in ink-2 ("Xray engine isn't installed."
 * + "Download it once — Auto and Routes share it."). Without a title the message is ink-1, or the tone
 * ink with [tintedMessage] (coral errors such as "Couldn't save…", soft-sky info notes). Optional
 * action button ([actionLabel] + [onAction], `ShadowButtonSize.Compact`, [actionVariant]) or a custom
 * [action], and a 44 dp dismiss x ([onDismiss], [dismissContentDescription], e.g. "Hide notice").
 */
@Composable
fun InlineBanner(
    message: String,
    tone: BannerTone,
    modifier: Modifier = Modifier,
    title: String? = null,
    icon: ImageVector? = null,
    tintedMessage: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    actionVariant: ShadowButtonVariant = ShadowButtonVariant.Primary,
    onDismiss: (() -> Unit)? = null,
    dismissContentDescription: String = "Dismiss",
    action: (@Composable () -> Unit)? = null,
) {
    val colors = Shadow.colors
    val accent = colors.bannerAccent(tone)
    val text = buildAnnotatedString {
        if (title != null) {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = accent)) { append(title) }
            append(" ")
            withStyle(SpanStyle(color = colors.ink2)) { append(message) }
        } else {
            withStyle(SpanStyle(color = if (tintedMessage) colors.bannerInk(tone) else colors.ink1)) {
                append(message)
            }
        }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(ShadowShapes.Banner)
            .background(colors.bannerTint(tone))
            .semantics {
                liveRegion = if (tone == BannerTone.Error) LiveRegionMode.Assertive else LiveRegionMode.Polite
            },
        verticalAlignment = Alignment.Top,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(start = 14.dp, end = if (onDismiss != null) 2.dp else 14.dp, top = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = icon ?: tone.defaultIcon(),
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(18.dp),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = text, style = Shadow.type.bodyS)
                when {
                    action != null -> action()
                    actionLabel != null && onAction != null -> ShadowButton(
                        text = actionLabel,
                        onClick = onAction,
                        variant = actionVariant,
                        size = ShadowButtonSize.Compact,
                    )
                }
            }
        }
        if (onDismiss != null) {
            ShadowIconButton(
                icon = ShadowIcons.Close,
                contentDescription = dismissContentDescription,
                onClick = onDismiss,
                tint = colors.ink2,
                iconSize = 16.dp,
            )
        }
    }
}

private fun ShadowColors.bannerTint(tone: BannerTone): Color = when (tone) {
    BannerTone.Success -> mintTint
    BannerTone.Error -> coralTint
    BannerTone.Info -> skyTint
    BannerTone.Warning -> amberTint
}

/** Body ink of a [InlineBanner] with `tintedMessage` (info notes use the soft sky). */
private fun ShadowColors.bannerInk(tone: BannerTone): Color = when (tone) {
    BannerTone.Success -> mintText
    BannerTone.Error -> coralText
    BannerTone.Info -> skySoft
    BannerTone.Warning -> amberText
}

private fun ShadowColors.bannerAccent(tone: BannerTone): Color = when (tone) {
    BannerTone.Success -> mintText
    BannerTone.Error -> coralText
    BannerTone.Info -> skyText
    BannerTone.Warning -> amberText
}

private fun BannerTone.defaultIcon(): ImageVector = when (this) {
    BannerTone.Success -> ShadowIcons.CheckCircle
    BannerTone.Error, BannerTone.Warning -> ShadowIcons.Warning
    BannerTone.Info -> ShadowIcons.Info
}

/**
 * Empty state: 56 dp icon tile (radius 16; [iconTint]/[iconContainer], e.g. coral on coral tint for an
 * error), title 16/600, message 13/18 ink-3 (max 270 dp wide), then [actions] 24 dp below (8 dp apart;
 * a 52 dp `PrimaryButton`, or `ShadowButtonSize.Regular` buttons). Center it in the free space.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    iconTint: Color = Color.Unspecified,
    iconContainer: Color = Color.Unspecified,
    actions: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val colors = Shadow.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconTile(icon = icon, size = 56.dp, iconSize = 26.dp, tint = iconTint, container = iconContainer)
        Text(
            text = title,
            style = Shadow.type.titleS,
            color = colors.ink1,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = message,
            style = Shadow.type.bodyS,
            color = colors.ink3,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(top = 6.dp)
                .widthIn(max = 270.dp),
        )
        if (actions != null) {
            Spacer(Modifier.height(24.dp))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = actions,
            )
        }
    }
}

/** Icon tint of a toast. */
enum class ToastTone { Neutral, Success, Error, Info }

/** One toast message ([detail] = optional second line, 12/16 ink-3). */
@Immutable
data class ToastData(
    val id: Long,
    val message: String,
    val tone: ToastTone,
    val icon: ImageVector?,
    val actionLabel: String?,
    val onAction: (() -> Unit)?,
    val durationMillis: Long,
    val detail: String? = null,
)

/** Holds the visible toast. Obtain via `LocalToaster.current`; the shell renders [ToastHost]. */
@Stable
class ToasterState {
    /** The toast on screen, or null. */
    var current: ToastData? by mutableStateOf(null)
        private set

    private var nextId = 0L

    /**
     * Shows [message] (replacing any visible toast); hides after [durationMillis] (2.5 s). With
     * [detail] the message is set 13/600 and the detail follows on a second line (Activity's
     * "Copied 42 lines" + "Paste them into a bug report").
     */
    fun show(
        message: String,
        tone: ToastTone = ToastTone.Success,
        icon: ImageVector? = tone.defaultIcon(),
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null,
        durationMillis: Long = ShadowMotion.ToastVisible.toLong(),
        detail: String? = null,
    ) {
        nextId += 1
        current = ToastData(nextId, message, tone, icon, actionLabel, onAction, durationMillis, detail)
    }

    /** Hides the toast (only if it is still [id] when given). */
    fun dismiss(id: Long? = null) {
        if (id == null || current?.id == id) current = null
    }
}

/** Toaster of the current screen tree; a detached default makes `show` a no-op without a host. */
val LocalToaster: ProvidableCompositionLocal<ToasterState> = staticCompositionLocalOf { ToasterState() }

/** Remembers a [ToasterState] to provide through [LocalToaster]. */
@Composable
fun rememberToasterState(): ToasterState = remember { ToasterState() }

/**
 * Where the toast host has to stay above: the tops of the elements screens pin to the bottom of the
 * window ([Modifier.toastObstacle]: Routes' mini connection bar, App routing's footer). The shell
 * provides one through [LocalToastAnchor] and lifts its [ToastHost] to [clearance] when that is higher
 * than its own default spot.
 */
@Stable
class ToastAnchorState {
    private val obstacles = mutableStateMapOf<Any, Float>()

    /** Px from the bottom of the window the toast's bottom edge must stay above (0 when nothing is marked). */
    val clearance: Float get() = obstacles.values.maxOrNull() ?: 0f

    internal fun update(key: Any, clearancePx: Float) {
        if (obstacles[key] != clearancePx) obstacles[key] = clearancePx
    }

    internal fun remove(key: Any) {
        obstacles.remove(key)
    }

    private val centeredScreens = mutableStateMapOf<Any, Unit>()

    /** True while a screen that shows centred, content-width toasts ([CenteredToasts]) is composed. */
    val centered: Boolean get() = centeredScreens.isNotEmpty()

    internal fun addCentered(key: Any) {
        centeredScreens[key] = Unit
    }

    internal fun removeCentered(key: Any) {
        centeredScreens.remove(key)
    }
}

/**
 * While this is in composition, toasts are centred and only as wide as their text (Settings.dc.html,
 * TabletHome.dc.html, Terminal.dc.html) instead of spanning the window between the 20 dp gutters.
 * Each toast keeps the shape it appeared with, so a screen change while it shows doesn't reflow it.
 */
@Composable
fun CenteredToasts() {
    val anchor = LocalToastAnchor.current
    DisposableEffect(anchor) {
        val key = Any()
        anchor.addCentered(key)
        onDispose { anchor.removeCentered(key) }
    }
}

/** The shell's [ToastAnchorState]; a detached default makes [Modifier.toastObstacle] a no-op without a host. */
val LocalToastAnchor: ProvidableCompositionLocal<ToastAnchorState> = staticCompositionLocalOf { ToastAnchorState() }

/**
 * Keeps toasts at least [gap] above this element while it is on screen (it follows the element as it
 * moves, e.g. above the keyboard, and stops counting when it leaves the composition). For bars pinned
 * to the bottom of a screen: Routes' mini connection bar (10 dp), App routing's footer.
 */
fun Modifier.toastObstacle(gap: Dp = TOAST_OBSTACLE_GAP): Modifier = this then ToastObstacleElement(gap)

private data class ToastObstacleElement(val gap: Dp) : ModifierNodeElement<ToastObstacleNode>() {
    override fun create() = ToastObstacleNode(gap)

    override fun update(node: ToastObstacleNode) {
        node.gap = gap
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "toastObstacle"
        properties["gap"] = gap
    }
}

private class ToastObstacleNode(var gap: Dp) :
    Modifier.Node(),
    GlobalPositionAwareModifierNode,
    CompositionLocalConsumerModifierNode {
    private var anchor: ToastAnchorState? = null

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val current = currentValueOf(LocalToastAnchor)
        if (anchor !== current) anchor?.remove(this)
        anchor = current
        if (!coordinates.isAttached) return
        val root = coordinates.findRootCoordinates()
        val top = root.localPositionOf(coordinates, Offset.Zero).y
        val clearance = root.size.height - top + with(requireDensity()) { gap.toPx() }
        current.update(this, clearance.coerceAtLeast(0f))
    }

    override fun onDetach() {
        anchor?.remove(this)
        anchor = null
    }
}

private val TOAST_OBSTACLE_GAP = 12.dp

/**
 * Renders [state]'s toast: 44 dp, surface-3, radius 14, 1 dp line-2 border, 13/18 ink-1 text with a
 * tone icon and an optional amber action ("Undo"). Slides/fades 10 dp, auto-hides. Place it at the
 * bottom of the screen above the bottom nav (20 dp side gutter is applied here). Full width between
 * the gutters, or centred and content-width while a screen asks for it ([CenteredToasts]).
 */
@Composable
fun ToastHost(state: ToasterState, modifier: Modifier = Modifier) {
    val toast = state.current
    val lastShown = remember { arrayOfNulls<ToastData>(1) }
    if (toast != null) lastShown[0] = toast
    val shown = toast ?: lastShown[0]
    val anchor = LocalToastAnchor.current
    val centered = remember(shown?.id) { anchor.centered }
    val accessibility = LocalAccessibilityManager.current
    LaunchedEffect(toast?.id) {
        if (toast != null) {
            // Like Material's SnackbarHost: TalkBack / Switch Access users get the system's longer
            // recommended time, so an "Undo" stays reachable.
            val visibleMillis = accessibility?.calculateRecommendedTimeoutMillis(
                originalTimeoutMillis = toast.durationMillis,
                containsIcons = toast.icon != null,
                containsText = true,
                containsControls = toast.actionLabel != null,
            ) ?: toast.durationMillis
            delay(visibleMillis)
            state.dismiss(toast.id)
        }
    }
    val rise = with(LocalDensity.current) { TOAST_RISE.roundToPx() }
    AnimatedVisibility(
        visible = toast != null,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        enter = fadeIn(shadowTween(TOAST_FADE_MS, ShadowMotion.Ease)) +
            slideInVertically(shadowTween(TOAST_SLIDE_MS, ShadowMotion.Standard)) { rise },
        exit = fadeOut(shadowTween(TOAST_FADE_MS, ShadowMotion.Exit)) +
            slideOutVertically(shadowTween(TOAST_FADE_MS, ShadowMotion.Exit)) { rise },
    ) {
        shown?.let { data ->
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Toast(data = data, centered = centered, onDismiss = { state.dismiss(data.id) })
            }
        }
    }
}

@Composable
private fun Toast(data: ToastData, centered: Boolean, onDismiss: () -> Unit) {
    val colors = Shadow.colors
    val shape = ShadowShapes.Toast
    val shadow = if (colors.isDark) NightToastShadow else DayToastShadow
    val endPadding = when {
        data.actionLabel != null -> 4.dp
        centered -> 16.dp
        else -> 14.dp
    }
    Row(
        modifier = Modifier
            // Centred: as wide as the content (capped by the gutters; the text then ellipsizes).
            .then(if (centered) Modifier.width(IntrinsicSize.Max) else Modifier.fillMaxWidth())
            .heightIn(min = 44.dp)
            .dropShadow(shape, shadow)
            .clip(shape)
            .background(colors.surface3)
            .border(1.dp, colors.line2, shape)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(start = 14.dp, end = endPadding),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = if (data.detail != null) Alignment.Top else Alignment.CenterVertically,
    ) {
        if (data.icon != null) {
            Icon(
                imageVector = data.icon,
                contentDescription = null,
                tint = colors.toastTint(data.tone),
                modifier = Modifier
                    .padding(top = if (data.detail != null) 12.dp else 0.dp)
                    .size(18.dp),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = if (data.detail != null) 12.dp else 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = data.message,
                style = if (data.detail != null) Shadow.type.label else Shadow.type.bodyS,
                color = colors.ink1,
                maxLines = if (centered) 1 else Int.MAX_VALUE,
                overflow = if (centered) TextOverflow.Ellipsis else TextOverflow.Clip,
            )
            if (data.detail != null) {
                Text(text = data.detail, style = Shadow.type.caption, color = colors.ink3)
            }
        }
        if (data.actionLabel != null) {
            Box(
                modifier = Modifier
                    .height(44.dp)
                    .clip(ShadowShapes.Tile)
                    .shadowClickable(remember { MutableInteractionSource() }) {
                        data.onAction?.invoke()
                        onDismiss()
                    }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = data.actionLabel, style = Shadow.type.label, color = colors.amberText)
            }
        }
    }
}

private fun ShadowColors.toastTint(tone: ToastTone): Color = when (tone) {
    ToastTone.Neutral -> ink2
    ToastTone.Success -> mintText
    ToastTone.Error -> coralText
    ToastTone.Info -> skyText
}

private fun ToastTone.defaultIcon(): ImageVector = when (this) {
    ToastTone.Neutral, ToastTone.Success -> ShadowIcons.CheckCircle
    ToastTone.Error -> ShadowIcons.Warning
    ToastTone.Info -> ShadowIcons.Info
}

private val NightToastShadow = DropShadowSpec(
    radius = 32.dp,
    color = Color(0x73000000),
    offset = DpOffset(0.dp, 12.dp),
)
private val DayToastShadow = DropShadowSpec(
    radius = 30.dp,
    color = Color(0x2414161A),
    offset = DpOffset(0.dp, 10.dp),
)
private val TOAST_RISE = 10.dp
private const val TOAST_FADE_MS = 220
private const val TOAST_SLIDE_MS = 320
