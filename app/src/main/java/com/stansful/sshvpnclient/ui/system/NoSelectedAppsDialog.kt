package com.stansful.sshvpnclient.ui.system

import androidx.compose.runtime.Composable
import com.stansful.sshvpnclient.ui.designsystem.ShadowDialog

/**
 * Connect was blocked because App routing is "Only selected apps" with none picked. One dialog for
 * Home and the Route library (same words as Home's "Pick apps" failure note).
 */
@Composable
fun NoSelectedAppsDialog(
    onDismiss: () -> Unit,
    onPickApps: () -> Unit,
) {
    ShadowDialog(
        onDismissRequest = onDismiss,
        title = "Pick apps first",
        message = "App routing is set to Only selected apps, but no apps are picked. " +
            "Pick at least one, or switch App routing to All apps.",
        confirmLabel = "Pick apps",
        onConfirm = onPickApps,
        dismissLabel = "Not now",
    )
}
