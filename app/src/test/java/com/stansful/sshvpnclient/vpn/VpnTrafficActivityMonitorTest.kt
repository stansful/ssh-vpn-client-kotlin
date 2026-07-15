package com.stansful.sshvpnclient.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnTrafficActivityMonitorTest {
    @Test
    fun `monitor reports deltas and survives counter reset`() {
        var counters = VpnTrafficCounters(receivedBytes = 1_000L, transmittedBytes = 500L)
        val monitor = VpnTrafficActivityMonitor { counters }

        counters = VpnTrafficCounters(receivedBytes = 70_000L, transmittedBytes = 800L)
        assertEquals(VpnTrafficDelta(69_000L, 300L), monitor.sampleSinceLast())
        counters = VpnTrafficCounters(receivedBytes = 10L, transmittedBytes = 900L)
        assertEquals(VpnTrafficDelta(0L, 100L), monitor.sampleSinceLast())
        counters = VpnTrafficCounters(receivedBytes = 50L, transmittedBytes = 20L)
        assertEquals(VpnTrafficDelta(40L, 0L), monitor.sampleSinceLast())
    }

    @Test
    fun `active receive keeps a download alive beyond tx-only cap`() {
        assertTrue(
            shouldDeferVpnDisruption(
                VpnTrafficDelta(receivedBytes = 64L * 1_024L, transmittedBytes = 0L),
                elapsedSinceLastForcedCheckMs = 30L * 60_000L,
            ),
        )
        assertFalse(
            shouldDeferVpnDisruption(
                traffic = VpnTrafficDelta(receivedBytes = 64L * 1_024L - 1L, transmittedBytes = 0L),
                elapsedSinceLastForcedCheckMs = 30_000L,
            ),
        )
    }

    @Test
    fun `tx-only activity remains bounded because retransmits do not prove liveness`() {
        assertTrue(
            shouldDeferVpnDisruption(
                traffic = VpnTrafficDelta(receivedBytes = 0L, transmittedBytes = 64L * 1_024L),
                elapsedSinceLastForcedCheckMs = 30_000L,
            ),
        )
        assertFalse(
            shouldDeferVpnDisruption(
                traffic = VpnTrafficDelta(
                    receivedBytes = 0L,
                    transmittedBytes = 10L * 1_024L * 1_024L,
                ),
                elapsedSinceLastForcedCheckMs = 5L * 60_000L,
            ),
        )
    }
}
