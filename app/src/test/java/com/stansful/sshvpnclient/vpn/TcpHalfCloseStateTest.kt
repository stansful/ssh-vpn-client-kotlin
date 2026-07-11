package com.stansful.sshvpnclient.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TcpHalfCloseStateTest {
    @Test
    fun `remote EOF keeps client upload open until client FIN is drained`() {
        val state = TcpHalfCloseState()

        state.onRemoteFinSent()
        state.onRemoteFinAcknowledged()

        assertTrue(state.acceptsClientData)
        assertFalse(state.canClose)

        state.onClientFinAccepted()
        assertFalse(state.acceptsClientData)
        assertFalse(state.canClose)

        state.onClientOutputClosed()
        assertTrue(state.canClose)
    }

    @Test
    fun `client EOF waits for remote FIN and its acknowledgement`() {
        val state = TcpHalfCloseState()

        state.onClientFinAccepted()
        state.onClientOutputClosed()
        assertFalse(state.canClose)

        state.onRemoteFinSent()
        assertFalse(state.canClose)

        state.onRemoteFinAcknowledged()
        assertTrue(state.canClose)
    }

    @Test
    fun `out of order close notifications cannot complete handshake`() {
        val state = TcpHalfCloseState()

        state.onRemoteFinAcknowledged()
        state.onClientOutputClosed()
        state.onRemoteFinSent()
        state.onClientFinAccepted()

        assertFalse(state.canClose)
    }

    @Test
    fun `remote FIN cleanup waits for a full idle interval`() {
        assertEquals(25_000L, remainingIdleCleanupDelayMs(idleForMs = 5_000L, timeoutMs = 30_000L))
        assertEquals(0L, remainingIdleCleanupDelayMs(idleForMs = 30_000L, timeoutMs = 30_000L))
        assertEquals(0L, remainingIdleCleanupDelayMs(idleForMs = 45_000L, timeoutMs = 30_000L))
    }

    @Test
    fun `client FIN cleanup is extended by half-close activity but remains bounded after idle`() {
        assertEquals(
            55_000L,
            remainingClientFinCleanupDelayMs(
                nowMs = 70_000L,
                clientFinReceivedAtMs = 10_000L,
                lastActivityAtMs = 65_000L,
                timeoutMs = 60_000L,
            ),
        )
        assertEquals(
            0L,
            remainingClientFinCleanupDelayMs(
                nowMs = 125_000L,
                clientFinReceivedAtMs = 10_000L,
                lastActivityAtMs = 65_000L,
                timeoutMs = 60_000L,
            ),
        )
    }

    @Test
    fun `client FIN timestamp is the cleanup floor when prior activity is older`() {
        assertEquals(
            50_000L,
            remainingClientFinCleanupDelayMs(
                nowMs = 20_000L,
                clientFinReceivedAtMs = 10_000L,
                lastActivityAtMs = 5_000L,
                timeoutMs = 60_000L,
            ),
        )
    }
}
