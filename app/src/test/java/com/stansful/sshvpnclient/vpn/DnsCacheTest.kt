package com.stansful.sshvpnclient.vpn

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsMessageTest {
    @Test
    fun `the question key ignores name case and separates resolvers`() {
        val lower = DnsMessage.questionKey(query(name = "example.com"), dnsServerAddress = 1)
        val mixed = DnsMessage.questionKey(query(name = "ExAmPlE.CoM"), dnsServerAddress = 1)
        val otherResolver = DnsMessage.questionKey(query(name = "example.com"), dnsServerAddress = 2)
        val otherType = DnsMessage.questionKey(query(name = "example.com", type = TYPE_AAAA), dnsServerAddress = 1)

        assertNotNull(lower)
        assertEquals(lower, mixed)
        assertTrue(lower != otherResolver)
        assertTrue(lower != otherType)
    }

    @Test
    fun `answers and malformed queries are not keyed`() {
        assertNull(DnsMessage.questionKey(response(name = "example.com", ttls = listOf(60L)), dnsServerAddress = 1))
        assertNull(DnsMessage.questionKey(ByteArray(4), dnsServerAddress = 1))
        assertNull(DnsMessage.questionKey(query(name = "example.com").copyOf(14), dnsServerAddress = 1))
    }

    @Test
    fun `the shortest record ttl decides how long an answer lives`() {
        val answer = response(name = "example.com", ttls = listOf(300L, 60L, 900L))

        assertEquals(60L, DnsMessage.cacheableTtlSeconds(answer))
    }

    @Test
    fun `uncacheable answers are reported as such`() {
        assertNull(DnsMessage.cacheableTtlSeconds(response(name = "example.com", ttls = listOf(0L))))
        assertNull(DnsMessage.cacheableTtlSeconds(response(name = "example.com", ttls = listOf(60L), rcode = 2)))
        assertNull(DnsMessage.cacheableTtlSeconds(response(name = "example.com", ttls = emptyList())))
        assertNull(DnsMessage.cacheableTtlSeconds(query(name = "example.com")))
    }

    @Test
    fun `a reused answer carries the new id and the remaining ttl`() {
        val answer = response(name = "example.com", ttls = listOf(300L), transactionId = 0x1111)
        val nextQuery = query(name = "example.com", transactionId = 0x2222)

        val reused = DnsMessage.copyAged(
            response = answer,
            query = nextQuery,
            elapsedSeconds = 100L,
            remainingSeconds = 600L,
        )

        assertNotNull(reused)
        assertEquals(0x2222, DnsMessage.transactionId(reused!!))
        assertEquals(200L, DnsMessage.cacheableTtlSeconds(reused))
    }

    @Test
    fun `a reused answer never outlives what is left of the entry`() {
        val answer = response(name = "example.com", ttls = listOf(86_400L))

        val reused = DnsMessage.copyAged(
            response = answer,
            query = query(name = "example.com"),
            elapsedSeconds = 0L,
            remainingSeconds = 30L,
        )

        assertEquals(30L, DnsMessage.cacheableTtlSeconds(reused!!))
    }

    @Test
    fun `a reused answer echoes the question of the query it answers`() {
        val answer = response(name = "example.com", ttls = listOf(300L))
        val mixedCaseQuery = query(name = "ExAmPlE.CoM", transactionId = 0x5555)

        val reused = DnsMessage.copyAged(
            response = answer,
            query = mixedCaseQuery,
            elapsedSeconds = 0L,
            remainingSeconds = 300L,
        )

        assertNotNull(reused)
        assertArrayEquals(
            mixedCaseQuery.copyOfRange(DnsMessage.HEADER_SIZE, mixedCaseQuery.size),
            reused!!.copyOfRange(DnsMessage.HEADER_SIZE, mixedCaseQuery.size),
        )
    }

    @Test
    fun `a ttl never ages below one second`() {
        val answer = response(name = "example.com", ttls = listOf(30L))

        val reused = DnsMessage.copyAged(
            response = answer,
            query = query(name = "example.com"),
            elapsedSeconds = 9_000L,
            remainingSeconds = 600L,
        )

        assertEquals(1L, DnsMessage.cacheableTtlSeconds(reused!!))
    }
}

