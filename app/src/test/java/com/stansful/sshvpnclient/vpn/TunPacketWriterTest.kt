package com.stansful.sshvpnclient.vpn

import java.io.OutputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunPacketWriterTest {
    @Test
    fun `single writer preserves packet ordering`() {
        val written = Collections.synchronizedList(mutableListOf<ByteArray>())
        val writesCompleted = CountDownLatch(2)
        val output = object : OutputStream() {
            override fun write(value: Int) = Unit

            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                written += buffer.copyOfRange(offset, offset + length)
                writesCompleted.countDown()
            }
        }
        val writer = TunPacketWriter(
            output = output,
            queueCapacity = 2,
            enqueueTimeoutMs = 100L,
            isRunning = { true },
            onFailure = { error("Unexpected writer failure: $it") },
        )

        writer.start()
        try {
            assertTrue(writer.enqueue(byteArrayOf(1, 2)))
            assertTrue(writer.enqueue(byteArrayOf(3, 4)))
            assertTrue(writesCompleted.await(1, TimeUnit.SECONDS))
            assertArrayEquals(byteArrayOf(1, 2), written[0])
            assertArrayEquals(byteArrayOf(3, 4), written[1])
        } finally {
            writer.stop()
        }
    }

    @Test
    fun `full queue waits only for configured bound`() {
        val writeEntered = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val keepRunning = AtomicBoolean(true)
        val output = object : OutputStream() {
            override fun write(value: Int) {
                writeEntered.countDown()
                try {
                    releaseWrite.await()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }

            override fun close() {
                releaseWrite.countDown()
            }
        }
        val writer = TunPacketWriter(
            output = output,
            queueCapacity = 1,
            enqueueTimeoutMs = 50L,
            isRunning = keepRunning::get,
            onFailure = { error("Unexpected writer failure: $it") },
        )

        writer.start()
        try {
            assertTrue(writer.enqueue(byteArrayOf(1)))
            assertTrue(writeEntered.await(1, TimeUnit.SECONDS))
            assertTrue(writer.enqueue(byteArrayOf(2)))
            assertFalse(writer.enqueue(byteArrayOf(3)))
        } finally {
            keepRunning.set(false)
            releaseWrite.countDown()
            writer.stop()
        }
    }

    @Test
    fun `pooled packet writes only its valid length and is recycled`() {
        val written = mutableListOf<ByteArray>()
        val writeCompleted = CountDownLatch(1)
        val pool = TunPacketBufferPool(bufferSize = 8, capacity = 1)
        val packet = pool.acquire().apply {
            buffer.fill(99)
            buffer[0] = 1
            buffer[1] = 2
            buffer[2] = 3
            length = 3
        }
        val writer = TunPacketWriter(
            output = object : OutputStream() {
                override fun write(value: Int) = Unit

                override fun write(buffer: ByteArray, offset: Int, length: Int) {
                    written += buffer.copyOfRange(offset, offset + length)
                    writeCompleted.countDown()
                }
            },
            queueCapacity = 1,
            enqueueTimeoutMs = 100L,
            isRunning = { true },
            onFailure = { error("Unexpected writer failure: $it") },
        )

        writer.start()
        assertTrue(writer.enqueue(packet))
        assertTrue(writeCompleted.await(1, TimeUnit.SECONDS))
        writer.stop()
        assertTrue(writer.awaitStopped(1_000L))

        assertArrayEquals(byteArrayOf(1, 2, 3), written.single())
        assertEquals(1, pool.cachedBufferCount())
    }

    @Test
    fun `stop drains queued pooled packets and recycles in-flight packet`() {
        val writeEntered = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val pool = TunPacketBufferPool(bufferSize = 8, capacity = 2)
        val first = pool.acquire().apply { length = 1 }
        val second = pool.acquire().apply { length = 1 }
        val writer = TunPacketWriter(
            output = object : OutputStream() {
                override fun write(value: Int) {
                    writeEntered.countDown()
                    try {
                        releaseWrite.await()
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }

                override fun close() {
                    releaseWrite.countDown()
                }
            },
            queueCapacity = 1,
            enqueueTimeoutMs = 100L,
            isRunning = { true },
            onFailure = { error("Unexpected writer failure: $it") },
        )

        writer.start()
        assertTrue(writer.enqueue(first))
        assertTrue(writeEntered.await(1, TimeUnit.SECONDS))
        assertTrue(writer.enqueue(second))
        writer.stop()

        assertTrue(writer.awaitStopped(1_000L))
        assertEquals(2, pool.cachedBufferCount())
    }
}
