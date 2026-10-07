package com.stansful.sshvpnclient.screenshots.servers

import com.stansful.sshvpnclient.ui.keys.SshKeyFormat
import com.stansful.sshvpnclient.ui.keys.sshKeyFormatOf
import com.stansful.sshvpnclient.ui.servers.FingerprintKind
import com.stansful.sshvpnclient.ui.servers.fingerprintKind
import com.stansful.sshvpnclient.ui.servers.formatShortDate
import com.stansful.sshvpnclient.ui.servers.keyUsageLabel
import com.stansful.sshvpnclient.ui.servers.serversCountLabel
import com.stansful.sshvpnclient.vpn.matchesSshHostKeyFingerprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64

/** Plain logic behind the Servers screens: the fingerprint badge, key formats and the copy helpers. */
class ServersLogicTest {

    private val hostKey = "ssh-ed25519 host key blob".toByteArray()

    @Test
    fun fingerprintBadgeAcceptsWhatTheConnectionAccepts() {
        val sha = Base64.getEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(hostKey))
        val md5 = MessageDigest.getInstance("MD5").digest(hostKey).joinToString(":") { "%02x".format(it) }
        val accepted = mapOf(
            "SHA256:$sha" to FingerprintKind.Sha256,
            "sha256:$sha=" to FingerprintKind.Sha256,
            sha to FingerprintKind.Sha256,
            md5 to FingerprintKind.Md5,
            "MD5:${md5.uppercase()}" to FingerprintKind.Md5,
            md5.replace(":", " ") to FingerprintKind.Md5,
            md5.replace(":", "") to FingerprintKind.Md5,
        )
        accepted.forEach { (value, kind) ->
            assertEquals(value, kind, fingerprintKind("  $value "))
            assertTrue(value, matchesSshHostKeyFingerprint(value, hostKey))
        }
    }

    @Test
    fun fingerprintBadgeRejectsWhatTheConnectionRejects() {
        listOf("", "abc", "SHA256:", "SHA256:abc", "MD5:12:34", "SHA1:$SAMPLE_SHA").forEach { value ->
            assertNull(value, fingerprintKind(value))
        }
    }

    @Test
    fun keyFormatFollowsThePemMarkers() {
        assertEquals(SshKeyFormat.OPENSSH, sshKeyFormatOf("\n" + ServersFixtures.OPENSSH_KEY + "\n"))
        assertEquals(
            SshKeyFormat.RSA_PEM,
            sshKeyFormatOf("-----BEGIN RSA PRIVATE KEY-----\nabc\n-----END RSA PRIVATE KEY-----"),
        )
        assertNull(sshKeyFormatOf("-----BEGIN PRIVATE KEY-----\nabc\n-----END PRIVATE KEY-----"))
        assertNull(sshKeyFormatOf(ServersFixtures.PUBLIC_KEY))
    }

    @Test
    fun copyHelpers() {
        assertEquals("1 server", serversCountLabel(1))
        assertEquals("3 servers", serversCountLabel(3))
        assertEquals("Not used by any server", keyUsageLabel(0))
        assertEquals("Used by 1 server", keyUsageLabel(1))
        assertEquals("Used by 2 servers", keyUsageLabel(2))
        val today = LocalDate.of(2026, 10, 6)
        val thisYear = LocalDate.of(2026, 10, 3).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val lastYear = LocalDate.of(2025, 6, 2).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals("3 Oct", formatShortDate(thisYear, today, ZoneOffset.UTC))
        assertEquals("2 Jun 2025", formatShortDate(lastYear, today, ZoneOffset.UTC))
    }

    private companion object {
        const val SAMPLE_SHA = "atzfmdcrqQzoXZfKHLarePDyMw/G5NYfJ3h1eHUVS9g"
    }
}
