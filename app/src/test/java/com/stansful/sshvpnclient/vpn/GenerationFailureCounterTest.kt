package com.stansful.sshvpnclient.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GenerationFailureCounterTest {
    @Test
    fun `late callbacks cannot reset or increment a newer generation`() {
        val counter = GenerationFailureCounter()
        counter.resetForGeneration(10L)
        assertEquals(1, counter.recordFailure(10L, 1_000L)?.count)

        counter.resetForGeneration(11L)
        counter.recordSuccess(10L)
        assertNull(counter.recordFailure(10L, 2_000L))
        assertEquals(1, counter.recordFailure(11L, 3_000L)?.count)
    }

    @Test
    fun `success clears count and failure duration only for current generation`() {
        val counter = GenerationFailureCounter()
        counter.resetForGeneration(7L)
        assertEquals(0L, counter.recordFailure(7L, 10_000L)?.elapsedMs)
        assertEquals(5_000L, counter.recordFailure(7L, 15_000L)?.elapsedMs)

        counter.recordSuccess(7L)

        val restarted = counter.recordFailure(7L, 20_000L)
        assertEquals(1, restarted?.count)
        assertEquals(0L, restarted?.elapsedMs)
    }
}
