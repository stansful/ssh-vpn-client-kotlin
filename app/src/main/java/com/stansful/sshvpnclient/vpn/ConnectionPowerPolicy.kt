package com.stansful.sshvpnclient.vpn

internal class ConnectionMonitorCadencePolicy(
    private val interactiveIntervalMs: Long,
    private val screenOffIntervalMs: Long,
) {
    init {
        require(interactiveIntervalMs > 0L)
        require(screenOffIntervalMs >= interactiveIntervalMs)
    }

    fun intervalMs(isInteractive: Boolean): Long {
        return if (isInteractive) interactiveIntervalMs else screenOffIntervalMs
    }
}

internal fun effectiveKeepAliveIntervalSec(
    configuredIntervalSec: Int,
    isInteractive: Boolean,
): Int {
    val configured = configuredIntervalSec.coerceIn(
        MIN_KEEP_ALIVE_INTERVAL_SEC,
        MAX_KEEP_ALIVE_INTERVAL_SEC,
    )
    return if (isInteractive) {
        configured
    } else {
        configured.coerceAtLeast(SCREEN_OFF_KEEP_ALIVE_INTERVAL_SEC)
    }
}

internal fun <K> shouldRestartForNetworkChange(
    transportNetwork: K?,
    selectedNetwork: K?,
): Boolean = transportNetwork != null && transportNetwork != selectedNetwork

internal fun shouldResetReconnectBackoff(
    connectedDurationMs: Long,
    stableConnectionMs: Long,
): Boolean {
    require(stableConnectionMs > 0L)
    return connectedDurationMs >= stableConnectionMs
}

internal data class TunResourceProfile(
    val maxActiveTcpSessions: Int,
    val sshChannelWindowBytes: Int,
    val maxPendingUploadBytesPerFlow: Int,
    val tunWriteQueueCapacity: Int,
    val outboundPacketPoolCapacity: Int,
)

internal fun selectTunResourceProfile(
    isLowRamDevice: Boolean,
    isPowerSaveMode: Boolean,
): TunResourceProfile {
    return when {
        isLowRamDevice -> TunResourceProfile(
            maxActiveTcpSessions = 32,
            sshChannelWindowBytes = 4 * 1_024 * 1_024,
            maxPendingUploadBytesPerFlow = 512 * 1_024,
            tunWriteQueueCapacity = 256,
            outboundPacketPoolCapacity = 32,
        )
        isPowerSaveMode -> TunResourceProfile(
            maxActiveTcpSessions = 64,
            sshChannelWindowBytes = 4 * 1_024 * 1_024,
            maxPendingUploadBytesPerFlow = 512 * 1_024,
            tunWriteQueueCapacity = 256,
            outboundPacketPoolCapacity = 32,
        )
        else -> TunResourceProfile(
            maxActiveTcpSessions = 128,
            sshChannelWindowBytes = 4 * 1_024 * 1_024,
            maxPendingUploadBytesPerFlow = 512 * 1_024,
            tunWriteQueueCapacity = 256,
            outboundPacketPoolCapacity = 64,
        )
    }
}

internal const val MIN_KEEP_ALIVE_INTERVAL_SEC = 15
internal const val MAX_KEEP_ALIVE_INTERVAL_SEC = 300
internal const val SCREEN_OFF_KEEP_ALIVE_INTERVAL_SEC = 120
