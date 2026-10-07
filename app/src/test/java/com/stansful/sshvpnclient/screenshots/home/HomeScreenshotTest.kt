package com.stansful.sshvpnclient.screenshots.home

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import com.stansful.sshvpnclient.domain.model.AppUpdateInfo
import com.stansful.sshvpnclient.domain.model.AppUpdateState
import com.stansful.sshvpnclient.domain.model.GlobalTab
import com.stansful.sshvpnclient.domain.model.SmartConnectPhase
import com.stansful.sshvpnclient.domain.model.SmartConnectState
import com.stansful.sshvpnclient.domain.model.VpnConnectionState
import com.stansful.sshvpnclient.domain.model.VpnConnectionStatus
import com.stansful.sshvpnclient.domain.model.VpnSessionOwner
import com.stansful.sshvpnclient.domain.model.VpnTransportType
import com.stansful.sshvpnclient.screenshots.ScreenSize
import com.stansful.sshvpnclient.screenshots.ScreenshotScope
import com.stansful.sshvpnclient.screenshots.ScreenshotTest
import com.stansful.sshvpnclient.screenshots.ShellTab
import com.stansful.sshvpnclient.screenshots.TopLevelShellFrame
import com.stansful.sshvpnclient.screenshots.home.HomeFixtures.auto
import com.stansful.sshvpnclient.screenshots.home.HomeFixtures.connectedAuto
import com.stansful.sshvpnclient.screenshots.home.HomeFixtures.denied
import com.stansful.sshvpnclient.screenshots.home.HomeFixtures.main
import com.stansful.sshvpnclient.screenshots.home.HomeFixtures.readyUpdate
import com.stansful.sshvpnclient.screenshots.home.HomeFixtures.routes
import com.stansful.sshvpnclient.screenshots.home.HomeFixtures.settings
import com.stansful.sshvpnclient.screenshots.home.HomeFixtures.ssh
import com.stansful.sshvpnclient.screenshots.home.HomeFixtures.state
import com.stansful.sshvpnclient.screenshots.renderScreenshot
import com.stansful.sshvpnclient.ui.designsystem.StatusTone
import com.stansful.sshvpnclient.ui.designsystem.ToasterState
import com.stansful.sshvpnclient.ui.home.HomeActions
import com.stansful.sshvpnclient.ui.home.HomeMode
import com.stansful.sshvpnclient.ui.home.HomeScreen
import com.stansful.sshvpnclient.ui.home.HomeSheet
import com.stansful.sshvpnclient.ui.home.HomeUiState
import com.stansful.sshvpnclient.ui.home.PoolTab
import com.stansful.sshvpnclient.ui.main.TunnelCheckResult
import com.stansful.sshvpnclient.ui.theme.DayColors
import com.stansful.sshvpnclient.ui.theme.NightColors
import com.stansful.sshvpnclient.ui.theme.ShadowColors
import org.junit.Test

class HomeScreenshotTest : ScreenshotTest() {
    /** Scroll actions animate; let the clock run while they settle. */
    private fun ScreenshotScope.scrollTo(action: ScreenshotScope.() -> Unit) {
        mainClock.autoAdvance = true
        action()
        mainClock.autoAdvance = false
    }

    private fun phone(
        name: String,
        state: HomeUiState,
        colors: ShadowColors = NightColors,
        sheet: HomeSheet? = null,
        poolTab: PoolTab = PoolTab.Pool,
    ) {
        renderScreenshot(name, ScreenSize.PHONE, colors) {
            TopLevelShellFrame(ShellTab.Home) {
                HomeScreen(state = state, actions = HomeActions(), sheet = sheet, poolTab = poolTab)
            }
        }
    }

    private fun tablet(
        name: String,
        state: HomeUiState,
        tone: StatusTone,
        colors: ShadowColors = NightColors,
        size: ScreenSize = ScreenSize.TABLET,
    ) {
        renderScreenshot(name, size, colors) {
            TopLevelShellFrame(ShellTab.Home, railTone = tone) {
                HomeScreen(state = state, actions = HomeActions())
            }
        }
    }

