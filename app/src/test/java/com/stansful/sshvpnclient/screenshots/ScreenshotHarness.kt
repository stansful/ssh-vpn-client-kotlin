package com.stansful.sshvpnclient.screenshots

import android.app.Application
import android.content.ComponentName
import android.os.Looper
import android.view.View
import android.view.WindowInsets as PlatformWindowInsets
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.AndroidComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MainTestClock
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.stansful.sshvpnclient.ui.designsystem.LocalToastAnchor
import com.stansful.sshvpnclient.ui.designsystem.LocalToaster
import com.stansful.sshvpnclient.ui.designsystem.ShadowBottomNav
import com.stansful.sshvpnclient.ui.designsystem.ShadowIcons
import com.stansful.sshvpnclient.ui.designsystem.ShadowNavItem
import com.stansful.sshvpnclient.ui.designsystem.ShadowNavRail
import com.stansful.sshvpnclient.ui.designsystem.StatusTone
import com.stansful.sshvpnclient.ui.designsystem.ToastAnchorState
import com.stansful.sshvpnclient.ui.designsystem.ToastHost
import com.stansful.sshvpnclient.ui.designsystem.ToasterState
import com.stansful.sshvpnclient.ui.designsystem.stateDotColor
import com.stansful.sshvpnclient.ui.designsystem.toastObstacle
import com.stansful.sshvpnclient.ui.shell.Destinations
import com.stansful.sshvpnclient.ui.shell.LocalWindowWidthClass
import com.stansful.sshvpnclient.ui.shell.WindowWidthClass
import com.stansful.sshvpnclient.ui.theme.NightColors
import com.stansful.sshvpnclient.ui.theme.Shadow
import com.stansful.sshvpnclient.ui.theme.ShadowColors
import com.stansful.sshvpnclient.ui.theme.ShadowDimens
import com.stansful.sshvpnclient.ui.theme.ShadowTheme
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration
import kotlin.math.roundToInt

/*
 * JVM screenshot harness (Robolectric native graphics + Roborazzi). No emulator needed.
 *
 *   class HomeScreenshotTest : ScreenshotTest() {
 *       @Test
 *       fun home() {
 *           renderScreenshot("Home_Connected_Night", ScreenSize.PHONE, NightColors) {
 *               TopLevelShellFrame(ShellTab.Home) { HomeScreen(state = fakeState, onConnect = {}) }
 *           }
 *       }
 *   }
 *
 * PNGs: app/build/screenshots/<name>.png at density 2.0 (PHONE = 780×1688 px, TABLET = 2560×1600 px).
 * See scratchpad/redesign/SCREENSHOTS.md for the command and caveats.
 */

/**
 * Robolectric SDK the screenshots run on. Robolectric 4.17 boots 37 (= targetSdk), but Espresso 3.7's
 * idling (used by Compose test + Roborazzi) crashes there (`InputManager.getInstance()` is gone).
 */
const val SCREENSHOT_SDK = 36

/** Pixel density of every PNG (xhdpi: 1 dp = 2 px). */
const val SCREENSHOT_DENSITY = 2f

/** Virtual time the harness lets pass before capturing (animations with reduced motion are instant). */
const val DEFAULT_SETTLE_MILLIS = 1_500L

/**
 * Base class for screenshot tests: Robolectric runner, native graphics, SDK [SCREENSHOT_SDK], xhdpi and
 * a plain [Application] (the real SshVpnApplication / AppContainer is never created).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [SCREENSHOT_SDK], application = Application::class, qualifiers = "xhdpi")
abstract class ScreenshotTest

/** A window size in dp. `heightDp = WRAP` crops the PNG to the content's height (galleries, components). */
data class ScreenSize(val widthDp: Int, val heightDp: Int) {
    fun withHeight(heightDp: Int): ScreenSize = copy(heightDp = heightDp)

    val wrapsHeight: Boolean get() = heightDp == WRAP

