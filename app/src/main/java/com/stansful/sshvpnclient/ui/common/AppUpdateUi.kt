package com.stansful.sshvpnclient.ui.common

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.stansful.sshvpnclient.domain.model.AppUpdateDownloadState
import com.stansful.sshvpnclient.domain.model.AppUpdateInfo
import java.util.Locale

data class AppUpdateUiState(
    val isChecking: Boolean = false,
    val availableUpdate: AppUpdateInfo? = null,
    val statusMessage: String? = null,
    val downloadState: AppUpdateDownloadState = AppUpdateDownloadState.Idle,
)

@Composable
fun AppUpdateSettingsSection(
    updateState: AppUpdateUiState,
    onCheckForUpdates: () -> Unit,
    onResumeUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    showTitle: Boolean = true,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showTitle) {
            Text("Application updates", style = MaterialTheme.typography.labelLarge)
        }
        FilledTonalButton(
            onClick = onCheckForUpdates,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 50.dp),
            shape = MaterialTheme.shapes.medium,
        ) {
            if (updateState.isChecking) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(Icons.Default.Refresh, contentDescription = null)
            }
            Text(
                text = if (updateState.isChecking) "Checking" else "Check for updates",
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        UpdateDownloadStatus(
            downloadState = updateState.downloadState,
            onResume = onResumeUpdate,
            onInstall = onInstallUpdate,
        )
        updateState.statusMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun AppUpdateAvailableDialog(
    update: AppUpdateInfo,
    downloadState: AppUpdateDownloadState,
    onLater: () -> Unit,
    onOpenRelease: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
) {
    val readyToInstall = (downloadState as? AppUpdateDownloadState.ReadyToInstall)
        ?.takeIf { it.versionName == update.versionName }
    val downloading = (downloadState as? AppUpdateDownloadState.Downloading)
        ?.takeIf { it.versionName == update.versionName }
    AlertDialog(
        onDismissRequest = onLater,
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text("Update available: ${update.versionName}") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(update.title, fontWeight = FontWeight.SemiBold)
                Text(
                    text = update.releaseNotes.ifBlank { "Release notes are available on GitHub." },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (update.apkSizeBytes > 0L) {
                    Text(
                        text = "APK size: ${formatFileSize(update.apkSizeBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                downloading?.let { state ->
                    DownloadProgressContent(state)
                }
                TextButton(
                    onClick = onOpenRelease,
                    modifier = Modifier.align(Alignment.Start),
                ) {
                    Text("View release notes")
                }
            }
        },
        confirmButton = {
            FilledTonalButton(
                onClick = if (readyToInstall != null) onInstall else onDownload,
                enabled = downloading == null,
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(
                    imageVector = if (readyToInstall != null) {
                        Icons.Default.SystemUpdateAlt
                    } else {
                        Icons.Default.Download
                    },
                    contentDescription = null,
                )
                Text(
                    text = when {
                        readyToInstall != null -> "Install"
                        downloading != null -> "Downloading"
                        else -> "Download"
                    },
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        },
        dismissButton = { TextButton(onClick = onLater) { Text("Later") } },
    )
}

@Composable
private fun UpdateDownloadStatus(
    downloadState: AppUpdateDownloadState,
    onResume: () -> Unit,
    onInstall: () -> Unit,
) {
    when (downloadState) {
        is AppUpdateDownloadState.Downloading -> {
            var expanded by remember(downloadState.versionName) { mutableStateOf(true) }
            Surface(
                onClick = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Text(
                            text = "Downloading ${downloadState.versionName}",
                            modifier = Modifier
                                .padding(start = 10.dp)
                                .weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        downloadState.progressPercent?.let { percent ->
                            Text("$percent%", style = MaterialTheme.typography.labelLarge)
                        }
                        Icon(
                            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (expanded) {
                                "Collapse download progress"
                            } else {
                                "Expand download progress"
                            },
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    AnimatedVisibility(visible = expanded) {
                        DownloadProgressContent(downloadState)
                    }
                }
            }
        }

        is AppUpdateDownloadState.ReadyToInstall -> {
            FilledTonalButton(
                onClick = onInstall,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 50.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(Icons.Default.SystemUpdateAlt, contentDescription = null)
                Text(
                    text = "Install shadow-ssh ${downloadState.versionName}",
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        is AppUpdateDownloadState.Failed -> {
            if (downloadState.canResume) {
                FilledTonalButton(
                    onClick = onResume,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 50.dp),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Text(
                        text = "Resume update download",
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
        AppUpdateDownloadState.Idle,
        -> Unit
    }
}

@Composable
private fun DownloadProgressContent(downloadState: AppUpdateDownloadState.Downloading) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val progress = downloadState.progressFraction
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .progressSemantics(progress),
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .progressSemantics(),
            )
        }
        val totalBytes = downloadState.totalBytes
        Text(
            text = when {
                downloadState.isPaused -> "Download paused by Android"
                totalBytes != null -> {
                    "${formatFileSize(downloadState.downloadedBytes)} of ${formatFileSize(totalBytes)}"
                }
                else -> "Waiting for download size"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

fun formatFileSize(sizeBytes: Long): String {
    val mebibytes = sizeBytes.toDouble() / (1_024.0 * 1_024.0)
    return String.format(Locale.US, "%.1f MiB", mebibytes)
}

fun openAppUpdateInstaller(context: Context, contentUri: String): Result<Unit> = runCatching {
    context.startActivity(
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(contentUri.toUri(), APK_MIME_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
    )
}

private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
