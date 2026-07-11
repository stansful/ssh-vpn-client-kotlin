package com.stansful.sshvpnclient.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TunForwarderConfigTest {
    @Test
    fun `MSS is derived from configured IPv4 TUN MTU`() {
        assertEquals(1_460, TunForwarderConfig(tunMtu = 1_500).tcpMss)
        assertEquals(8_460, TunForwarderConfig(tunMtu = 8_500).tcpMss)
    }

    @Test
    fun `upload queue must fit a full advertised segment`() {
        assertThrows(IllegalArgumentException::class.java) {
            TunForwarderConfig(
                tunMtu = 8_500,
                maxPendingUploadBytesPerFlow = 8_459,
            )
        }
    }

    @Test
    fun `session pressure thresholds scale down on low RAM configuration`() {
        val normal = TunForwarderConfig(tunMtu = 8_500)
        val lowRam = TunForwarderConfig(tunMtu = 8_500, maxActiveTcpSessions = 32)

        assertEquals(128, normal.maxActiveTcpSessions)
        assertEquals(96, normal.sessionPressureThreshold)
        assertEquals(72, normal.sessionPressureTarget)
        assertEquals(24, lowRam.sessionPressureThreshold)
        assertEquals(18, lowRam.sessionPressureTarget)
    }

    @Test
    fun `coalesced upload object count is bounded by byte capacity`() {
        val normal = TunForwarderConfig(tunMtu = 8_500)
        val constrained = TunForwarderConfig(
            tunMtu = 8_500,
            maxPendingUploadBytesPerFlow = 128 * 1_024,
        )

        assertEquals(8, normal.maxPendingUploadChunksPerFlow)
        assertEquals(2, constrained.maxPendingUploadChunksPerFlow)
        assertEquals(8, TunForwarderConfig(tunMtu = 1_500).maxPendingUploadChunksPerFlow)
    }

    @Test
    fun `session cap cannot exceed reader worker hard limit`() {
        assertThrows(IllegalArgumentException::class.java) {
            TunForwarderConfig(tunMtu = 8_500, maxActiveTcpSessions = 129)
        }
    }
}
