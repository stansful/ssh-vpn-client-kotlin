package com.stansful.sshvpnclient.vpn

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.Session
import java.io.EOFException
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class KotlinTunForwarder(
    private val vpnInterface: ParcelFileDescriptor,
    private val sshSession: Session,
    private val log: (String) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private val stoppedLatch = CountDownLatch(1)
    private val sessions = ConcurrentHashMap<TcpKey, TcpProxySession>()
    private val diagnostics = ForwarderDiagnostics(log)
    private val executors = ForwarderExecutors(diagnostics)
    private val packetId = AtomicInteger(1)
    private val writeLock = Any()

    @Volatile
    private var output: FileOutputStream? = null

    @Volatile
    private var readThread: Thread? = null

    @Volatile
    private var unsupportedUdpLogged = false

    fun start(onStopped: (String) -> Unit) {
        if (!running.compareAndSet(false, true)) return

        val input = FileInputStream(vpnInterface.fileDescriptor)
        output = FileOutputStream(vpnInterface.fileDescriptor)
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
                    executors.shutdownNow()
                    runCatching { input.close() }
                    runCatching { output?.close() }
                    stoppedLatch.countDown()
                    if (stopReason != "Stopped") {
                        onStopped(stopReason)
                    }
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
        runCatching { vpnInterface.close() }
        executors.shutdownNow()
    }

    fun awaitStopped(timeoutMs: Long) {
        stoppedLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun readTunLoop(input: FileInputStream) {
        val buffer = ByteArray(TUN_BUFFER_SIZE)
        while (running.get()) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) {
                sleepAfterEmptyRead()
                continue
            }
            handleIpv4Packet(buffer, read)
        }
    }

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
        val payload = if (tcp.payloadLength > 0) {
            buffer.copyOfRange(tcp.payloadOffset, tcp.payloadOffset + tcp.payloadLength)
        } else {
            EMPTY_BYTES
        }

        if (tcp.flags.hasFlag(TCP_RST)) {
            sessions.remove(key)?.close()
            return
        }

        val session = if (tcp.flags.hasFlag(TCP_SYN) && !tcp.flags.hasFlag(TCP_ACK)) {
            sessions.computeIfAbsent(key) {
                TcpProxySession(
                    key = key,
                    sshSession = sshSession,
                    executors = executors,
                    diagnostics = diagnostics,
                    packetSender = ::sendTcpPacket,
                    activeSessionCount = sessions::size,
                    onClosed = sessions::remove,
                )
            }.also { it.onSyn(tcp.sequence) }
        } else {
            sessions[key]
        }

        if (session == null) {
            sendTcpReset(packet, tcp)
            return
        }

        if (tcp.flags.hasFlag(TCP_ACK)) {
            session.onAck(tcp.acknowledgement, tcp.window)
        }
        if (payload.isNotEmpty()) {
            session.onClientData(tcp.sequence, payload)
        }
        if (tcp.flags.hasFlag(TCP_FIN)) {
            session.onClientFin(seqPlus(tcp.sequence, payload.size))
        }
    }

    private fun handleUdpPacket(buffer: ByteArray, packet: Ipv4Packet) {
        val udp = PacketCodec.parseUdp(buffer, packet) ?: return
        if (udp.destinationPort != DNS_PORT) {
            if (!unsupportedUdpLogged) {
                unsupportedUdpLogged = true
                log(
                    "Custom Kotlin forwarder rejects non-DNS UDP with ICMP unreachable; " +
                        "only TCP and DNS UDP/53 are supported",
                )
            }
            sendIcmpPortUnreachable(buffer, packet)
            return
        }
        val payload = buffer.copyOfRange(udp.payloadOffset, udp.payloadOffset + udp.payloadLength)
        val scheduled = executors.executeControl {
            forwardDnsQuery(
                query = payload,
                clientAddress = packet.source,
                clientPort = udp.sourcePort,
                dnsServerAddress = packet.destination,
            )
        }
        if (!scheduled) {
            diagnostics.logDnsFailure("forwarder control queue is saturated")
        }
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
        writePacket(
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
    ) {
        if (!running.get()) return
        var channel: ChannelDirectTCPIP? = null
        try {
            channel = openDirectTcpChannel(addressToString(dnsServerAddress), DNS_PORT, clientAddress, clientPort)
            val input = channel.inputStream
            val output = channel.outputStream
            output.write((query.size ushr 8) and 0xFF)
            output.write(query.size and 0xFF)
            output.write(query)
            output.flush()

            val lengthPrefix = ByteArray(DNS_TCP_LENGTH_SIZE)
            readFully(input, lengthPrefix)
            val responseLength = PacketCodec.readU16(lengthPrefix, 0)
            if (responseLength <= 0 || responseLength > DNS_MAX_RESPONSE_SIZE) return

            val response = ByteArray(responseLength)
            readFully(input, response)
            sendUdpPacket(
                sourceAddress = dnsServerAddress,
                destinationAddress = clientAddress,
                sourcePort = DNS_PORT,
                destinationPort = clientPort,
                payload = response,
            )
        } catch (error: Exception) {
            diagnostics.logDnsFailure(error.message ?: error::class.java.simpleName)
        } finally {
            channel?.disconnect()
        }
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
        payload: ByteArray,
    ) {
        val tcpSegment = PacketCodec.buildTcpSegment(
            sourceAddress = sourceAddress,
            destinationAddress = destinationAddress,
            sourcePort = sourcePort,
            destinationPort = destinationPort,
            sequence = sequence,
            acknowledgement = acknowledgement,
            flags = flags,
            payload = payload,
        )
        writePacket(
            PacketCodec.buildIpv4Packet(
                id = packetId.getAndIncrement(),
                protocol = PROTOCOL_TCP,
                sourceAddress = sourceAddress,
                destinationAddress = destinationAddress,
                payload = tcpSegment,
            ),
        )
    }

    private fun sendUdpPacket(
        sourceAddress: Int,
        destinationAddress: Int,
        sourcePort: Int,
        destinationPort: Int,
        payload: ByteArray,
    ) {
        val udpDatagram = PacketCodec.buildUdpDatagram(
            sourceAddress = sourceAddress,
            destinationAddress = destinationAddress,
            sourcePort = sourcePort,
            destinationPort = destinationPort,
            payload = payload,
        )
        writePacket(
            PacketCodec.buildIpv4Packet(
                id = packetId.getAndIncrement(),
                protocol = PROTOCOL_UDP,
                sourceAddress = sourceAddress,
                destinationAddress = destinationAddress,
                payload = udpDatagram,
            ),
        )
    }

    private fun writePacket(packet: ByteArray) {
        if (!running.get()) return
        synchronized(writeLock) {
            output?.write(packet)
            output?.flush()
        }
    }

    private fun openDirectTcpChannel(
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
        channel.connect(SSH_CHANNEL_CONNECT_TIMEOUT_MS)
        return channel
    }

    private fun closeSessions() {
        sessions.values.forEach { session ->
            runCatching { session.close() }
        }
        sessions.clear()
    }

    private class ForwarderExecutors(
        private val diagnostics: ForwarderDiagnostics,
    ) {
        private val controlExecutor = ThreadPoolExecutor(
            CONTROL_WORKER_THREADS,
            CONTROL_WORKER_THREADS,
            WORKER_KEEP_ALIVE_MS,
            TimeUnit.MILLISECONDS,
            LinkedBlockingQueue(MAX_CONTROL_QUEUE_SIZE),
            NamedThreadFactory(CONTROL_THREAD_PREFIX),
            ThreadPoolExecutor.AbortPolicy(),
        )
        private val remoteReadExecutor = ThreadPoolExecutor(
            MIN_REMOTE_READ_THREADS,
            MAX_REMOTE_READ_THREADS,
            WORKER_KEEP_ALIVE_MS,
            TimeUnit.MILLISECONDS,
            SynchronousQueue(),
            NamedThreadFactory(REMOTE_READ_THREAD_PREFIX),
            ThreadPoolExecutor.AbortPolicy(),
        )
        private val cleanupExecutor = ScheduledThreadPoolExecutor(
            CLEANUP_WORKER_THREADS,
            NamedThreadFactory(CLEANUP_THREAD_PREFIX),
        ).apply {
            removeOnCancelPolicy = true
        }

        fun executeControl(task: () -> Unit): Boolean {
            return execute(CONTROL_POOL_NAME, controlExecutor, task)
        }

        fun executeRemoteRead(task: () -> Unit): Boolean {
            return execute(REMOTE_READ_POOL_NAME, remoteReadExecutor, task)
        }

        fun scheduleCleanup(
            delayMs: Long,
            task: () -> Unit,
        ): Boolean {
            return try {
                cleanupExecutor.schedule({ task() }, delayMs, TimeUnit.MILLISECONDS)
                true
            } catch (_: RejectedExecutionException) {
                diagnostics.logWorkerRejected(CLEANUP_POOL_NAME)
                false
            }
        }

        fun shutdownNow() {
            controlExecutor.shutdownNow()
            remoteReadExecutor.shutdownNow()
            cleanupExecutor.shutdownNow()
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
        private val onClosed: (TcpKey) -> Unit,
    ) {
        private val lock = ReentrantLock()
        private val sendWindowChanged = lock.newCondition()
        private val pendingClientWrites = ArrayDeque<ByteArray>()
        private var state = TcpState.CLOSED
        private var clientNextSequence = 0L
        private val initialServerSequence = initialSequence()
        private var serverFirstUnackedSequence = initialServerSequence
        private var serverNextSequence = initialServerSequence
        private var clientWindow = DEFAULT_TCP_WINDOW
        private var connecting = false
        private var writeScheduled = false
        private var clientFinReceived = false
        private var clientFinCleanupScheduled = false
        private var channel: ChannelDirectTCPIP? = null
        private var remoteOutput: OutputStream? = null

        @Volatile
        private var lastActivityAtMs = elapsedRealtimeMs()

        init {
            scheduleIdleCleanup(TCP_IDLE_SESSION_TTL_MS)
        }

        fun onSyn(clientSequence: Long) {
            markActivity()
            val responseSequence: Long
            val responseAcknowledgement: Long
            lock.withLock {
                if (state == TcpState.CLOSED) {
                    state = TcpState.SYN_RECEIVED
                    clientNextSequence = seqPlus(clientSequence, 1)
                    responseSequence = serverNextSequence
                    responseAcknowledgement = clientNextSequence
                    serverNextSequence = seqPlus(serverNextSequence, 1)
                } else {
                    responseSequence = seqMinus(serverNextSequence, 1)
                    responseAcknowledgement = clientNextSequence
                }
            }
            sendTcp(responseSequence, responseAcknowledgement, TCP_SYN or TCP_ACK, EMPTY_BYTES)
        }

        fun onAck(
            acknowledgement: Long,
            window: Int,
        ) {
            markActivity()
            var shouldConnect = false
            var shouldClose = false
            lock.withLock {
                clientWindow = window
                updateServerAckLocked(acknowledgement)
                if (state == TcpState.SYN_RECEIVED && acknowledgement == serverNextSequence) {
                    state = TcpState.ESTABLISHED
                    shouldConnect = true
                } else if (state == TcpState.REMOTE_FIN_SENT && serverFirstUnackedSequence == serverNextSequence) {
                    shouldClose = true
                }
                sendWindowChanged.signalAll()
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
            payload: ByteArray,
        ) {
            markActivity()
            var shouldConnect = false
            var shouldScheduleWrite = false
            var acknowledgement: Long
            var responseSequence: Long
            lock.withLock {
                if (state == TcpState.CLOSED || state == TcpState.REMOTE_FIN_SENT || clientFinReceived) return
                if (state == TcpState.SYN_RECEIVED) {
                    state = TcpState.ESTABLISHED
                    shouldConnect = true
                }
                if (sequence != clientNextSequence) {
                    acknowledgement = clientNextSequence
                    responseSequence = serverNextSequence
                } else {
                    clientNextSequence = seqPlus(clientNextSequence, payload.size)
                    pendingClientWrites.add(payload)
                    shouldScheduleWrite = true
                    acknowledgement = clientNextSequence
                    responseSequence = serverNextSequence
                }
            }

            sendTcp(responseSequence, acknowledgement, TCP_ACK, EMPTY_BYTES)
            if (shouldConnect) {
                ensureRemoteConnecting()
            }
            if (shouldScheduleWrite) {
                scheduleClientFlush()
            }
        }

        fun onClientFin(finSequence: Long) {
            markActivity()
            val responseSequence: Long
            val responseAcknowledgement: Long
            val remoteOutputToClose: OutputStream?
            val shouldScheduleClientFinCleanup: Boolean
            val shouldClose: Boolean
            lock.withLock {
                if (state == TcpState.CLOSED) return
                if (finSequence == clientNextSequence) {
                    clientNextSequence = seqPlus(clientNextSequence, 1)
                }
                remoteOutputToClose = if (!clientFinReceived) remoteOutput else null
                if (!clientFinReceived) {
                    clientFinReceived = true
                    pendingClientWrites.clear()
                    remoteOutput = null
                }
                responseSequence = serverNextSequence
                responseAcknowledgement = clientNextSequence
                shouldClose = state == TcpState.REMOTE_FIN_SENT
                shouldScheduleClientFinCleanup = clientFinReceived && !shouldClose
            }
            sendTcp(responseSequence, responseAcknowledgement, TCP_ACK, EMPTY_BYTES)
            runCatching { remoteOutputToClose?.close() }
            if (shouldClose) {
                close()
            } else if (shouldScheduleClientFinCleanup) {
                scheduleClientFinCleanup()
            }
        }

        fun close() {
            val activeChannel: ChannelDirectTCPIP?
            lock.withLock {
                if (state == TcpState.CLOSED) return
                state = TcpState.CLOSED
                pendingClientWrites.clear()
                activeChannel = channel
                channel = null
                remoteOutput = null
                sendWindowChanged.signalAll()
            }
            activeChannel?.disconnect()
            onClosed(key)
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
                val scheduled = executors.executeControl { connectRemote() }
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
            try {
                diagnostics.logTcpOpen(host, key.remotePort)
                nextChannel = sshSession.openChannel("direct-tcpip") as ChannelDirectTCPIP
                nextChannel.setHost(host)
                nextChannel.setPort(key.remotePort)
                nextChannel.setOrgIPAddress(addressToString(key.clientAddress))
                nextChannel.setOrgPort(key.clientPort)
                val input = nextChannel.inputStream
                val output = nextChannel.outputStream
                nextChannel.connect(SSH_CHANNEL_CONNECT_TIMEOUT_MS)
                markActivity()
                val closeRemoteOutput: Boolean
                lock.withLock {
                    if (state == TcpState.CLOSED) {
                        nextChannel.disconnect()
                        return
                    }
                    channel = nextChannel
                    closeRemoteOutput = clientFinReceived
                    remoteOutput = if (clientFinReceived) null else output
                    connecting = false
                    sendWindowChanged.signalAll()
                }
                if (closeRemoteOutput) {
                    runCatching { output.close() }
                    scheduleClientFinCleanup()
                }
                val readScheduled = executors.executeRemoteRead { readRemote(input) }
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
                diagnostics.logTcpFailure(
                    host = host,
                    port = key.remotePort,
                    message = error.message ?: error::class.java.simpleName,
                )
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
                val scheduled = executors.executeControl { flushClientWrites() }
                if (!scheduled) {
                    lock.withLock {
                        writeScheduled = false
                    }
                    sendResetAndClose()
                }
            }
        }

        private fun flushClientWrites() {
            while (true) {
                val nextPayload: ByteArray
                val output: OutputStream
                lock.withLock {
                    val remote = remoteOutput
                    if (remote == null || state == TcpState.CLOSED) {
                        writeScheduled = false
                        return
                    }
                    val queued = pendingClientWrites.poll()
                    if (queued == null) {
                        writeScheduled = false
                        return
                    }
                    nextPayload = queued
                    output = remote
                }
                try {
                    output.write(nextPayload)
                    output.flush()
                    markActivity()
                } catch (error: Exception) {
                    diagnostics.logTcpWriteFailure(error.message ?: error::class.java.simpleName)
                    sendResetAndClose()
                    return
                }
            }
        }

        private fun readRemote(input: InputStream) {
            val buffer = ByteArray(REMOTE_READ_BUFFER_SIZE)
            try {
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) {
                        sleepAfterEmptyRead()
                        continue
                    }
                    markActivity()
                    var offset = 0
                    while (offset < read) {
                        val chunkSize = minOf(MAX_TCP_PAYLOAD_SIZE, read - offset)
                        val payload = buffer.copyOfRange(offset, offset + chunkSize)
                        if (!sendRemoteData(payload)) return
                        offset += chunkSize
                    }
                }
                sendRemoteFinAndClose()
            } catch (_: Exception) {
                sendResetAndClose()
            }
        }

        private fun scheduleClientFinCleanup() {
            var shouldSchedule = false
            lock.withLock {
                if (!clientFinCleanupScheduled && state != TcpState.CLOSED && state != TcpState.REMOTE_FIN_SENT) {
                    clientFinCleanupScheduled = true
                    shouldSchedule = true
                }
            }
            if (!shouldSchedule) return

            val scheduled = executors.scheduleCleanup(CLIENT_FIN_SESSION_TTL_MS) {
                val shouldClose: Boolean
                lock.withLock {
                    shouldClose = state != TcpState.CLOSED && state != TcpState.REMOTE_FIN_SENT && clientFinReceived
                }
                if (shouldClose) {
                    diagnostics.logClientFinTimeout(addressToString(key.remoteAddress), key.remotePort)
                    sendResetAndClose()
                }
            }
            if (!scheduled) {
                close()
            }
        }

        private fun scheduleIdleCleanup(delayMs: Long) {
            val scheduled = executors.scheduleCleanup(delayMs.coerceAtLeast(TCP_IDLE_SESSION_MIN_CHECK_MS)) {
                cleanupIdleSession()
            }
            if (!scheduled) {
                close()
            }
        }

        private fun cleanupIdleSession() {
            val idleForMs = elapsedRealtimeMs() - lastActivityAtMs
            if (idleForMs < TCP_IDLE_SESSION_TTL_MS) {
                scheduleIdleCleanup(TCP_IDLE_SESSION_TTL_MS - idleForMs)
                return
            }

            val shouldClose = lock.withLock {
                state != TcpState.CLOSED && state != TcpState.REMOTE_FIN_SENT
            }
            if (shouldClose) {
                diagnostics.logIdleTimeout(addressToString(key.remoteAddress), key.remotePort, idleForMs)
                sendResetAndClose()
            }
        }

        private fun markActivity() {
            lastActivityAtMs = elapsedRealtimeMs()
        }

        private fun sendRemoteData(payload: ByteArray): Boolean {
            var offset = 0
            while (offset < payload.size) {
                val sequence: Long
                val acknowledgement: Long
                val nextPayload: ByteArray
                lock.withLock {
                    while (state != TcpState.CLOSED && availableSendWindowLocked() <= 0) {
                        try {
                            sendWindowChanged.await(TCP_WINDOW_WAIT_MS, TimeUnit.MILLISECONDS)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return false
                        }
                    }
                    if (state == TcpState.CLOSED) return false
                    val chunkSize = minOf(
                        MAX_TCP_PAYLOAD_SIZE,
                        payload.size - offset,
                        availableSendWindowLocked(),
                    )
                    sequence = serverNextSequence
                    acknowledgement = clientNextSequence
                    serverNextSequence = seqPlus(serverNextSequence, chunkSize)
                    nextPayload = payload.copyOfRange(offset, offset + chunkSize)
                }
                sendTcp(sequence, acknowledgement, TCP_PSH or TCP_ACK, nextPayload)
                offset += nextPayload.size
            }
            return true
        }

        private fun sendRemoteFinAndClose() {
            val sequence: Long
            val acknowledgement: Long
            val activeChannel: ChannelDirectTCPIP?
            lock.withLock {
                if (state == TcpState.CLOSED) return
                state = TcpState.REMOTE_FIN_SENT
                sequence = serverNextSequence
                acknowledgement = clientNextSequence
                serverNextSequence = seqPlus(serverNextSequence, 1)
                activeChannel = channel
                channel = null
                remoteOutput = null
            }
            activeChannel?.disconnect()
            sendTcp(sequence, acknowledgement, TCP_FIN or TCP_ACK, EMPTY_BYTES)
            val scheduled = executors.scheduleCleanup(REMOTE_FIN_SESSION_TTL_MS) {
                lock.withLock {
                    if (state != TcpState.REMOTE_FIN_SENT) return@scheduleCleanup
                }
                close()
            }
            if (!scheduled) {
                close()
            }
        }

        private fun sendResetAndClose() {
            val sequence: Long
            val acknowledgement: Long
            lock.withLock {
                if (state == TcpState.CLOSED) return
                sequence = serverNextSequence
                acknowledgement = clientNextSequence
            }
            sendTcp(sequence, acknowledgement, TCP_RST or TCP_ACK, EMPTY_BYTES)
            close()
        }

        private fun updateServerAckLocked(acknowledgement: Long) {
            val outstanding = seqDistance(serverFirstUnackedSequence, serverNextSequence)
            val acknowledged = seqDistance(serverFirstUnackedSequence, acknowledgement)
            if (acknowledged in 1..outstanding) {
                serverFirstUnackedSequence = acknowledgement
            }
        }

        private fun availableSendWindowLocked(): Int {
            val bytesInFlight = seqDistance(serverFirstUnackedSequence, serverNextSequence)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
            return (clientWindow - bytesInFlight).coerceAtLeast(0)
        }

        private fun sendTcp(
            sequence: Long,
            acknowledgement: Long,
            flags: Int,
            payload: ByteArray,
        ) {
            packetSender(
                key.remoteAddress,
                key.clientAddress,
                key.remotePort,
                key.clientPort,
                sequence,
                acknowledgement,
                flags,
                payload,
            )
        }
    }

    private class ForwarderDiagnostics(
        private val log: (String) -> Unit,
    ) {
        private val tcpOpenCount = AtomicInteger(0)
        private val tcpFailureCount = AtomicInteger(0)
        private val tcpWriteFailureCount = AtomicInteger(0)
        private val tcpClientFinTimeoutCount = AtomicInteger(0)
        private val tcpIdleTimeoutCount = AtomicInteger(0)
        private val dnsFailureCount = AtomicInteger(0)
        private val workerRejectedCount = AtomicInteger(0)

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

        fun logDnsFailure(message: String) {
            logLimited(
                counter = dnsFailureCount,
                message = "DNS over SSH failed: $message",
                suppressedMessage = "DNS over SSH: further failure logs suppressed",
            )
        }

        fun logWorkerRejected(poolName: String) {
            logLimited(
                counter = workerRejectedCount,
                message = "Kotlin TUN forwarding $poolName worker pool is saturated; rejecting background task",
                suppressedMessage = "Kotlin TUN forwarding worker saturation logs suppressed",
            )
        }

        private fun logLimited(
            counter: AtomicInteger,
            message: String,
            suppressedMessage: String,
        ) {
            when (counter.incrementAndGet()) {
                in 1..MAX_DETAILED_FORWARDER_LOGS -> log(message)
                MAX_DETAILED_FORWARDER_LOGS + 1 -> log(suppressedMessage)
            }
        }
    }

    private data class TcpKey(
        val clientAddress: Int,
        val clientPort: Int,
        val remoteAddress: Int,
        val remotePort: Int,
    )

    private data class Ipv4Packet(
        val source: Int,
        val destination: Int,
        val protocol: Int,
        val headerLength: Int,
        val totalLength: Int,
        val payloadOffset: Int,
    )

    private data class TcpPacket(
        val sourcePort: Int,
        val destinationPort: Int,
        val sequence: Long,
        val acknowledgement: Long,
        val flags: Int,
        val window: Int,
        val payloadOffset: Int,
        val payloadLength: Int,
    )

    private data class UdpPacket(
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

    private object PacketCodec {
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

        fun buildTcpSegment(
            sourceAddress: Int,
            destinationAddress: Int,
            sourcePort: Int,
            destinationPort: Int,
            sequence: Long,
            acknowledgement: Long,
            flags: Int,
            payload: ByteArray,
        ): ByteArray {
            val options = if (flags.hasFlag(TCP_SYN)) TCP_SYN_OPTIONS else EMPTY_BYTES
            val headerLength = TCP_MIN_HEADER_SIZE + options.size
            val segment = ByteArray(headerLength + payload.size)
            writeU16(segment, 0, sourcePort)
            writeU16(segment, 2, destinationPort)
            writeU32(segment, 4, sequence)
            writeU32(segment, 8, acknowledgement)
            segment[12] = ((headerLength / 4) shl 4).toByte()
            segment[13] = flags.toByte()
            writeU16(segment, 14, DEFAULT_TCP_WINDOW)
            options.copyInto(segment, TCP_MIN_HEADER_SIZE)
            payload.copyInto(segment, headerLength)
            val checksum = transportChecksum(sourceAddress, destinationAddress, PROTOCOL_TCP, segment)
            writeU16(segment, 16, checksum)
            return segment
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
            val checksum = transportChecksum(sourceAddress, destinationAddress, PROTOCOL_UDP, datagram)
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
            payload: ByteArray,
        ): Int {
            var sum = 0L
            sum += (sourceAddress ushr 16) and 0xFFFF
            sum += sourceAddress and 0xFFFF
            sum += (destinationAddress ushr 16) and 0xFFFF
            sum += destinationAddress and 0xFFFF
            sum += protocol and 0xFF
            sum += payload.size
            sum += sumWords(payload, 0, payload.size)
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
        const val REMOTE_READ_THREAD_PREFIX = "kotlin-tun-read"
        const val CLEANUP_THREAD_PREFIX = "kotlin-tun-cleanup"
        const val CONTROL_POOL_NAME = "control"
        const val REMOTE_READ_POOL_NAME = "remote-read"
        const val CLEANUP_POOL_NAME = "cleanup"
        const val CONTROL_WORKER_THREADS = 8
        const val MAX_CONTROL_QUEUE_SIZE = 1_024
        const val MIN_REMOTE_READ_THREADS = 0
        const val MAX_REMOTE_READ_THREADS = 128
        const val CLEANUP_WORKER_THREADS = 1
        const val WORKER_KEEP_ALIVE_MS = 15_000L
        const val MAX_DETAILED_FORWARDER_LOGS = 5
        const val TUN_BUFFER_SIZE = 32 * 1024
        const val REMOTE_READ_BUFFER_SIZE = 16 * 1024
        const val MAX_TCP_PAYLOAD_SIZE = 1_320
        const val SSH_CHANNEL_CONNECT_TIMEOUT_MS = 10_000
        const val DNS_PORT = 53
        const val DNS_TCP_LENGTH_SIZE = 2
        const val DNS_MAX_RESPONSE_SIZE = 4_096
        const val REMOTE_FIN_SESSION_TTL_MS = 30_000L
        const val CLIENT_FIN_SESSION_TTL_MS = 10_000L
        const val TCP_IDLE_SESSION_TTL_MS = 20_000L
        const val TCP_IDLE_SESSION_MIN_CHECK_MS = 1_000L
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
        const val DEFAULT_TCP_WINDOW = 65_535
        const val TCP_MSS = 1_320
        const val TCP_WINDOW_WAIT_MS = 250L
        const val UINT_MASK = 0xFFFF_FFFFL
        val TCP_SYN_OPTIONS = byteArrayOf(2, 4, ((TCP_MSS ushr 8) and 0xFF).toByte(), (TCP_MSS and 0xFF).toByte())
        val EMPTY_BYTES = ByteArray(0)
    }
}

private fun sleepAfterEmptyRead() {
    runCatching {
        Thread.sleep(EMPTY_READ_BACKOFF_MS)
    }
}

private fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()

private typealias TcpPacketSender = (
    sourceAddress: Int,
    destinationAddress: Int,
    sourcePort: Int,
    destinationPort: Int,
    sequence: Long,
    acknowledgement: Long,
    flags: Int,
    payload: ByteArray,
) -> Unit

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
