package com.stansful.sshvpnclient.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SshRetryPolicyTest {
    @Test
    fun `a changed host key stops the run before and after the first connection`() {
        val policy = SshRetryPolicy()

        val beforeFirst = policy.onFailure(SshFailureKind.HOST_KEY_CHANGED, everConnected = false)
        val afterFirst = policy.onFailure(SshFailureKind.HOST_KEY_CHANGED, everConnected = true)

        assertTrue(beforeFirst is SshRetryDecision.Stop)
        assertTrue(afterFirst is SshRetryDecision.Stop)
        assertNotNull((afterFirst as SshRetryDecision.Stop).diagnostic)
    }

    @Test
    fun `an authentication failure before the first connection stops at once`() {
        val decision = SshRetryPolicy().onFailure(SshFailureKind.AUTHENTICATION, everConnected = false)

        assertEquals(SshRetryDecision.Stop(diagnostic = null), decision)
    }

    @Test
    fun `after the first connection the third failed sign-in in a row stops the run`() {
        val policy = SshRetryPolicy()

        assertEquals(SshRetryDecision.Retry, policy.onFailure(SshFailureKind.AUTHENTICATION, everConnected = true))
        assertEquals(SshRetryDecision.Retry, policy.onFailure(SshFailureKind.AUTHENTICATION, everConnected = true))
        val third = policy.onFailure(SshFailureKind.AUTHENTICATION, everConnected = true)

        assertTrue(third is SshRetryDecision.Stop)
        assertTrue((third as SshRetryDecision.Stop).diagnostic.orEmpty().contains("3 failed sign-ins"))
    }

    @Test
    fun `a successful sign-in resets the count`() {
        val policy = SshRetryPolicy()
        policy.onFailure(SshFailureKind.AUTHENTICATION, everConnected = true)
        policy.onFailure(SshFailureKind.AUTHENTICATION, everConnected = true)

        policy.onAuthenticated()

        assertEquals(0, policy.consecutiveAuthFailures)
        assertEquals(SshRetryDecision.Retry, policy.onFailure(SshFailureKind.AUTHENTICATION, everConnected = true))
        assertEquals(SshRetryDecision.Retry, policy.onFailure(SshFailureKind.AUTHENTICATION, everConnected = true))
        assertTrue(policy.onFailure(SshFailureKind.AUTHENTICATION, everConnected = true) is SshRetryDecision.Stop)
    }

    @Test
    fun `timeouts between failed sign-ins do not reset the count`() {
        val policy = SshRetryPolicy()

        policy.onFailure(SshFailureKind.AUTHENTICATION, everConnected = true)
        assertEquals(SshRetryDecision.Retry, policy.onFailure(SshFailureKind.TRANSIENT, everConnected = true))
        policy.onFailure(SshFailureKind.AUTHENTICATION, everConnected = true)
        assertEquals(SshRetryDecision.Retry, policy.onFailure(SshFailureKind.OTHER, everConnected = true))

        assertTrue(policy.onFailure(SshFailureKind.AUTHENTICATION, everConnected = true) is SshRetryDecision.Stop)
    }

    @Test
    fun `other failures keep the old rule`() {
        val policy = SshRetryPolicy()

        assertEquals(SshRetryDecision.Retry, policy.onFailure(SshFailureKind.TRANSIENT, everConnected = false))
        assertEquals(SshRetryDecision.Stop(null), policy.onFailure(SshFailureKind.OTHER, everConnected = false))
        repeat(10) {
            assertEquals(SshRetryDecision.Retry, policy.onFailure(SshFailureKind.TRANSIENT, everConnected = true))
            assertEquals(SshRetryDecision.Retry, policy.onFailure(SshFailureKind.OTHER, everConnected = true))
        }
    }

    @Test
    fun `failures are classified by type first, then by the transient messages`() {
        assertEquals(
            SshFailureKind.HOST_KEY_CHANGED,
            classifySshFailure(SshHostKeyChangedException("vps", "SHA256:old", null)),
        )
        assertEquals(
            SshFailureKind.AUTHENTICATION,
            classifySshFailure(SshAuthenticationException("Authentication failed")),
        )
        // A typed auth failure stays one even if its text mentions a timeout.
        assertEquals(
            SshFailureKind.AUTHENTICATION,
            classifySshFailure(SshAuthenticationException("Authentication failed: timeout")),
        )
        listOf(
            "Connection timeout",
            "Host unreachable",
            "No usable non-VPN network",
            "Underlying network changed during SSH connection",
            "SSH runtime belongs to another service instance",
            "SSH transport interrupted during VPN startup",
            "Unknown connection error",
            "TUN forwarding stopped",
        ).forEach { message ->
            assertEquals(message, SshFailureKind.TRANSIENT, classifySshFailure(VpnConnectionException(message)))
        }
        listOf(
            "Selected SSH key not found",
            "Invalid private key format",
            "No apps selected",
            // Plain "Authentication failed" (no password stored) is a setup problem, not a server verdict.
            "Authentication failed",
        ).forEach { message ->
            assertEquals(message, SshFailureKind.OTHER, classifySshFailure(VpnConnectionException(message)))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `the limit must allow at least one failure`() {
        SshRetryPolicy(maxConsecutiveAuthFailures = 0)
    }

    @Test
    fun `stop decisions without extra context carry no diagnostic`() {
        val decision = SshRetryPolicy().onFailure(SshFailureKind.OTHER, everConnected = false)

        assertNull((decision as SshRetryDecision.Stop).diagnostic)
    }
}
