package com.stansful.sshvpnclient.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class CoalescedActivityTimestampTest {
    @Test
    fun `hot packet activity updates shared timestamp at most once per interval`() {
        val timestamp = CoalescedActivityTimestamp(
            initialValueMs = 1_000L,
            minimumUpdateIntervalMs = 1_000L,
        )

        timestamp.mark(1_001L)
        timestamp.mark(1_999L)
        assertEquals(1_000L, timestamp.get())

        timestamp.mark(2_000L)
        assertEquals(2_000L, timestamp.get())

        timestamp.mark(1_500L)
        assertEquals(2_000L, timestamp.get())
    }
}
