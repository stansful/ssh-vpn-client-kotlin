package com.stansful.sshvpnclient.screenshots.tools

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import com.stansful.sshvpnclient.screenshots.ScreenSize
import com.stansful.sshvpnclient.screenshots.ScreenshotScope
import com.stansful.sshvpnclient.screenshots.ScreenshotTest
import com.stansful.sshvpnclient.screenshots.SystemBarsDp
import com.stansful.sshvpnclient.screenshots.renderScreenshot
import com.stansful.sshvpnclient.ui.activity.ActivityLogTracker
import com.stansful.sshvpnclient.ui.activity.ActivityScreen
import com.stansful.sshvpnclient.ui.activity.ActivityScreenState
import com.stansful.sshvpnclient.ui.activity.ActivitySession
import com.stansful.sshvpnclient.ui.activity.SessionMode
import com.stansful.sshvpnclient.ui.designsystem.StatusTone
import com.stansful.sshvpnclient.ui.designsystem.ToasterState
import com.stansful.sshvpnclient.ui.theme.DayColors
import com.stansful.sshvpnclient.ui.theme.NightColors
import com.stansful.sshvpnclient.ui.theme.ShadowColors
import org.junit.Test

class ActivityScreenshotTest : ScreenshotTest() {

    @Test
    fun server() {
        render("Activity_Server_Night", serverState)
        render("Activity_Server_Day", serverState, colors = DayColors)
        render("Activity_Server_Device_Night", serverState, systemBars = SystemBarsDp.Device)
        render("Activity_Server_Tablet_Night", serverState, size = ScreenSize.TABLET)
    }

    @Test
    fun auto() {
        render("Activity_Auto_Night", autoState)
    }

    @Test
    fun idleLastSession() {
        render(
            "Activity_LastSession_Night",
            serverState.copy(
                live = false,
                session = ActivitySession(
                    mode = SessionMode.Server,
                    title = "Last session",
                    subtitle = "Server · started 14:02:11",
                    statusLabel = "Not connected",
                    statusTone = StatusTone.Neutral,
                ),
            ),
        )
    }

    @Test
    fun empty() {
        render(
            "Activity_Empty_Night",
            ActivityScreenState(
                lines = emptyList(),
                session = null,
                live = false,
                sshMode = true,
                serverName = null,
                canEditServer = false,
            ),
        )
        render(
            "Activity_Cleared_Night",
            serverState.copy(lines = emptyList(), cleared = true),
        )
    }

    @Test
    fun interactions() {
        render(
            "Activity_Scrolled_Night",
            serverState,
            interact = { onNode(hasScrollToIndexAction()).performScrollToIndex(SCROLLED_INDEX) },
        )
        render(
            "Activity_Problems_Night",
            serverState,
            interact = {
                onNodeWithText("Problems").performClick()
                onNode(hasScrollToIndexAction()).performScrollToIndex(PROBLEMS_INDEX)
            },
        )
        render(
            "Activity_LineSelected_Night",
            serverState,
            interact = {
                onNode(hasScrollToIndexAction()).performScrollToIndex(SELECTED_INDEX)
                onNodeWithText(RECONNECT_FAILED, substring = true).performClick()
            },
        )
        render(
            "Activity_End_Night",
            serverState,
            interact = { onNode(hasScrollToIndexAction()).performScrollToIndex(END_INDEX) },
        )
        render(
            "Activity_FilterEmpty_Night",
            autoState.copy(lines = ActivityLogTracker().update(autoLines.take(QUIET_AUTO_LINES))),
            interact = { onNodeWithText("Problems").performClick() },
        )
        render(
            "Activity_ClearDialog_Night",
            serverState,
            interact = { onNodeWithContentDescription("Clear activity").performClick() },
        )
        render(
            "Activity_ClearDialog_Day",
            serverState,
            colors = DayColors,
            interact = { onNodeWithContentDescription("Clear activity").performClick() },
        )
    }

    @Test
    fun copiedToast() {
        val toaster = ToasterState()
        renderScreenshot(
            name = "Activity_Toast_Night",
            toaster = toaster,
            interact = {
                runOnUiThread {
                    toaster.show(
                        "Copied ${serverLines.size} lines",
                        detail = "May contain IP addresses. Check before sharing.",
                    )
                }
            },
        ) {
            Screen(serverState)
        }
    }

    private fun render(
        name: String,
        state: ActivityScreenState,
        colors: ShadowColors = NightColors,
        size: ScreenSize = ScreenSize.PHONE,
        systemBars: SystemBarsDp = SystemBarsDp.Artboard,
        interact: (ScreenshotScope.() -> Unit)? = null,
    ) {
        renderScreenshot(name = name, size = size, colors = colors, systemBars = systemBars, interact = interact) {
            Screen(state)
        }
    }