    companion object {
        /** Height marker: lay out in a [WRAP_MAX_HEIGHT_DP] tall window and crop to the content. */
        const val WRAP = -1

        /** Content taller than this is cut off in [WRAP] mode. */
        const val WRAP_MAX_HEIGHT_DP = 5000

        /** [widthDp] wide, as tall as the content (do not use fillMaxSize/verticalScroll at the root). */
        fun wrap(widthDp: Int = 390): ScreenSize = ScreenSize(widthDp, WRAP)

        /** Phone artboards: 390×844 dp → 780×1688 px. */
        val PHONE = ScreenSize(390, 844)

        /** Tablet artboards (TabletHome, TabletRoutes): 1280×800 dp → 2560×1600 px. */
        val TABLET = ScreenSize(1280, 800)
    }
}

/**
 * System bar insets the harness fakes into the window (dp). The artboards have no status bar: their
 * 14 px top padding IS the status-bar inset that `TopLevelBar` / `WordmarkTopBar` / `SubScreenBar` pad,
 * so [Artboard] (top 14, bottom 0) lines a render up 1:1 with an artboard.
 */
data class SystemBarsDp(val top: Int = 0, val bottom: Int = 0) {
    companion object {
        val Artboard = SystemBarsDp(top = 14, bottom = 0)
        val None = SystemBarsDp(0, 0)

        /** A typical gesture-nav phone (24 dp status bar, 16 dp gesture bar) to check inset handling. */
        val Device = SystemBarsDp(top = 24, bottom = 16)
    }
}

/**
 * Renders [content] inside `ShadowTheme(colors, reducedMotion)` at [size] (density 2.0) and writes
 * `app/build/screenshots/<name>.png` (overwritten every run). Returns the file.
 *
 * Provided around [content]: a full-size `Shadow.colors.bg` background, [LocalToaster] (= [toaster],
 * with a [ToastHost] 24 dp above the bottom, or above the content's `Modifier.toastObstacle()`
 * elements like in the shell), [LocalWindowWidthClass] (from [size]), and fake system
 * bar insets ([systemBars]). NOT provided: `LocalAppContainer` / ViewModels — render the stateless
 * `XxxScreen(state, callbacks)` composables with fake state instead of `XxxRoute(navController)`.
 *
 * The test clock does not auto-advance: the harness composes, advances [settleMillis] of virtual
 * time, runs [interact] (clicks, typing, `toaster.show(...)`, then advances [settleMillis] again) and
 * captures the whole screen — every window, so Dialogs and ModalBottomSheets are included.
 * With [reducedMotion] = true (default) loops stop and transitions snap, so renders are deterministic.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalRoborazziApi::class)
fun renderScreenshot(
    name: String,
    size: ScreenSize = ScreenSize.PHONE,
    colors: ShadowColors = NightColors,
    systemBars: SystemBarsDp = SystemBarsDp.Artboard,
    reducedMotion: Boolean = true,
    windowWidthClass: WindowWidthClass = WindowWidthClass.of(size.widthDp.dp),
    toaster: ToasterState = ToasterState(),
    settleMillis: Long = DEFAULT_SETTLE_MILLIS,
    interact: (ScreenshotScope.() -> Unit)? = null,
    content: @Composable () -> Unit,
): File {
    val file = screenshotFile(name)
    file.parentFile?.mkdirs()
    applyWindowSize(size)
    registerHostActivity()
    runAndroidComposeUiTest(ComponentActivity::class.java) {
        mainClock.autoAdvance = false
        runOnUiThread { activity?.let { installFakeSystemBars(it, systemBars) } }
        setContent {
            ScreenshotFrame(
                colors = colors,
                reducedMotion = reducedMotion,
                windowWidthClass = windowWidthClass,
                toaster = toaster,
                wrapHeight = size.wrapsHeight,
                content = content,
            )
        }
        settle(settleMillis)
        if (interact != null) {
            ScreenshotScope(this).interact()
            settle(settleMillis)
        }
        if (size.wrapsHeight) {
            onNodeWithTag(WRAP_CONTENT_TAG).captureRoboImage(file)
        } else {
            captureScreenRoboImage(file)
        }
    }
    return file
}

/**
 * What [renderScreenshot]'s `interact` block can do before the capture: find nodes and act on them
 * (`onNodeWithText("Delete").performClick()`, `performTextInput`, scrolling), drive the virtual clock
 * and run code on the UI thread (`toaster.show(...)`, state changes).
 */
