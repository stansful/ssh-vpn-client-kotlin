package com.stansful.sshvpnclient.vpn

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Shared daemon threads for every SSH link: one timer that never blocks, and a pool for calls that may. */
internal object SshLinkExecutors {
    val timer: ScheduledExecutorService = ScheduledThreadPoolExecutor(1, daemonThreads("ssh-link-timer"))
        .apply { removeOnCancelPolicy = true }

    /** Keepalive writes and `Session.disconnect()` can wait for the JSch write lock; never on [timer]. */
    val blocking: ExecutorService = Executors.newCachedThreadPool(daemonThreads("ssh-link-io"))

    private fun daemonThreads(prefix: String): ThreadFactory {
        val counter = AtomicInteger()
        return ThreadFactory { runnable ->
            val body = Runnable {
                // These threads are first created inside a connect attempt and live for the process:
                // they must not inherit that attempt's JSch logger (and, through it, its service).
                detachJschLogFromCurrentThread()
                runnable.run()
            }
            Thread(body, "$prefix-${counter.incrementAndGet()}").apply { isDaemon = true }
        }
    }
}

/**
 * Watches one SSH socket independently of the JSch threads. It holds no wake lock and wakes
 * nothing: its timer only runs while the CPU is awake, and all thresholds are awake time.
 *
 * @param sendProbe writes an SSH keepalive (`session.sendKeepAliveMsg()`); runs on a pool
 * thread because it can wait for the JSch write lock.
 */
