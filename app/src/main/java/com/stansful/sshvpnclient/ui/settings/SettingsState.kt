package com.stansful.sshvpnclient.ui.settings

import androidx.compose.runtime.Immutable
import com.stansful.sshvpnclient.domain.model.AppThemeMode
import com.stansful.sshvpnclient.domain.model.AppUpdateState
import com.stansful.sshvpnclient.domain.model.AppUpdateStatusKind
import com.stansful.sshvpnclient.domain.model.CustomThemeColors
import com.stansful.sshvpnclient.domain.model.VpnMode
import com.stansful.sshvpnclient.domain.model.XrayCoreAsset
import com.stansful.sshvpnclient.domain.model.XrayCoreRelease
import com.stansful.sshvpnclient.ui.opensource.OpenSourceViewModel
import com.stansful.sshvpnclient.ui.opensource.XrayCoreStatusKind
import com.stansful.sshvpnclient.ui.opensource.XrayCoreUpdateUiState

/** Everything the Settings screen shows. */
@Immutable
internal data class SettingsUiState(
    val versionName: String,
    val vpnMode: VpnMode = VpnMode.PROXY,
    val selectedAppsCount: Int = 0,
    val showConnectionActivity: Boolean = false,
    val showTerminal: Boolean = false,
    val warnBeforeRoutes: Boolean = true,
    val refreshInBackground: Boolean = false,
    val libraryRouteCount: Int = 0,
    val libraryRefresh: LibraryRefresh = LibraryRefresh.Idle(lastUpdatedAtMs = null),
    val engine: EngineUi = EngineUi(),
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val customPalette: CustomThemeColors = CustomThemeColors.night(),
    val update: UpdatesUi = UpdatesUi(),
    val nowMs: Long = 0L,
)

/** State of the "Refresh now" row of the route library. */
@Immutable
internal sealed interface LibraryRefresh {
    /** Not refreshing; [lastUpdatedAtMs] is the last successful download of the list (null = never). */
    data class Idle(val lastUpdatedAtMs: Long?) : LibraryRefresh

    data object Running : LibraryRefresh

    /** Finished in this visit; [message] is the failure text when it did not work. */
    data class Done(val failureMessage: String?) : LibraryRefresh
}

/** The Xray engine card. */
@Immutable
internal data class EngineUi(
    val installed: Boolean = false,
    val runtimeAbi: String = "",
    /** An Xray session (Auto or Routes) is starting, running or stopping: the engine can't change. */
    val inUse: Boolean = false,
    val isChecking: Boolean = false,
    val isDownloading: Boolean = false,
    val release: XrayCoreRelease? = null,
    val outcome: EngineOutcome? = null,
    /** When the last release lookup finished in this visit (for "just now"). */
    val checkedAtMs: Long? = null,
) {
    val asset: XrayCoreAsset? get() = release?.assets?.firstOrNull { it.abi == release.runtimeAbi }
}

/** The view model's last engine status line, typed so the card can color and place it. */
@Immutable
internal sealed interface EngineOutcome {
    /** Installed and usable right away. */
    data object Installed : EngineOutcome

    /** Installed over an engine that is already loaded: it works after shadow restarts. */
    data object NeedsRestart : EngineOutcome

    data object Cancelled : EngineOutcome

    data class Error(val message: String) : EngineOutcome
}

/** Maps the Routes view model's engine state to the card. */
internal fun engineUi(
    installed: Boolean,
    state: XrayCoreUpdateUiState,
    inUse: Boolean,
    checkedAtMs: Long?,
): EngineUi = EngineUi(
    installed = installed,
    runtimeAbi = state.runtimeAbi,
    inUse = inUse,
    isChecking = state.isChecking,
    isDownloading = state.isDownloading,
    release = state.release,
    outcome = state.engineOutcome(),
    checkedAtMs = checkedAtMs,
)

/** Downloads the found release's engine for this phone's ABI (nothing when the release has none). */
internal fun OpenSourceViewModel.downloadEngineForThisPhone() {
    val engine = uiState.value.xrayCoreUpdateState
    val release = engine.release ?: return
    release.assets.firstOrNull { it.abi == release.runtimeAbi }?.let(::downloadXrayCore)
}

