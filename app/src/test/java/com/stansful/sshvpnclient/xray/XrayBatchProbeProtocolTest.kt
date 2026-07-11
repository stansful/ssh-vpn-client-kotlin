package com.stansful.sshvpnclient.xray

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayBatchProbeProtocolTest {
    @Test
    fun `encodes independent SOCKS username and password authentication`() {
        assertArrayEquals(
            byteArrayOf(1, 3, 'b'.code.toByte(), 'o'.code.toByte(), 'b'.code.toByte(), 2, 1, 2),
            buildSocks5UserPasswordRequest("bob", "\u0001\u0002"),
        )
    }

    @Test
    fun `encodes SOCKS domain connect request`() {
        assertArrayEquals(
            byteArrayOf(
                5,
                1,
                0,
                3,
                4,
                'a'.code.toByte(),
                '.'.code.toByte(),
                'i'.code.toByte(),
                'o'.code.toByte(),
                1,
                0xbb.toByte(),
            ),
            buildSocks5ConnectRequest("a.io", 443),
        )
    }

    @Test
    fun `accepts fragmented method selection and password authentication replies`() {
        readSocks5MethodSelection(FragmentedInputStream(byteArrayOf(5, 2)))
        readSocks5PasswordAuthentication(FragmentedInputStream(byteArrayOf(1, 0)))
    }

    @Test
    fun `consumes fragmented IPv4 connect reply`() {
        val input = FragmentedInputStream(
            byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0x01, 0xbb.toByte()),
        )

        readSocks5ConnectReply(input)

        assertEquals(-1, input.read())
    }

    @Test
    fun `consumes fragmented domain connect reply`() {
        val domain = "a.io".toByteArray(StandardCharsets.US_ASCII)
        val input = FragmentedInputStream(
            byteArrayOf(5, 0, 0, 3, domain.size.toByte()) +
                domain +
                byteArrayOf(0x01, 0xbb.toByte()),
        )

        readSocks5ConnectReply(input)

        assertEquals(-1, input.read())
    }

    @Test
    fun `consumes fragmented IPv6 connect reply`() {
        val address = ByteArray(16) { index -> index.toByte() }
        val input = FragmentedInputStream(
            byteArrayOf(5, 0, 0, 4) + address + byteArrayOf(0x01, 0xbb.toByte()),
        )

        readSocks5ConnectReply(input)

        assertEquals(-1, input.read())
    }

    @Test
    fun `rejects unsupported authentication method and failed credentials`() {
        val methodError = assertThrows(IllegalStateException::class.java) {
            readSocks5MethodSelection(FragmentedInputStream(byteArrayOf(5, 0)))
        }
        val credentialsError = assertThrows(IllegalStateException::class.java) {
            readSocks5PasswordAuthentication(FragmentedInputStream(byteArrayOf(1, 1)))
        }

        assertTrue(methodError.message.orEmpty().contains("method was rejected"))
        assertTrue(credentialsError.message.orEmpty().contains("credentials were rejected"))
    }

    @Test
    fun `rejects failed SOCKS connect response`() {
        val error = assertThrows(IllegalStateException::class.java) {
            readSocks5ConnectReply(FragmentedInputStream(byteArrayOf(5, 5, 0, 1)))
        }

        assertTrue(error.message.orEmpty().contains("SOCKS code 5"))
    }

    @Test
    fun `deadline requires a complete two second probe window`() {
        assertFalse(hasFullProbeBudget(deadlineNanos = 1_999_999_999L, nowNanos = 0L))
        assertTrue(hasFullProbeBudget(deadlineNanos = 2_000_000_000L, nowNanos = 0L))
        assertTrue(hasFullProbeBudget(deadlineNanos = 12_000_000_000L, nowNanos = 10_000_000_000L))
        assertFalse(hasFullProbeBudget(deadlineNanos = 9L, nowNanos = 10L))
    }

    @Test
    fun `500 profiles are scheduled in four bounded waves`() {
        assertEquals(1, batchProbeConcurrency(1))
        assertEquals(125, batchProbeConcurrency(500))
        assertEquals(125, batchProbeConcurrency(500, remainingMs = 8_000L))
        assertEquals(128, batchProbeConcurrency(500, remainingMs = 6_000L))
        assertEquals(128, batchProbeConcurrency(1_000, remainingMs = 8_000L))
        assertEquals(10_000L, XRAY_BATCH_TARGET_BUDGET_MS)
        assertEquals(60_000L, XRAY_BATCH_TOTAL_BUDGET_MS)
    }

    @Test
    fun `battery and low RAM modes reduce only transient batch pressure`() {
        assertEquals(
            128,
            deviceAwareBatchProbeConcurrency(
                requested = 128,
                minimumForDeadline = 1,
                isLowRamDevice = false,
                isPowerSaveMode = false,
            ),
        )
        assertEquals(
            64,
            deviceAwareBatchProbeConcurrency(
                requested = 128,
                minimumForDeadline = 1,
                isLowRamDevice = false,
                isPowerSaveMode = true,
            ),
        )
        assertEquals(
            32,
            deviceAwareBatchProbeConcurrency(
                requested = 128,
                minimumForDeadline = 1,
                isLowRamDevice = true,
                isPowerSaveMode = true,
            ),
        )
        assertEquals(35, minimumBatchProbeConcurrencyForDeadline(1_000, 58_000L))
        assertEquals(
            35,
            deviceAwareBatchProbeConcurrency(
                requested = 128,
                minimumForDeadline = 35,
                isLowRamDevice = true,
                isPowerSaveMode = false,
            ),
        )
    }

    @Test
    fun `batch worker pool preserves order and caps active transforms`() = runBlocking {
        val values = (0 until 32).toList()
        val active = AtomicInteger(0)
        val maximumActive = AtomicInteger(0)
        val callbacks = ConcurrentLinkedQueue<Int>()
        val firstWaveStarted = CompletableDeferred<Unit>()
        val releaseFirstWave = CompletableDeferred<Unit>()

        val mapped = async {
            mapBatchConcurrentOrdered(
                values = values,
                maxConcurrency = 4,
                dispatcher = Dispatchers.Default,
                onResult = callbacks::add,
            ) { value ->
                val activeNow = active.incrementAndGet()
                maximumActive.updateAndGet { previous -> maxOf(previous, activeNow) }
                if (activeNow == 4) firstWaveStarted.complete(Unit)
                try {
                    releaseFirstWave.await()
                    value * 3
                } finally {
                    active.decrementAndGet()
                }
            }
        }

        withTimeout(1_000L) { firstWaveStarted.await() }
        assertEquals(4, maximumActive.get())
        releaseFirstWave.complete(Unit)
        val results = mapped.await()

        assertEquals(values.map { it * 3 }, results)
        assertEquals(4, maximumActive.get())
        assertEquals(results.toSet(), callbacks.toSet())
        assertEquals(values.size, callbacks.size)
        assertEquals(0, active.get())
    }

    @Test
    fun `batch worker pool returns every one of 500 inputs`() = runBlocking {
        val values = (0 until 500).toList()
        val completed = AtomicInteger(0)

        val results = mapBatchConcurrentOrdered(
            values = values,
            maxConcurrency = 128,
            dispatcher = Dispatchers.Default,
            onResult = { completed.incrementAndGet() },
            transform = { value -> value },
        )

        assertEquals(values, results)
        assertEquals(500, completed.get())
    }

    @Test
    fun `free batch worker starts the next input before a slow peer finishes`() = runBlocking {
        val releaseFirst = CompletableDeferred<Unit>()
        val thirdStarted = CompletableDeferred<Unit>()
        val mapped = async {
            mapBatchConcurrentOrdered(
                values = listOf(0, 1, 2),
                maxConcurrency = 2,
                dispatcher = Dispatchers.Default,
                onResult = {},
            ) { value ->
                if (value == 0) releaseFirst.await()
                if (value == 2) thirdStarted.complete(Unit)
                value
            }
        }

        withTimeout(1_000L) { thirdStarted.await() }
        assertFalse(mapped.isCompleted)
        releaseFirst.complete(Unit)
        assertEquals(listOf(0, 1, 2), mapped.await())
    }
}

private class FragmentedInputStream(
    bytes: ByteArray,
    private val chunkSize: Int = 1,
) : ByteArrayInputStream(bytes) {
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return super.read(buffer, offset, minOf(length, chunkSize))
    }
}
