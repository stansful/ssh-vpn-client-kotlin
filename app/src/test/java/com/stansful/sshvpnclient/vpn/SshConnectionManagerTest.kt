package com.stansful.sshvpnclient.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SshConnectionManagerTest {
    @Test
    fun `recognizes expected disconnect messages from JSch session thread`() {
        assertTrue(
            isExpectedJschDisconnectLog(
                "Caught an exception, leaving main loop due to Software caused connection abort",
            ),
        )
        assertTrue(
            isExpectedJschDisconnectLog(
                "Caught an exception, leaving main loop due to Connection reset\njava.net.SocketException",
            ),
        )
        assertTrue(
            isExpectedJschDisconnectLog(
                "Caught an exception, leaving main loop due to Socket closed",
            ),
        )
    }

    @Test
    fun `keeps unexpected JSch messages visible`() {
        assertFalse(isExpectedJschDisconnectLog("Authentication failed"))
        assertFalse(isExpectedJschDisconnectLog("channel is not opened"))
    }
}
