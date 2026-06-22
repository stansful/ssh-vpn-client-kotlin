package com.stansful.sshvpnclient.vpn

internal class ReconnectBackoff(
    private val initialDelayMs: Long,
    private val maxDelayMs: Long,
) {
    private var nextDelayMs = initialDelayMs

    init {
        require(initialDelayMs > 0L) { "Initial reconnect delay must be positive" }
        require(maxDelayMs >= initialDelayMs) { "Maximum reconnect delay must not be smaller than initial delay" }
    }

    fun reset() {
        nextDelayMs = initialDelayMs
    }

    fun nextFailureDelayMs(): Long {
        val delayMs = nextDelayMs
        nextDelayMs = (nextDelayMs * 2L).coerceAtMost(maxDelayMs)
        return delayMs
    }
}
