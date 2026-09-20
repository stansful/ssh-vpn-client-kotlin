package com.stansful.sshvpnclient.vpn

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.DirectTcpipChannelTuning
import com.jcraft.jsch.Session
import java.io.EOFException
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private const val MIN_IPV4_MTU = 576
private const val MAX_IPV4_PACKET_SIZE = 65_535
private const val IPV4_TCP_HEADER_BYTES = 40
private const val MAX_TCP_WINDOW = 65_535
private const val DEFAULT_SSH_CHANNEL_WINDOW_BYTES = 4 * 1024 * 1024
private const val DEFAULT_MAX_PENDING_UPLOAD_BYTES_PER_FLOW = 512 * 1024
private const val DEFAULT_TUN_WRITE_QUEUE_CAPACITY = 256
private const val DEFAULT_OUTBOUND_PACKET_POOL_CAPACITY = 64
private const val COALESCED_UPLOAD_CHUNK_BYTES = 64 * 1024
private const val HARD_MAX_ACTIVE_TCP_SESSIONS = 128
private const val HARD_MAX_ACTIVE_UDP_RELAY_SESSIONS = 24
private const val DEFAULT_MAX_ACTIVE_TCP_SESSIONS = HARD_MAX_ACTIVE_TCP_SESSIONS
private const val DEFAULT_SESSION_PRESSURE_THRESHOLD = 96
private const val DEFAULT_SESSION_PRESSURE_TARGET = 72
internal const val DEFAULT_TUN_WRITE_ENQUEUE_TIMEOUT_MS = 5_000L
private const val DEFAULT_DNS_QUERY_TIMEOUT_MS = 10_000L
private const val TUN_WRITER_THREAD_NAME = "kotlin-tun-writer"

// Well-known UDP ports worth naming in the diagnostics: these are the flows a user is most likely
// to notice failing.
private const val HTTP_PORT = 80
private const val HTTPS_PORT = 443
private const val NTP_PORT = 123
private const val STUN_PORT = 3478
private const val STUN_ALT_PORT = 3479
private const val TURN_TLS_PORT = 5349

internal fun shouldSendRemoteFin(
    streamReachedEof: Boolean,
    channelStillConnected: Boolean,
): Boolean = streamReachedEof && channelStillConnected

internal data class TunForwarderConfig(
    val tunMtu: Int,
    val sshChannelWindowBytes: Int = DEFAULT_SSH_CHANNEL_WINDOW_BYTES,
    val maxPendingUploadBytesPerFlow: Int = DEFAULT_MAX_PENDING_UPLOAD_BYTES_PER_FLOW,
    val tunWriteQueueCapacity: Int = DEFAULT_TUN_WRITE_QUEUE_CAPACITY,
    val outboundPacketPoolCapacity: Int = DEFAULT_OUTBOUND_PACKET_POOL_CAPACITY,
    val maxActiveTcpSessions: Int = DEFAULT_MAX_ACTIVE_TCP_SESSIONS,
    val tunWriteEnqueueTimeoutMs: Long = DEFAULT_TUN_WRITE_ENQUEUE_TIMEOUT_MS,
    val dnsQueryTimeoutMs: Long = DEFAULT_DNS_QUERY_TIMEOUT_MS,
) {
    init {
        require(tunMtu in MIN_IPV4_MTU..MAX_IPV4_PACKET_SIZE) {
            "TUN MTU must be in $MIN_IPV4_MTU..$MAX_IPV4_PACKET_SIZE"
        }
        require(sshChannelWindowBytes > 0) { "SSH channel window must be positive" }
        require(maxPendingUploadBytesPerFlow >= tcpMss) {
            "Upload queue must fit at least one TCP MSS"
        }
        require(tunWriteQueueCapacity > 0) { "TUN write queue capacity must be positive" }
        require(outboundPacketPoolCapacity > 0) { "Outbound packet pool capacity must be positive" }
        require(maxActiveTcpSessions in 1..HARD_MAX_ACTIVE_TCP_SESSIONS) {
            "Active TCP session limit must be in 1..$HARD_MAX_ACTIVE_TCP_SESSIONS"
        }
        require(tunWriteEnqueueTimeoutMs > 0L) { "TUN write enqueue timeout must be positive" }
        require(dnsQueryTimeoutMs > 0L) { "DNS query timeout must be positive" }
    }

    val tcpMss: Int
        get() = tunMtu - IPV4_TCP_HEADER_BYTES

    val sessionPressureThreshold: Int
        get() = minOf(
            DEFAULT_SESSION_PRESSURE_THRESHOLD,
            (maxActiveTcpSessions * 3 / 4).coerceAtLeast(1),
        )

    val sessionPressureTarget: Int
        get() = minOf(
            DEFAULT_SESSION_PRESSURE_TARGET,
            (maxActiveTcpSessions * 9 / 16).coerceAtLeast(1),
        )

    val maxPendingUploadChunksPerFlow: Int
        get() = ceilDiv(maxPendingUploadBytesPerFlow, COALESCED_UPLOAD_CHUNK_BYTES)
}

internal class ClientUploadFlow(
    private val capacityBytes: Int,
) {
    init {
        require(capacityBytes > 0) { "Upload capacity must be positive" }
    }

    var nextSequence: Long = 0L
        private set

    var isFinished: Boolean = false
        private set

    var bufferedBytes: Int = 0
        private set

    fun begin(clientSynSequence: Long) {
        nextSequence = seqPlus(clientSynSequence, 1)
    }

    fun tryAcceptData(sequence: Long, byteCount: Int): Boolean {
        require(byteCount >= 0) { "Byte count cannot be negative" }
        if (byteCount == 0) return sequence == nextSequence && !isFinished
        if (isFinished || sequence != nextSequence || byteCount > capacityBytes - bufferedBytes) {
            return false
        }
        bufferedBytes += byteCount
        nextSequence = seqPlus(nextSequence, byteCount)
        return true
    }

    fun tryAcceptFin(sequence: Long): Boolean {
        if (isFinished || sequence != nextSequence) return false
        isFinished = true
        nextSequence = seqPlus(nextSequence, 1)
        return true
    }

    fun releaseBuffered(byteCount: Int) {
        require(byteCount in 0..bufferedBytes) { "Cannot release more bytes than buffered" }
        bufferedBytes -= byteCount
    }

    fun advertisedWindow(): Int {
        return (capacityBytes - bufferedBytes).coerceIn(0, MAX_TCP_WINDOW)
    }
}

internal class CoalescingUploadQueue(
    private val capacityBytes: Int,
    private val chunkSizeBytes: Int,
) {
    internal class Chunk internal constructor(
        val buffer: ByteArray,
        var length: Int = 0,
    )

    private val chunks = ArrayDeque<Chunk>()
    private val recycledChunks = ArrayDeque<Chunk>()

    var bufferedBytes: Int = 0
        private set

    val chunkCount: Int
        get() = chunks.size

    val maxChunkCount: Int = ceilDiv(capacityBytes, chunkSizeBytes)

    internal val recycledChunkCount: Int
        get() = recycledChunks.size

    val isEmpty: Boolean
        get() = chunks.isEmpty()

    init {
        require(capacityBytes > 0) { "Upload queue capacity must be positive" }
        require(chunkSizeBytes > 0) { "Upload chunk size must be positive" }
    }

    /**
     * Coalesces arbitrarily small TCP payloads into fixed-size blocks. All blocks except the tail
     * are full, so byte capacity also provides a strict object-count bound.
     */
    fun append(
        source: ByteArray,
        sourceOffset: Int,
        byteCount: Int,
    ) {
        require(sourceOffset >= 0 && byteCount > 0 && sourceOffset + byteCount <= source.size) {
            "Upload payload must fit its source buffer"
        }
        require(byteCount <= capacityBytes - bufferedBytes) {
            "Upload payload exceeds queue byte capacity"
        }

        var readOffset = sourceOffset
        val endOffset = sourceOffset + byteCount
        while (readOffset < endOffset) {
            val tail = chunks.peekLast()?.takeIf { it.length < chunkSizeBytes }
                ?: acquireChunk().also(chunks::addLast)
            val copied = minOf(chunkSizeBytes - tail.length, endOffset - readOffset)
            source.copyInto(
                destination = tail.buffer,
                destinationOffset = tail.length,
                startIndex = readOffset,
                endIndex = readOffset + copied,
            )
            tail.length += copied
            readOffset += copied
        }
        bufferedBytes += byteCount
        check(chunks.size <= maxChunkCount) { "Upload queue exceeded its chunk bound" }
    }

    fun poll(): Chunk? {
        val chunk = chunks.pollFirst() ?: return null
        bufferedBytes -= chunk.length
        return chunk
    }

    fun recycle(chunk: Chunk) {
        require(chunk.buffer.size == chunkSizeBytes) { "Upload chunk belongs to another queue" }
        chunk.length = 0
        if (recycledChunks.size < MAX_RECYCLED_UPLOAD_CHUNKS) {
            recycledChunks.addLast(chunk)
        }
    }

    fun clear() {
        chunks.clear()
        recycledChunks.clear()
        bufferedBytes = 0
    }

    private fun acquireChunk(): Chunk {
        return (recycledChunks.pollFirst() ?: Chunk(ByteArray(chunkSizeBytes))).also {
            check(it.length == 0) { "Recycled upload chunk was not reset" }
        }
    }

    private companion object {
        const val MAX_RECYCLED_UPLOAD_CHUNKS = 2
    }
}

internal class TcpHalfCloseState {
    var remoteFinSent: Boolean = false
        private set

    var remoteFinAcknowledged: Boolean = false
        private set

    var clientFinAccepted: Boolean = false
        private set

    var clientOutputClosed: Boolean = false
        private set

    val acceptsClientData: Boolean
        get() = !clientFinAccepted

    val canClose: Boolean
        get() = remoteFinSent && remoteFinAcknowledged && clientFinAccepted && clientOutputClosed

    fun onRemoteFinSent() {
        remoteFinSent = true
    }

    fun onRemoteFinAcknowledged() {
        if (remoteFinSent) {
            remoteFinAcknowledged = true
        }
    }

    fun onClientFinAccepted() {
        clientFinAccepted = true
    }

    fun onClientOutputClosed() {
        if (clientFinAccepted) {
            clientOutputClosed = true
        }
    }
}

/**
 * Latches every zero window handed to the TUN writer until a dedicated positive-window ACK is
 * handed off successfully. An ordinary positive packet cannot clear the latch: its TCP sequence
 * may precede the zero-window ACK and the client is then allowed to ignore its window update.
 */
internal class UploadWindowAdvertisementTracker {
    private var needsExplicitReopen = false

    fun recordSent(window: Int) {
        if (window == 0) {
            needsExplicitReopen = true
        }
    }

    fun shouldSendReopen(currentWindow: Int): Boolean {
        return needsExplicitReopen && currentWindow > 0
    }

    fun recordExplicitReopenSent(window: Int) {
        require(window > 0) { "An upload window reopen must advertise positive capacity" }
        needsExplicitReopen = false
    }
}

internal fun remainingIdleCleanupDelayMs(
    idleForMs: Long,
    timeoutMs: Long,
): Long = (timeoutMs - idleForMs).coerceAtLeast(0L)

internal fun remainingClientFinCleanupDelayMs(
    nowMs: Long,
    clientFinReceivedAtMs: Long,
    lastActivityAtMs: Long,
    timeoutMs: Long,
): Long {
    val lastHalfCloseActivityAtMs = maxOf(clientFinReceivedAtMs, lastActivityAtMs)
    return remainingIdleCleanupDelayMs(
        idleForMs = (nowMs - lastHalfCloseActivityAtMs).coerceAtLeast(0L),
        timeoutMs = timeoutMs,
    )
}

internal class CoalescedActivityTimestamp(
    initialValueMs: Long,
    private val minimumUpdateIntervalMs: Long,
) {
    private val value = AtomicLong(initialValueMs)

    init {
        require(minimumUpdateIntervalMs >= 0L) { "Activity update interval cannot be negative" }
    }

    fun mark(nowMs: Long) {
        while (true) {
            val previous = value.get()
            if (nowMs <= previous || nowMs - previous < minimumUpdateIntervalMs) return
            if (value.compareAndSet(previous, nowMs)) return
        }
    }

    fun get(): Long = value.get()
}

/**
 * A lazily allocated, strictly bounded pool for full-MTU packets. The packet object and its byte
 * array move together between a producer, the TUN queue, the writer, and finally back to this pool.
 */
internal class TunPacketBufferPool(
    private val bufferSize: Int,
    capacity: Int,
) {
    private val available = ArrayBlockingQueue<TunWritePacket>(capacity)

    init {
        require(bufferSize > 0) { "Packet buffer size must be positive" }
        require(capacity > 0) { "Packet pool capacity must be positive" }
    }

    fun acquire(): TunWritePacket {
        return (available.poll() ?: TunWritePacket(ByteArray(bufferSize), owner = this)).also {
            it.length = 0
        }
    }

    internal fun recycle(packet: TunWritePacket) {
        packet.length = 0
        available.offer(packet)
    }

    internal fun cachedBufferCount(): Int = available.size
}

internal class TunWritePacket internal constructor(
    val buffer: ByteArray,
    var length: Int,
    private val owner: TunPacketBufferPool?,
) {
    internal constructor(buffer: ByteArray) : this(
        buffer = buffer,
        length = buffer.size,
        owner = null,
    )

    internal constructor(buffer: ByteArray, owner: TunPacketBufferPool) : this(
        buffer = buffer,
        length = 0,
        owner = owner,
    )

    init {
        require(length in 0..buffer.size) { "Packet length must fit its buffer" }
    }

    internal fun recycle() {
        owner?.recycle(this)
    }
}

