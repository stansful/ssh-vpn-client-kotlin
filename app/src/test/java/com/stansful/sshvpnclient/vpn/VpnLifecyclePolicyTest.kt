package com.stansful.sshvpnclient.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnLifecyclePolicyTest {
    @Test
    fun `accepts only the current live service command`() {
        assertTrue(
            isVpnLifecycleCommandCurrent(
                expectedRunId = 7L,
                currentRunId = 7L,
                expectedCommandId = 11L,
                currentCommandId = 11L,
                expectedStartId = 3,
                serviceDestroyed = false,
            ),
        )
    }

    @Test
    fun `rejects superseded run or command`() {
        assertFalse(
            isVpnLifecycleCommandCurrent(
                expectedRunId = 7L,
                currentRunId = 8L,
                expectedCommandId = 11L,
                currentCommandId = 11L,
                expectedStartId = 3,
                serviceDestroyed = false,
            ),
        )
        assertFalse(
            isVpnLifecycleCommandCurrent(
                expectedRunId = 7L,
                currentRunId = 7L,
                expectedCommandId = 11L,
                currentCommandId = 12L,
                expectedStartId = 3,
                serviceDestroyed = false,
            ),
        )
    }

    @Test
    fun `rejects destroyed service and invalid start id`() {
        assertFalse(
            isVpnLifecycleCommandCurrent(
                expectedRunId = 7L,
                currentRunId = 7L,
                expectedCommandId = 11L,
                currentCommandId = 11L,
                expectedStartId = 3,
                serviceDestroyed = true,
            ),
        )
        assertFalse(
            isVpnLifecycleCommandCurrent(
                expectedRunId = 7L,
                currentRunId = 7L,
                expectedCommandId = 11L,
                currentCommandId = 11L,
                expectedStartId = 0,
                serviceDestroyed = false,
            ),
        )
    }
}
