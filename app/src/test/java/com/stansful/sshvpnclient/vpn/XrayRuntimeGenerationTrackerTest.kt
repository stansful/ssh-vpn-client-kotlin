package com.stansful.sshvpnclient.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayRuntimeGenerationTrackerTest {
    @Test
    fun `stale attempt cannot clear successor generation`() {
        val tracker = XrayRuntimeGenerationTracker()
        tracker.publish(1L)
        val firstAttemptGeneration = tracker.snapshot()

        tracker.publish(2L)

        assertEquals(1L, firstAttemptGeneration)
        assertFalse(tracker.clearIfMatches(checkNotNull(firstAttemptGeneration)))
        assertEquals(2L, tracker.snapshot())
    }

    @Test
    fun `stale publication cannot replace successor generation`() {
        val tracker = XrayRuntimeGenerationTracker()
        tracker.publish(2L)

        tracker.publish(1L)

        assertEquals(2L, tracker.snapshot())
        assertTrue(tracker.clearIfMatches(2L))
        assertNull(tracker.snapshot())
    }
}