internal class TunPacketWriter(
    private val output: OutputStream,
    queueCapacity: Int,
    private val enqueueTimeoutMs: Long,
    private val isRunning: () -> Boolean,
    private val onFailure: (String) -> Unit,
) {
    private val queue = ArrayBlockingQueue<TunWritePacket>(queueCapacity)
    private val writerRunning = AtomicBoolean(false)
    private val stoppedLatch = CountDownLatch(1)

    @Volatile
    private var thread: Thread? = null

    fun start() {
        if (!writerRunning.compareAndSet(false, true)) return
        thread = Thread(
            {
                try {
                    while (writerRunning.get()) {
                        val packet = queue.take()
                        try {
                            output.write(packet.buffer, 0, packet.length)
                        } finally {
                            packet.recycle()
                        }
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (error: Exception) {
                    if (writerRunning.get() && isRunning()) {
                        onFailure("TUN writer failed: ${error.message ?: error::class.java.simpleName}")
                    }
                } finally {
                    writerRunning.set(false)
                    drainAndRecycle()
                    runCatching { output.close() }
                    stoppedLatch.countDown()
                }
            },
            TUN_WRITER_THREAD_NAME,
        ).apply {
            isDaemon = true
            start()
        }
    }

    /** Diagnostics only: how much is waiting to reach the TUN right now. */
    fun queuedPackets(): Int = queue.size

    fun enqueue(packet: ByteArray): Boolean {
        return enqueue(TunWritePacket(packet))
    }

    fun enqueue(packet: TunWritePacket): Boolean {
        if (packet.length !in 1..packet.buffer.size) {
            packet.recycle()
            return false
        }
        if (!writerRunning.get()) {
            packet.recycle()
            return false
        }
        return try {
            val accepted = queue.offer(packet) ||
                queue.offer(packet, enqueueTimeoutMs, TimeUnit.MILLISECONDS)
            if (!accepted) {
                packet.recycle()
                false
            } else if (!writerRunning.get() && queue.remove(packet)) {
                // stop() may race an enqueue that observed the previous running state.
                packet.recycle()
                false
            } else {
                true
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            packet.recycle()
            false
        }
    }

    /** Enqueues with a bounded wait; the packet is dropped instead of stalling the caller. */
    fun offerWithin(packet: TunWritePacket, waitMs: Long): Boolean {
        if (packet.length !in 1..packet.buffer.size || !writerRunning.get()) {
            packet.recycle()
            return false
        }
        val accepted = try {
            queue.offer(packet) || (waitMs > 0L && queue.offer(packet, waitMs, TimeUnit.MILLISECONDS))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!accepted) {
            packet.recycle()
            return false
        }
        if (!writerRunning.get() && queue.remove(packet)) {
            packet.recycle()
            return false
        }
        return true
    }

    fun stop() {
        writerRunning.set(false)
        thread?.interrupt()
        runCatching { output.close() }
        drainAndRecycle()
    }

    internal fun awaitStopped(timeoutMs: Long): Boolean {
        return stoppedLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun drainAndRecycle() {
        while (true) {
            val packet = queue.poll() ?: return
            packet.recycle()
        }
    }
}

internal class KotlinTunForwarder(
    private val vpnInterface: ParcelFileDescriptor,
    sshSession: Session,
    private val log: (String) -> Unit,
    private val onDegraded: (reason: String, transportGeneration: Long?) -> Unit = { _, _ -> },
    private val onTransportSuspect: () -> Unit = {},
    /** Stall episode started (true) or cleared (false); the service probes the SSH link on a start. */
    private val onTransportStall: (active: Boolean) -> Unit = {},
    /** A client opened a TCP flow; lets the service probe a link that has been silent for a while. */
    private val onNewFlow: () -> Unit = {},
    private val config: TunForwarderConfig,
) {
    private val running = AtomicBoolean(false)
    private val stoppedLatch = CountDownLatch(1)
    private val sessions = ConcurrentHashMap<TcpKey, TcpProxySession>()
    private val activeDnsChannels = ConcurrentHashMap.newKeySet<ChannelDirectTCPIP>()
    private val udpSessions = ConcurrentHashMap<UdpKey, UdpProxySession>()
    private val udpRelayCooldowns = ConcurrentHashMap<UdpRelayTarget, Long>()
    private val udpRelayTcpPorts = ConcurrentHashMap<Int, Int>()
    private val udpRejectedFlows = ConcurrentHashMap<UdpRejectKey, Long>()
    private val dnsCache = DnsResponseCache(nowMs = ::elapsedRealtimeMs)
    private val transportLossReportedAtMs = AtomicLong(0L)

    @Volatile
    private var lastTunReadAtMs = 0L

    @Volatile
    private var stallStartedAtMs = 0L

    /** Transport generation the current stall episode was reported for; the watchdog thread only. */
    private var stallTransportGeneration = 0L

    private val udpRejectionSummary = UdpRejectionSummary()
    private val reflectorVerdictLogged = AtomicBoolean(false)
    private val sshSessionReference = AtomicReference<Session?>(sshSession)
    private val transportLock = Any()
    private var transportGeneration = 0L
    private val diagnostics = ForwarderDiagnostics(log)
    private val executors = ForwarderExecutors(
        diagnostics = diagnostics,
        // Closed flows keep their open until it times out, so a table's worth of those comes on top.
        maxConcurrentConnects = 2 * config.maxActiveTcpSessions + CONNECT_THREAD_SLACK,
    )
    private val outboundPacketPool = TunPacketBufferPool(
        bufferSize = config.tunMtu,
        capacity = config.outboundPacketPoolCapacity,
    )
    private val tcpPacketSender = object : TcpPacketSender {
        override fun send(
            sourceAddress: Int,
            destinationAddress: Int,
            sourcePort: Int,
            destinationPort: Int,
            sequence: Long,
            acknowledgement: Long,
            flags: Int,
            advertisedWindow: Int,
            payload: ByteArray,
            payloadOffset: Int,
            payloadLength: Int,
        ) {
            sendTcpPacket(
                sourceAddress = sourceAddress,
                destinationAddress = destinationAddress,
                sourcePort = sourcePort,
                destinationPort = destinationPort,
                sequence = sequence,
                acknowledgement = acknowledgement,
                flags = flags,
                advertisedWindow = advertisedWindow,
                payload = payload,
                payloadOffset = payloadOffset,
                payloadLength = payloadLength,
            )
        }
    }
    private val udpDatagramSender = object : UdpDatagramSender {
        override fun send(
            sourceAddress: Int,
            destinationAddress: Int,
            sourcePort: Int,
            destinationPort: Int,
            payload: ByteArray,
        ) {
            sendUdpPacket(
                sourceAddress = sourceAddress,
                destinationAddress = destinationAddress,
                sourcePort = sourcePort,
                destinationPort = destinationPort,
                payload = payload,
                bestEffort = true,
            )
        }
    }
    private val packetId = AtomicInteger(1)
    private val dnsFailureStreak = AtomicInteger(0)

    /** Resolvers that did not answer through the current transport, and until when to skip them. */
    private val silentResolvers = ConcurrentHashMap<Int, Long>()
    private val degradationReported = AtomicBoolean(false)
    private val terminalError = AtomicReference<String?>(null)
    private val pressureCleanupScheduled = AtomicBoolean(false)

    private val tunWriterReference = AtomicReference<TunPacketWriter?>()

    @Volatile
    private var readThread: Thread? = null

    // Only a ceiling against a UDP flood filling the TUN writer queue; per-flow pacing below is
    // what decides who gets an answer.
    private val udpRejectLimiter = TokenBucketRateLimiter(
        capacity = UDP_REJECT_BURST,
        refillIntervalMs = UDP_REJECT_REFILL_INTERVAL_MS,
    )

    fun start(onStopped: (String) -> Unit) {
        if (!running.compareAndSet(false, true)) return

        val input = FileInputStream(vpnInterface.fileDescriptor)
        val writer = TunPacketWriter(
            output = FileOutputStream(vpnInterface.fileDescriptor),
            queueCapacity = config.tunWriteQueueCapacity,
            enqueueTimeoutMs = config.tunWriteEnqueueTimeoutMs,
            isRunning = running::get,
            onFailure = ::failForwarder,
        )
        tunWriterReference.set(writer)
        writer.start()
        lastTunReadAtMs = elapsedRealtimeMs()
        diagnostics.markRemoteRead(lastTunReadAtMs)
        scheduleStallWatchdog()
        log(
            "Kotlin TUN optimized datapath: mtu=${config.tunMtu}, mss=${config.tcpMss}, " +
                "sshWindow=${config.sshChannelWindowBytes}B, " +
                "uploadQueue=${config.maxPendingUploadBytesPerFlow}B/flow, " +
                "sessions=${config.maxActiveTcpSessions}, tunWriter=${config.tunWriteQueueCapacity} packets, " +
                "dnsTimeout=${config.dnsQueryTimeoutMs}ms, " +
                "voipUdpRelay=$HARD_MAX_ACTIVE_UDP_RELAY_SESSIONS flows over reflector TCP $REFLECTOR_TCP_PORT, " +
                "dnsCache=${DnsResponseCache.DEFAULT_MAX_ENTRIES} answers",
        )
        readThread = Thread(
            {
                var stopReason = "Stopped"
                try {
                    readTunLoop(input)
                } catch (error: Exception) {
                    stopReason = if (running.get()) {
                        error.message ?: error::class.java.simpleName
                    } else {
                        "Stopped"
                    }
                } finally {
                    running.set(false)
                    closeSessions()
                    closeActiveDnsChannels()
                    closeUdpSessions()
                    executors.shutdownNow()
                    runCatching { input.close() }
                    tunWriterReference.getAndSet(null)?.stop()
                    stoppedLatch.countDown()
                    stopReason = terminalError.get() ?: stopReason
                    onStopped(stopReason)
                }
            },
            READ_THREAD_NAME,
        ).apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        closeSessions()
        closeActiveDnsChannels()
        closeUdpSessions()
        tunWriterReference.getAndSet(null)?.stop()
        runCatching { vpnInterface.close() }
        executors.shutdownNow()
    }

    fun awaitStopped(timeoutMs: Long) {
        stoppedLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    fun pauseSshTransport() {
        synchronized(transportLock) {
            transportGeneration += 1
            sshSessionReference.set(null)
            resetDnsFailureStateLocked()
            closeSessions(resetClients = true)
            closeActiveDnsChannels()
            closeUdpSessions()
        }
    }

    fun resumeSshTransport(sshSession: Session) {
        check(sshSession.isConnected) { "SSH session is not connected" }
        synchronized(transportLock) {
            transportGeneration += 1
            resetDnsFailureStateLocked()
            closeSessions()
            closeActiveDnsChannels()
            closeUdpSessions()
            sshSessionReference.set(sshSession)
        }
    }

    fun isDegradationSignalCurrent(expectedTransportGeneration: Long?): Boolean {
        if (expectedTransportGeneration == null) return running.get()
        return synchronized(transportLock) {
            running.get() &&
                transportGeneration == expectedTransportGeneration &&
                sshSessionReference.get()?.isConnected == true
        }
    }

    private fun requestPressureCleanup(delayMs: Long = PRESSURE_CLEANUP_INITIAL_DELAY_MS) {
        if (!running.get() || sessions.size <= config.sessionPressureThreshold) return
        if (!pressureCleanupScheduled.compareAndSet(false, true)) return
        val scheduled = executors.scheduleCleanup(delayMs) {
            pressureCleanupScheduled.set(false)
            if (!running.get()) return@scheduleCleanup
            runPressureCleanup()
            if (sessions.size > config.sessionPressureThreshold) {
                requestPressureCleanup(PRESSURE_CLEANUP_RECHECK_MS)
            }
        }
        if (scheduled == null) {
            pressureCleanupScheduled.set(false)
        }
    }

    /**
     * An idle keep-alive is not a leak. Browsers and messengers park connections for minutes and
     * expect to reuse them, and closing one costs the user a visible reconnect - a chat that says
     * "connecting", a page that reloads. So pressure alone buys a long deadline, and the short one
     * is kept for the case that actually hurts: a full table, where the next flow would be refused.
     */
    private fun runPressureCleanup() {
        val now = elapsedRealtimeMs()
        val activeCount = sessions.size
        if (activeCount <= config.sessionPressureThreshold) return

        val idleTtlMs = if (activeCount >= config.maxActiveTcpSessions) {
            SATURATED_IDLE_SESSION_TTL_MS
        } else {
            PRESSURE_IDLE_SESSION_TTL_MS
        }
        val maxToClose = activeCount - config.sessionPressureTarget
        var closedCount = 0
        sessions.values
            .asSequence()
            .map { session -> session to session.idleForMs(now) }
            .filter { (_, idleForMs) -> idleForMs >= idleTtlMs }
            .sortedByDescending { (_, idleForMs) -> idleForMs }
            .forEach { (session, _) ->
                if (closedCount >= maxToClose) return@forEach
                if (session.closeIdleUnderPressure(now, idleTtlMs)) {
                    closedCount += 1
                }
            }
        if (closedCount > 0) {
            diagnostics.logPressureCleanup(
                closedCount = closedCount,
                activeCount = activeCount,
                maxSessions = config.maxActiveTcpSessions,
                idleTtlMs = idleTtlMs,
            )
        }
    }

    private fun readTunLoop(input: FileInputStream) {
        val buffer = ByteArray(config.tunMtu)
        while (running.get()) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) {
                sleepAfterEmptyRead()
                continue
            }
            lastTunReadAtMs = elapsedRealtimeMs()
            handleIpv4Packet(buffer, read)
        }
    }

    /**
     * "Everything freezes for a moment and then comes back", with nothing in the log: no reconnect,
     * no rebuild, no error - so the stall is inside the data path and nothing was reporting it.
     * This does: how long the tunnel has been silent while apps were still sending, how many flows
     * are parked waiting for their client to open its receive window, and how full the TUN writer
     * queue is. Those three numbers separate a wedged SSH session from a slow peer.
     */
    private fun scheduleStallWatchdog() {
        if (!running.get()) return
        // Nothing to watch while no flow is open, and a twice-a-second timer for diagnostics has no
        // business waking a sleeping device.
        val delayMs = if (sessions.isEmpty()) STALL_WATCHDOG_IDLE_INTERVAL_MS else STALL_WATCHDOG_INTERVAL_MS
        val scheduled = executors.scheduleQuietCleanup(delayMs) {
            try {
                runStallWatchdog()
            } finally {
                scheduleStallWatchdog()
            }
        }
        if (scheduled == null) {
            stallStartedAtMs = 0L
        }
    }

    private fun runStallWatchdog() {
        if (!running.get()) return
        // Read before the sessions: a pause that lands after this closes them, and the next tick sees it.
        val generation = synchronized(transportLock) { transportGeneration }
        val nowMs = elapsedRealtimeMs()
        val activeSessions = sessions.size
        val silentForMs = nowMs - diagnostics.lastRemoteReadAtMs()
        val clientQuietForMs = nowMs - lastTunReadAtMs
        // Apps still sending plus nothing at all coming back from any channel is the shape of a
        // stall; an idle tunnel is quiet in both directions and says nothing.
        val awaitingClientWindow = diagnostics.clientWindowWaitCount()
        val writerQueueDepth = tunWriterReference.get()?.queuedPackets() ?: 0
        // Silence alone is not a stall: an upload legitimately receives nothing for seconds. What
        // makes it one is silence while something is visibly stuck - a flow parked on a closed
        // client window, or a TUN writer queue that is backing up.
        val stalled = activeSessions > 0 &&
            silentForMs >= STALL_SILENCE_THRESHOLD_MS &&
            clientQuietForMs <= STALL_CLIENT_ACTIVITY_MS &&
            (awaitingClientWindow > 0 || writerQueueDepth >= config.tunWriteQueueCapacity / 2)
        if (stalled) {
            // A stall that outlives a hot reconnect is news about the new transport: report it again,
            // or the service, which forgets stalls with the old link, would never hear of it.
            if (stallStartedAtMs == 0L || stallTransportGeneration != generation) {
                stallStartedAtMs = nowMs
                stallTransportGeneration = generation
                diagnostics.logTunStall(
                    silentForMs = silentForMs,
                    activeSessions = activeSessions,
                    awaitingClientWindow = awaitingClientWindow,
                    writerQueueDepth = writerQueueDepth,
                    writerQueueCapacity = config.tunWriteQueueCapacity,
                )
                notifyTransportStall(active = true)
            }
            return
        }
        if (stallStartedAtMs != 0L) {
            diagnostics.logTunStallCleared(nowMs - stallStartedAtMs)
            stallStartedAtMs = 0L
            notifyTransportStall(active = false)
        }
    }

    private fun notifyTransportStall(active: Boolean) {
        runCatching { onTransportStall(active) }
    }

    fun activeTcpSessionCount(): Int = sessions.size

    private fun handleIpv4Packet(buffer: ByteArray, length: Int) {
        val packet = PacketCodec.parseIpv4(buffer, length) ?: return
        when (packet.protocol) {
            PROTOCOL_TCP -> handleTcpPacket(buffer, packet)
            PROTOCOL_UDP -> handleUdpPacket(buffer, packet)
        }
    }

    private fun handleTcpPacket(buffer: ByteArray, packet: Ipv4Packet) {
        val tcp = PacketCodec.parseTcp(buffer, packet) ?: return
        val key = TcpKey(
            clientAddress = packet.source,
            clientPort = tcp.sourcePort,
            remoteAddress = packet.destination,
            remotePort = tcp.destinationPort,
        )
        if (tcp.flags.hasFlag(TCP_RST)) {
            sessions.remove(key)?.close()
            return
        }

        val isInitialSyn = tcp.flags.hasFlag(TCP_SYN) && !tcp.flags.hasFlag(TCP_ACK)
        var waitForSshTransport = false
        val session = if (isInitialSyn) {
            val created = synchronized(transportLock) {
                val activeSshSession = sshSessionReference.get()?.takeIf { it.isConnected }
                    ?: run {
                        waitForSshTransport = true
                        return@synchronized null
                    }
                if (!sessions.containsKey(key) && sessions.size >= config.maxActiveTcpSessions) {
                    diagnostics.logSessionLimit(config.maxActiveTcpSessions)
                    return@synchronized null
                }
                sessions.computeIfAbsent(key) {
                    TcpProxySession(
                        key = key,
                        sshSession = activeSshSession,
                        executors = executors,
                        diagnostics = diagnostics,
                        packetSender = tcpPacketSender,
                        activeSessionCount = sessions::size,
                        onClosed = { closedKey, closedSession ->
                            removeExpectedConcurrentEntry(sessions, closedKey, closedSession)
                        },
                        config = config,
                    )
                }
            }
            created
        } else {
            sessions[key]
        }

        if (session == null) {
            if (isInitialSyn && waitForSshTransport) {
                noticeTransportLoss()
                return
            }
            sendTcpReset(packet, tcp)
            return
        }

        val packetActivityAtMs = elapsedRealtimeMs()
        session.recordPacketActivity(packetActivityAtMs)
        if (isInitialSyn) {
            session.onSyn(tcp.sequence)
            requestPressureCleanup()
            // Only a hint, rate-limited on the other side; the read thread must never wait on it.
            runCatching { onNewFlow() }
        }

        if (tcp.flags.hasFlag(TCP_ACK)) {
            session.onAck(tcp.acknowledgement, tcp.window)
        }
        if (tcp.payloadLength > 0) {
            session.onClientData(
                sequence = tcp.sequence,
                source = buffer,
                sourceOffset = tcp.payloadOffset,
                byteCount = tcp.payloadLength,
            )
        }
        if (tcp.flags.hasFlag(TCP_FIN)) {
            session.onClientFin(
                finSequence = seqPlus(tcp.sequence, tcp.payloadLength),
                receivedAtMs = packetActivityAtMs,
            )
        }
    }

    private fun handleUdpPacket(buffer: ByteArray, packet: Ipv4Packet) {
        val udp = PacketCodec.parseUdp(buffer, packet) ?: return
        if (udp.destinationPort != DNS_PORT) {
            if (forwardVoipDatagram(buffer, packet, udp) == UdpRelayDecision.NOT_RELAYABLE) {
                rejectUnsupportedUdp(buffer, packet, udp)
            }
            return
        }
        val payload = buffer.copyOfRange(udp.payloadOffset, udp.payloadOffset + udp.payloadLength)
        val deadlineAtMs = elapsedRealtimeMs() + config.dnsQueryTimeoutMs
        val scheduled = executors.executeDns {
            forwardDnsQuery(
                query = payload,
                clientAddress = packet.source,
                clientPort = udp.sourcePort,
                dnsServerAddress = packet.destination,
                deadlineAtMs = deadlineAtMs,
            )
        }
        if (!scheduled) {
            val message = "forwarder DNS queue is saturated"
            currentDnsTransport()?.let { transport ->
                if (recordDnsFailure(message, transport)) {
                    diagnostics.logDnsFailure(message)
                }
            }
        }
    }

    /**
     * SSH `direct-tcpip` cannot carry UDP, so VoIP reflector flows are relayed over the reflector's
     * own MTProto TCP transport. Everything else stays unsupported and is rejected below.
     */
    private fun forwardVoipDatagram(
        buffer: ByteArray,
        packet: Ipv4Packet,
        udp: UdpPacket,
    ): UdpRelayDecision {
        if (udp.payloadLength <= 0) return UdpRelayDecision.NOT_RELAYABLE
        if (!TelegramNetworks.containsIpv4(packet.destination)) return UdpRelayDecision.NOT_RELAYABLE
        val key = UdpKey(
            clientAddress = packet.source,
            clientPort = udp.sourcePort,
            remoteAddress = packet.destination,
            remotePort = udp.destinationPort,
        )
        // A missing session means the SSH transport is down or the flow cap is reached. The packet is
        // dropped like the TCP path drops SYNs while reconnecting: answering ICMP would make the
        // client mark a perfectly good reflector as dead.
        val session = udpSessions[key] ?: run {
            if (isUdpRelayCoolingDown(packet.destination, udp.destinationPort)) {
                return UdpRelayDecision.NOT_RELAYABLE
            }
            createUdpSession(key)
        } ?: return UdpRelayDecision.DEFERRED
        val payload = buffer.copyOfRange(udp.payloadOffset, udp.payloadOffset + udp.payloadLength)
        return if (session.onClientDatagram(payload)) {
            UdpRelayDecision.ACCEPTED
        } else {
            UdpRelayDecision.DEFERRED
        }
    }

    private fun createUdpSession(key: UdpKey): UdpProxySession? {
        // scheduleIdleCleanup() can close the session, and closing removes it from udpSessions,
        // so it must never run inside the computeIfAbsent mapping function.
        var createdSession: UdpProxySession? = null
        val session = synchronized(transportLock) {
            val activeSshSession = sshSessionReference.get()?.takeIf { it.isConnected } ?: return null
            if (!udpSessions.containsKey(key) && udpSessions.size >= HARD_MAX_ACTIVE_UDP_RELAY_SESSIONS) {
                diagnostics.logUdpRelayLimit(HARD_MAX_ACTIVE_UDP_RELAY_SESSIONS)
                return null
            }
            udpSessions.computeIfAbsent(key) {
                UdpProxySession(
                    key = key,
                    sshSession = activeSshSession,
                    executors = executors,
                    diagnostics = diagnostics,
                    datagramSender = udpDatagramSender,
                    maxDatagramBytes = config.tunMtu - IPV4_MIN_HEADER_SIZE - UDP_HEADER_SIZE,
                    sshChannelWindowBytes = config.sshChannelWindowBytes,
                    relayPorts = relayTcpPortPolicy(key),
                    isForwarderRunning = running::get,
                    onConnectFailed = ::onUdpRelayConnectFailed,
                    onClosed = { closedKey, closedSession ->
                        removeExpectedConcurrentEntry(udpSessions, closedKey, closedSession)
                    },
                ).also { created -> createdSession = created }
            }
        }
        createdSession?.scheduleIdleCleanup()
        return session
    }

    private fun isUdpRelayCoolingDown(address: Int, port: Int): Boolean {
        val target = UdpRelayTarget(address, port)
        val retryAfterMs = udpRelayCooldowns[target] ?: return false
        if (elapsedRealtimeMs() < retryAfterMs) return true
        udpRelayCooldowns.remove(target, retryAfterMs)
        return false
    }

    /**
     * Reflectors answer the TCP transport on 443 only. A SYN to the UDP media port is dropped by
     * Telegram itself - it behaves the same from the SSH server and from unrelated networks - so
     * 443 is tried first and the media port stays as a second candidate for servers that do
     * forward it. The port that answered is remembered for the rest of the transport, so later
     * flows to the same reflector do not pay for the dead candidate again.
     */
    private fun relayTcpPortPolicy(key: UdpKey): RelayTcpPortPolicy {
        val candidates = linkedSetOf<Int>()
        udpRelayTcpPorts[key.remoteAddress]?.let { confirmed -> candidates.add(confirmed) }
        candidates.add(REFLECTOR_TCP_PORT)
        candidates.add(key.remotePort)
        return RelayTcpPortPolicy(candidates.toList()) { confirmed ->
            udpRelayTcpPorts[key.remoteAddress] = confirmed
        }
    }

    /**
     * A reflector that answered on none of its TCP ports is not going to answer on the next
     * datagram either, and every retry costs a channel timeout per candidate. Park the destination
     * and let the client fall back to whatever transport it has left.
     */
    private fun onUdpRelayConnectFailed(key: UdpKey) {
        val target = UdpRelayTarget(key.remoteAddress, key.remotePort)
        udpRelayCooldowns[target] = elapsedRealtimeMs() + UDP_RELAY_FAILURE_COOLDOWN_MS
        diagnostics.logUdpRelayDisabled(
            host = addressToString(key.remoteAddress),
            port = key.remotePort,
            cooldownMs = UDP_RELAY_FAILURE_COOLDOWN_MS,
        )
        // Every reflector port refused or dropped the connection, so the verdict is about the SSH
        // server's route to Telegram's VoIP range, not about this one flow. Said once per transport.
        if (reflectorVerdictLogged.compareAndSet(false, true)) {
            diagnostics.logReflectorUnreachable()
        }
    }

    /**
     * SSH cannot carry this datagram, and the client only learns that from the ICMP answer: a
     * connected UDP socket - QUIC, most game clients - surfaces it as `ECONNREFUSED` and gives up
     * on UDP at once, while silence costs it a full handshake timeout before it tries TCP. So the
     * first datagram of every flow is answered, repeats are answered once a second, and the global
     * bucket is only there to keep a flood from filling the TUN writer queue.
     */
    private fun rejectUnsupportedUdp(
        buffer: ByteArray,
        packet: Ipv4Packet,
        udp: UdpPacket,
    ) {
        val nowMs = elapsedRealtimeMs()
        val key = UdpRejectKey(
            clientAddress = packet.source,
            clientPort = udp.sourcePort,
            remoteAddress = packet.destination,
            remotePort = udp.destinationPort,
        )
        val lastRejectedAtMs = udpRejectedFlows[key]
        if (lastRejectedAtMs != null && nowMs - lastRejectedAtMs < UDP_REJECT_FLOW_INTERVAL_MS) return
        if (!udpRejectLimiter.tryAcquire(nowMs)) return
        rememberRejectedFlow(key, nowMs)
        if (lastRejectedAtMs == null) {
            diagnostics.logUnsupportedUdp(
                host = addressToString(packet.destination),
                port = udp.destinationPort,
            )
            udpRejectionSummary.recordFlow(udp.destinationPort)
        }
        udpRejectionSummary.takeDueSummary(nowMs)?.let(diagnostics::logUdpRejectionSummary)
        sendIcmpPortUnreachable(buffer, packet)
    }

    /**
     * The table is rate-limiter memory, not state anything depends on, so it is bounded by dropping
     * stale entries first and by starting over if a flood outruns even that.
     */
    private fun rememberRejectedFlow(key: UdpRejectKey, nowMs: Long) {
        if (udpRejectedFlows.size >= MAX_TRACKED_REJECTED_UDP_FLOWS) {
            udpRejectedFlows.entries.removeAll { entry -> nowMs - entry.value >= UDP_REJECT_FLOW_TTL_MS }
            if (udpRejectedFlows.size >= MAX_TRACKED_REJECTED_UDP_FLOWS) {
                udpRejectedFlows.clear()
            }
        }
        udpRejectedFlows[key] = nowMs
    }

    private fun sendIcmpPortUnreachable(
        originalBuffer: ByteArray,
        originalPacket: Ipv4Packet,
    ) {
        val returnedPacketLength = minOf(
            originalPacket.totalLength,
            originalPacket.headerLength + ICMP_ORIGINAL_TRANSPORT_BYTES,
        )
        val returnedPacket = originalBuffer.copyOfRange(0, returnedPacketLength)
        val icmpPayload = PacketCodec.buildIcmpDestinationUnreachable(
            code = ICMP_CODE_PORT_UNREACHABLE,
            returnedPacket = returnedPacket,
        )
        writeControlPacket(
            PacketCodec.buildIpv4Packet(
                id = packetId.getAndIncrement(),
                protocol = PROTOCOL_ICMP,
                sourceAddress = originalPacket.destination,
                destinationAddress = originalPacket.source,
                payload = icmpPayload,
            ),
        )
    }

    private fun forwardDnsQuery(
        query: ByteArray,
        clientAddress: Int,
        clientPort: Int,
        dnsServerAddress: Int,
        deadlineAtMs: Long,
    ) {
        if (!running.get()) return
        val cacheKey = DnsMessage.questionKey(query, dnsServerAddress)
        if (cacheKey != null) {
            val cached = dnsCache.lookup(cacheKey, query)
            if (cached != null) {
                diagnostics.logDnsCacheHit()
                sendUdpPacket(
                    sourceAddress = dnsServerAddress,
                    destinationAddress = clientAddress,
                    sourcePort = DNS_PORT,
                    destinationPort = clientPort,
                    payload = cached,
                )
                return
            }
        }
        val transport = currentDnsTransport() ?: run {
            noticeTransportLoss()
            return
        }
        val sshSession = transport.session
        val upstreams = TunnelDnsPolicy.upstreamsFor(dnsServerAddress, ::isResolverSilent)
        try {
            val response = resolveThroughTunnel(
                sshSession = sshSession,
                query = query,
                upstreams = upstreams,
                clientAddress = clientAddress,
                clientPort = clientPort,
                deadlineAtMs = deadlineAtMs,
                transportGeneration = transport.generation,
            )
            ensureDnsTransportActive(sshSession, transport.generation)
            if (cacheKey != null) {
                DnsMessage.cacheableTtlSeconds(response)?.let { ttlSeconds ->
                    dnsCache.store(cacheKey, response, ttlSeconds)
                }
            }
            // The answer comes from the address the app asked, whichever resolver gave it.
            sendUdpPacket(
                sourceAddress = dnsServerAddress,
                destinationAddress = clientAddress,
                sourcePort = DNS_PORT,
                destinationPort = clientPort,
                payload = response,
            )
            recordDnsSuccess(transport)
        } catch (error: Exception) {
            val message = error.message ?: error::class.java.simpleName
            val asked = upstreams.joinToString(", ") { upstream -> addressToString(upstream) }
            val failure = "${addressToString(dnsServerAddress)} (asked $asked): $message"
            if (recordDnsFailure(failure, transport)) {
                diagnostics.logDnsFailure(failure)
            }
        }
    }

    /**
     * DNS over TCP through the SSH session, one resolver after another, and nothing but port 53 on
     * the server's side. A resolver that does not answer within its share of the time is remembered
     * as silent, so the next queries go straight to the one that does.
     */
    private fun resolveThroughTunnel(
        sshSession: Session,
        query: ByteArray,
        upstreams: List<Int>,
        clientAddress: Int,
        clientPort: Int,
        deadlineAtMs: Long,
        transportGeneration: Long,
    ): ByteArray {
        var lastError: Exception? = null
        for ((index, upstream) in upstreams.withIndex()) {
            val startedAtMs = elapsedRealtimeMs()
            val remainingMs = deadlineAtMs - startedAtMs
            if (remainingMs <= 0L) break
            val isLast = index == upstreams.lastIndex
            val budgetMs = if (isLast) remainingMs else TunnelDnsPolicy.attemptBudgetMs(upstream, remainingMs)
            try {
                val response = resolveDnsOverTcp(
                    sshSession = sshSession,
                    query = query,
                    dnsServer = addressToString(upstream),
                    clientAddress = clientAddress,
                    clientPort = clientPort,
                    deadlineAtMs = startedAtMs + budgetMs,
                    transportGeneration = transportGeneration,
                )
                silentResolvers.remove(upstream)
                return response
            } catch (error: Exception) {
                lastError = error
                // A transport that changed under the query says nothing about the resolver.
                ensureDnsTransportActive(sshSession, transportGeneration)
                if (budgetMs >= TunnelDnsPolicy.MIN_SILENCE_EVIDENCE_MS) {
                    markResolverSilent(
                        resolver = upstream,
                        next = upstreams.getOrNull(index + 1),
                        message = error.message ?: error::class.java.simpleName,
                    )
                }
            }
        }
        throw lastError ?: SocketTimeoutException("DNS over SSH timed out after ${config.dnsQueryTimeoutMs}ms")
    }

    private fun isResolverSilent(resolver: Int): Boolean {
        val silentUntilMs = silentResolvers[resolver] ?: return false
        if (elapsedRealtimeMs() < silentUntilMs) return true
        silentResolvers.remove(resolver, silentUntilMs)
        return false
    }

    private fun markResolverSilent(resolver: Int, next: Int?, message: String) {
        val previous = silentResolvers.put(resolver, elapsedRealtimeMs() + TunnelDnsPolicy.SILENT_RESOLVER_COOLDOWN_MS)
        if (previous != null) return
        diagnostics.logSilentResolver(
            resolver = addressToString(resolver),
            next = next?.let(::addressToString),
            privateAddress = !TunnelDnsPolicy.isPublicUnicast(resolver),
            message = message,
        )
    }

    /**
     * An app needed the tunnel and the SSH session is gone with it. The service polls for that
     * every few seconds, so saying it now is the difference between a reconnect nobody notices and
     * one that freezes every app until the next poll. Throttled: a stalled device retries a lot.
     */
    private fun noticeTransportLoss() {
        // Paused included: that is when the service waits out a backoff, and an app asking for the
        // tunnel is exactly the reason to stop waiting.
        if (!running.get()) return
        if (sshSessionReference.get()?.isConnected == true) return
        val nowMs = elapsedRealtimeMs()
        val lastReportedAtMs = transportLossReportedAtMs.get()
        if (nowMs - lastReportedAtMs < TRANSPORT_LOSS_REPORT_INTERVAL_MS) return
        if (!transportLossReportedAtMs.compareAndSet(lastReportedAtMs, nowMs)) return
        diagnostics.logTransportLost()
        onTransportSuspect()
    }

    private fun currentDnsTransport(): DnsTransport? = synchronized(transportLock) {
        val activeSession = sshSessionReference.get()?.takeIf { it.isConnected }
            ?: return@synchronized null
        DnsTransport(activeSession, transportGeneration)
    }

    private fun resolveDnsOverTcp(
        sshSession: Session,
        query: ByteArray,
        dnsServer: String,
        clientAddress: Int,
        clientPort: Int,
        deadlineAtMs: Long,
        transportGeneration: Long,
    ): ByteArray {
        var channel: ChannelDirectTCPIP? = null
        try {
            channel = createDirectTcpChannel(
                sshSession = sshSession,
                host = dnsServer,
                port = DNS_PORT,
                originAddress = clientAddress,
                originPort = clientPort,
            )
            activeDnsChannels += channel
            ensureDnsTransportActive(sshSession, transportGeneration)
            return withDnsChannelDeadline(channel, deadlineAtMs) { remainingTimeoutMs ->
                ensureDnsTransportActive(sshSession, transportGeneration)
                val input = channel.inputStream
                val output = channel.outputStream
                channel.connect(minOf(SSH_CHANNEL_CONNECT_TIMEOUT_MS.toLong(), remainingTimeoutMs).toInt())
                ensureDnsTransportActive(sshSession, transportGeneration)
                output.write((query.size ushr 8) and 0xFF)
                output.write(query.size and 0xFF)
                output.write(query)
                output.flush()

                val lengthPrefix = ByteArray(DNS_TCP_LENGTH_SIZE)
                readFully(input, lengthPrefix)
                val responseLength = PacketCodec.readU16(lengthPrefix, 0)
                if (responseLength <= 0 || responseLength > DNS_MAX_RESPONSE_SIZE) {
                    throw EOFException("Invalid DNS TCP response length: $responseLength")
                }

                ByteArray(responseLength).also { response -> readFully(input, response) }
            }
        } finally {
            channel?.let(activeDnsChannels::remove)
            channel?.disconnect()
        }
    }

    private fun recordDnsSuccess(expectedTransport: DnsTransport) {
        synchronized(transportLock) {
            if (isDnsTransportActiveLocked(expectedTransport)) {
                resetDnsFailureStateLocked()
            }
        }
    }

    private fun recordDnsFailure(
        message: String,
        expectedTransport: DnsTransport,
    ): Boolean {
        val (failures, shouldReport) = synchronized(transportLock) {
            if (!isDnsTransportActiveLocked(expectedTransport)) {
                return false
            }
            val failures = dnsFailureStreak.incrementAndGet()
            failures to (
                failures >= DNS_DEGRADATION_FAILURE_THRESHOLD &&
                    degradationReported.compareAndSet(false, true)
                )
        }
        if (shouldReport) {
            log(
                "DNS failed $failures consecutive time(s); continuing per-query fallback " +
                    "without rebuilding VPN; last error: $message",
            )
        }
        return true
    }

    private fun resetDnsFailureStateLocked() {
        dnsFailureStreak.set(0)
        degradationReported.set(false)
        silentResolvers.clear()
    }

    private fun isDnsTransportActiveLocked(expectedTransport: DnsTransport): Boolean {
        return running.get() &&
            transportGeneration == expectedTransport.generation &&
            sshSessionReference.get() === expectedTransport.session &&
            expectedTransport.session.isConnected
    }

    private fun sendTcpReset(
        packet: Ipv4Packet,
        tcp: TcpPacket,
    ) {
        val acknowledgement = seqPlus(tcp.sequence, tcp.payloadLength + if (tcp.flags.hasFlag(TCP_SYN)) 1 else 0)
        sendTcpPacket(
            sourceAddress = packet.destination,
            destinationAddress = packet.source,
            sourcePort = tcp.destinationPort,
            destinationPort = tcp.sourcePort,
            sequence = tcp.acknowledgement,
            acknowledgement = acknowledgement,
            flags = TCP_RST or TCP_ACK,
            payload = EMPTY_BYTES,
        )
    }

    private fun sendTcpPacket(
        sourceAddress: Int,
        destinationAddress: Int,
        sourcePort: Int,
        destinationPort: Int,
        sequence: Long,
        acknowledgement: Long,
        flags: Int,
        advertisedWindow: Int = MAX_TCP_WINDOW,
        payload: ByteArray,
        payloadOffset: Int = 0,
        payloadLength: Int = payload.size,
    ) {
        val packet = outboundPacketPool.acquire()
        try {
            packet.length = PacketCodec.buildIpv4TcpPacketInto(
                destination = packet.buffer,
                id = packetId.getAndIncrement(),
                sourceAddress = sourceAddress,
                destinationAddress = destinationAddress,
                sourcePort = sourcePort,
                destinationPort = destinationPort,
                sequence = sequence,
                acknowledgement = acknowledgement,
                flags = flags,
                advertisedWindow = advertisedWindow,
                tcpMss = config.tcpMss,
                payload = payload,
                payloadOffset = payloadOffset,
                payloadLength = payloadLength,
            )
        } catch (error: Exception) {
            packet.recycle()
            throw error
        }
        writePacket(packet)
    }

    private fun sendUdpPacket(
        sourceAddress: Int,
        destinationAddress: Int,
        sourcePort: Int,
        destinationPort: Int,
        payload: ByteArray,
        bestEffort: Boolean = false,
    ) {
        val udpDatagram = PacketCodec.buildUdpDatagram(
            sourceAddress = sourceAddress,
            destinationAddress = destinationAddress,
            sourcePort = sourcePort,
            destinationPort = destinationPort,
            payload = payload,
        )
        val ipPacket = PacketCodec.buildIpv4Packet(
            id = packetId.getAndIncrement(),
            protocol = PROTOCOL_UDP,
            sourceAddress = sourceAddress,
            destinationAddress = destinationAddress,
            payload = udpDatagram,
        )
        if (bestEffort) {
            writeControlPacket(ipPacket, TUN_RELAY_ENQUEUE_WAIT_MS)
        } else {
            writePacket(ipPacket)
        }
    }

    private fun writePacket(packet: ByteArray) {
        writePacket(TunWritePacket(packet))
    }

    /**
     * Best-effort write used by rejections and relayed VoIP datagrams: a media burst must never be
     * able to saturate the TUN writer long enough to tear the whole tunnel down.
     */
    private fun writeControlPacket(packet: ByteArray, waitMs: Long = 0L) {
        if (!running.get()) return
        val writer = tunWriterReference.get() ?: return
        if (!writer.offerWithin(TunWritePacket(packet), waitMs)) {
            diagnostics.logTunWriteDropped()
        }
    }

    private fun writePacket(packet: TunWritePacket) {
        if (!running.get()) {
            packet.recycle()
            return
        }
        val writer = tunWriterReference.get()
        if (writer == null) {
            packet.recycle()
            if (running.get()) {
                failForwarder("TUN writer is unavailable")
            }
            return
        }
        val enqueued = writer.enqueue(packet)
        if (!enqueued && running.get()) {
            failForwarder("TUN writer queue remained saturated for ${config.tunWriteEnqueueTimeoutMs}ms")
        }
    }

    private fun createDirectTcpChannel(
        sshSession: Session,
        host: String,
        port: Int,
        originAddress: Int,
        originPort: Int,
    ): ChannelDirectTCPIP {
        val channel = sshSession.openChannel("direct-tcpip") as ChannelDirectTCPIP
        channel.setHost(host)
        channel.setPort(port)
        channel.setOrgIPAddress(addressToString(originAddress))
        channel.setOrgPort(originPort)
        DirectTcpipChannelTuning.setLocalWindowSize(channel, config.sshChannelWindowBytes)
        return channel
    }

    private fun <T> withDnsChannelDeadline(
        channel: ChannelDirectTCPIP,
        deadlineAtMs: Long,
        operation: (remainingTimeoutMs: Long) -> T,
    ): T {
        val remainingTimeoutMs = (deadlineAtMs - elapsedRealtimeMs()).coerceAtLeast(0L)
        if (remainingTimeoutMs == 0L) {
            channel.disconnect()
            throw SocketTimeoutException("DNS query used up its ${config.dnsQueryTimeoutMs}ms before it was sent")
        }
        val timedOut = AtomicBoolean(false)
        val timeout = executors.scheduleDnsTimeout(remainingTimeoutMs) {
            timedOut.set(true)
            channel.disconnect()
        } ?: run {
            channel.disconnect()
            throw RejectedExecutionException("DNS timeout scheduler is unavailable")
        }
        try {
            val result = operation(remainingTimeoutMs)
            if (timedOut.get()) {
                throw SocketTimeoutException("DNS over SSH timed out after ${remainingTimeoutMs}ms")
            }
            return result
        } catch (error: Exception) {
            if (timedOut.get() && error !is SocketTimeoutException) {
                throw SocketTimeoutException("DNS over SSH timed out after ${remainingTimeoutMs}ms").apply {
                    initCause(error)
                }
            }
            throw error
        } finally {
            timeout.cancel(false)
        }
    }

    private fun ensureDnsTransportActive(
        sshSession: Session,
        expectedGeneration: Long,
    ) = synchronized(transportLock) {
        if (
            !running.get() ||
            transportGeneration != expectedGeneration ||
            sshSessionReference.get() !== sshSession ||
            !sshSession.isConnected
        ) {
            throw EOFException("SSH transport changed while opening DNS channel")
        }
    }

    private fun closeSessions(resetClients: Boolean = false) {
        sessions.values.forEach { session ->
            runCatching {
                if (resetClients) session.resetAndClose() else session.close()
            }
        }
        sessions.clear()
    }

    private fun closeUdpSessions() {
        udpSessions.values.forEach { session -> runCatching { session.close("forwarder shutdown") } }
        udpSessions.clear()
        // Reachability verdicts belong to the SSH transport that produced them.
        udpRelayCooldowns.clear()
        udpRelayTcpPorts.clear()
        udpRejectedFlows.clear()
        reflectorVerdictLogged.set(false)
    }

    private fun closeActiveDnsChannels() {
        activeDnsChannels.forEach { channel -> runCatching { channel.disconnect() } }
        activeDnsChannels.clear()
    }

    private fun failForwarder(reason: String) {
        if (!terminalError.compareAndSet(null, reason)) return
        running.set(false)
        closeSessions()
        closeActiveDnsChannels()
        closeUdpSessions()
        executors.shutdownNow()
        runCatching { vpnInterface.close() }
        tunWriterReference.getAndSet(null)?.stop()
    }

    private class ForwarderExecutors(
        private val diagnostics: ForwarderDiagnostics,
        maxConcurrentConnects: Int,
    ) {
        /**
         * Opening a `direct-tcpip` channel blocks until the SSH server has reached the destination -
         * up to [SSH_CHANNEL_CONNECT_TIMEOUT_MS] for a host it cannot reach. On the shared control
         * pool a handful of such hosts tied up every worker, and the uploads of every established
         * flow queued behind them: the whole tunnel froze. Opens get their own threads, one per flow
         * at most, so a dead destination only ever costs its own flow.
         */
        private val connectExecutor = ThreadPoolExecutor(
            0,
            maxConcurrentConnects,
            WORKER_KEEP_ALIVE_MS,
            TimeUnit.MILLISECONDS,
            SynchronousQueue(),
            NamedThreadFactory(CONNECT_THREAD_PREFIX),
            ThreadPoolExecutor.AbortPolicy(),
        )
        private val controlExecutor = ThreadPoolExecutor(
            CONTROL_WORKER_THREADS,
            CONTROL_WORKER_THREADS,
            WORKER_KEEP_ALIVE_MS,
            TimeUnit.MILLISECONDS,
            LinkedBlockingQueue(MAX_CONTROL_QUEUE_SIZE),
            NamedThreadFactory(CONTROL_THREAD_PREFIX),
            ThreadPoolExecutor.AbortPolicy(),
        ).apply {
            allowCoreThreadTimeOut(true)
        }
        private val dnsExecutor = ThreadPoolExecutor(
            DNS_WORKER_THREADS,
            DNS_WORKER_THREADS,
            WORKER_KEEP_ALIVE_MS,
            TimeUnit.MILLISECONDS,
            LinkedBlockingQueue(MAX_DNS_QUEUE_SIZE),
            NamedThreadFactory(DNS_THREAD_PREFIX),
            ThreadPoolExecutor.AbortPolicy(),
        ).apply {
            allowCoreThreadTimeOut(true)
        }
        private val remoteReadExecutor = ThreadPoolExecutor(
            MIN_REMOTE_READ_THREADS,
            MAX_REMOTE_READ_THREADS,
            WORKER_KEEP_ALIVE_MS,
            TimeUnit.MILLISECONDS,
            SynchronousQueue(),
            NamedThreadFactory(REMOTE_READ_THREAD_PREFIX),
            ThreadPoolExecutor.AbortPolicy(),
        )
        private val udpRelayExecutor = ThreadPoolExecutor(
            UDP_RELAY_WORKER_THREADS,
            UDP_RELAY_WORKER_THREADS,
            WORKER_KEEP_ALIVE_MS,
            TimeUnit.MILLISECONDS,
            LinkedBlockingQueue(MAX_UDP_RELAY_QUEUE_SIZE),
            NamedThreadFactory(UDP_RELAY_THREAD_PREFIX),
            ThreadPoolExecutor.AbortPolicy(),
        ).apply {
            allowCoreThreadTimeOut(true)
        }
        private val cleanupExecutor = ScheduledThreadPoolExecutor(
            CLEANUP_WORKER_THREADS,
            NamedThreadFactory(CLEANUP_THREAD_PREFIX),
        ).apply {
            removeOnCancelPolicy = true
            setKeepAliveTime(CLEANUP_WORKER_KEEP_ALIVE_MS, TimeUnit.MILLISECONDS)
            allowCoreThreadTimeOut(true)
        }
        private val dnsTimeoutExecutor = ScheduledThreadPoolExecutor(
            DNS_TIMEOUT_WORKER_THREADS,
            NamedThreadFactory(DNS_TIMEOUT_THREAD_PREFIX),
        ).apply {
            removeOnCancelPolicy = true
            setKeepAliveTime(WORKER_KEEP_ALIVE_MS, TimeUnit.MILLISECONDS)
            allowCoreThreadTimeOut(true)
        }

        fun executeControl(task: () -> Unit): Boolean {
            return execute(CONTROL_POOL_NAME, controlExecutor, task)
        }

        fun executeConnect(task: () -> Unit): Boolean {
            return execute(CONNECT_POOL_NAME, connectExecutor, task)
        }

        fun executeDns(task: () -> Unit): Boolean {
            return execute(DNS_POOL_NAME, dnsExecutor, task)
        }

        fun executeUdpRelay(task: () -> Unit): Boolean {
            return execute(UDP_RELAY_POOL_NAME, udpRelayExecutor, task)
        }

        fun executeRemoteRead(task: () -> Unit): Boolean {
            return execute(REMOTE_READ_POOL_NAME, remoteReadExecutor, task)
        }

        fun scheduleCleanup(
            delayMs: Long,
            task: () -> Unit,
        ): ScheduledFuture<*>? {
            return try {
                cleanupExecutor.schedule({ task() }, delayMs, TimeUnit.MILLISECONDS)
            } catch (_: RejectedExecutionException) {
                diagnostics.logWorkerRejected(CLEANUP_POOL_NAME)
                null
            }
        }

        /** Like [scheduleCleanup], but a rejection during teardown is not worth a log line. */
        fun scheduleQuietCleanup(
            delayMs: Long,
            task: () -> Unit,
        ): ScheduledFuture<*>? {
            return try {
                cleanupExecutor.schedule({ task() }, delayMs, TimeUnit.MILLISECONDS)
            } catch (_: RejectedExecutionException) {
                null
            }
        }

        fun scheduleDnsTimeout(
            delayMs: Long,
            task: () -> Unit,
        ): ScheduledFuture<*>? {
            return try {
                dnsTimeoutExecutor.schedule({ task() }, delayMs, TimeUnit.MILLISECONDS)
            } catch (_: RejectedExecutionException) {
                diagnostics.logWorkerRejected(DNS_TIMEOUT_POOL_NAME)
                null
            }
        }

        fun shutdownNow() {
            connectExecutor.shutdownNow()
            controlExecutor.shutdownNow()
            udpRelayExecutor.shutdownNow()
            dnsExecutor.shutdownNow()
            remoteReadExecutor.shutdownNow()
            cleanupExecutor.shutdownNow()
            dnsTimeoutExecutor.shutdownNow()
        }

        private fun execute(
            poolName: String,
            executor: ThreadPoolExecutor,
            task: () -> Unit,
        ): Boolean {
            return try {
                executor.execute { task() }
                true
            } catch (_: RejectedExecutionException) {
                diagnostics.logWorkerRejected(poolName)
                false
            }
        }
    }

    private class TcpProxySession(
        private val key: TcpKey,
        private val sshSession: Session,
        private val executors: ForwarderExecutors,
        private val diagnostics: ForwarderDiagnostics,
        private val packetSender: TcpPacketSender,
        private val activeSessionCount: () -> Int,
        private val onClosed: (TcpKey, TcpProxySession) -> Unit,
        private val config: TunForwarderConfig,
    ) {
        private val lock = ReentrantLock()
        private val outboundSendLock = ReentrantLock()
        private val sendWindowChanged = lock.newCondition()
        private val pendingClientWrites = CoalescingUploadQueue(
            capacityBytes = config.maxPendingUploadBytesPerFlow,
            chunkSizeBytes = COALESCED_UPLOAD_CHUNK_BYTES,
        )
        private val clientUpload = ClientUploadFlow(config.maxPendingUploadBytesPerFlow)
        private val halfClose = TcpHalfCloseState()
        private val uploadWindowAdvertisement = UploadWindowAdvertisementTracker()
        private var state = TcpState.CLOSED
        private val initialServerSequence = initialSequence()
        private var serverFirstUnackedSequence = initialServerSequence
        private var serverNextSequence = initialServerSequence
        private var clientWindow = MAX_TCP_WINDOW
        private var connecting = false
        private var writeScheduled = false
        private var clientFinReceivedAtMs = 0L
        private var channel: ChannelDirectTCPIP? = null
        private var remoteOutput: OutputStream? = null
        private var clientFinCleanupFuture: ScheduledFuture<*>? = null
        private var remoteFinCleanupFuture: ScheduledFuture<*>? = null

        private data class CloseResources(
            val channel: ChannelDirectTCPIP?,
            val cleanupFutures: List<ScheduledFuture<*>>,
        )

        private val lastActivityAtMs = CoalescedActivityTimestamp(
            initialValueMs = elapsedRealtimeMs(),
            minimumUpdateIntervalMs = ACTIVITY_TIMESTAMP_UPDATE_INTERVAL_MS,
        )

        fun recordPacketActivity(nowMs: Long) {
            markActivity(nowMs)
        }

        fun onSyn(clientSequence: Long) {
            val responseSequence: Long
            lock.withLock {
                if (state == TcpState.CLOSED) {
                    state = TcpState.SYN_RECEIVED
                    clientUpload.begin(clientSequence)
                    responseSequence = serverNextSequence
                    serverNextSequence = seqPlus(serverNextSequence, 1)
                } else {
                    responseSequence = seqMinus(serverNextSequence, 1)
                }
            }
            sendTcp(
                sequence = responseSequence,
                flags = TCP_SYN or TCP_ACK,
                payload = EMPTY_BYTES,
            )
        }

        fun onAck(
            acknowledgement: Long,
            window: Int,
        ) {
            var shouldConnect = false
            var shouldClose = false
            lock.withLock {
                clientWindow = window
                updateServerAckLocked(acknowledgement)
                if (state == TcpState.SYN_RECEIVED && acknowledgement == serverNextSequence) {
                    state = TcpState.ESTABLISHED
                    shouldConnect = true
                } else if (state == TcpState.REMOTE_FIN_SENT && serverFirstUnackedSequence == serverNextSequence) {
                    halfClose.onRemoteFinAcknowledged()
                    shouldClose = halfClose.canClose
                }
                sendWindowChanged.signal()
            }
            if (shouldConnect) {
                ensureRemoteConnecting()
            }
            if (shouldClose) {
                close()
            }
        }

        fun onClientData(
            sequence: Long,
            source: ByteArray,
            sourceOffset: Int,
            byteCount: Int,
        ) {
            var shouldConnect = false
            var shouldScheduleWrite = false
            lock.withLock {
                if (state == TcpState.CLOSED || !halfClose.acceptsClientData || clientUpload.isFinished) return
                if (state == TcpState.SYN_RECEIVED) {
                    state = TcpState.ESTABLISHED
                    shouldConnect = true
                }
                if (clientUpload.tryAcceptData(sequence, byteCount)) {
                    pendingClientWrites.append(
                        source = source,
                        sourceOffset = sourceOffset,
                        byteCount = byteCount,
                    )
                    shouldScheduleWrite = true
                }
            }

            sendCurrentAck()
            if (shouldConnect) {
                ensureRemoteConnecting()
            }
            if (shouldScheduleWrite) {
                scheduleClientFlush()
            }
        }

        fun onClientFin(finSequence: Long, receivedAtMs: Long) {
            val finAccepted: Boolean
            lock.withLock {
                if (state == TcpState.CLOSED) return
                finAccepted = clientUpload.tryAcceptFin(finSequence)
                if (finAccepted) {
                    halfClose.onClientFinAccepted()
                    clientFinReceivedAtMs = receivedAtMs
                }
            }
            sendCurrentAck()
            if (finAccepted) {
                scheduleClientFlush()
                scheduleClientFinCleanup(CLIENT_FIN_SESSION_TTL_MS)
            }
        }

        fun close() {
            val resources = outboundSendLock.withLock {
                lock.withLock { transitionToClosedLocked() }
            } ?: return
            finishClose(resources)
        }

        fun resetAndClose() {
            sendResetAndClose()
        }

        private fun ensureRemoteConnecting() {
            var shouldStart = false
            lock.withLock {
                if (!connecting && channel == null && state != TcpState.CLOSED) {
                    connecting = true
                    shouldStart = true
                }
            }
            if (shouldStart) {
                val scheduled = executors.executeConnect { connectRemote() }
                if (!scheduled) {
                    lock.withLock {
                        connecting = false
                    }
                    sendResetAndClose()
                }
            }
        }

        private fun connectRemote() {
            var nextChannel: ChannelDirectTCPIP? = null
            val host = addressToString(key.remoteAddress)
            // Telegram flows get their own log budget: this is how we see whether the call is trying
            // its own TCP relay through the tunnel at all.
            val isTelegram = TelegramNetworks.containsIpv4(key.remoteAddress)
            val startedAtMs = elapsedRealtimeMs()
            try {
                if (isTelegram) {
                    diagnostics.logTelegramTcpOpen(host, key.remotePort)
                } else {
                    diagnostics.logTcpOpen(host, key.remotePort)
                }
                nextChannel = sshSession.openChannel("direct-tcpip") as ChannelDirectTCPIP
                nextChannel.setHost(host)
                nextChannel.setPort(key.remotePort)
                nextChannel.setOrgIPAddress(addressToString(key.clientAddress))
                nextChannel.setOrgPort(key.clientPort)
                DirectTcpipChannelTuning.setLocalWindowSize(nextChannel, config.sshChannelWindowBytes)
                val input = nextChannel.inputStream
                val output = nextChannel.outputStream
                nextChannel.connect(SSH_CHANNEL_CONNECT_TIMEOUT_MS)
                if (isTelegram) {
                    diagnostics.logTelegramTcpEstablished(
                        host = host,
                        port = key.remotePort,
                        elapsedMs = elapsedRealtimeMs() - startedAtMs,
                    )
                }
                markActivity()
                lock.withLock {
                    if (state == TcpState.CLOSED) {
                        nextChannel.disconnect()
                        return
                    }
                    channel = nextChannel
                    remoteOutput = output
                    connecting = false
                    sendWindowChanged.signal()
                }
                val readScheduled = executors.executeRemoteRead { readRemote(nextChannel, input) }
                if (!readScheduled) {
                    diagnostics.logTcpFailure(
                        host = host,
                        port = key.remotePort,
                        message = "remote read worker pool is saturated; activeSessions=${activeSessionCount()}",
                    )
                    sendResetAndClose()
                    return
                }
                scheduleClientFlush()
            } catch (error: Exception) {
                lock.withLock {
                    connecting = false
                }
                nextChannel?.disconnect()
                val failure = "${error.message ?: error::class.java.simpleName} " +
                    "after ${elapsedRealtimeMs() - startedAtMs}ms"
                if (isTelegram) {
                    diagnostics.logTelegramTcpFailure(host, key.remotePort, failure)
                } else {
                    diagnostics.logTcpFailure(host, key.remotePort, failure)
                }
                sendResetAndClose()
            }
        }

        private fun scheduleClientFlush() {
            var shouldSchedule = false
            lock.withLock {
                if (!writeScheduled) {
                    writeScheduled = true
                    shouldSchedule = true
                }
            }
            if (shouldSchedule) {
                submitClientFlushTask()
            }
        }

        private fun submitClientFlushTask() {
            val scheduled = executors.executeControl { flushClientWrites() }
            if (!scheduled) {
                lock.withLock {
                    writeScheduled = false
                }
                sendResetAndClose()
            }
        }

        /** Processes at most one coalesced block so busy upload flows cannot monopolize control workers. */
        private fun flushClientWrites() {
            var payloadBatch: CoalescingUploadQueue.Chunk? = null
            var output: OutputStream? = null
            var outputToClose: OutputStream? = null
            var batchChannel: ChannelDirectTCPIP? = null
            var channelUnavailable = false
            lock.withLock {
                val remote = remoteOutput
                if (remote == null || state == TcpState.CLOSED) {
                    writeScheduled = false
                    return
                }
                val activeChannel = channel
                if (activeChannel == null || !isSshChannelOpen(activeChannel)) {
                    writeScheduled = false
                    channelUnavailable = true
                } else {
                    batchChannel = activeChannel
                    payloadBatch = pendingClientWrites.poll()
                    if (payloadBatch == null) {
                        writeScheduled = false
                        if (clientUpload.isFinished) {
                            remoteOutput = null
                            outputToClose = remote
                        }
                    } else {
                        output = remote
                    }
                }
            }
            if (channelUnavailable) {
                diagnostics.logTcpWriteFailure("SSH channel closed before upload flush")
                sendResetAndClose()
                return
            }
            val activeChannel = batchChannel ?: return
            outputToClose?.let { closingOutput ->
                completeClientOutputClose(closingOutput, activeChannel)
                return
            }
            val activeOutput = output ?: return
            val activeBatch = payloadBatch ?: return
            try {
                if (!isSshChannelOpen(activeChannel)) {
                    throw EOFException("SSH channel closed before upload batch")
                }
                activeOutput.write(activeBatch.buffer, 0, activeBatch.length)
                activeOutput.flush()
                if (!isSshChannelOpen(activeChannel)) {
                    throw EOFException("SSH channel closed during upload flush")
                }
                markActivity()
                lock.withLock {
                    clientUpload.releaseBuffered(activeBatch.length)
                    pendingClientWrites.recycle(activeBatch)
                }
                sendUploadWindowReopenIfNeeded()
            } catch (error: Exception) {
                diagnostics.logTcpWriteFailure(error.message ?: error::class.java.simpleName)
                sendResetAndClose()
                return
            }

            val shouldReschedule = lock.withLock {
                if (state == TcpState.CLOSED) {
                    writeScheduled = false
                    false
                } else if (!pendingClientWrites.isEmpty || clientUpload.isFinished) {
                    true
                } else {
                    writeScheduled = false
                    false
                }
            }
            if (shouldReschedule) {
                submitClientFlushTask()
            }
        }

        private fun completeClientOutputClose(
            output: OutputStream,
            activeChannel: ChannelDirectTCPIP,
        ) {
            try {
                if (!isSshChannelOpen(activeChannel)) {
                    throw EOFException("SSH channel closed before client EOF")
                }
                output.close()
            } catch (error: Exception) {
                diagnostics.logTcpWriteFailure(error.message ?: error::class.java.simpleName)
                sendResetAndClose()
                return
            }
            val shouldClose = lock.withLock {
                if (state == TcpState.CLOSED) return@withLock false
                halfClose.onClientOutputClosed()
                halfClose.canClose
            }
            if (shouldClose) {
                close()
            }
        }

        private fun readRemote(
            activeChannel: ChannelDirectTCPIP,
            input: InputStream,
        ) {
            val buffer = ByteArray(REMOTE_READ_BUFFER_SIZE)
            try {
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) {
                        sleepAfterEmptyRead()
                        continue
                    }
                    val readAtMs = elapsedRealtimeMs()
                    markActivity(readAtMs)
                    diagnostics.markRemoteRead(readAtMs)
                    var offset = 0
                    while (offset < read) {
                        val chunkSize = minOf(config.tcpMss, read - offset)
                        if (!sendRemoteData(buffer, offset, chunkSize)) return
                        offset += chunkSize
                    }
                }
                // JSch sets eof_remote for both remote EOF and forced disconnect. Channel
                // disconnect marks connected=false before closing the stream, whereas a genuine
                // SSH_MSG_CHANNEL_EOF leaves the channel connected until its later CLOSE.
                if (shouldSendRemoteFin(
                        streamReachedEof = true,
                        channelStillConnected = isSshChannelOpen(activeChannel),
                    )
                ) {
                    sendRemoteFin()
                } else {
                    sendResetAndClose()
                }
            } catch (error: Exception) {
                diagnostics.logTcpReadFailure(error.message ?: error::class.java.simpleName)
                sendResetAndClose()
            }
        }

        fun idleForMs(now: Long): Long = now - lastActivityAtMs.get()

        fun closeIdleUnderPressure(now: Long, idleTtlMs: Long): Boolean {
            val idleForMs = idleForMs(now)
            val shouldClose = lock.withLock {
                state != TcpState.CLOSED &&
                    state != TcpState.REMOTE_FIN_SENT &&
                    !clientUpload.isFinished &&
                    idleForMs >= idleTtlMs
            }
            if (shouldClose) {
                diagnostics.logIdleTimeout(addressToString(key.remoteAddress), key.remotePort, idleForMs)
                sendResetAndClose()
                return true
            }
            return false
        }

        private fun markActivity(now: Long = elapsedRealtimeMs()) {
            lastActivityAtMs.mark(now)
        }

        private fun isSshChannelOpen(channel: ChannelDirectTCPIP): Boolean {
            return channel.isConnected && !channel.isClosed
        }

        private fun sendRemoteData(
            payload: ByteArray,
            payloadOffset: Int,
            payloadLength: Int,
        ): Boolean {
            var offset = payloadOffset
            val endOffset = payloadOffset + payloadLength
            // One deadline for handing this chunk over, not one per retry: a client that opens a
            // byte of window every few seconds would otherwise reset the clock forever and keep
            // holding the SSH session that every other flow shares.
            val handoverDeadlineAtMs = elapsedRealtimeMs() + CLIENT_WINDOW_STALL_TIMEOUT_MS
            while (offset < endOffset) {
                var sequence = 0L
                var acknowledgement = 0L
                var advertisedWindow = 0
                var chunkSize = 0
                val shouldWaitForWindow = outboundSendLock.withLock {
                    val canSend = lock.withLock {
                        if (state == TcpState.CLOSED) return false
                        val availableWindow = availableSendWindowLocked()
                        if (availableWindow <= 0) {
                            false
                        } else {
                            chunkSize = minOf(
                                config.tcpMss,
                                endOffset - offset,
                                availableWindow,
                            )
                            sequence = serverNextSequence
                            serverNextSequence = seqPlus(serverNextSequence, chunkSize)
                            acknowledgement = clientUpload.nextSequence
                            advertisedWindow = advertisedUploadWindowLocked()
                            true
                        }
                    }
                    if (!canSend) {
                        true
                    } else {
                        // Sequence reservation and packet enqueue share outboundSendLock. A
                        // concurrent reconnect RST can therefore never jump over unsent payload.
                        sendTcpLocked(
                            sequence = sequence,
                            acknowledgement = acknowledgement,
                            flags = TCP_PSH or TCP_ACK,
                            advertisedWindow = advertisedWindow,
                            payload = payload,
                            payloadOffset = offset,
                            payloadLength = chunkSize,
                        )
                        false
                    }
                }
                if (shouldWaitForWindow) {
                    // While this flow waits it stops draining its SSH channel, and JSch delivers
                    // every channel on one thread: a client that never reopens its window would
                    // freeze the whole tunnel, not just itself. So the wait is bounded, and a flow
                    // that sits on a closed window past the deadline is reset on its own.
                    var stalledOnClientWindow = false
                    diagnostics.enterClientWindowWait()
                    try {
                        lock.withLock {
                            while (state != TcpState.CLOSED && availableSendWindowLocked() <= 0) {
                                try {
                                    sendWindowChanged.await(
                                        CLIENT_WINDOW_WAIT_SLICE_MS,
                                        TimeUnit.MILLISECONDS,
                                    )
                                } catch (_: InterruptedException) {
                                    Thread.currentThread().interrupt()
                                    return false
                                }
                                if (elapsedRealtimeMs() >= handoverDeadlineAtMs) {
                                    stalledOnClientWindow = true
                                    break
                                }
                            }
                            if (state == TcpState.CLOSED) return false
                        }
                    } finally {
                        diagnostics.exitClientWindowWait()
                    }
                    if (stalledOnClientWindow) {
                        diagnostics.logClientWindowStall(
                            host = addressToString(key.remoteAddress),
                            port = key.remotePort,
                            stalledForMs = CLIENT_WINDOW_STALL_TIMEOUT_MS,
                        )
                        sendResetAndClose()
                        return false
                    }
                } else {
                    offset += chunkSize
                }
            }
            return true
        }

        private fun sendRemoteFin() {
            val sent = outboundSendLock.withLock {
                var sequence = 0L
                var acknowledgement = 0L
                var advertisedWindow = 0
                val shouldSend = lock.withLock {
                    if (state == TcpState.CLOSED || halfClose.remoteFinSent) {
                        false
                    } else {
                        state = TcpState.REMOTE_FIN_SENT
                        halfClose.onRemoteFinSent()
                        sequence = serverNextSequence
                        serverNextSequence = seqPlus(serverNextSequence, 1)
                        acknowledgement = clientUpload.nextSequence
                        advertisedWindow = advertisedUploadWindowLocked()
                        true
                    }
                }
                if (shouldSend) {
                    sendTcpLocked(
                        sequence = sequence,
                        acknowledgement = acknowledgement,
                        flags = TCP_FIN or TCP_ACK,
                        advertisedWindow = advertisedWindow,
                        payload = EMPTY_BYTES,
                        payloadOffset = 0,
                        payloadLength = 0,
                    )
                }
                shouldSend
            }
            if (sent) scheduleRemoteFinCleanup(REMOTE_FIN_SESSION_TTL_MS)
        }

        private fun scheduleClientFinCleanup(delayMs: Long) {
            val scheduled = executors.scheduleCleanup(delayMs) {
                val remainingDelayMs = lock.withLock {
                    if (
                        state == TcpState.CLOSED ||
                        state == TcpState.REMOTE_FIN_SENT ||
                        !clientUpload.isFinished ||
                        clientFinReceivedAtMs <= 0L
                    ) {
                        return@scheduleCleanup
                    }
                    remainingClientFinCleanupDelayMs(
                        nowMs = elapsedRealtimeMs(),
                        clientFinReceivedAtMs = clientFinReceivedAtMs,
                        lastActivityAtMs = lastActivityAtMs.get(),
                        timeoutMs = CLIENT_FIN_SESSION_TTL_MS,
                    )
                }
                if (remainingDelayMs == 0L) {
                    diagnostics.logClientFinTimeout(addressToString(key.remoteAddress), key.remotePort)
                    sendResetAndClose()
                } else {
                    scheduleClientFinCleanup(remainingDelayMs)
                }
            }
            if (scheduled == null) {
                sendResetAndClose()
                return
            }
            val cancelScheduled = lock.withLock {
                if (state == TcpState.CLOSED || !clientUpload.isFinished) {
                    true
                } else {
                    clientFinCleanupFuture?.cancel(false)
                    clientFinCleanupFuture = scheduled
                    false
                }
            }
            if (cancelScheduled) {
                scheduled.cancel(false)
            }
        }

        private fun scheduleRemoteFinCleanup(delayMs: Long) {
            val scheduled = executors.scheduleCleanup(delayMs) {
                val remainingDelayMs = lock.withLock {
                    if (state != TcpState.REMOTE_FIN_SENT) return@scheduleCleanup
                    remainingIdleCleanupDelayMs(
                        idleForMs = idleForMs(elapsedRealtimeMs()),
                        timeoutMs = REMOTE_FIN_SESSION_TTL_MS,
                    )
                }
                if (remainingDelayMs == 0L) {
                    close()
                } else {
                    scheduleRemoteFinCleanup(remainingDelayMs)
                }
            }
            if (scheduled == null) {
                close()
                return
            }
            val cancelScheduled = lock.withLock {
                if (state != TcpState.REMOTE_FIN_SENT) {
                    true
                } else {
                    remoteFinCleanupFuture?.cancel(false)
                    remoteFinCleanupFuture = scheduled
                    false
                }
            }
            if (cancelScheduled) {
                scheduled.cancel(false)
            }
        }

        private fun sendResetAndClose() {
            var closeResources: CloseResources? = null
            try {
                outboundSendLock.withLock {
                    var sequence = 0L
                    var acknowledgement = 0L
                    var advertisedWindow = 0
                    lock.withLock {
                        if (state == TcpState.CLOSED) return
                        sequence = serverNextSequence
                        acknowledgement = clientUpload.nextSequence
                        advertisedWindow = advertisedUploadWindowLocked()
                        closeResources = transitionToClosedLocked()
                    }
                    sendTcpLocked(
                        sequence = sequence,
                        acknowledgement = acknowledgement,
                        flags = TCP_RST or TCP_ACK,
                        advertisedWindow = advertisedWindow,
                        payload = EMPTY_BYTES,
                        payloadOffset = 0,
                        payloadLength = 0,
                    )
                }
            } finally {
                closeResources?.let(::finishClose)
            }
        }

        private fun transitionToClosedLocked(): CloseResources? {
            if (state == TcpState.CLOSED) return null
            state = TcpState.CLOSED
            pendingClientWrites.clear()
            val resources = CloseResources(
                channel = channel,
                cleanupFutures = listOfNotNull(clientFinCleanupFuture, remoteFinCleanupFuture),
            )
            channel = null
            remoteOutput = null
            clientFinCleanupFuture = null
            remoteFinCleanupFuture = null
            sendWindowChanged.signal()
            return resources
        }

        private fun finishClose(resources: CloseResources) {
            resources.cleanupFutures.forEach { future -> future.cancel(false) }
            resources.channel?.disconnect()
            onClosed(key, this)
        }

        private fun updateServerAckLocked(acknowledgement: Long) {
            val outstanding = seqDistance(serverFirstUnackedSequence, serverNextSequence)
            val acknowledged = seqDistance(serverFirstUnackedSequence, acknowledgement)
            if (acknowledged in 1..outstanding) {
                serverFirstUnackedSequence = acknowledgement
            }
        }

        private fun advertisedUploadWindowLocked(): Int {
            return clientUpload.advertisedWindow()
        }

        private fun availableSendWindowLocked(): Int {
            val bytesInFlight = seqDistance(serverFirstUnackedSequence, serverNextSequence)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
            return (clientWindow - bytesInFlight).coerceAtLeast(0)
        }

        /**
         * All session packets pass through this lock. The ACK/window snapshot is taken only after
         * the sender owns the lock and is then enqueued before releasing it. This preserves the
         * order between a concurrent zero-window ACK and the later reopen ACK.
         */
        private fun sendTcp(
            sequence: Long,
            flags: Int,
            payload: ByteArray,
            payloadOffset: Int = 0,
            payloadLength: Int = payload.size,
        ) {
            outboundSendLock.withLock {
                val acknowledgement: Long
                val advertisedWindow: Int
                lock.withLock {
                    if (state == TcpState.CLOSED) return
                    acknowledgement = clientUpload.nextSequence
                    advertisedWindow = advertisedUploadWindowLocked()
                }
                sendTcpLocked(
                    sequence = sequence,
                    acknowledgement = acknowledgement,
                    flags = flags,
                    advertisedWindow = advertisedWindow,
                    payload = payload,
                    payloadOffset = payloadOffset,
                    payloadLength = payloadLength,
                )
            }
        }

        private fun sendCurrentAck() {
            outboundSendLock.withLock {
                val sequence: Long
                val acknowledgement: Long
                val advertisedWindow: Int
                lock.withLock {
                    if (state == TcpState.CLOSED) return
                    sequence = serverNextSequence
                    acknowledgement = clientUpload.nextSequence
                    advertisedWindow = advertisedUploadWindowLocked()
                }
                sendTcpLocked(
                    sequence = sequence,
                    acknowledgement = acknowledgement,
                    flags = TCP_ACK,
                    advertisedWindow = advertisedWindow,
                    payload = EMPTY_BYTES,
                    payloadOffset = 0,
                    payloadLength = 0,
                )
            }
        }

        private fun sendUploadWindowReopenIfNeeded() {
            outboundSendLock.withLock {
                val sequence: Long
                val acknowledgement: Long
                val advertisedWindow: Int
                lock.withLock {
                    if (state == TcpState.CLOSED) return
                    advertisedWindow = advertisedUploadWindowLocked()
                    if (!uploadWindowAdvertisement.shouldSendReopen(advertisedWindow)) return
                    sequence = serverNextSequence
                    acknowledgement = clientUpload.nextSequence
                }
                sendTcpLocked(
                    sequence = sequence,
                    acknowledgement = acknowledgement,
                    flags = TCP_ACK,
                    advertisedWindow = advertisedWindow,
                    payload = EMPTY_BYTES,
                    payloadOffset = 0,
                    payloadLength = 0,
                )
                uploadWindowAdvertisement.recordExplicitReopenSent(advertisedWindow)
            }
        }

        /** Must be called while [outboundSendLock] is held. */
        private fun sendTcpLocked(
            sequence: Long,
            acknowledgement: Long,
            flags: Int,
            advertisedWindow: Int,
            payload: ByteArray,
            payloadOffset: Int,
            payloadLength: Int,
        ) {
            packetSender.send(
                key.remoteAddress,
                key.clientAddress,
                key.remotePort,
                key.clientPort,
                sequence,
                acknowledgement,
                flags,
                advertisedWindow,
                payload,
                payloadOffset,
                payloadLength,
            )
            uploadWindowAdvertisement.recordSent(advertisedWindow)
        }
    }

    /**
     * The per-flow log budget runs out long before a busy device does, so rejections keep being
     * counted after it and a compact summary goes out once a minute. Without it, "which app is
     * failing" stops being visible right after the first few lines.
     */
    private class UdpRejectionSummary {
        private val lock = Any()
        private val flowsByPort = HashMap<Int, Int>()
        private var flows = 0
        private var lastSummaryAtMs = Long.MIN_VALUE

        fun recordFlow(port: Int) {
            synchronized(lock) {
                flows += 1
                if (flowsByPort.size < MAX_SUMMARISED_UDP_PORTS || flowsByPort.containsKey(port)) {
                    flowsByPort[port] = (flowsByPort[port] ?: 0) + 1
                }
            }
        }

        fun takeDueSummary(nowMs: Long): String? = synchronized(lock) {
            if (lastSummaryAtMs == Long.MIN_VALUE) {
                lastSummaryAtMs = nowMs
                return@synchronized null
            }
            val elapsedMs = nowMs - lastSummaryAtMs
            if (flows == 0 || elapsedMs < UDP_REJECT_SUMMARY_INTERVAL_MS) return@synchronized null
            val topPorts = flowsByPort.entries
                .sortedByDescending { entry -> entry.value }
                .take(TOP_SUMMARISED_UDP_PORTS)
                .joinToString { entry -> "${entry.key}${describeUdpPort(entry.key)} x${entry.value}" }
            val summary = "$flows unsupported flow(s) rejected in the last ${elapsedMs / 1_000}s" +
                if (topPorts.isEmpty()) "" else "; top ports: $topPorts"
            flows = 0
            flowsByPort.clear()
            lastSummaryAtMs = nowMs
            summary
        }
    }

    /**
     * The reflector TCP ports one flow may use, and where to record the one that answered so the
     * next flow to the same reflector starts with a port that is known to work.
     */
    private class RelayTcpPortPolicy(
        val candidates: List<Int>,
        private val onPortConfirmed: (Int) -> Unit,
    ) {
        init {
            require(candidates.isNotEmpty()) { "A relay needs at least one candidate TCP port" }
        }

        fun confirm(port: Int) {
            onPortConfirmed(port)
        }
    }

    /**
     * Relays a single UDP flow over one SSH `direct-tcpip` channel using the reflector's own TCP
     * transport: the `0xEEEEEEEE` prologue and the `uint32` little-endian length prefix per packet
     * that tgcalls' `RawTcpSocket` writes, carrying datagrams that are already self-describing
     * (`peer_tag | sender_tag | big-endian size | payload`). SSH cannot forward UDP, so this is what
     * keeps VoIP media inside the tunnel instead of dropping it or leaking it around the VPN.
     *
     * The stream goes to [REFLECTOR_TCP_PORT], not to the UDP media port the client is addressing:
     * reflectors serve TCP on 443 alone.
     */
    private class UdpProxySession(
        private val key: UdpKey,
        private val sshSession: Session,
        private val executors: ForwarderExecutors,
        private val diagnostics: ForwarderDiagnostics,
        private val datagramSender: UdpDatagramSender,
        private val maxDatagramBytes: Int,
        private val sshChannelWindowBytes: Int,
        private val relayPorts: RelayTcpPortPolicy,
        private val isForwarderRunning: () -> Boolean,
        private val onConnectFailed: (UdpKey) -> Unit,
        private val onClosed: (UdpKey, UdpProxySession) -> Unit,
    ) {
        private val lock = ReentrantLock()
        private val pending = ArrayDeque<ByteArray>()
        private val closed = AtomicBoolean(false)
        private val lastActivityAtMs = AtomicLong(elapsedRealtimeMs())
        private val decoder = MtProtoIntermediateDecoder(maxChunkBytes = REMOTE_READ_BUFFER_SIZE)
        private var pendingBytes = 0
        private var uplinkScheduled = false
        private var prologueSent = false
        private var readySignalled = false
        private var idleCleanupFuture: ScheduledFuture<*>? = null

        @Volatile
        private var channel: ChannelDirectTCPIP? = null

        @Volatile
        private var remoteOutput: OutputStream? = null

        /** Returns true when the flow is owned by this relay, even if the datagram had to be dropped. */
        fun onClientDatagram(payload: ByteArray): Boolean {
            if (closed.get()) return false
            if (payload.size > MtProtoIntermediateTransport.MAX_FRAME_BYTES) return true
            val accepted = lock.withLock {
                if (
                    pending.size >= MAX_PENDING_UDP_UPLINK_DATAGRAMS ||
                    pendingBytes + payload.size > MAX_PENDING_UDP_UPLINK_BYTES
                ) {
                    false
                } else {
                    pending.addLast(payload)
                    pendingBytes += payload.size
                    true
                }
            }
            if (!accepted) {
                diagnostics.logUdpRelayDropped(addressToString(key.remoteAddress))
                return true
            }
            lastActivityAtMs.set(elapsedRealtimeMs())
            requestUplink()
            return true
        }

        fun scheduleIdleCleanup() {
            if (closed.get()) return
            val future = executors.scheduleCleanup(UDP_RELAY_IDLE_CHECK_MS) { runIdleCleanup() }
            if (future == null) {
                close("cleanup worker pool is saturated")
                return
            }
            lock.withLock { idleCleanupFuture = future }
            if (closed.get()) {
                future.cancel(false)
            }
        }

        fun close(reason: String) {
            if (!closed.compareAndSet(false, true)) return
            var activeChannel: ChannelDirectTCPIP? = null
            var activeFuture: ScheduledFuture<*>? = null
            lock.withLock {
                activeChannel = channel
                activeFuture = idleCleanupFuture
                channel = null
                remoteOutput = null
                idleCleanupFuture = null
                pending.clear()
                pendingBytes = 0
            }
            activeFuture?.cancel(false)
            runCatching { activeChannel?.disconnect() }
            diagnostics.logUdpRelayClosed(addressToString(key.remoteAddress), key.remotePort, reason)
            onClosed(key, this)
        }

        private fun runIdleCleanup() {
            if (closed.get()) return
            val idleForMs = elapsedRealtimeMs() - lastActivityAtMs.get()
            if (!isForwarderRunning() || idleForMs >= UDP_RELAY_IDLE_TTL_MS) {
                close("idle for ${idleForMs / 1_000}s")
            } else {
                scheduleIdleCleanup()
            }
        }

        private fun requestUplink() {
            val shouldSchedule = lock.withLock {
                if (uplinkScheduled) {
                    false
                } else {
                    uplinkScheduled = true
                    true
                }
            }
            if (!shouldSchedule) return
            val scheduled = executors.executeUdpRelay { runUplink() }
            if (!scheduled) {
                lock.withLock { uplinkScheduled = false }
                close("UDP relay worker pool is saturated")
            }
        }

        private fun runUplink() {
            var reschedule = false
            try {
                if (closed.get() || !isForwarderRunning()) return
                if (remoteOutput == null) {
                    connectRemote()
                }
                drainUplink()
            } catch (error: Exception) {
                close(error.message ?: error::class.java.simpleName)
                return
            } finally {
                lock.withLock {
                    uplinkScheduled = false
                    reschedule = pending.isNotEmpty()
                }
            }
            if (reschedule && !closed.get() && isForwarderRunning()) {
                requestUplink()
            }
        }

        /**
         * Reflector TCP ports are tried in order, so a server that does forward the media port is
         * still used while the common case - 443 - costs one attempt. Only a failure of every
         * candidate parks the destination; a saturated read pool is a local problem and must not.
         */
        private fun connectRemote() {
            val host = addressToString(key.remoteAddress)
            var lastError: Exception? = null
            for (tcpPort in relayPorts.candidates) {
                if (closed.get() || !isForwarderRunning()) return
                val startedAtMs = elapsedRealtimeMs()
                try {
                    openRelayChannel(host, tcpPort)
                    relayPorts.confirm(tcpPort)
                    diagnostics.logUdpRelayReady(
                        host = host,
                        udpPort = key.remotePort,
                        tcpPort = tcpPort,
                        elapsedMs = elapsedRealtimeMs() - startedAtMs,
                    )
                    return
                } catch (error: Exception) {
                    lastError = error
                    diagnostics.logUdpRelayFailure(
                        host = host,
                        port = tcpPort,
                        message = "${describeConnectFailure(elapsedRealtimeMs() - startedAtMs)}: " +
                            "${error.message ?: error::class.java.simpleName}",
                    )
                    // A local shortage or a dead transport says nothing about the reflector.
                    if (error is VpnConnectionException || closed.get() || !sshSession.isConnected) {
                        throw error
                    }
                }
            }
            onConnectFailed(key)
            throw lastError ?: VpnConnectionException("no reflector TCP port answered")
        }

        private fun openRelayChannel(host: String, tcpPort: Int) {
            var nextChannel: ChannelDirectTCPIP? = null
            try {
                diagnostics.logUdpRelayOpen(host = host, udpPort = key.remotePort, tcpPort = tcpPort)
                nextChannel = sshSession.openChannel("direct-tcpip") as ChannelDirectTCPIP
                nextChannel.setHost(host)
                nextChannel.setPort(tcpPort)
                nextChannel.setOrgIPAddress(addressToString(key.clientAddress))
                nextChannel.setOrgPort(key.clientPort)
                DirectTcpipChannelTuning.setLocalWindowSize(nextChannel, sshChannelWindowBytes)
                val input = nextChannel.inputStream
                val output = nextChannel.outputStream
                nextChannel.connect(UDP_RELAY_CONNECT_TIMEOUT_MS)
                val readChannel = nextChannel
                lock.withLock {
                    channel = readChannel
                    remoteOutput = output
                }
                val readScheduled = executors.executeRemoteRead { readRemote(readChannel, input) }
                if (!readScheduled) {
                    throw VpnConnectionException("remote read worker pool is saturated")
                }
            } catch (error: Exception) {
                lock.withLock {
                    channel = null
                    remoteOutput = null
                }
                runCatching { nextChannel?.disconnect() }
                throw error
            }
        }

        /**
         * JSch collapses "the server refused the channel" and "the channel open timed out" into the
         * same message, so the elapsed time is what tells a filtered port from a refused one.
         */
        private fun describeConnectFailure(elapsedMs: Long): String {
            return if (elapsedMs >= UDP_RELAY_CONNECT_TIMEOUT_MS - CONNECT_VERDICT_SLACK_MS) {
                "no answer in ${elapsedMs}ms, the SSH server saw no SYN-ACK (port filtered or dropped)"
            } else {
                "rejected in ${elapsedMs}ms by the SSH server or the destination"
            }
        }

        /** Bounded so one busy flow cannot monopolize a control worker, mirroring the TCP uploader. */
        private fun drainUplink() {
            var written = 0
            while (written < MAX_UDP_DATAGRAMS_PER_UPLINK_RUN) {
                val payload = nextPendingDatagram() ?: return
                writeDatagram(payload)
                written += 1
            }
        }

        private fun nextPendingDatagram(): ByteArray? = lock.withLock {
            val next = pending.pollFirst() ?: return@withLock null
            pendingBytes -= next.size
            next
        }

        /**
         * The client believes it speaks UDP, so its hello is the 40-byte reflector ping while the
         * TCP transport registers a connection with a 20-byte hello. Every ping is translated, not
         * only the first: the client keeps pinging as a keepalive, and a verbatim ping on the TCP
         * stream would be read as a data packet with a `0xFFFFFFFF` size tag.
         */
        private fun writeDatagram(payload: ByteArray) {
            val output = remoteOutput ?: throw VpnConnectionException("UDP relay channel is not connected")
            if (!prologueSent) {
                output.write(MtProtoIntermediateTransport.prologue())
                prologueSent = true
            }
            if (ReflectorHandshake.isUdpHello(payload)) {
                output.write(MtProtoIntermediateTransport.encodeFrame(ReflectorHandshake.tcpHello(payload)))
                output.flush()
                signalRelayReady(payload)
                return
            }
            output.write(MtProtoIntermediateTransport.encodeFrame(payload))
            output.flush()
        }

        /**
         * tgcalls marks a TCP reflector port ready as soon as the socket connects, but this client
         * runs the UDP path, where readiness only arrives with an inbound packet carrying the peer
         * tag. Without that answer the reflector candidate is never used even though the stream is
         * up, so the relay produces the same signal once the TCP hello is on the wire.
         */
        private fun signalRelayReady(udpHello: ByteArray) {
            val shouldSignal = lock.withLock {
                if (readySignalled) {
                    false
                } else {
                    readySignalled = true
                    true
                }
            }
            if (!shouldSignal) return
            deliverDatagram(ReflectorHandshake.readyPong(udpHello))
        }

        private fun readRemote(activeChannel: ChannelDirectTCPIP, input: InputStream) {
            val buffer = ByteArray(REMOTE_READ_BUFFER_SIZE)
            try {
                while (!closed.get() && isForwarderRunning()) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    lastActivityAtMs.set(elapsedRealtimeMs())
                    decoder.offer(buffer, read, ::deliverDatagram)
                }
                close("reflector closed the relay stream")
            } catch (error: Exception) {
                if (!closed.get() && isForwarderRunning()) {
                    diagnostics.logUdpRelayFailure(
                        host = addressToString(key.remoteAddress),
                        port = key.remotePort,
                        message = error.message ?: error::class.java.simpleName,
                    )
                }
                close(error.message ?: error::class.java.simpleName)
            } finally {
                runCatching { activeChannel.disconnect() }
            }
        }

        private fun deliverDatagram(frame: ByteArray) {
            if (frame.size > maxDatagramBytes) {
                diagnostics.logUdpRelayDropped(addressToString(key.remoteAddress))
                return
            }
            datagramSender.send(
                sourceAddress = key.remoteAddress,
                destinationAddress = key.clientAddress,
                sourcePort = key.remotePort,
                destinationPort = key.clientPort,
                payload = frame,
            )
        }
    }

    private class ForwarderDiagnostics(
        private val log: (String) -> Unit,
    ) {
        private val tcpOpenCount = AtomicInteger(0)
        private val tcpFailureCount = AtomicInteger(0)
        private val tcpWriteFailureCount = AtomicInteger(0)
        private val tcpReadFailureCount = AtomicInteger(0)
        private val tcpClientFinTimeoutCount = AtomicInteger(0)
        private val tcpIdleTimeoutCount = AtomicInteger(0)
        private val dnsFailureCount = AtomicInteger(0)
        private val silentResolverCount = AtomicInteger(0)
        private val workerRejectedCount = AtomicInteger(0)
        private val sessionLimitCount = AtomicInteger(0)
        private val udpRelayOpenCount = AtomicInteger(0)
        private val udpRelayFailureCount = AtomicInteger(0)
        private val udpRelayClosedCount = AtomicInteger(0)
        private val udpRelayDroppedCount = AtomicInteger(0)
        private val udpRelayLimitCount = AtomicInteger(0)
        private val unsupportedUdpCount = AtomicInteger(0)
        private val tunWriteDroppedCount = AtomicInteger(0)
        private val udpRelayDisabledCount = AtomicInteger(0)
        private val udpRelayReadyCount = AtomicInteger(0)
        private val udpRejectionSummaryCount = AtomicInteger(0)
        private val dnsCacheHitCount = AtomicInteger(0)
        private val pressureCleanupCount = AtomicInteger(0)
        private val tunStallCount = AtomicInteger(0)
        private val clientWindowStallCount = AtomicInteger(0)
        private val lastRemoteReadAt = AtomicLong(0L)
        private val clientWindowWaiters = AtomicInteger(0)
        private val transportLostCount = AtomicInteger(0)
        private val reflectorUnreachableCount = AtomicInteger(0)
        private val telegramTcpOpenCount = AtomicInteger(0)
        private val telegramTcpEstablishedCount = AtomicInteger(0)
        private val telegramTcpFailureCount = AtomicInteger(0)

        fun logTcpOpen(host: String, port: Int) {
            logLimited(
                counter = tcpOpenCount,
                message = "TUN TCP: opening SSH direct TCP to $host:$port",
                suppressedMessage = "TUN TCP: further per-connection open logs suppressed",
            )
        }

        fun logTcpFailure(host: String, port: Int, message: String) {
            logLimited(
                counter = tcpFailureCount,
                message = "TUN TCP failed: $host:$port: $message",
                suppressedMessage = "TUN TCP: further per-connection failure logs suppressed",
            )
        }

        fun logTcpWriteFailure(message: String) {
            logLimited(
                counter = tcpWriteFailureCount,
                message = "TUN TCP write failed: $message",
                suppressedMessage = "TUN TCP: further write failure logs suppressed",
            )
        }

        fun logTcpReadFailure(message: String) {
            logLimited(
                counter = tcpReadFailureCount,
                message = "TUN TCP read failed: $message",
                suppressedMessage = "TUN TCP: further read failure logs suppressed",
            )
        }

        fun logClientFinTimeout(host: String, port: Int) {
            logLimited(
                counter = tcpClientFinTimeoutCount,
                message = "TUN TCP closed stale client-finished session: $host:$port",
                suppressedMessage = "TUN TCP: further stale client-finished session logs suppressed",
            )
        }

        fun logIdleTimeout(host: String, port: Int, idleForMs: Long) {
            logLimited(
                counter = tcpIdleTimeoutCount,
                message = "TUN TCP closed idle session after ${idleForMs / 1_000}s: $host:$port",
                suppressedMessage = "TUN TCP: further idle session cleanup logs suppressed",
            )
        }

        fun logPressureCleanup(
            closedCount: Int,
            activeCount: Int,
            maxSessions: Int,
            idleTtlMs: Long,
        ) {
            logLimited(
                counter = pressureCleanupCount,
                message = "TUN TCP session pressure: closed $closedCount session(s) idle for " +
                    "${idleTtlMs / 1_000}s or more; table was $activeCount/$maxSessions",
                suppressedMessage = "TUN TCP: further session-pressure logs suppressed",
                limit = MAX_SESSION_PRESSURE_LOGS,
            )
        }

        fun logDnsFailure(message: String) {
            logLimited(
                counter = dnsFailureCount,
                message = "DNS over SSH failed: $message",
                suppressedMessage = "DNS over SSH: further failure logs suppressed",
            )
        }

        fun markRemoteRead(nowMs: Long) {
            lastRemoteReadAt.set(nowMs)
        }

        fun lastRemoteReadAtMs(): Long = lastRemoteReadAt.get()

        fun enterClientWindowWait() {
            clientWindowWaiters.incrementAndGet()
        }

        fun exitClientWindowWait() {
            clientWindowWaiters.decrementAndGet()
        }

        fun clientWindowWaitCount(): Int = clientWindowWaiters.get()

        fun logTunStall(
            silentForMs: Long,
            activeSessions: Int,
            awaitingClientWindow: Int,
            writerQueueDepth: Int,
            writerQueueCapacity: Int,
        ) {
            logLimited(
                counter = tunStallCount,
                message = "TUN stall: nothing received over SSH for ${silentForMs}ms while apps are " +
                    "still sending; sessions=$activeSessions, waitingForClientWindow=$awaitingClientWindow, " +
                    "tunWriterQueue=$writerQueueDepth/$writerQueueCapacity",
                suppressedMessage = "TUN stall: further stall reports suppressed",
                limit = MAX_TUN_STALL_LOGS,
            )
        }

        fun logClientWindowStall(host: String, port: Int, stalledForMs: Long) {
            logLimited(
                counter = clientWindowStallCount,
                message = "TUN TCP reset $host:$port: the app stopped reading for " +
                    "${stalledForMs / 1_000}s while data kept arriving, and a flow parked that long " +
                    "holds the whole SSH session",
                suppressedMessage = "TUN TCP: further client-window stall logs suppressed",
                limit = MAX_TUN_STALL_LOGS,
            )
        }

        fun logTunStallCleared(stalledForMs: Long) {
            logLimited(
                counter = tunStallCount,
                message = "TUN stall cleared after ${stalledForMs}ms",
                suppressedMessage = "TUN stall: further stall reports suppressed",
                limit = MAX_TUN_STALL_LOGS,
            )
        }

        fun logDnsCacheHit() {
            logLimited(
                counter = dnsCacheHitCount,
                message = "DNS answered from the forwarder cache without a round trip through SSH",
                suppressedMessage = "DNS cache: further local answers are not logged",
                limit = MAX_DNS_CACHE_LOGS,
            )
        }

        fun logTransportLost() {
            logLimited(
                counter = transportLostCount,
                message = "SSH transport is gone while traffic is waiting; asking for an immediate reconnect",
                suppressedMessage = "Kotlin TUN: further transport-loss notices suppressed",
            )
        }

        fun logSilentResolver(resolver: String, next: String?, privateAddress: Boolean, message: String) {
            val why = if (privateAddress) {
                "most likely a resolver on the phone's own network, which the SSH server cannot reach"
            } else {
                message
            }
            val instead = next?.let { "asking $it instead" } ?: "no other resolver left to ask"
            logLimited(
                counter = silentResolverCount,
                message = "DNS over SSH: $resolver did not answer ($why); $instead; skipping it for " +
                    "${TunnelDnsPolicy.SILENT_RESOLVER_COOLDOWN_MS / 60_000} minutes",
                suppressedMessage = "DNS over SSH: further silent-resolver logs suppressed",
            )
        }

        fun logWorkerRejected(poolName: String) {
            logLimited(
                counter = workerRejectedCount,
                message = "Kotlin TUN forwarding $poolName worker pool is saturated; rejecting background task",
                suppressedMessage = "Kotlin TUN forwarding worker saturation logs suppressed",
            )
        }

        fun logSessionLimit(limit: Int) {
            logLimited(
                counter = sessionLimitCount,
                message = "Kotlin TUN TCP session limit reached ($limit); rejecting new flow",
                suppressedMessage = "Kotlin TUN TCP: further session-limit logs suppressed",
            )
        }

        fun logUdpRelayOpen(host: String, udpPort: Int, tcpPort: Int) {
            logLimited(
                counter = udpRelayOpenCount,
                message = "TUN UDP relay: opening reflector TCP transport over SSH to $host:$tcpPort " +
                    "for the UDP flow to port $udpPort",
                suppressedMessage = "TUN UDP relay: further per-flow open logs suppressed",
                limit = MAX_UDP_RELAY_LOGS,
            )
        }

        fun logUdpRelayReady(host: String, udpPort: Int, tcpPort: Int, elapsedMs: Long) {
            logLimited(
                counter = udpRelayReadyCount,
                message = "TUN UDP relay: reflector TCP transport to $host:$tcpPort connected in " +
                    "${elapsedMs}ms; the UDP flow to port $udpPort is carried over it",
                suppressedMessage = "TUN UDP relay: further transport-ready logs suppressed",
                limit = MAX_UDP_RELAY_LOGS,
            )
        }

        fun logUdpRelayFailure(host: String, port: Int, message: String) {
            logLimited(
                counter = udpRelayFailureCount,
                message = "TUN UDP relay failed: $host:$port: $message",
                suppressedMessage = "TUN UDP relay: further failure logs suppressed",
                limit = MAX_UDP_RELAY_LOGS,
            )
        }

        fun logUdpRelayClosed(host: String, port: Int, reason: String) {
            logLimited(
                counter = udpRelayClosedCount,
                message = "TUN UDP relay closed $host:$port: $reason",
                suppressedMessage = "TUN UDP relay: further close logs suppressed",
            )
        }

        fun logUdpRelayDropped(host: String) {
            logLimited(
                counter = udpRelayDroppedCount,
                message = "TUN UDP relay dropped a datagram for $host: relay queue is full",
                suppressedMessage = "TUN UDP relay: further datagram drop logs suppressed",
            )
        }

        fun logUdpRelayLimit(limit: Int) {
            logLimited(
                counter = udpRelayLimitCount,
                message = "TUN UDP relay flow limit reached ($limit); rejecting new flow",
                suppressedMessage = "TUN UDP relay: further flow-limit logs suppressed",
            )
        }

        fun logUnsupportedUdp(host: String, port: Int) {
            logLimited(
                counter = unsupportedUdpCount,
                message = "TUN UDP: $host:$port${describeUdpPort(port)} is not a relayable " +
                    "destination; SSH carries TCP, DNS UDP/53 and VoIP reflector UDP only. " +
                    "The client is told so with ICMP port unreachable, so it can fall back to TCP",
                suppressedMessage = "TUN UDP: further unsupported-destination logs suppressed",
                limit = MAX_UNSUPPORTED_UDP_LOGS,
            )
        }

        fun logUdpRejectionSummary(summary: String) {
            logLimited(
                counter = udpRejectionSummaryCount,
                message = "TUN UDP: $summary",
                suppressedMessage = "TUN UDP: further rejection summaries suppressed",
                limit = MAX_UDP_RELAY_LOGS,
            )
        }

        fun logReflectorUnreachable() {
            logLimited(
                counter = reflectorUnreachableCount,
                message = "TUN UDP relay: this SSH server reaches no TCP port of the Telegram VoIP " +
                    "hosts - neither $REFLECTOR_TCP_PORT nor the media port - so call media cannot be " +
                    "carried through it; the rest of Telegram is unaffected. The same hosts answer on " +
                    "$REFLECTOR_TCP_PORT from other networks, so this is the server's egress path: " +
                    "check its outbound filtering for Telegram VoIP prefixes, or use another server",
                suppressedMessage = "TUN UDP relay: further reachability verdicts suppressed",
            )
        }

        fun logTunWriteDropped() {
            logLimited(
                counter = tunWriteDroppedCount,
                message = "TUN writer queue is full; dropping a best-effort packet",
                suppressedMessage = "TUN writer: further best-effort drop logs suppressed",
            )
        }

        fun logUdpRelayDisabled(host: String, port: Int, cooldownMs: Long) {
            logLimited(
                counter = udpRelayDisabledCount,
                message = "TUN UDP relay disabled for $host:$port for ${cooldownMs / 1_000}s; " +
                    "no reflector TCP port answered through the SSH server",
                suppressedMessage = "TUN UDP relay: further disable logs suppressed",
                limit = MAX_UDP_RELAY_LOGS,
            )
        }

        fun logTelegramTcpOpen(host: String, port: Int) {
            logLimited(
                counter = telegramTcpOpenCount,
                message = "TUN TCP to Telegram: opening SSH direct TCP to $host:$port",
                suppressedMessage = "TUN TCP to Telegram: further open logs suppressed",
                limit = MAX_TELEGRAM_TCP_LOGS,
            )
        }

        fun logTelegramTcpEstablished(host: String, port: Int, elapsedMs: Long) {
            logLimited(
                counter = telegramTcpEstablishedCount,
                message = "TUN TCP to Telegram: $host:$port connected in ${elapsedMs}ms",
                suppressedMessage = "TUN TCP to Telegram: further connect logs suppressed",
                limit = MAX_TELEGRAM_TCP_LOGS,
            )
        }

        fun logTelegramTcpFailure(host: String, port: Int, message: String) {
            logLimited(
                counter = telegramTcpFailureCount,
                message = "TUN TCP to Telegram failed: $host:$port: $message",
                suppressedMessage = "TUN TCP to Telegram: further failure logs suppressed",
                limit = MAX_TELEGRAM_TCP_LOGS,
            )
        }

        private fun logLimited(
            counter: AtomicInteger,
            message: String,
            suppressedMessage: String,
            limit: Int = MAX_DETAILED_FORWARDER_LOGS,
        ) {
            val seen = counter.incrementAndGet()
            when {
                seen <= limit -> log(message)
                seen == limit + 1 -> log(suppressedMessage)
            }
        }
    }

    private data class TcpKey(
        val clientAddress: Int,
        val clientPort: Int,
        val remoteAddress: Int,
        val remotePort: Int,
    )

    private enum class UdpRelayDecision {
        NOT_RELAYABLE,
        ACCEPTED,
        DEFERRED,
    }

    private data class UdpRelayTarget(
        val address: Int,
        val port: Int,
    )

    private data class UdpRejectKey(
        val clientAddress: Int,
        val clientPort: Int,
        val remoteAddress: Int,
        val remotePort: Int,
    )

    private data class UdpKey(
        val clientAddress: Int,
        val clientPort: Int,
        val remoteAddress: Int,
        val remotePort: Int,
    )

    private data class DnsTransport(
        val session: Session,
        val generation: Long,
    )

    internal data class Ipv4Packet(
        val source: Int,
        val destination: Int,
        val protocol: Int,
        val headerLength: Int,
        val totalLength: Int,
        val payloadOffset: Int,
    )

    internal data class TcpPacket(
        val sourcePort: Int,
        val destinationPort: Int,
        val sequence: Long,
        val acknowledgement: Long,
        val flags: Int,
        val window: Int,
        val payloadOffset: Int,
        val payloadLength: Int,
    )

    internal data class UdpPacket(
        val sourcePort: Int,
        val destinationPort: Int,
        val payloadOffset: Int,
        val payloadLength: Int,
    )

    private enum class TcpState {
        CLOSED,
        SYN_RECEIVED,
        ESTABLISHED,
        REMOTE_FIN_SENT,
    }

    internal object PacketCodec {
        fun parseIpv4(buffer: ByteArray, length: Int): Ipv4Packet? {
            if (length < IPV4_MIN_HEADER_SIZE) return null
            val version = (buffer[0].toInt() ushr 4) and 0x0F
            if (version != IPV4_VERSION) return null
            val headerLength = (buffer[0].toInt() and 0x0F) * 4
            if (headerLength < IPV4_MIN_HEADER_SIZE || length < headerLength) return null
            val totalLength = readU16(buffer, 2).coerceAtMost(length)
            if (totalLength <= headerLength) return null
            return Ipv4Packet(
                source = readInt(buffer, 12),
                destination = readInt(buffer, 16),
                protocol = buffer[9].toInt() and 0xFF,
                headerLength = headerLength,
                totalLength = totalLength,
                payloadOffset = headerLength,
            )
        }

        fun parseTcp(buffer: ByteArray, packet: Ipv4Packet): TcpPacket? {
            if (packet.totalLength < packet.payloadOffset + TCP_MIN_HEADER_SIZE) return null
            val offset = packet.payloadOffset
            val tcpHeaderLength = ((buffer[offset + 12].toInt() ushr 4) and 0x0F) * 4
            if (tcpHeaderLength < TCP_MIN_HEADER_SIZE) return null
            val payloadOffset = offset + tcpHeaderLength
            if (packet.totalLength < payloadOffset) return null
            return TcpPacket(
                sourcePort = readU16(buffer, offset),
                destinationPort = readU16(buffer, offset + 2),
                sequence = readU32(buffer, offset + 4),
                acknowledgement = readU32(buffer, offset + 8),
                flags = buffer[offset + 13].toInt() and 0xFF,
                window = readU16(buffer, offset + 14),
                payloadOffset = payloadOffset,
                payloadLength = packet.totalLength - payloadOffset,
            )
        }

        fun parseUdp(buffer: ByteArray, packet: Ipv4Packet): UdpPacket? {
            if (packet.totalLength < packet.payloadOffset + UDP_HEADER_SIZE) return null
            val offset = packet.payloadOffset
            val udpLength = readU16(buffer, offset + 4)
            if (udpLength < UDP_HEADER_SIZE || packet.payloadOffset + udpLength > packet.totalLength) return null
            return UdpPacket(
                sourcePort = readU16(buffer, offset),
                destinationPort = readU16(buffer, offset + 2),
                payloadOffset = offset + UDP_HEADER_SIZE,
                payloadLength = udpLength - UDP_HEADER_SIZE,
            )
        }

        fun buildIpv4Packet(
            id: Int,
            protocol: Int,
            sourceAddress: Int,
            destinationAddress: Int,
            payload: ByteArray,
        ): ByteArray {
            val packet = ByteArray(IPV4_MIN_HEADER_SIZE + payload.size)
            packet[0] = 0x45
            packet[1] = 0
            writeU16(packet, 2, packet.size)
            writeU16(packet, 4, id and 0xFFFF)
            writeU16(packet, 6, IPV4_DONT_FRAGMENT)
            packet[8] = IPV4_TTL.toByte()
            packet[9] = protocol.toByte()
            writeInt(packet, 12, sourceAddress)
            writeInt(packet, 16, destinationAddress)
            val checksum = checksum(packet, 0, IPV4_MIN_HEADER_SIZE)
            writeU16(packet, 10, checksum)
            payload.copyInto(packet, IPV4_MIN_HEADER_SIZE)
            return packet
        }

        fun buildIpv4TcpPacket(
            id: Int,
            sourceAddress: Int,
            destinationAddress: Int,
            sourcePort: Int,
            destinationPort: Int,
            sequence: Long,
            acknowledgement: Long,
            flags: Int,
            advertisedWindow: Int,
            tcpMss: Int,
            payload: ByteArray,
            payloadOffset: Int,
            payloadLength: Int,
        ): ByteArray {
            require(payloadOffset >= 0 && payloadLength >= 0 && payloadOffset + payloadLength <= payload.size)
            val tcpHeaderLength = TCP_MIN_HEADER_SIZE + if (flags.hasFlag(TCP_SYN)) TCP_MSS_OPTION_SIZE else 0
            val packet = ByteArray(IPV4_MIN_HEADER_SIZE + tcpHeaderLength + payloadLength)
            buildIpv4TcpPacketInto(
                destination = packet,
                id = id,
                sourceAddress = sourceAddress,
                destinationAddress = destinationAddress,
                sourcePort = sourcePort,
                destinationPort = destinationPort,
                sequence = sequence,
                acknowledgement = acknowledgement,
                flags = flags,
                advertisedWindow = advertisedWindow,
                tcpMss = tcpMss,
                payload = payload,
                payloadOffset = payloadOffset,
                payloadLength = payloadLength,
            )
            return packet
        }

        fun buildIpv4TcpPacketInto(
            destination: ByteArray,
            id: Int,
            sourceAddress: Int,
            destinationAddress: Int,
            sourcePort: Int,
            destinationPort: Int,
            sequence: Long,
            acknowledgement: Long,
            flags: Int,
            advertisedWindow: Int,
            tcpMss: Int,
            payload: ByteArray,
            payloadOffset: Int,
            payloadLength: Int,
        ): Int {
            require(payloadOffset >= 0 && payloadLength >= 0 && payloadOffset + payloadLength <= payload.size)
            val tcpHeaderLength = TCP_MIN_HEADER_SIZE + if (flags.hasFlag(TCP_SYN)) TCP_MSS_OPTION_SIZE else 0
            val tcpOffset = IPV4_MIN_HEADER_SIZE
            val tcpLength = tcpHeaderLength + payloadLength
            val packetLength = tcpOffset + tcpLength
            require(destination.size >= packetLength) { "Destination buffer is too small for TCP packet" }

            destination[0] = 0x45
            destination[1] = 0
            writeU16(destination, 2, packetLength)
            writeU16(destination, 4, id and 0xFFFF)
            writeU16(destination, 6, IPV4_DONT_FRAGMENT)
            destination[8] = IPV4_TTL.toByte()
            destination[9] = PROTOCOL_TCP.toByte()
            writeU16(destination, 10, 0)
            writeInt(destination, 12, sourceAddress)
            writeInt(destination, 16, destinationAddress)

            writeU16(destination, tcpOffset, sourcePort)
            writeU16(destination, tcpOffset + 2, destinationPort)
            writeU32(destination, tcpOffset + 4, sequence)
            writeU32(destination, tcpOffset + 8, acknowledgement)
            destination[tcpOffset + 12] = ((tcpHeaderLength / 4) shl 4).toByte()
            destination[tcpOffset + 13] = flags.toByte()
            writeU16(destination, tcpOffset + 14, advertisedWindow.coerceIn(0, MAX_TCP_WINDOW))
            writeU16(destination, tcpOffset + 16, 0)
            writeU16(destination, tcpOffset + 18, 0)
            if (flags.hasFlag(TCP_SYN)) {
                destination[tcpOffset + TCP_MIN_HEADER_SIZE] = TCP_OPTION_MSS.toByte()
                destination[tcpOffset + TCP_MIN_HEADER_SIZE + 1] = TCP_MSS_OPTION_SIZE.toByte()
                writeU16(destination, tcpOffset + TCP_MIN_HEADER_SIZE + 2, tcpMss)
            }
            payload.copyInto(
                destination = destination,
                destinationOffset = tcpOffset + tcpHeaderLength,
                startIndex = payloadOffset,
                endIndex = payloadOffset + payloadLength,
            )
            val tcpChecksum = transportChecksum(
                sourceAddress = sourceAddress,
                destinationAddress = destinationAddress,
                protocol = PROTOCOL_TCP,
                buffer = destination,
                offset = tcpOffset,
                length = tcpLength,
            )
            writeU16(destination, tcpOffset + 16, tcpChecksum)
            writeU16(destination, 10, checksum(destination, 0, IPV4_MIN_HEADER_SIZE))
            return packetLength
        }

        fun buildUdpDatagram(
            sourceAddress: Int,
            destinationAddress: Int,
            sourcePort: Int,
            destinationPort: Int,
            payload: ByteArray,
        ): ByteArray {
            val datagram = ByteArray(UDP_HEADER_SIZE + payload.size)
            writeU16(datagram, 0, sourcePort)
            writeU16(datagram, 2, destinationPort)
            writeU16(datagram, 4, datagram.size)
            payload.copyInto(datagram, UDP_HEADER_SIZE)
            val checksum = transportChecksum(
                sourceAddress = sourceAddress,
                destinationAddress = destinationAddress,
                protocol = PROTOCOL_UDP,
                buffer = datagram,
                offset = 0,
                length = datagram.size,
            )
            writeU16(datagram, 6, checksum)
            return datagram
        }

        fun buildIcmpDestinationUnreachable(
            code: Int,
            returnedPacket: ByteArray,
        ): ByteArray {
            val message = ByteArray(ICMP_DESTINATION_UNREACHABLE_HEADER_SIZE + returnedPacket.size)
            message[0] = ICMP_TYPE_DESTINATION_UNREACHABLE.toByte()
            message[1] = code.toByte()
            returnedPacket.copyInto(message, ICMP_DESTINATION_UNREACHABLE_HEADER_SIZE)
            val checksum = checksum(message, 0, message.size)
            writeU16(message, 2, checksum)
            return message
        }

        fun readU16(buffer: ByteArray, offset: Int): Int {
            return ((buffer[offset].toInt() and 0xFF) shl 8) or
                (buffer[offset + 1].toInt() and 0xFF)
        }

        private fun readU32(buffer: ByteArray, offset: Int): Long {
            return (
                ((readU16(buffer, offset).toLong() shl 16) or readU16(buffer, offset + 2).toLong()) and
                    UINT_MASK
                )
        }

        private fun readInt(buffer: ByteArray, offset: Int): Int {
            return ((buffer[offset].toInt() and 0xFF) shl 24) or
                ((buffer[offset + 1].toInt() and 0xFF) shl 16) or
                ((buffer[offset + 2].toInt() and 0xFF) shl 8) or
                (buffer[offset + 3].toInt() and 0xFF)
        }

        private fun writeU16(buffer: ByteArray, offset: Int, value: Int) {
            buffer[offset] = ((value ushr 8) and 0xFF).toByte()
            buffer[offset + 1] = (value and 0xFF).toByte()
        }

        private fun writeU32(buffer: ByteArray, offset: Int, value: Long) {
            val normalized = value and UINT_MASK
            writeU16(buffer, offset, ((normalized ushr 16) and 0xFFFF).toInt())
            writeU16(buffer, offset + 2, (normalized and 0xFFFF).toInt())
        }

        private fun writeInt(buffer: ByteArray, offset: Int, value: Int) {
            buffer[offset] = ((value ushr 24) and 0xFF).toByte()
            buffer[offset + 1] = ((value ushr 16) and 0xFF).toByte()
            buffer[offset + 2] = ((value ushr 8) and 0xFF).toByte()
            buffer[offset + 3] = (value and 0xFF).toByte()
        }

        private fun checksum(buffer: ByteArray, offset: Int, length: Int): Int {
            return finalizeChecksum(sumWords(buffer, offset, length))
        }

        private fun transportChecksum(
            sourceAddress: Int,
            destinationAddress: Int,
            protocol: Int,
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            var sum = 0L
            sum += (sourceAddress ushr 16) and 0xFFFF
            sum += sourceAddress and 0xFFFF
            sum += (destinationAddress ushr 16) and 0xFFFF
            sum += destinationAddress and 0xFFFF
            sum += protocol and 0xFF
            sum += length
            sum += sumWords(buffer, offset, length)
            return finalizeChecksum(sum)
        }

        private fun sumWords(buffer: ByteArray, offset: Int, length: Int): Long {
            var sum = 0L
            var index = offset
            val end = offset + length
            while (index + 1 < end) {
                sum += ((buffer[index].toInt() and 0xFF) shl 8) or
                    (buffer[index + 1].toInt() and 0xFF)
                index += 2
            }
            if (index < end) {
                sum += (buffer[index].toInt() and 0xFF) shl 8
            }
            return sum
        }

        private fun finalizeChecksum(rawSum: Long): Int {
            var sum = rawSum
            while ((sum ushr 16) != 0L) {
                sum = (sum and 0xFFFF) + (sum ushr 16)
            }
            return sum.inv().toInt() and 0xFFFF
        }
    }

    private class NamedThreadFactory(
        private val prefix: String,
    ) : ThreadFactory {
        private val nextId = AtomicInteger(1)

        override fun newThread(runnable: Runnable): Thread {
            return Thread(runnable, "$prefix-${nextId.getAndIncrement()}").apply {
                isDaemon = true
            }
        }
    }

    private companion object {
        const val READ_THREAD_NAME = "kotlin-tun-forwarder"
        const val CONTROL_THREAD_PREFIX = "kotlin-tun-control"
        const val CONNECT_THREAD_PREFIX = "kotlin-tun-connect"
        const val DNS_THREAD_PREFIX = "kotlin-tun-dns"
        const val DNS_TIMEOUT_THREAD_PREFIX = "kotlin-tun-dns-timeout"
        const val REMOTE_READ_THREAD_PREFIX = "kotlin-tun-read"
        const val CLEANUP_THREAD_PREFIX = "kotlin-tun-cleanup"
        const val CONTROL_POOL_NAME = "control"
        const val CONNECT_POOL_NAME = "connect"
        const val DNS_POOL_NAME = "dns"
        const val DNS_TIMEOUT_POOL_NAME = "dns-timeout"
        const val REMOTE_READ_POOL_NAME = "remote-read"
        const val CLEANUP_POOL_NAME = "cleanup"
        const val UDP_RELAY_THREAD_PREFIX = "kotlin-tun-udp-relay"
        const val UDP_RELAY_POOL_NAME = "udp-relay"
        const val UDP_RELAY_WORKER_THREADS = HARD_MAX_ACTIVE_UDP_RELAY_SESSIONS
        const val MAX_UDP_RELAY_QUEUE_SIZE = 256
        const val CONTROL_WORKER_THREADS = 8
        const val MAX_CONTROL_QUEUE_SIZE = 1_024
        const val CONNECT_THREAD_SLACK = 32
        // A query holds its worker until its resolver answers or its time is up.
        const val DNS_WORKER_THREADS = 16
        const val MAX_DNS_QUEUE_SIZE = 256
        const val DNS_TIMEOUT_WORKER_THREADS = 4
        const val MIN_REMOTE_READ_THREADS = 0
        const val REMOTE_READ_THREAD_SLACK = 16
        const val MAX_REMOTE_READ_THREADS =
            HARD_MAX_ACTIVE_TCP_SESSIONS + HARD_MAX_ACTIVE_UDP_RELAY_SESSIONS + REMOTE_READ_THREAD_SLACK
        const val CLEANUP_WORKER_THREADS = 1
        const val WORKER_KEEP_ALIVE_MS = 15_000L
        const val CLEANUP_WORKER_KEEP_ALIVE_MS = 60_000L
        const val MAX_DETAILED_FORWARDER_LOGS = 5
        const val MAX_TELEGRAM_TCP_LOGS = 40
        const val MAX_UDP_RELAY_LOGS = 40
        const val REMOTE_READ_BUFFER_SIZE = 16 * 1024
        const val SSH_CHANNEL_CONNECT_TIMEOUT_MS = 10_000
        const val DNS_PORT = 53
        const val DNS_TCP_LENGTH_SIZE = 2
        const val DNS_MAX_RESPONSE_SIZE = 4_096
        const val DNS_DEGRADATION_FAILURE_THRESHOLD = 3
        const val REMOTE_FIN_SESSION_TTL_MS = 30_000L
        const val CLIENT_FIN_SESSION_TTL_MS = 60_000L
        // Long enough that a parked keep-alive survives: servers hold theirs for 60-120s, and an
        // app that loses one shows the user a reconnect.
        const val PRESSURE_IDLE_SESSION_TTL_MS = 150_000L
        // The table is full and the next flow would be refused, so a stale session has to go.
        const val SATURATED_IDLE_SESSION_TTL_MS = 20_000L
        // One answer per new flow is the point, so the ceiling is generous and the pacing is
        // per-flow: a silent drop costs the client a handshake timeout it does not need to pay.
        const val UDP_REJECT_BURST = 64
        const val UDP_REJECT_REFILL_INTERVAL_MS = 15L
        const val UDP_REJECT_FLOW_INTERVAL_MS = 1_000L
        const val UDP_REJECT_FLOW_TTL_MS = 60_000L
        const val MAX_TRACKED_REJECTED_UDP_FLOWS = 256
        const val UDP_REJECT_SUMMARY_INTERVAL_MS = 60_000L
        const val MAX_SUMMARISED_UDP_PORTS = 16
        const val TOP_SUMMARISED_UDP_PORTS = 3
        const val MAX_UNSUPPORTED_UDP_LOGS = 20
        const val MAX_DNS_CACHE_LOGS = 3
        const val MAX_SESSION_PRESSURE_LOGS = 20
        const val MAX_TUN_STALL_LOGS = 30
        const val STALL_WATCHDOG_INTERVAL_MS = 500L
        const val STALL_WATCHDOG_IDLE_INTERVAL_MS = 5_000L
        const val STALL_SILENCE_THRESHOLD_MS = 1_500L
        const val STALL_CLIENT_ACTIVITY_MS = 1_000L
        const val CLIENT_WINDOW_WAIT_SLICE_MS = 250L
        // Long enough for an app that is merely busy, short enough that a wedged flow cannot hold
        // the shared SSH session for a noticeable time.
        const val CLIENT_WINDOW_STALL_TIMEOUT_MS = 10_000L
        const val TRANSPORT_LOSS_REPORT_INTERVAL_MS = 1_000L
        const val UDP_RELAY_IDLE_CHECK_MS = 15_000L
        const val UDP_RELAY_IDLE_TTL_MS = 45_000L
        const val MAX_PENDING_UDP_UPLINK_BYTES = 64 * 1024
        const val MAX_PENDING_UDP_UPLINK_DATAGRAMS = 256
        const val MAX_UDP_DATAGRAMS_PER_UPLINK_RUN = 16
        const val UDP_RELAY_CONNECT_TIMEOUT_MS = 2_000
        // Long enough that a call stops hammering a dead reflector, short enough that a reflector
        // parked during a flaky minute of transport comes back for the next call.
        const val UDP_RELAY_FAILURE_COOLDOWN_MS = 300_000L
        const val REFLECTOR_TCP_PORT = 443
        const val CONNECT_VERDICT_SLACK_MS = 250
        const val TUN_RELAY_ENQUEUE_WAIT_MS = 25L
        const val PRESSURE_CLEANUP_INITIAL_DELAY_MS = 1_000L
        const val PRESSURE_CLEANUP_RECHECK_MS = 20_000L
        const val ACTIVITY_TIMESTAMP_UPDATE_INTERVAL_MS = 1_000L
        const val IPV4_VERSION = 4
        const val IPV4_MIN_HEADER_SIZE = 20
        const val IPV4_DONT_FRAGMENT = 0x4000
        const val IPV4_TTL = 64
        const val TCP_MIN_HEADER_SIZE = 20
        const val UDP_HEADER_SIZE = 8
        const val PROTOCOL_TCP = 6
        const val PROTOCOL_UDP = 17
        const val PROTOCOL_ICMP = 1
        const val ICMP_TYPE_DESTINATION_UNREACHABLE = 3
        const val ICMP_CODE_PORT_UNREACHABLE = 3
        const val ICMP_DESTINATION_UNREACHABLE_HEADER_SIZE = 8
        const val ICMP_ORIGINAL_TRANSPORT_BYTES = 8
        const val TCP_FIN = 0x01
        const val TCP_SYN = 0x02
        const val TCP_RST = 0x04
        const val TCP_PSH = 0x08
        const val TCP_ACK = 0x10
        const val TCP_OPTION_MSS = 2
        const val TCP_MSS_OPTION_SIZE = 4
        const val UINT_MASK = 0xFFFF_FFFFL
        val EMPTY_BYTES = ByteArray(0)
    }
}

