package com.stansful.sshvpnclient.vpn

import android.os.SystemClock
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Two monotonic clocks for the SSH link.
 *
 * Liveness thresholds use [awakeMs]: it stops in deep sleep, exactly like the kernel's TCP
 * timers, so "8 s without a reply" means 8 s in which the reply could have been read - not 8 s
 * of the phone sleeping in a pocket. [realMs] includes sleep and exists only to report it.
 */
internal interface LinkClock {
    fun awakeMs(): Long

    fun realMs(): Long
}

internal object AndroidLinkClock : LinkClock {
    override fun awakeMs(): Long = SystemClock.uptimeMillis()

    override fun realMs(): Long = SystemClock.elapsedRealtime()
}

internal const val LINK_NEVER = -1L

/** Immutable view of one SSH socket, taken by the watchdog; the judge decides on these alone. */
internal data class LinkSnapshot(
    val nowAwakeMs: Long,
    val nowRealMs: Long,
    val rxBytes: Long,
    val txBytes: Long,
    /** [LINK_NEVER] until the first byte. */
    val lastRxAwakeMs: Long,
    val lastTxAwakeMs: Long,
    val lastRxRealMs: Long,
    val lastTxRealMs: Long,
    /** Bytes written to the socket over the last 8-16 s of awake time. */
    val recentTxBytes: Long,
    /** Since when the JSch reader has been outside `read()`; [LINK_NEVER] while it is reading. */
    val readerBusySinceAwakeMs: Long,
    /** Since when a `write()` has been in flight; [LINK_NEVER] while nothing is being written. */
    val writeInFlightSinceAwakeMs: Long,
    /** First write since the last byte from the server; [LINK_NEVER] once the server has said anything. */
    val firstUnansweredTxAwakeMs: Long,
    /** Set once the reader hit EOF or a fatal read error: it will not read this socket again. */
    val readerGone: String?,
    /** Deep sleep since the socket was created. */
    val sleptMs: Long,
)

/**
 * Byte counters of one SSH socket. The JSch threads feed it through the stream wrappers below,
 * the watchdog reads it. Nothing here blocks or allocates on the data path.
 */
internal class LinkStats(private val clock: LinkClock) {
    private val createdAwakeMs = clock.awakeMs()
    private val createdRealMs = clock.realMs()
    private val rx = AtomicLong()
    private val tx = AtomicLong()

    @Volatile
    private var lastRxAwakeMs = LINK_NEVER

    @Volatile
    private var lastRxRealMs = LINK_NEVER

    @Volatile
    private var lastTxAwakeMs = LINK_NEVER

    @Volatile
    private var lastTxRealMs = LINK_NEVER

    @Volatile
    private var readerBusySinceAwakeMs = LINK_NEVER

    @Volatile
    private var readerGone: String? = null

    /** The thread that read the socket last: the JSch session thread once connected. */
    @Volatile
    var readerThread: Thread? = null
        private set

    private val writesInFlight = AtomicInteger()

    @Volatile
    private var writeInFlightSinceAwakeMs = LINK_NEVER

    @Volatile
    private var firstUnansweredTxAwakeMs = LINK_NEVER

    // Two TX_SLOT_MS slots of "recent upload"; JSch serialises writes, so this lock is uncontended.
    private val txWindowLock = Any()
    private var txSlot = 0L
    private var txCurrentSlotBytes = 0L
    private var txPreviousSlotBytes = 0L

    fun snapshot(): LinkSnapshot {
        val nowAwakeMs = clock.awakeMs()
        val nowRealMs = clock.realMs()
        return LinkSnapshot(
            nowAwakeMs = nowAwakeMs,
            nowRealMs = nowRealMs,
            rxBytes = rx.get(),
            txBytes = tx.get(),
            lastRxAwakeMs = lastRxAwakeMs,
            lastTxAwakeMs = lastTxAwakeMs,
            lastRxRealMs = lastRxRealMs,
            lastTxRealMs = lastTxRealMs,
            recentTxBytes = recentTxBytes(nowAwakeMs),
            readerBusySinceAwakeMs = readerBusySinceAwakeMs,
            writeInFlightSinceAwakeMs = writeInFlightSinceAwakeMs,
            firstUnansweredTxAwakeMs = firstUnansweredTxAwakeMs,
            readerGone = readerGone,
            sleptMs = ((nowRealMs - createdRealMs) - (nowAwakeMs - createdAwakeMs)).coerceAtLeast(0L),
        )
    }

    /** Allocation-free pieces of [snapshot] for the per-flow hot path. */
    fun nowRealMs(): Long = clock.realMs()

