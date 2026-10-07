package com.stansful.sshvpnclient.ui.designsystem

import org.junit.Assert.assertEquals
import org.junit.Test

class LatencyLevelTest {
    @Test
    fun `buckets latency on the meter scale`() {
        assertEquals(LatencyLevel.Fast, latencyLevel(86))
        assertEquals(LatencyLevel.Fast, latencyLevel(200))
        assertEquals(LatencyLevel.Medium, latencyLevel(201))
        assertEquals(LatencyLevel.Medium, latencyLevel(500))
        assertEquals(LatencyLevel.Slow, latencyLevel(501))
        assertEquals(LatencyLevel.Unknown, latencyLevel(null))
        assertEquals(LatencyLevel.Unknown, latencyLevel(-1))
    }

    @Test
    fun `formats latency in mono copy`() {
        assertEquals("86 ms", formatLatency(86))
        assertEquals("—", formatLatency(null))
    }
}
