package com.stansful.sshvpnclient.domain.model

data class AppUpdateInfo(
    val versionName: String,
    val title: String,
    val releaseNotes: String,
    val releaseUrl: String,
    val apkName: String,
    val apkUrl: String,
    val apkSizeBytes: Long,
    val sha256Digest: String?,
)

data class AppUpdateState(
    val isChecking: Boolean = false,
    val availableUpdate: AppUpdateInfo? = null,
    /** Text for the status line; [statusKind] says what it reports (null together with it). */
    val statusMessage: String? = null,
    val downloadState: AppUpdateDownloadState = AppUpdateDownloadState.Idle,
    val statusKind: AppUpdateStatusKind? = null,
)

/** What [AppUpdateState.statusMessage] reports, so screens can branch without parsing the text. */
enum class AppUpdateStatusKind {
    /** A manual check found nothing newer. */
    UP_TO_DATE,

    /** A manual check failed; the message is the error. */
    CHECK_FAILED,

    /** Download progress ("Downloading shadow-ssh 3.5.0 · 42%"). */
    DOWNLOADING,

    /** The download stopped or its file was rejected; the message is the reason. */
    DOWNLOAD_FAILED,

    /** The verified file is waiting for the installer ("… is ready to install"). */
    READY_TO_INSTALL,

    /** Opening the installer (or another update action) failed; the message is the reason. */
    ACTION_FAILED,
}

sealed interface AppUpdateCheckResult {
    data class Available(val update: AppUpdateInfo) : AppUpdateCheckResult
    data object UpToDate : AppUpdateCheckResult
    data object NotDue : AppUpdateCheckResult
}

sealed interface AppUpdateDownloadState {
    data object Idle : AppUpdateDownloadState
    data class Downloading(
        val versionName: String,
        val downloadedBytes: Long = 0L,
        val totalBytes: Long? = null,
        val isPaused: Boolean = false,
    ) : AppUpdateDownloadState {
        val progressFraction: Float?
            get() = totalBytes
                ?.takeIf { it > 0L }
                ?.let { total -> downloadedBytes.coerceIn(0L, total).toFloat() / total.toFloat() }

        val progressPercent: Int?
            get() = progressFraction?.let { progress -> (progress * 100f).toInt().coerceIn(0, 100) }
    }

    data class ReadyToInstall(
        val versionName: String,
        val contentUri: String,
    ) : AppUpdateDownloadState
    /** [rejected]: the whole file arrived but failed verification (size, SHA-256, package, signature). */
    data class Failed(
        val message: String,
        val canResume: Boolean = false,
        val rejected: Boolean = false,
    ) : AppUpdateDownloadState
}
