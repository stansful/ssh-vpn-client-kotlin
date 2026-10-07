package com.stansful.sshvpnclient.screenshots.tools

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.stansful.sshvpnclient.screenshots.ScreenSize
import com.stansful.sshvpnclient.screenshots.ScreenshotScope
import com.stansful.sshvpnclient.screenshots.ScreenshotTest
import com.stansful.sshvpnclient.screenshots.SystemBarsDp
import com.stansful.sshvpnclient.screenshots.renderScreenshot
import com.stansful.sshvpnclient.ui.terminal.TerminalPhase
import com.stansful.sshvpnclient.ui.terminal.TerminalScreen
import com.stansful.sshvpnclient.ui.terminal.TerminalScreenState
import com.stansful.sshvpnclient.ui.theme.DayColors
import com.stansful.sshvpnclient.ui.theme.NightColors
import com.stansful.sshvpnclient.ui.theme.ShadowColors
import org.junit.Test

class TerminalScreenshotTest : ScreenshotTest() {

    @Test
    fun active() {
        render("Terminal_Active_Night", activeState, command = "")
        render("Terminal_Active_Day", activeState, command = "", colors = DayColors)
        render("Terminal_Typing_Night", activeState, command = "journalctl -u ssh -n 20")
        render("Terminal_Active_Device_Night", activeState, command = "", systemBars = SystemBarsDp.Device)
        render("Terminal_Active_Tablet_Night", activeState, command = "", size = ScreenSize.TABLET)
    }

    @Test
    fun opening() {
        render("Terminal_Opening_Night", base.copy(phase = TerminalPhase.Opening), command = "upt")
        render("Terminal_Opening_Day", base.copy(phase = TerminalPhase.Opening), command = "", colors = DayColors)
    }

    @Test
    fun failed() {
        render(
            "Terminal_Failed_Night",
            base.copy(phase = TerminalPhase.Failed, errorMessage = "SSH terminal failed: timed out after 10 s"),
            command = "",
        )
    }

    @Test
    fun ended() {
        render(
            "Terminal_Ended_Night",
            endedState,
            command = "",
        )
        render("Terminal_Ended_Day", endedState, command = "", colors = DayColors)
    }

    @Test
    fun writeError() {
        // A command could not be written although the shell is still open: the error shows under the output.
        render("Terminal_WriteError_Night", activeState.copy(errorMessage = "Terminal is closed"), command = "ls")
    }

    @Test
    fun unavailable() {
        render("Terminal_NotConnected_Night", base.copy(phase = TerminalPhase.NotConnected), command = "")
        render(
            "Terminal_NotConnected_Day",
            base.copy(phase = TerminalPhase.NotConnected),
            command = "",
            colors = DayColors,
        )
        render("Terminal_Off_Night", base.copy(phase = TerminalPhase.Off), command = "")
        render("Terminal_Off_Day", base.copy(phase = TerminalPhase.Off), command = "", colors = DayColors)
    }

    @Test
    fun menu() {
        render(
            "Terminal_Menu_Night",
            activeState,
            command = "",
            interact = { onNodeWithContentDescription("More actions").performClick() },
        )
    }

    @Test
    fun copiedToast() {
        render(
            "Terminal_Toast_Night",
            activeState,
            command = "",
            interact = {
                onNodeWithContentDescription("More actions").performClick()
                advanceTimeBy(MENU_SETTLE_MS)
                onNodeWithText("Copy output").performClick()
            },
        )
    }

    private fun render(
        name: String,
        state: TerminalScreenState,
        command: String,
        colors: ShadowColors = NightColors,
        size: ScreenSize = ScreenSize.PHONE,
        systemBars: SystemBarsDp = SystemBarsDp.Artboard,
        interact: (ScreenshotScope.() -> Unit)? = null,
    ) {
        renderScreenshot(name = name, size = size, colors = colors, systemBars = systemBars, interact = interact) {
            Screen(state, command)
        }
    }

    @Composable
    private fun Screen(state: TerminalScreenState, command: String) {
        TerminalScreen(
            state = state,
            command = command,
            onCommandChange = {},
            onRun = {},
            onBack = {},
            onCopyOutput = {},
            onRestart = {},
            onOpenSettings = {},
            onViewActivity = {},
            onBackToHome = {},
        )
    }

    private companion object {
        const val MENU_SETTLE_MS = 600L

        val base = TerminalScreenState(
            phase = TerminalPhase.Active,
            serverName = "Home VPS",
            userAtHost = "root@vps.example.net",
        )

        /** What a PTY really sends: CRLF, an OSC window title and color codes around the prompt. */
        const val PROMPT = "\u001B]0;root@vps: ~\u0007\u001B[01;32mroot@vps\u001B[00m:\u001B[01;34m~\u001B[00m# "

        const val ACTIVE_OUTPUT =
            "Welcome to Ubuntu 24.04.1 LTS (GNU/Linux 6.8.0-45-generic x86_64)\r\n" +
                "Last login: Tue Oct  6 13:58:40 2026 from 192.0.2.10\r\n" +
                PROMPT + "uptime\r\n" +
                " 14:02:11 up 12 days,  3:41,  1 user,  load average: 0.08, 0.03, 0.01\r\n" +
                PROMPT + "df -h /\r\n" +
                "Filesystem      Size  Used Avail Use% Mounted on\r\n" +
                "/dev/vda1        38G   12G   25G  33% /\r\n" +
                PROMPT + "systemctl status ssh --no-pager\r\n" +
                "\u001B[0;1;32m●\u001B[0m ssh.service - OpenBSD Secure Shell server\r\n" +
                "     Loaded: loaded (/usr/lib/systemd/system/ssh.service; enabled; preset: enabled)\r\n" +
                "     Active: \u001B[0;1;32mactive (running)\u001B[0m " +
                "since Wed 2026-09-24 10:21:07 UTC; 12 days ago\r\n" +
                PROMPT

        val activeState = base.copy(output = ACTIVE_OUTPUT, outputRevision = 4L)

        val endedState = activeState.copy(
            phase = TerminalPhase.Ended,
            output = ACTIVE_OUTPUT + "exit\r\nlogout\r\n",
            errorMessage = "remote shell closed",
        )
    }
}
