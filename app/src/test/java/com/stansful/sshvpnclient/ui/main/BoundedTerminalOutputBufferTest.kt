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
    fun `dropped line breaks number the first kept line`() {
        val maxCharacters = 64
        val buffer = BoundedTerminalOutputBuffer(maxCharacters)
        val expected = StringBuilder()

        repeat(500) { index ->
            val line = "line $index\n"
            buffer.append(line)
            expected.append(line)
            val dropped = expected.length - maxCharacters
            val droppedBreaks = if (dropped > 0) expected.substring(0, dropped).count { it == '\n' } else 0
            assertEquals(droppedBreaks.toLong(), buffer.droppedLineBreaks)
        }
        buffer.append("x".repeat(maxCharacters - 1) + "\n" + "tail")
        assertEquals(
            (expected.toString() + "x".repeat(maxCharacters - 1) + "\n" + "tail").dropLast(maxCharacters)
                .count { it == '\n' }.toLong(),
            buffer.droppedLineBreaks,
        )

        buffer.clear()
        assertEquals(0L, buffer.droppedLineBreaks)
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