internal class LinkWatchdog(
    private val label: String,
    private val stats: LinkStats,
    private val sendProbe: () -> Unit,
    private val listener: Listener,
    private val config: LinkWatchdogConfig,
    private val log: (String) -> Unit,
    private val timer: ScheduledExecutorService = SshLinkExecutors.timer,
    private val blocking: Executor = SshLinkExecutors.blocking,
) {
    interface Listener {
        /** Exactly once per watchdog, on the timer thread: must not block. */
        fun onDead(reason: LinkDeathReason, detail: String)
    }

    private val judge = LinkJudge(config)
    private val finished = AtomicBoolean(false)
    private val tickLock = Any()
    private var nextTick: ScheduledFuture<*>? = null
    private val stallReports = AtomicInteger()
    private val probesSent = AtomicInteger()
    private val probesAnswered = AtomicInteger()

    @Volatile
    private var lastRttMs = LINK_NEVER

    @Volatile
    private var lastAnsweredLogAwakeMs = LINK_NEVER

    val isDead: Boolean
        get() = judge.isDead

    fun start() {
        scheduleTick(config.activeTickMs)
    }

    /** Stops without a verdict; no callback follows. */
    fun stop() {
        finished.set(true)
        synchronized(tickLock) {
            nextTick?.cancel(false)
            nextTick = null
        }
    }

    /**
     * Checks the path now with one SSH keepalive. Cheap enough for every wake-up, network change or
     * stall hint; the verdict arrives within [LinkWatchdogConfig.probeReplyTimeoutMs] of awake time.
     */
    fun probe(): LinkProbeDecision {
        if (finished.get()) return LinkProbeDecision.LINK_DEAD
        val (decision, probeId) = judge.armProbe(stats.snapshot())
        if (decision != LinkProbeDecision.SENT) return decision
        probesSent.incrementAndGet()
        sendProbeInBackground(probeId)
        for (delayMs in QUICK_CHECKS_MS) {
            scheduleOneShotCheck(delayMs)
        }
        scheduleOneShotCheck(config.probeReplyTimeoutMs + FINAL_CHECK_MARGIN_MS)
        return decision
    }

    /** One line for the heartbeat: byte ages, where the reader is, and how much the device slept. */
    fun describe(): String {
        val snapshot = stats.snapshot()
        val reader = when {
            snapshot.readerGone != null -> "gone"
            snapshot.readerBusySinceAwakeMs == LINK_NEVER -> "reading"
            else -> "busy ${formatLinkDuration(snapshot.nowAwakeMs - snapshot.readerBusySinceAwakeMs)}"
        }
        val write = if (snapshot.writeInFlightSinceAwakeMs == LINK_NEVER) {
            "idle"
        } else {
            "in flight ${formatLinkDuration(snapshot.nowAwakeMs - snapshot.writeInFlightSinceAwakeMs)}"
        }
        val rtt = lastRttMs.takeIf { it != LINK_NEVER }?.let { " rtt=${it}ms" }.orEmpty()
        return "rx=${formatLinkBytes(snapshot.rxBytes)} tx=${formatLinkBytes(snapshot.txBytes)} " +
            "lastRx=${linkAge(snapshot, snapshot.lastRxAwakeMs, snapshot.lastRxRealMs)} " +
            "lastTx=${linkAge(snapshot, snapshot.lastTxAwakeMs, snapshot.lastTxRealMs)} " +
            "reader=$reader write=$write slept=${formatLinkDuration(snapshot.sleptMs)} " +
            "probes=${probesAnswered.get()}/${probesSent.get()}$rtt" +
            (if (judge.isProbePending) " probe=pending" else "") +
            (if (judge.isDead) " DEAD" else "")
    }

    private fun sendProbeInBackground(probeId: Long) {
        try {
            blocking.execute {
                try {
                    judge.probeWriting(probeId, stats.snapshot())
                    sendProbe()
                    judge.probeWritten(probeId, stats.snapshot())
                } catch (error: Exception) {
                    val detail = "keepalive not sent: ${error::class.java.simpleName}: ${error.message}"
                    judge.probeSendFailed(probeId, detail)?.let(::finish)
                }
            }
        } catch (ignored: RejectedExecutionException) {
            // Shared pool never shuts down; nothing to probe with if it somehow did.
        }
    }

    private fun scheduleTick(delayMs: Long) {
        synchronized(tickLock) {
            if (finished.get()) return
            nextTick?.cancel(false)
            nextTick = try {
                timer.schedule({ runScheduledTick() }, delayMs, TimeUnit.MILLISECONDS)
            } catch (ignored: RejectedExecutionException) {
                null
            }
        }
    }

    private fun scheduleOneShotCheck(delayMs: Long) {
        if (finished.get()) return
        try {
            timer.schedule({ safeTick() }, delayMs, TimeUnit.MILLISECONDS)
        } catch (ignored: RejectedExecutionException) {
            // The periodic tick still runs.
        }
    }

    private fun runScheduledTick() {
        safeTick()
        if (finished.get()) return
        val snapshot = stats.snapshot()
        if (judge.wantsSilenceProbe(snapshot) && probe() == LinkProbeDecision.SENT) {
            judge.noteSilenceProbeSent(snapshot.nowAwakeMs)
        }
        val active = judge.isProbePending ||
            snapshot.writeInFlightSinceAwakeMs != LINK_NEVER ||
            snapshot.readerBusySinceAwakeMs != LINK_NEVER ||
            snapshot.firstUnansweredTxAwakeMs != LINK_NEVER
        scheduleTick(if (active) config.activeTickMs else config.idleTickMs)
    }

    private fun safeTick() {
        if (finished.get()) return
        try {
            tick()
        } catch (error: RuntimeException) {
            log("$label: watchdog tick failed: ${error::class.java.simpleName}: ${error.message}")
        }
    }

    private fun tick() {
        var stack: Array<StackTraceElement>? = null
        val outcomes = judge.evaluate(stats.snapshot()) {
            val thread = stats.readerThread ?: return@evaluate ReaderStallKind.UNKNOWN
            val frames = thread.stackTrace
            stack = frames
            ReaderStallClassifier.classify(frames, thread.state)
        }
        outcomes.forEach { outcome ->
            when (outcome) {
                is LinkOutcome.ProbeAnswered -> onProbeAnswered(outcome.rttMs)
                is LinkOutcome.ProbeAbandoned -> log(
                    "$label: keepalive could not be written for ${outcome.waitedMs}ms; probe dropped",
                )
                is LinkOutcome.ReaderStall -> {
                    val reports = stallReports.incrementAndGet()
                    if (reports <= MAX_STALL_REPORTS) {
                        val frames = stack?.let { ReaderStallClassifier.describe(it) }.orEmpty()
                        log(
                            "$label: JSch reader has not read the socket for ${outcome.busyMs}ms, " +
                                "parked at ${outcome.kind}: $frames",
                        )
                    } else if (reports == MAX_STALL_REPORTS + 1) {
                        log("$label: further reader stall reports suppressed")
                    }
                }
                is LinkOutcome.Dead -> finish(outcome)
            }
        }
    }

    private fun onProbeAnswered(rttMs: Long) {
        probesAnswered.incrementAndGet()
        lastRttMs = rttMs
        val nowAwakeMs = stats.snapshot().nowAwakeMs
        val lastLogged = lastAnsweredLogAwakeMs
        if (lastLogged == LINK_NEVER || nowAwakeMs - lastLogged >= ANSWERED_LOG_INTERVAL_MS) {
            lastAnsweredLogAwakeMs = nowAwakeMs
            log("$label: keepalive answered in ~${rttMs}ms")
        }
    }

    private fun finish(dead: LinkOutcome.Dead) {
        if (!finished.compareAndSet(false, true)) return
        synchronized(tickLock) {
            nextTick?.cancel(false)
            nextTick = null
        }
        log("$label: DEAD ${dead.reason}: ${dead.detail}; ${describe()}")
        try {
            listener.onDead(dead.reason, dead.detail)
        } catch (error: RuntimeException) {
            log("$label: death callback failed: ${error::class.java.simpleName}: ${error.message}")
        }
    }

    private fun linkAge(snapshot: LinkSnapshot, awakeMs: Long, realMs: Long): String {
        if (awakeMs == LINK_NEVER) return "never"
        val awakeAgeMs = snapshot.nowAwakeMs - awakeMs
        val realAgeMs = snapshot.nowRealMs - realMs
        return if (realAgeMs - awakeAgeMs > SLEEP_REPORT_THRESHOLD_MS) {
            "${formatLinkDuration(awakeAgeMs)} (${formatLinkDuration(realAgeMs)} incl. sleep)"
        } else {
            formatLinkDuration(awakeAgeMs)
        }
    }

    private companion object {
        val QUICK_CHECKS_MS = longArrayOf(150L, 400L, 1_000L, 2_500L)
        const val FINAL_CHECK_MARGIN_MS = 50L
        const val MAX_STALL_REPORTS = 12
        const val ANSWERED_LOG_INTERVAL_MS = 60_000L
        const val SLEEP_REPORT_THRESHOLD_MS = 1_000L
    }
}

