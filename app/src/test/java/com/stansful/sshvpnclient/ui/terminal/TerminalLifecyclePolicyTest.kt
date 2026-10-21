package com.stansful.sshvpnclient.ui.terminal

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
}
