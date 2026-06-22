package com.stansful.sshvpnclient.vpn

internal class WakeRecoveryPolicy(
    private val minimumScreenOffDurationMs: Long,
) {
    init {
        require(minimumScreenOffDurationMs >= 0L)
    }

    fun recoveryDurationMs(
        screenOffAtMs: Long,
        screenOnAtMs: Long,
    ): Long? {
        if (screenOffAtMs < 0L || screenOnAtMs < screenOffAtMs) return null
        val durationMs = screenOnAtMs - screenOffAtMs
        return durationMs.takeIf { it >= minimumScreenOffDurationMs }
    }
}
