package com.stansful.sshvpnclient.vpn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Ipv4TcpPacketCodecTest {
    @Test
    fun `SYN packet carries configured MSS window and valid checksums`() {
        val sourceAddress = 0x0A00_0001
        val destinationAddress = 0x0808_0808
        val packet = KotlinTunForwarder.PacketCodec.buildIpv4TcpPacket(
            id = 7,
            sourceAddress = sourceAddress,
            destinationAddress = destinationAddress,
            sourcePort = 443,
            destinationPort = 12_345,
            sequence = 10L,
            acknowledgement = 20L,
            flags = 0x12,
            advertisedWindow = 12_345,
            tcpMss = 8_460,
            payload = byteArrayOf(),
            payloadOffset = 0,
            payloadLength = 0,
        )

        assertEquals(44, packet.size)
        assertEquals(44, readU16(packet, 2))
        assertEquals(24, ((packet[32].toInt() ushr 4) and 0x0F) * 4)
        assertEquals(12_345, readU16(packet, 34))
        assertEquals(2, packet[40].toInt() and 0xFF)
        assertEquals(4, packet[41].toInt() and 0xFF)
        assertEquals(8_460, readU16(packet, 42))
        assertEquals(0, internetChecksum(packet, 0, 20))
        assertEquals(0, tcpChecksum(packet, sourceAddress, destinationAddress))
    }

    @Test
    fun `payload slice is copied directly into one IPv4 TCP packet`() {
        val packet = KotlinTunForwarder.PacketCodec.buildIpv4TcpPacket(
            id = 8,
            sourceAddress = 0x0101_0101,
            destinationAddress = 0x0A00_0002,
            sourcePort = 80,
            destinationPort = 23_456,
            sequence = 30L,
            acknowledgement = 40L,
            flags = 0x18,
            advertisedWindow = 65_535,
            tcpMss = 1_460,
            payload = byteArrayOf(9, 8, 7, 6),
            payloadOffset = 1,
            payloadLength = 2,
        )

        assertEquals(42, packet.size)
        assertArrayEquals(byteArrayOf(8, 7), packet.copyOfRange(40, 42))
        assertEquals(0, internetChecksum(packet, 0, 20))
        assertEquals(0, tcpChecksum(packet, 0x0101_0101, 0x0A00_0002))
    }

    @Test
    fun `build into reusable MTU buffer clears stale header bytes and returns exact length`() {
        val sourceAddress = 0x0A00_0001
        val destinationAddress = 0x0808_0808
        val payload = byteArrayOf(9, 8, 7, 6)
        val expected = KotlinTunForwarder.PacketCodec.buildIpv4TcpPacket(
            id = 9,
            sourceAddress = sourceAddress,
            destinationAddress = destinationAddress,
            sourcePort = 443,
            destinationPort = 32_000,
            sequence = 50L,
            acknowledgement = 60L,
            flags = 0x18,
            advertisedWindow = 42_000,
            tcpMss = 8_460,
            payload = payload,
            payloadOffset = 1,
            payloadLength = 2,
        )
        val reusable = ByteArray(8_500) { 0x7F }

        val length = KotlinTunForwarder.PacketCodec.buildIpv4TcpPacketInto(
            destination = reusable,
            id = 9,
            sourceAddress = sourceAddress,
            destinationAddress = destinationAddress,
            sourcePort = 443,
            destinationPort = 32_000,
            sequence = 50L,
            acknowledgement = 60L,
            flags = 0x18,
            advertisedWindow = 42_000,
            tcpMss = 8_460,
            payload = payload,
            payloadOffset = 1,
            payloadLength = 2,
        )

        assertEquals(expected.size, length)
        assertArrayEquals(expected, reusable.copyOf(length))
        assertEquals(0, internetChecksum(reusable, 0, 20))
        assertEquals(0, tcpChecksum(reusable.copyOf(length), sourceAddress, destinationAddress))
        assertEquals(0x7F, reusable[length].toInt() and 0xFF)
    }

    private fun tcpChecksum(packet: ByteArray, sourceAddress: Int, destinationAddress: Int): Int {
        val tcpLength = packet.size - 20
        var sum = 0L
        sum += (sourceAddress ushr 16) and 0xFFFF
        sum += sourceAddress and 0xFFFF
        sum += (destinationAddress ushr 16) and 0xFFFF
        sum += destinationAddress and 0xFFFF
        sum += 6
        sum += tcpLength
        return internetChecksum(packet, 20, tcpLength, sum)
    }

    private fun internetChecksum(
        bytes: ByteArray,
        offset: Int,
        length: Int,
        initialSum: Long = 0L,
    ): Int {
        var sum = initialSum
        var index = offset
        val end = offset + length
        while (index + 1 < end) {
            sum += ((bytes[index].toInt() and 0xFF) shl 8) or (bytes[index + 1].toInt() and 0xFF)
            index += 2
        }
        if (index < end) {
            sum += (bytes[index].toInt() and 0xFF) shl 8
        }
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return sum.inv().toInt() and 0xFFFF
    }

    private fun readU16(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
    }
}
