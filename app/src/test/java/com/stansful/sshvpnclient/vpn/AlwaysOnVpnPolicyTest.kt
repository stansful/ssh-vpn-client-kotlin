package com.stansful.sshvpnclient.vpn

import com.stansful.sshvpnclient.domain.model.VpnConnectionState
import com.stansful.sshvpnclient.domain.model.VpnConnectionStatus
import com.stansful.sshvpnclient.domain.model.VpnSessionOwner
import com.stansful.sshvpnclient.domain.model.VpnTransportType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlwaysOnVpnPolicyTest {
    @Test
    fun `android's start brings back the last recorded mode when nothing runs`() {
        VpnSessionOwner.entries.forEach { owner ->
            assertEquals(
                AlwaysOnStartDecision.Start(owner),
                resolveAlwaysOnStart(VpnConnectionState(), lastOwner = owner),
            )
        }
    }

    @Test
    fun `a failed earlier attempt does not block android's start`() {
        val failed = VpnConnectionState(
            status = VpnConnectionStatus.ERROR,
            activeConfigId = "server",
            errorMessage = "Authentication failed",
        )

        assertEquals(
            AlwaysOnStartDecision.Start(VpnSessionOwner.SHADOW_SSH),
            resolveAlwaysOnStart(failed, lastOwner = VpnSessionOwner.SHADOW_SSH),
        )
    }

    @Test
    fun `without a recorded mode android's start does not fall back to server like the tile`() {
        assertEquals(
            AlwaysOnStartDecision.NoRecordedMode,
            resolveAlwaysOnStart(VpnConnectionState(), lastOwner = null),
        )
        assertEquals(VpnSessionOwner.SHADOW_SSH, quickTileConnectTarget(null))
    }

    @Test
    fun `a starting running or stopping session is never restarted`() {
        listOf(
            VpnConnectionStatus.CONNECTING,
            VpnConnectionStatus.CONNECTED,
            VpnConnectionStatus.RECONNECTING,
            VpnConnectionStatus.DISCONNECTING,
        ).forEach { status ->
            VpnSessionOwner.entries.forEach { owner ->
                assertEquals(
                    "$owner $status",
                    AlwaysOnStartDecision.Ignore(owner, status),
                    resolveAlwaysOnStart(liveState(owner, status), lastOwner = owner),
                )
            }
        }
    }

    @Test
    fun `a session of another mode is not replaced by the recorded one`() {
        assertEquals(
            AlwaysOnStartDecision.Ignore(VpnSessionOwner.OPEN_SOURCE, VpnConnectionStatus.RECONNECTING),
            resolveAlwaysOnStart(
                liveState(VpnSessionOwner.OPEN_SOURCE, VpnConnectionStatus.RECONNECTING),
                lastOwner = VpnSessionOwner.SMART_CONNECT,
            ),
        )
    }

    @Test
    fun `a live status without an owner still counts as busy`() {
        assertEquals(
            AlwaysOnStartDecision.Ignore(owner = null, status = VpnConnectionStatus.DISCONNECTING),
            resolveAlwaysOnStart(
                VpnConnectionState(status = VpnConnectionStatus.DISCONNECTING),
                lastOwner = VpnSessionOwner.SHADOW_SSH,
            ),
        )
    }

    @Test
    fun `receiving service stops again when another mode runs or nothing does`() {
        VpnSessionOwner.entries.forEach { receiver ->
            assertTrue(
                "$receiver idle",
                mayStopAfterAlwaysOnStart(VpnConnectionState(), receiver, receiverRunActive = false),
            )
            VpnSessionOwner.entries.filter { it != receiver }.forEach { other ->
                assertTrue(
                    "$receiver while $other runs",
                    mayStopAfterAlwaysOnStart(
                        liveState(other, VpnConnectionStatus.CONNECTING),
                        receiver,
                        receiverRunActive = false,
                    ),
                )
            }
        }
    }

    @Test
    fun `receiving service never stops for android's start while it runs the session itself`() {
        VpnSessionOwner.entries.forEach { receiver ->
            listOf(
                VpnConnectionStatus.CONNECTING,
                VpnConnectionStatus.CONNECTED,
                VpnConnectionStatus.RECONNECTING,
                VpnConnectionStatus.DISCONNECTING,
            ).forEach { status ->
                assertFalse(
                    "$receiver $status",
                    mayStopAfterAlwaysOnStart(liveState(receiver, status), receiver, receiverRunActive = false),
                )
            }
        }
    }

    @Test
    fun `auto restoring before it owns the global state is not stopped`() {
        assertFalse(
            mayStopAfterAlwaysOnStart(
                VpnConnectionState(),
                VpnSessionOwner.SMART_CONNECT,
                receiverRunActive = true,
            ),
        )
    }

    @Test
    fun `a session ending after android's start it kept still stops its service`() {
        // Connect was start 1, Android's start 2 was kept: stopSelfResult(1) would be refused for good.
        assertEquals(2, sessionStopStartId(sessionStartId = 1, keptAlwaysOnStartId = 2))
    }

    @Test
    fun `a stop after android's start was kept uses its own newer start id`() {
        // Android's start 2 was kept, then the user's disconnect came as start 3.
        assertEquals(3, sessionStopStartId(sessionStartId = 3, keptAlwaysOnStartId = 2))
    }

    @Test
    fun `without a kept android start the session stops with its own start id`() {
        assertEquals(1, sessionStopStartId(sessionStartId = 1, keptAlwaysOnStartId = 0))
    }

    @Test
    fun `android's start has nothing to settle when no session end was refused`() {
        var stops = 0
        val settlement = RefusedSessionStop().settle {
            stops += 1
            true
        }

        assertEquals(RefusedStopSettlement.NONE, settlement)
        assertEquals(0, stops)
    }

    @Test
    fun `android's start settles a session end its queued start id refused`() {
        // Connect was start 2; Android's start 3 was queued when the connect failed: stopSelfResult(2)
        // refused, the error stayed unpublished. Start 3 is the newest now, so its stop goes through.
        val refused = RefusedSessionStop()
        var finished = 0
        refused.record(isCurrent = { true }, finish = { finished += 1 })

        assertEquals(RefusedStopSettlement.STOPPED, refused.settle { true })
        assertEquals(1, finished)
        // Settled once: a duplicate start of Android finds nothing left.
        assertEquals(RefusedStopSettlement.NONE, refused.settle { true })
        assertEquals(1, finished)
    }

    @Test
    fun `a command newer than android's start keeps the session end waiting`() {
        val refused = RefusedSessionStop()
        var finished = 0
        refused.record(isCurrent = { true }, finish = { finished += 1 })

        assertEquals(RefusedStopSettlement.STILL_REFUSED, refused.settle { false })
        assertEquals(0, finished)
        // That newer command was another start of Android: it settles the end.
        assertEquals(RefusedStopSettlement.STOPPED, refused.settle { true })
        assertEquals(1, finished)
    }

    @Test
    fun `a command of the app delivered since makes the refused end stale`() {
        val refused = RefusedSessionStop()
        var current = false
        var stops = 0
        var finished = 0
        refused.record(isCurrent = { current }, finish = { finished += 1 })

        val settlement = refused.settle {
            stops += 1
            true
        }

        assertEquals(RefusedStopSettlement.NONE, settlement)
        assertEquals(0, stops)
        assertEquals(0, finished)
        // Dropped for good: the command ids it checks only grow.
        current = true
        assertEquals(RefusedStopSettlement.NONE, refused.settle { true })
        assertEquals(0, finished)
    }

    @Test
    fun `a newer refused session end replaces the older one`() {
        val refused = RefusedSessionStop()
        val finished = mutableListOf<String>()
        refused.record(isCurrent = { true }, finish = { finished += "failed connect" })
        refused.record(isCurrent = { true }, finish = { finished += "disconnect" })

        assertEquals(RefusedStopSettlement.STOPPED, refused.settle { true })
        assertEquals(listOf("disconnect"), finished)
    }

    @Test
    fun `skipped start names the mode and what to fix for every reason`() {
        val texts = QuickTileOpenAppReason.entries.map { reason ->
            alwaysOnSkippedDiagnostic(VpnSessionOwner.OPEN_SOURCE, reason)
        }
        texts.forEach { text ->
            assertTrue(text, text.startsWith("Always-on VPN: Routes not started, "))
            assertTrue(text, text.endsWith("; open the app to fix it"))
        }
        assertEquals(texts.size, texts.toSet().size)
        assertEquals(
            "Always-on VPN: Server not started, no server selected; open the app to fix it",
            alwaysOnSkippedDiagnostic(VpnSessionOwner.SHADOW_SSH, QuickTileOpenAppReason.NO_SSH_CONFIG),
        )
        assertEquals(
            "Always-on VPN: Auto not started, the Xray engine is not installed; open the app to fix it",
            alwaysOnSkippedDiagnostic(VpnSessionOwner.SMART_CONNECT, QuickTileOpenAppReason.XRAY_CORE_MISSING),
        )
    }

    @Test
    fun `log lines name the mode the way the app does`() {
        assertEquals(
            "Always-on VPN: Android's start ignored, Auto is already reconnecting",
            alwaysOnIgnoredDiagnostic(
                AlwaysOnStartDecision.Ignore(VpnSessionOwner.SMART_CONNECT, VpnConnectionStatus.RECONNECTING),
            ),
        )
        assertEquals(
            "Always-on VPN: Android's start ignored, a session is already disconnecting",
            alwaysOnIgnoredDiagnostic(AlwaysOnStartDecision.Ignore(null, VpnConnectionStatus.DISCONNECTING)),
        )
        assertEquals(
            "Always-on VPN: Server started by Android",
            alwaysOnStartedDiagnostic(VpnSessionOwner.SHADOW_SSH),
        )
        assertEquals(
            "Always-on VPN: Routes failed to start: boom",
            alwaysOnFailedDiagnostic(VpnSessionOwner.OPEN_SOURCE, "boom"),
        )
        assertEquals(
            "Always-on VPN: Android's start ignored, Server had just ended",
            alwaysOnEndedSessionDiagnostic(VpnSessionOwner.SHADOW_SSH),
        )
    }

    private fun liveState(owner: VpnSessionOwner, status: VpnConnectionStatus) = VpnConnectionState(
        status = status,
        activeConfigId = "config",
        activeTransport = if (owner == VpnSessionOwner.SHADOW_SSH) VpnTransportType.SSH else VpnTransportType.XRAY,
        sessionOwner = owner,
    )
}
