package com.stansful.sshvpnclient.ui.home

import android.net.VpnService
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.stansful.sshvpnclient.domain.model.AppSettings
import com.stansful.sshvpnclient.domain.model.GlobalTab
import com.stansful.sshvpnclient.domain.model.SmartConnectPhase
import com.stansful.sshvpnclient.domain.model.VpnMode
import com.stansful.sshvpnclient.ui.designsystem.LocalToaster
import com.stansful.sshvpnclient.ui.designsystem.ShadowIcons
import com.stansful.sshvpnclient.ui.designsystem.ToastTone
import com.stansful.sshvpnclient.ui.designsystem.rememberClipboardCopier
import com.stansful.sshvpnclient.ui.main.MainViewModel
import com.stansful.sshvpnclient.ui.opensource.OpenSourceViewModel
import com.stansful.sshvpnclient.ui.shell.Destinations
import com.stansful.sshvpnclient.ui.shell.LocalAppContainer
import com.stansful.sshvpnclient.ui.shell.LocalWindowWidthClass
import com.stansful.sshvpnclient.ui.shell.WindowWidthClass
import com.stansful.sshvpnclient.ui.shell.activityViewModel
import com.stansful.sshvpnclient.ui.shell.navigateTo
import com.stansful.sshvpnclient.ui.shell.navigateTopLevel
import com.stansful.sshvpnclient.ui.shell.rememberConsentGate
import com.stansful.sshvpnclient.ui.shell.rememberVpnPermissionRequester
import com.stansful.sshvpnclient.ui.smartconnect.SmartConnectViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Home: mode switch, connection orb and quick actions (Auto · Server · Routes). */
@Composable
fun HomeRoute(navController: NavHostController) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val copier = rememberClipboardCopier()
    val consentGate = rememberConsentGate()
    val mainViewModel = activityViewModel<MainViewModel>()
    val smartViewModel = activityViewModel<SmartConnectViewModel>()
    val routesViewModel = activityViewModel<OpenSourceViewModel>()
    // The repositories' StateFlows hold the current settings and VPN state right away, so the first
    // frame already shows the stored mode instead of the ViewModels' empty initial state.
    val settings by container.appSettingsRepository.settings.collectAsStateWithLifecycle()
    val vpnState by container.vpnConnectionRepository.state.collectAsStateWithLifecycle(
        initialValue = container.vpnConnectionRepository.currentState,
    )
    val mainState by mainViewModel.uiState.collectAsStateWithLifecycle()
    val autoState by smartViewModel.uiState.collectAsStateWithLifecycle()
    val routesState by routesViewModel.uiState.collectAsStateWithLifecycle()
    // The Route library screen filters `routesState.profiles`; Home always reads the whole library.
    val routeLibrary by remember(container) { container.proxyProfileRepository.observeSummaries() }
        .collectAsStateWithLifecycle(initialValue = null)

    var forcedMode by rememberSaveable { mutableStateOf<HomeMode?>(null) }
    var pendingSwitch by rememberSaveable { mutableStateOf<HomeMode?>(null) }
    var deniedMode by rememberSaveable { mutableStateOf<HomeMode?>(null) }
    var permissionFor by rememberSaveable { mutableStateOf<HomeMode?>(null) }
    var dismissedUpdate by rememberSaveable { mutableStateOf<String?>(null) }
    var checkedRouteId by rememberSaveable { mutableStateOf<String?>(null) }
    // A route check started here (not one started in the Route library) while it runs.
    var homeCheckRunning by remember { mutableStateOf(false) }
    var sheet by rememberSaveable { mutableStateOf<HomeSheet?>(null) }

    val retrying = autoState.workflow.phase == SmartConnectPhase.RETRY_WAIT && autoState.retryWaitStartedAtMs != null
    val now by produceState(SystemClock.elapsedRealtime(), retrying) {
        while (retrying) {
            value = SystemClock.elapsedRealtime()
            delay(CLOCK_TICK_MS)
        }
    }
    val tablet = LocalWindowWidthClass.current == WindowWidthClass.Expanded
    val appLabels by produceState(emptyList<String>(), tablet, settings.vpnMode, settings.selectedAppPackages) {
        value = if (tablet && settings.vpnMode == VpnMode.SELECTED_APPS) {
            withContext(Dispatchers.IO) {
                runCatching { container.installedAppsRepository.getInstalledApps() }.getOrDefault(emptyList())
            }.filter { it.packageName in settings.selectedAppPackages }.map { it.label }
        } else {
            emptyList()
        }
    }

    val sources = HomeSources(
        main = mainState.copy(appSettings = settings, vpnState = vpnState),
        auto = autoState,
        routes = routesState,
        forcedMode = forcedMode,
        permissionDeniedMode = deniedMode,
        pendingSwitch = pendingSwitch,
        dismissedUpdateKey = dismissedUpdate,
        checkedRouteId = checkedRouteId,
        nowElapsedMs = now,
        selectedAppLabels = appLabels,
        routeLibrary = routeLibrary,
    )
    val latest by rememberUpdatedState(sources)
    val state = remember(sources) { buildHomeUiState(sources) }
    val latestState by rememberUpdatedState(state)
    val activeOwner = sources.activeOwnerMode()

    val permission = rememberVpnPermissionRequester(
        onGranted = {
            when (permissionFor) {
                HomeMode.Server -> mainViewModel.connect()
                HomeMode.Auto -> smartViewModel.start()
                HomeMode.Routes -> routesViewModel.connect()
                null -> Unit
            }
            permissionFor = null
        },
        onDenied = {
            deniedMode = permissionFor
            when (permissionFor) {
                HomeMode.Server -> mainViewModel.onVpnPermissionDenied()
                HomeMode.Auto -> smartViewModel.onVpnPermissionDenied()
                HomeMode.Routes, null -> Unit
            }
            permissionFor = null
        },
        onCancelled = { permissionFor = null },
    )

    // Lifecycle, ported from the old Smart Connect and Public Routes screens.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        smartViewModel.refreshXrayCoreAvailability()
        routesViewModel.refreshXrayCoreAvailability()
        if (!latest.routes.isChecking) homeCheckRunning = false
        val auto = latest.auto
        if (auto.workflow.desiredActive && !auto.ownsVpnSession && !auto.isStartPending) {
            smartViewModel.reconcilePersistedSession(vpnPermissionGranted = VpnService.prepare(context) == null)
        }
    }
    // Like the old Public Routes screen: a route check started here stops when Home leaves the screen or
    // the app goes to the background. A check started in the Route library is left alone.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (homeCheckRunning && latest.routes.isChecking) routesViewModel.cancelChecks()
        homeCheckRunning = false
    }
    LaunchedEffect(routesState.isChecking) {
        if (!routesState.isChecking) homeCheckRunning = false
    }
    // The engine install key: a restore deferred while the engine was installing runs once it's done.
    LaunchedEffect(
        autoState.workflow.desiredActive,
        autoState.ownsVpnSession,
        autoState.isStartPending,
        autoState.xrayCoreUpdate.isDownloading,
    ) {
        if (autoState.workflow.desiredActive && !autoState.ownsVpnSession && !autoState.isStartPending) {
            smartViewModel.reconcilePersistedSession(vpnPermissionGranted = VpnService.prepare(context) == null)
        }
    }
    // A switch in progress shows the new mode until the stopped session lets go (at most 5 s).
    LaunchedEffect(forcedMode, activeOwner) {
        val forced = forcedMode ?: return@LaunchedEffect
        if (activeOwner == null || activeOwner == forced) {
            forcedMode = null
        } else {
            delay(FORCED_MODE_TIMEOUT_MS)
            forcedMode = null
        }
    }
    // A denied-permission notice is stale once a session of any mode is running (e.g. from the tile).
    // A Routes session Home shows (tile start, restore) was consented to already: ending it must not
    // bring up the Routes consent sheet.
    LaunchedEffect(activeOwner) {
        if (activeOwner != null) deniedMode = null
        if (activeOwner == HomeMode.Routes) consentGate.acknowledgeRunningRoutes()
    }
    // A session started elsewhere (quick tile, restore) makes its mode Home's mode.
    LaunchedEffect(activeOwner, forcedMode, settings.activeGlobalTab) {
        if (forcedMode == null && activeOwner != null && activeOwner.tab != settings.activeGlobalTab) {
            mainViewModel.setActiveGlobalTab(activeOwner.tab)
        }
    }
    // Entering Auto/Routes from outside the switch (stored at launch, set by the tile) asks for consent too.
    LaunchedEffect(settings.activeGlobalTab, activeOwner == null) {
        if (activeOwner != null || forcedMode != null) return@LaunchedEffect
        val fallback = { mainViewModel.setActiveGlobalTab(GlobalTab.SHADOW_SSH) }
        when (HomeMode.of(settings.activeGlobalTab)) {
            HomeMode.Auto -> consentGate.requestAuto(onGranted = {}, onDenied = fallback)
            HomeMode.Routes -> consentGate.requestRoutes(onGranted = {}, onDenied = fallback)
            HomeMode.Server -> Unit
        }
    }

    fun stopSession(mode: HomeMode) {
        when (mode) {
            HomeMode.Server -> mainViewModel.disconnect()
            HomeMode.Auto -> smartViewModel.stop()
            HomeMode.Routes -> routesViewModel.disconnect()
        }
    }

    fun applyMode(target: HomeMode) {
        deniedMode = null
        sheet = null
        mainViewModel.setActiveGlobalTab(target.tab)
    }

    fun requestMode(target: HomeMode) {
        val commit = {
            val current = latest.displayedMode()
            when {
                current == target -> Unit
                latest.isSessionActive(current) -> pendingSwitch = target
                else -> applyMode(target)
            }
        }
        when (target) {
            HomeMode.Auto -> consentGate.requestAuto(onGranted = commit)
            HomeMode.Routes -> consentGate.requestRoutes(onGranted = commit)
            HomeMode.Server -> commit()
        }
    }

    fun connectOrStop() {
        val current = latest
        when (current.displayedMode()) {
            HomeMode.Server -> when {
                current.main.canDisconnect -> mainViewModel.disconnect()
                current.main.canConnect -> {
                    deniedMode = null
                    if (current.main.appSettings.requiresSelectedAppsButHasNone()) {
                        mainViewModel.connect()
                    } else {
                        permissionFor = HomeMode.Server
                        permission.request()
                    }
                }
            }
            HomeMode.Auto -> if (current.auto.isActive) {
                smartViewModel.stop()
            } else {
                deniedMode = null
                if (smartViewModel.prepareStart()) {
                    permissionFor = HomeMode.Auto
                    permission.request()
                }
            }
            HomeMode.Routes -> when {
                current.routes.xrayConnected -> routesViewModel.disconnect()
                current.canStartRoutes() -> {
                    deniedMode = null
                    if (current.main.appSettings.requiresSelectedAppsButHasNone()) {
                        routesViewModel.connect()
                    } else {
                        permissionFor = HomeMode.Routes
                        permission.request()
                    }
                }
            }
        }
    }

    fun dismissNoApps() {
        mainViewModel.dismissNoSelectedAppsDialog()
        smartViewModel.dismissNoSelectedAppsDialog()
        routesViewModel.dismissNoSelectedAppsDialog()
    }

    val actions = remember(navController) {
        HomeActions(
            onModeSelect = ::requestMode,
            onOrbClick = ::connectOrStop,
            onOpenActivity = { navController.navigateTo(Destinations.ACTIVITY) },
            onOpenAppRouting = { navController.navigateTo(Destinations.APP_ROUTING) },
            onOpenRouteLibrary = { navController.navigateTopLevel(Destinations.ROUTES) },
            onOpenTerminal = { navController.navigateTo(Destinations.TERMINAL) },
            onCheckTunnel = mainViewModel::checkTunnel,
            onCheckRoute = {
                // Exactly the route Home shows (the whole library's active one, whatever the Route
                // library's search or filter hides), checked as a single-route check.
                latest.activeRoute()?.id?.let { id ->
                    checkedRouteId = id
                    homeCheckRunning = true
                    routesViewModel.checkRoute(id)
                }
            },
            onSelectServer = { id ->
                // Like the Servers list: a storage failure keeps the old server and says so.
                mainViewModel.selectConfig(id) {
                    toaster.show("Couldn’t save your choice. Try again.", tone = ToastTone.Error)
                }
            },
            onManageServers = { navController.navigateTopLevel(Destinations.servers()) },
            onOpenKeys = { navController.navigateTopLevel(Destinations.servers(Destinations.TAB_KEYS)) },
            onAddServer = { navController.navigateTo(Destinations.serverEdit()) },
            onEditSelectedServer = {
                latest.main.selectedConfig?.let { navController.navigateTo(Destinations.serverEdit(it.id)) }
            },
            onSwitchRoute = { id ->
                val route = latest.libraryRoutes().firstOrNull { it.id == id }
                if (route?.isStale == true) {
                    // Like the route library: an outdated route can't connect, the session is kept.
                    toaster.show(
                        message = "${route.name} is outdated",
                        detail = "It left the public list. Pick a current route to connect.",
                        tone = ToastTone.Info,
                    )
                } else {
                    routesViewModel.switchToProfile(id)
                }
            },
            onDownloadEngine = { navController.navigateTopLevel(Destinations.settings(Destinations.SECTION_ENGINE)) },
            onOpenUpdates = { navController.navigateTopLevel(Destinations.settings(Destinations.SECTION_UPDATES)) },
            onDismissUpdate = {
                dismissedUpdate = latestState.updateBanner?.key
                toaster.show(
                    message = "Hidden for now. It’s still in Settings.",
                    tone = ToastTone.Neutral,
                    icon = ShadowIcons.Info,
                    actionLabel = "Undo",
                    onAction = { dismissedUpdate = null },
                )
            },
            onConfirmSwitch = {
                val target = pendingSwitch
                pendingSwitch = null
                if (target != null) {
                    stopSession(latest.displayedMode())
                    forcedMode = target
                    applyMode(target)
                }
            },
            onCancelSwitch = { pendingSwitch = null },
            onPickApps = {
                dismissNoApps()
                navController.navigateTo(Destinations.APP_ROUTING)
            },
            onDismissNoApps = ::dismissNoApps,
            onCopyActivity = {
                val lines = latest.main.vpnState.diagnostics
                copier.copy(label = "Connection activity", text = lines.joinToString(separator = "\n"), toast = null)
                val count = if (lines.size == 1) "1 line" else "${lines.size} lines"
                toaster.show(message = "Copied $count · they include IP addresses")
            },
            onOpenSettings = { navController.navigateTopLevel(Destinations.settings()) },
        )
    }

    HomeScreen(
        state = state,
        actions = actions,
        sheet = sheet,
        onSheetChange = { sheet = it },
    )
}

private fun AppSettings.requiresSelectedAppsButHasNone(): Boolean =
    vpnMode == VpnMode.SELECTED_APPS && selectedAppPackages.isEmpty()

private const val CLOCK_TICK_MS = 1_000L
private const val FORCED_MODE_TIMEOUT_MS = 5_000L
