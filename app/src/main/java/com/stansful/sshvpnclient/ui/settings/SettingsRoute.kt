package com.stansful.sshvpnclient.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.stansful.sshvpnclient.BuildConfig
import com.stansful.sshvpnclient.ui.designsystem.rememberClipboardCopier
import com.stansful.sshvpnclient.ui.main.MainViewModel
import com.stansful.sshvpnclient.ui.opensource.OpenSourceViewModel
import com.stansful.sshvpnclient.ui.shell.Destinations
import com.stansful.sshvpnclient.ui.shell.LocalAppContainer
import com.stansful.sshvpnclient.ui.shell.RoutesConsentSession
import com.stansful.sshvpnclient.ui.shell.activityViewModel
import com.stansful.sshvpnclient.ui.shell.navigateTo
import com.stansful.sshvpnclient.ui.shell.rememberConsentGate
import com.stansful.sshvpnclient.ui.smartconnect.SmartConnectViewModel
import com.stansful.sshvpnclient.work.ProxySourceSyncWorker
import kotlinx.coroutines.delay

/** Settings; [section] (`engine`, `updates`, …) is the section to bring into view. */
@Composable
fun SettingsRoute(
    navController: NavHostController,
    section: String?,
) {
    val container = LocalAppContainer.current
    val routes: OpenSourceViewModel = activityViewModel()
    val main: MainViewModel = activityViewModel()
    val smart: SmartConnectViewModel = activityViewModel()
    val updates = appUpdatePresenter()
    val routesState by routes.uiState.collectAsStateWithLifecycle()
    val smartState by smart.uiState.collectAsStateWithLifecycle()
    val settings by container.appSettingsRepository.settings.collectAsStateWithLifecycle()
    val updateState by updates.state.collectAsStateWithLifecycle()
    val offer by updates.offer.collectAsStateWithLifecycle()
    val lastProgress by updates.lastProgress.collectAsStateWithLifecycle()
    val consentGate = rememberConsentGate()
    val links = rememberLinkOpener()
    val copier = rememberClipboardCopier()
    val installer = rememberUpdateInstaller(onFailed = updates::onActionFailed)
    val now by rememberClock()
    // Both read off the main thread by the data layer; null until they arrive (and when never set).
    val lastLibrarySync by remember(container) { container.proxySourceSynchronizer.lastSuccessfulSyncAt() }
        .collectAsStateWithLifecycle(initialValue = null)
    val lastUpdateCheck by remember(container) { container.appUpdateCoordinator.lastSuccessfulCheckAt }
        .collectAsStateWithLifecycle(initialValue = null)

    // The engine can be installed from another screen or process start; re-read it on every visit.
    LaunchedEffect(routes) { routes.refreshXrayCoreAvailability() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { routes.refreshXrayCoreAvailability() }

    // "Refresh now" result: the view model's outcome of a refresh that finished after one was started
    // here (its id is newer than the one before the tap).
    var syncStartedAfter by remember { mutableStateOf<Long?>(null) }
    val librarySyncOutcome = routesState.lastSync?.takeIf { outcome ->
        syncStartedAfter?.let { before -> outcome.id > before } == true
    }

    val engineState = routesState.xrayCoreUpdateState
    var engineCheckedAt by rememberSaveable { mutableStateOf<Long?>(null) }
    var engineCheckSeen by remember { mutableStateOf(false) }
    LaunchedEffect(engineState.isChecking) {
        if (engineState.isChecking) {
            engineCheckSeen = true
        } else if (engineCheckSeen) {
            engineCheckSeen = false
            engineCheckedAt = System.currentTimeMillis()
        }
    }

    var updateCheckSeen by remember { mutableStateOf(false) }
    var updateCheckedJustNow by remember { mutableStateOf(false) }
    LaunchedEffect(updateState.isChecking) {
        if (updateState.isChecking) {
            updateCheckSeen = true
        } else if (updateCheckSeen) {
            updateCheckSeen = false
            updateCheckedJustNow = true
        }
    }

    val phase = updatePhase(updateState, offer, lastProgress)
    val state = SettingsUiState(
        versionName = BuildConfig.VERSION_NAME,
        vpnMode = settings.vpnMode,
        selectedAppsCount = settings.selectedAppPackages.size,
        showConnectionActivity = settings.showConnectionActivity,
        showTerminal = settings.showTerminalOnMain,
        warnBeforeRoutes = settings.showOpenSourceWarningOnEnter,
        refreshInBackground = settings.openSourceAutoUpdateEnabled,
        libraryRouteCount = routesState.allProfileIds.size,
        libraryRefresh = when {
            routesState.isSyncing -> LibraryRefresh.Running
            librarySyncOutcome != null -> LibraryRefresh.Done(failureMessage = librarySyncOutcome.failureMessage)
            else -> LibraryRefresh.Idle(lastLibrarySync)
        },
        engine = engineUi(
            installed = routesState.xrayCoreAvailable,
            state = engineState,
            inUse = routesState.anyXrayRuntimeActive || smartState.xrayRuntimeInUse,
            checkedAtMs = engineCheckedAt,
        ),
        themeMode = settings.themeMode,
        customPalette = settings.customThemeColors,
        update = updatesUi(
            state = updateState,
            phase = phase,
            sizeBytes = offer?.apkSizeBytes ?: lastProgress?.totalBytes,
            lastCheckedAtMs = lastUpdateCheck,
            checkedJustNow = updateCheckedJustNow,
        ),
        nowMs = now,
    )

    val actions = remember(routes, main, updates, installer, links, copier, consentGate, navController) {
        SettingsActions(
            onOpenAppRouting = { navController.navigateTo(Destinations.APP_ROUTING) },
            onShowConnectionActivityChange = container.appSettingsRepository::setShowConnectionActivity,
            onShowTerminalChange = main::setShowTerminalOnMain,
            onWarnBeforeRoutesChange = { enabled ->
                // Turning the notice back on shows it the next time Routes opens, even in this launch.
                if (enabled) RoutesConsentSession.acknowledged = false
                routes.setShowOpenSourceWarningOnEnter(enabled)
            },
            onRefreshInBackgroundChange = { enabled ->
                routes.setOpenSourceAutoUpdateEnabled(enabled)
                if (enabled) {
                    ProxySourceSyncWorker.schedule(container.applicationContext)
                } else {
                    ProxySourceSyncWorker.cancel(container.applicationContext)
                }
            },
            onRefreshNow = {
                consentGate.requestRoutes(
                    onGranted = {
                        syncStartedAfter = routes.uiState.value.lastSync?.id ?: 0L
                        routes.synchronize()
                    },
                )
            },
            onCheckEngine = routes::checkXrayCoreUpdates,
            onDownloadEngine = routes::downloadEngineForThisPhone,
            onCancelEngineDownload = routes::cancelXrayCoreDownload,
            onOpenEngineRelease = {
                routes.uiState.value.xrayCoreUpdateState.release?.let { links.open(it.releaseUrl) }
            },
            onThemeModeChange = container.appSettingsRepository::setThemeMode,
            onOpenAppearance = { navController.navigateTo(Destinations.APPEARANCE) },
            onCheckForUpdates = {
                updateCheckedJustNow = false
                updates.checkForUpdates()
            },
            onViewUpdate = updates::openSheet,
            onResumeUpdate = updates::download,
            onInstallUpdate = {
                val ready = updatePhase(updates.state.value, updates.offer.value, updates.lastProgress.value)
                if (ready is UpdatePhase.Ready) {
                    installer.install(ready.contentUri)
                } else {
                    updates.onActionFailed("Downloaded update is not ready to install")
                }
            },
            onOpenSource = { links.open(GITHUB_REPOSITORY_URL) },
            onCopySourceLink = { copier.copy("GitHub repository", GITHUB_REPOSITORY_URL, toast = "Link copied") },
        )
    }

    SettingsScreen(
        state = state,
        actions = actions,
        scrollTo = when (section) {
            Destinations.SECTION_ENGINE -> SettingsSection.Engine
            Destinations.SECTION_UPDATES -> SettingsSection.Updates
            else -> null
        },
    )
}

/** Wall clock for relative times ("2 h ago"), ticking every 30 s. */
@Composable
private fun rememberClock(): State<Long> = produceState(System.currentTimeMillis()) {
    while (true) {
        delay(CLOCK_TICK_MS)
        value = System.currentTimeMillis()
    }
}

private const val CLOCK_TICK_MS = 30_000L
