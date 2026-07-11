package com.stansful.sshvpnclient.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WakeRecoveryPolicyTest {
    private val policy = WakeRecoveryPolicy(minimumScreenOffDurationMs = 5 * 60_000L)

    @Test
    fun `ignores short screen off interval`() {
        assertNull(policy.recoveryDurationMs(screenOffAtMs = 1_000L, screenOnAtMs = 300_999L))
    }

    @Test
    fun `accepts interval at threshold`() {
        assertEquals(
            300_000L,
            policy.recoveryDurationMs(screenOffAtMs = 1_000L, screenOnAtMs = 301_000L),
        )
    }

    @Test
    fun `ignores missing or invalid screen off timestamp`() {
        assertNull(policy.recoveryDurationMs(screenOffAtMs = -1L, screenOnAtMs = 100_000L))
        assertNull(policy.recoveryDurationMs(screenOffAtMs = 100_000L, screenOnAtMs = 99_999L))
    }
}
