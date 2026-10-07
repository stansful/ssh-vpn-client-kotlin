package com.stansful.sshvpnclient.screenshots.routes

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performClick
import com.stansful.sshvpnclient.screenshots.ScreenSize
import com.stansful.sshvpnclient.screenshots.ScreenshotTest
import com.stansful.sshvpnclient.screenshots.ShellTab
import com.stansful.sshvpnclient.screenshots.TopLevelShellFrame
import com.stansful.sshvpnclient.screenshots.renderScreenshot
import com.stansful.sshvpnclient.screenshots.routes.RoutesFixtures.checking
import com.stansful.sshvpnclient.screenshots.routes.RoutesFixtures.routes
import com.stansful.sshvpnclient.screenshots.routes.RoutesFixtures.screen
import com.stansful.sshvpnclient.ui.designsystem.StatusTone
import com.stansful.sshvpnclient.ui.opensource.RouteCounts
import com.stansful.sshvpnclient.ui.opensource.RouteStatusFilter
import com.stansful.sshvpnclient.ui.routes.RoutesActions
import com.stansful.sshvpnclient.ui.routes.RoutesOverlay
import com.stansful.sshvpnclient.ui.routes.RoutesScreen
import com.stansful.sshvpnclient.ui.routes.RoutesScreenState
import com.stansful.sshvpnclient.ui.routes.RoutesUiController
import com.stansful.sshvpnclient.ui.theme.DayColors
import org.junit.Test

/** Routes.dc.html, RoutesSelect.dc.html, RouteImport.dc.html and TabletRoutes.dc.html states. */
class RoutesScreenshotTest : ScreenshotTest() {
    private val editingTallinn = RoutesFixtures.editor(RoutesFixtures.TALLINN_LINK, profileId = "tal")

    @Composable
    private fun Library(
        state: RoutesScreenState,
        controller: RoutesUiController = RoutesUiController(),
        actions: RoutesActions = RoutesActions(readClipboard = { RoutesFixtures.clipboardBatch }),
        railTone: StatusTone = StatusTone.Neutral,
    ) {
        TopLevelShellFrame(ShellTab.Routes, railTone = railTone) {
            RoutesScreen(state = state, actions = actions, controller = controller, linkParser = RoutesFixtures.parser)
        }
    }

    @Test
    fun library() {
        val idle = screen(routes())
        renderScreenshot("Routes_Library_Night") { Library(idle) }
        renderScreenshot("Routes_Library_Day", colors = DayColors) { Library(idle) }
        renderScreenshot("Routes_NoticeExpanded_Night") { Library(screen(routes(riskExpanded = true))) }
    }

    @Test
    fun checking() {
        val state = screen(routes(transform = checking(done = 54, total = 128, soFar = 21)))
        renderScreenshot("Routes_Checking_Night") { Library(state) }
        renderScreenshot(
            "Routes_StopCheckDialog_Night",
        ) { Library(state, RoutesUiController(overlay = RoutesOverlay.StopCheckAndConnect)) }
        val single = screen(routes { copy(isChecking = true, checkingRouteId = "home", checkTotal = 1) })
        renderScreenshot("Routes_CheckingOne_Night") { Library(single) }
    }

    @Test
    fun connection() {
        renderScreenshot("Routes_Connected_Night") {
            Library(screen(routes(vpn = RoutesFixtures.routesConnected)))
        }
        val switching = routes(vpn = RoutesFixtures.routesConnected.copy(activeConfigId = "ams"))
        renderScreenshot("Routes_ConnectedOther_Night") { Library(screen(switching)) }
        val server = screen(routes(vpn = RoutesFixtures.serverConnected), serverName = "Home VPS")
        renderScreenshot("Routes_ServerMode_Night") { Library(server) }
        renderScreenshot("Routes_SwitchDialog_Night") {
            Library(server, RoutesUiController(overlay = RoutesOverlay.SwitchMode()))
        }
        renderScreenshot("Routes_EngineMissing_Night") { Library(screen(routes(engine = false))) }
    }

    @Test
    fun emptyAndSearch() {
        val empty = routes(profiles = emptyList(), all = emptyList(), counts = RouteCounts())
        renderScreenshot("Routes_Empty_Night") { Library(screen(empty)) }
        val noMatch = routes(profiles = emptyList()) { copy(query = "oslo") }
        renderScreenshot("Routes_NoMatch_Night") { Library(screen(noMatch), RoutesUiController(searchOpen = true)) }
        val pinned = routes(profiles = listOf(RoutesFixtures.frankfurt, RoutesFixtures.home)) {
            copy(statusFilter = RouteStatusFilter.PINNED, pinnedOnly = true)
        }
        renderScreenshot("Routes_FilterPinned_Night") { Library(screen(pinned)) }
    }