    @Composable
    private fun Screen(state: ActivityScreenState) {
        ActivityScreen(
            state = state,
            onBack = {},
            onCopyAll = {},
            onClear = {},
            onCopyLine = {},
            onCopyFingerprint = {},
            onEditServer = {},
            onManageServers = {},
            onGoHome = {},
        )
    }

    private companion object {
        const val SCROLLED_INDEX = 8
        const val PROBLEMS_INDEX = 2
        const val SELECTED_INDEX = 20

        /** Session, filters, 2 highlight items, log head, top cap, 21 lines, live, bottom cap, privacy. */
        const val END_INDEX = 30

        /** The first three Auto lines have no warnings or errors. */
        const val QUIET_AUTO_LINES = 3
        const val RECONNECT_FAILED = "Reconnect failed: Connection timeout"

        /** Real diagnostics strings (as the SSH service writes them) of an "Office bastion" session. */
        val serverLines = listOf(
            "14:02:11 Starting VPN connection",
            "14:02:11 Selected config: deploy@198.51.100.10:2222",
            "14:02:11 Auth type: Private key",
            "14:02:11 Network diagnostics: active=Wi-Fi",
            "14:02:11 Network capabilities: internet, validated, not_vpn",
            "14:02:12 Opening SSH session to 198.51.100.10:2222",
            "14:02:12 Server host key fingerprint: SHA256:9f2cLqVb3rN0tWq8yZk1mHc5XeJ4uRa6sPd2oGiT7Hk",
            "14:02:12 WARNING: SSH host identity is not verified because no fingerprint is configured; " +
                "save the displayed fingerprint to enable pre-authentication verification",
            "14:02:13 SSH transport connected (SSH link #1)",
            "14:02:13 VPN app routing mode: proxy; all applications are routed through VPN",
            "14:02:14 Kotlin TUN forwarding engine started",
            "14:02:14 VPN connection is connected",
            "14:02:19 DNS over SSH: 192.168.1.1 did not answer (most likely a resolver on the phone's own " +
                "network, which the SSH server cannot reach); asking 1.1.1.1 instead; skipping it for 5 minutes",
            "14:02:41 TUN UDP relay: this SSH server reaches no TCP port of the Telegram VoIP hosts - neither 443 " +
                "nor the media port - so call media cannot be carried through it; the rest of Telegram is unaffected.",
            "14:03:14 SSH link #1: keepalive answered in ~138ms",
            "14:04:02 TUN TCP reset <destination>:443: the app stopped reading for 10s while data kept arriving",
            "14:05:30 TUN UDP: 12 unsupported flow(s) rejected in the last 60s; top ports: 443 (QUIC) x12",
            "14:07:40 Underlying network changed (Wi-Fi -> cellular); reconnecting SSH transport",
            "14:07:41 Reconnect failed: Connection timeout",
            "14:07:41 Reconnecting in 1000ms; press Disconnect to stop",
            "14:07:43 VPN forwarding restored in 2100ms without rebuilding Android VPN interface",
        )

        val autoLines = listOf(
            "14:02:11 Smart Connect source refreshed: Added 14, updated 3, duplicates 0, invalid 0, unsupported 2",
            "14:02:12 VPN app routing mode: proxy; all applications are routed through VPN",
            "14:02:31 Smart Connect verified Amsterdam · NL 03 through YouTube",
            "14:07:40 Smart Connect physical network changed (wifi -> cellular)",
            "14:07:40 Smart Connect physical network changed; restarting the current tunnel",
            "14:07:41 Smart Connect auxiliary health failure 1/3; keeping verified tunnel",
            "14:08:13 Smart Connect health probe postponed for active transfer (rx=512 KiB, uidRx=480 KiB); " +
                "forced check within 30s",
        )

        val serverState = ActivityScreenState(
            lines = ActivityLogTracker().update(serverLines),
            session = ActivitySession(
                mode = SessionMode.Server,
                title = "Office bastion",
                subtitle = "Server · started 14:02:11",
                statusLabel = "Connected",
                statusTone = StatusTone.Success,
            ),
            live = true,
            sshMode = true,
            serverName = "Office bastion",
            canEditServer = true,
        )

        val autoState = ActivityScreenState(
            lines = ActivityLogTracker().update(autoLines),
            session = ActivitySession(
                mode = SessionMode.Auto,
                title = "Amsterdam · NL 03",
                subtitle = "Auto · started 14:02:11",
                statusLabel = "Connected",
                statusTone = StatusTone.Success,
            ),
            live = true,
            sshMode = false,
            serverName = null,
            canEditServer = false,
        )
    }
}
