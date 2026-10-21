package com.stansful.sshvpnclient.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bookkeeping [KotlinTunForwarder] does for every answered and failed DNS query. */
class TunnelDnsHealthTest {
    private val router = ipv4("192.168.3.1")
    private val cloudflare = ipv4("1.1.1.1")
    private val google = ipv4("8.8.8.8")
    private var nowMs = 1_000_000L
    private val health = TunnelDnsHealth(nowMs = { nowMs }, degradationThreshold = THRESHOLD)

    @Test
    fun `an answered query keeps the silent marks`() {
        health.silentResolvers.markSilent(router, transportGeneration = 0L)

        repeat(3) {
            nowMs += 10_000L
            health.recordSuccess()
        }

        assertTrue(health.silentResolvers.isSilent(router))
        assertEquals(listOf(cloudflare, google), TunnelDnsPolicy.upstreamsFor(router, health.silentResolvers::isSilent))
    }

    @Test
    fun `a failure run is reported once when it reaches the threshold`() {
        assertNull(health.recordFailure())
        assertNull(health.recordFailure())

        assertEquals(THRESHOLD, health.recordFailure())
        assertNull("the same run is not reported twice", health.recordFailure())
    }

    @Test
    fun `an answered query ends the run, and the next run is reported again`() {
        repeat(THRESHOLD) { health.recordFailure() }

        health.recordSuccess()

        assertNull(health.recordFailure())
        assertNull(health.recordFailure())
        assertEquals(THRESHOLD, health.recordFailure())
    }

    @Test
    fun `a new transport ends the run and drops the silent marks`() {
        health.silentResolvers.markSilent(router, transportGeneration = 0L)
        repeat(THRESHOLD) { health.recordFailure() }

        health.onTransportChanged(transportGeneration = 1L)

        assertFalse(health.silentResolvers.isSilent(router))
        assertNull(health.recordFailure())
        assertFalse(
            "a query that outlived the old transport can't mark the new one",
            health.silentResolvers.markSilent(router, transportGeneration = 0L),
        )
        assertTrue(health.silentResolvers.markSilent(router, transportGeneration = 1L))
    }

    private fun ipv4(text: String): Int {
        return text.split('.').map(String::toInt).fold(0) { address, octet -> (address shl 8) or octet }
    }

    private companion object {
        const val THRESHOLD = 3
    }
}