@OptIn(ExperimentalTestApi::class)
class ScreenshotScope internal constructor(
    private val test: AndroidComposeUiTest<ComponentActivity>,
) : SemanticsNodeInteractionsProvider by test {
    /** The virtual clock (auto-advance is off; the harness advances `settleMillis` after `interact`). */
    val mainClock: MainTestClock get() = test.mainClock

    /** The host activity (a plain ComponentActivity). */
    val activity: ComponentActivity? get() = test.activity

    fun runOnUiThread(block: () -> Unit) {
        test.runOnUiThread(block)
    }

    fun waitForIdle() {
        test.waitForIdle()
    }

    /** Lets [millis] of virtual time pass (Compose clock + main looper in lockstep). */
    fun advanceTimeBy(millis: Long) {
        test.settle(millis)
    }
}

/**
 * Advances the Compose test clock and Robolectric's main looper together, one frame at a time: Compose
 * frames drive animations/effects, the looper delivers snapshot apply notifications, Choreographer
 * traversals of extra windows (Dialog, ModalBottomSheet) and Handler messages.
 */
@OptIn(ExperimentalTestApi::class)
private fun AndroidComposeUiTest<*>.settle(millis: Long) {
    var left = millis
    while (left > 0) {
        val step = minOf(FRAME_MILLIS, left)
        mainClock.advanceTimeBy(step, ignoreFrameDuration = true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(step))
        left -= step
    }
    waitForIdle()
}

/** `app/build/screenshots/<name>.png` (the Gradle test task passes the absolute directory). */
fun screenshotFile(name: String): File {
    require(name.matches(Regex("[A-Za-z0-9_.-]+"))) { "Screenshot name must be [A-Za-z0-9_.-]+: $name" }
    val dir = System.getProperty("shadow.screenshots.dir") ?: "build/screenshots"
    return File(dir, "$name.png").absoluteFile
}

/** Theme + deterministic locals around a screenshot. Usable on its own inside custom test setups. */
@Composable
fun ScreenshotFrame(
    colors: ShadowColors = NightColors,
    reducedMotion: Boolean = true,
    windowWidthClass: WindowWidthClass = WindowWidthClass.Compact,
    toaster: ToasterState = ToasterState(),
    wrapHeight: Boolean = false,
    content: @Composable () -> Unit,
) {
    val toastAnchor = remember { ToastAnchorState() }
    ShadowTheme(colors = colors, reducedMotion = reducedMotion) {
        CompositionLocalProvider(
            LocalToaster provides toaster,
            LocalToastAnchor provides toastAnchor,
            LocalWindowWidthClass provides windowWidthClass,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Shadow.colors.bg),
            ) {
                if (wrapHeight) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .wrapContentHeight(Alignment.Top, unbounded = false)
                            .background(Shadow.colors.bg)
                            .testTag(WRAP_CONTENT_TAG),
                    ) { content() }
                } else {
                    content()
                }
                ToastHost(
                    state = toaster,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        // Like the shell: above the screen's toast obstacles (bottom nav, Routes' bar, footers).
                        .padding(bottom = max(TOAST_BOTTOM_GAP, with(LocalDensity.current) { toastAnchor.clearance.toDp() }))
                        .widthIn(max = TOAST_MAX_WIDTH),
                )
            }
        }
    }
}

/** Top-level destinations of the shell, for [TopLevelShellFrame]. */
enum class ShellTab(val key: String, val label: String) {
    Home(Destinations.HOME, "Home"),
    Servers(Destinations.SERVERS, "Servers"),
    Routes(Destinations.ROUTES, "Routes"),
    Settings(Destinations.SETTINGS, "Settings"),
}

/** The shell's nav items (same keys, labels and icons as `ShadowApp`). */
val ShellNavItems: List<ShadowNavItem> = listOf(
    ShadowNavItem(ShellTab.Home.key, ShellTab.Home.label, ShadowIcons.Home),
    ShadowNavItem(ShellTab.Servers.key, ShellTab.Servers.label, ShadowIcons.Server),
    ShadowNavItem(ShellTab.Routes.key, ShellTab.Routes.label, ShadowIcons.Routes),
    ShadowNavItem(ShellTab.Settings.key, ShellTab.Settings.label, ShadowIcons.Settings),
)

