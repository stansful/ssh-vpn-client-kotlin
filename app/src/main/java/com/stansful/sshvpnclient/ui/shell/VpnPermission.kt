package com.stansful.sshvpnclient.ui.shell

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.stansful.sshvpnclient.ui.system.VpnPermissionSheet

/** Asks Android for the VPN permission; obtain with [rememberVpnPermissionRequester]. */
@Stable
class VpnPermissionRequester internal constructor(
    private val onRequest: () -> Unit,
) {
    /**
     * Calls `onGranted` right away when the app already holds the VPN permission. Otherwise shows
     * the pre-prompt sheet; Continue opens Android's connection request and reports its result.
     */
    fun request() = onRequest()
}

/**
 * VPN permission flow: `VpnService.prepare` → (pre-prompt sheet → system dialog) → result.
 *
 * - [onGranted]: permission held or just granted — start the connection.
 * - [onDenied]: the system dialog was declined (or could not be shown) — callers show the recovery
 *   banner "VPN permission was denied. Tap Connect to ask again."
 * - [onCancelled]: "Not now" / Back / tap outside on the pre-prompt — nothing connects, no banner.
 *   Defaults to doing nothing; pass it when the caller must reset a pending start.
 */
@Composable
fun rememberVpnPermissionRequester(
    onGranted: () -> Unit,
    onDenied: () -> Unit,
    onCancelled: () -> Unit = {},
): VpnPermissionRequester {
    val context = LocalContext.current
    val currentOnGranted by rememberUpdatedState(onGranted)
    val currentOnDenied by rememberUpdatedState(onDenied)
    val currentOnCancelled by rememberUpdatedState(onCancelled)
    var showPrompt by rememberSaveable { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) currentOnGranted() else currentOnDenied()
    }

    if (showPrompt) {
        VpnPermissionSheet(
            onContinue = {
                showPrompt = false
                val intent = vpnPermissionIntent(context)
                if (intent == null) {
                    currentOnGranted()
                } else {
                    try {
                        launcher.launch(intent)
                    } catch (_: ActivityNotFoundException) {
                        currentOnDenied()
                    }
                }
            },
            onNotNow = {
                showPrompt = false
                currentOnCancelled()
            },
        )
    }

    return remember(context) {
        VpnPermissionRequester(
            onRequest = {
                if (vpnPermissionIntent(context) == null) currentOnGranted() else showPrompt = true
            },
        )
    }
}

private fun vpnPermissionIntent(context: Context) = VpnService.prepare(context)
