package com.stansful.sshvpnclient.vpn

import com.jcraft.jsch.HostKeyRepository
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
    fun `host key repository preserves optional fingerprint policy`() {
        val hostKey = "unconfigured-server-host-key".toByteArray()
        val messages = mutableListOf<String>()
        val repository = FingerprintHostKeyRepository(expectedFingerprint = null, log = messages::add)

        assertEquals(HostKeyRepository.OK, repository.check("example.com", hostKey))
        assertTrue(messages.any { it.contains("host identity is not verified") })
    }

    @Test
    fun `host key repository rejects mismatch before authentication`() {
        val messages = mutableListOf<String>()
        val repository = FingerprintHostKeyRepository(
            expectedFingerprint = "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            log = messages::add,
        )

        assertEquals(
            HostKeyRepository.CHANGED,
            repository.check("example.com", "different-server-host-key".toByteArray()),
        )
        assertTrue(messages.any { it.contains("authentication was not attempted") })
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
}
