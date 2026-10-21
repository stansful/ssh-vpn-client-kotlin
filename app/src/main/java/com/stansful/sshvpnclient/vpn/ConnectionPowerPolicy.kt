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
    is64BitProcess: Boolean,
): TunResourceProfile {
    val profile = when {
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
            // A downlink burst that does not fit here blocks the flow that produced it, and with it
            // the single JSch thread that feeds every other flow, so the queue buys headroom.
            tunWriteQueueCapacity = 512,
            outboundPacketPoolCapacity = 128,
        )
    }
    return if (is64BitProcess) {
        profile
    } else {
        profile.copy(maxActiveTcpSessions = minOf(profile.maxActiveTcpSessions, MAX_32_BIT_PROCESS_TCP_SESSIONS))
    }
}

/**
 * The flow cap for a 32-bit process, the Battery Saver one. The Kotlin forwarder gives a TCP flow up
 * to three threads of its own - its remote reader, its upload writer and, while an open is still
 * timing out after the flow closed, a connect thread - and every thread reserves about 1 MiB of
 * stack. At 128 flows the upload and connect pools alone may grow to 144 + 288 threads, next to up
 * to 168 remote readers: some 600 MiB of address space, which a 32-bit process shares with ART's
 * heap, the boot image and every native library, so thread starts can fail there. 64 flows bring
 * those two ceilings down to 80 + 160, and the readers follow the open channels down with them.
 * A thread that can't start only resets its flow (see [submitToPool]); this keeps that rare.
 */
internal const val MAX_32_BIT_PROCESS_TCP_SESSIONS = 64

internal const val MIN_KEEP_ALIVE_INTERVAL_SEC = 15
internal const val MAX_KEEP_ALIVE_INTERVAL_SEC = 300
internal const val SCREEN_OFF_KEEP_ALIVE_INTERVAL_SEC = 120
