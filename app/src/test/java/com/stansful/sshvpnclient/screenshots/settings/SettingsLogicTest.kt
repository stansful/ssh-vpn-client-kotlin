package com.stansful.sshvpnclient.screenshots.settings

import com.stansful.sshvpnclient.domain.model.AppUpdateDownloadState
import com.stansful.sshvpnclient.domain.model.AppUpdateState
import com.stansful.sshvpnclient.domain.model.AppUpdateStatusKind
import com.stansful.sshvpnclient.domain.model.CustomThemeColors
import com.stansful.sshvpnclient.domain.model.InstalledAppInfo
import com.stansful.sshvpnclient.ui.apppicker.buildAppPickerList
import com.stansful.sshvpnclient.ui.designsystem.StatusTone
import com.stansful.sshvpnclient.ui.opensource.XrayCoreStatusKind
import com.stansful.sshvpnclient.ui.opensource.XrayCoreUpdateUiState
import com.stansful.sshvpnclient.ui.settings.EngineOutcome
import com.stansful.sshvpnclient.ui.settings.EngineUi
import com.stansful.sshvpnclient.ui.settings.PaletteRole
import com.stansful.sshvpnclient.ui.settings.UpdatePhase
import com.stansful.sshvpnclient.ui.settings.changedRoles
import com.stansful.sshvpnclient.ui.settings.engineOutcome
import com.stansful.sshvpnclient.ui.settings.formatRatio
import com.stansful.sshvpnclient.ui.settings.get
import com.stansful.sshvpnclient.ui.settings.hue
import com.stansful.sshvpnclient.ui.settings.installErrorMessage
import com.stansful.sshvpnclient.ui.settings.paletteName
import com.stansful.sshvpnclient.ui.settings.parseHex
import com.stansful.sshvpnclient.ui.settings.relativeTime
import com.stansful.sshvpnclient.ui.settings.releaseNoteLines
import com.stansful.sshvpnclient.ui.settings.rgb
import com.stansful.sshvpnclient.ui.settings.sanitizeHex
import com.stansful.sshvpnclient.ui.settings.sheetEngine
import com.stansful.sshvpnclient.ui.settings.toHex
import com.stansful.sshvpnclient.ui.settings.updatePhase
import com.stansful.sshvpnclient.ui.settings.updatesUi
import com.stansful.sshvpnclient.ui.settings.with
import com.stansful.sshvpnclient.ui.settings.withHue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure logic behind Settings, Appearance, App routing and the update sheet. */
class SettingsLogicTest {
    @Test
    fun `hex field keeps hex digits only and accepts six of them`() {
        assertEquals("FF9F1C", sanitizeHex("#ff9f1c"))
        assertEquals("ABC", sanitizeHex("a-b-c"))
        assertEquals("123456", sanitizeHex("1234567"))
        assertEquals(0xFFFF9F1C.toInt(), parseHex("FF9F1C"))
        assertNull(parseHex("FF9F1"))
        assertEquals("#FF9F1C", 0xFFFF9F1C.toInt().toHex())
    }

    @Test
    fun `channels clamp and hue changes keep the hue the user picked`() {
        assertEquals(0xFFFF0000.toInt(), rgb(300, -4, 0))
        val orange = 0xFFFF9F1C.toInt()
        assertEquals(35, orange.hue())
        assertEquals(200, orange.withHue(200f).hue())
        // Greys get a usable saturation so the hue strip still changes something.
        assertEquals(120, 0xFF808080.toInt().withHue(120f).hue())
    }

    @Test
    fun `contrast ratio is floored so a failing ratio never reads as passing`() {
        assertEquals("4.4:1", formatRatio(4.49f))
        assertEquals("21.0:1", formatRatio(21f))
    }

    @Test
    fun `draft roles and preset names`() {
        val saved = CustomThemeColors.shadowClassic()
        val draft = saved.with(PaletteRole.Primary, 0xFFFFB547.toInt()).with(PaletteRole.Text, 0xFF54463A.toInt())
        assertEquals(listOf(PaletteRole.Primary, PaletteRole.Text), draft.changedRoles(saved))
        assertEquals(0xFF54463A.toInt(), draft[PaletteRole.Text])
        assertEquals("Shadow classic", saved.paletteName())
        assertEquals("Your colors", draft.paletteName())
    }