/**
 * The engine status line as a card outcome. Progress and lookup results are drawn by the card itself,
 * so only install results, cancellation and errors become one.
 */
internal fun XrayCoreUpdateUiState.engineOutcome(): EngineOutcome? = when (statusKind) {
    null,
    XrayCoreStatusKind.RELEASE_FOUND,
    XrayCoreStatusKind.NO_ASSET,
    XrayCoreStatusKind.DOWNLOADING,
    -> null
    XrayCoreStatusKind.INSTALLED_AFTER_RESTART -> EngineOutcome.NeedsRestart
    XrayCoreStatusKind.INSTALLED,
    XrayCoreStatusKind.ALREADY_INSTALLED,
    -> EngineOutcome.Installed
    XrayCoreStatusKind.CANCELLED -> EngineOutcome.Cancelled
    XrayCoreStatusKind.CHECK_FAILED,
    XrayCoreStatusKind.DOWNLOAD_BLOCKED,
    XrayCoreStatusKind.INSTALL_FAILED,
    -> EngineOutcome.Error(statusMessage.orEmpty())
}

/** The Updates card. */
@Immutable
internal data class UpdatesUi(
    val isChecking: Boolean = false,
    val phase: UpdatePhase = UpdatePhase.None,
    /** The last manual check reported "up to date" in this process. */
    val upToDate: Boolean = false,
    /** Error text of the last manual check or install attempt. */
    val errorMessage: String? = null,
    /** Last successful GitHub check (any check), null if never. */
    val lastCheckedAtMs: Long? = null,
    /** Size of the release on offer or downloading. */
    val sizeBytes: Long? = null,
    /** A check finished while this screen was open (for "Check again · just now"). */
    val checkedJustNow: Boolean = false,
)

internal fun updatesUi(
    state: AppUpdateState,
    phase: UpdatePhase,
    sizeBytes: Long?,
    lastCheckedAtMs: Long?,
    checkedJustNow: Boolean,
): UpdatesUi {
    val readyError = if (phase is UpdatePhase.Ready) installErrorMessage(state) else null
    val checkError = state.statusMessage.takeIf {
        phase == UpdatePhase.None && state.statusKind != AppUpdateStatusKind.UP_TO_DATE
    }
    return UpdatesUi(
        isChecking = state.isChecking,
        phase = phase,
        upToDate = state.statusKind == AppUpdateStatusKind.UP_TO_DATE,
        errorMessage = readyError ?: checkError,
        lastCheckedAtMs = lastCheckedAtMs,
        sizeBytes = sizeBytes,
        checkedJustNow = checkedJustNow,
    )
}

/** "just now", "5 min ago", "2 h ago", "3 days ago". */
internal fun relativeTime(thenMs: Long, nowMs: Long): String {
    val minutes = ((nowMs - thenMs).coerceAtLeast(0L) / MILLIS_PER_MINUTE)
    return when {
        minutes < 1 -> "just now"
        minutes < MINUTES_PER_HOUR -> "$minutes min ago"
        minutes < MINUTES_PER_DAY -> "${minutes / MINUTES_PER_HOUR} h ago"
        minutes < MINUTES_PER_DAY * 2 -> "yesterday"
        else -> "${minutes / MINUTES_PER_DAY} days ago"
    }
}

/** "today", "yesterday", "3 days ago" — for "checked …". */
internal fun relativeDay(thenMs: Long, nowMs: Long): String {
    val minutes = ((nowMs - thenMs).coerceAtLeast(0L) / MILLIS_PER_MINUTE)
    return when {
        minutes < 1 -> "just now"
        minutes < MINUTES_PER_DAY -> "today"
        minutes < MINUTES_PER_DAY * 2 -> "yesterday"
        else -> "${minutes / MINUTES_PER_DAY} days ago"
    }
}

private const val MILLIS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60L
private const val MINUTES_PER_DAY = 24L * 60L
