package com.stansful.sshvpnclient.vpn

import java.net.ProtocolException

/**
 * IPv4 prefixes announced by Telegram (`core.telegram.org/resources/cidr.txt`).
 *
 * Voice and video reflectors always live inside these ranges, so a non-DNS UDP flow that targets
 * them is reflector traffic and can be carried over the reflector's own TCP transport instead of
 * being dropped: SSH `direct-tcpip` cannot carry UDP at all. That transport listens on 443; the
 * UDP media ports drop TCP SYNs.
 */
internal object TelegramNetworks {
    private val prefixes: List<Ipv4Prefix> = listOf(
        prefix(91, 105, 192, 0, 23),
        prefix(91, 108, 4, 0, 22),
        prefix(91, 108, 8, 0, 22),
        prefix(91, 108, 12, 0, 22),
        prefix(91, 108, 16, 0, 22),
        prefix(91, 108, 20, 0, 22),
        prefix(91, 108, 56, 0, 22),
        prefix(149, 154, 160, 0, 20),
        prefix(185, 76, 151, 0, 24),
    )

    fun containsIpv4(address: Int): Boolean {
        return prefixes.any { candidate -> (address and candidate.mask) == candidate.network }
    }

    private fun prefix(
        first: Int,
        second: Int,
        third: Int,
        fourth: Int,
        prefixLength: Int,
    ): Ipv4Prefix {
        require(prefixLength in 1..32) { "IPv4 prefix length must be in 1..32" }
        val address = (first shl 24) or (second shl 16) or (third shl 8) or fourth
        val mask = -1 shl (32 - prefixLength)
        return Ipv4Prefix(network = address and mask, mask = mask)
    }

    private data class Ipv4Prefix(
        val network: Int,
        val mask: Int,
    )
}

/**
 * MTProto `intermediate` transport framing, the wire format tgcalls uses for reflector traffic over
 * TCP: a single `0xEEEEEEEE` prologue per connection, then a `uint32` little-endian payload length
 * followed by the payload. The payload is byte-identical to the datagram the same client would have
 * sent over UDP, which is what makes a UDP-over-TCP shim possible.
 */
internal object MtProtoIntermediateTransport {
    const val LENGTH_SIZE = 4
    const val MAX_FRAME_BYTES = 64 * 1024

    const val PROLOGUE_SIZE = 4

    private val prologueBytes = byteArrayOf(PROLOGUE_BYTE, PROLOGUE_BYTE, PROLOGUE_BYTE, PROLOGUE_BYTE)

    /** Returns a fresh copy so callers cannot mutate the shared prologue. */
    fun prologue(): ByteArray = prologueBytes.copyOf()

    fun encodeFrame(payload: ByteArray): ByteArray {
        require(payload.isNotEmpty()) { "Reflector frame must not be empty" }
        require(payload.size <= MAX_FRAME_BYTES) { "Reflector frame must not exceed $MAX_FRAME_BYTES bytes" }
        val frame = ByteArray(LENGTH_SIZE + payload.size)
        writeLengthLittleEndian(frame, payload.size)
        payload.copyInto(frame, LENGTH_SIZE)
        return frame
    }

    fun readLengthLittleEndian(buffer: ByteArray, offset: Int): Long {
        return (buffer[offset].toLong() and 0xFF) or
            ((buffer[offset + 1].toLong() and 0xFF) shl 8) or
            ((buffer[offset + 2].toLong() and 0xFF) shl 16) or
            ((buffer[offset + 3].toLong() and 0xFF) shl 24)
    }

    private fun writeLengthLittleEndian(buffer: ByteArray, value: Int) {
        buffer[0] = (value and 0xFF).toByte()
        buffer[1] = ((value ushr 8) and 0xFF).toByte()
        buffer[2] = ((value ushr 16) and 0xFF).toByte()
        buffer[3] = ((value ushr 24) and 0xFF).toByte()
    }
}

private const val PROLOGUE_BYTE: Byte = 0xEE.toByte()

