package com.stansful.sshvpnclient.data.update

import java.net.HttpURLConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAppUpdateDownloaderPolicyTest {
    @Test
    fun `accepts only the repository release path as an initial URL`() {
        assertTrue(
            isTrustedAppUpdateInitialUrl(
                "https://github.com/stansful/ssh-vpn-client-kotlin/releases/download/v2.5.7/app.apk",
            ),
        )
        assertFalse(isTrustedAppUpdateInitialUrl("http://github.com/stansful/ssh-vpn-client-kotlin/releases/download/v2/app.apk"))
        assertFalse(isTrustedAppUpdateInitialUrl("https://github.com/other/repository/releases/download/v2/app.apk"))
        assertFalse(isTrustedAppUpdateInitialUrl("https://user@github.com/stansful/ssh-vpn-client-kotlin/releases/download/v2/app.apk"))
    }

    @Test
    fun `accepts only HTTPS GitHub redirect hosts`() {
        assertTrue(isTrustedAppUpdateRedirectUrl("https://release-assets.githubusercontent.com/file?token=1"))
        assertTrue(isTrustedAppUpdateRedirectUrl("https://objects.githubusercontent.com/file"))
        assertFalse(isTrustedAppUpdateRedirectUrl("http://release-assets.githubusercontent.com/file"))
        assertFalse(isTrustedAppUpdateRedirectUrl("https://githubusercontent.com.evil.example/file"))
        assertFalse(isTrustedAppUpdateRedirectUrl("https://example.com/file"))
    }

    @Test
    fun `rejects captive portal and API bodies before writing the partial APK`() {
        assertTrue(isRejectedAppUpdateContentType("text/html; charset=UTF-8"))
        assertTrue(isRejectedAppUpdateContentType("application/problem+json"))
        assertFalse(isRejectedAppUpdateContentType("application/octet-stream"))
        assertFalse(isRejectedAppUpdateContentType("application/vnd.android.package-archive"))
        assertFalse(isRejectedAppUpdateContentType(null))
    }

    @Test
    fun `parses complete and unsatisfied byte ranges`() {
        assertEquals(
            AppUpdateContentRange(start = 100L, endInclusive = 199L, total = 1_000L),
            parseAppUpdateContentRange("bytes 100-199/1000"),
        )
        assertEquals(
            AppUpdateContentRange(start = null, endInclusive = null, total = 1_000L),
            parseAppUpdateContentRange("bytes */1000"),
        )
        assertNull(parseAppUpdateContentRange("bytes 200-100/1000"))
    }

    @Test
    fun `resume response appends only at the exact local offset`() {
        val matchingRange = AppUpdateContentRange(256L, 511L, 1_024L)
        val wrongRange = AppUpdateContentRange(128L, 511L, 1_024L)

        assertEquals(
            AppUpdateResponseAction.APPEND,
            chooseAppUpdateResponseAction(256L, HttpURLConnection.HTTP_PARTIAL, matchingRange),
        )
        assertEquals(
            AppUpdateResponseAction.RETRY_FROM_ZERO,
            chooseAppUpdateResponseAction(256L, HttpURLConnection.HTTP_PARTIAL, wrongRange),
        )
        assertEquals(
            AppUpdateResponseAction.RESTART,
            chooseAppUpdateResponseAction(256L, HttpURLConnection.HTTP_OK, null),
        )
    }

    @Test
    fun `range not satisfiable completes only when local length equals remote total`() {
        assertEquals(
            AppUpdateResponseAction.COMPLETE,
            chooseAppUpdateResponseAction(
                existingBytes = 1_024L,
                responseCode = 416,
                contentRange = AppUpdateContentRange(null, null, 1_024L),
            ),
        )
        assertEquals(
            AppUpdateResponseAction.RETRY_FROM_ZERO,
            chooseAppUpdateResponseAction(
                existingBytes = 512L,
                responseCode = 416,
                contentRange = AppUpdateContentRange(null, null, 1_024L),
            ),
        )
    }
}
