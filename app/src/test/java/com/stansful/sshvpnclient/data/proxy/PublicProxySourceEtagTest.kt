package com.stansful.sshvpnclient.data.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PublicProxySourceEtagTest {
    @Test
    fun `conditional request reuses the ETag issued for the current source`() {
        assertEquals(
            ETAG,
            proxySourceIfNoneMatch(
                force = false,
                storedEtag = ETAG,
                storedEtagUrl = SOURCE_URL,
                sourceUrl = SOURCE_URL,
            ),
        )
    }

    @Test
    fun `forced refresh never sends If-None-Match`() {
        assertNull(
            proxySourceIfNoneMatch(
                force = true,
                storedEtag = ETAG,
                storedEtagUrl = SOURCE_URL,
                sourceUrl = SOURCE_URL,
            ),
        )
    }

    @Test
    fun `ETag issued by a previous source URL is not sent to the new source`() {
        assertNull(
            proxySourceIfNoneMatch(
                force = false,
                storedEtag = ETAG,
                storedEtagUrl = "https://previous.example.test/list.txt",
                sourceUrl = SOURCE_URL,
            ),
        )
    }

    @Test
    fun `legacy ETag without a recorded URL forces one full download`() {
        assertNull(
            proxySourceIfNoneMatch(
                force = false,
                storedEtag = ETAG,
                storedEtagUrl = null,
                sourceUrl = SOURCE_URL,
            ),
        )
    }

    @Test
    fun `missing ETag makes an unconditional request`() {
        assertNull(
            proxySourceIfNoneMatch(
                force = false,
                storedEtag = null,
                storedEtagUrl = SOURCE_URL,
                sourceUrl = SOURCE_URL,
            ),
        )
    }

    private companion object {
        const val ETAG = "\"list-v2\""
        const val SOURCE_URL = "https://current.example.test/list.txt"
    }
}