class DnsResponseCacheTest {
    private var nowMs = 10_000L
    private val cache = DnsResponseCache(maxEntries = 2, maxTtlSeconds = 600L, nowMs = { nowMs })

    @Test
    fun `an answer is reused until its ttl runs out`() {
        cache.store("key", response(name = "example.com", ttls = listOf(60L)), ttlSeconds = 60L)

        nowMs += 30_000L
        val served = cache.lookup("key", query(name = "example.com", transactionId = 0x4242))
        assertNotNull(served)
        assertEquals(0x4242, DnsMessage.transactionId(served!!))
        assertEquals(30L, DnsMessage.cacheableTtlSeconds(served))

        nowMs += 31_000L
        assertNull(cache.lookup("key", query(name = "example.com", transactionId = 0x4242)))
        assertEquals(0, cache.size())
    }

    @Test
    fun `the cap evicts the least recently used answer`() {
        cache.store("a", response(name = "a.example", ttls = listOf(600L)), ttlSeconds = 600L)
        cache.store("b", response(name = "b.example", ttls = listOf(600L)), ttlSeconds = 600L)
        assertNotNull(cache.lookup("a", query(name = "a.example")))

        cache.store("c", response(name = "c.example", ttls = listOf(600L)), ttlSeconds = 600L)

        assertEquals(2, cache.size())
        assertNotNull(cache.lookup("a", query(name = "a.example")))
        assertNotNull(cache.lookup("c", query(name = "c.example")))
        assertNull(cache.lookup("b", query(name = "b.example")))
    }

    @Test
    fun `the ttl cap bounds how long an answer is kept`() {
        cache.store("key", response(name = "example.com", ttls = listOf(86_400L)), ttlSeconds = 86_400L)

        nowMs += 601_000L

        assertNull(cache.lookup("key", query(name = "example.com")))
    }
}

private const val TYPE_A = 1
private const val TYPE_AAAA = 28
private const val CLASS_IN = 1

private fun query(
    name: String,
    type: Int = TYPE_A,
    transactionId: Int = 0x1234,
): ByteArray {
    val message = ByteArrayOutputStream()
    message.writeHeader(transactionId, flags = 0x0100, questions = 1, answers = 0)
    message.writeQuestion(name, type)
    return message.toByteArray()
}

private fun response(
    name: String,
    ttls: List<Long>,
    transactionId: Int = 0x1234,
    rcode: Int = 0,
): ByteArray {
    val message = ByteArrayOutputStream()
    message.writeHeader(
        transactionId = transactionId,
        flags = 0x8180 or rcode,
        questions = 1,
        answers = ttls.size,
    )
    message.writeQuestion(name, TYPE_A)
    ttls.forEach { ttl ->
        // A compression pointer back to the question name, exactly like a real resolver writes.
        message.write(0xC0)
        message.write(0x0C)
        message.writeUnsignedShort(TYPE_A)
        message.writeUnsignedShort(CLASS_IN)
        message.writeUnsignedInt(ttl)
        message.writeUnsignedShort(4)
        message.write(byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34.toByte()))
    }
    return message.toByteArray()
}

private fun ByteArrayOutputStream.writeHeader(
    transactionId: Int,
    flags: Int,
    questions: Int,
    answers: Int,
) {
    writeUnsignedShort(transactionId)
    writeUnsignedShort(flags)
    writeUnsignedShort(questions)
    writeUnsignedShort(answers)
    writeUnsignedShort(0)
    writeUnsignedShort(0)
}

private fun ByteArrayOutputStream.writeQuestion(name: String, type: Int) {
    name.split('.').forEach { label ->
        write(label.length)
        write(label.toByteArray(Charsets.US_ASCII))
    }
    write(0)
    writeUnsignedShort(type)
    writeUnsignedShort(CLASS_IN)
}

private fun ByteArrayOutputStream.writeUnsignedShort(value: Int) {
    write((value ushr 8) and 0xFF)
    write(value and 0xFF)
}

private fun ByteArrayOutputStream.writeUnsignedInt(value: Long) {
    write(((value ushr 24) and 0xFF).toInt())
    write(((value ushr 16) and 0xFF).toInt())
    write(((value ushr 8) and 0xFF).toInt())
    write((value and 0xFF).toInt())
}
