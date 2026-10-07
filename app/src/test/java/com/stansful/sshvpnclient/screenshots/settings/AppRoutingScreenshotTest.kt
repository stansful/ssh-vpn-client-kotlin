package com.stansful.sshvpnclient.screenshots.settings

import com.stansful.sshvpnclient.domain.model.InstalledAppInfo
import com.stansful.sshvpnclient.domain.model.VpnMode
import com.stansful.sshvpnclient.screenshots.ScreenSize
import com.stansful.sshvpnclient.screenshots.ScreenshotTest
import com.stansful.sshvpnclient.screenshots.renderScreenshot
import com.stansful.sshvpnclient.ui.apppicker.AppPickerList
import com.stansful.sshvpnclient.ui.apppicker.AppPickerUiState
import com.stansful.sshvpnclient.ui.apppicker.buildAppPickerList
import com.stansful.sshvpnclient.ui.designsystem.ShadowIcons
import com.stansful.sshvpnclient.ui.designsystem.ToastTone
import com.stansful.sshvpnclient.ui.designsystem.ToasterState
import com.stansful.sshvpnclient.ui.settings.AppRoutingActions
import com.stansful.sshvpnclient.ui.settings.AppRoutingScreen
import com.stansful.sshvpnclient.ui.settings.AppRoutingUiState
import com.stansful.sshvpnclient.ui.settings.RoutingConnection
import com.stansful.sshvpnclient.ui.theme.DayColors
import com.stansful.sshvpnclient.ui.theme.NightColors
import com.stansful.sshvpnclient.ui.theme.ShadowColors
import org.junit.Test

class AppRoutingScreenshotTest : ScreenshotTest() {
    private val apps = listOf(
        app("Chrome", "com.android.chrome"),
        app("Discord", "com.discord"),
        app("Firefox", "org.mozilla.firefox"),
        app("Instagram", "com.instagram.android"),
        app("Signal", "org.thoughtcrime.securesms"),
        app("Spotify", "com.spotify.music"),
        app("Telegram", "org.telegram.messenger"),
        app("Termux", "com.termux"),
        app("YouTube", "com.google.android.youtube"),
        app("Gmail", "com.google.android.gm", system = true),
        app("Google Play Store", "com.android.vending", system = true),
        app("Maps", "com.google.android.apps.maps", system = true),
    )
    private val saved = setOf(
        "org.telegram.messenger",
        "com.android.chrome",
        "com.google.android.youtube",
        "com.instagram.android",
    )

    private fun app(label: String, packageName: String, system: Boolean = false) =
        InstalledAppInfo(label = label, packageName = packageName, isSystem = system)

    private fun picker(
        selected: Set<String> = saved,
        group: Set<String> = selected,
        query: String = "",
        showSystem: Boolean = false,
        vpnMode: VpnMode = VpnMode.SELECTED_APPS,
        pending: Boolean = false,
        editing: Boolean = false,
        loading: Boolean = false,
        failed: Boolean = false,
    ): AppPickerUiState {
        val installed = apps.mapTo(HashSet()) { it.packageName }
        return AppPickerUiState(
            query = query,
            showSystemApps = showSystem,
            selectedPackages = selected,
            installedPackages = if (loading || failed) emptySet() else installed,
            installedApps = if (loading || failed) emptyList() else apps,
            list = if (loading || failed) {
                AppPickerList()
            } else {
                buildAppPickerList(apps, installed, selected, group, query, showSystem)
            },
            isLoading = loading,
            loadFailed = failed,
            vpnMode = vpnMode,
            pendingSelectedApps = pending,
            editingWhileAllApps = editing,
        )
    }

    private fun render(
        name: String,
        state: AppRoutingUiState,
        colors: ShadowColors = NightColors,
        size: ScreenSize = ScreenSize.PHONE,
        toaster: ToasterState = ToasterState(),
        toast: (ToasterState.() -> Unit)? = null,
    ) {
        renderScreenshot(
            name = name,
            size = size,
            colors = colors,
            toaster = toaster,
            interact = toast?.let { show -> { runOnUiThread { toaster.show() } } },
        ) {
            AppRoutingScreen(state = state, actions = AppRoutingActions())
        }
    }

    @Test
    fun selectedApps() {
        val connected = AppRoutingUiState(picker(), RoutingConnection.Connected)
        render("AppRouting_Selected_Night", connected)
        render("AppRouting_Selected_Day", connected, DayColors)
        render("AppRouting_Selected_Tall_Night", connected, size = ScreenSize(390, 1400))
        render(
            name = "AppRouting_Reconnecting_Night",
            state = connected.copy(connection = RoutingConnection.Reconnecting),
            toast = {
                show("Reconnecting to apply your changes…", tone = ToastTone.Neutral, icon = ShadowIcons.Refresh)
            },
        )
        render(
            "AppRouting_SystemShown_Night",
            AppRoutingUiState(picker(showSystem = true), RoutingConnection.Connected),
            size = ScreenSize(390, 1400),
        )
    }

    @Test
    fun allApps() {
        render("AppRouting_All_Night", AppRoutingUiState(picker(vpnMode = VpnMode.PROXY)))
        render("AppRouting_All_Day", AppRoutingUiState(picker(vpnMode = VpnMode.PROXY)), DayColors)
        render(
            "AppRouting_AllEmpty_Night",
            AppRoutingUiState(picker(selected = emptySet(), vpnMode = VpnMode.PROXY)),
        )
        render(
            "AppRouting_EditingInAll_Night",
            AppRoutingUiState(picker(vpnMode = VpnMode.PROXY, editing = true)),
        )
    }

    @Test
    fun emptyAndPending() {
        render(
            "AppRouting_Pending_Night",
            AppRoutingUiState(picker(selected = emptySet(), vpnMode = VpnMode.PROXY, pending = true)),
        )
        render(
            "AppRouting_EmptyError_Night",
            AppRoutingUiState(picker(selected = emptySet(), group = saved), RoutingConnection.Connected),
        )
        render(
            "AppRouting_Uninstalled_Night",
            AppRoutingUiState(
                picker(selected = saved + "com.whatsapp", group = saved + "com.whatsapp"),
                RoutingConnection.Connected,
            ),
            size = ScreenSize(390, 1200),
        )
        render(
            name = "AppRouting_TurnedOn_Night",
            state = AppRoutingUiState(picker(selected = setOf("org.telegram.messenger"), group = emptySet())),
            toast = { show("Only selected apps is on now", icon = ShadowIcons.CheckCircle) },
        )
    }

    @Test
    fun listStates() {
        render("AppRouting_Loading_Night", AppRoutingUiState(picker(loading = true), RoutingConnection.Connected))
        render("AppRouting_LoadError_Night", AppRoutingUiState(picker(failed = true), RoutingConnection.Connected))
        render(
            "AppRouting_NoMatches_Night",
            AppRoutingUiState(picker(query = "gmai"), RoutingConnection.Connected),
        )
        render(
            "AppRouting_SearchHint_Night",
            AppRoutingUiState(picker(query = "go"), RoutingConnection.Connected),
        )
    }
}
