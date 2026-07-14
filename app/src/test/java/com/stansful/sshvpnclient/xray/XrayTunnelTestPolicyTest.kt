package com.stansful.sshvpnclient.xray

import com.stansful.sshvpnclient.domain.model.ProxyTestStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XrayTunnelTestPolicyTest {
    @Test
    fun `latency below five seconds is available`() {
        val result = classifyTunnelTestLatency("fast", 4_999L)

        assertEquals("fast", result.profileId)
        assertEquals(ProxyTestStatus.AVAILABLE, result.status)
        assertEquals(4_999L, result.latencyMs)
        assertNull(result.message)
    }

    @Test
    fun `latency at five seconds is unavailable`() {
        val result = classifyTunnelTestLatency("boundary", 5_000L)

        assertEquals("boundary", result.profileId)
        assertEquals(ProxyTestStatus.UNAVAILABLE, result.status)
        assertNull(result.latencyMs)
        assertEquals("Tunnel response took 5000ms; limit is under 5000ms", result.message)
    }

    @Test
    fun `latency above five seconds is unavailable`() {
        val result = classifyTunnelTestLatency("slow", 5_001L)

        assertEquals("slow", result.profileId)
        assertEquals(ProxyTestStatus.UNAVAILABLE, result.status)
        assertNull(result.latencyMs)
        assertEquals("Tunnel response took 5001ms; limit is under 5000ms", result.message)
    }

    @Test
    fun `effective latency includes core startup and rounds wall time up`() {
        assertEquals(4_999L, effectiveTunnelTestLatency(500L, 4_999_000_000L))
        assertEquals(5_000L, effectiveTunnelTestLatency(500L, 4_999_000_001L))
        assertEquals(5_100L, effectiveTunnelTestLatency(5_100L, 500_000_000L))
    }
}
