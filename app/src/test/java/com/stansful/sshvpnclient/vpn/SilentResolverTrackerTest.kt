package com.stansful.sshvpnclient.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SilentResolverTrackerTest {
    private val router = ipv4("192.168.3.1")
    private val cloudflare = ipv4("1.1.1.1")
    private val google = ipv4("8.8.8.8")
    private var nowMs = 1_000_000L
    private val tracker = SilentResolverTracker(nowMs = { nowMs })

    @Test
    fun `a silent resolver is skipped for the whole cooldown`() {
        assertTrue(tracker.markSilent(router, transportGeneration = 0L))

        nowMs += TunnelDnsPolicy.SILENT_RESOLVER_COOLDOWN_MS - 1
        assertTrue(tracker.isSilent(router))

        nowMs += 1
        assertFalse(tracker.isSilent(router))
    }

    @Test
    fun `a fallback resolver answering keeps the silent mark`() {
        // First lookup: the router stays silent for its 1.5 s budget, then 1.1.1.1 answers.
        tracker.markSilent(router, transportGeneration = 0L)
        tracker.markAnswered(cloudflare)

        // Every following uncached lookup goes straight to the tunnel's resolvers.
        repeat(3) {
            nowMs += 10_000L
            assertEquals(listOf(cloudflare, google), TunnelDnsPolicy.upstreamsFor(router, tracker::isSilent))
            tracker.markAnswered(cloudflare)
        }
    }

    @Test
    fun `the silent resolver itself answering clears its mark`() {
        tracker.markSilent(router, transportGeneration = 0L)

        tracker.markAnswered(router)

        assertFalse(tracker.isSilent(router))
        assertEquals(listOf(router, cloudflare, google), TunnelDnsPolicy.upstreamsFor(router, tracker::isSilent))
    }

    @Test
    fun `a new transport starts without silent resolvers`() {
        tracker.markSilent(router, transportGeneration = 0L)

        tracker.onTransportChanged(transportGeneration = 1L)

        assertFalse(tracker.isSilent(router))
    }

    @Test
    fun `a query that outlived its transport cannot mark a resolver for the next one`() {
        tracker.onTransportChanged(transportGeneration = 1L)

        assertFalse(tracker.markSilent(router, transportGeneration = 0L))
        assertFalse(tracker.isSilent(router))

        assertTrue(tracker.markSilent(router, transportGeneration = 1L))
        assertTrue(tracker.isSilent(router))
    }

    @Test
    fun `only a resolver that was not already silent is worth a log line`() {
        assertTrue(tracker.markSilent(router, transportGeneration = 0L))
        nowMs += 1_000L
        assertFalse("still in its cooldown", tracker.markSilent(router, transportGeneration = 0L))

        nowMs += TunnelDnsPolicy.SILENT_RESOLVER_COOLDOWN_MS
        assertTrue("the cooldown ran out", tracker.markSilent(router, transportGeneration = 0L))
    }

    @Test
    fun `marking again restarts the cooldown`() {
        tracker.markSilent(router, transportGeneration = 0L)
        nowMs += TunnelDnsPolicy.SILENT_RESOLVER_COOLDOWN_MS - 1_000L
        tracker.markSilent(router, transportGeneration = 0L)

        nowMs += 2_000L

        assertTrue(tracker.isSilent(router))
    }

    private fun ipv4(text: String): Int {
        return text.split('.').map(String::toInt).fold(0) { address, octet -> (address shl 8) or octet }
    }
}
