package com.stansful.sshvpnclient.screenshots.tools

import com.stansful.sshvpnclient.domain.model.VpnConnectionState
import com.stansful.sshvpnclient.domain.model.VpnConnectionStatus
import com.stansful.sshvpnclient.domain.model.VpnSessionOwner
import com.stansful.sshvpnclient.ui.activity.ActivityFilter
import com.stansful.sshvpnclient.ui.activity.ActivityLogTracker
import com.stansful.sshvpnclient.ui.activity.HostKeyPin
import com.stansful.sshvpnclient.ui.activity.LogSeverity
import com.stansful.sshvpnclient.ui.activity.SessionMode
import com.stansful.sshvpnclient.ui.activity.activityHighlights
import com.stansful.sshvpnclient.ui.activity.activitySession
import com.stansful.sshvpnclient.ui.activity.evictedPrefix
import com.stansful.sshvpnclient.ui.activity.logSeverity
import com.stansful.sshvpnclient.ui.activity.parseActivityLine
import com.stansful.sshvpnclient.ui.activity.shortFingerprint
import com.stansful.sshvpnclient.ui.designsystem.StatusTone
import com.stansful.sshvpnclient.ui.main.TerminalUiState
import com.stansful.sshvpnclient.ui.terminal.AnsiTone
import com.stansful.sshvpnclient.ui.terminal.TerminalPhase
import com.stansful.sshvpnclient.ui.terminal.TerminalSpan
import com.stansful.sshvpnclient.ui.terminal.parseTerminalOutput
import com.stansful.sshvpnclient.ui.terminal.plainText
import com.stansful.sshvpnclient.ui.terminal.terminalErrorText
import com.stansful.sshvpnclient.ui.terminal.terminalPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure logic behind the Terminal and Activity screens. */
class ToolsParsingTest {

    @Test
    fun terminalOutputDropsEscapesAndSplitsPrompts() {
        val output = "\u001B]0;root@vps: ~\u0007\u001B[01;32mroot@vps\u001B[00m:\u001B[01;34m~\u001B[00m# uptime\r\n" +
            " 14:02:11 up 12 days\r\n" +
            "root@vps:~# "
        val lines = parseTerminalOutput(output)

        assertEquals(3, lines.size)
        assertEquals("root@vps:~# ", lines[0].prompt)
        assertEquals("uptime", lines[0].text)
        assertFalse(lines[1].isPrompt)
        assertEquals(" 14:02:11 up 12 days", lines[1].text)
        assertEquals("root@vps:~# ", lines[2].prompt)
        assertEquals("", lines[2].text)
        assertEquals("root@vps:~# uptime\n 14:02:11 up 12 days\nroot@vps:~#", lines.plainText())
    }

    @Test
    fun terminalOutputHandlesOtherPromptShapesAndControlCharacters() {
        assertEquals("[deploy@bastion ~]$ ", parseTerminalOutput("[deploy@bastion ~]$ ls")[0].prompt)
        assertEquals("vps:~# ", parseTerminalOutput("vps:~# ls")[0].prompt)
        val venv = "(venv) pi@raspberrypi:~/app $ "
        assertEquals(venv, parseTerminalOutput(venv + "ls")[0].prompt)
        assertFalse(parseTerminalOutput("# a comment in a config file")[0].isPrompt)
        assertEquals("20%", parseTerminalOutput("10%\r20%")[0].text)
        assertEquals("a\tb".replace("\t", "       "), parseTerminalOutput("a\tb")[0].text)
        assertEquals("", parseTerminalOutput("done\r\n").last().plain)
        assertTrue(parseTerminalOutput("").isEmpty())
    }

