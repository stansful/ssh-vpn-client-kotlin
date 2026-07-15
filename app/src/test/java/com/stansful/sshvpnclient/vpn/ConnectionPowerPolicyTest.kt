package com.stansful.sshvpnclient.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionPowerPolicyTest {
    @Test
    fun `monitor cadence slows down while screen is off`() {
        val policy = ConnectionMonitorCadencePolicy(
            interactiveIntervalMs = 5_000L,
            screenOffIntervalMs = 30_000L,
        )

        assertEquals(5_000L, policy.intervalMs(isInteractive = true))
        assertEquals(30_000L, policy.intervalMs(isInteractive = false))
    }

    @Test
    fun `keepalive clamps unsafe extremes while honoring normal configured values`() {
        assertEquals(3, SSH_SERVER_ALIVE_COUNT_MAX)
        assertEquals(15, effectiveKeepAliveIntervalSec(1, isInteractive = true))
        assertEquals(45, effectiveKeepAliveIntervalSec(45, isInteractive = true))
        assertEquals(240, effectiveKeepAliveIntervalSec(240, isInteractive = true))
        assertEquals(300, effectiveKeepAliveIntervalSec(600, isInteractive = true))
    }

    @Test
    fun `screen off keepalive is at least two minutes and active value is restored`() {
        assertEquals(120, effectiveKeepAliveIntervalSec(30, isInteractive = false))
        assertEquals(180, effectiveKeepAliveIntervalSec(180, isInteractive = false))
        assertEquals(30, effectiveKeepAliveIntervalSec(30, isInteractive = true))
    }

    @Test
    fun `late callback for already bound network does not restart transport`() {
        assertEquals(false, shouldRestartForNetworkChange("wifi", "wifi"))
        assertEquals(true, shouldRestartForNetworkChange("cellular", "wifi"))
        assertEquals(true, shouldRestartForNetworkChange("wifi", null))
        assertEquals(false, shouldRestartForNetworkChange(null, "wifi"))
        assertEquals(false, shouldRestartForNetworkChange<String>(null, null))
    }

    @Test
    fun `backoff resets only after stable connection lifetime`() {
        assertEquals(false, shouldResetReconnectBackoff(29_999L, 30_000L))
        assertEquals(true, shouldResetReconnectBackoff(30_000L, 30_000L))
    }

    @Test
    fun `normal TUN profile keeps throughput oriented buffers`() {
        assertEquals(
            TunResourceProfile(
                maxActiveTcpSessions = 128,
                sshChannelWindowBytes = 4 * 1_024 * 1_024,
                maxPendingUploadBytesPerFlow = 512 * 1_024,
                tunWriteQueueCapacity = 256,
                outboundPacketPoolCapacity = 64,
            ),
            selectTunResourceProfile(isLowRamDevice = false, isPowerSaveMode = false),
        )
    }

    @Test
    fun `power saver preserves transport buffers while reducing session and retained pool caps`() {
        assertEquals(
            TunResourceProfile(
                maxActiveTcpSessions = 64,
                sshChannelWindowBytes = 4 * 1_024 * 1_024,
                maxPendingUploadBytesPerFlow = 512 * 1_024,
                tunWriteQueueCapacity = 256,
                outboundPacketPoolCapacity = 32,
            ),
            selectTunResourceProfile(isLowRamDevice = false, isPowerSaveMode = true),
        )
    }

    @Test
    fun `low RAM profile takes precedence over power saver`() {
        assertEquals(
            TunResourceProfile(
                maxActiveTcpSessions = 32,
                sshChannelWindowBytes = 4 * 1_024 * 1_024,
                maxPendingUploadBytesPerFlow = 512 * 1_024,
                tunWriteQueueCapacity = 256,
                outboundPacketPoolCapacity = 32,
            ),
            selectTunResourceProfile(isLowRamDevice = true, isPowerSaveMode = true),
        )
    }

    @Test
    fun `every TUN resource profile preserves the 100 Mbps high RTT datapath`() {
        val targetBandwidthDelayProductBytes = requiredBandwidthDelayProductBytes(
            targetMegabitsPerSecond = TARGET_THROUGHPUT_MBPS,
            roundTripTimeMs = HIGH_LATENCY_RTT_MS,
        )
        val sshTcpMss = VpnTunnelMode.SSH.mtu - IPV4_TCP_HEADER_BYTES
        val profiles = listOf(
            selectTunResourceProfile(isLowRamDevice = false, isPowerSaveMode = false),
            selectTunResourceProfile(isLowRamDevice = false, isPowerSaveMode = true),
            selectTunResourceProfile(isLowRamDevice = true, isPowerSaveMode = false),
            selectTunResourceProfile(isLowRamDevice = true, isPowerSaveMode = true),
        )

        assertEquals(1_325_000L, targetBandwidthDelayProductBytes)
        profiles.forEach { profile ->
            assertTrue(
                "SSH window must retain at least 2x BDP headroom for $profile",
                profile.sshChannelWindowBytes.toLong() >= 2L * targetBandwidthDelayProductBytes,
            )
            assertTrue(
                "TUN writer queue must absorb at least one target BDP for $profile",
                profile.tunWriteQueueCapacity.toLong() * sshTcpMss >= targetBandwidthDelayProductBytes,
            )
            assertEquals(512 * 1_024, profile.maxPendingUploadBytesPerFlow)
        }
    }

    private fun requiredBandwidthDelayProductBytes(
        targetMegabitsPerSecond: Int,
        roundTripTimeMs: Int,
    ): Long {
        require(targetMegabitsPerSecond > 0)
        require(roundTripTimeMs > 0)
        return Math.multiplyExact(
            Math.multiplyExact(targetMegabitsPerSecond.toLong(), roundTripTimeMs.toLong()),
            BYTES_PER_MEGABIT_MILLISECOND,
        )
    }

    private companion object {
        const val TARGET_THROUGHPUT_MBPS = 100
        const val HIGH_LATENCY_RTT_MS = 106
        const val IPV4_TCP_HEADER_BYTES = 40
        const val BYTES_PER_MEGABIT_MILLISECOND = 125L
    }
}