/**
 * Incremental decoder for [MtProtoIntermediateTransport] frames. The reflector answers on a byte
 * stream, so frames arrive split or coalesced and have to be reassembled before they can be handed
 * back to the client as UDP datagrams.
 */
internal class MtProtoIntermediateDecoder(
    private val maxFrameBytes: Int = MtProtoIntermediateTransport.MAX_FRAME_BYTES,
    private val maxChunkBytes: Int = DEFAULT_MAX_CHUNK_BYTES,
) {
    init {
        require(maxFrameBytes in 1..MtProtoIntermediateTransport.MAX_FRAME_BYTES) {
            "Frame limit must be in 1..${MtProtoIntermediateTransport.MAX_FRAME_BYTES}"
        }
        require(maxChunkBytes > 0) { "Chunk limit must be positive" }
    }

    // A whole chunk is appended before parsing, so the buffer has to fit the largest read on top of
    // the longest partial frame it can already hold.
    private val maxBufferBytes = MtProtoIntermediateTransport.LENGTH_SIZE + maxFrameBytes + maxChunkBytes
    private var buffer = ByteArray(INITIAL_CAPACITY)
    private var readOffset = 0
    private var writeOffset = 0

    val bufferedBytes: Int
        get() = writeOffset - readOffset

    fun offer(
        chunk: ByteArray,
        chunkLength: Int,
        onFrame: (ByteArray) -> Unit,
    ) {
        append(chunk, chunkLength)
        while (true) {
            val available = writeOffset - readOffset
            if (available < MtProtoIntermediateTransport.LENGTH_SIZE) break
            val frameLength = MtProtoIntermediateTransport.readLengthLittleEndian(buffer, readOffset)
            if (frameLength <= 0L || frameLength > maxFrameBytes.toLong()) {
                throw ProtocolException("Reflector frame length $frameLength is out of 1..$maxFrameBytes")
            }
            val frameSize = frameLength.toInt()
            if (available < MtProtoIntermediateTransport.LENGTH_SIZE + frameSize) break
            val payloadStart = readOffset + MtProtoIntermediateTransport.LENGTH_SIZE
            onFrame(buffer.copyOfRange(payloadStart, payloadStart + frameSize))
            readOffset = payloadStart + frameSize
        }
        compact()
    }

    private fun append(chunk: ByteArray, chunkLength: Int) {
        require(chunkLength >= 0) { "Chunk length must not be negative" }
        require(chunkLength <= chunk.size) { "Chunk length must fit its buffer" }
        require(chunkLength <= maxChunkBytes) { "Chunk must not exceed the configured $maxChunkBytes bytes" }
        if (chunkLength == 0) return
        compact()
        val required = writeOffset + chunkLength
        if (required > maxBufferBytes) {
            throw ProtocolException("Reflector stream buffer exceeded $maxBufferBytes bytes")
        }
        if (required > buffer.size) {
            var capacity = buffer.size.coerceAtLeast(INITIAL_CAPACITY)
            while (capacity < required) {
                capacity = capacity shl 1
            }
            buffer = buffer.copyOf(capacity)
        }
        chunk.copyInto(buffer, writeOffset, 0, chunkLength)
        writeOffset += chunkLength
    }

    private fun compact() {
        if (readOffset == 0) return
        if (readOffset == writeOffset) {
            readOffset = 0
            writeOffset = 0
            return
        }
        buffer.copyInto(buffer, 0, readOffset, writeOffset)
        writeOffset -= readOffset
        readOffset = 0
    }

    private companion object {
        const val INITIAL_CAPACITY = 4 * 1024
        const val DEFAULT_MAX_CHUNK_BYTES = 64 * 1024
    }
}