/**
 * Replica of `ShadowApp`'s layout for a top-level destination without a NavHost or AppContainer:
 * bottom nav (Compact) or rail (Medium/Expanded, from [LocalWindowWidthClass]) over the bg, and
 * [content] in the area inset by the nav and the system bars it covers (those insets consumed),
 * exactly like `TopLevelFrame`. [railTone] colors the rail's state dot (Neutral = off, no glow).
 */
@Composable
fun TopLevelShellFrame(
    selected: ShellTab,
    railTone: StatusTone = StatusTone.Neutral,
    content: @Composable BoxScope.() -> Unit,
) {
    val compact = LocalWindowWidthClass.current == WindowWidthClass.Compact
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val sides = WindowInsets.systemBars.union(WindowInsets.displayCutout)
        .only(WindowInsetsSides.Horizontal)
        .asPaddingValues(density)
    val navigationBarBottom = WindowInsets.navigationBars.asPaddingValues(density).calculateBottomPadding()
    val padding = if (compact) {
        PaddingValues(
            start = sides.calculateStartPadding(layoutDirection),
            end = sides.calculateEndPadding(layoutDirection),
            bottom = ShadowDimens.BottomNav + navigationBarBottom,
        )
    } else {
        PaddingValues(
            start = ShadowDimens.NavRail +
                WindowInsets.systemBars.only(WindowInsetsSides.Start)
                    .asPaddingValues(density)
                    .calculateStartPadding(layoutDirection),
            end = sides.calculateEndPadding(layoutDirection),
            bottom = navigationBarBottom,
        )
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Shadow.colors.bg),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
            content = content,
        )
        if (compact) {
            ShadowBottomNav(
                items = ShellNavItems,
                selectedKey = selected.key,
                onSelect = {},
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    // The shell keeps toasts 12 dp above the bottom nav.
                    .toastObstacle(),
            )
        } else {
            ShadowNavRail(
                items = ShellNavItems,
                selectedKey = selected.key,
                onSelect = {},
                dotColor = Shadow.colors.stateDotColor(railTone),
                dotGlow = railTone != StatusTone.Neutral,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight(),
            )
        }
    }
}

private fun applyWindowSize(size: ScreenSize) {
    val height = if (size.wrapsHeight) ScreenSize.WRAP_MAX_HEIGHT_DP else size.heightDp
    val orientation = if (size.widthDp > height) "land" else "port"
    RuntimeEnvironment.setQualifiers("w${size.widthDp}dp-h${height}dp-$orientation-xhdpi")
}

private fun registerHostActivity() {
    val app: Application = ApplicationProvider.getApplicationContext()
    shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
}

/**
 * Edge-to-edge like MainActivity, and every insets dispatch to the window is replaced by [bars]
 * (status bar top, navigation bar bottom), so `WindowInsets.statusBars` etc. read the fake values.
 */
private fun installFakeSystemBars(activity: ComponentActivity, bars: SystemBarsDp) {
    activity.enableEdgeToEdge()
    val density = activity.resources.displayMetrics.density
    val fake: PlatformWindowInsets = checkNotNull(
        WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, (bars.top * density).roundToInt(), 0, 0))
            .setInsets(
                WindowInsetsCompat.Type.navigationBars(),
                Insets.of(0, 0, 0, (bars.bottom * density).roundToInt()),
            )
            .setInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.statusBars(),
                Insets.of(0, (bars.top * density).roundToInt(), 0, 0),
            )
            .setInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.navigationBars(),
                Insets.of(0, 0, 0, (bars.bottom * density).roundToInt()),
            )
            .build()
            .toWindowInsets(),
    )
    val decor: View = activity.window.decorView
    decor.setOnApplyWindowInsetsListener { _, _ -> fake }
    decor.requestApplyInsets()
}

private const val FRAME_MILLIS = 16L
private const val WRAP_CONTENT_TAG = "screenshot-content"
private val TOAST_BOTTOM_GAP = 24.dp
private val TOAST_MAX_WIDTH = 560.dp
