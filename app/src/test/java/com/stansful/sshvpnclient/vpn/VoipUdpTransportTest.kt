package com.stansful.sshvpnclient.vpn

import java.net.ProtocolException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramNetworksTest {
    @Test
    fun `observed reflector addresses are recognised`() {
        assertTrue(TelegramNetworks.containsIpv4(ipv4(91, 108, 13, 2)))
        assertTrue(TelegramNetworks.containsIpv4(ipv4(91, 108, 9, 57)))
        assertTrue(TelegramNetworks.containsIpv4(ipv4(91, 108, 17, 21)))
        assertTrue(TelegramNetworks.containsIpv4(ipv4(149, 154, 167, 51)))
        assertTrue(TelegramNetworks.containsIpv4(ipv4(185, 76, 151, 200)))
    }

    @Test
    fun `addresses just outside the announced prefixes are rejected`() {
        assertFalse(TelegramNetworks.containsIpv4(ipv4(91, 108, 3, 255)))
        assertFalse(TelegramNetworks.containsIpv4(ipv4(91, 108, 24, 1)))
        assertFalse(TelegramNetworks.containsIpv4(ipv4(149, 154, 176, 1)))
        assertFalse(TelegramNetworks.containsIpv4(ipv4(185, 76, 152, 1)))
        assertFalse(TelegramNetworks.containsIpv4(ipv4(8, 8, 8, 8)))
        assertFalse(TelegramNetworks.containsIpv4(ipv4(10, 10, 0, 2)))
    }

    private fun ipv4(first: Int, second: Int, third: Int, fourth: Int): Int {
        return (first shl 24) or (second shl 16) or (third shl 8) or fourth
    }
}

class MtProtoIntermediateTransportTest {
    @Test
    fun `prologue is four 0xEE bytes`() {
        assertArrayEquals(
            byteArrayOf(0xEE.toByte(), 0xEE.toByte(), 0xEE.toByte(), 0xEE.toByte()),
            MtProtoIntermediateTransport.prologue(),
        )
        assertEquals(MtProtoIntermediateTransport.PROLOGUE_SIZE, MtProtoIntermediateTransport.prologue().size)
    }

    @Test
    fun `the prologue is handed out as a fresh copy`() {
        val first = MtProtoIntermediateTransport.prologue()
        first[0] = 0

        assertEquals(0xEE, MtProtoIntermediateTransport.prologue()[0].toInt() and 0xFF)
    }

    @Test
    fun `frame carries a little endian byte length prefix`() {
        val payload = ByteArray(260) { index -> index.toByte() }

        val frame = MtProtoIntermediateTransport.encodeFrame(payload)

        assertEquals(264, frame.size)
        assertEquals(0x04, frame[0].toInt() and 0xFF)
        assertEquals(0x01, frame[1].toInt() and 0xFF)
        assertEquals(0x00, frame[2].toInt() and 0xFF)
        assertEquals(0x00, frame[3].toInt() and 0xFF)
        assertArrayEquals(payload, frame.copyOfRange(4, frame.size))
    }

