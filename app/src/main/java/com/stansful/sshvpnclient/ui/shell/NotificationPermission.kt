package com.stansful.sshvpnclient.ui.shell

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Android 13+ keeps the VPN session notifications (status, Disconnect / Stop) out of the shade until
 * the app holds POST_NOTIFICATIONS. Obtain with [rememberNotificationPermissionPrompt].
 */
@Stable
internal class NotificationPermissionPrompt internal constructor(
    private val onRequest: () -> Unit,
) {
    /**
     * Shows Android's notification permission dialog when [shouldRequestNotificationPermission] says
     * so. Fire-and-forget: the caller has already started its VPN, and a denial changes nothing.
     */
    fun requestOnce() = onRequest()
}

@Composable
internal fun rememberNotificationPermissionPrompt(): NotificationPermissionPrompt {
    val context = LocalContext.current
    val settingsRepository = LocalAppContainer.current.appSettingsRepository
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
        // Granted or denied, the VPN keeps running; only its notification's visibility differs.
    }
    return remember(context, settingsRepository) {
        NotificationPermissionPrompt(
            onRequest = request@{
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@request
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
                val ask = shouldRequestNotificationPermission(
                    sdkInt = Build.VERSION.SDK_INT,
                    granted = granted,
                    alreadyRequested = settingsRepository.settings.value.notificationPermissionRequested,
                )
                if (!ask) return@request
                // Stored before the dialog: a process death while it is open must not ask again.
                settingsRepository.setNotificationPermissionRequested(true)
                try {
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } catch (_: ActivityNotFoundException) {
                    // No permission UI on this build: nothing to ask, the VPN works anyway.
                }
            },
        )
    }
}

/**
 * Ask for POST_NOTIFICATIONS when a VPN is started from the app: only on Android 13+ (older versions
 * grant it at install), only while it is not granted, and at most once per install, so a denial is
 * final (Settings can still turn notifications on).
 */
internal fun shouldRequestNotificationPermission(
    sdkInt: Int,
    granted: Boolean,
    alreadyRequested: Boolean,
): Boolean = sdkInt >= Build.VERSION_CODES.TIRAMISU && !granted && !alreadyRequested