private fun sleepAfterEmptyRead() {
    runCatching {
        Thread.sleep(EMPTY_READ_BACKOFF_MS)
    }
}

private fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()

private fun ceilDiv(value: Int, divisor: Int): Int {
    require(value > 0 && divisor > 0)
    return ((value.toLong() + divisor - 1L) / divisor).toInt()
}

/** Removes a completed entry only while the registry still points at that exact instance. */
internal fun <K : Any, V : Any> removeExpectedConcurrentEntry(
    entries: ConcurrentHashMap<K, V>,
    key: K,
    expectedValue: V,
): Boolean = entries.remove(key, expectedValue)

private fun interface UdpDatagramSender {
    fun send(
        sourceAddress: Int,
        destinationAddress: Int,
        sourcePort: Int,
        destinationPort: Int,
        payload: ByteArray,
    )
}

private fun interface TcpPacketSender {
    fun send(
        sourceAddress: Int,
        destinationAddress: Int,
        sourcePort: Int,
        destinationPort: Int,
        sequence: Long,
        acknowledgement: Long,
        flags: Int,
        advertisedWindow: Int,
        payload: ByteArray,
        payloadOffset: Int,
        payloadLength: Int,
    )
}

private fun Int.hasFlag(flag: Int): Boolean = (this and flag) == flag

