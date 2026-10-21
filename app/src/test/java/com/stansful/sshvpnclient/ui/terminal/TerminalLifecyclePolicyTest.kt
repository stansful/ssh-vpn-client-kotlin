package com.stansful.sshvpnclient.ui.terminal

import com.stansful.sshvpnclient.ui.main.TerminalUiState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalLifecyclePolicyTest {
    @Test
    fun `leaving the screen or going to the background closes the shell`() {
        assertTrue(closesTerminalOnStop(changingConfigurations = false))
    }

    @Test
    fun `a configuration change keeps the shell for the recreated screen`() {
        assertFalse(closesTerminalOnStop(changingConfigurations = true))
    }

    @Test
    fun `a fresh terminal opens by itself`() {
        // Also what leaving the screen or going to the background leaves behind (closeTerminal).
        assertTrue(opensTerminalAutomatically(TerminalUiState()))
    }

    @Test
    fun `an ended shell keeps its output on screen instead of reopening`() {
        val ended = TerminalUiState(output = "logout\n", outputRevision = 3L, errorMessage = "Shell closed")

        assertFalse(opensTerminalAutomatically(ended))
    }

    @Test
    fun `a shell that failed to open keeps its error until restart`() {
        assertFalse(opensTerminalAutomatically(TerminalUiState(errorMessage = "Channel refused")))
    }

    @Test
    fun `an open or opening shell is left to openTerminal, which ignores it`() {
        assertTrue(opensTerminalAutomatically(TerminalUiState(isOpen = true, errorMessage = "Write failed")))
        assertTrue(opensTerminalAutomatically(TerminalUiState(isConnecting = true)))
    }
}
