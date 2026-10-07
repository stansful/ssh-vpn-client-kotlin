package com.stansful.sshvpnclient.ui.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import com.stansful.sshvpnclient.domain.model.AppUpdateDownloadState
import com.stansful.sshvpnclient.domain.model.AppUpdateInfo
import com.stansful.sshvpnclient.domain.model.AppUpdateState
import com.stansful.sshvpnclient.domain.model.AppUpdateStatusKind
import java.util.Locale

/** `12.3 MiB` (always one decimal, MiB). */
fun formatFileSize(sizeBytes: Long): String {
    val mebibytes = sizeBytes.toDouble() / (1_024.0 * 1_024.0)
    return String.format(Locale.US, "%.1f MiB", mebibytes)
}

/** `12.3` — the MiB number alone, for "12.3 of 28.9 MiB". */
internal fun formatMebibytes(sizeBytes: Long): String {
    val mebibytes = sizeBytes.toDouble() / (1_024.0 * 1_024.0)
    return String.format(Locale.US, "%.1f", mebibytes)
}

/** Opens Android's package installer for the downloaded APK. */
fun openAppUpdateInstaller(context: Context, contentUri: String): Result<Unit> = runCatching {
    context.startActivity(
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(contentUri.toUri(), APK_MIME_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
    )
}

/**
 * Starts the downloaded update's installation. [install] opens the installer when shadow may install
 * unknown apps and otherwise sends the user to Android's "Install unknown apps" page first, opening the
 * installer on return once it is allowed. Failures go to [onFailed] (the coordinator's status line).
 */
internal class UpdateInstaller(
    private val canInstall: () -> Boolean,
    private val openInstaller: (String) -> Unit,
    private val requestPermission: (String) -> Unit,
) {
    /** True when shadow may already install unknown apps. */
    val canInstallPackages: Boolean get() = canInstall()

    fun install(contentUri: String) {
        if (canInstall()) openInstaller(contentUri) else requestPermission(contentUri)
    }

    /** Opens the "Install unknown apps" page; the installer follows on return if it was allowed. */
    fun requestInstallPermission(contentUri: String) = requestPermission(contentUri)
}

@Composable
internal fun rememberUpdateInstaller(
    onFailed: (String) -> Unit,
    onInstallerOpened: () -> Unit = {},
): UpdateInstaller {
    val context = LocalContext.current
    val currentOnFailed by rememberUpdatedState(onFailed)
    val currentOnOpened by rememberUpdatedState(onInstallerOpened)
    var pendingUri by rememberSaveable { mutableStateOf<String?>(null) }
    val openInstaller: (String) -> Unit = { uri ->
        openAppUpdateInstaller(context, uri)
            .onSuccess { currentOnOpened() }
            .onFailure { error -> currentOnFailed(error.message ?: "Couldn’t open the Android installer.") }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val uri = pendingUri
        pendingUri = null
        when {
            uri == null -> Unit
            context.packageManager.canRequestPackageInstalls() -> openInstaller(uri)
            else -> currentOnFailed(INSTALL_PERMISSION_OFF)
        }
    }
    return remember(context, launcher) {
        UpdateInstaller(
            canInstall = { context.packageManager.canRequestPackageInstalls() },
            openInstaller = openInstaller,
            requestPermission = { uri ->
                pendingUri = uri
                runCatching {
                    launcher.launch(
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            "package:${context.packageName}".toUri(),
                        ),
                    )
                }.onFailure { error ->
                    pendingUri = null
                    currentOnFailed(error.message ?: "Couldn’t open Android settings.")
                }
            },
        )
    }
}

/** What the update UI shows, derived from the coordinator state plus the remembered offer/progress. */
internal sealed interface UpdatePhase {
    /** Nothing to offer: up to date, never checked, or the last manual check failed. */
    data object None : UpdatePhase

    data class Available(val update: AppUpdateInfo) : UpdatePhase

    /** Bytes are still arriving ([verifying] once the whole file is in and its signature is checked). */
    data class Downloading(
        val versionName: String,
        val downloadedBytes: Long,
        val totalBytes: Long?,
        val percent: Int?,
        val fraction: Float?,
    ) : UpdatePhase {
        val verifying: Boolean get() = totalBytes != null && totalBytes > 0 && downloadedBytes >= totalBytes
    }

    data class Ready(val versionName: String, val contentUri: String) : UpdatePhase

    /** A resumable failure; [savedBytes]/[totalBytes] come from the last progress seen in this session. */
    data class Stopped(
        val message: String,
        val versionName: String?,
        val savedBytes: Long?,
        val totalBytes: Long?,
    ) : UpdatePhase

    /** A failure that cannot resume; [rejected] when the downloaded file failed verification. */
    data class Failed(val message: String, val rejected: Boolean) : UpdatePhase
}

internal fun updatePhase(
    state: AppUpdateState,
    offer: AppUpdateInfo?,
    lastProgress: AppUpdateDownloadState.Downloading?,
): UpdatePhase = when (val download = state.downloadState) {
    is AppUpdateDownloadState.Downloading -> UpdatePhase.Downloading(
        versionName = download.versionName,
        downloadedBytes = download.downloadedBytes,
        totalBytes = download.totalBytes,
        percent = download.progressPercent,
        fraction = download.progressFraction,
    )
    is AppUpdateDownloadState.ReadyToInstall -> UpdatePhase.Ready(download.versionName, download.contentUri)
    is AppUpdateDownloadState.Failed -> when {
        state.availableUpdate != null -> UpdatePhase.Available(state.availableUpdate)
        download.canResume -> UpdatePhase.Stopped(
            message = download.message,
            versionName = lastProgress?.versionName ?: offer?.versionName,
            savedBytes = lastProgress?.downloadedBytes,
            totalBytes = lastProgress?.totalBytes,
        )
        else -> UpdatePhase.Failed(message = download.message, rejected = download.rejected)
    }
    AppUpdateDownloadState.Idle -> (state.availableUpdate ?: offer)
        ?.let { UpdatePhase.Available(it) }
        ?: UpdatePhase.None
}

/**
 * While an update is ready: the coordinator's status line when it reports a failed install attempt
 * (the line otherwise repeats "… is ready to install").
 */
internal fun installErrorMessage(state: AppUpdateState): String? =
    state.statusMessage?.takeIf { state.statusKind != AppUpdateStatusKind.READY_TO_INSTALL }

/** Shown when the user came back from Android's "Install unknown apps" page without allowing it. */
internal const val INSTALL_PERMISSION_OFF =
    "Installs from shadow are still off. Turn on Allow from this source, then tap Install again."
private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
