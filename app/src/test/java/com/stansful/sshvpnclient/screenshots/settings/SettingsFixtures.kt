package com.stansful.sshvpnclient.screenshots.settings

import com.stansful.sshvpnclient.domain.model.AppThemeMode
import com.stansful.sshvpnclient.domain.model.AppUpdateInfo
import com.stansful.sshvpnclient.domain.model.CustomThemeColors
import com.stansful.sshvpnclient.domain.model.VpnMode
import com.stansful.sshvpnclient.domain.model.XrayCoreAsset
import com.stansful.sshvpnclient.domain.model.XrayCoreRelease
import com.stansful.sshvpnclient.ui.settings.EngineUi
import com.stansful.sshvpnclient.ui.settings.LibraryRefresh
import com.stansful.sshvpnclient.ui.settings.SettingsUiState
import com.stansful.sshvpnclient.ui.settings.UpdatesUi

/** Shared sample data of the redesign brief (§9). */
internal object SettingsFixtures {
    const val NOW = 1_759_700_000_000L
    private const val HOUR = 60L * 60L * 1_000L
    private const val MIB = 1_024L * 1_024L

    val release = XrayCoreRelease(
        versionName = "v26.9.30",
        title = "v26.9.30",
        releaseUrl = "https://github.com/stansful/ssh-vpn-client-kotlin/releases/tag/v26.9.30",
        runtimeAbi = "arm64-v8a",
        assets = listOf(
            XrayCoreAsset(
                abi = "arm64-v8a",
                name = "libxray.aar",
                downloadUrl = "https://github.com/stansful/ssh-vpn-client-kotlin/releases/download/v26.9.30/x.aar",
                sizeBytes = (41.2 * MIB).toLong(),
                sha256Digest = null,
                universal = true,
            ),
        ),
    )

    val update = AppUpdateInfo(
        versionName = "3.5.0",
        title = "shadow-ssh 3.5.0",
        releaseNotes = "- Smoother reconnects on mobile networks\n" +
            "- Route library search is faster\n" +
            "- Clearer messages when a server rejects your key\n" +
            "- Small fixes and polish across the app",
        releaseUrl = "https://github.com/stansful/ssh-vpn-client-kotlin/releases/tag/v3.5.0",
        apkName = "shadow-ssh-3.5.0-arm64-v8a.apk",
        apkUrl = "https://github.com/stansful/ssh-vpn-client-kotlin/releases/download/v3.5.0/app.apk",
        apkSizeBytes = (28.9 * MIB).toLong(),
        sha256Digest = null,
    )

    val updateSize: Long = update.apkSizeBytes

    val settings = SettingsUiState(
        versionName = "3.4.0",
        vpnMode = VpnMode.SELECTED_APPS,
        selectedAppsCount = 4,
        showConnectionActivity = true,
        showTerminal = false,
        warnBeforeRoutes = true,
        refreshInBackground = false,
        libraryRouteCount = 128,
        libraryRefresh = LibraryRefresh.Idle(lastUpdatedAtMs = NOW - 2 * HOUR),
        engine = EngineUi(installed = true, runtimeAbi = "arm64-v8a"),
        themeMode = AppThemeMode.SYSTEM,
        customPalette = CustomThemeColors.shadowClassic(),
        update = UpdatesUi(lastCheckedAtMs = NOW - 3 * HOUR),
        nowMs = NOW,
    )
}
