package com.stansful.sshvpnclient.xray

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayLiveHealthPolicyTest {
    @Test
    fun `parses bounded HTTP status codes including YouTube 204`() {
        assertEquals(204, parseHttpStatusCode("HTTP/1.1 204 No Content"))
        assertEquals(200, parseHttpStatusCode("HTTP/1.0 200 OK"))
        assertEquals(299, parseHttpStatusCode("HTTP/2 299"))
        assertNull(parseHttpStatusCode("HTTP/1.1 99 Invalid"))
        assertNull(parseHttpStatusCode("HTTP/1.1 600 Invalid"))
        assertNull(parseHttpStatusCode("HTTP/1.1 2040 Invalid"))
        assertNull(parseHttpStatusCode("ICY 200 OK"))
        assertNull(parseHttpStatusCode("not-http"))
    }

    @Test
    fun `live health accepts only HTTP 2xx`() {
        assertTrue(isSuccessfulLiveHealthHttpStatus(200))
        assertTrue(isSuccessfulLiveHealthHttpStatus(204))
        assertTrue(isSuccessfulLiveHealthHttpStatus(299))
        assertFalse(isSuccessfulLiveHealthHttpStatus(199))
        assertFalse(isSuccessfulLiveHealthHttpStatus(300))
        assertFalse(isSuccessfulLiveHealthHttpStatus(403))
        assertFalse(isSuccessfulLiveHealthHttpStatus(503))
    }

    @Test
    fun `live health handle requires exact owner generation and endpoint`() {
        val owner = TestOwner(1)
        val equalButDifferentOwner = TestOwner(1)
        val endpoint = XrayLiveHealthEndpoint(12_345, "user", "password")
        val handle = XrayLiveHealthHandle(generation = 7L, endpoint = endpoint)

        assertTrue(
            isLiveHealthHandleCurrent(
                activeOwner = owner,
                activeGeneration = 7L,
                activeEndpoint = endpoint,
                expectedOwner = owner,
                handle = handle,
            ),
        )
        assertFalse(
            isLiveHealthHandleCurrent(
                activeOwner = equalButDifferentOwner,
                activeGeneration = 7L,
                activeEndpoint = endpoint,
                expectedOwner = owner,
                handle = handle,
            ),
        )
        assertFalse(
            isLiveHealthHandleCurrent(
                activeOwner = owner,
                activeGeneration = 8L,
                activeEndpoint = endpoint,
                expectedOwner = owner,
                handle = handle,
            ),
        )
        assertFalse(
            isLiveHealthHandleCurrent(
                activeOwner = owner,
                activeGeneration = 7L,
                activeEndpoint = endpoint.copy(port = 12_346),
                expectedOwner = owner,
                handle = handle,
            ),
        )
    }

    @Test
    fun `live health endpoint validates SOCKS wire limits eagerly`() {
        XrayLiveHealthEndpoint(port = 1, username = "u", password = "p")
        XrayLiveHealthEndpoint(port = 65_535, username = "u", password = "p")

        assertThrows(IllegalArgumentException::class.java) {
            XrayLiveHealthEndpoint(port = 0, username = "u", password = "p")
        }
        assertThrows(IllegalArgumentException::class.java) {
            XrayLiveHealthEndpoint(port = 1, username = "", password = "p")
        }
        assertThrows(IllegalArgumentException::class.java) {
            XrayLiveHealthEndpoint(port = 1, username = "u", password = "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            // 128 two-byte UTF-8 characters exceed the SOCKS one-byte length field.
            XrayLiveHealthEndpoint(port = 1, username = "é".repeat(128), password = "p")
        }
    }

    @Test
    fun `live health endpoint does not expose credentials in diagnostics`() {
        val endpoint = XrayLiveHealthEndpoint(
            port = 12_345,
            username = "secret-user",
            password = "secret-password",
        )

        assertFalse(endpoint.toString().contains("secret-user"))
        assertFalse(endpoint.toString().contains("secret-password"))
        assertTrue(endpoint.toString().contains("credentials=<redacted>"))
    }

    @Test
    fun `live health timeout is fixed at five seconds`() {
        assertEquals(5_000L, XRAY_LIVE_HEALTH_TIMEOUT_MS)
    }
}

private data class TestOwner(val id: Int)
