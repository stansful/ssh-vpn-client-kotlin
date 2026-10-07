package com.stansful.sshvpnclient.screenshots.system

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.core.app.ApplicationProvider
import com.stansful.sshvpnclient.MainActivity
import com.stansful.sshvpnclient.R
import com.stansful.sshvpnclient.domain.model.VpnConnectionStatus
import com.stansful.sshvpnclient.screenshots.ScreenSize
import com.stansful.sshvpnclient.screenshots.ScreenshotTest
import com.stansful.sshvpnclient.screenshots.SystemBarsDp
import com.stansful.sshvpnclient.screenshots.renderScreenshot
import com.stansful.sshvpnclient.ui.system.AutoNotice
import com.stansful.sshvpnclient.ui.system.NotificationAction
import com.stansful.sshvpnclient.ui.system.autoNotificationCopy
import com.stansful.sshvpnclient.ui.system.connectionNotification
import com.stansful.sshvpnclient.ui.system.routeNotificationCopy
import com.stansful.sshvpnclient.ui.system.serverNotificationCopy
import com.stansful.sshvpnclient.ui.theme.DayColors
import com.stansful.sshvpnclient.ui.theme.Shadow
import com.stansful.sshvpnclient.vpn.OpenSourceVpnService
import com.stansful.sshvpnclient.vpn.SmartConnectVpnService
import com.stansful.sshvpnclient.vpn.SshVpnService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSystem

/**
 * System.dc.html column A: the three VPN notifications, built by the services' helper and inflated
 * from Android's own (expanded) template, plus checks of their copy, action and tap target.
 */
class SystemNotificationsScreenshotTest : ScreenshotTest() {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun copyFollowsTheSession() {
        val serverConnected = context.serverNotificationCopy(VpnConnectionStatus.CONNECTED, "Home VPS")
        assertEquals("Connected to Home VPS", serverConnected.title)
        assertEquals("Tap to open", serverConnected.text)
        assertEquals(
            "Connecting to your server",
            context.serverNotificationCopy(status = null, serverName = null).title,
        )
        assertEquals(
            "Reconnecting to Home VPS",
            context.serverNotificationCopy(VpnConnectionStatus.RECONNECTING, "Home VPS").title,
        )
        assertEquals(
            "Connected via Frankfurt · DE 11",
            context.routeNotificationCopy(VpnConnectionStatus.CONNECTED, "Frankfurt · DE 11").title,
        )
        assertEquals("Starting your route", context.routeNotificationCopy(null, null).title)
        val routeReconnecting = context.routeNotificationCopy(VpnConnectionStatus.RECONNECTING, "Frankfurt · DE 11")
        assertEquals("Reconnecting via Frankfurt · DE 11", routeReconnecting.title)
        // Xray closes the tunnel between attempts, so the notification must not promise protection.
        assertEquals("Traffic is not protected yet", routeReconnecting.text)
        val autoConnected = context.autoNotificationCopy(AutoNotice.Connected("Amsterdam · NL 03", 92))
        assertEquals("Connected via Amsterdam · NL 03", autoConnected.title)
        assertEquals("Live check 92 ms", autoConnected.text)
        assertEquals("Next pass in 48 s", context.autoNotificationCopy(AutoNotice.Retrying(47_200)).text)
        assertEquals("Next pass in 1 s", context.autoNotificationCopy(AutoNotice.Retrying(200)).text)
    }

    @Test
    fun notificationOpensTheAppAndOffersOneAction() {
        val notification = serverNotification(VpnConnectionStatus.CONNECTED)
        assertEquals("Server", notification.extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
        assertEquals("Connected to Home VPS", notification.extras.getCharSequence(Notification.EXTRA_TITLE))
        assertEquals(R.drawable.ic_shadow_shield, notification.smallIcon.resId)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(1, notification.actions.size)
        assertEquals("Disconnect", notification.actions.single().title)
        val disconnect = shadowOf(notification.actions.single().actionIntent).savedIntent
        assertEquals(SshVpnService.disconnectIntent(context).action, disconnect.action)
        val open = shadowOf(notification.contentIntent).savedIntent
        assertEquals(MainActivity::class.java.name, open.component?.className)
        assertTrue(open.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
    }

    @Test
    fun notifications() {
        // The three cards of the artboard first, then the other phases.
        val notes = listOf(
            autoNotification(AutoNotice.Connected("Amsterdam · NL 03", 92)),
            serverNotification(VpnConnectionStatus.CONNECTED),
            autoNotification(AutoNotice.Retrying(48_000)),
            routeNotification(VpnConnectionStatus.CONNECTED),
            routeNotification(VpnConnectionStatus.RECONNECTING),
            serverNotification(VpnConnectionStatus.RECONNECTING),
            autoNotification(AutoNotice.Searching),
        )
        // Android draws the templates itself, in the light system theme here (recoverBuilder() builds
        // from the app's own configuration), so the stand-in shade is Day.
        renderScreenshot(
            name = "System_Notifications_Day",
            size = ScreenSize.wrap(420),
            colors = DayColors,
            systemBars = SystemBarsDp.None,
        ) {
            NotificationShade(notes)
        }
    }

    private fun serverNotification(status: VpnConnectionStatus) = context.connectionNotification(
        channelId = "ssh_vpn_connection",
        mode = R.string.mode_server,
        copy = context.serverNotificationCopy(status, "Home VPS"),
        action = NotificationAction(
            R.string.notification_action_disconnect,
            SshVpnService.disconnectIntent(context),
            requestCode = 3001,
        ),
    )

    private fun routeNotification(status: VpnConnectionStatus) = context.connectionNotification(
        channelId = "ssh_vpn_connection",
        mode = R.string.mode_routes,
        copy = context.routeNotificationCopy(status, "Frankfurt · DE 11"),
        action = NotificationAction(
            R.string.notification_action_disconnect,
            OpenSourceVpnService.disconnectIntent(context),
            requestCode = 3002,
        ),
    )

    private fun autoNotification(notice: AutoNotice) = context.connectionNotification(
        channelId = "smart_connect_vpn",
        mode = R.string.mode_auto,
        copy = context.autoNotificationCopy(notice),
        action = NotificationAction(
            R.string.notification_action_stop,
            SmartConnectVpnService.stopIntent(context),
            requestCode = 3003,
        ),
    )
}

@Composable
private fun NotificationShade(notes: List<Notification>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        notes.forEach { notification ->
            NotificationTemplate(
                notification = notification,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Shadow.colors.surface2),
            )
        }
    }
}

/**
 * Android's expanded notification template for [notification] (header, title, text, actions), posted
 * "now" on Robolectric's clock, as the artboard shows.
 */
// createBigContentView() is deprecated for apps building views; here it only previews the template.
@Suppress("DEPRECATION")
@Composable
private fun NotificationTemplate(notification: Notification, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { viewContext ->
            val builder = Notification.Builder.recoverBuilder(viewContext, notification)
                .setWhen(ShadowSystem.currentTimeMillis())
                .setStyle(Notification.BigTextStyle())
            builder.createBigContentView().apply(viewContext, FrameLayout(viewContext))
        },
    )
}