    @Test
    fun auto() {
        val settings = settings(GlobalTab.SMART_CONNECT)
        phone("Home_Auto_Off_Night", state(GlobalTab.SMART_CONNECT))
        phone(
            "Home_Auto_Testing_Night",
            state(
                GlobalTab.SMART_CONNECT,
                auto = auto(
                    settings,
                    SmartConnectState(
                        phase = SmartConnectPhase.CHECKING,
                        desiredActive = true,
                        checkCompleted = 129,
                        checkTotal = 350,
                    ),
                ),
            ),
        )
        phone(
            "Home_Auto_RetryWait_Night",
            state(
                GlobalTab.SMART_CONNECT,
                auto = auto(
                    settings,
                    SmartConnectState(
                        phase = SmartConnectPhase.RETRY_WAIT,
                        desiredActive = true,
                        checkCompleted = 312,
                        checkTotal = 312,
                        retryDelayMs = 60_000,
                    ),
                    retryStartedAt = 0L,
                ),
                configure = { copy(nowElapsedMs = 12_000) },
            ),
        )
        phone(
            "Home_Auto_Connected_Night",
            state(
                GlobalTab.SMART_CONNECT,
                auto = auto(settings, connectedAuto()),
                main = main(
                    settings,
                    vpn = VpnConnectionState(
                        status = VpnConnectionStatus.CONNECTED,
                        activeTransport = VpnTransportType.XRAY,
                        sessionOwner = VpnSessionOwner.SMART_CONNECT,
                    ),
                ),
            ),
        )
        phone(
            "Home_Auto_EngineMissing_Day",
            state(GlobalTab.SMART_CONNECT, auto = auto(settings, coreAvailable = false)),
            colors = DayColors,
        )
    }

    @Test
    fun moreStates() {
        val autoSettings = settings(GlobalTab.SMART_CONNECT)
        phone(
            "Home_Auto_Restoring_Night",
            state(
                GlobalTab.SMART_CONNECT,
                auto = auto(
                    autoSettings,
                    SmartConnectState(
                        phase = SmartConnectPhase.STARTING,
                        desiredActive = true,
                        message = "Restoring Smart Connect",
                    ),
                ),
            ),
        )
        phone(
            "Home_Auto_Error_Night",
            state(
                GlobalTab.SMART_CONNECT,
                auto = auto(
                    autoSettings,
                    SmartConnectState(phase = SmartConnectPhase.ERROR, message = "No apps selected"),
                ),
            ),
        )
        val serverSettings = settings(GlobalTab.SHADOW_SSH)
        phone(
            "Home_Server_NoneSelected_Night",
            state(GlobalTab.SHADOW_SSH, main = main(serverSettings, selected = null)),
        )
        val routesSettings = settings(GlobalTab.OPEN_SOURCE)
        phone(
            "Home_Routes_NoRoute_Night",
            state(
                GlobalTab.OPEN_SOURCE,
                routes = routes(
                    routesSettings,
                ).copy(profiles = HomeFixtures.library.map { it.copy(isSelected = false) }),
            ),
        )
        tablet(
            "Home_Tablet_AutoTesting_Night",
            state(
                GlobalTab.SMART_CONNECT,
                auto = auto(
                    autoSettings,
                    SmartConnectState(
                        phase = SmartConnectPhase.CHECKING,
                        desiredActive = true,
                        checkCompleted = 129,
                        checkTotal = 350,
                    ),
                ),
            ),
            StatusTone.Progress,
        )
    }

    @Test
    fun autoPhases() {
        val settings = settings(GlobalTab.SMART_CONNECT)
        phone(
            "Home_Auto_Selecting_Night",
            state(
                GlobalTab.SMART_CONNECT,
                auto = auto(
                    settings,
                    SmartConnectState(
                        phase = SmartConnectPhase.SELECTING,
                        desiredActive = true,
                        checkCompleted = 350,
                        checkTotal = 350,
                        availableCount = 24,
                    ),
                ),
            ),
        )
        val verifying = state(
            GlobalTab.SMART_CONNECT,
            auto = auto(
                settings,
                SmartConnectState(
                    phase = SmartConnectPhase.VERIFYING,
                    desiredActive = true,
                    activeProfileId = "nl03",
                    activeProfileName = "Amsterdam · NL 03",
                    activeProfileLatencyMs = 86,
                ),
            ),
        )
        phone("Home_Auto_Verifying_Night", verifying)
        phone("Home_RoutePool_Verifying_Night", verifying, sheet = HomeSheet.RoutePool)
        phone(
            "Home_RoutePool_Stopped_Night",
            state(GlobalTab.SMART_CONNECT),
            sheet = HomeSheet.RoutePool,
        )
    }