    @Test
    fun overlays() {
        val idle = screen(routes())
        renderScreenshot("Routes_Menu_Night") { Library(idle, RoutesUiController(menuOpen = true)) }
        renderScreenshot("Routes_RouteSheet_Night") {
            Library(idle, RoutesUiController(overlay = RoutesOverlay.RouteActions("ams")))
        }
        renderScreenshot("Routes_DeleteDialog_Night") {
            Library(idle, RoutesUiController(overlay = RoutesOverlay.DeleteRoute("par")))
        }
        renderScreenshot("Routes_RemoveSheet_Night") {
            Library(screen(routes { copy(showRemoveUnavailableConfirmation = true) }))
        }
        renderScreenshot("Routes_NoApps_Night") { Library(screen(routes { copy(showNoSelectedAppsDialog = true) })) }
    }

    @Test
    fun selection() {
        val selecting = routes { copy(selectedIds = setOf("vil", "rig", "tal"), selectionActive = true) }
        renderScreenshot("Routes_Selecting_Night") { Library(screen(selecting)) }
        renderScreenshot("Routes_Selecting_Day", colors = DayColors) { Library(screen(selecting)) }
        renderScreenshot("Routes_SelectingNone_Night") { Library(screen(routes { copy(selectionActive = true) })) }
        renderScreenshot("Routes_DeleteSelectedSheet_Night") {
            Library(
                screen(routes { copy(selectedIds = setOf("fra", "vil", "rig", "tal"), selectionActive = true) }),
                RoutesUiController(overlay = RoutesOverlay.DeleteSelected),
            )
        }
    }

    @Test
    fun addSheet() {
        fun withEditor(text: String) = screen(routes { copy(editor = RoutesFixtures.editor(text)) })
        renderScreenshot("Routes_AddPaste_Night") { Library(withEditor(RoutesFixtures.OSLO_LINK)) }
        renderScreenshot("Routes_AddPaste_Day", colors = DayColors) { Library(withEditor(RoutesFixtures.OSLO_LINK)) }
        renderScreenshot("Routes_AddEmpty_Night") { Library(withEditor("")) }
        renderScreenshot("Routes_AddDuplicate_Night") { Library(withEditor(RoutesFixtures.AMSTERDAM_COPY)) }
        renderScreenshot("Routes_AddUnsupported_Night") { Library(withEditor(RoutesFixtures.OSLO_SS)) }
        renderScreenshot("Routes_AddSeveral_Night") {
            Library(withEditor(RoutesFixtures.OSLO_LINK + "\n" + RoutesFixtures.AMSTERDAM_COPY))
        }
        renderScreenshot("Routes_AddClipboard_Night") { Library(screen(routes { copy(showBulkImport = true) })) }
        renderScreenshot("Routes_AddClipboardEmpty_Night") {
            Library(screen(routes { copy(showBulkImport = true) }), actions = RoutesActions(readClipboard = { "" }))
        }
        renderScreenshot("Routes_Edit_Night") {
            Library(screen(routes { copy(editor = editingTallinn) }))
        }
        renderScreenshot("Routes_EditError_Night") {
            Library(
                screen(
                    routes {
                        copy(
                            editor = RoutesFixtures.editor(
                                RoutesFixtures.TALLINN_LINK,
                                profileId = "tal",
                                error = "Couldn't save the route. Nothing was changed, try again.",
                            ),
                        )
                    },
                ),
            )
        }
    }

    @Test
    fun tablet() {
        val idle = screen(routes())
        renderScreenshot("Routes_Tablet_Night", ScreenSize.TABLET) { Library(idle) }
        renderScreenshot("Routes_Tablet_Day", ScreenSize.TABLET, DayColors) { Library(idle) }
        renderScreenshot("Routes_Tablet_Outdated_Night", ScreenSize.TABLET) {
            Library(idle, RoutesUiController(viewedRouteId = "tal"))
        }
        renderScreenshot("Routes_Tablet_Connected_Night", ScreenSize.TABLET) {
            Library(screen(routes(vpn = RoutesFixtures.routesConnected)), railTone = StatusTone.Success)
        }
        renderScreenshot("Routes_Tablet_Checking_Night", ScreenSize.TABLET) {
            Library(screen(routes(transform = checking(done = 54, total = 128, soFar = 21))))
        }
        renderScreenshot("Routes_Tablet_Selecting_Night", ScreenSize.TABLET) {
            Library(screen(routes { copy(selectedIds = setOf("vil", "rig", "tal"), selectionActive = true) }))
        }
        renderScreenshot("Routes_Tablet_EditDialog_Night", ScreenSize.TABLET) {
            Library(screen(routes { copy(editor = editingTallinn) }))
        }
        renderScreenshot("Routes_Tablet_DeleteDialog_Night", ScreenSize.TABLET) {
            Library(idle, RoutesUiController(overlay = RoutesOverlay.DeleteRoute("fra")))
        }
    }

