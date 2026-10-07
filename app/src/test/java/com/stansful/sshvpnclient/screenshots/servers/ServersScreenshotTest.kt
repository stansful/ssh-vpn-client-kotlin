package com.stansful.sshvpnclient.screenshots.servers

import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.stansful.sshvpnclient.screenshots.ScreenSize
import com.stansful.sshvpnclient.screenshots.ScreenshotScope
import com.stansful.sshvpnclient.screenshots.ScreenshotTest
import com.stansful.sshvpnclient.screenshots.ShellTab
import com.stansful.sshvpnclient.screenshots.TopLevelShellFrame
import com.stansful.sshvpnclient.screenshots.renderScreenshot
import com.stansful.sshvpnclient.ui.configs.ConfigListUiState
import com.stansful.sshvpnclient.ui.designsystem.StatusTone
import com.stansful.sshvpnclient.ui.designsystem.ToastTone
import com.stansful.sshvpnclient.ui.designsystem.ToasterState
import com.stansful.sshvpnclient.ui.keys.KeyDeleteError
import com.stansful.sshvpnclient.ui.keys.KeyListUiState
import com.stansful.sshvpnclient.ui.servers.ConnectionPhase
import com.stansful.sshvpnclient.ui.servers.ServerConnection
import com.stansful.sshvpnclient.ui.servers.ServersActions
import com.stansful.sshvpnclient.ui.servers.ServersScreen
import com.stansful.sshvpnclient.ui.servers.ServersScreenState
import com.stansful.sshvpnclient.ui.servers.ServersTab
import com.stansful.sshvpnclient.ui.theme.DayColors
import com.stansful.sshvpnclient.ui.theme.NightColors
import com.stansful.sshvpnclient.ui.theme.ShadowColors
import org.junit.Test

class ServersScreenshotTest : ScreenshotTest() {

    private val connected = ServersScreenState(
        servers = ServersFixtures.serverList(),
        keys = ServersFixtures.keyList,
        connection = ServerConnection(ConnectionPhase.Connected, "home"),
    )

    private fun render(
        name: String,
        state: ServersScreenState,
        colors: ShadowColors = NightColors,
        size: ScreenSize = ScreenSize.PHONE,
        toaster: ToasterState = ToasterState(),
        reducedMotion: Boolean = true,
        settleMillis: Long = 1_500,
        interact: (ScreenshotScope.() -> Unit)? = null,
    ) {
        renderScreenshot(
            name,
            size,
            colors,
            reducedMotion = reducedMotion,
            toaster = toaster,
            settleMillis = settleMillis,
            interact = interact,
        ) {
            TopLevelShellFrame(ShellTab.Servers, railTone = StatusTone.Success) {
                ServersScreen(state = state, actions = ServersActions(), today = ServersFixtures.today)
            }
        }
    }

    @Test
    fun serversConnected() {
        render("Servers_Connected_Night", connected)
        render("Servers_Connected_Day", connected, DayColors)
    }

    @Test
    fun serversStates() {
        render("Servers_Disconnected_Night", connected.copy(connection = null))
        render(
            "Servers_Reconnecting_Night",
            connected.copy(connection = ServerConnection(ConnectionPhase.Reconnecting, "home")),
        )
        render(
            "Servers_ConnectedViaDeleted_Night",
            connected.copy(
                servers = ConfigListUiState(items = ServersFixtures.servers(null).drop(1), isLoaded = true),
                connection = ServerConnection(ConnectionPhase.Connected, "home"),
            ),
        )
        render(
            "Servers_Connecting_Night",
            connected.copy(connection = ServerConnection(ConnectionPhase.Connecting, "home")),
        )
        render(
            "Servers_NoSelection_Night",
            connected.copy(connection = null, servers = ServersFixtures.serverList(selectedId = null)),
        )
        render(
            "Servers_DeletedNamed_Night",
            connected.copy(
                servers = ConfigListUiState(
                    items = ServersFixtures.servers(null).drop(1),
                    isLoaded = true,
                    removedNames = mapOf("home" to "Home VPS"),
                ),
            ),
        )
        render("Servers_Empty_Night", ServersScreenState(servers = ConfigListUiState(isLoaded = true)))
        render("Servers_Empty_Day", ServersScreenState(servers = ConfigListUiState(isLoaded = true)), DayColors)
    }

    @Test
    fun serversMenuAndDialogs() {
        render(
            name = "Servers_Menu_Night",
            state = connected,
            interact = { onNodeWithContentDescription("Server actions, Office bastion").performClick() },
        )
        render(
            "Servers_DeleteDialog_Night",
            connected.copy(servers = ServersFixtures.serverList().copy(pendingDeleteId = "home")),
        )
        render(
            "Servers_DeleteFailed_Day",
            connected.copy(
                connection = null,
                servers = ServersFixtures.serverList().copy(pendingDeleteId = "pi", deleteFailed = true),
            ),
            DayColors,
        )
    }

    @Test
    fun serversToast() {
        val toaster = ToasterState()
        render(
            name = "Servers_Toast_Night",
            state = connected,
            toaster = toaster,
            interact = { runOnUiThread { toaster.show("Reconnect to use Office bastion", tone = ToastTone.Info) } },
        )
    }

    @Test
    fun serversSelectFailed() {
        val toaster = ToasterState()
        render(
            name = "Servers_SelectFailed_Night",
            state = connected.copy(connection = null),
            toaster = toaster,
            interact = {
                runOnUiThread { toaster.show("Couldn’t save your choice. Try again.", tone = ToastTone.Error) }
            },
        )
    }

    @Test
    fun serversTablet() {
        render("Servers_Tablet_Night", connected, size = ScreenSize.TABLET)
    }

    @Test
    fun keys() {
        val keysTab = connected.copy(tab = ServersTab.Keys)
        render("Keys_List_Night", keysTab)
        render("Keys_List_Day", keysTab, DayColors)
        render("Keys_Empty_Night", keysTab.copy(keys = KeyListUiState(isLoaded = true)))
        // The card of a key just saved flashes amber (captured mid-flash, with motion on).
        render("Keys_Fresh_Night", keysTab.copy(freshKeyId = "pi"), reducedMotion = false, settleMillis = 500)
        render(
            name = "Keys_Menu_Night",
            state = keysTab,
            interact = { onNodeWithContentDescription("Actions for pi-rsa").performClick() },
        )
    }

    @Test
    fun keyDialogs() {
        val keysTab = connected.copy(tab = ServersTab.Keys)
        render("Keys_InUseDialog_Night", keysTab.copy(keys = ServersFixtures.keyList.copy(pendingDeleteId = "work")))
        render("Keys_DeleteDialog_Night", keysTab.copy(keys = ServersFixtures.keyList.copy(pendingDeleteId = "old")))
        render(
            "Keys_DeleteFailed_Day",
            keysTab.copy(
                keys = ServersFixtures.keyList.copy(pendingDeleteId = "old", deleteError = KeyDeleteError.Storage),
            ),
            DayColors,
        )
    }
}
