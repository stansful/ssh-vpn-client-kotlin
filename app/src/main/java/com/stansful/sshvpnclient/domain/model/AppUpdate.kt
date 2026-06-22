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
    data class Downloading(val versionName: String) : AppUpdateDownloadState
    data class ReadyToInstall(
        val versionName: String,
        val contentUri: String,
    ) : AppUpdateDownloadState
    data class Failed(val message: String) : AppUpdateDownloadState
}
