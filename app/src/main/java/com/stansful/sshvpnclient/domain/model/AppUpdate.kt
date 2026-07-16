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
    data class Failed(
        val message: String,
        val canResume: Boolean = false,
    ) : AppUpdateDownloadState
}
