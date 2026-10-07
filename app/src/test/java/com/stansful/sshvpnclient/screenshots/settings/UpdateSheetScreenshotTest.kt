package com.stansful.sshvpnclient.screenshots.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stansful.sshvpnclient.screenshots.ScreenSize
import com.stansful.sshvpnclient.screenshots.ScreenshotTest
import com.stansful.sshvpnclient.screenshots.ShellTab
import com.stansful.sshvpnclient.screenshots.SystemBarsDp
import com.stansful.sshvpnclient.screenshots.TopLevelShellFrame
import com.stansful.sshvpnclient.screenshots.renderScreenshot
import com.stansful.sshvpnclient.ui.settings.EngineOutcome
import com.stansful.sshvpnclient.ui.settings.EngineUi
import com.stansful.sshvpnclient.ui.settings.INSTALL_PERMISSION_OFF
import com.stansful.sshvpnclient.ui.settings.SettingsActions
import com.stansful.sshvpnclient.ui.settings.SheetEngineActions
import com.stansful.sshvpnclient.ui.settings.SettingsScreen
import com.stansful.sshvpnclient.ui.settings.UpdatePhase
import com.stansful.sshvpnclient.ui.settings.UpdateSheet
import com.stansful.sshvpnclient.ui.settings.UpdateSheetActions
import com.stansful.sshvpnclient.ui.settings.UpdateSheetContent
import com.stansful.sshvpnclient.ui.settings.UpdateSheetEngineSection
import com.stansful.sshvpnclient.ui.settings.UpdateSheetState
import com.stansful.sshvpnclient.ui.settings.UpdatesUi
import com.stansful.sshvpnclient.ui.settings.sheetEngine
import com.stansful.sshvpnclient.ui.theme.DayColors
import com.stansful.sshvpnclient.ui.theme.NightColors
import com.stansful.sshvpnclient.ui.theme.Shadow
import com.stansful.sshvpnclient.ui.theme.ShadowColors
import com.stansful.sshvpnclient.ui.theme.ShadowShapes
import org.junit.Test

class UpdateSheetScreenshotTest : ScreenshotTest() {
    private val mib = 1_024L * 1_024L
    private val update = SettingsFixtures.update
    private val installedEngine = EngineUi(installed = true, runtimeAbi = "arm64-v8a")
    private val foundEngine = installedEngine.copy(release = SettingsFixtures.release)
    private val available = UpdateSheetState(
        phase = UpdatePhase.Available(update),
        currentVersion = "3.4.0",
        release = update,
        engine = sheetEngine(installedEngine, downloadFailed = false),
        engineAbi = "arm64-v8a",
    )

    private fun render(name: String, state: UpdateSheetState, colors: ShadowColors = NightColors) {
        renderScreenshot(name, ScreenSize.PHONE, colors) {
            TopLevelShellFrame(ShellTab.Settings) {
                SettingsScreen(
                    state = SettingsFixtures.settings.copy(update = UpdatesUi(phase = state.phase)),
                    actions = SettingsActions(),
                )
            }
            UpdateSheet(state = state, actions = UpdateSheetActions())
        }
    }

    @Test
    fun available() {
        render("UpdateSheet_Available_Night", available)
        render("UpdateSheet_Available_Day", available, DayColors)
    }

    @Test
    fun downloading() {
        render(
            "UpdateSheet_Downloading_Night",
            available.copy(
                phase = UpdatePhase.Downloading("3.5.0", (12.3 * mib).toLong(), update.apkSizeBytes, 42, 0.426f),
            ),
        )
        render(
            "UpdateSheet_Verifying_Night",
            available.copy(phase = UpdatePhase.Downloading("3.5.0", update.apkSizeBytes, update.apkSizeBytes, 100, 1f)),
        )
    }

    @Test
    fun failures() {
        render(
            "UpdateSheet_Stopped_Night",
            available.copy(
                phase = UpdatePhase.Stopped(
                    message = "Update download failed: Update network error. Use Resume update download.",
                    versionName = "3.5.0",
                    savedBytes = (12.3 * mib).toLong(),
                    totalBytes = update.apkSizeBytes,
                ),
            ),
        )
        render(
            "UpdateSheet_Rejected_Night",
            available.copy(
                phase = UpdatePhase.Failed("Downloaded APK signing certificate does not match the installed app", true),
            ),
        )
    }

    @Test
    fun ready() {
        val ready = available.copy(phase = UpdatePhase.Ready("3.5.0", "content://update"))
        render("UpdateSheet_Ready_Night", ready)
        render("UpdateSheet_ReadyStep1_Night", ready.copy(installStep = 1))
        render(
            "UpdateSheet_ReadyStep2_Night",
            ready.copy(installStep = 2, installError = INSTALL_PERMISSION_OFF),
        )
    }


    /** The engine card of the sheet in every state of the Updates artboard. */
    @Test
    fun engineStates() {
        val states = listOf(
            "NotInstalled" to sheetEngine(installedEngine.copy(installed = false), downloadFailed = false),
            "Checking" to sheetEngine(installedEngine.copy(isChecking = true), downloadFailed = false),
            "Available" to sheetEngine(foundEngine, downloadFailed = false),
            "InUse" to sheetEngine(foundEngine.copy(inUse = true), downloadFailed = false),
            "Downloading" to sheetEngine(foundEngine.copy(isDownloading = true), downloadFailed = false),
            "Failed" to sheetEngine(
                foundEngine.copy(outcome = EngineOutcome.Error("Unable to resolve host github.com")),
                downloadFailed = true,
            ),
            "Restart" to sheetEngine(foundEngine.copy(outcome = EngineOutcome.NeedsRestart), downloadFailed = false),
            "Installed" to sheetEngine(foundEngine.copy(outcome = EngineOutcome.Installed), downloadFailed = false),
            "NoAsset" to sheetEngine(
                foundEngine.copy(release = SettingsFixtures.release.copy(assets = emptyList())),
                downloadFailed = false,
            ),
        )
        states.forEach { (name, engine) ->
            renderScreenshot("UpdateSheet_Engine_${name}_Night", ScreenSize.wrap(390), NightColors, SystemBarsDp.None) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Shadow.colors.sheet)
                        .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
                ) {
                    UpdateSheetEngineSection(
                        abi = "arm64-v8a",
                        engine = engine,
                        // The shell always passes "Go to Home".
                        actions = SheetEngineActions(onGoHome = {}),
                    )
                }
            }
        }
    }
    /** The whole sheet body inline (a tall sheet scrolls inside the 844 dp window). */
    @Test
    fun inline() {
        renderScreenshot("UpdateSheet_Inline_Available_Night", ScreenSize.wrap(390), NightColors, SystemBarsDp.None) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Shadow.colors.sheet, ShadowShapes.Sheet)
                    .padding(top = 28.dp, bottom = 22.dp),
            ) {
                UpdateSheetContent(available, UpdateSheetActions())
            }
        }
    }
}