    @Test
    fun `engine status kinds become card outcomes`() {
        fun outcome(kind: XrayCoreStatusKind?, message: String? = kind?.let { "line" }) =
            XrayCoreUpdateUiState(statusMessage = message, statusKind = kind).engineOutcome()
        assertEquals(EngineOutcome.NeedsRestart, outcome(XrayCoreStatusKind.INSTALLED_AFTER_RESTART))
        assertEquals(EngineOutcome.Installed, outcome(XrayCoreStatusKind.INSTALLED))
        assertEquals(EngineOutcome.Installed, outcome(XrayCoreStatusKind.ALREADY_INSTALLED))
        assertEquals(EngineOutcome.Cancelled, outcome(XrayCoreStatusKind.CANCELLED))
        assertNull(outcome(XrayCoreStatusKind.RELEASE_FOUND))
        assertNull(outcome(XrayCoreStatusKind.DOWNLOADING))
        assertNull(outcome(XrayCoreStatusKind.NO_ASSET))
        assertNull(outcome(null))
        val tooLarge = "Xray core download is too large"
        assertEquals(EngineOutcome.Error(tooLarge), outcome(XrayCoreStatusKind.INSTALL_FAILED, tooLarge))
        assertEquals(EngineOutcome.Error("offline"), outcome(XrayCoreStatusKind.CHECK_FAILED, "offline"))
        assertEquals(EngineOutcome.Error("busy"), outcome(XrayCoreStatusKind.DOWNLOAD_BLOCKED, "busy"))
    }

    @Test
    fun `update card reads the coordinator's status kind, not its text`() {
        val upToDate = AppUpdateState(statusMessage = "shadow-ssh is up to date", statusKind = AppUpdateStatusKind.UP_TO_DATE)
        val card = updatesUi(upToDate, UpdatePhase.None, sizeBytes = null, lastCheckedAtMs = null, checkedJustNow = true)
        assertTrue(card.upToDate)
        assertNull(card.errorMessage)

        val failed = AppUpdateState(statusMessage = "GitHub is unreachable", statusKind = AppUpdateStatusKind.CHECK_FAILED)
        assertEquals("GitHub is unreachable", updatesUi(failed, UpdatePhase.None, null, null, false).errorMessage)

        val ready = AppUpdateState(statusMessage = "anything", statusKind = AppUpdateStatusKind.READY_TO_INSTALL)
        assertNull(installErrorMessage(ready))
        val installFailed = ready.copy(statusMessage = "No installer", statusKind = AppUpdateStatusKind.ACTION_FAILED)
        assertEquals("No installer", installErrorMessage(installFailed))
    }

    @Test
    fun `update phase follows the download first and remembers the offer`() {
        val offer = SettingsFixtures.update
        assertEquals(UpdatePhase.None, updatePhase(AppUpdateState(), null, null))
        assertEquals(UpdatePhase.Available(offer), updatePhase(AppUpdateState(), offer, null))
        val progress = AppUpdateDownloadState.Downloading("3.5.0", 10L, 100L)
        val stopped = updatePhase(
            AppUpdateState(downloadState = AppUpdateDownloadState.Failed("network", canResume = true)),
            offer,
            progress,
        )
        assertEquals(UpdatePhase.Stopped("network", "3.5.0", 10L, 100L), stopped)
        val rejected = updatePhase(
            AppUpdateState(
                downloadState = AppUpdateDownloadState.Failed(
                    "Downloaded update SHA-256 verification failed",
                    rejected = true,
                ),
            ),
            offer,
            null,
        )
        assertTrue((rejected as UpdatePhase.Failed).rejected)
        // The typed flag decides, not the wording.
        val notRejected = updatePhase(
            AppUpdateState(downloadState = AppUpdateDownloadState.Failed("Downloaded something")),
            offer,
            null,
        )
        assertFalse((notRejected as UpdatePhase.Failed).rejected)
        val reoffered = updatePhase(
            AppUpdateState(availableUpdate = offer, downloadState = AppUpdateDownloadState.Failed("x")),
            null,
            null,
        )
        assertEquals(UpdatePhase.Available(offer), reoffered)
    }