    @Test
    fun terminalOutputKeepsSgrColoursAndBold() {
        val line = parseTerminalOutput("\u001B[0;1;32m●\u001B[0m ssh.service · \u001B[31mfailed\u001B[39m ok")[0]

        assertEquals("● ssh.service · failed ok", line.plain)
        assertEquals(
            listOf(
                TerminalSpan(start = 0, end = 1, tone = AnsiTone.Green, bold = true),
                TerminalSpan(start = 16, end = 22, tone = AnsiTone.Red, bold = false),
            ),
            line.spans,
        )
        // 256-colour codes keep the 16 base colours; true colour and backgrounds are ignored.
        assertEquals(AnsiTone.Blue, parseTerminalOutput("\u001B[38;5;12mdir")[0].spans.single().tone)
        assertTrue(parseTerminalOutput("\u001B[38;2;1;2;3;48;5;4mx")[0].spans.isEmpty())
        // Plain output carries no spans.
        assertTrue(parseTerminalOutput("plain")[0].spans.isEmpty())
    }

    @Test
    fun terminalOutputErasesToTheEndOfTheLine() {
        assertEquals("100%", parseTerminalOutput("progress 10%\r\u001B[K100%")[0].plain)
        assertEquals("abX", parseTerminalOutput("abcdef\r\u001B[2Cx\u001B[K\bX")[0].plain)
        assertEquals("ok", parseTerminalOutput("\u001B[?2004hok\u001B[?2004l")[0].plain)
    }

    @Test
    fun terminalErrorsAreWordedLikeTheDesign() {
        assertEquals("Shell closed by the server", terminalErrorText("remote shell closed"))
        assertEquals(
            "Couldn’t open a shell: channel is not opened.",
            terminalErrorText("SSH terminal failed: channel is not opened."),
        )
        assertEquals("Socket closed", terminalErrorText("Socket closed"))
    }

    @Test
    fun terminalPhaseFollowsTheViewModelState() {
        assertEquals(TerminalPhase.NotConnected, terminalPhase(false, true, TerminalUiState()))
        assertEquals(TerminalPhase.Off, terminalPhase(true, false, TerminalUiState()))
        assertEquals(TerminalPhase.Opening, terminalPhase(true, true, TerminalUiState()))
        assertEquals(
            TerminalPhase.Opening,
            terminalPhase(true, true, TerminalUiState(isOpen = true, isConnecting = true)),
        )
        assertEquals(TerminalPhase.Active, terminalPhase(true, true, TerminalUiState(isOpen = true)))
        assertEquals(
            TerminalPhase.Failed,
            terminalPhase(true, true, TerminalUiState(errorMessage = "SSH terminal failed: timeout")),
        )
        assertEquals(
            TerminalPhase.Ended,
            terminalPhase(
                true,
                true,
                TerminalUiState(output = "logout\r\n", outputRevision = 3, errorMessage = "remote shell closed"),
            ),
        )
    }

    @Test
    fun severityHeuristics() {
        assertEquals(LogSeverity.Info, logSeverity("Starting VPN connection"))
        assertEquals(LogSeverity.Info, logSeverity("SSH auth method: Password; keepAlive=30s; connectTimeout=40000ms"))
        assertEquals(LogSeverity.Ok, logSeverity("SSH transport connected (SSH link #1)"))
        assertEquals(LogSeverity.Ok, logSeverity("SSH link #2: keepalive answered in ~142ms"))
        assertEquals(LogSeverity.Ok, logSeverity("TUN stall cleared after 812ms"))
        assertEquals(LogSeverity.Error, logSeverity("Reconnect failed: Connection timeout"))
        assertEquals(LogSeverity.Error, logSeverity("Fingerprint mismatch; authentication was not attempted"))
        assertEquals(LogSeverity.Error, logSeverity("SSH link #1: DEAD PROBE_TIMEOUT: no bytes from the server"))
        assertEquals(
            LogSeverity.Warning,
            logSeverity("Smart Connect auxiliary health failure 1/3; keeping verified tunnel"),
        )
        assertEquals(LogSeverity.Warning, logSeverity("Reconnecting in 1000ms; press Disconnect to stop"))
        assertEquals(LogSeverity.Warning, logSeverity("WARNING: SSH host identity is not verified"))
        assertEquals(
            LogSeverity.Info,
            logSeverity("TUN UDP: 12 unsupported flow(s) rejected in the last 60s; top ports: 443 (QUIC) x12"),
        )
        assertEquals(LogSeverity.Ok, logSeverity("Android stopped blocking this app's traffic"))
    }

