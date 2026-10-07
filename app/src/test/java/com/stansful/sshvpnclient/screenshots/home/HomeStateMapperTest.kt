package com.stansful.sshvpnclient.screenshots.home

import com.stansful.sshvpnclient.domain.model.AppUpdateInfo
import com.stansful.sshvpnclient.domain.model.AppUpdateState
import com.stansful.sshvpnclient.domain.model.GlobalTab
import com.stansful.sshvpnclient.domain.model.SmartConnectPhase
import com.stansful.sshvpnclient.domain.model.SmartConnectState
import com.stansful.sshvpnclient.domain.model.VpnConnectionState
import com.stansful.sshvpnclient.domain.model.VpnConnectionStatus
import com.stansful.sshvpnclient.domain.model.VpnSessionOwner
import com.stansful.sshvpnclient.domain.model.VpnTransportType
import com.stansful.sshvpnclient.screenshots.home.HomeFixtures.auto
import com.stansful.sshvpnclient.screenshots.home.HomeFixtures.main
import com.stansful.sshvpnclient.screenshots.home.HomeFixtures.routes
import com.stansful.sshvpnclient.screenshots.home.HomeFixtures.settings
import com.stansful.sshvpnclient.screenshots.home.HomeFixtures.ssh
import com.stansful.sshvpnclient.screenshots.home.HomeFixtures.state
import com.stansful.sshvpnclient.ui.designsystem.OrbState
import com.stansful.sshvpnclient.ui.home.CardTarget
import com.stansful.sshvpnclient.ui.home.CheckState
import com.stansful.sshvpnclient.ui.home.HomeChip
import com.stansful.sshvpnclient.ui.home.HomeMode
import com.stansful.sshvpnclient.ui.home.HomeNote
import com.stansful.sshvpnclient.ui.home.HomeSources
import com.stansful.sshvpnclient.ui.home.NoteAction
import com.stansful.sshvpnclient.ui.home.buildHomeUiState
import com.stansful.sshvpnclient.ui.home.canStartRoutes
import com.stansful.sshvpnclient.ui.home.displayedMode
import com.stansful.sshvpnclient.ui.home.failureNote
import com.stansful.sshvpnclient.ui.home.hasButton
import com.stansful.sshvpnclient.ui.home.isSessionActive
import com.stansful.sshvpnclient.ui.main.TunnelCheckResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeStateMapperTest {
    private val serverSettings = settings(GlobalTab.SHADOW_SSH)
    private val autoSettings = settings(GlobalTab.SMART_CONNECT)

    @Test
    fun `a session started elsewhere shows its owner's mode`() {
        val autoVpn = VpnConnectionState(
            status = VpnConnectionStatus.CONNECTED,
            activeTransport = VpnTransportType.XRAY,
            sessionOwner = VpnSessionOwner.SMART_CONNECT,
        )
        val sources = HomeSources(main(serverSettings, vpn = autoVpn), auto(serverSettings), routes(serverSettings))

        assertEquals(HomeMode.Auto, sources.displayedMode())
        assertEquals(HomeMode.Routes, sources.copy(forcedMode = HomeMode.Routes).displayedMode())
    }

    @Test
    fun `the quick tile's missing VPN permission in Auto asks to tap Connect, not a failure`() {
        val tileFailure = SmartConnectState(phase = SmartConnectPhase.ERROR, message = "VPN permission required")
        val state = state(GlobalTab.SMART_CONNECT, auto = auto(autoSettings, tileFailure))

        assertEquals("Find the fastest route", state.hero.headline)
        assertTrue(state.hero.orbEnabled)
        assertEquals(
            listOf(HomeNote.Info("shadow needs the VPN permission. Tap Connect to allow it.")),
            state.notes,
        )
    }

    @Test
    fun `Auto waits while the engine installs from Settings`() {
        val installing = auto(autoSettings).let {
            it.copy(xrayCoreUpdate = it.xrayCoreUpdate.copy(isDownloading = true))
        }
        val state = state(GlobalTab.SMART_CONNECT, auto = installing)

        assertFalse(installing.canStart)
        assertTrue(HomeNote.Info("The Xray engine is still installing. Connect when it's done.") in state.notes)
    }

    @Test
    fun `stored mode is shown without a session`() {
        val sources = HomeSources(main(autoSettings), auto(autoSettings), routes(autoSettings))

        assertEquals(HomeMode.Auto, sources.displayedMode())
        assertFalse(sources.isSessionActive(HomeMode.Auto))
    }

    @Test
    fun `no servers is the first-run state and the orb steps back`() {
        val state = state(GlobalTab.SHADOW_SSH, main = main(serverSettings, selected = null, configs = emptyList()))

        assertTrue(state.firstRun)
        assertEquals(OrbState.Disabled, state.hero.orb)
        assertEquals("Add a server to start", state.hero.headline)
    }

    @Test
    fun `saved servers without a selection open the chooser`() {
        val state = state(GlobalTab.SHADOW_SSH, main = main(serverSettings, selected = null))

        assertFalse(state.firstRun)
        assertEquals(CardTarget.ChooseServer, state.card?.target)
        assertEquals("No server selected", state.card?.title)
    }

    @Test
    fun `testing routes fills the ring and hides the stale pool count`() {
        val workflow = SmartConnectState(
            phase = SmartConnectPhase.CHECKING,
            desiredActive = true,
            checkCompleted = 175,
            checkTotal = 350,
        )
        val state = state(GlobalTab.SMART_CONNECT, auto = auto(autoSettings, workflow))

        assertEquals(OrbState.Progress(0.5f), state.hero.orb)
        assertEquals("Testing routes", state.hero.pillLabel)
        assertTrue(HomeNote.AutoSearching in state.notes)
        assertEquals(HomeChip.RoutePool(0), state.chips.filterIsInstance<HomeChip.RoutePool>().single())
    }

    @Test
    fun `waiting to retry counts down from the pause start`() {
        val workflow = SmartConnectState(
            phase = SmartConnectPhase.RETRY_WAIT,
            desiredActive = true,
            checkCompleted = 312,
            checkTotal = 312,
            retryDelayMs = 60_000,
        )
        val state = state(
            GlobalTab.SMART_CONNECT,
            auto = auto(autoSettings, workflow, retryStartedAt = 1_000L),
            configure = { copy(nowElapsedMs = 13_000L) },
        )

        assertEquals("0:48", state.hero.pillClock)
        assertEquals(OrbState.Waiting(0.8f), state.hero.orb)
        assertTrue(HomeNote.LastPass(312) in state.notes)
    }

    @Test
    fun `denied VPN permission is a recovery banner, not an error`() {
        val state = state(
            GlobalTab.SHADOW_SSH,
            main = main(serverSettings, vpn = ssh(VpnConnectionStatus.ERROR, "VPN permission denied")),
        )

        assertEquals(HomeNote.PermissionDenied, state.notes.first())
        assertEquals(OrbState.Off, state.hero.orb)
        assertTrue(state.notes.none { it is HomeNote.Failure })
    }

    @Test
    fun `missing engine replaces the raw core error`() {
        val error = VpnConnectionState(
            status = VpnConnectionStatus.ERROR,
            activeConfigId = "lib-de11",
            errorMessage = "Xray runtime core is not installed. Download it from Public Routes settings.",
        )
        val settings = settings(GlobalTab.OPEN_SOURCE)
        val state = state(
            GlobalTab.OPEN_SOURCE,
            main = main(settings, vpn = error),
            routes = routes(settings, vpn = error, coreAvailable = false),
        )

        assertEquals(OrbState.Error, state.hero.orb)
        assertTrue(state.notes.any { it is HomeNote.EngineMissing })
        assertTrue(state.notes.none { it is HomeNote.Failure })
    }

    @Test
    fun `a failed tunnel check shows the failure banner instead of the caption`() {
        val state = state(
            GlobalTab.SHADOW_SSH,
            main = main(serverSettings, vpn = ssh(VpnConnectionStatus.CONNECTED), check = TunnelCheckResult.FAILURE),
        )

        assertEquals("Home VPS", state.checkFailedServer)
        assertFalse(state.showCheckCaption)
        assertEquals(CheckState.Failed, state.chips.filterIsInstance<HomeChip.CheckTunnel>().single().state)
    }

    @Test
    fun `check tunnel and terminal appear only while connected`() {
        val idle = state(GlobalTab.SHADOW_SSH)
        val connected = state(
            GlobalTab.SHADOW_SSH,
            main = main(serverSettings, vpn = ssh(VpnConnectionStatus.CONNECTED)),
        )

        assertTrue(idle.chips.none { it is HomeChip.CheckTunnel || it is HomeChip.Terminal })
        assertTrue(connected.chips.any { it is HomeChip.CheckTunnel })
        assertTrue(connected.chips.any { it is HomeChip.Terminal })
    }

    @Test
    fun `activity entry points follow the setting`() {
        val hidden = settings(GlobalTab.SHADOW_SSH, activity = false)
        val state = state(GlobalTab.SHADOW_SSH, settings = hidden)

        assertFalse(state.showActivity)
        assertTrue(state.chips.none { it == HomeChip.Activity })
    }

    @Test
    fun `no-apps dialog follows any mode's flag`() {
        val state = state(GlobalTab.SHADOW_SSH, auto = auto(serverSettings).copy(showNoSelectedAppsDialog = true))

        assertTrue(state.showNoAppsDialog)
    }

    @Test
    fun `a dismissed update stays hidden for that version`() {
        val update = AppUpdateState(availableUpdate = updateInfo("3.5.0"))
        val shown = state(GlobalTab.SHADOW_SSH, main = main(serverSettings, update = update))
        val hidden = state(
            GlobalTab.SHADOW_SSH,
            main = main(serverSettings, update = update),
            configure = { copy(dismissedUpdateKey = shown.updateBanner?.key) },
        )

        assertEquals("3.5.0", shown.updateBanner?.version)
        assertNull(hidden.updateBanner)
    }

    @Test
    fun `quick switch lists pinned routes then the fastest available`() {
        val state = state(GlobalTab.OPEN_SOURCE)

        assertEquals(
            listOf("Frankfurt · DE 11", "My home route", "Amsterdam · NL 03", "Paris · FR 07"),
            state.routeOptions.map { it.name },
        )
        assertTrue(state.routeOptions.first().selected)
    }

    @Test
    fun `server errors offer the fix where Home can reach it`() {
        assertEquals(NoteAction.EditServer, failureNote("Selected SSH key not found", HomeMode.Server).action)
        assertEquals(NoteAction.PickApps, failureNote("No apps selected", HomeMode.Auto).action)
        assertEquals(NoteAction.OpenActivity, failureNote("Connection timeout", HomeMode.Routes).action)
    }

    @Test
    fun `a Route library search does not hide the active route on Home`() {
        val settings = settings(GlobalTab.OPEN_SOURCE)
        val searched = routes(settings).copy(profiles = emptyList())
        val sources = HomeSources(main(settings), auto(settings), searched, routeLibrary = HomeFixtures.library)
        val state = buildHomeUiState(sources)

        assertEquals("Frankfurt · DE 11", state.card?.title)
        assertEquals(OrbState.Off, state.hero.orb)
        assertTrue(sources.canStartRoutes())
        assertTrue(state.routeOptions.any { it.selected })
    }

    @Test
    fun `an outdated active route can't connect and Quick switch offers no outdated route`() {
        val settings = settings(GlobalTab.OPEN_SOURCE)
        val library = HomeFixtures.library.map { route ->
            when (route.id) {
                "lib-de11" -> route.copy(isStale = true)
                "lib-home" -> route.copy(isStale = true)
                else -> route
            }
        }
        val sources = HomeSources(main(settings), auto(settings), routes(settings), routeLibrary = library)
        val state = buildHomeUiState(sources)

        assertFalse(sources.canStartRoutes())
        assertEquals(OrbState.Disabled, state.hero.orb)
        assertEquals("Frankfurt · DE 11 is outdated", state.hero.headline)
        assertEquals("Outdated · pick a current route", state.card?.subtitle)
        assertEquals(CardTarget.QuickSwitch, state.card?.target)
        // The active one is listed (marked outdated); the outdated pinned "My home route" is not offered.
        assertTrue(state.routeOptions.single { it.selected }.outdated)
        assertTrue(state.routeOptions.none { it.id == "lib-home" })
    }

    @Test
    fun `the engine banner waits for the first probe`() {
        val unknown = auto(autoSettings, coreAvailable = false).copy(xrayCoreChecked = false)
        val state = state(GlobalTab.SMART_CONNECT, auto = unknown)

        assertTrue(state.notes.none { it is HomeNote.EngineMissing })
        assertEquals(OrbState.Off, state.hero.orb)
        val missing = state(GlobalTab.SMART_CONNECT, auto = auto(autoSettings, coreAvailable = false))
        assertTrue(missing.notes.any { it is HomeNote.EngineMissing })
        assertEquals(OrbState.Disabled, missing.hero.orb)
    }

    @Test
    fun `restoring Auto says so`() {
        val restoring = SmartConnectState(
            phase = SmartConnectPhase.STARTING,
            desiredActive = true,
            message = "Restoring Smart Connect",
        )
        val state = state(GlobalTab.SMART_CONNECT, auto = auto(autoSettings, restoring))

        assertEquals("Restoring Auto", state.hero.headline)
    }

    @Test
    fun `a session through a deleted server still has a server card`() {
        val state = state(
            GlobalTab.SHADOW_SSH,
            main = main(serverSettings, vpn = ssh(VpnConnectionStatus.CONNECTED), selected = null),
        )

        assertEquals("No server selected", state.card?.title)
        assertEquals(OrbState.Connected, state.hero.orb)
    }

    @Test
    fun `notes with a fix button replace the chip row`() {
        assertTrue(HomeNote.EngineMissing("41.2 MiB").hasButton)
        assertTrue(failureNote("Authentication failed", HomeMode.Server).hasButton)
        assertFalse(failureNote("Connection refused", HomeMode.Server).hasButton)
        assertFalse(HomeNote.AutoSearching.hasButton)
    }

    private fun updateInfo(version: String) = AppUpdateInfo(
        versionName = version,
        title = "shadow $version",
        releaseNotes = "",
        releaseUrl = "https://example.com",
        apkName = "shadow.apk",
        apkUrl = "https://example.com/shadow.apk",
        apkSizeBytes = 1L,
        sha256Digest = null,
    )
}