/**
 * Reflector handshake helpers.
 *
 * A client that believes it speaks UDP opens the conversation with a 40-byte ping
 * (`peer_tag(16) | FF x12 | FE | FF x3 | 8 bytes`), while the reflector's TCP transport expects a
 * 20-byte hello (`peer_tag(16) | 00 00 00 00`) that registers the connection. The relay translates
 * every ping into that hello and forwards the remaining datagrams verbatim: data packets carry
 * their own `peer_tag | sender_tag | big-endian size` header and need no rewriting.
 */
internal object ReflectorHandshake {
    const val PEER_TAG_SIZE = 16
    const val UDP_HELLO_SIZE = 40
    const val TCP_HELLO_SIZE = 20

    fun isUdpHello(payload: ByteArray, payloadLength: Int = payload.size): Boolean {
        if (payloadLength != UDP_HELLO_SIZE) return false
        for (index in PEER_TAG_SIZE until PEER_TAG_SIZE + UDP_HELLO_FILLER_SIZE) {
            if (payload[index] != FILLER_BYTE) return false
        }
        if (payload[MARKER_OFFSET] != MARKER_BYTE) return false
        for (index in MARKER_OFFSET + 1 until MARKER_OFFSET + 1 + MARKER_TAIL_SIZE) {
            if (payload[index] != FILLER_BYTE) return false
        }
        return true
    }

    /**
     * The answer the client's UDP state machine is waiting for.
     *
     * tgcalls treats a reflector port as ready as soon as an inbound packet carries the peer tag,
     * and treats a packet whose bytes 16..27 are `0xFF` as a control packet rather than media - the
     * shape of the ping itself. Its TCP path never needs this because a connected socket is ready
     * by definition, so the relay echoes the ping back once the TCP hello is on the wire and the
     * client can start using the reflector it can no longer reach over UDP.
     */
    fun readyPong(udpHello: ByteArray): ByteArray {
        require(isUdpHello(udpHello)) { "A ready pong is built from the ${UDP_HELLO_SIZE}-byte ping" }
        return udpHello.copyOf()
    }

    fun tcpHello(peerTagSource: ByteArray): ByteArray {
        require(peerTagSource.size >= PEER_TAG_SIZE) {
            "Peer tag source must hold at least $PEER_TAG_SIZE bytes"
        }
        val hello = ByteArray(TCP_HELLO_SIZE)
        peerTagSource.copyInto(hello, 0, 0, PEER_TAG_SIZE)
        return hello
    }

    private const val UDP_HELLO_FILLER_SIZE = 12
    private const val MARKER_OFFSET = 28
    private const val MARKER_TAIL_SIZE = 3
    private const val FILLER_BYTE: Byte = 0xFF.toByte()
    private const val MARKER_BYTE: Byte = 0xFE.toByte()
}

/**
 * Token bucket that keeps unsupported-UDP rejections from amplifying into the TUN write queue.
 * One inbound datagram used to cost one outbound ICMP packet, so a VoIP burst could saturate the
 * writer and tear the whole tunnel down.
 */
internal class TokenBucketRateLimiter(
    private val capacity: Int,
    private val refillIntervalMs: Long,
) {
    init {
        require(capacity > 0) { "Rate limiter capacity must be positive" }
        require(refillIntervalMs > 0L) { "Rate limiter refill interval must be positive" }
    }

    private var tokens = capacity
    private var lastRefillAtMs = Long.MIN_VALUE

    @Synchronized
    fun tryAcquire(nowMs: Long): Boolean {
        if (lastRefillAtMs == Long.MIN_VALUE) {
            lastRefillAtMs = nowMs
        }
        val elapsedMs = nowMs - lastRefillAtMs
        if (elapsedMs < 0L) {
            lastRefillAtMs = nowMs
        } else if (elapsedMs >= refillIntervalMs) {
            val intervals = elapsedMs / refillIntervalMs
            val refill = intervals.coerceAtMost(capacity.toLong()).toInt()
            tokens = (tokens + refill).coerceAtMost(capacity)
            lastRefillAtMs += intervals * refillIntervalMs
        }
        if (tokens <= 0) return false
        tokens -= 1
        return true
    }
}