internal data class SshTransportLinkConfig(
    /**
     * TCP_USER_TIMEOUT: how long sent data may stay unacknowledged before the kernel drops the
     * connection and every blocked read/write fails. Without it Linux retries for ~15 minutes while
     * JSch keeps reporting the session as connected. Kernel timers stop in deep sleep, so this is
     * awake time. Lower detects faster but cuts more often on a bad cell edge; 30 s is the compromise.
     */
    val tcpUserTimeoutMs: Int = 30_000,
    val watchdog: LinkWatchdogConfig = LinkWatchdogConfig(),
    /** Minimum spacing of "has this idle link survived?" probes triggered by new flows. */
    val quietProbeMinIntervalMs: Long = 10_000L,
)

/**
 * One SSH session's socket, owned by the app rather than by JSch.
 *
 * - [kill] closes the raw socket from any thread without blocking: every JSch read and write that
 *   hangs on a dead path fails at once, `isConnected` drops within milliseconds, and nothing that
 *   tears the session down afterwards can wait on the JSch write lock.
 * - [connect] has one hard deadline for TCP, key exchange and authentication together.
 * - [destroy] never blocks: socket first, `Session.disconnect()` on a pool thread.
 * - the watchdog turns silence into a verdict and calls [onDead] with the socket already closed.
 */
