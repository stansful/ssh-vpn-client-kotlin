package com.stansful.sshvpnclient.vpn

import android.net.TrafficStats
import android.os.Process
import android.os.SystemClock

/**
 * Samples aggregate counters only when the existing connection monitor wakes up; it does not
 * poll sockets or keep the CPU awake.
 *
 * Android may reattribute bytes forwarded by a VPN from the VPN process UID back to the browser
 * UID. Consequently getUidRxBytes(myUid) can stay almost flat during a large browser download.
 * Device totals are retained as a conservative fallback: they are sampled only around a proposed
 * destructive rebuild/health probe, where preserving an active transfer is safer than tearing it
 * down because an auxiliary endpoint was filtered.
 */
internal class VpnTrafficActivityMonitor(
    private val countersProvider: () -> VpnTrafficCounters = ::currentVpnTrafficCounters,
    private val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
) {
    private val lock = Any()
    private var previousCounters = countersProvider().normalized()
    private var lastMeaningfulUidReceiveAtMs = NO_TRAFFIC_TIMESTAMP
    private var lastMeaningfulDeviceReceiveAtMs = NO_TRAFFIC_TIMESTAMP

    fun resetBaseline() {
        synchronized(lock) {
            previousCounters = countersProvider().normalized()
            lastMeaningfulUidReceiveAtMs = NO_TRAFFIC_TIMESTAMP
            lastMeaningfulDeviceReceiveAtMs = NO_TRAFFIC_TIMESTAMP
        }
    }

    fun sampleSinceLast(): VpnTrafficDelta = synchronized(lock) {
        val next = countersProvider().normalized()
        val previous = previousCounters
        previousCounters = next
        val uidReceivedBytes = monotonicDelta(next.receivedBytes, previous.receivedBytes)
        val uidTransmittedBytes = monotonicDelta(next.transmittedBytes, previous.transmittedBytes)
        val deviceReceivedBytes = monotonicDelta(
            next.deviceReceivedBytes,
            previous.deviceReceivedBytes,
        )
        val deviceTransmittedBytes = monotonicDelta(
            next.deviceTransmittedBytes,
            previous.deviceTransmittedBytes,
        )
        val effectiveReceivedBytes = maxOf(uidReceivedBytes, deviceReceivedBytes)
        val effectiveTransmittedBytes = maxOf(uidTransmittedBytes, deviceTransmittedBytes)
        val nowMs = elapsedRealtimeMs()
        if (uidReceivedBytes >= ACTIVE_VPN_TRAFFIC_THRESHOLD_BYTES) {
            lastMeaningfulUidReceiveAtMs = nowMs
        }
        if (deviceReceivedBytes >= ACTIVE_VPN_TRAFFIC_THRESHOLD_BYTES) {
            lastMeaningfulDeviceReceiveAtMs = nowMs
        }
        val uidReceivedRecently = lastMeaningfulUidReceiveAtMs != NO_TRAFFIC_TIMESTAMP &&
            nowMs - lastMeaningfulUidReceiveAtMs <= RECENT_VPN_RECEIVE_GRACE_MS
        val deviceReceivedRecently = lastMeaningfulDeviceReceiveAtMs != NO_TRAFFIC_TIMESTAMP &&
            nowMs - lastMeaningfulDeviceReceiveAtMs <= RECENT_VPN_RECEIVE_GRACE_MS
        VpnTrafficDelta(
            receivedBytes = effectiveReceivedBytes,
            transmittedBytes = effectiveTransmittedBytes,
            uidReceivedBytes = uidReceivedBytes,
            uidTransmittedBytes = uidTransmittedBytes,
            deviceReceivedBytes = deviceReceivedBytes,
            deviceTransmittedBytes = deviceTransmittedBytes,
            uidReceivedRecently = uidReceivedRecently,
            deviceReceivedRecently = deviceReceivedRecently,
            receivedRecently = uidReceivedRecently || deviceReceivedRecently,
        )
    }

    private fun VpnTrafficCounters.normalized() = VpnTrafficCounters(
        receivedBytes = receivedBytes.coerceAtLeast(0L),
        transmittedBytes = transmittedBytes.coerceAtLeast(0L),
        deviceReceivedBytes = deviceReceivedBytes.coerceAtLeast(0L),
        deviceTransmittedBytes = deviceTransmittedBytes.coerceAtLeast(0L),
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
    val uidReceiveIsActive = traffic.uidReceivedRecently ||
        traffic.uidReceivedBytes >= ACTIVE_VPN_TRAFFIC_THRESHOLD_BYTES
    val deviceReceiveIsActive = traffic.deviceReceivedRecently ||
        traffic.deviceReceivedBytes >= ACTIVE_VPN_TRAFFIC_THRESHOLD_BYTES
    return uidReceiveIsActive ||
        (deviceReceiveIsActive &&
            elapsedSinceLastForcedCheckMs < MAX_DEVICE_ONLY_RX_DEFERRAL_MS) ||
        (traffic.transmittedBytes >= ACTIVE_VPN_TRAFFIC_THRESHOLD_BYTES &&
            elapsedSinceLastForcedCheckMs < MAX_TX_ONLY_TRAFFIC_CHECK_DEFERRAL_MS)
}

internal data class VpnTrafficCounters(
    val receivedBytes: Long,
    val transmittedBytes: Long,
    val deviceReceivedBytes: Long = receivedBytes,
    val deviceTransmittedBytes: Long = transmittedBytes,
)

internal data class VpnTrafficDelta(
    val receivedBytes: Long,
    val transmittedBytes: Long,
    val uidReceivedBytes: Long = receivedBytes,
    val uidTransmittedBytes: Long = transmittedBytes,
    val deviceReceivedBytes: Long = receivedBytes,
    val deviceTransmittedBytes: Long = transmittedBytes,
    val uidReceivedRecently: Boolean = uidReceivedBytes >= ACTIVE_VPN_TRAFFIC_THRESHOLD_BYTES,
    val deviceReceivedRecently: Boolean = deviceReceivedBytes >= ACTIVE_VPN_TRAFFIC_THRESHOLD_BYTES,
    val receivedRecently: Boolean = uidReceivedRecently || deviceReceivedRecently,
) {
    val totalBytes: Long
        get() = receivedBytes + transmittedBytes
}

private fun currentVpnTrafficCounters(): VpnTrafficCounters {
    val uid = Process.myUid()
    return VpnTrafficCounters(
        receivedBytes = TrafficStats.getUidRxBytes(uid),
        transmittedBytes = TrafficStats.getUidTxBytes(uid),
        deviceReceivedBytes = TrafficStats.getTotalRxBytes(),
        deviceTransmittedBytes = TrafficStats.getTotalTxBytes(),
    )
}

internal const val ACTIVE_VPN_TRAFFIC_THRESHOLD_BYTES = 64L * 1_024L
internal const val RECENT_VPN_RECEIVE_GRACE_MS = 45_000L
internal const val MAX_DEVICE_ONLY_RX_DEFERRAL_MS = 15L * 60_000L
internal const val MAX_TX_ONLY_TRAFFIC_CHECK_DEFERRAL_MS = 5L * 60_000L
private const val NO_TRAFFIC_TIMESTAMP = Long.MIN_VALUE
