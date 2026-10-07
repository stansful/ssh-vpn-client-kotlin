package com.stansful.sshvpnclient.ui.shell

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.stansful.sshvpnclient.AppContainer
import com.stansful.sshvpnclient.domain.model.VpnConnectionStatus
import com.stansful.sshvpnclient.ui.activity.ActivityRoute
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
import com.stansful.sshvpnclient.ui.designsystem.rememberToasterState
import com.stansful.sshvpnclient.ui.designsystem.stateDotColor
import com.stansful.sshvpnclient.ui.home.HomeRoute
import com.stansful.sshvpnclient.ui.routes.RoutesRoute
import com.stansful.sshvpnclient.ui.servers.KeyEditorRoute
import com.stansful.sshvpnclient.ui.servers.ServerEditorRoute
import com.stansful.sshvpnclient.ui.servers.ServersRoute
import com.stansful.sshvpnclient.ui.settings.AppRoutingRoute
import com.stansful.sshvpnclient.ui.settings.AppearanceRoute
import com.stansful.sshvpnclient.ui.settings.SettingsRoute
import com.stansful.sshvpnclient.ui.settings.UpdateSheetHost
import com.stansful.sshvpnclient.ui.terminal.TerminalRoute
import com.stansful.sshvpnclient.ui.theme.Shadow
import com.stansful.sshvpnclient.ui.theme.ShadowDimens
import com.stansful.sshvpnclient.ui.theme.ShadowMotion
import com.stansful.sshvpnclient.ui.theme.shadowTween
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.sign

/** Window width buckets: Compact < 600 dp (bottom nav), Medium 600–840 (rail), Expanded ≥ 840 (rail + two panes). */
enum class WindowWidthClass {
    Compact,
    Medium,
    Expanded,
    ;

    companion object {
        fun of(width: Dp): WindowWidthClass = when {
            width < MEDIUM_MIN_WIDTH -> Compact
            width < EXPANDED_MIN_WIDTH -> Medium
            else -> Expanded
        }
    }
}

/** Width class of the whole window (Home and Routes switch to two panes at [WindowWidthClass.Expanded]). */
val LocalWindowWidthClass: ProvidableCompositionLocal<WindowWidthClass> =
    staticCompositionLocalOf { WindowWidthClass.Compact }

/** A request from outside the UI (quick tile) to show a destination; [id] makes repeats distinct. */
@Immutable
data class ShellLaunchRequest(
    val id: Long,
    val route: String,
)

/**
 * Root of the redesigned app: provides [LocalAppContainer], [LocalToaster] and the Activity
 * ViewModel owner, lays out the adaptive navigation (bottom nav < 600 dp, rail ≥ 600 dp, both only
 * on top-level destinations), hosts every destination with the design transitions and renders the
 * update sheet and toasts once.
 *
 * Top-level screens get their area already inset for the nav and the navigation bar (they only pad
 * the status bar, which `TopLevelBar` / `WordmarkTopBar` do). Sub-screens fill the window and pad
 * the navigation bar (and IME) themselves.
 */
@Composable
fun ShadowApp(
    container: AppContainer,
    modifier: Modifier = Modifier,
    launchRequest: ShellLaunchRequest? = null,
    onLaunchRequestHandled: () -> Unit = {},
) {
    val navController = rememberNavController()
    val toaster = rememberToasterState()
    val toastAnchor = remember { ToastAnchorState() }
    val activityOwner = LocalViewModelStoreOwner.current
    CompositionLocalProvider(
        LocalAppContainer provides container,
        LocalToaster provides toaster,
        LocalToastAnchor provides toastAnchor,
        LocalActivityViewModelStoreOwner provides activityOwner,
    ) {
        BoxWithConstraints(
            modifier = modifier
                .fillMaxSize()
                .background(Shadow.colors.bg),
        ) {
            val widthClass = WindowWidthClass.of(maxWidth)
            CompositionLocalProvider(LocalWindowWidthClass provides widthClass) {
                ShellScaffold(
                    navController = navController,
                    toaster = toaster,
                    toastAnchor = toastAnchor,
                    navigation = if (widthClass == WindowWidthClass.Compact) {
                        ShellNavigation.BottomBar
                    } else {
                        ShellNavigation.Rail
                    },
                )
            }
        }
        UpdateSheetHost(onGoHome = { navController.navigateTopLevel(Destinations.HOME) })
    }

    LaunchedEffect(container) {
        // Creating the coordinator schedules its automatic update check (~1.5 s later), once per
        // process, whether or not a screen that shows update state is open yet.
        container.appUpdateCoordinator
    }

    val currentOnLaunchRequestHandled by rememberUpdatedState(onLaunchRequestHandled)
    LaunchedEffect(launchRequest) {
        val request = launchRequest ?: return@LaunchedEffect
        navController.openFromOutside(request.route)
        currentOnLaunchRequestHandled()
    }
}

