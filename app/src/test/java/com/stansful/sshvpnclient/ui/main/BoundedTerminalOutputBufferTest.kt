package com.stansful.sshvpnclient.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BoundedTerminalOutputBufferTest {
    @Test
    fun `append retains only newest characters across chunk boundaries`() {
        val buffer = BoundedTerminalOutputBuffer(maxCharacters = 10)

        buffer.append("12345")
        buffer.append("6789")
        buffer.append("ABC")

        assertEquals("3456789ABC", buffer.snapshot())
    }

    @Test
    fun `oversized chunk replaces previous output with its tail`() {
        val buffer = BoundedTerminalOutputBuffer(maxCharacters = 5)

        buffer.append("before")
        buffer.append("1234567")

        assertEquals("34567", buffer.snapshot())
    }

    @Test
    fun `repeated appends never grow snapshot beyond hard limit`() {
        val maxCharacters = 64 * 1_024
        val buffer = BoundedTerminalOutputBuffer(maxCharacters)
        val expected = StringBuilder()

        repeat(10_000) { index ->
            val chunk = "chunk-$index\n"
            buffer.append(chunk)
            expected.append(chunk)
        }

        assertEquals(expected.takeLast(maxCharacters), buffer.snapshot())
    }

    @Test
    fun `clear releases all retained output`() {
        val buffer = BoundedTerminalOutputBuffer(maxCharacters = 16)
        buffer.append("terminal output")

        buffer.clear()

        assertEquals("", buffer.snapshot())
    }

    @Test
    fun `non-positive capacity is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            BoundedTerminalOutputBuffer(maxCharacters = 0)
        }
    }
}