    @Test
    fun linesSplitTimeAndGroups() {
        val line = parseActivityLine(7, "14:02:19 DNS over SSH: 192.168.1.1 did not answer; asking 1.1.1.1 instead")
        assertEquals("14:02:19", line.time)
        assertTrue(line.message.startsWith("DNS over SSH"))
        assertTrue(line.matches(ActivityFilter.Network, sshMode = true))
        assertTrue(line.matches(ActivityFilter.Mode, sshMode = true))
        assertTrue(line.matches(ActivityFilter.Problems, sshMode = true))
        assertFalse(line.matches(ActivityFilter.Mode, sshMode = false))
        assertTrue(parseActivityLine(1, "Smart Connect verified X through YouTube").matches(ActivityFilter.Mode, false))
        assertEquals("", parseActivityLine(2, "no timestamp").time)
    }

    @Test
    fun highlightsFindFingerprintPinStateAndTelegramVerdict() {
        val lines = listOf(
            "14:02:12 Server host key fingerprint: SHA256:9f2cLqVb3rN0tWq8yZk1mHc5XeJ4uRa6sPd2oGiT7Hk",
            "14:02:12 WARNING: SSH host identity is not verified because no fingerprint is configured",
            "14:02:41 TUN UDP relay: this SSH server reaches no TCP port of the Telegram VoIP hosts - neither 443",
        ).mapIndexed { index, raw -> parseActivityLine(index.toLong(), raw) }
        val highlights = activityHighlights(lines)

        assertEquals("SHA256:9f2cLqVb3rN0tWq8yZk1mHc5XeJ4uRa6sPd2oGiT7Hk", highlights.fingerprint?.fingerprint)
        assertEquals(HostKeyPin.NotPinned, highlights.fingerprint?.pin)
        assertTrue(highlights.telegramCallsBlocked)
        assertEquals("SHA256:9f2cLq…7Hk", shortFingerprint("SHA256:9f2cLqVb3rN0tWq8yZk1mHc5XeJ4uRa6sPd2oGiT7Hk"))
        assertTrue(activityHighlights(emptyList()).isEmpty)
    }

    @Test
    fun trackerKeepsKeysAcrossEvictionAndRenewsThemAfterAClear() {
        val tracker = ActivityLogTracker()
        val first = tracker.update(listOf("a", "b", "c"))
        val second = tracker.update(listOf("b", "c", "d"))
        assertEquals(first[1].key, second[0].key)
        assertEquals(first[2].key, second[1].key)
        assertTrue(second[2].key > first[2].key)
        val cleared = tracker.update(listOf("x"))
        assertTrue(cleared[0].key > second[2].key)
        assertEquals(0, evictedPrefix(listOf("a"), listOf("a", "a")))
        assertNull(evictedPrefix(listOf("a", "b"), listOf("c")))
    }

    @Test
    fun sessionCardDescribesActiveAndLastSessions() {
        val active = activitySession(
            vpnState = VpnConnectionState(status = VpnConnectionStatus.CONNECTED),
            owner = VpnSessionOwner.SHADOW_SSH,
            active = true,
            name = "Office bastion",
            startedAt = "14:02:11",
            hasLines = true,
        )
        assertEquals("Office bastion", active?.title)
        assertEquals("Server · started 14:02:11", active?.subtitle)
        assertEquals(StatusTone.Success, active?.statusTone)
        assertEquals(SessionMode.Server, active?.mode)

        val idle = activitySession(VpnConnectionState(), VpnSessionOwner.SMART_CONNECT, false, null, "09:00:00", true)
        assertEquals("Last session", idle?.title)
        assertEquals("Auto · started 09:00:00", idle?.subtitle)
        assertEquals("Not connected", idle?.statusLabel)
        assertNull(activitySession(VpnConnectionState(), null, false, null, null, false))
    }
}
