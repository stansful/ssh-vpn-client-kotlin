package com.stansful.sshvpnclient.data.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import com.stansful.sshvpnclient.domain.model.AppUpdateDownloadState
import com.stansful.sshvpnclient.domain.model.AppUpdateInfo
import com.stansful.sshvpnclient.domain.model.SemanticVersion
import com.stansful.sshvpnclient.domain.repository.AppUpdateDownloader
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AndroidAppUpdateDownloader(
    context: Context,
    private val applicationScope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AppUpdateDownloader {
    private val appContext = context.applicationContext
    private val downloadManager = appContext.getSystemService(DownloadManager::class.java)
    private val packageManager = appContext.packageManager
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow<AppUpdateDownloadState>(AppUpdateDownloadState.Idle)
    private var enqueueJob: Job? = null
    private var progressMonitorJob: Job? = null
    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, INVALID_DOWNLOAD_ID)
            if (completedId != preferences.getLong(KEY_DOWNLOAD_ID, INVALID_DOWNLOAD_ID)) return
            val pendingResult = goAsync()
            applicationScope.launch {
                try {
                    inspectPendingDownload(completedId)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    override val state: StateFlow<AppUpdateDownloadState> = mutableState.asStateFlow()

    init {
        ContextCompat.registerReceiver(
            appContext,
            downloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        applicationScope.launch { restorePendingDownload() }
    }

    override fun download(update: AppUpdateInfo) {
        if (mutableState.value is AppUpdateDownloadState.Downloading || enqueueJob?.isActive == true) return
        enqueueJob = applicationScope.launch {
            runCatching {
                enqueueDownload(update)
            }.onSuccess { downloadId ->
                startProgressMonitor(downloadId)
            }.onFailure { error ->
                mutableState.value = AppUpdateDownloadState.Failed(
                    error.message ?: "Unable to start update download",
                )
            }
        }
    }

    private suspend fun enqueueDownload(update: AppUpdateInfo): Long = withContext(ioDispatcher) {
        validateDownloadUrl(update.apkUrl)
        val updateDirectory = File(
            checkNotNull(appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)) {
                "External downloads directory is unavailable"
            },
            UPDATES_DIRECTORY,
        ).apply { mkdirs() }
        updateDirectory.listFiles()
            ?.filter { it.extension.equals("apk", ignoreCase = true) }
            ?.forEach { oldFile -> runCatching { oldFile.delete() } }

        val version = SemanticVersion.parse(update.versionName)
            ?: throw AppUpdateException("Update version is invalid: ${update.versionName}")
        val destination = File(updateDirectory, "shadow-ssh-$version.apk")
        val request = DownloadManager.Request(update.apkUrl.toUri())
            .setTitle("shadow-ssh $version")
            .setDescription("Downloading application update")
            .setMimeType(APK_MIME_TYPE)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setDestinationInExternalFilesDir(
                appContext,
                Environment.DIRECTORY_DOWNLOADS,
                "$UPDATES_DIRECTORY/${destination.name}",
            )

        val downloadId = downloadManager.enqueue(request)
        preferences.edit(commit = true) {
            putLong(KEY_DOWNLOAD_ID, downloadId)
            putString(KEY_FILE_PATH, destination.absolutePath)
            putString(KEY_VERSION_NAME, version.toString())
            putString(KEY_SHA256, update.sha256Digest)
        }
        mutableState.value = AppUpdateDownloadState.Downloading(version.toString())
        downloadId
    }

    private suspend fun restorePendingDownload() {
        val downloadId = withContext(ioDispatcher) {
            preferences.getLong(KEY_DOWNLOAD_ID, INVALID_DOWNLOAD_ID)
        }
        if (downloadId != INVALID_DOWNLOAD_ID) {
            if (inspectPendingDownload(downloadId)) {
                startProgressMonitor(downloadId)
            }
        }
    }

    private fun startProgressMonitor(downloadId: Long) {
        progressMonitorJob?.cancel()
        progressMonitorJob = applicationScope.launch {
            while (inspectPendingDownload(downloadId)) {
                val pollInterval = when ((mutableState.value as? AppUpdateDownloadState.Downloading)?.isPaused) {
                    true -> PAUSED_PROGRESS_POLL_INTERVAL_MS
                    else -> ACTIVE_PROGRESS_POLL_INTERVAL_MS
                }
                delay(pollInterval)
            }
        }
    }

    private suspend fun inspectPendingDownload(downloadId: Long): Boolean = withContext(ioDispatcher) {
        val query = DownloadManager.Query().setFilterById(downloadId)
        downloadManager.query(query)?.use { cursor ->
            if (!cursor.moveToFirst()) {
                failAndClear("Downloaded update is no longer available")
                return@use false
            }
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            when (status) {
                DownloadManager.STATUS_PENDING,
                DownloadManager.STATUS_RUNNING,
                DownloadManager.STATUS_PAUSED,
                -> {
                    val version = preferences.getString(KEY_VERSION_NAME, null).orEmpty()
                    val downloadedBytes = cursor.getLong(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                    ).coerceAtLeast(0L)
                    val rawTotalBytes = cursor.getLong(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
                    )
                    mutableState.value = AppUpdateDownloadState.Downloading(
                        versionName = version,
                        downloadedBytes = downloadedBytes,
                        totalBytes = rawTotalBytes.takeIf { it > 0L },
                        isPaused = status == DownloadManager.STATUS_PAUSED,
                    )
                    true
                }

                DownloadManager.STATUS_SUCCESSFUL -> {
                    validateDownloadedApk()
                    false
                }
                DownloadManager.STATUS_FAILED -> {
                    val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    failAndClear("Update download failed (reason $reason)")
                    false
                }

                else -> false
            }
        } ?: run {
            failAndClear("Android DownloadManager is unavailable")
            false
        }
    }

    private fun validateDownloadedApk() {
        val file = preferences.getString(KEY_FILE_PATH, null)?.let(::File)
            ?: return failAndClear("Downloaded update path is missing")
        val expectedVersion = preferences.getString(KEY_VERSION_NAME, null)
            ?: return failAndClear("Downloaded update version is missing")
        if (!file.isFile || file.length() == 0L) {
            return failAndClear("Downloaded update file is missing")
        }

        val expectedDigest = preferences.getString(KEY_SHA256, null)
        if (!expectedDigest.isNullOrBlank() && sha256(file) != expectedDigest.lowercase()) {
            file.delete()
            return failAndClear("Downloaded update SHA-256 verification failed")
        }

        val archiveInfo = getArchivePackageInfo(file) ?: run {
            file.delete()
            return failAndClear("Downloaded file is not a valid APK")
        }
        val installedInfo = getInstalledPackageInfo()
        if (archiveInfo.packageName != appContext.packageName) {
            file.delete()
            return failAndClear("Downloaded APK has an unexpected package name")
        }
        if (SemanticVersion.parse(archiveInfo.versionName) != SemanticVersion.parse(expectedVersion)) {
            file.delete()
            return failAndClear("Downloaded APK version does not match the GitHub release")
        }
        if (PackageInfoCompat.getLongVersionCode(archiveInfo) <= PackageInfoCompat.getLongVersionCode(installedInfo)) {
            file.delete()
            clearPendingMetadata()
            mutableState.value = AppUpdateDownloadState.Idle
            return
        }
        if (!signaturesMatch(installedInfo, archiveInfo)) {
            file.delete()
            return failAndClear("Downloaded APK signing certificate does not match the installed app")
        }

        val contentUri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            file,
        )
        mutableState.value = AppUpdateDownloadState.ReadyToInstall(
            versionName = expectedVersion,
            contentUri = contentUri.toString(),
        )
    }

    @Suppress("DEPRECATION")
    private fun getArchivePackageInfo(file: File): PackageInfo? {
        return packageManager.getPackageArchiveInfo(file.absolutePath, PACKAGE_INFO_FLAGS)
    }

    @Suppress("DEPRECATION")
    private fun getInstalledPackageInfo(): PackageInfo {
        return packageManager.getPackageInfo(appContext.packageName, PACKAGE_INFO_FLAGS)
    }

    @Suppress("DEPRECATION")
    private fun signaturesMatch(installed: PackageInfo, archive: PackageInfo): Boolean {
        val installedSignatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            installed.signingInfo?.signingCertificateHistory.orEmpty()
        } else {
            installed.signatures.orEmpty()
        }.map { signature -> sha256(signature.toByteArray()) }.toSet()
        val archiveSignatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archive.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            archive.signatures.orEmpty()
        }.map { signature -> sha256(signature.toByteArray()) }.toSet()
        return archiveSignatures.isNotEmpty() && installedSignatures.containsAll(archiveSignatures)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(HASH_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun sha256(value: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value)
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun validateDownloadUrl(value: String) {
        val uri = value.toUri()
        val validPathPrefix = "/stansful/ssh-vpn-client-kotlin/releases/download/"
        if (uri.scheme != "https" || uri.host != "github.com" || !uri.path.orEmpty().startsWith(validPathPrefix)) {
            throw AppUpdateException("GitHub release returned an invalid APK URL")
        }
    }

    private fun failAndClear(message: String) {
        clearPendingMetadata()
        mutableState.value = AppUpdateDownloadState.Failed(message)
    }

    private fun clearPendingMetadata() {
        preferences.edit(commit = true) { clear() }
    }

    private companion object {
        const val PREFERENCES_NAME = "shadow-ssh-update-download"
        const val KEY_DOWNLOAD_ID = "download_id"
        const val KEY_FILE_PATH = "file_path"
        const val KEY_VERSION_NAME = "version_name"
        const val KEY_SHA256 = "sha256"
        const val UPDATES_DIRECTORY = "updates"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        const val INVALID_DOWNLOAD_ID = -1L
        const val HASH_BUFFER_SIZE = 32 * 1_024
        const val ACTIVE_PROGRESS_POLL_INTERVAL_MS = 5_000L
        const val PAUSED_PROGRESS_POLL_INTERVAL_MS = 30_000L
        val PACKAGE_INFO_FLAGS: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
    }
}
