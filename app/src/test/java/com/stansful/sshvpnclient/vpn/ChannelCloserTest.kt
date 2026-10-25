package com.stansful.sshvpnclient.vpn

import java.util.Collections
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
    private val ranOn: MutableList<Thread> = Collections.synchronizedList(mutableListOf())

    /** Set, every closer thread start fails like Android's once `pthread_create` fails. */
    @Volatile
    private var outOfThreads = false

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
        repeat(QUEUED) { closer.disconnectLater { ranOn += Thread.currentThread() } }

        closer.shutdown()

        assertEquals(List(QUEUED) { Thread.currentThread() }, ranOn.toList())
    }

    @Test
    fun `a disconnect after the closer stopped runs in place`() {
        val closer = closer()
        closer.shutdown()

        closer.disconnectLater { ranOn += Thread.currentThread() }

        assertEquals(listOf(Thread.currentThread()), ranOn.toList())
    }

    @Test
    fun `a failed disconnect never escapes a closer thread`() {
        val closer = closer(threads = 1)
        val disconnected = CountDownLatch(1)

        closer.disconnectLater { error("channel already gone") }
        closer.disconnectLater { disconnected.countDown() }

        assertTrue(disconnected.await(WAIT_SECONDS, TimeUnit.SECONDS))
        closer.shutdown()
        assertNothingEscaped()
    }

    @Test
    fun `a disconnect runs in place when the closer has no thread and can't start one`() {
        outOfThreads = true
        val closer = closer()

        closer.disconnectLater { ranOn += Thread.currentThread() }

        assertEquals(listOf(Thread.currentThread()), ranOn.toList())
    }

    @Test
    fun `a disconnect whose thread can't be started goes to an idle closer thread`() {
        val closer = closer(keepAliveMs = IDLE_FOR_GOOD_MS)
        val firstDone = CountDownLatch(1)
        closer.disconnectLater { firstDone.countDown() }
        assertTrue(firstDone.await(WAIT_SECONDS, TimeUnit.SECONDS))
        val idle = synchronized(created) { created.single() }
        // The pool is short of its four threads, so it starts one for the next disconnect, and can't.
        outOfThreads = true
        val disconnected = CountDownLatch(1)

        closer.disconnectLater {
            ranOn += Thread.currentThread()
            disconnected.countDown()
        }

        assertTrue(disconnected.await(WAIT_SECONDS, TimeUnit.SECONDS))
        assertEquals("the TUN read thread must not wait on a channel", listOf(idle), ranOn.toList())
    }

    @Test
    fun `a disconnect whose thread can't be started runs in place when the last closer thread just left`() {
        // The thread has left the pool and is about to look for disconnects queued meanwhile.
        val queue = StallingQueue(stallAt = LEFTOVER_CHECK)
        val closer = closer(keepAliveMs = EXPIRE_MS, queue = queue)
        closer.disconnectLater {}
        assertTrue(queue.stalled.await(WAIT_SECONDS, TimeUnit.SECONDS))
        val leaving = synchronized(created) { created.single() }
        outOfThreads = true

        closer.disconnectLater { ranOn += Thread.currentThread() }
        queue.release.countDown()
        leaving.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))

        assertEquals("exactly once, here", listOf(Thread.currentThread()), ranOn.toList())
        assertNothingEscaped()
    }

    @Test
    fun `the last closer thread runs a disconnect queued as it left when it can't start its replacement`() {
        // The thread timed out and found the queue empty, so it may leave; it is still in the pool.
        val queue = StallingQueue(stallAt = LEAVE_CHECK)
        val closer = closer(keepAliveMs = EXPIRE_MS, queue = queue)
        closer.disconnectLater {}
        assertTrue(queue.stalled.await(WAIT_SECONDS, TimeUnit.SECONDS))
        val leaving = synchronized(created) { created.single() }
        outOfThreads = true
        val disconnected = CountDownLatch(1)

        closer.disconnectLater {
            ranOn += Thread.currentThread()
            disconnected.countDown()
        }
        assertEquals("handed to the thread still in the pool", emptyList<Thread>(), ranOn.toList())
        // It leaves on the old answer, finds the disconnect and can't start a thread to take it.
        queue.release.countDown()

        assertTrue("the disconnect is not stranded", disconnected.await(WAIT_SECONDS, TimeUnit.SECONDS))
        leaving.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))
        assertEquals("exactly once, on that thread", listOf(leaving), ranOn.toList())
        assertNothingEscaped()
    }

    /** On Android an exception that escapes a pool thread ends the process. */
    private fun assertNothingEscaped() {
        // A thread's uncaught-exception handler has run once the thread is gone.
        synchronized(created) { created.toList() }.forEach { thread ->
            thread.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))
        }
        assertEquals(emptyList<Throwable>(), uncaught.toList())
    }

    private fun closer(
        threads: Int = 4,
        keepAliveMs: Long = 1_000L,
        queue: BlockingQueue<Runnable> = LinkedBlockingQueue(),
    ): ChannelCloser {
        val factory = ThreadFactory { task ->
            // What Android's Thread.start throws; the JVM pool asks the factory first.
            if (outOfThreads) throw OutOfMemoryError("pthread_create (1040KB stack) failed: Try again")
            Thread(task, CLOSER_THREAD_NAME).apply {
                isDaemon = true
                uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error -> uncaught += error }
                created += this
            }
        }
        return ChannelCloser(factory, keepAliveMs = keepAliveMs, threads = threads, queue = queue).also(closers::add)
    }

    /**
     * Holds a closer thread at its [stallAt]-th look at whether the queue is empty, after the answer
     * and before it acts on it. A pool thread that timed out asks first whether it may leave: only an
     * empty queue lets the last one go. Out of the pool it asks again, and starts a thread to take its
     * place if a disconnect was queued meanwhile.
     */
    private class StallingQueue(private val stallAt: Int) : LinkedBlockingQueue<Runnable>() {
        val stalled = CountDownLatch(1)
        val release = CountDownLatch(1)
        private val looks = AtomicInteger()

        override fun isEmpty(): Boolean {
            val empty = super.isEmpty()
            if (Thread.currentThread().name == CLOSER_THREAD_NAME && looks.incrementAndGet() == stallAt) {
                stalled.countDown()
                release.await(WAIT_SECONDS, TimeUnit.SECONDS)
            }
            return empty
        }
    }

    private companion object {
        const val CLOSER_THREAD_NAME = "test-channel-closer"
        const val WAIT_SECONDS = 5L
        const val SETTLE_MS = 200L
        const val QUEUED = 3
        const val IDLE_FOR_GOOD_MS = 60_000L
        const val EXPIRE_MS = 50L
        const val LEAVE_CHECK = 1
        const val LEFTOVER_CHECK = 2
    }
}