    @Test
    fun `release notes stay plain text with bullets for list lines`() {
        val lines = releaseNoteLines("## 3.5.0\n\n- Faster\n* Smaller\nPlain line\n")
        assertEquals(listOf("## 3.5.0", "Faster", "Smaller", "Plain line"), lines.map { it.text })
        assertEquals(listOf(false, true, true, false), lines.map { it.bullet })
    }

    @Test
    fun `relative times`() {
        val now = 1_000_000_000L
        assertEquals("just now", relativeTime(now - 10_000L, now))
        assertEquals("5 min ago", relativeTime(now - 5 * 60_000L, now))
        assertEquals("2 h ago", relativeTime(now - 2 * 3_600_000L, now))
    }


    @Test
    fun `update sheet engine card follows the engine state`() {
        val installed = EngineUi(installed = true, runtimeAbi = "arm64-v8a")
        val idle = sheetEngine(installed, downloadFailed = false)
        assertEquals("Installed · arm64-v8a", idle.status)
        assertEquals("Check for engine updates", idle.checkLabel)

        val missing = sheetEngine(installed.copy(installed = false), downloadFailed = false)
        assertEquals(StatusTone.Error, missing.tone)
        assertEquals("Find the engine for this phone", missing.checkLabel)

        val found = installed.copy(release = SettingsFixtures.release)
        val available = sheetEngine(found, downloadFailed = false)
        assertEquals("v26.9.30 available", available.status)
        assertEquals("Universal AAR · 41.2 MiB", available.detail)
        assertEquals("Download", available.downloadLabel)
        assertTrue(available.showRelease)
        assertNull(available.checkLabel)

        val inUse = sheetEngine(found.copy(inUse = true), downloadFailed = false)
        assertTrue(inUse.showInUse)
        assertFalse(inUse.downloadEnabled)

        val downloading = sheetEngine(found.copy(isDownloading = true), downloadFailed = false)
        assertTrue(downloading.showCancel && downloading.showProgress && downloading.busy)
        assertNull(downloading.downloadLabel)

        val error = EngineOutcome.Error("Xray core download failed with HTTP 404")
        val failed = sheetEngine(found.copy(outcome = error), downloadFailed = true)
        assertEquals("Download failed · nothing changed", failed.status)
        assertEquals("Try again", failed.downloadLabel)
        assertTrue(failed.noteIsError)
        // A failed lookup keeps the found release on offer and only explains the error.
        val lookupFailed = sheetEngine(found.copy(outcome = error), downloadFailed = false)
        assertEquals("Download", lookupFailed.downloadLabel)
        assertEquals(error.message, lookupFailed.note)

        val restart = sheetEngine(found.copy(outcome = EngineOutcome.NeedsRestart), downloadFailed = false)
        assertEquals("Installed — restart shadow to use it", restart.status)
        assertEquals("v26.9.30 · arm64-v8a", restart.detail)
        assertNull(restart.checkLabel)

        val noAsset = sheetEngine(
            found.copy(release = SettingsFixtures.release.copy(assets = emptyList())),
            downloadFailed = false,
        )
        assertEquals("No arm64-v8a build in v26.9.30", noAsset.status)
        assertNull(noAsset.downloadLabel)
    }
    @Test
    fun `app list keeps checked apps first and hides unchecked system apps`() {
        val apps = listOf(
            InstalledAppInfo("Chrome", "com.android.chrome", false),
            InstalledAppInfo("Telegram", "org.telegram.messenger", false),
            InstalledAppInfo("Gmail", "com.google.android.gm", true),
            InstalledAppInfo("Maps", "com.google.android.apps.maps", true),
        )
        val installed = apps.mapTo(HashSet()) { it.packageName }
        val selected = setOf("org.telegram.messenger", "com.google.android.gm", "com.whatsapp")
        val list = buildAppPickerList(apps, installed, selected, selected, query = "", showSystem = false)
        assertEquals(listOf("Telegram", "Gmail"), list.selected.map { it.label })
        assertEquals(listOf("com.whatsapp"), list.uninstalled)
        assertEquals(listOf("Chrome"), list.others.map { it.label })
        assertEquals(1, list.hiddenSystemMatches)

        val byPackage = buildAppPickerList(apps, installed, selected, selected, query = "apps.maps", showSystem = true)
        assertEquals(listOf("Maps"), byPackage.others.map { it.label })
        assertTrue(byPackage.uninstalled.isEmpty())
    }
}
