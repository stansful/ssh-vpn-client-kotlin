package com.stansful.sshvpnclient.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class ReconnectBackoffTest {
    @Test
    fun `failure delays grow exponentially and stop at maximum`() {
        val backoff = ReconnectBackoff(initialDelayMs = 250L, maxDelayMs = 5_000L)

        val delays = List(9) { backoff.nextFailureDelayMs() }

        assertEquals(
            listOf(250L, 500L, 1_000L, 2_000L, 4_000L, 5_000L, 5_000L, 5_000L, 5_000L),
            delays,
        )
    }

    @Test
    fun `reset restores fast retry delay`() {
        val backoff = ReconnectBackoff(initialDelayMs = 250L, maxDelayMs = 5_000L)
        backoff.nextFailureDelayMs()
        backoff.nextFailureDelayMs()

        backoff.reset()

        assertEquals(250L, backoff.nextFailureDelayMs())
    }
}
