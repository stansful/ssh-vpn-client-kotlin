package com.stansful.sshvpnclient.vpn

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SshLinkLivenessTest {
    private class FakeClock(var awake: Long = 1_000_000L, var real: Long = 5_000_000L) : LinkClock {
        override fun awakeMs(): Long = awake

        override fun realMs(): Long = real

        fun advance(ms: Long, sleptMs: Long = 0L) {
            awake += ms
            real += ms + sleptMs
        }
    }

    private val config = LinkWatchdogConfig()

    @Test
    fun `stream wrappers count bytes and keep a moving upload window`() {
        val clock = FakeClock()
        val stats = LinkStats(clock)
        val input = CountingInputStream(ByteArrayInputStream(ByteArray(10)), stats)
        val output = CountingOutputStream(ByteArrayOutputStream(), stats)

        assertEquals(4, input.read(ByteArray(4), 0, 4))
        output.write(ByteArray(300), 0, 300)
        var snapshot = stats.snapshot()
        assertEquals(4L, snapshot.rxBytes)
        assertEquals(300L, snapshot.txBytes)
        assertEquals(300L, snapshot.recentTxBytes)
        assertEquals(clock.awake, snapshot.lastRxAwakeMs)
        assertEquals(LINK_NEVER, snapshot.writeInFlightSinceAwakeMs)

        clock.advance(20_000L)
        snapshot = stats.snapshot()
        assertEquals("the upload window forgets old bytes", 0L, snapshot.recentTxBytes)
        assertEquals(300L, snapshot.txBytes)
    }

    @Test
    fun `reader states distinguish listening, busy and gone`() {
        val clock = FakeClock()
        val stats = LinkStats(clock)
        val timeouts = CountingInputStream(ThrowingInputStream(SocketTimeoutException("keepalive tick")), stats)
        runCatching { timeouts.read(ByteArray(1), 0, 1) }
        var snapshot = stats.snapshot()
        assertNull("SO_TIMEOUT is how JSch counts keepalives, not a dead reader", snapshot.readerGone)
        assertEquals(clock.awake, snapshot.readerBusySinceAwakeMs)

        val eof = CountingInputStream(ByteArrayInputStream(ByteArray(0)), stats)
        assertEquals(-1, eof.read(ByteArray(1), 0, 1))
        snapshot = stats.snapshot()
        assertNotNull(snapshot.readerGone)
        assertEquals(Thread.currentThread(), stats.readerThread)
    }

    @Test
    fun `sleep is reported separately from awake time`() {
        val clock = FakeClock()
        val stats = LinkStats(clock)
        clock.advance(ms = 2_000L, sleptMs = 600_000L)
        assertEquals(600_000L, stats.snapshot().sleptMs)
    }

    @Test
    fun `probe answered by any byte after the keepalive was written`() {
        val (judge, clock, stats) = judgeWithStats()
        val (decision, id) = judge.armProbe(stats.snapshot())
        assertEquals(LinkProbeDecision.SENT, decision)
        judge.probeWriting(id, stats.snapshot())
        clock.advance(5L)
        judge.probeWritten(id, stats.snapshot())
        clock.advance(80L)
        feedRx(stats, 36)

        val outcomes = judge.evaluate(stats.snapshot()) { ReaderStallKind.UNKNOWN }
        assertEquals(listOf(LinkOutcome.ProbeAnswered(80L)), outcomes)
        assertFalse(judge.isProbePending)
    }

    @Test
    fun `a reply that lands while the write call returns still counts`() {
        val (judge, clock, stats) = judgeWithStats()
        val (_, id) = judge.armProbe(stats.snapshot())
        judge.probeWriting(id, stats.snapshot())
        // Loopback-fast server: the reply is read before probeWritten() takes its snapshot.
        feedRx(stats, 36)
        judge.probeWritten(id, stats.snapshot())
        clock.advance(config.probeReplyTimeoutMs + 1L)

        val outcomes = judge.evaluate(stats.snapshot()) { ReaderStallKind.UNKNOWN }
        assertTrue(outcomes.single() is LinkOutcome.ProbeAnswered)
        assertFalse(judge.isDead)
    }

    @Test
    fun `silence after a written keepalive is death only while the reader listens`() {
        val (judge, clock, stats) = judgeWithStats()
        stats.enterRead()
        val (_, id) = judge.armProbe(stats.snapshot())
        judge.probeWriting(id, stats.snapshot())
        judge.probeWritten(id, stats.snapshot())
        clock.advance(config.probeReplyTimeoutMs - 1L)
        assertEquals(emptyList<LinkOutcome>(), judge.evaluate(stats.snapshot()) { ReaderStallKind.UNKNOWN })

        clock.advance(1L)
        val outcome = judge.evaluate(stats.snapshot()) { ReaderStallKind.UNKNOWN }.single()
        assertEquals(LinkDeathReason.PROBE_TIMEOUT, (outcome as LinkOutcome.Dead).reason)
        assertTrue(judge.isDead)
        assertEquals(LinkProbeDecision.LINK_DEAD, judge.armProbe(stats.snapshot()).first)
    }

    @Test
    fun `a parked reader defers the probe verdict to the stall rules`() {
        val (judge, clock, stats) = judgeWithStats()
        stats.enterRead()
        stats.exitRead(1)
        val (_, id) = judge.armProbe(stats.snapshot())
        judge.probeWriting(id, stats.snapshot())
        judge.probeWritten(id, stats.snapshot())
        clock.advance(config.probeReplyTimeoutMs + 1_000L)

        val outcomes = judge.evaluate(stats.snapshot()) { ReaderStallKind.WRITE_LOCK }
        assertFalse("a reader stuck behind the write lock cannot count a reply", judge.isDead)
        assertTrue(outcomes.single() is LinkOutcome.ReaderStall)
    }

    @Test
    fun `an unwritten probe never times out and is dropped later`() {
        val (judge, clock, stats) = judgeWithStats()
        judge.armProbe(stats.snapshot())
        clock.advance(config.probeReplyTimeoutMs * 3)
        assertEquals(emptyList<LinkOutcome>(), judge.evaluate(stats.snapshot()) { ReaderStallKind.UNKNOWN })

        clock.advance(config.unsentProbeResetMs)
        assertTrue(judge.evaluate(stats.snapshot()) { ReaderStallKind.UNKNOWN }.single() is LinkOutcome.ProbeAbandoned)
        assertFalse(judge.isDead)
        assertFalse(judge.isProbePending)
    }

    @Test
    fun `callbacks of an older probe cannot move the current one`() {
        val (judge, clock, stats) = judgeWithStats()
        val (_, first) = judge.armProbe(stats.snapshot())
        clock.advance(config.unsentProbeResetMs)
        judge.evaluate(stats.snapshot()) { ReaderStallKind.UNKNOWN }
        val (decision, second) = judge.armProbe(stats.snapshot())
        assertEquals(LinkProbeDecision.SENT, decision)

        judge.probeWritten(first, stats.snapshot())
        assertNull(judge.probeSendFailed(first, "late failure of the dropped probe"))
        assertFalse(judge.isDead)
        clock.advance(config.probeReplyTimeoutMs * 2)
        assertEquals(
            "the current probe was never written, so its reply clock never started",
            emptyList<LinkOutcome>(),
            judge.evaluate(stats.snapshot()) { ReaderStallKind.UNKNOWN },
        )
        assertEquals(LinkDeathReason.PROBE_SEND_FAILED, judge.probeSendFailed(second, "session is down")?.reason)
    }

    @Test
    fun `probes are never stacked`() {
        val (judge, _, stats) = judgeWithStats()
        assertEquals(LinkProbeDecision.SENT, judge.armProbe(stats.snapshot()).first)
        assertEquals(LinkProbeDecision.ALREADY_PENDING, judge.armProbe(stats.snapshot()).first)
    }

    @Test
    fun `a keepalive written behind an upload gets the longer timeout`() {
        val (judge, clock, stats) = judgeWithStats()
        stats.enterRead()
        val output = CountingOutputStream(ByteArrayOutputStream(), stats)
        output.write(ByteArray(200 * 1_024), 0, 200 * 1_024)
        val (decision, id) = judge.armProbe(stats.snapshot())
        assertEquals(
            "the kernel queue ahead of it is invisible, so it is sent anyway",
            LinkProbeDecision.SENT,
            decision,
        )
        judge.probeWriting(id, stats.snapshot())
        judge.probeWritten(id, stats.snapshot())

        clock.advance(config.probeReplyTimeoutMs * 2)
        assertEquals(emptyList<LinkOutcome>(), judge.evaluate(stats.snapshot()) { ReaderStallKind.UNKNOWN })
        clock.advance(config.probeReplyTimeoutUnderUploadMs - config.probeReplyTimeoutMs * 2)
        val dead = judge.evaluate(stats.snapshot()) { ReaderStallKind.UNKNOWN }.single() as LinkOutcome.Dead
        assertEquals(LinkDeathReason.PROBE_TIMEOUT, dead.reason)
        assertTrue(dead.detail.contains("behind 200KiB of upload"))
    }

    @Test
    fun `silence after a write asks the server once, rate-limited`() {
        val (judge, clock, stats) = judgeWithStats()
        val output = CountingOutputStream(ByteArrayOutputStream(), stats)
        output.write(ByteArray(64), 0, 64)
        clock.advance(config.silenceAfterTxProbeMs - 1L)
        assertFalse(judge.wantsSilenceProbe(stats.snapshot()))
        clock.advance(1L)
        assertTrue(judge.wantsSilenceProbe(stats.snapshot()))

        judge.noteSilenceProbeSent(clock.awake)
        assertFalse("rate-limited", judge.wantsSilenceProbe(stats.snapshot()))
        clock.advance(config.silenceProbeMinIntervalMs)
        assertTrue(judge.wantsSilenceProbe(stats.snapshot()))

        judge.armProbe(stats.snapshot())
        assertFalse("never while a probe is out", judge.wantsSilenceProbe(stats.snapshot()))
    }

    @Test
    fun `any byte from the server ends the silence`() {
        val (judge, clock, stats) = judgeWithStats()
        val output = CountingOutputStream(ByteArrayOutputStream(), stats)
        output.write(ByteArray(64), 0, 64)
        clock.advance(1_000L)
        feedRx(stats, 48)
        clock.advance(config.silenceAfterTxProbeMs * 2)
        assertFalse(judge.wantsSilenceProbe(stats.snapshot()))
        assertEquals(LINK_NEVER, stats.snapshot().firstUnansweredTxAwakeMs)

        output.write(ByteArray(64), 0, 64)
        val firstUnanswered = clock.awake
        clock.advance(1_000L)
        output.write(ByteArray(64), 0, 64)
        assertEquals(
            "silence counts from the first unanswered write",
            firstUnanswered,
            stats.snapshot().firstUnansweredTxAwakeMs,
        )
    }

    @Test
    fun `a quiet link is judged by real time, so a long sleep makes it quiet`() {
        val clock = FakeClock()
        val stats = LinkStats(clock)
        feedRx(stats, 64)
        clock.advance(ms = 3_000L, sleptMs = 3_600_000L)
        assertTrue(stats.nowRealMs() - stats.lastRxRealMs() >= 3_600_000L)
    }

    @Test
    fun `a write that never returns kills the link`() {
        val (judge, clock, stats) = judgeWithStats()
        stats.enterWrite()
        clock.advance(config.blockedWriteKillMs - 1L)
        assertFalse(judge.evaluate(stats.snapshot()) { ReaderStallKind.UNKNOWN }.any { it is LinkOutcome.Dead })
        clock.advance(1L)
        val dead = judge.evaluate(stats.snapshot()) { ReaderStallKind.UNKNOWN }.single() as LinkOutcome.Dead
        assertEquals(LinkDeathReason.BLOCKED_WRITE, dead.reason)
    }

    @Test
    fun `reader gone is final`() {
        val (judge, _, stats) = judgeWithStats()
        stats.readFailed(IOException("Connection reset"))
        val dead = judge.evaluate(stats.snapshot()) { ReaderStallKind.UNKNOWN }.single() as LinkOutcome.Dead
        assertEquals(LinkDeathReason.READER_GONE, dead.reason)
        assertEquals(emptyList<LinkOutcome>(), judge.evaluate(stats.snapshot()) { ReaderStallKind.UNKNOWN })
    }

    @Test
    fun `reader parked on a channel pipe is reported, then written off`() {
        val (judge, clock, stats) = judgeWithStats()
        stats.enterRead()
        stats.exitRead(512)
        clock.advance(config.readerStallReportMs)
        val report = judge.evaluate(stats.snapshot()) { ReaderStallKind.CHANNEL_PIPE }.single()
        assertEquals(ReaderStallKind.CHANNEL_PIPE, (report as LinkOutcome.ReaderStall).kind)
        clock.advance(1_000L)
        assertEquals(
            "one report per episode until the repeat interval",
            emptyList<LinkOutcome>(),
            judge.evaluate(stats.snapshot()) { ReaderStallKind.CHANNEL_PIPE },
        )

        clock.advance(config.readerStallKillMs)
        val dead = judge.evaluate(stats.snapshot()) { ReaderStallKind.CHANNEL_PIPE }.single() as LinkOutcome.Dead
        assertEquals(LinkDeathReason.READER_STALLED, dead.reason)
    }

    @Test
    fun `reader waiting for the write lock is never written off by the stall rule`() {
        val (judge, clock, stats) = judgeWithStats()
        stats.enterRead()
        stats.exitRead(1)
        clock.advance(config.readerStallKillMs * 3)
        val outcomes = judge.evaluate(stats.snapshot()) { ReaderStallKind.WRITE_LOCK }
        assertTrue(outcomes.single() is LinkOutcome.ReaderStall)
        assertFalse(judge.isDead)
    }

    @Test
    fun `classifier names where the reader is parked`() {
        fun frame(className: String, method: String) = StackTraceElement(className, method, "X.java", 1)
        val pipe = arrayOf(frame("java.lang.Object", "wait"), frame("java.io.PipedInputStream", "awaitSpace"))
        val androidWrite = arrayOf(frame("java.net.SocketOutputStream", "socketWrite0"))
        val nioWrite = arrayOf(frame("sun.nio.ch.NioSocketImpl", "implWrite"))
        val sink = arrayOf(frame("com.jcraft.jsch.Channel", "write"), frame("com.jcraft.jsch.Session", "run"))
        val lock = arrayOf(frame("com.jcraft.jsch.Session", "_write"), frame("com.jcraft.jsch.Session", "run"))

        assertEquals(ReaderStallKind.CHANNEL_PIPE, ReaderStallClassifier.classify(pipe, Thread.State.TIMED_WAITING))
        assertEquals(ReaderStallKind.SOCKET_WRITE, ReaderStallClassifier.classify(androidWrite, Thread.State.RUNNABLE))
        assertEquals(ReaderStallKind.SOCKET_WRITE, ReaderStallClassifier.classify(nioWrite, Thread.State.RUNNABLE))
        assertEquals(ReaderStallKind.CHANNEL_SINK, ReaderStallClassifier.classify(sink, Thread.State.RUNNABLE))
        assertEquals(ReaderStallKind.WRITE_LOCK, ReaderStallClassifier.classify(lock, Thread.State.BLOCKED))
        assertEquals(ReaderStallKind.OTHER, ReaderStallClassifier.classify(lock, Thread.State.RUNNABLE))
        assertEquals(ReaderStallKind.UNKNOWN, ReaderStallClassifier.classify(emptyArray(), Thread.State.RUNNABLE))
    }

    @Test
    fun `kill releases a reader blocked on the socket at once`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val stats = LinkStats(FakeClock())
            val factory = VpnProtectedSocketFactory(
                protectSocket = { true },
                connectTimeoutMs = 2_000,
                log = {},
                linkStats = stats,
            )
            val socket = factory.createSocket(loopbackHost(), server.localPort)
            server.accept().use {
                val input = factory.getInputStream(socket)
                val failure = AtomicReference<Throwable?>()
                val readerDone = CountDownLatch(1)
                val reader = thread(isDaemon = true) {
                    try {
                        input.read(ByteArray(16), 0, 16)
                    } catch (error: IOException) {
                        failure.set(error)
                    } finally {
                        readerDone.countDown()
                    }
                }
                Thread.sleep(100L)
                val startedAt = System.nanoTime()
                factory.kill("test")
                assertTrue(readerDone.await(2, TimeUnit.SECONDS))
                assertTrue((System.nanoTime() - startedAt) / 1_000_000L < 1_000L)
                assertNotNull(failure.get())
                assertTrue(factory.isKilled)
                assertNotNull(stats.snapshot().readerGone)
                reader.join(1_000L)
            }
        }
    }

    @Test
    fun `a killed factory refuses to open another socket`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val factory = VpnProtectedSocketFactory(protectSocket = { true }, connectTimeoutMs = 2_000, log = {})
            factory.kill("destroyed before connect")
            val error = runCatching {
                factory.createSocket(loopbackHost(), server.localPort)
            }.exceptionOrNull()
            assertTrue(error is IOException)
            assertEquals("destroyed before connect", factory.killReason)
        }
    }

    @Test
    fun `counting streams are only used when the factory has stats`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val factory = VpnProtectedSocketFactory(protectSocket = { true }, connectTimeoutMs = 2_000, log = {})
            val socket: Socket = factory.createSocket(loopbackHost(), server.localPort)
            socket.use {
                assertFalse(factory.getInputStream(it) is CountingInputStream)
                assertFalse(factory.getOutputStream(it) is CountingOutputStream)
            }
        }
    }

    private fun judgeWithStats(): Triple<LinkJudge, FakeClock, LinkStats> {
        val clock = FakeClock()
        return Triple(LinkJudge(config), clock, LinkStats(clock))
    }

    private fun loopbackHost(): String = requireNotNull(InetAddress.getLoopbackAddress().hostAddress)

    private fun feedRx(stats: LinkStats, bytes: Int) {
        stats.enterRead()
        stats.exitRead(bytes)
        stats.enterRead()
    }

    private class ThrowingInputStream(private val error: IOException) : InputStream() {
        override fun read(): Int = throw error

        override fun read(b: ByteArray, off: Int, len: Int): Int = throw error
    }
}
