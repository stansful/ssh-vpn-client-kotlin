package com.stansful.sshvpnclient.vpn

import android.net.TrafficStats
import android.os.Process

/**
 * Samples only aggregate UID counters; it does not poll sockets or keep the CPU awake. Xray and
 * SSH outbound sockets run in this UID, so meaningful deltas prove that the VPN is still moving
 * user data even when an auxiliary health endpoint is temporarily unavailable.
 */
internal class VpnTrafficActivityMonitor(
    private val countersProvider: () -> VpnTrafficCounters = ::currentVpnUidTrafficCounters,
) {
    private val lock = Any()
    private var previousCounters = countersProvider().normalized()

    fun resetBaseline() {
        synchronized(lock) {
            previousCounters = countersProvider().normalized()
        }
    }

    fun sampleSinceLast(): VpnTrafficDelta = synchronized(lock) {
        val next = countersProvider().normalized()
        val previous = previousCounters
        previousCounters = next
        VpnTrafficDelta(
            receivedBytes = monotonicDelta(next.receivedBytes, previous.receivedBytes),
            transmittedBytes = monotonicDelta(next.transmittedBytes, previous.transmittedBytes),
        )
    }

    private fun VpnTrafficCounters.normalized() = VpnTrafficCounters(
        receivedBytes = receivedBytes.coerceAtLeast(0L),
        transmittedBytes = transmittedBytes.coerceAtLeast(0L),
    )

    private fun monotonicDelta(next: Long, previous: Long): Long {
        return if (next >= previous) next - previous else 0L
    }
}

internal fun shouldDeferVpnDisruption(
    traffic: VpnTrafficDelta,
    elapsedSinceLastForcedCheckMs: Long,
): Boolean {
    // Sustained RX is direct proof that the download path is alive. Never destroy that TCP flow
    // merely because the auxiliary YouTube endpoint is temporarily filtered. TX-only activity is
    // less conclusive (retransmits can continue after a dead path), so it retains a bounded cap.
    return traffic.receivedBytes >= ACTIVE_VPN_TRAFFIC_THRESHOLD_BYTES ||
        (traffic.transmittedBytes >= ACTIVE_VPN_TRAFFIC_THRESHOLD_BYTES &&
            elapsedSinceLastForcedCheckMs < MAX_TX_ONLY_TRAFFIC_CHECK_DEFERRAL_MS)
}

internal data class VpnTrafficCounters(
    val receivedBytes: Long,
    val transmittedBytes: Long,
)

internal data class VpnTrafficDelta(
    val receivedBytes: Long,
    val transmittedBytes: Long,
) {
    val totalBytes: Long
        get() = receivedBytes + transmittedBytes
}

private fun currentVpnUidTrafficCounters(): VpnTrafficCounters {
    val uid = Process.myUid()
    return VpnTrafficCounters(
        receivedBytes = TrafficStats.getUidRxBytes(uid),
        transmittedBytes = TrafficStats.getUidTxBytes(uid),
    )
}

internal const val ACTIVE_VPN_TRAFFIC_THRESHOLD_BYTES = 64L * 1_024L
internal const val MAX_TX_ONLY_TRAFFIC_CHECK_DEFERRAL_MS = 5L * 60_000L