    @Test
    fun connectionPhases() {
        renderScreenshot("Routes_Connecting_Night") { Library(screen(routes(vpn = RoutesFixtures.routesConnecting))) }
        renderScreenshot("Routes_Reconnecting_Night") {
            Library(screen(routes(vpn = RoutesFixtures.routesReconnecting)))
        }
        renderScreenshot("Routes_Failed_Night") { Library(screen(routes(vpn = RoutesFixtures.routesFailed))) }
        renderScreenshot("Routes_AutoMode_Night") { Library(screen(routes(vpn = RoutesFixtures.autoConnected))) }
        renderScreenshot("Routes_EngineMissingSize_Night") {
            Library(screen(routes(engine = false) { copy(xrayCoreUpdateState = RoutesFixtures.engineRelease) }))
        }
    }

    /** A long library scrolled down: "Scroll to top" / "Scroll to bottom" appear above the bar. */
    @Test
    fun scrolled() {
        val long = RoutesFixtures.library + RoutesFixtures.more
        renderScreenshot(
            name = "Routes_Scrolled_Night",
            interact = { onNode(hasScrollToIndexAction()).performScrollToIndex(12) },
        ) { Library(screen(routes(profiles = long, all = long))) }
    }

    @Test
    fun clipboardStates() {
        val bulk = screen(routes { copy(showBulkImport = true) })
        renderScreenshot("Routes_AddClipboardFile_Night") {
            Library(bulk, actions = RoutesActions(readClipboard = { RoutesFixtures.CLIPBOARD_FILE }))
        }
        renderScreenshot(
            name = "Routes_AddClipboardEdit_Night",
            interact = { onNodeWithText("Edit").performClick() },
        ) { Library(bulk) }
        renderScreenshot("Routes_AddClipboard_Day", colors = DayColors) { Library(bulk) }
    }

    @Test
    fun tabletStates() {
        renderScreenshot("Routes_Tablet_EngineMissing_Night", ScreenSize.TABLET) {
            Library(screen(routes(engine = false)))
        }
        val server = screen(routes(vpn = RoutesFixtures.serverConnected), serverName = "Home VPS")
        renderScreenshot("Routes_Tablet_Server_Night", ScreenSize.TABLET) {
            Library(server, railTone = StatusTone.Success)
        }
        renderScreenshot("Routes_Tablet_SwitchDialog_Night", ScreenSize.TABLET) {
            Library(
                server,
                RoutesUiController(overlay = RoutesOverlay.SwitchMode("fra")),
                railTone = StatusTone.Success,
            )
        }
        renderScreenshot("Routes_Tablet_RemoveDialog_Night", ScreenSize.TABLET) {
            Library(screen(routes { copy(showRemoveUnavailableConfirmation = true) }))
        }
        renderScreenshot("Routes_Tablet_Error_Night", ScreenSize.TABLET) {
            Library(screen(routes(vpn = RoutesFixtures.routesFailed)), railTone = StatusTone.Error)
        }
        val notChecked = routes(profiles = listOf(RoutesFixtures.home)) {
            copy(statusFilter = RouteStatusFilter.NOT_CHECKED)
        }
        renderScreenshot("Routes_Tablet_ActiveHidden_Night", ScreenSize.TABLET) { Library(screen(notChecked)) }
        renderScreenshot(
            name = "Routes_Tablet_Toast_Night",
            size = ScreenSize.TABLET,
            interact = { onNodeWithContentDescription("Pin Amsterdam · NL 03").performClick() },
        ) { Library(screen(routes())) }
        renderScreenshot("Routes_Tablet_Reconnecting_Night", ScreenSize.TABLET) {
            Library(screen(routes(vpn = RoutesFixtures.routesReconnecting)), railTone = StatusTone.Progress)
        }
    }

    /** Tapping a route makes it active and confirms it with a toast (the connection is untouched). */
    @Test
    fun activationToast() {
        renderScreenshot(
            name = "Routes_ActivationToast_Night",
            interact = { onNodeWithText("Amsterdam · NL 03").performClick() },
        ) { Library(screen(routes())) }
    }
}
