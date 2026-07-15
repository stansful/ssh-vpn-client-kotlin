package com.stansful.sshvpnclient.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnTrafficActivityMonitorTest {
    @Test
    fun `monitor reports deltas and survives counter reset`() {
        var counters = VpnTrafficCounters(receivedBytes = 1_000L, transmittedBytes = 500L)
        var nowMs = 1_000L
        val monitor = VpnTrafficActivityMonitor(
            countersProvider = { counters },
            elapsedRealtimeMs = { nowMs },
        )

        counters = VpnTrafficCounters(receivedBytes = 70_000L, transmittedBytes = 800L)
        assertEquals(VpnTrafficDelta(69_000L, 300L), monitor.sampleSinceLast())
        nowMs += 1_000L
        counters = VpnTrafficCounters(receivedBytes = 10L, transmittedBytes = 900L)
        val afterReset = monitor.sampleSinceLast()
        assertEquals(0L, afterReset.receivedBytes)
        assertEquals(100L, afterReset.transmittedBytes)
        assertTrue(afterReset.receivedRecently)
        nowMs += RECENT_VPN_RECEIVE_GRACE_MS + 1L
        counters = VpnTrafficCounters(receivedBytes = 50L, transmittedBytes = 20L)
        assertEquals(VpnTrafficDelta(40L, 0L), monitor.sampleSinceLast())
    }

    @Test
    fun `device totals protect browser download when Android reattributes VPN uid traffic`() {
        var counters = VpnTrafficCounters(
            receivedBytes = 10_000L,
            transmittedBytes = 5_000L,
            deviceReceivedBytes = 1_000_000L,
            deviceTransmittedBytes = 500_000L,
        )
        val monitor = VpnTrafficActivityMonitor(
            countersProvider = { counters },
            elapsedRealtimeMs = { 10_000L },
        )
        counters = counters.copy(
            receivedBytes = 10_100L,
            transmittedBytes = 5_100L,
            deviceReceivedBytes = 151_000_000L,
            deviceTransmittedBytes = 5_500_000L,
        )

        val traffic = monitor.sampleSinceLast()

        assertEquals(100L, traffic.uidReceivedBytes)
        assertEquals(150_000_000L, traffic.deviceReceivedBytes)
        assertEquals(150_000_000L, traffic.receivedBytes)
        assertTrue(traffic.receivedRecently)
        assertTrue(
            shouldDeferVpnDisruption(
                traffic = traffic,
                elapsedSinceLastForcedCheckMs = MAX_DEVICE_ONLY_RX_DEFERRAL_MS - 1L,
            ),
        )
        assertFalse(
            shouldDeferVpnDisruption(
                traffic = traffic,
                elapsedSinceLastForcedCheckMs = MAX_DEVICE_ONLY_RX_DEFERRAL_MS,
            ),
        )
    }

    @Test
    fun `recent receive grace bridges one quiet health sampling window`() {
        var nowMs = 0L
        var counters = VpnTrafficCounters(receivedBytes = 0L, transmittedBytes = 0L)
        val monitor = VpnTrafficActivityMonitor(
            countersProvider = { counters },
            elapsedRealtimeMs = { nowMs },
        )
        counters = VpnTrafficCounters(receivedBytes = 1_000_000L, transmittedBytes = 0L)
        assertTrue(monitor.sampleSinceLast().receivedRecently)

        nowMs = RECENT_VPN_RECEIVE_GRACE_MS
        assertTrue(monitor.sampleSinceLast().receivedRecently)
        nowMs += 1L
        assertFalse(monitor.sampleSinceLast().receivedRecently)
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