internal class SshTransportLink(
    val id: Long,
    val socketFactory: VpnProtectedSocketFactory,
    private val stats: LinkStats,
    private val config: SshTransportLinkConfig,
    private val log: (String) -> Unit,
    private val onDead: (link: SshTransportLink, reason: LinkDeathReason, detail: String) -> Unit,
    private val timer: ScheduledExecutorService = SshLinkExecutors.timer,
    private val blocking: Executor = SshLinkExecutors.blocking,
) {
    val label: String = "SSH link #$id"
    private val destroyed = AtomicBoolean(false)
    private val lastQuietProbeRealMs = AtomicLong(LINK_NEVER)

    @Volatile
    private var session: Session? = null

    @Volatile
    private var watchdog: LinkWatchdog? = null

    @Volatile
    var deathReason: String? = null
        private set

    /** False once destroyed, killed or written off by the watchdog: stop trusting `isConnected`. */
    val isUsable: Boolean
        get() = !destroyed.get() && deathReason == null && !socketFactory.isKilled

    /**
     * Replaces `session.connect(timeout)`: [handshakeTimeoutMs] still bounds each read of the
     * handshake, and [totalDeadlineMs] bounds the whole attempt by closing the socket.
     */
    fun connect(session: Session, handshakeTimeoutMs: Int, totalDeadlineMs: Long) {
        check(this.session == null) { "$label is single-use" }
        this.session = session
        if (destroyed.get()) throw JSchException("$label was closed before connect")
        val deadline = try {
            timer.schedule(
                { socketFactory.kill("connect timeout: ${totalDeadlineMs}ms deadline for TCP, handshake and auth") },
                totalDeadlineMs,
                TimeUnit.MILLISECONDS,
            )
        } catch (ignored: RejectedExecutionException) {
            null
        }
        try {
            session.connect(handshakeTimeoutMs)
        } catch (error: JSchException) {
            val killReason = socketFactory.killReason ?: throw error
            throw JSchException("$label closed during connect ($killReason)", error)
        } finally {
            deadline?.cancel(false)
        }
        socketFactory.killReason?.let { killReason ->
            // The handshake finished just as the deadline or a teardown closed the socket.
            teardownInBackground(session)
            throw JSchException("$label closed during connect ($killReason)")
        }
    }

    /** Starts liveness supervision once the session has been promoted to active. */
    fun startWatchdog() {
        val activeSession = session ?: return
        if (destroyed.get() || watchdog != null) return
        val next = LinkWatchdog(
            label = label,
            stats = stats,
            sendProbe = { activeSession.sendKeepAliveMsg() },
            listener = object : LinkWatchdog.Listener {
                override fun onDead(reason: LinkDeathReason, detail: String) {
                    deathReason = "$reason: $detail"
                    socketFactory.kill(reason.name)
                    teardownInBackground(activeSession)
                    if (!destroyed.get()) onDead(this@SshTransportLink, reason, detail)
                }
            },
            config = config.watchdog,
            log = log,
            timer = timer,
            blocking = blocking,
        )
        watchdog = next
        next.start()
        if (destroyed.get()) next.stop()
    }

    fun probe(): LinkProbeDecision {
        if (!isUsable) return LinkProbeDecision.LINK_DEAD
        return watchdog?.probe() ?: LinkProbeDecision.LINK_DEAD
    }

    /**
     * Probes only when the link has been silent for [quietMs] and no quiet probe went out recently.
     * Called for new flows from the TUN read thread, so the common path is two volatile reads.
     * Silence is real time here: a NAT mapping expires while the phone sleeps, awake time does not.
     */
    fun probeIfQuiet(quietMs: Long): LinkProbeDecision {
        if (!isUsable) return LinkProbeDecision.LINK_DEAD
        val nowRealMs = stats.nowRealMs()
        val lastRx = stats.lastRxRealMs()
        if (lastRx != LINK_NEVER && nowRealMs - lastRx < quietMs) return LinkProbeDecision.SKIPPED_RECENT_RX
        val lastProbe = lastQuietProbeRealMs.get()
        if (lastProbe != LINK_NEVER && nowRealMs - lastProbe < config.quietProbeMinIntervalMs) {
            return LinkProbeDecision.ALREADY_PENDING
        }
        if (!lastQuietProbeRealMs.compareAndSet(lastProbe, nowRealMs)) {
            return LinkProbeDecision.ALREADY_PENDING
        }
        return probe()
    }

    /** Closes the socket only; bookkeeping and `Session.disconnect()` belong to [destroy]. */
    fun kill(reason: String) {
        socketFactory.kill(reason)
    }

    /** Never blocks. After this no callback comes from this link. */
    fun destroy(reason: String) {
        if (!destroyed.compareAndSet(false, true)) return
        watchdog?.stop()
        socketFactory.kill(reason)
        session?.let(::teardownInBackground)
    }

    fun localAddress(): InetAddress? = socketFactory.currentSocket?.localAddress

    fun describe(): String {
        val body = watchdog?.describe() ?: "not supervised"
        val state = when {
            destroyed.get() -> " closed"
            deathReason != null -> " dead(${deathReason})"
            else -> ""
        }
        return "$label $body$state"
    }

    private fun teardownInBackground(session: Session) {
        try {
            blocking.execute {
                val startedAtNs = System.nanoTime()
                try {
                    session.disconnect()
                } catch (ignored: Exception) {
                    // JSch can race its own reader thread here; the socket is closed either way.
                }
                val tookMs = (System.nanoTime() - startedAtNs) / NANOS_PER_MS
                if (tookMs > SLOW_TEARDOWN_LOG_MS) {
                    log("$label: Session.disconnect() took ${tookMs}ms off the reconnect path")
                }
            }
        } catch (ignored: RejectedExecutionException) {
            // The socket is already closed; JSch threads unwind on their own.
        }
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
        const val SLOW_TEARDOWN_LOG_MS = 2_000L
    }
}