    fun lastRxRealMs(): Long = lastRxRealMs

    internal fun enterRead() {
        readerThread = Thread.currentThread()
        readerBusySinceAwakeMs = LINK_NEVER
    }

    internal fun exitRead(bytes: Int) {
        val nowAwakeMs = clock.awakeMs()
        if (bytes > 0) {
            rx.addAndGet(bytes.toLong())
            lastRxAwakeMs = nowAwakeMs
            lastRxRealMs = clock.realMs()
            firstUnansweredTxAwakeMs = LINK_NEVER
        } else if (bytes < 0) {
            readerGone = "EOF from the server"
        }
        readerBusySinceAwakeMs = nowAwakeMs
    }

    internal fun readFailed(error: IOException) {
        // SO_TIMEOUT is how JSch counts serverAliveInterval: the reader goes straight back to read.
        if (error !is InterruptedIOException) {
            readerGone = "read failed: ${error::class.java.simpleName}: ${error.message}"
        }
        readerBusySinceAwakeMs = clock.awakeMs()
    }

    internal fun enterWrite() {
        if (writesInFlight.getAndIncrement() == 0) {
            writeInFlightSinceAwakeMs = clock.awakeMs()
        }
    }

    internal fun exitWrite(bytes: Int, completed: Boolean) {
        if (completed && bytes > 0) {
            val nowAwakeMs = clock.awakeMs()
            tx.addAndGet(bytes.toLong())
            lastTxAwakeMs = nowAwakeMs
            lastTxRealMs = clock.realMs()
            if (firstUnansweredTxAwakeMs == LINK_NEVER) firstUnansweredTxAwakeMs = nowAwakeMs
            synchronized(txWindowLock) {
                rollTxWindowLocked(nowAwakeMs)
                txCurrentSlotBytes += bytes
            }
        }
        if (writesInFlight.decrementAndGet() == 0) {
            writeInFlightSinceAwakeMs = LINK_NEVER
        }
    }

    private fun recentTxBytes(nowAwakeMs: Long): Long = synchronized(txWindowLock) {
        rollTxWindowLocked(nowAwakeMs)
        txCurrentSlotBytes + txPreviousSlotBytes
    }

    private fun rollTxWindowLocked(nowAwakeMs: Long) {
        val slot = nowAwakeMs / TX_SLOT_MS
        if (slot == txSlot) return
        txPreviousSlotBytes = if (slot == txSlot + 1) txCurrentSlotBytes else 0L
        txCurrentSlotBytes = 0L
        txSlot = slot
    }

    private companion object {
        const val TX_SLOT_MS = 8_000L
    }
}