    @Test
    fun `empty and oversized payloads are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            MtProtoIntermediateTransport.encodeFrame(ByteArray(0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            MtProtoIntermediateTransport.encodeFrame(ByteArray(MtProtoIntermediateTransport.MAX_FRAME_BYTES + 1))
        }
    }
}

class MtProtoIntermediateDecoderTest {
    @Test
    fun `coalesced frames are split back into datagrams`() {
        val decoder = MtProtoIntermediateDecoder()
        val first = byteArrayOf(1, 2, 3, 4)
        val second = byteArrayOf(9, 9, 9, 9, 9, 9, 9, 9)
        val stream = MtProtoIntermediateTransport.encodeFrame(first) +
            MtProtoIntermediateTransport.encodeFrame(second)
        val received = mutableListOf<ByteArray>()

        decoder.offer(stream, stream.size) { frame -> received += frame }

        assertEquals(2, received.size)
        assertArrayEquals(first, received[0])
        assertArrayEquals(second, received[1])
        assertEquals(0, decoder.bufferedBytes)
    }

    @Test
    fun `a frame split across reads is reassembled`() {
        val decoder = MtProtoIntermediateDecoder()
        val payload = ByteArray(1_000) { index -> (index % 251).toByte() }
        val stream = MtProtoIntermediateTransport.encodeFrame(payload)
        val received = mutableListOf<ByteArray>()

        var offset = 0
        while (offset < stream.size) {
            val chunkSize = minOf(97, stream.size - offset)
            val chunk = stream.copyOfRange(offset, offset + chunkSize)
            decoder.offer(chunk, chunk.size) { frame -> received += frame }
            offset += chunkSize
        }

        assertEquals(1, received.size)
        assertArrayEquals(payload, received[0])
    }

    @Test
    fun `a full remote read on top of a partial frame still fits the buffer`() {
        // MTU-sized frames read in 16 KiB chunks always leave a long partial frame buffered, which is
        // exactly what used to overflow the decoder and kill the flow mid-call.
        val decoder = MtProtoIntermediateDecoder(maxFrameBytes = 8_472, maxChunkBytes = 16 * 1024)
        val payload = ByteArray(8_472) { index -> (index % 251).toByte() }
        val frame = MtProtoIntermediateTransport.encodeFrame(payload)
        val stream = frame + frame + frame + frame
        val received = mutableListOf<ByteArray>()

        var offset = 0
        while (offset < stream.size) {
            val chunkSize = minOf(16 * 1024, stream.size - offset)
            val chunk = stream.copyOfRange(offset, offset + chunkSize)
            decoder.offer(chunk, chunk.size) { decoded -> received += decoded }
            offset += chunkSize
        }

        assertEquals(4, received.size)
        received.forEach { decoded -> assertArrayEquals(payload, decoded) }
        assertEquals(0, decoder.bufferedBytes)
    }

    @Test
    fun `a chunk larger than the configured limit is rejected`() {
        val decoder = MtProtoIntermediateDecoder(maxChunkBytes = 1_024)

        assertThrows(IllegalArgumentException::class.java) {
            decoder.offer(ByteArray(2_048), 2_048) { }
        }
    }

    @Test
    fun `an out of range frame length aborts the stream`() {
        val decoder = MtProtoIntermediateDecoder(maxFrameBytes = 1_024)
        val header = byteArrayOf(0x00, 0x00, 0x01, 0x00)

        assertThrows(ProtocolException::class.java) {
            decoder.offer(header, header.size) { }
        }
    }

    @Test
    fun `a zero length frame aborts the stream`() {
        val decoder = MtProtoIntermediateDecoder()
        val header = byteArrayOf(0x00, 0x00, 0x00, 0x00)

        assertThrows(ProtocolException::class.java) {
            decoder.offer(header, header.size) { }
        }
    }
}

class ReflectorHandshakeTest {
    @Test
    fun `the 40 byte reflector ping is recognised`() {
        assertTrue(ReflectorHandshake.isUdpHello(udpHello()))
    }

    @Test
    fun `data packets are not mistaken for the ping`() {
        val dataPacket = ByteArray(64) { index -> index.toByte() }
        assertFalse(ReflectorHandshake.isUdpHello(dataPacket))

        val wrongMarker = udpHello().also { hello -> hello[28] = 0xFF.toByte() }
        assertFalse(ReflectorHandshake.isUdpHello(wrongMarker))

        val wrongFiller = udpHello().also { hello -> hello[20] = 0x00 }
        assertFalse(ReflectorHandshake.isUdpHello(wrongFiller))

        assertFalse(ReflectorHandshake.isUdpHello(udpHello().copyOf(39)))
    }

    @Test
    fun `the TCP hello is the peer tag followed by four zero bytes`() {
        val hello = ReflectorHandshake.tcpHello(udpHello())

        assertEquals(ReflectorHandshake.TCP_HELLO_SIZE, hello.size)
        assertArrayEquals(ByteArray(16) { index -> index.toByte() }, hello.copyOfRange(0, 16))
        assertArrayEquals(ByteArray(4), hello.copyOfRange(16, 20))
    }

    private fun udpHello(): ByteArray {
        val hello = ByteArray(ReflectorHandshake.UDP_HELLO_SIZE)
        for (index in 0 until 16) {
            hello[index] = index.toByte()
        }
        for (index in 16 until 28) {
            hello[index] = 0xFF.toByte()
        }
        hello[28] = 0xFE.toByte()
        for (index in 29 until 32) {
            hello[index] = 0xFF.toByte()
        }
        hello[39] = 123
        return hello
    }
}

class TokenBucketRateLimiterTest {
    @Test
    fun `burst is capped and refilled over time`() {
        val limiter = TokenBucketRateLimiter(capacity = 3, refillIntervalMs = 100L)

        assertTrue(limiter.tryAcquire(1_000L))
        assertTrue(limiter.tryAcquire(1_000L))
        assertTrue(limiter.tryAcquire(1_000L))
        assertFalse(limiter.tryAcquire(1_000L))

        assertTrue(limiter.tryAcquire(1_150L))
        assertFalse(limiter.tryAcquire(1_150L))

        assertTrue(limiter.tryAcquire(9_000L))
        assertTrue(limiter.tryAcquire(9_000L))
        assertTrue(limiter.tryAcquire(9_000L))
        assertFalse(limiter.tryAcquire(9_000L))
    }

    @Test
    fun `capacity and interval must be positive`() {
        assertThrows(IllegalArgumentException::class.java) {
            TokenBucketRateLimiter(capacity = 0, refillIntervalMs = 100L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TokenBucketRateLimiter(capacity = 1, refillIntervalMs = 0L)
        }
    }
}
