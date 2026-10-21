package com.stansful.sshvpnclient.vpn

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubmitToPoolTest {
    private val pools = mutableListOf<ExecutorService>()
    private val refusals = mutableListOf<PoolRefusal>()

    /**
     * Creates threads until [outOfThreads] is set, then fails like Android once `pthread_create`
     * fails. Android's `Thread.start` throws there; the JVM pool asks the factory first, and either
     * way the error comes out of `execute` with the pool's worker count put back.
     */
    private val threads = object : ThreadFactory {
        @Volatile
        var outOfThreads = false

        override fun newThread(runnable: Runnable): Thread {
            if (outOfThreads) throw OutOfMemoryError("pthread_create (1040KB stack) failed: Try again")
            return Thread(runnable).apply { isDaemon = true }
        }
    }

    @After
    fun stopPools() {
        pools.forEach(ExecutorService::shutdownNow)
    }

    @Test
    fun `an upload writer that can't be started is refused instead of thrown at the caller`() {
        val executor = newFlowWriterExecutor(maxActiveTcpSessions = 4, threadFactory = threads, keepAliveMs = 1_000L)
            .also(pools::add)
        threads.outOfThreads = true
        var ran = false

        val handedOver = submitToPool(refusals::add) { executor.execute { ran = true } }

        assertNull(handedOver)
        assertEquals(listOf(PoolRefusal.NO_THREAD), refusals)
        assertFalse(ran)
        assertEquals(0, executor.poolSize)
    }

    @Test
    fun `the pool takes tasks again once threads can be started`() {
        val executor = newFlowWriterExecutor(maxActiveTcpSessions = 1, threadFactory = threads, keepAliveMs = 1_000L)
            .also(pools::add)
        threads.outOfThreads = true
        // Every writer the pool allows fails to start: none of them may stay counted.
        repeat(flowWriterLimit(maxActiveTcpSessions = 1) + 1) {
            assertNull(submitToPool(refusals::add) { executor.execute {} })
        }
        threads.outOfThreads = false
        val ran = CountDownLatch(1)

        assertNotNull(submitToPool(refusals::add) { executor.execute { ran.countDown() } })
        assertTrue(ran.await(WAIT_SECONDS, TimeUnit.SECONDS))
        assertTrue(refusals.all { refusal -> refusal == PoolRefusal.NO_THREAD })
    }

    @Test
    fun `a timer whose thread can't be started is refused instead of thrown at the caller`() {
        val scheduler = ScheduledThreadPoolExecutor(1, threads).also(pools::add)
        threads.outOfThreads = true

        val future = submitToPool(refusals::add) { scheduler.schedule({}, 1, TimeUnit.SECONDS) }

        assertNull(future)
        assertEquals(listOf(PoolRefusal.NO_THREAD), refusals)
    }

    @Test
    fun `a full or stopped pool is refused as saturated`() {
        val executor = newFlowWriterExecutor(maxActiveTcpSessions = 1, threadFactory = threads, keepAliveMs = 1_000L)
            .also(pools::add)
        executor.shutdown()

        assertNull(submitToPool(refusals::add) { executor.execute {} })
        assertEquals(listOf(PoolRefusal.SATURATED), refusals)
    }

    @Test
    fun `a task the pool takes is handed over without a refusal`() {
        val executor = newFlowWriterExecutor(maxActiveTcpSessions = 1, threadFactory = threads, keepAliveMs = 1_000L)
            .also(pools::add)
        val ran = CountDownLatch(1)

        assertNotNull(submitToPool(refusals::add) { executor.execute { ran.countDown() } })
        assertTrue(ran.await(WAIT_SECONDS, TimeUnit.SECONDS))
        assertTrue(refusals.isEmpty())
    }

    private companion object {
        const val WAIT_SECONDS = 5L
    }
}
