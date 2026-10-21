package com.stansful.sshvpnclient.vpn

import com.jcraft.jsch.HostKeyRepository
import com.stansful.sshvpnclient.ui.servers.FingerprintKind
import com.stansful.sshvpnclient.ui.servers.fingerprintKind
import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SshConnectionManagerTest {
    @Test
    fun `recognizes expected disconnect messages from JSch session thread`() {
        assertTrue(
            isExpectedJschDisconnectLog(
                "Caught an exception, leaving main loop due to Software caused connection abort",
            ),
        )
        assertTrue(
            isExpectedJschDisconnectLog(
                "Caught an exception, leaving main loop due to Connection reset\njava.net.SocketException",
            ),
        )
        assertTrue(
            isExpectedJschDisconnectLog(
                "Caught an exception, leaving main loop due to Socket closed",
            ),
        )
    }

    @Test
    fun `keeps unexpected JSch messages visible`() {
        assertFalse(isExpectedJschDisconnectLog("Authentication failed"))
        assertFalse(isExpectedJschDisconnectLog("channel is not opened"))
    }

    @Test
    fun `matches OpenSSH SHA256 fingerprints without weakening base64 comparison`() {
        val hostKey = "test-server-host-key".toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(hostKey)
        val encoded = Base64.getEncoder().withoutPadding().encodeToString(digest)

        assertTrue(matchesSshHostKeyFingerprint("SHA256:$encoded", hostKey))
        assertTrue(matchesSshHostKeyFingerprint(encoded, hostKey))

        val letterIndex = encoded.indexOfFirst(Char::isLetter)
        val originalLetter = encoded[letterIndex]
        val changedLetter = if (originalLetter.isUpperCase()) {
            originalLetter.lowercaseChar()
        } else {
            originalLetter.uppercaseChar()
        }
        val changedCase = encoded.replaceRange(letterIndex, letterIndex + 1, changedLetter.toString())
        assertFalse(matchesSshHostKeyFingerprint("SHA256:$changedCase", hostKey))
    }

    @Test
    fun `keeps legacy MD5 fingerprint compatibility`() {
        val hostKey = "legacy-server-host-key".toByteArray()
        val fingerprint = MessageDigest.getInstance("MD5")
            .digest(hostKey)
            .joinToString(":") { byte -> "%02X".format(byte.toInt() and 0xFF) }

        assertTrue(matchesSshHostKeyFingerprint("MD5:$fingerprint", hostKey))
        assertFalse(matchesSshHostKeyFingerprint("MD5:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00", hostKey))
    }

    @Test
    fun `host key repository trusts an unpinned key on first use and captures its fingerprint`() {
        val hostKey = "unconfigured-server-host-key".toByteArray()
        val messages = mutableListOf<String>()
        val repository = FingerprintHostKeyRepository(expectedFingerprint = null, log = messages::add)

        assertEquals(HostKeyRepository.OK, repository.check("example.com", hostKey))
        val trust = (repository.verdict as HostKeyVerdict.Accepted).trust
        assertTrue(trust is SshHostKeyTrust.FirstUse)
        assertEquals(openSshSha256(hostKey), trust.fingerprint)
        assertTrue(messages.any { it.contains("host identity is not verified") })
        assertTrue(messages.contains("Server host key fingerprint: ${openSshSha256(hostKey)}"))
    }

    @Test
    fun `a blank saved fingerprint means trust on first use too`() {
        val hostKey = "blank-pin-host-key".toByteArray()
        val repository = FingerprintHostKeyRepository(expectedFingerprint = "   ")

        assertEquals(HostKeyRepository.OK, repository.check("example.com", hostKey))
        assertTrue((repository.verdict as HostKeyVerdict.Accepted).trust is SshHostKeyTrust.FirstUse)
    }

    @Test
    fun `fingerprint captured on first use is accepted as a pin by the check and by the editor`() {
        val hostKey = "first-use-host-key".toByteArray()
        val firstUse = FingerprintHostKeyRepository(expectedFingerprint = null)
        firstUse.check("example.com", hostKey)
        val captured = ((firstUse.verdict as HostKeyVerdict.Accepted).trust as SshHostKeyTrust.FirstUse).fingerprint

        assertTrue(captured.startsWith("SHA256:"))
        assertTrue(matchesSshHostKeyFingerprint(captured, hostKey))
        assertEquals(FingerprintKind.Sha256, fingerprintKind(captured))
        val pinned = FingerprintHostKeyRepository(expectedFingerprint = captured)
        assertEquals(HostKeyRepository.OK, pinned.check("example.com", hostKey))
        assertEquals(HostKeyVerdict.Accepted(SshHostKeyTrust.Pinned(captured)), pinned.verdict)
    }

    @Test
    fun `a matching MD5 pin reports the key in the SHA256 form`() {
        val hostKey = "legacy-pinned-host-key".toByteArray()
        val md5 = MessageDigest.getInstance("MD5")
            .digest(hostKey)
            .joinToString(":") { byte -> "%02x".format(byte.toInt() and 0xFF) }
        val repository = FingerprintHostKeyRepository(expectedFingerprint = " MD5:$md5 ")

        assertEquals(HostKeyRepository.OK, repository.check("example.com", hostKey))
        assertEquals(HostKeyVerdict.Accepted(SshHostKeyTrust.Pinned(openSshSha256(hostKey))), repository.verdict)
    }

    @Test
    fun `host key repository rejects mismatch before authentication`() {
        val messages = mutableListOf<String>()
        val detail = mutableListOf<String>()
        val pinned = "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        val presented = "different-server-host-key".toByteArray()
        val repository = FingerprintHostKeyRepository(
            expectedFingerprint = pinned,
            log = messages::add,
            detailLog = detail::add,
        )

        assertEquals(HostKeyRepository.CHANGED, repository.check("example.com", presented))
        assertEquals(
            HostKeyVerdict.Changed(pinnedFingerprint = pinned, observedFingerprint = openSshSha256(presented)),
            repository.verdict,
        )
        // A mismatch ends the run, so it is logged even on quiet (non-verbose) attempts.
        assertTrue(messages.any { it.contains("authentication was not attempted") })
        assertTrue(messages.contains("Server host key fingerprint: ${openSshSha256(presented)}"))
    }

    @Test
    fun `host key changed error names the server and the way out`() {
        val error = SshHostKeyChangedException(
            serverName = "Home VPS",
            pinnedFingerprint = "SHA256:old",
            observedFingerprint = "SHA256:new",
        )

        assertEquals(
            "Host key changed for Home VPS. Open the server to review and accept the new key.",
            error.message,
        )
        assertEquals(SshFailureKind.HOST_KEY_CHANGED, classifySshFailure(error))
        assertFalse(error.afterSignIn)
    }

    @Test
    fun `a host key change found after sign-in says so`() {
        val error = SshHostKeyChangedException(
            serverName = "Home VPS",
            pinnedFingerprint = "SHA256:saved-meanwhile",
            observedFingerprint = "SHA256:new",
            afterSignIn = true,
        )

        assertEquals(
            "Host key changed for Home VPS after sign-in. Open the server to review the new key.",
            error.message,
        )
        assertEquals(SshFailureKind.HOST_KEY_CHANGED, classifySshFailure(error))
    }

    @Test
    fun `sanitizes multiline and secret-bearing SSH diagnostics`() {
        val sanitized = sanitizeSshDiagnostic(
            "server reply\npassword=secret-value\u0000 passphrase: another-secret",
        )

        assertFalse(sanitized.contains('\n'))
        assertFalse(sanitized.contains('\u0000'))
        assertFalse(sanitized.contains("secret-value"))
        assertFalse(sanitized.contains("another-secret"))
        assertTrue(sanitized.contains("password=<redacted>"))
        assertTrue(sanitized.contains("passphrase=<redacted>"))
    }

    private fun openSshSha256(hostKey: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(hostKey)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }
}