    @Test
    fun autoSheets() {
        val settings = settings(GlobalTab.SMART_CONNECT)
        val connected = state(GlobalTab.SMART_CONNECT, auto = auto(settings, connectedAuto()))
        phone("Home_RoutePool_Night", connected, sheet = HomeSheet.RoutePool)
        phone("Home_RoutePool_How_Night", connected, sheet = HomeSheet.RoutePool, poolTab = PoolTab.How)
        phone("Home_RoutePool_Day", connected, colors = DayColors, sheet = HomeSheet.RoutePool)
        renderScreenshot(
            name = "Home_RoutePool_PoolEnd_Night",
            interact = { scrollTo { onNodeWithText("Route library", substring = true).performScrollTo() } },
        ) {
            TopLevelShellFrame(ShellTab.Home) {
                HomeScreen(state = connected, actions = HomeActions(), sheet = HomeSheet.RoutePool)
            }
        }
        renderScreenshot(
            name = "Home_RoutePool_HowLadder_Night",
            interact = { scrollTo { onNodeWithText(
                "While Auto searches, traffic goes outside the VPN.",
            ).performScrollTo() } },
        ) {
            TopLevelShellFrame(ShellTab.Home) {
                HomeScreen(
                    state = connected,
                    actions = HomeActions(),
                    sheet = HomeSheet.RoutePool,
                    poolTab = PoolTab.How,
                )
            }
        }
        renderScreenshot(
            name = "Home_RoutePool_HowEnd_Night",
            interact = { scrollTo { onNodeWithText("Why did Auto retry?").performScrollTo() } },
        ) {
            TopLevelShellFrame(ShellTab.Home) {
                HomeScreen(
                    state = connected,
                    actions = HomeActions(),
                    sheet = HomeSheet.RoutePool,
                    poolTab = PoolTab.How,
                )
            }
        }
    }

    @Test
    fun server() {
        val settings = settings(GlobalTab.SHADOW_SSH)
        phone(
            "Home_Server_Connecting_Night",
            state(GlobalTab.SHADOW_SSH, main = main(settings, vpn = ssh(VpnConnectionStatus.CONNECTING))),
        )
        phone(
            "Home_Server_Connected_Night",
            state(GlobalTab.SHADOW_SSH, main = main(settings, vpn = ssh(VpnConnectionStatus.CONNECTED))),
        )
        phone(
            "Home_Server_Reconnecting_Night",
            state(GlobalTab.SHADOW_SSH, main = main(settings, vpn = ssh(VpnConnectionStatus.RECONNECTING))),
        )
        phone(
            "Home_Server_CheckOk_Day",
            state(
                GlobalTab.SHADOW_SSH,
                main = main(
                    settings,
                    vpn = ssh(VpnConnectionStatus.CONNECTED),
                    check = TunnelCheckResult.SUCCESS,
                    update = readyUpdate,
                ),
            ),
            colors = DayColors,
        )
        phone(
            "Home_Server_CheckFailUpdate_Day",
            state(
                GlobalTab.SHADOW_SSH,
                main = main(
                    settings,
                    vpn = ssh(VpnConnectionStatus.CONNECTED),
                    check = TunnelCheckResult.FAILURE,
                    update = readyUpdate,
                ),
            ),
            colors = DayColors,
        )
        phone(
            "Home_Server_UpdateAvailable_Night",
            state(
                GlobalTab.SHADOW_SSH,
                main = main(
                    settings,
                    update = AppUpdateState(
                        availableUpdate = AppUpdateInfo(
                            versionName = "3.5.0",
                            title = "shadow 3.5.0",
                            releaseNotes = "",
                            releaseUrl = "https://example.com",
                            apkName = "shadow.apk",
                            apkUrl = "https://example.com/shadow.apk",
                            apkSizeBytes = 30_356_275L,
                            sha256Digest = null,
                        ),
                    ),
                ),
            ),
        )
        phone(
            "Home_Server_CheckFail_Day",
            state(
                GlobalTab.SHADOW_SSH,
                main = main(settings, vpn = ssh(VpnConnectionStatus.CONNECTED), check = TunnelCheckResult.FAILURE),
            ),
            colors = DayColors,
        )
        phone(
            "Home_Server_Error_Night",
            state(
                GlobalTab.SHADOW_SSH,
                main = main(settings, vpn = ssh(VpnConnectionStatus.ERROR, "Authentication failed")),
            ),
        )
        phone(
            "Home_Server_FirstRun_Night",
            state(GlobalTab.SHADOW_SSH, main = main(settings, selected = null, configs = emptyList())),
        )
        phone(
            "Home_Server_PermissionDenied_Night",
            state(GlobalTab.SHADOW_SSH, configure = { denied(HomeMode.Server) }),
        )
    }

