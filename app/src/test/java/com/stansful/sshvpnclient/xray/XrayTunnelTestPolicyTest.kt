package com.stansful.sshvpnclient.xray

import com.stansful.sshvpnclient.domain.model.ProxyTestStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XrayTunnelTestPolicyTest {
    @Test
    fun `latency below two seconds is available`() {
        val result = classifyTunnelTestLatency("fast", 1_999L)

        assertEquals("fast", result.profileId)
        assertEquals(ProxyTestStatus.AVAILABLE, result.status)
        assertEquals(1_999L, result.latencyMs)
        assertNull(result.message)
    }

    @Test
    fun `latency at two seconds is unavailable`() {
        val result = classifyTunnelTestLatency("boundary", 2_000L)

        assertEquals("boundary", result.profileId)
        assertEquals(ProxyTestStatus.UNAVAILABLE, result.status)
        assertNull(result.latencyMs)
        assertEquals("Tunnel response took 2000ms; limit is under 2000ms", result.message)
    }

    @Test
    fun `latency above two seconds is unavailable`() {
        val result = classifyTunnelTestLatency("slow", 2_001L)

        assertEquals("slow", result.profileId)
        assertEquals(ProxyTestStatus.UNAVAILABLE, result.status)
        assertNull(result.latencyMs)
        assertEquals("Tunnel response took 2001ms; limit is under 2000ms", result.message)
    }

    @Test
    fun `effective latency includes core startup and rounds wall time up`() {
        assertEquals(1_999L, effectiveTunnelTestLatency(500L, 1_999_000_000L))
        assertEquals(2_000L, effectiveTunnelTestLatency(500L, 1_999_000_001L))
        assertEquals(2_100L, effectiveTunnelTestLatency(2_100L, 500_000_000L))
    }
}
