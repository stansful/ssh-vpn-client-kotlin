package com.stansful.sshvpnclient.vpn

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelCloserTest {
    private val writerHoldsChannel = CountDownLatch(1)
    private val writeDone = CountDownLatch(1)
    private val closers = mutableListOf<ChannelCloser>()
    private val uncaught: MutableList<Throwable> = Collections.synchronizedList(mutableListOf())
    private val created: MutableList<Thread> = Collections.synchronizedList(mutableListOf())

    @After
    fun releaseWriter() {
        writeDone.countDown()
        closers.forEach(ChannelCloser::shutdown)
    }

    @Test
    fun `a disconnect waiting for the channel's writer does not hold up the caller`() {
        // Like JSch: the writer keeps the channel's monitor while it queues for the session's socket,
        // and disconnect() needs that monitor first.
        val channel = Any()
        val writer = Thread {
            synchronized(channel) {
                writerHoldsChannel.countDown()
                writeDone.await(WAIT_SECONDS, TimeUnit.SECONDS)
            }
        }.apply { start() }
        assertTrue(writerHoldsChannel.await(WAIT_SECONDS, TimeUnit.SECONDS))
        val closer = closer()
        val disconnected = CountDownLatch(1)

        closer.disconnectLater { synchronized(channel) { disconnected.countDown() } }

        // The caller is back here while the disconnect still waits for the writer.
        assertFalse(disconnected.await(SETTLE_MS, TimeUnit.MILLISECONDS))
        writeDone.countDown()
        assertTrue("the disconnect runs once the writer lets go", disconnected.await(WAIT_SECONDS, TimeUnit.SECONDS))
        writer.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))
    }

    @Test
    fun `disconnects still queued when the closer stops run on the caller`() {
        val closer = closer(threads = 1)
        val busy = CountDownLatch(1)
        closer.disconnectLater {
            busy.countDown()
            writeDone.await(WAIT_SECONDS, TimeUnit.SECONDS)
        }
        assertTrue(busy.await(WAIT_SECONDS, TimeUnit.SECONDS))
        val ranOn = Collections.synchronizedList(mutableListOf<Thread>())
        repeat(QUEUED) { closer.disconnectLater { ranOn += Thread.currentThread() } }

        closer.shutdown()

        assertEquals(List(QUEUED) { Thread.currentThread() }, ranOn.toList())
    }

    @Test
    fun `a disconnect after the closer stopped runs in place`() {
        val closer = closer()
        closer.shutdown()
        var ranOn: Thread? = null

        closer.disconnectLater { ranOn = Thread.currentThread() }

        assertEquals(Thread.currentThread(), ranOn)
    }

    @Test
    fun `a failed disconnect never escapes a closer thread`() {
        val closer = closer(threads = 1)
        val disconnected = CountDownLatch(1)

        closer.disconnectLater { error("channel already gone") }
        closer.disconnectLater { disconnected.countDown() }

        assertTrue(disconnected.await(WAIT_SECONDS, TimeUnit.SECONDS))
        closer.shutdown()
        // A thread's uncaught-exception handler has run once the thread is gone.
        synchronized(created) { created.toList() }.forEach { thread ->
            thread.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))
        }
        // On Android an exception that escapes a pool thread ends the process.
        assertEquals(emptyList<Throwable>(), uncaught.toList())
    }

    private fun closer(threads: Int = 4): ChannelCloser {
        val factory = ThreadFactory { task ->
            Thread(task, "test-channel-closer").apply {
                isDaemon = true
                uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error -> uncaught += error }
                created += this
            }
        }
        return ChannelCloser(factory, keepAliveMs = 1_000L, threads = threads).also(closers::add)
    }

    private companion object {
        const val WAIT_SECONDS = 5L
        const val SETTLE_MS = 200L
        const val QUEUED = 3
    }
}
