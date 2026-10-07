package com.stansful.sshvpnclient.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TunnelCheckParsingTest {
    @Test
    fun `success line yields target and latency`() {
        val success = parseTunnelCheckSuccess("Tunnel check succeeded: 127.0.0.1:22 reachable through SSH in 84ms")

        assertEquals(TunnelCheckSuccess(target = "127.0.0.1:22", latencyMs = 84L), success)
    }

    @Test
    fun `other tunnel check lines are ignored`() {
        assertNull(parseTunnelCheckSuccess("Tunnel check: opening SSH direct TCP to 127.0.0.1:22 on the SSH server"))
        assertNull(parseTunnelCheckSuccess("Tunnel check: 127.0.0.1:22 failed: timeout"))
        assertNull(parseTunnelCheckSuccess("Tunnel check failed: timeout"))
    }
}
