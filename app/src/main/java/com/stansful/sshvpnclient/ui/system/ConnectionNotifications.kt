package com.stansful.sshvpnclient.ui.system

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import com.stansful.sshvpnclient.MainActivity
import com.stansful.sshvpnclient.R
import com.stansful.sshvpnclient.domain.model.VpnConnectionStatus
import com.stansful.sshvpnclient.ui.theme.NightColors

/** Headline and detail line of a VPN notification; [connected] tints the header mint, else amber. */
internal data class NotificationCopy(
    val title: String,
    val text: String,
    val connected: Boolean,
)

/** The notification's single action: "Disconnect" (Server, Routes) or "Stop" (Auto). */
internal class NotificationAction(
    @param:StringRes val label: Int,
    val serviceIntent: Intent,
    val requestCode: Int,
)

/** What Auto's notification says, one value per phase of the Auto session. */
internal sealed interface AutoNotice {
    data object Starting : AutoNotice

    /** Android restarted the service without (or with an altered) start command. */
    data object Restoring : AutoNotice

    data object WaitingForNetwork : AutoNotice

    /** A pass over the public list: refresh, test, prune, pick. */
    data object Searching : AutoNotice

    /** The live route failed its checks and is being replaced. */
    data object Switching : AutoNotice

    data class Connecting(val routeName: String) : AutoNotice

    data class Connected(val routeName: String, val liveCheckMs: Long) : AutoNotice

    data class Retrying(val retryDelayMs: Long) : AutoNotice
}

/** Creates (or renames) a low-importance channel: no sound, no heads-up. */
internal fun Context.ensureConnectionChannel(channelId: String, @StringRes name: Int) {
    getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel(channelId, getString(name), NotificationManager.IMPORTANCE_LOW),
    )
}

/**
 * Foreground notification of a VPN service (System.dc.html, column A): "shadow · [mode]" header with
 * the brand shield, a state headline, one detail line and one action. A tap opens the app on top of
 * its current screen.
 */
internal fun Context.connectionNotification(
    channelId: String,
    @StringRes mode: Int,
    copy: NotificationCopy,
    action: NotificationAction,
): Notification {
    val openApp = PendingIntent.getActivity(
        this,
        OPEN_APP_REQUEST_CODE,
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val actionIntent = PendingIntent.getService(
        this,
        action.requestCode,
        action.serviceIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    return NotificationCompat.Builder(this, channelId)
        .setSmallIcon(R.drawable.ic_shadow_shield)
        // Android adjusts the accent for contrast on light and dark shades.
        .setColor((if (copy.connected) NightColors.mint else NightColors.amber).toArgb())
        .setSubText(getString(mode))
        .setContentTitle(copy.title)
        .setContentText(copy.text)
        .setContentIntent(openApp)
        .addAction(0, getString(action.label), actionIntent)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()
}

/** Server mode: "Connected to Home VPS" etc.; a missing [serverName] reads "your server". */
internal fun Context.serverNotificationCopy(
    status: VpnConnectionStatus?,
    serverName: String?,
): NotificationCopy {
    val name = serverName ?: getString(R.string.notification_server_fallback)
    return when (status) {
        VpnConnectionStatus.CONNECTED -> NotificationCopy(
            title = getString(R.string.notification_server_connected, name),
            text = getString(R.string.notification_text_tap_to_open),
            connected = true,
        )
        // The SSH service keeps the tunnel interface up while it reconnects ("hot" reconnect).
        VpnConnectionStatus.RECONNECTING -> NotificationCopy(
            title = getString(R.string.notification_server_reconnecting, name),
            text = getString(R.string.notification_text_apps_stay_on_vpn),
            connected = false,
        )
        else -> NotificationCopy(
            title = getString(R.string.notification_server_connecting, name),
            text = getString(R.string.notification_text_not_protected),
            connected = false,
        )
    }
}

/** Routes mode: "Connected via Frankfurt · DE 11" etc.; a missing [routeName] reads "your route". */
internal fun Context.routeNotificationCopy(
    status: VpnConnectionStatus?,
    routeName: String?,
): NotificationCopy {
    val name = routeName ?: getString(R.string.notification_route_fallback)
    return when (status) {
        VpnConnectionStatus.CONNECTED -> NotificationCopy(
            title = getString(R.string.notification_route_connected, name),
            text = getString(R.string.notification_text_tap_to_open),
            connected = true,
        )
        // Xray closes the tunnel interface between reconnect attempts.
        VpnConnectionStatus.RECONNECTING -> NotificationCopy(
            title = getString(R.string.notification_route_reconnecting, name),
            text = getString(R.string.notification_text_not_protected),
            connected = false,
        )
        else -> NotificationCopy(
            title = getString(R.string.notification_route_starting, name),
            text = getString(R.string.notification_text_not_protected),
            connected = false,
        )
    }
}

/** Auto mode, by phase: "Connected via Amsterdam · NL 03" + "Live check 92 ms", "No working route yet" … */
internal fun Context.autoNotificationCopy(notice: AutoNotice): NotificationCopy {
    val notProtected = getString(R.string.notification_text_not_protected)
    return when (notice) {
        AutoNotice.Starting -> NotificationCopy(getString(R.string.notification_auto_starting), notProtected, false)
        AutoNotice.Restoring -> NotificationCopy(getString(R.string.notification_auto_restoring), notProtected, false)
        AutoNotice.WaitingForNetwork -> NotificationCopy(
            title = getString(R.string.notification_auto_waiting_network),
            text = getString(R.string.notification_auto_waiting_network_text),
            connected = false,
        )
        AutoNotice.Searching -> NotificationCopy(getString(R.string.notification_auto_searching), notProtected, false)
        AutoNotice.Switching -> NotificationCopy(
            title = getString(R.string.notification_auto_switching),
            text = getString(R.string.notification_auto_switching_text),
            connected = false,
        )
        is AutoNotice.Connecting -> NotificationCopy(
            title = getString(R.string.notification_auto_connecting, notice.routeName),
            text = notProtected,
            connected = false,
        )
        is AutoNotice.Connected -> NotificationCopy(
            title = getString(R.string.notification_auto_connected, notice.routeName),
            text = getString(R.string.notification_auto_live_check, notice.liveCheckMs),
            connected = true,
        )
        is AutoNotice.Retrying -> NotificationCopy(
            title = getString(R.string.notification_auto_no_route),
            // Whole seconds, rounded up: the app never shows "0 s" while a wait is pending.
            text = getString(
                R.string.notification_auto_next_pass,
                (notice.retryDelayMs + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND,
            ),
            connected = false,
        )
    }
}

private const val OPEN_APP_REQUEST_CODE = 3000
private const val MILLIS_PER_SECOND = 1_000L