    @Test
    fun serverOverlays() {
        val settings = settings(GlobalTab.SHADOW_SSH)
        val connected = state(GlobalTab.SHADOW_SSH, main = main(settings, vpn = ssh(VpnConnectionStatus.CONNECTED)))
        phone("Home_ChooseServer_Night", connected, sheet = HomeSheet.ChooseServer)
        phone("Home_ChooseServer_Day", connected, colors = DayColors, sheet = HomeSheet.ChooseServer)
        phone(
            "Home_SwitchDialog_Night",
            state(
                GlobalTab.SHADOW_SSH,
                main = main(settings, vpn = ssh(VpnConnectionStatus.CONNECTED)),
                configure = { copy(pendingSwitch = HomeMode.Auto) },
            ),
        )
        phone(
            "Home_NoAppsDialog_Night",
            state(GlobalTab.SHADOW_SSH, main = main(settings).copy(showNoSelectedAppsDialog = true)),
        )
    }

    @Test
    fun routes() {
        val settings = settings(GlobalTab.OPEN_SOURCE)
        val connectedVpn = VpnConnectionState(
            status = VpnConnectionStatus.CONNECTED,
            activeConfigId = "lib-de11",
            activeTransport = VpnTransportType.XRAY,
            sessionOwner = VpnSessionOwner.OPEN_SOURCE,
        )
        phone("Home_Routes_Off_Night", state(GlobalTab.OPEN_SOURCE))
        phone(
            "Home_Routes_Connected_Night",
            state(
                GlobalTab.OPEN_SOURCE,
                main = main(settings, vpn = connectedVpn),
                routes = routes(settings, vpn = connectedVpn),
            ),
        )
        val errorVpn = VpnConnectionState(
            status = VpnConnectionStatus.ERROR,
            activeConfigId = "lib-de11",
            errorMessage = "Xray runtime core is not installed. Download it from Public Routes settings.",
        )
        phone(
            "Home_Routes_Error_Night",
            state(
                GlobalTab.OPEN_SOURCE,
                main = main(settings, vpn = errorVpn),
                auto = auto(settings, coreAvailable = false),
                routes = routes(settings, vpn = errorVpn, coreAvailable = false),
            ),
        )
        phone("Home_QuickSwitch_Night", state(GlobalTab.OPEN_SOURCE), sheet = HomeSheet.QuickSwitch)
    }

    /** The active route (and a pinned one) left the public list: Connect is off, Quick switch marks it. */
    @Test
    fun routesOutdated() {
        val stale = HomeFixtures.library.map { route ->
            if (route.id == "lib-de11" || route.id == "lib-home") route.copy(isStale = true) else route
        }
        val outdated = state(GlobalTab.OPEN_SOURCE, configure = { copy(routeLibrary = stale) })
        phone("Home_Routes_Outdated_Night", outdated)
        phone("Home_QuickSwitch_Outdated_Night", outdated, sheet = HomeSheet.QuickSwitch)
    }

    @Test
    fun tablet() {
        val autoSettings = settings(GlobalTab.SMART_CONNECT, selectedApps = setOf("a", "b", "c", "d"))
        tablet(
            "Home_Tablet_Auto_Night",
            state(
                GlobalTab.SMART_CONNECT,
                settings = autoSettings,
                auto = auto(autoSettings, connectedAuto()),
                configure = { copy(selectedAppLabels = listOf("Telegram", "Chrome", "YouTube", "Instagram")) },
            ),
            StatusTone.Success,
        )
        val serverSettings = settings(GlobalTab.SHADOW_SSH)
        tablet(
            "Home_Tablet_Server_Night",
            state(
                GlobalTab.SHADOW_SSH,
                main = main(
                    serverSettings,
                    vpn = ssh(VpnConnectionStatus.CONNECTED),
                    check = TunnelCheckResult.SUCCESS,
                ),
            ),
            StatusTone.Success,
        )
        tablet("Home_Tablet_Routes_Night", state(GlobalTab.OPEN_SOURCE), StatusTone.Neutral)
        // TabletHome.dc.html: a centred, content-width toast.
        val toaster = ToasterState()
        renderScreenshot(
            name = "Home_Tablet_Toast_Night",
            size = ScreenSize.TABLET,
            toaster = toaster,
            interact = { runOnUiThread { toaster.show("Copied 42 lines · they include IP addresses") } },
        ) {
            TopLevelShellFrame(ShellTab.Home, railTone = StatusTone.Success) {
                HomeScreen(state = state(GlobalTab.OPEN_SOURCE), actions = HomeActions())
            }
        }
        // Medium (600–840 dp): rail + the phone column, kept at its readable width.
        tablet(
            "Home_Medium_Server_Night",
            state(GlobalTab.SHADOW_SSH),
            StatusTone.Neutral,
            size = ScreenSize(720, 900),
        )
        tablet(
            "Home_Tablet_Server_Day",
            state(GlobalTab.SHADOW_SSH, main = main(serverSettings, selected = HomeFixtures.raspberryPi)),
            StatusTone.Neutral,
            colors = DayColors,
        )
    }
}
