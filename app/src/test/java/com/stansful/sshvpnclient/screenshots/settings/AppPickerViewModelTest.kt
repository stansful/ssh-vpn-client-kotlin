package com.stansful.sshvpnclient.screenshots.settings

import android.app.Application
import android.os.Looper
import com.stansful.sshvpnclient.domain.model.AppSettings
import com.stansful.sshvpnclient.domain.model.AppThemeMode
import com.stansful.sshvpnclient.domain.model.CustomThemeColors
import com.stansful.sshvpnclient.domain.model.GlobalTab
import com.stansful.sshvpnclient.domain.model.InstalledAppInfo
import com.stansful.sshvpnclient.domain.model.VpnMode
import com.stansful.sshvpnclient.domain.repository.AppSettingsRepository
import com.stansful.sshvpnclient.domain.repository.InstalledAppsRepository
import com.stansful.sshvpnclient.ui.apppicker.AppPickerViewModel
import java.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The App routing save rules (viewModelScope needs the Robolectric main looper). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AppPickerViewModelTest {
    private val apps = listOf(
        InstalledAppInfo("Chrome", "com.android.chrome", false),
        InstalledAppInfo("Telegram", "org.telegram.messenger", false),
    )

    @Test
    fun `only selected apps stays pending until an installed app is picked`() {
        val settings = FakeSettings(AppSettings(vpnMode = VpnMode.PROXY, selectedAppPackages = setOf("com.whatsapp")))
        val viewModel = AppPickerViewModel(settings, FakeApps(apps))
        viewModel.startSession()

        viewModel.chooseSelectedApps()
        assertEquals(VpnMode.PROXY, settings.settings.value.vpnMode)

        viewModel.togglePackage("org.telegram.messenger")
        assertEquals(VpnMode.SELECTED_APPS, settings.settings.value.vpnMode)
        assertEquals(setOf("com.whatsapp", "org.telegram.messenger"), settings.settings.value.selectedAppPackages)
        // The list is written while All apps is still on, so only the mode change reconnects.
        assertEquals(listOf("list", "mode:selected-apps"), settings.writes)
    }

    @Test
    fun `unchecking every app is held back until the screen closes`() {
        val settings = FakeSettings(
            AppSettings(vpnMode = VpnMode.SELECTED_APPS, selectedAppPackages = setOf("org.telegram.messenger")),
        )
        val viewModel = AppPickerViewModel(settings, FakeApps(apps))
        viewModel.startSession()

        viewModel.togglePackage("org.telegram.messenger")
        idle(Duration.ofSeconds(2))
        assertEquals(setOf("org.telegram.messenger"), settings.settings.value.selectedAppPackages)

        viewModel.saveSelection()
        assertTrue(settings.settings.value.selectedAppPackages.isEmpty())
    }

    @Test
    fun `edits apply once the user pauses`() {
        val settings = FakeSettings(
            AppSettings(vpnMode = VpnMode.SELECTED_APPS, selectedAppPackages = setOf("org.telegram.messenger")),
        )
        val viewModel = AppPickerViewModel(settings, FakeApps(apps))
        viewModel.startSession()

        viewModel.togglePackage("com.android.chrome")
        assertEquals(setOf("org.telegram.messenger"), settings.settings.value.selectedAppPackages)
        idle(Duration.ofSeconds(1))
        assertEquals(setOf("org.telegram.messenger", "com.android.chrome"), settings.settings.value.selectedAppPackages)
        assertEquals(listOf("list"), settings.writes)
    }

    @Test
    fun `editing the list under all apps never changes the mode`() {
        val settings = FakeSettings(AppSettings(vpnMode = VpnMode.PROXY))
        val viewModel = AppPickerViewModel(settings, FakeApps(apps))
        viewModel.startSession()

        viewModel.editListWhileAllApps()
        viewModel.togglePackage("com.android.chrome")
        viewModel.finishEditingList()
        assertEquals(VpnMode.PROXY, settings.settings.value.vpnMode)
        assertEquals(setOf("com.android.chrome"), settings.settings.value.selectedAppPackages)
    }

    @Test
    fun `nothing is written before the visit starts`() {
        val settings = FakeSettings(AppSettings(selectedAppPackages = setOf("com.android.chrome")))
        val viewModel = AppPickerViewModel(settings, FakeApps(apps))
        viewModel.saveSelection()
        assertTrue(settings.writes.isEmpty())
    }

    private fun idle(duration: Duration) {
        shadowOf(Looper.getMainLooper()).idleFor(duration)
    }

    private class FakeApps(private val apps: List<InstalledAppInfo>) : InstalledAppsRepository {
        override suspend fun getInstalledApps(): List<InstalledAppInfo> = apps
    }

    private class FakeSettings(initial: AppSettings) : AppSettingsRepository {
        private val state = MutableStateFlow(initial)
        val writes = mutableListOf<String>()
        override val settings: StateFlow<AppSettings> = state

        override fun setVpnMode(vpnMode: VpnMode) {
            writes += "mode:${vpnMode.storageValue}"
            state.value = state.value.copy(vpnMode = vpnMode)
        }

        override fun setSelectedAppPackages(packageNames: Set<String>) {
            writes += "list"
            state.value = state.value.copy(selectedAppPackages = packageNames.toSortedSet())
        }

        override fun setShowLogsOnMain(show: Boolean) = Unit
        override fun setShowLogsOnOpenSource(show: Boolean) = Unit
        override fun setShowLogsOnSmartConnect(show: Boolean) = Unit
        override fun setShowConnectionActivity(show: Boolean) = Unit
        override fun setShowTerminalOnMain(show: Boolean) = Unit
        override fun setThemeMode(themeMode: AppThemeMode) = Unit
        override fun setCustomThemeColors(colors: CustomThemeColors) = Unit
        override fun setActiveGlobalTab(tab: GlobalTab) = Unit
        override fun setOpenSourceConsentVersion(version: Int) = Unit
        override fun setShowOpenSourceWarningOnEnter(show: Boolean) = Unit
        override fun setOpenSourceRiskBannerExpanded(expanded: Boolean) = Unit
        override fun setOpenSourceAutoUpdateEnabled(enabled: Boolean) = Unit
        override fun setSmartConnectConsentVersion(version: Int) = Unit
        override fun setShowSmartConnectWarningOnEnter(show: Boolean) = Unit
    }
}
