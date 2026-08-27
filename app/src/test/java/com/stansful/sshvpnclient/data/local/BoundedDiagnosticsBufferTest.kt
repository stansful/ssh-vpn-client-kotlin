package com.stansful.sshvpnclient.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedDiagnosticsBufferTest {
    @Test
    fun `keeps only newest entries when line limit is reached`() {
        val buffer = BoundedDiagnosticsBuffer(
            maxEntries = 3,
            maxCharacters = 100,
            maxEntryCharacters = 50,
        )

        buffer.addAll(listOf("one", "two", "three", "four"))

        assertEquals(listOf("two", "three", "four"), buffer.snapshot())
    }

    @Test
    fun `bounds individual entries and total retained characters`() {
        val buffer = BoundedDiagnosticsBuffer(
            maxEntries = 10,
            maxCharacters = 12,
            maxEntryCharacters = 6,
        )

        buffer.addLast("123456789")
        buffer.addLast("abcdef")
        buffer.addLast("uvwxyz")

        val snapshot = buffer.snapshot()
        assertEquals(listOf("abcdef", "uvwxyz"), snapshot)
        assertTrue(snapshot.sumOf(String::length) <= 12)
        assertTrue(snapshot.all { it.length <= 6 })
    }

    @Test
    fun `redacts per-flow destination only from TUN TCP diagnostics`() {
        val tunMessage = redactPersistentDestinationMetadata(
            "12:00:00 TUN TCP failed: 203.0.113.10:443: connection closed",
        )
        val sshMessage = redactPersistentDestinationMetadata(
            "SSH socket remote=203.0.113.10:22",
        )

        assertFalse(tunMessage.contains("203.0.113.10:443"))
        assertTrue(tunMessage.contains("<destination>:443"))
        assertEquals("SSH socket remote=203.0.113.10:22", sshMessage)
    }

    @Test
    fun `keeps Telegram addresses so call diagnosis stays possible`() {
        val telegramMessage = redactPersistentDestinationMetadata(
            "12:00:00 TUN TCP to Telegram: 91.108.9.69:443 connected in 118ms",
        )

        assertEquals(
            "12:00:00 TUN TCP to Telegram: 91.108.9.69:443 connected in 118ms",
            telegramMessage,
        )
    }
}
