package com.stansful.sshvpnclient.vpn

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowWriterExecutorTest {
    private val stalledOutputs = mutableListOf<StalledChannelOutput>()
    private val executors = mutableListOf<ThreadPoolExecutor>()

    @After
    fun releaseWriters() {
        stalledOutputs.forEach(StalledChannelOutput::closeChannel)
        executors.forEach(ThreadPoolExecutor::shutdownNow)
    }

    @Test
    fun `flows stuck on a shut ssh window do not hold up another flow's upload`() {
        val executor = executor(maxActiveTcpSessions = 32)
        // More stuck flows than the old shared pool had workers (8): that was enough to freeze it.
        val released = CountDownLatch(STUCK_FLOWS)
        val stuck = List(STUCK_FLOWS) { stalledOutput() }
        stuck.forEach { output ->
            assertTrue(submit(executor) { writeAndCountFailure(output, released) })
        }
        stuck.forEach { output -> assertTrue(output.entered.await(WAIT_SECONDS, TimeUnit.SECONDS)) }

        val healthy = ByteArrayOutputStream()
        val flushed = CountDownLatch(1)
        assertTrue(
            submit(executor) {
                healthy.write(PAYLOAD)
                healthy.flush()
                flushed.countDown()
            },
        )

        assertTrue("the healthy flow's upload waited for stuck flows", flushed.await(WAIT_SECONDS, TimeUnit.SECONDS))
        assertArrayEquals(PAYLOAD, healthy.toByteArray())
        assertFalse("stuck writers stay stuck while their window is shut", released.await(0, TimeUnit.MILLISECONDS))

        stuck.forEach(StalledChannelOutput::closeChannel)
        assertTrue("closing a channel releases its writer", released.await(WAIT_SECONDS, TimeUnit.SECONDS))
    }

    @Test
    fun `writers are bounded and a released one is reused`() {
        val executor = executor(maxActiveTcpSessions = 1)
        val limit = flowWriterLimit(maxActiveTcpSessions = 1)
        val stuck = List(limit) { stalledOutput() }
        stuck.forEach { output -> assertTrue(submit(executor) { writeAndCountFailure(output, CountDownLatch(1)) }) }
        stuck.forEach { output -> assertTrue(output.entered.await(WAIT_SECONDS, TimeUnit.SECONDS)) }

        assertFalse("a writer past the limit is refused, not queued behind stuck ones", submit(executor) {})

        stuck.first().closeChannel()
        val ran = CountDownLatch(1)
        assertTrue(eventually { submit(executor) { ran.countDown() } })
        assertTrue(ran.await(WAIT_SECONDS, TimeUnit.SECONDS))
    }

    @Test
    fun `every flow the table allows can have its writer`() {
        listOf(true to false, false to true, false to false).forEach { (lowRam, powerSave) ->
            listOf(true, false).forEach { is64Bit ->
                val sessions = selectTunResourceProfile(
                    isLowRamDevice = lowRam,
                    isPowerSaveMode = powerSave,
                    is64BitProcess = is64Bit,
                ).maxActiveTcpSessions
                assertTrue("$sessions sessions", flowWriterLimit(sessions) >= sessions)
            }
        }
        // Low-RAM devices keep a small table, and with it a small ceiling on writer threads.
        val lowRamSessions = selectTunResourceProfile(
            isLowRamDevice = true,
            isPowerSaveMode = false,
            is64BitProcess = true,
        ).maxActiveTcpSessions
        assertTrue(flowWriterLimit(lowRamSessions) <= 2 * lowRamSessions)
    }

    @Test
    fun `a stopped forwarder takes no new writes and a closed channel still frees an interrupted writer`() {
        val executor = executor(maxActiveTcpSessions = 4)
        val released = CountDownLatch(1)
        val stuck = stalledOutput()
        assertTrue(submit(executor) { writeAndCountFailure(stuck, released) })
        assertTrue(stuck.entered.await(WAIT_SECONDS, TimeUnit.SECONDS))

        // shutdownNow() interrupts the writer, which JSch ignores, so only the channel close ends it.
        executor.shutdownNow()
        assertFalse(submit(executor) {})
        assertFalse(released.await(STALL_PROBE_MS, TimeUnit.MILLISECONDS))

        stuck.closeChannel()
        assertTrue(released.await(WAIT_SECONDS, TimeUnit.SECONDS))
        assertTrue(executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS))
    }

    private fun executor(maxActiveTcpSessions: Int): ThreadPoolExecutor {
        return newFlowWriterExecutor(
            maxActiveTcpSessions = maxActiveTcpSessions,
            threadFactory = DAEMON_THREADS,
            keepAliveMs = KEEP_ALIVE_MS,
        ).also(executors::add)
    }

    private fun stalledOutput(): StalledChannelOutput = StalledChannelOutput().also(stalledOutputs::add)

    private fun submit(executor: ThreadPoolExecutor, task: () -> Unit): Boolean {
        return try {
            executor.execute { task() }
            true
        } catch (_: RejectedExecutionException) {
            false
        }
    }

    private fun writeAndCountFailure(output: OutputStream, failed: CountDownLatch) {
        try {
            output.write(PAYLOAD)
        } catch (_: IOException) {
            failed.countDown()
        }
    }

    private fun eventually(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(POLL_MS)
        }
        return false
    }

    /**
     * A `direct-tcpip` channel whose remote window is used up, as JSch's `Session.write` treats it:
     * the write wakes every 100 ms, ignores interrupts and leaves only when the channel closes.
     */
    private class StalledChannelOutput : OutputStream() {
        val entered = CountDownLatch(1)
        private val channelClosed = CountDownLatch(1)

        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            entered.countDown()
            while (true) {
                try {
                    if (channelClosed.await(JSCH_WINDOW_WAIT_MS, TimeUnit.MILLISECONDS)) break
                } catch (_: InterruptedException) {
                    // JSch swallows the interrupt and keeps waiting for the window.
                }
            }
            throw IOException("channel is broken")
        }

        fun closeChannel() {
            channelClosed.countDown()
        }
    }

    private companion object {
        const val STUCK_FLOWS = 12
        const val WAIT_SECONDS = 5L
        const val KEEP_ALIVE_MS = 1_000L
        const val POLL_MS = 10L
        const val STALL_PROBE_MS = 300L
        const val JSCH_WINDOW_WAIT_MS = 100L
        val PAYLOAD = ByteArray(64 * 1024) { index -> index.toByte() }
        val DAEMON_THREADS = ThreadFactory { runnable -> Thread(runnable).apply { isDaemon = true } }
    }
}