/** Counts bytes and tells the watchdog whether the JSch reader is parked inside `read()`. */
internal class CountingInputStream(
    private val delegate: InputStream,
    private val stats: LinkStats,
) : InputStream() {
    override fun read(): Int {
        stats.enterRead()
        try {
            val value = delegate.read()
            stats.exitRead(if (value < 0) -1 else 1)
            return value
        } catch (error: IOException) {
            stats.readFailed(error)
            throw error
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        stats.enterRead()
        try {
            val read = delegate.read(buffer, offset, length)
            stats.exitRead(read)
            return read
        } catch (error: IOException) {
            stats.readFailed(error)
            throw error
        }
    }

    override fun available(): Int = delegate.available()

    override fun close() = delegate.close()
}

/** Counts bytes and marks a write as in flight until the kernel has taken it. */
internal class CountingOutputStream(
    private val delegate: OutputStream,
    private val stats: LinkStats,
) : OutputStream() {
    override fun write(value: Int) {
        stats.enterWrite()
        var completed = false
        try {
            delegate.write(value)
            completed = true
        } finally {
            stats.exitWrite(1, completed)
        }
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        stats.enterWrite()
        var completed = false
        try {
            delegate.write(buffer, offset, length)
            completed = true
        } finally {
            stats.exitWrite(length, completed)
        }
    }

    override fun flush() = delegate.flush()

    override fun close() = delegate.close()
}

internal enum class LinkDeathReason {
    /** The JSch reader got EOF or a read error: the session is gone even if `isConnected` lags. */
    READER_GONE,

    /** A socket write has not returned for too long: the path takes no data (TCP_USER_TIMEOUT backstop). */
    BLOCKED_WRITE,

    /** Nothing at all came back after an SSH keepalive, while the reader was listening. */
    PROBE_TIMEOUT,

    /** The keepalive could not even be written. */
    PROBE_SEND_FAILED,

    /** The reader is parked delivering data into one channel and reads nothing: every flow is frozen. */
    READER_STALLED,
}

/** Where the JSch reader sits when it stays out of `socket.read()`. */
internal enum class ReaderStallKind {
    /** In `PipedInputStream.awaitSpace()`: one channel's pipe is full because its consumer stopped reading. */
    CHANNEL_PIPE,

    /** In `Channel.write()` into a consumer-owned stream. */
    CHANNEL_SINK,

    /** Waiting for the JSch write lock that another thread holds. */
    WRITE_LOCK,

    /** Writing to the socket itself (window adjust, keepalive, rekey) and the write does not leave. */
    SOCKET_WRITE,
    OTHER,
    UNKNOWN,
}

internal enum class LinkProbeDecision {
    /** A keepalive is on its way; the verdict follows within the reply timeout. */
    SENT,
    ALREADY_PENDING,

    /** Bytes arrived recently enough that a probe would prove nothing new. */
    SKIPPED_RECENT_RX,
    LINK_DEAD,
}

internal data class LinkWatchdogConfig(
    val activeTickMs: Long = 1_000L,
    val idleTickMs: Long = 5_000L,
    val probeReplyTimeoutMs: Long = 8_000L,
    /**
     * A keepalive written after this much upload (last 8-16 s) may sit in the kernel's send queue
     * behind it - a queue the app cannot see, up to the socket buffer. Such a probe gets
     * [probeReplyTimeoutUnderUploadMs]. A live upload answers sooner anyway: sshd sends a window
     * adjust every ~96 KiB it forwards, and any byte counts as the reply. A dead one is TCP_USER_TIMEOUT's.
     */
    val probeQueueSuspectTxBytes: Long = 32L * 1_024L,
    val probeReplyTimeoutUnderUploadMs: Long = 45_000L,
    /** An armed probe that JSch never managed to write is dropped after this long (no verdict). */
    val unsentProbeResetMs: Long = 60_000L,
    /** Backstop for TCP_USER_TIMEOUT: a write this old means the path takes nothing. */
    val blockedWriteKillMs: Long = 45_000L,
    val readerStallReportMs: Long = 5_000L,
    val readerStallRepeatMs: Long = 15_000L,
    /** Continuous parking on one channel after which the whole session is written off; 0 = report only. */
    val readerStallKillMs: Long = 20_000L,
    /** A reader that has been out of `read()` longer than this was not listening for a reply. */
    val readerListeningGraceMs: Long = 500L,
    /**
     * We wrote and the server has said nothing for this long: ask it once. sshd answers a keepalive
     * itself, whatever the remote end of any channel is doing, so a live link replies in one RTT.
     */
    val silenceAfterTxProbeMs: Long = 6_000L,
    val silenceProbeMinIntervalMs: Long = 15_000L,
)

internal sealed interface LinkOutcome {
    data class ProbeAnswered(val rttMs: Long) : LinkOutcome

    data class ProbeAbandoned(val waitedMs: Long) : LinkOutcome

    data class ReaderStall(val busyMs: Long, val kind: ReaderStallKind) : LinkOutcome

    data class Dead(val reason: LinkDeathReason, val detail: String) : LinkOutcome
}

/**
 * Pure verdict logic: snapshots in, outcomes out. No threads and no clocks of its own, so every
 * rule is a JVM test. All times are awake time (see [LinkClock]).
 *
 * The probe has two phases on purpose. Its reply clock starts only once JSch has actually written
 * the keepalive: a probe stuck behind the write lock has not been sent and must not time out.
 * Its RX mark is taken right before the write: a reply that arrives while the write call is still
 * returning is a reply, not part of the baseline.
 */
internal class LinkJudge(private val config: LinkWatchdogConfig = LinkWatchdogConfig()) {
    private var probeId = 0L
    private var probeArmedAtMs = LINK_NEVER
    private var probeSentAtMs = LINK_NEVER
    private var probeRxMark = 0L
    private var probeRxMarkBeforeWrite = 0L
    private var probeRecentTxAtArm = 0L
    private var stallEpisodeStartMs = LINK_NEVER
    private var stallLastReportMs = LINK_NEVER
    private var lastSilenceProbeAtMs = LINK_NEVER

    private var dead = false

    @get:Synchronized
    val isDead: Boolean
        get() = dead

    @get:Synchronized
    val isProbePending: Boolean
        get() = probeArmedAtMs != LINK_NEVER

    /** @return the decision and, when it is [LinkProbeDecision.SENT], the id the sender must quote. */
    @Synchronized
    fun armProbe(snapshot: LinkSnapshot): Pair<LinkProbeDecision, Long> {
        if (dead) return LinkProbeDecision.LINK_DEAD to NO_PROBE
        if (probeArmedAtMs != LINK_NEVER) return LinkProbeDecision.ALREADY_PENDING to NO_PROBE
        probeId += 1
        probeArmedAtMs = snapshot.nowAwakeMs
        probeSentAtMs = LINK_NEVER
        probeRecentTxAtArm = snapshot.recentTxBytes
        return LinkProbeDecision.SENT to probeId
    }

    /** Right before JSch gets the keepalive: bytes read from here on may be its reply. */
    @Synchronized
    fun probeWriting(id: Long, snapshot: LinkSnapshot) {
        if (!isCurrentProbe(id)) return
        probeRxMarkBeforeWrite = snapshot.rxBytes
    }

    /** The keepalive left JSch: its reply clock starts now. */
    @Synchronized
    fun probeWritten(id: Long, snapshot: LinkSnapshot) {
        if (!isCurrentProbe(id)) return
        probeSentAtMs = snapshot.nowAwakeMs
        probeRxMark = probeRxMarkBeforeWrite
    }

    @Synchronized
    fun probeSendFailed(id: Long, detail: String): LinkOutcome.Dead? {
        if (!isCurrentProbe(id)) return null
        return markDead(LinkDeathReason.PROBE_SEND_FAILED, detail)
    }

    /**
     * The link's own trigger: something was written, nothing came back for
     * [LinkWatchdogConfig.silenceAfterTxProbeMs], and no probe is out. This is what catches a path that
     * dies under an upload or under JSch's own keepalive without anybody outside asking.
     */
    @Synchronized
    fun wantsSilenceProbe(snapshot: LinkSnapshot): Boolean {
        if (dead || probeArmedAtMs != LINK_NEVER) return false
        val since = snapshot.firstUnansweredTxAwakeMs
        if (since == LINK_NEVER || snapshot.nowAwakeMs - since < config.silenceAfterTxProbeMs) return false
        return lastSilenceProbeAtMs == LINK_NEVER ||
            snapshot.nowAwakeMs - lastSilenceProbeAtMs >= config.silenceProbeMinIntervalMs
    }

    @Synchronized
    fun noteSilenceProbeSent(nowAwakeMs: Long) {
        lastSilenceProbeAtMs = nowAwakeMs
    }

    private fun isCurrentProbe(id: Long): Boolean = !dead && probeArmedAtMs != LINK_NEVER && id == probeId

    /** @param classifyStall called lazily, only once the reader has been busy past the report threshold. */
    @Synchronized
    fun evaluate(snapshot: LinkSnapshot, classifyStall: () -> ReaderStallKind): List<LinkOutcome> {
        if (dead) return emptyList()
        snapshot.readerGone?.let { return listOf(markDead(LinkDeathReason.READER_GONE, it)) }
        if (snapshot.writeInFlightSinceAwakeMs != LINK_NEVER) {
            val stuckMs = snapshot.nowAwakeMs - snapshot.writeInFlightSinceAwakeMs
            if (stuckMs >= config.blockedWriteKillMs) {
                return listOf(markDead(LinkDeathReason.BLOCKED_WRITE, "socket write blocked for ${stuckMs}ms"))
            }
        }
        val outcomes = ArrayList<LinkOutcome>(2)
        evaluateProbe(snapshot)?.let { outcome ->
            if (outcome is LinkOutcome.Dead) return listOf(outcome)
            outcomes += outcome
        }
        evaluateReader(snapshot, classifyStall)?.let { outcome ->
            if (outcome is LinkOutcome.Dead) return outcomes + outcome
            outcomes += outcome
        }
        return outcomes
    }

    private fun evaluateProbe(snapshot: LinkSnapshot): LinkOutcome? {
        if (probeArmedAtMs == LINK_NEVER) return null
        if (probeSentAtMs == LINK_NEVER) {
            val waitedMs = snapshot.nowAwakeMs - probeArmedAtMs
            if (waitedMs < config.unsentProbeResetMs) return null
            clearProbe()
            return LinkOutcome.ProbeAbandoned(waitedMs)
        }
        if (snapshot.rxBytes > probeRxMark) {
            val rttMs = (snapshot.lastRxAwakeMs - probeSentAtMs).coerceAtLeast(0L)
            clearProbe()
            return LinkOutcome.ProbeAnswered(rttMs)
        }
        val waitedMs = snapshot.nowAwakeMs - probeSentAtMs
        if (waitedMs < config.probeReplyTimeoutMs) return null
        // Silence proves something only if somebody was listening. A reader parked elsewhere (a
        // full channel pipe, the write lock) cannot count the reply; the stall rules own that case.
        val readerListening = snapshot.readerBusySinceAwakeMs == LINK_NEVER ||
            snapshot.nowAwakeMs - snapshot.readerBusySinceAwakeMs < config.readerListeningGraceMs
        if (!readerListening) return null
        val queuedBehindUpload = probeRecentTxAtArm >= config.probeQueueSuspectTxBytes
        if (queuedBehindUpload && waitedMs < config.probeReplyTimeoutUnderUploadMs) return null
        val queueNote = if (queuedBehindUpload) {
            " (written behind ${formatLinkBytes(probeRecentTxAtArm)} of upload)"
        } else {
            ""
        }
        return markDead(
            LinkDeathReason.PROBE_TIMEOUT,
            "no bytes from the server for ${waitedMs}ms after an SSH keepalive$queueNote",
        )
    }

    private fun evaluateReader(
        snapshot: LinkSnapshot,
        classifyStall: () -> ReaderStallKind,
    ): LinkOutcome? {
        if (snapshot.readerBusySinceAwakeMs == LINK_NEVER) {
            stallEpisodeStartMs = LINK_NEVER
            return null
        }
        val busyMs = snapshot.nowAwakeMs - snapshot.readerBusySinceAwakeMs
        if (busyMs < config.readerStallReportMs) return null
        val kind = classifyStall()
        val parkedOnChannel = kind == ReaderStallKind.CHANNEL_PIPE || kind == ReaderStallKind.CHANNEL_SINK
        if (config.readerStallKillMs > 0L && parkedOnChannel && busyMs >= config.readerStallKillMs) {
            return markDead(
                LinkDeathReason.READER_STALLED,
                "reader parked on a channel ($kind) for ${busyMs}ms; the socket is not being read",
            )
        }
        val newEpisode = stallEpisodeStartMs != snapshot.readerBusySinceAwakeMs
        if (!newEpisode && snapshot.nowAwakeMs - stallLastReportMs < config.readerStallRepeatMs) return null
        stallEpisodeStartMs = snapshot.readerBusySinceAwakeMs
        stallLastReportMs = snapshot.nowAwakeMs
        return LinkOutcome.ReaderStall(busyMs, kind)
    }

    private fun clearProbe() {
        probeArmedAtMs = LINK_NEVER
        probeSentAtMs = LINK_NEVER
    }

    private fun markDead(reason: LinkDeathReason, detail: String): LinkOutcome.Dead {
        dead = true
        clearProbe()
        return LinkOutcome.Dead(reason, detail)
    }

    private companion object {
        const val NO_PROBE = 0L
    }
}

/** Reads the JSch reader's stack to name where it is parked. Pure. */
internal object ReaderStallClassifier {
    fun classify(stack: Array<StackTraceElement>, state: Thread.State): ReaderStallKind {
        if (stack.isEmpty()) return ReaderStallKind.UNKNOWN
        return when {
            stack.any { frame -> frame.className.startsWith("java.io.Piped") } -> ReaderStallKind.CHANNEL_PIPE
            stack.any(::isSocketWriteFrame) -> ReaderStallKind.SOCKET_WRITE
            stack.any { frame -> frame.className == JSCH_CHANNEL && frame.methodName == "write" } ->
                ReaderStallKind.CHANNEL_SINK
            state == Thread.State.BLOCKED &&
                stack.any { frame -> frame.className == JSCH_SESSION && frame.methodName.contains("write") } ->
                ReaderStallKind.WRITE_LOCK
            else -> ReaderStallKind.OTHER
        }
    }

    fun describe(stack: Array<StackTraceElement>, frames: Int = 8): String {
        return stack.take(frames).joinToString(" < ") { frame ->
            "${frame.className.substringAfterLast('.')}.${frame.methodName}:${frame.lineNumber}"
        }
    }

    private fun isSocketWriteFrame(frame: StackTraceElement): Boolean {
        return frame.className.contains("SocketOutputStream") ||
            frame.methodName.startsWith("socketWrite") ||
            frame.methodName.startsWith("sendto") ||
            (frame.className.contains("NioSocketImpl") && frame.methodName.contains("write", ignoreCase = true))
    }

    private const val JSCH_CHANNEL = "com.jcraft.jsch.Channel"
    private const val JSCH_SESSION = "com.jcraft.jsch.Session"
}