private fun seqPlus(
    sequence: Long,
    increment: Int,
): Long = (sequence + increment) and 0xFFFF_FFFFL

private fun seqMinus(
    sequence: Long,
    decrement: Int,
): Long = (sequence - decrement) and 0xFFFF_FFFFL

private fun seqDistance(
    start: Long,
    end: Long,
): Long = (end - start) and 0xFFFF_FFFFL

private fun initialSequence(): Long = System.nanoTime() and 0xFFFF_FFFFL

/** Turns the ports that actually show up in these logs into something readable at a glance. */
private fun describeUdpPort(port: Int): String = when (port) {
    HTTP_PORT, HTTPS_PORT -> " (QUIC)"
    STUN_PORT, STUN_ALT_PORT, TURN_TLS_PORT -> " (STUN/TURN)"
    NTP_PORT -> " (NTP)"
    else -> ""
}

private fun addressToString(address: Int): String {
    return listOf(
        (address ushr 24) and 0xFF,
        (address ushr 16) and 0xFF,
        (address ushr 8) and 0xFF,
        address and 0xFF,
    ).joinToString(".")
}

private fun readFully(input: InputStream, buffer: ByteArray) {
    var offset = 0
    while (offset < buffer.size) {
        val read = input.read(buffer, offset, buffer.size - offset)
        if (read < 0) throw EOFException("Unexpected EOF")
        offset += read
    }
}

private const val EMPTY_READ_BACKOFF_MS = 5L
