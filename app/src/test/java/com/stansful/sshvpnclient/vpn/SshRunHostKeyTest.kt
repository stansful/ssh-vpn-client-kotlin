package com.stansful.sshvpnclient.vpn

import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SshRunHostKeyTest {
    private val serverKeyBlob = "server-host-key".toByteArray()
    private val serverKey = SshHostKeyTrust.FirstUse(serverKeyBlob)
    private val otherKey = SshHostKeyTrust.FirstUse("other-host-key".toByteArray())

    @Test
    fun `a run starts with the saved fingerprint and a blank one trusts on first use`() {
        assertEquals(USER_KEY, SshRunHostKey(" $USER_KEY ").fingerprint)
        assertNull(SshRunHostKey("   ").fingerprint)
        assertNull(SshRunHostKey(null).fingerprint)
    }

    @Test
    fun `a key pinned on first use is checked by later attempts`() {
        val run = SshRunHostKey(null)

        assertTrue(run.afterFirstUse(serverKey, saved = serverKey.fingerprint))

        assertEquals(serverKey.fingerprint, run.fingerprint)
    }

    @Test
    fun `clearing the fingerprint during the run trusts the next key on first use again`() {
        val run = SshRunHostKey(null)
        run.afterFirstUse(serverKey, saved = serverKey.fingerprint)

        run.onSavedFingerprintRead(null)

        assertNull(run.fingerprint)
    }

    @Test
    fun `a fingerprint replaced during the run is checked from the next attempt`() {
        val run = SshRunHostKey(USER_KEY)

        run.onSavedFingerprintRead(" ${otherKey.fingerprint} ")

        assertEquals(otherKey.fingerprint, run.fingerprint)
    }

    @Test
    fun `a first use key that could not be saved is still checked while nothing is saved`() {
        val run = SshRunHostKey(null)

        assertTrue(run.afterFirstUse(serverKey, saved = null))
        assertEquals(serverKey.fingerprint, run.fingerprint)

        run.onSavedFingerprintRead(null)
        assertEquals(serverKey.fingerprint, run.fingerprint)
        run.onSavedFingerprintRead("  ")
        assertEquals(serverKey.fingerprint, run.fingerprint)
    }

    @Test
    fun `a fingerprint saved later replaces the unsaved first use key for good`() {
        val run = SshRunHostKey(null)
        run.afterFirstUse(serverKey, saved = null)

        run.onSavedFingerprintRead(USER_KEY)
        assertEquals(USER_KEY, run.fingerprint)
        // The user cleared it again on purpose: trust on first use, not the old unsaved key.
        run.onSavedFingerprintRead(null)
        assertNull(run.fingerprint)
    }

    @Test
    fun `a matching fingerprint saved during sign-in is kept in the form the user saved it`() {
        val md5 = "MD5:" + MessageDigest.getInstance("MD5").digest(serverKeyBlob)
            .joinToString(":") { byte -> "%02x".format(byte.toInt() and 0xFF) }
        val run = SshRunHostKey(null)

        assertTrue(run.afterFirstUse(serverKey, saved = md5))
        assertEquals(md5, run.fingerprint)

        val bare = serverKey.fingerprint.removePrefix("SHA256:")
        assertTrue(SshRunHostKey(null).afterFirstUse(serverKey, saved = bare))
    }

    @Test
    fun `a different key saved during sign-in stops the session`() {
        val run = SshRunHostKey(null)

        assertFalse(run.afterFirstUse(serverKey, saved = otherKey.fingerprint))

        assertEquals(otherKey.fingerprint, run.fingerprint)
    }

    @Test
    fun `first use trust carries the key's SHA256 fingerprint and is equal by it`() {
        val digest = MessageDigest.getInstance("SHA-256").digest(serverKeyBlob)
        val expected = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)

        assertEquals(expected, serverKey.fingerprint)
        assertEquals(SshHostKeyTrust.FirstUse(serverKeyBlob.copyOf()), serverKey)
        assertTrue(serverKey.matches(expected))
        assertFalse(serverKey.matches(otherKey.fingerprint))
    }

    private companion object {
        const val USER_KEY = "SHA256:atzfmdcrqQzoXZfKHLarePDyMw/G5NYfJ3h1eHUVS9g"
    }
}