/**
 * Linux `TCP_USER_TIMEOUT` from linux/tcp.h; identical on every Android ABI. OsConstants only gained it
 * in recent releases, and its fields are not compile-time constants, so older devices would not link.
 */
internal const val TCP_USER_TIMEOUT_OPTION = 18

/** Applies TCP_USER_TIMEOUT to a socket that already has a descriptor; failures are logged, not fatal. */
internal fun applyTcpUserTimeout(socket: Socket, timeoutMs: Int, log: (String) -> Unit): Boolean {
    return try {
        // fromSocket() dups the descriptor: the option lands on the same socket, closing the dup does not.
        ParcelFileDescriptor.fromSocket(socket).use { descriptor ->
            Os.setsockoptInt(descriptor.fileDescriptor, OsConstants.IPPROTO_TCP, TCP_USER_TIMEOUT_OPTION, timeoutMs)
        }
        true
    } catch (error: ErrnoException) {
        log("SSH socket: TCP_USER_TIMEOUT not applied: ${error.message}")
        false
    } catch (error: IOException) {
        log("SSH socket: TCP_USER_TIMEOUT not applied: ${error.message}")
        false
    } catch (error: RuntimeException) {
        log("SSH socket: TCP_USER_TIMEOUT not applied: ${error::class.java.simpleName}: ${error.message}")
        false
    }
}

internal fun formatLinkDuration(ms: Long): String {
    return if (ms < DURATION_SECONDS_WITH_DECIMAL_MS) {
        "${ms / 100 / 10.0}s"
    } else {
        "${ms / 1_000}s"
    }
}

internal fun formatLinkBytes(bytes: Long): String {
    return when {
        bytes >= BYTES_PER_MIB -> "${bytes / BYTES_PER_MIB}MiB"
        bytes >= BYTES_PER_KIB -> "${bytes / BYTES_PER_KIB}KiB"
        else -> "${bytes}B"
    }
}

private const val DURATION_SECONDS_WITH_DECIMAL_MS = 10_000L
private const val BYTES_PER_KIB = 1_024L
private const val BYTES_PER_MIB = 1_024L * 1_024L
