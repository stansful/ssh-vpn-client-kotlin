package com.stansful.sshvpnclient.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientUploadFlowTest {
    @Test
    fun `advertised window follows buffered bytes and never exceeds TCP limit`() {
        val flow = ClientUploadFlow(capacityBytes = 256 * 1024)
        flow.begin(clientSynSequence = 100L)

        assertEquals(65_535, flow.advertisedWindow())
        assertTrue(flow.tryAcceptData(sequence = 101L, byteCount = 60_000))
        assertEquals(60_000, flow.bufferedBytes)
        assertEquals(65_535, flow.advertisedWindow())

        assertTrue(flow.tryAcceptData(sequence = 60_101L, byteCount = 200_000))
        assertEquals(2_144, flow.advertisedWindow())
        assertFalse(flow.tryAcceptData(sequence = 260_101L, byteCount = 2_145))

        flow.releaseBuffered(200_000)
        assertEquals(65_535, flow.advertisedWindow())
    }

    @Test
    fun `FIN is accepted only at expected sequence and preserves buffered upload until drain`() {
        val flow = ClientUploadFlow(capacityBytes = 8_192)
        flow.begin(clientSynSequence = 4_294_967_294L)

        assertTrue(flow.tryAcceptData(sequence = 4_294_967_295L, byteCount = 2))
        assertEquals(1L, flow.nextSequence)
        assertFalse(flow.tryAcceptFin(sequence = 0L))
        assertFalse(flow.isFinished)

        assertTrue(flow.tryAcceptFin(sequence = 1L))
        assertTrue(flow.isFinished)
        assertEquals(2, flow.bufferedBytes)
        assertFalse(flow.tryAcceptData(sequence = 2L, byteCount = 1))

        flow.releaseBuffered(2)
        assertEquals(0, flow.bufferedBytes)
    }

    @Test
    fun `queue rejects bytes beyond its hard capacity without advancing ACK`() {
        val flow = ClientUploadFlow(capacityBytes = 1_024)
        flow.begin(clientSynSequence = 9L)

        assertTrue(flow.tryAcceptData(sequence = 10L, byteCount = 1_024))
        assertEquals(0, flow.advertisedWindow())
        assertFalse(flow.tryAcceptData(sequence = 1_034L, byteCount = 1))
        assertEquals(1_034L, flow.nextSequence)
        assertEquals(1_024, flow.bufferedBytes)
    }

    @Test
    fun `zero window remains latched across stale positive data until explicit reopen ACK`() {
        val tracker = UploadWindowAdvertisementTracker()

        tracker.recordSent(0)
        // A previously reserved data segment can be handed to TUN after the newer zero-window ACK.
        // Its positive window must not suppress the dedicated ACK at the current sequence.
        tracker.recordSent(65_535)

        assertTrue(tracker.shouldSendReopen(currentWindow = 65_535))
        assertFalse(tracker.shouldSendReopen(currentWindow = 0))

        tracker.recordExplicitReopenSent(window = 65_535)
        assertFalse(tracker.shouldSendReopen(currentWindow = 65_535))
    }

    @Test
    fun `one KiB payloads reach byte capacity without premature chunk window shrink`() {
        val capacityBytes = 128 * 1_024
        val flow = ClientUploadFlow(capacityBytes = capacityBytes)
        val queue = CoalescingUploadQueue(
            capacityBytes = capacityBytes,
            chunkSizeBytes = 64 * 1_024,
        )
        val payload = ByteArray(1_024) { index -> index.toByte() }
        flow.begin(clientSynSequence = 100L)
        var sequence = flow.nextSequence
        var previousAdvertisedRightEdge = flow.nextSequence + flow.advertisedWindow()

        repeat(capacityBytes / payload.size) {
            assertTrue(flow.tryAcceptData(sequence = sequence, byteCount = payload.size))
            queue.append(payload, sourceOffset = 0, byteCount = payload.size)
            sequence = flow.nextSequence
            val advertisedRightEdge = flow.nextSequence + flow.advertisedWindow()
            assertTrue(advertisedRightEdge >= previousAdvertisedRightEdge)
            previousAdvertisedRightEdge = advertisedRightEdge
        }

        assertEquals(0, flow.advertisedWindow())
        assertEquals(capacityBytes, queue.bufferedBytes)
        assertEquals(2, queue.chunkCount)
        assertEquals(2, queue.maxChunkCount)
    }

    @Test
    fun `coalesced upload queue exposes one bounded flush block at a time`() {
        val queue = CoalescingUploadQueue(
            capacityBytes = 128 * 1_024,
            chunkSizeBytes = 64 * 1_024,
        )
        val payload = ByteArray(128 * 1_024)
        queue.append(payload, sourceOffset = 0, byteCount = payload.size)

        val first = queue.poll()
        assertEquals(64 * 1_024, first?.length)
        assertEquals(1, queue.chunkCount)
        assertEquals(64 * 1_024, queue.bufferedBytes)

        val second = queue.poll()
        assertEquals(64 * 1_024, second?.length)
        assertTrue(queue.isEmpty)
        assertEquals(0, queue.bufferedBytes)
    }

    @Test
    fun `completed upload chunks are reused from the bounded recycle cache`() {
        val queue = CoalescingUploadQueue(
            capacityBytes = 128 * 1_024,
            chunkSizeBytes = 64 * 1_024,
        )
        val payload = ByteArray(1_024)
        queue.append(payload, sourceOffset = 0, byteCount = payload.size)
        val completed = requireNotNull(queue.poll())

        queue.recycle(completed)
        assertEquals(1, queue.recycledChunkCount)
        queue.append(payload, sourceOffset = 0, byteCount = payload.size)
        val reused = requireNotNull(queue.poll())

        assertSame(completed, reused)
        assertEquals(0, queue.recycledChunkCount)
    }
}
