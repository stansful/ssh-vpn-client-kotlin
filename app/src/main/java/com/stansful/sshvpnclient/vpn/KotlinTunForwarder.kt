package com.stansful.sshvpnclient.vpn

import android.os.ParcelFileDescriptor
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal class KotlinTunForwarder(
    private val vpnInterface: ParcelFileDescriptor,
    private val sshSession: Session,
    private val log: (String) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private val stoppedLatch = CountDownLatch(1)
    private val sessions = ConcurrentHashMap<TcpKey, TcpProxySession>()
    private val executor = Executors.newCachedThreadPool(NamedThreadFactory(WORKER_THREAD_PREFIX))
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
                    executor.shutdownNow()
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
        executor.shutdownNow()
    }

    fun awaitStopped(timeoutMs: Long) {
        stoppedLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun readTunLoop(input: FileInputStream) {
        val buffer = ByteArray(TUN_BUFFER_SIZE)
        while (running.get()) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
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
                    executor = executor,
                    log = log,
                    packetSender = ::sendTcpPacket,
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
                log("Custom Kotlin forwarder dropped non-DNS UDP traffic; only TCP and DNS UDP/53 are supported")
            }
            return
        }
        val payload = buffer.copyOfRange(udp.payloadOffset, udp.payloadOffset + udp.payloadLength)
        executor.execute {
            forwardDnsQuery(
                query = payload,
                clientAddress = packet.source,
                clientPort = udp.sourcePort,
                dnsServerAddress = packet.destination,
            )
        }
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
            log("DNS over SSH failed: ${error.message ?: error::class.java.simpleName}")
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

    private class TcpProxySession(
        private val key: TcpKey,
        private val sshSession: Session,
        private val executor: ExecutorService,
        private val log: (String) -> Unit,
        private val packetSender: TcpPacketSender,
        private val onClosed: (TcpKey) -> Unit,
    ) {
        private val lock = Any()
        private val pendingClientWrites = ArrayDeque<ByteArray>()
        private var state = TcpState.CLOSED
        private var clientNextSequence = 0L
        private var serverNextSequence = initialSequence()
        private var clientWindow = DEFAULT_TCP_WINDOW
        private var connecting = false
        private var writeScheduled = false
        private var channel: ChannelDirectTCPIP? = null
        private var remoteOutput: OutputStream? = null

        fun onSyn(clientSequence: Long) {
            val responseSequence: Long
            val responseAcknowledgement: Long
            synchronized(lock) {
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
            var shouldConnect = false
            var shouldClose = false
            synchronized(lock) {
                clientWindow = window
                if (state == TcpState.SYN_RECEIVED && acknowledgement == serverNextSequence) {
                    state = TcpState.ESTABLISHED
                    shouldConnect = true
                } else if (state == TcpState.REMOTE_FIN_SENT && acknowledgement == serverNextSequence) {
                    shouldClose = true
                }
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
            var shouldConnect = false
            var shouldScheduleWrite = false
            var acknowledgement: Long
            var responseSequence: Long
            synchronized(lock) {
                if (state == TcpState.CLOSED || state == TcpState.REMOTE_FIN_SENT) return
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
            val responseSequence: Long
            val responseAcknowledgement: Long
            val shouldClose: Boolean
            synchronized(lock) {
                if (state == TcpState.CLOSED) return
                if (finSequence == clientNextSequence) {
                    clientNextSequence = seqPlus(clientNextSequence, 1)
                }
                responseSequence = serverNextSequence
                responseAcknowledgement = clientNextSequence
                shouldClose = state == TcpState.REMOTE_FIN_SENT
            }
            sendTcp(responseSequence, responseAcknowledgement, TCP_ACK, EMPTY_BYTES)
            if (shouldClose) {
                close()
            }
        }

        fun close() {
            val activeChannel: ChannelDirectTCPIP?
            synchronized(lock) {
                if (state == TcpState.CLOSED) return
                state = TcpState.CLOSED
                pendingClientWrites.clear()
                activeChannel = channel
                channel = null
                remoteOutput = null
            }
            activeChannel?.disconnect()
            onClosed(key)
        }

        private fun ensureRemoteConnecting() {
            var shouldStart = false
            synchronized(lock) {
                if (!connecting && channel == null && state != TcpState.CLOSED) {
                    connecting = true
                    shouldStart = true
                }
            }
            if (shouldStart) {
                executor.execute { connectRemote() }
            }
        }

        private fun connectRemote() {
            var nextChannel: ChannelDirectTCPIP? = null
            try {
                val host = addressToString(key.remoteAddress)
                log("TUN TCP: opening SSH direct TCP to $host:${key.remotePort}")
                nextChannel = sshSession.openChannel("direct-tcpip") as ChannelDirectTCPIP
                nextChannel.setHost(host)
                nextChannel.setPort(key.remotePort)
                nextChannel.setOrgIPAddress(addressToString(key.clientAddress))
                nextChannel.setOrgPort(key.clientPort)
                val input = nextChannel.inputStream
                val output = nextChannel.outputStream
                nextChannel.connect(SSH_CHANNEL_CONNECT_TIMEOUT_MS)
                synchronized(lock) {
                    if (state == TcpState.CLOSED) {
                        nextChannel.disconnect()
                        return
                    }
                    channel = nextChannel
                    remoteOutput = output
                    connecting = false
                }
                executor.execute { readRemote(input) }
                scheduleClientFlush()
            } catch (error: Exception) {
                synchronized(lock) {
                    connecting = false
                }
                nextChannel?.disconnect()
                log(
                    "TUN TCP failed: ${addressToString(key.remoteAddress)}:${key.remotePort}: " +
                        (error.message ?: error::class.java.simpleName),
                )
                sendResetAndClose()
            }
        }

        private fun scheduleClientFlush() {
            var shouldSchedule = false
            synchronized(lock) {
                if (!writeScheduled) {
                    writeScheduled = true
                    shouldSchedule = true
                }
            }
            if (shouldSchedule) {
                executor.execute { flushClientWrites() }
            }
        }

        private fun flushClientWrites() {
            while (true) {
                val nextPayload: ByteArray
                val output: OutputStream
                synchronized(lock) {
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
                } catch (error: Exception) {
                    log("TUN TCP write failed: ${error.message ?: error::class.java.simpleName}")
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
                    if (read == 0) continue
                    var offset = 0
                    while (offset < read) {
                        val chunkSize = minOf(MAX_TCP_PAYLOAD_SIZE, read - offset)
                        val payload = buffer.copyOfRange(offset, offset + chunkSize)
                        sendRemoteData(payload)
                        offset += chunkSize
                    }
                }
                sendRemoteFinAndClose()
            } catch (_: Exception) {
                sendResetAndClose()
            }
        }

        private fun sendRemoteData(payload: ByteArray) {
            val sequence: Long
            val acknowledgement: Long
            synchronized(lock) {
                if (state == TcpState.CLOSED) return
                sequence = serverNextSequence
                acknowledgement = clientNextSequence
                serverNextSequence = seqPlus(serverNextSequence, payload.size)
            }
            sendTcp(sequence, acknowledgement, TCP_PSH or TCP_ACK, payload)
        }

        private fun sendRemoteFinAndClose() {
            val sequence: Long
            val acknowledgement: Long
            val activeChannel: ChannelDirectTCPIP?
            synchronized(lock) {
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
            executor.execute {
                runCatching { Thread.sleep(REMOTE_FIN_SESSION_TTL_MS) }
                    .onFailure { return@execute }
                synchronized(lock) {
                    if (state != TcpState.REMOTE_FIN_SENT) return@execute
                }
                close()
            }
        }

        private fun sendResetAndClose() {
            val sequence: Long
            val acknowledgement: Long
            synchronized(lock) {
                if (state == TcpState.CLOSED) return
                sequence = serverNextSequence
                acknowledgement = clientNextSequence
            }
            sendTcp(sequence, acknowledgement, TCP_RST or TCP_ACK, EMPTY_BYTES)
            close()
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
        const val WORKER_THREAD_PREFIX = "kotlin-tun-worker"
        const val TUN_BUFFER_SIZE = 32 * 1024
        const val REMOTE_READ_BUFFER_SIZE = 16 * 1024
        const val MAX_TCP_PAYLOAD_SIZE = 1_320
        const val SSH_CHANNEL_CONNECT_TIMEOUT_MS = 10_000
        const val DNS_PORT = 53
        const val DNS_TCP_LENGTH_SIZE = 2
        const val DNS_MAX_RESPONSE_SIZE = 4_096
        const val REMOTE_FIN_SESSION_TTL_MS = 30_000L
        const val IPV4_VERSION = 4
        const val IPV4_MIN_HEADER_SIZE = 20
        const val IPV4_DONT_FRAGMENT = 0x4000
        const val IPV4_TTL = 64
        const val TCP_MIN_HEADER_SIZE = 20
        const val UDP_HEADER_SIZE = 8
        const val PROTOCOL_TCP = 6
        const val PROTOCOL_UDP = 17
        const val TCP_FIN = 0x01
        const val TCP_SYN = 0x02
        const val TCP_RST = 0x04
        const val TCP_PSH = 0x08
        const val TCP_ACK = 0x10
        const val DEFAULT_TCP_WINDOW = 65_535
        const val TCP_MSS = 1_320
        const val UINT_MASK = 0xFFFF_FFFFL
        val TCP_SYN_OPTIONS = byteArrayOf(2, 4, ((TCP_MSS ushr 8) and 0xFF).toByte(), (TCP_MSS and 0xFF).toByte())
        val EMPTY_BYTES = ByteArray(0)
    }
}

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