private enum class ShellNavigation {
    BottomBar,
    Rail,
}

@Immutable
private data class TopLevelDestination(
    val pattern: String,
    val route: String,
    val item: ShadowNavItem,
)

private val TopLevelDestinations = listOf(
    TopLevelDestination(
        pattern = Destinations.HOME,
        route = Destinations.HOME,
        item = ShadowNavItem(Destinations.HOME, "Home", ShadowIcons.Home),
    ),
    TopLevelDestination(
        pattern = Destinations.SERVERS,
        route = Destinations.servers(),
        item = ShadowNavItem(Destinations.SERVERS, "Servers", ShadowIcons.Server),
    ),
    TopLevelDestination(
        pattern = Destinations.ROUTES,
        route = Destinations.ROUTES,
        item = ShadowNavItem(Destinations.ROUTES, "Routes", ShadowIcons.Routes),
    ),
    TopLevelDestination(
        pattern = Destinations.SETTINGS,
        route = Destinations.settings(),
        item = ShadowNavItem(Destinations.SETTINGS, "Settings", ShadowIcons.Settings),
    ),
)

private val NavItems = TopLevelDestinations.map(TopLevelDestination::item)

@Composable
private fun ShellScaffold(
    navController: NavHostController,
    toaster: ToasterState,
    toastAnchor: ToastAnchorState,
    navigation: ShellNavigation,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentPattern = backStackEntry?.destination?.route
    // Before the NavHost reports its first entry the start destination (Home) is showing.
    val showNav = currentPattern == null || Destinations.isTopLevel(currentPattern)
    var lastTopLevel by rememberSaveable { mutableStateOf(Destinations.HOME) }
    LaunchedEffect(currentPattern) {
        if (currentPattern != null && Destinations.isTopLevel(currentPattern)) lastTopLevel = currentPattern
    }
    // While the nav slides away under a sub-screen it keeps highlighting the destination below it.
    val selectedKey = if (currentPattern != null && showNav) currentPattern else lastTopLevel
    val onSelect: (ShadowNavItem) -> Unit = { item ->
        // Re-tapping the current destination does nothing (no reload, no scroll reset), as before.
        if (item.key != currentPattern) {
            TopLevelDestinations.firstOrNull { it.pattern == item.key }?.let { destination ->
                navController.navigateTopLevel(destination.route)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ShellNavHost(
            navController = navController,
            navigation = navigation,
            modifier = Modifier.fillMaxSize(),
        )
        when (navigation) {
            ShellNavigation.BottomBar -> BottomNavigation(
                visible = showNav,
                selectedKey = selectedKey,
                onSelect = onSelect,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            ShellNavigation.Rail -> RailNavigation(
                visible = showNav,
                selectedKey = selectedKey,
                onSelect = onSelect,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight(),
            )
        }
        ShellToasts(
            toaster = toaster,
            toastAnchor = toastAnchor,
            navigation = navigation,
            navVisible = showNav,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun BottomNavigation(
    visible: Boolean,
    selectedKey: String,
    onSelect: (ShadowNavItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInVertically(shadowTween(ShadowMotion.Swap)) { it } +
            fadeIn(shadowTween(ShadowMotion.Swap)),
        exit = slideOutVertically(shadowTween(ShadowMotion.Swap, ShadowMotion.Exit)) { it } +
            fadeOut(shadowTween(ShadowMotion.Swap, ShadowMotion.Exit)),
    ) {
        ShadowBottomNav(items = NavItems, selectedKey = selectedKey, onSelect = onSelect)
    }
}

@Composable
private fun RailNavigation(
    visible: Boolean,
    selectedKey: String,
    onSelect: (ShadowNavItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    val statusFlow = remember(container) {
        container.vpnConnectionRepository.state
            .map { state -> state.status }
            .distinctUntilChanged()
    }
    val status by statusFlow.collectAsStateWithLifecycle(
        initialValue = container.vpnConnectionRepository.currentState.status,
    )
    val tone = status.railTone()
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInHorizontally(shadowTween(ShadowMotion.Swap)) { -it } +
            fadeIn(shadowTween(ShadowMotion.Swap)),
        exit = slideOutHorizontally(shadowTween(ShadowMotion.Swap, ShadowMotion.Exit)) { -it } +
            fadeOut(shadowTween(ShadowMotion.Swap, ShadowMotion.Exit)),
    ) {
        ShadowNavRail(
            items = NavItems,
            selectedKey = selectedKey,
            onSelect = onSelect,
            dotColor = Shadow.colors.stateDotColor(tone),
            dotGlow = tone != StatusTone.Neutral,
        )
    }
}

private fun VpnConnectionStatus.railTone(): StatusTone = when (this) {
    VpnConnectionStatus.CONNECTED -> StatusTone.Success
    VpnConnectionStatus.CONNECTING,
    VpnConnectionStatus.RECONNECTING,
    VpnConnectionStatus.DISCONNECTING,
    -> StatusTone.Progress
    VpnConnectionStatus.ERROR -> StatusTone.Error
    VpnConnectionStatus.DISCONNECTED -> StatusTone.Neutral
}

/**
 * Toasts sit 12 dp above the bottom nav, 24 dp above the screen bottom elsewhere, above the IME, and
 * above whatever the current screen marks with `Modifier.toastObstacle()` ([toastAnchor]).
 */
@Composable
private fun ShellToasts(
    toaster: ToasterState,
    toastAnchor: ToastAnchorState,
    navigation: ShellNavigation,
    navVisible: Boolean,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val navigationBarInset = WindowInsets.navigationBars.asPaddingValues(density).calculateBottomPadding()
    val imeInset = WindowInsets.ime.asPaddingValues(density).calculateBottomPadding()
    val bottomNavShown = navigation == ShellNavigation.BottomBar && navVisible
    val railShown = navigation == ShellNavigation.Rail && navVisible
    val aboveNav by animateDpAsState(
        targetValue = if (bottomNavShown) ShadowDimens.BottomNav + TOAST_NAV_GAP else TOAST_SCREEN_GAP,
        animationSpec = shadowTween(ShadowMotion.Swap),
        label = "toast-bottom",
    )
    val railStart = WindowInsets.systemBars.only(WindowInsetsSides.Start)
        .asPaddingValues(density)
        .calculateStartPadding(layoutDirection)
    val start by animateDpAsState(
        targetValue = if (railShown) ShadowDimens.NavRail + railStart else 0.dp,
        animationSpec = shadowTween(ShadowMotion.Swap),
        label = "toast-start",
    )
    val obstacle = with(density) { toastAnchor.clearance.toDp() }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = start,
                bottom = max(max(aboveNav + navigationBarInset, imeInset + TOAST_NAV_GAP), obstacle),
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        ToastHost(state = toaster, modifier = Modifier.widthIn(max = TOAST_MAX_WIDTH))
    }
}

@Composable
private fun ShellNavHost(
    navController: NavHostController,
    navigation: ShellNavigation,
    modifier: Modifier = Modifier,
) {
    val reducedMotion = Shadow.reducedMotion
    val slide = with(LocalDensity.current) { PUSH_SLIDE.roundToPx() }
    NavHost(
        navController = navController,
        startDestination = Destinations.HOME,
        modifier = modifier,
        enterTransition = {
            when {
                reducedMotion -> EnterTransition.None
                isTopLevelSwitch() -> fadeIn(tween(ShadowMotion.Small, easing = ShadowMotion.Ease))
                else -> pushEnter(slide, AnimatedContentTransitionScope.SlideDirection.Start)
            }
        },
        exitTransition = {
            when {
                reducedMotion -> ExitTransition.None
                isTopLevelSwitch() -> fadeOut(tween(ShadowMotion.Small, easing = ShadowMotion.Ease))
                else -> pushExit(slide, AnimatedContentTransitionScope.SlideDirection.Start)
            }
        },
        popEnterTransition = {
            when {
                reducedMotion -> EnterTransition.None
                isTopLevelSwitch() -> fadeIn(tween(ShadowMotion.Small, easing = ShadowMotion.Ease))
                else -> pushEnter(slide, AnimatedContentTransitionScope.SlideDirection.End)
            }
        },
        popExitTransition = {
            when {
                reducedMotion -> ExitTransition.None
                isTopLevelSwitch() -> fadeOut(tween(ShadowMotion.Small, easing = ShadowMotion.Ease))
                else -> pushExit(slide, AnimatedContentTransitionScope.SlideDirection.End)
            }
        },
    ) {
        topLevelDestinations(navController, navigation)
        subScreenDestinations(navController)
    }
}

private fun NavGraphBuilder.topLevelDestinations(
    navController: NavHostController,
    navigation: ShellNavigation,
) {
    composable(Destinations.HOME) {
        TopLevelFrame(navigation) { HomeRoute(navController) }
    }
    composable(
        route = Destinations.SERVERS,
        arguments = listOf(optionalStringArgument(Destinations.ARG_TAB)),
    ) { entry ->
        TopLevelFrame(navigation) {
            ServersRoute(navController, initialTab = entry.arguments?.getString(Destinations.ARG_TAB))
        }
    }
    composable(Destinations.ROUTES) {
        TopLevelFrame(navigation) { RoutesRoute(navController) }
    }
    composable(
        route = Destinations.SETTINGS,
        arguments = listOf(optionalStringArgument(Destinations.ARG_SECTION)),
    ) { entry ->
        TopLevelFrame(navigation) {
            SettingsRoute(navController, section = entry.arguments?.getString(Destinations.ARG_SECTION))
        }
    }
}

private fun NavGraphBuilder.subScreenDestinations(navController: NavHostController) {
    composable(
        route = Destinations.SERVER_EDIT,
        arguments = listOf(navArgument(Destinations.ARG_CONFIG_ID) { type = NavType.StringType }),
    ) { entry ->
        ServerEditorRoute(
            navController = navController,
            configId = entry.arguments?.getString(Destinations.ARG_CONFIG_ID).editorIdOrNull(),
        )
    }
    composable(
        route = Destinations.KEY_EDIT,
        arguments = listOf(navArgument(Destinations.ARG_KEY_ID) { type = NavType.StringType }),
    ) { entry ->
        KeyEditorRoute(
            navController = navController,
            keyId = entry.arguments?.getString(Destinations.ARG_KEY_ID).editorIdOrNull(),
        )
    }
    composable(Destinations.TERMINAL) { TerminalRoute(navController) }
    composable(Destinations.ACTIVITY) { ActivityRoute(navController) }
    composable(Destinations.APPEARANCE) { AppearanceRoute(navController) }
    composable(Destinations.APP_ROUTING) { AppRoutingRoute(navController) }
}

private fun optionalStringArgument(name: String) = navArgument(name) {
    type = NavType.StringType
    nullable = true
    defaultValue = null
}

/**
 * Area of a top-level destination: inset by the nav (bottom bar or rail) and the system bars it
 * covers; those insets are consumed so the screen pads only the status bar.
 */
@Composable
private fun TopLevelFrame(
    navigation: ShellNavigation,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val sides = WindowInsets.systemBars.union(WindowInsets.displayCutout)
        .only(WindowInsetsSides.Horizontal)
        .asPaddingValues(density)
    val navigationBarBottom = WindowInsets.navigationBars.asPaddingValues(density).calculateBottomPadding()
    val padding = when (navigation) {
        ShellNavigation.BottomBar -> PaddingValues(
            start = sides.calculateStartPadding(layoutDirection),
            end = sides.calculateEndPadding(layoutDirection),
            bottom = ShadowDimens.BottomNav + navigationBarBottom,
        )
        ShellNavigation.Rail -> PaddingValues(
            start = ShadowDimens.NavRail +
                WindowInsets.systemBars.only(WindowInsetsSides.Start)
                    .asPaddingValues(density)
                    .calculateStartPadding(layoutDirection),
            end = sides.calculateEndPadding(layoutDirection),
            bottom = navigationBarBottom,
        )
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .consumeWindowInsets(padding),
    ) {
        content()
    }
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.isTopLevelSwitch(): Boolean =
    Destinations.isTopLevel(initialState.destination.route) &&
        Destinations.isTopLevel(targetState.destination.route)

/**
 * Fade + [slide] px slide. `slideIntoContainer`/`slideOutOfContainer` pass a full-width offset whose
 * sign encodes the direction (and the layout direction); keep the sign, shorten it to [slide].
 */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.pushEnter(
    slide: Int,
    towards: AnimatedContentTransitionScope.SlideDirection,
): EnterTransition =
    fadeIn(tween(ShadowMotion.Swap, easing = ShadowMotion.Standard)) +
        slideIntoContainer(
            towards = towards,
            animationSpec = tween(ShadowMotion.Swap, easing = ShadowMotion.Standard),
            initialOffset = { fullSlide -> fullSlide.sign * slide },
        )

private fun AnimatedContentTransitionScope<NavBackStackEntry>.pushExit(
    slide: Int,
    towards: AnimatedContentTransitionScope.SlideDirection,
): ExitTransition =
    fadeOut(tween(ShadowMotion.Swap, easing = ShadowMotion.Standard)) +
        slideOutOfContainer(
            towards = towards,
            animationSpec = tween(ShadowMotion.Swap, easing = ShadowMotion.Standard),
            targetOffset = { fullSlide -> fullSlide.sign * slide },
        )

/**
 * Opens a route requested from outside the UI (the quick tile): only the top-level destinations of
 * [Destinations.externalRouteOrNull]; anything else is ignored, and a failed navigation never throws.
 */
private fun NavHostController.openFromOutside(route: String) {
    val allowed = Destinations.externalRouteOrNull(route) ?: return
    runCatching { navigateTopLevel(allowed) }
}

private val MEDIUM_MIN_WIDTH = 600.dp
private val EXPANDED_MIN_WIDTH = 840.dp
private val PUSH_SLIDE = 24.dp
private val TOAST_NAV_GAP = 12.dp
private val TOAST_SCREEN_GAP = 24.dp
private val TOAST_MAX_WIDTH = 560.dp
