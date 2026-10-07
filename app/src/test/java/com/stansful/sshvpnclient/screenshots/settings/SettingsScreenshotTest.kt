package com.stansful.sshvpnclient.screenshots.settings

import com.stansful.sshvpnclient.domain.model.AppThemeMode
import com.stansful.sshvpnclient.screenshots.ScreenSize
import com.stansful.sshvpnclient.screenshots.ScreenshotTest
import com.stansful.sshvpnclient.screenshots.ShellTab
import com.stansful.sshvpnclient.screenshots.TopLevelShellFrame
import com.stansful.sshvpnclient.screenshots.renderScreenshot
import com.stansful.sshvpnclient.ui.designsystem.ToasterState
import com.stansful.sshvpnclient.ui.settings.EngineOutcome
import com.stansful.sshvpnclient.ui.settings.LibraryRefresh
import com.stansful.sshvpnclient.ui.settings.SettingsActions
import com.stansful.sshvpnclient.ui.settings.SettingsScreen
import com.stansful.sshvpnclient.ui.settings.SettingsUiState
import com.stansful.sshvpnclient.ui.settings.UpdatePhase
import com.stansful.sshvpnclient.ui.settings.UpdatesUi
import com.stansful.sshvpnclient.ui.theme.DayColors
import com.stansful.sshvpnclient.ui.theme.NightColors
import com.stansful.sshvpnclient.ui.theme.ShadowColors
import org.junit.Test

class SettingsScreenshotTest : ScreenshotTest() {
    private val base = SettingsFixtures.settings
    private val tall = ScreenSize(390, 1760)

    private fun render(
        name: String,
        state: SettingsUiState,
        colors: ShadowColors = NightColors,
        size: ScreenSize = tall,
    ) {
        renderScreenshot(name, size, colors) {
            TopLevelShellFrame(ShellTab.Settings) { SettingsScreen(state = state, actions = SettingsActions()) }
        }
    }

    @Test
    fun idle() {
        render("Settings_Idle_Night", base)
        render("Settings_Idle_Day", base, DayColors)
        render("Settings_Idle_Phone_Night", base, size = ScreenSize.PHONE)
    }

    /** Settings.dc.html: a centred, content-width toast; Day tints the success icon with mint text. */
    @Test
    fun toast() {
        listOf("Settings_Toast_Night" to NightColors, "Settings_Toast_Day" to DayColors).forEach { (name, colors) ->
            val toaster = ToasterState()
            renderScreenshot(
                name = name,
                size = ScreenSize.PHONE,
                colors = colors,
                toaster = toaster,
                interact = { runOnUiThread { toaster.show("Link copied") } },
            ) {
                TopLevelShellFrame(ShellTab.Settings) { SettingsScreen(state = base, actions = SettingsActions()) }
            }
        }
    }

    @Test
    fun found() {
        val found = base.copy(
            themeMode = AppThemeMode.CUSTOM,
            libraryRefresh = LibraryRefresh.Done(failureMessage = null),
            engine = base.engine.copy(release = SettingsFixtures.release, checkedAtMs = SettingsFixtures.NOW),
            update = UpdatesUi(
                phase = UpdatePhase.Available(SettingsFixtures.update),
                checkedJustNow = true,
                sizeBytes = SettingsFixtures.updateSize,
                lastCheckedAtMs = SettingsFixtures.NOW,
            ),
        )
        render("Settings_Found_Night", found)
    }

    @Test
    fun engineStates() {
        render(
            "Settings_EngineInUse_Night",
            base.copy(
                engine = base.engine.copy(
                    inUse = true,
                    release = SettingsFixtures.release,
                    checkedAtMs = SettingsFixtures.NOW,
                ),
                libraryRefresh = LibraryRefresh.Running,
            ),
        )
        render(
            "Settings_EngineMissing_Night",
            base.copy(engine = base.engine.copy(installed = false, isChecking = true)),
        )
        render(
            "Settings_EngineDownloading_Night",
            base.copy(
                engine = base.engine.copy(
                    installed = false,
                    isDownloading = true,
                    release = SettingsFixtures.release,
                    checkedAtMs = SettingsFixtures.NOW,
                ),
            ),
        )
        render(
            "Settings_EngineRestart_Night",
            base.copy(engine = base.engine.copy(outcome = EngineOutcome.NeedsRestart)),
        )
    }

    @Test
    fun updateStates() {
        val mib = 1_024L * 1_024L
        render(
            "Settings_UpdateDownloading_Night",
            base.copy(
                update = UpdatesUi(
                    phase = UpdatePhase.Downloading(
                        versionName = "3.5.0",
                        downloadedBytes = (12.3 * mib).toLong(),
                        totalBytes = SettingsFixtures.updateSize,
                        percent = 42,
                        fraction = 0.42f,
                    ),
                    sizeBytes = SettingsFixtures.updateSize,
                ),
            ),
        )
        render(
            "Settings_UpdateReady_Night",
            base.copy(
                update = UpdatesUi(
                    phase = UpdatePhase.Ready("3.5.0", "content://update"),
                    sizeBytes = SettingsFixtures.updateSize,
                ),
            ),
        )
        render(
            "Settings_UpdateStopped_Night",
            base.copy(
                update = UpdatesUi(
                    phase = UpdatePhase.Stopped(
                        message = "Update download failed: Update network error. Use Resume update download.",
                        versionName = "3.5.0",
                        savedBytes = (12.3 * mib).toLong(),
                        totalBytes = SettingsFixtures.updateSize,
                    ),
                ),
            ),
        )
        render(
            "Settings_UpdateCheckFailed_Night",
            base.copy(
                update = UpdatesUi(errorMessage = "GitHub update check failed with HTTP 503"),
                libraryRefresh = LibraryRefresh.Done("Public configuration source returned HTTP 404"),
            ),
        )
    }

    @Test
    fun tablet() {
        renderScreenshot("Settings_Tablet_Night", ScreenSize.TABLET, NightColors) {
            TopLevelShellFrame(ShellTab.Settings) { SettingsScreen(state = base, actions = SettingsActions()) }
        }
    }
}
