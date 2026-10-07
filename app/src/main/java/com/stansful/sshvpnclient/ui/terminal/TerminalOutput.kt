package com.stansful.sshvpnclient.ui.terminal

import androidx.compose.runtime.Immutable

/** Foreground colour the server asked for (SGR 30–37 / 90–97 / 38;5;0–15); drawn with theme tokens. */
internal enum class AnsiTone {
    Gray,
    Red,
    Green,
    Yellow,
    Blue,
    Magenta,
    Cyan,
    White,
}

/** A styled range of [TerminalLine.plain]: `[start, end)`, its [tone] (null = default ink) and weight. */
@Immutable
internal data class TerminalSpan(
    val start: Int,
    val end: Int,
    val tone: AnsiTone?,
    val bold: Boolean,
)

/**
 * One rendered line of shell output. [prompt] is non-empty when the line starts with a shell
 * prompt (see [parseTerminalOutput]); [text] is the rest of the line (the command echoed by the PTY,
 * or plain output). [spans] carry the server's colours and bold over [plain].
 */
@Immutable
internal data class TerminalLine(
    val prompt: String,
    val text: String,
    val spans: List<TerminalSpan> = emptyList(),
) {
    val isPrompt: Boolean get() = prompt.isNotEmpty()

    val plain: String get() = prompt + text
}

/**
 * Turns the raw PTY stream into display lines.
 *
 * - SGR colours and bold (`ESC[…m`) become [TerminalSpan]s; `ESC[K` erases the line from the cursor
 *   (progress bars) and `ESC[nC` / `ESC[nD` move the cursor along the line. Every other ANSI/xterm
 *   sequence (window titles, modes, charset switches) is removed — they used to be shown verbatim.
 * - `\r` returns to the line start (later text overwrites), `\b` steps back one character, tabs
 *   expand to 8-column stops, other control characters are dropped.
 * - The last element is the line the cursor is on (usually the server's current prompt); it is `""`
 *   when the output ends with a line break.
 *
 * Prompt detection (the PTY echoes commands itself; nothing is echoed locally) recognises the usual
 * prompt shapes at the start of a line: `user@host:path$ ` / `# ` / `% ` / `> ` (optionally preceded
 * by a `(venv) ` tag), `[user@host dir]$ `, `host:path$ ` (BusyBox/Alpine) and a bare `$ `.
 */
internal fun parseTerminalOutput(output: String): List<TerminalLine> {
    if (output.isEmpty()) return emptyList()
    return TerminalParser().apply { feed(output) }.finish()
}

/** The output as plain text, the way it is rendered (for "Copy output"). */
internal fun List<TerminalLine>.plainText(): String = joinToString(separator = "\n") { it.plain }.trimEnd()

/** Line-by-line terminal emulation of the subset a line-mode shell needs. */
private class TerminalParser {
    private val lines = ArrayList<TerminalLine>()
    private val chars = StringBuilder()
    private val styles = ArrayList<Int>()
    private var cursor = 0
    private var style = PLAIN

    fun feed(output: String) {
        var index = 0
        while (index < output.length) {
            val char = output[index]
            index = when {
                char == ESC -> escape(output, index + 1)
                char == '\n' -> {
                    endLine()
                    index + 1
                }
                else -> {
                    control(char)
                    index + 1
                }
            }
        }
    }

    fun finish(): List<TerminalLine> {
        endLine()
        return lines
    }

    private fun control(char: Char) {
        when {
            char == '\r' -> cursor = 0
            char == '\b' -> if (cursor > 0) cursor -= 1
            char == '\t' -> repeat(TAB_WIDTH - (cursor % TAB_WIDTH)) { put(' ') }
            char.isISOControl() -> Unit
            else -> put(char)
        }
    }

    /** Consumes the escape sequence whose introducer follows ESC at [start]; returns the next index. */
    private fun escape(output: String, start: Int): Int {
        if (start >= output.length) return start
        return when (output[start]) {
            '[' -> csi(output, start + 1)
            ']' -> skipString(output, start + 1, bellEnds = true)
            'P', 'X', '^', '_' -> skipString(output, start + 1, bellEnds = false)
            '(', ')', '*', '+' -> minOf(start + 2, output.length)
            else -> {
                var index = start
                while (index < output.length && output[index] in ' '..'/') index++
                minOf(index + 1, output.length)
            }
        }
    }

    private fun csi(output: String, start: Int): Int {
        var index = start
        while (index < output.length && output[index] in '0'..'?') index++
        val params = output.substring(start, index)
        while (index < output.length && output[index] in ' '..'/') index++
        if (index >= output.length) return index
        val privateMode = params.firstOrNull()?.let { it in '<'..'?' } == true
        if (!privateMode) {
            when (output[index]) {
                'm' -> applySgr(params)
                'K' -> eraseInLine(params)
                'C' -> cursor = (cursor + repeatCount(params)).coerceAtMost(MAX_CURSOR_COLUMN)
                'D' -> cursor = (cursor - repeatCount(params)).coerceAtLeast(0)
            }
        }
        return index + 1
    }

    /** Skips an OSC (ends at BEL or ST) or DCS/SOS/PM/APC string (ends at ST). */
    private fun skipString(output: String, start: Int, bellEnds: Boolean): Int {
        var index = start
        while (index < output.length) {
            val char = output[index]
            if (bellEnds && char == BEL) return index + 1
            if (char == ESC) return minOf(index + 2, output.length)
            index++
        }
        return index
    }

    private fun applySgr(params: String) {
        val codes = if (params.isEmpty()) listOf(0) else params.split(';', ':').map { it.toIntOrNull() ?: 0 }
        var index = 0
        while (index < codes.size) {
            when (val code = codes[index]) {
                0 -> style = PLAIN
                1 -> style = style or BOLD
                22 -> style = style and BOLD.inv()
                in 30..37 -> style = withTone(code - 30)
                in 90..97 -> style = withTone(code - 90)
                39 -> style = style and BOLD
                38, 48 -> index += extendedColor(codes, index, foreground = code == 38)
            }
            index++
        }
    }

    /** `38;5;n` (keeps the 16 base colours) and `38;2;r;g;b` (ignored); returns how many codes it used. */
    private fun extendedColor(codes: List<Int>, at: Int, foreground: Boolean): Int = when (codes.getOrNull(at + 1)) {
        5 -> {
            val color = codes.getOrNull(at + 2)
            if (foreground && color != null && color in 0..BASE_COLORS) style = withTone(color % ANSI_COLORS)
            2
        }
        2 -> 4
        else -> 0
    }

    private fun withTone(ansi: Int): Int = (style and BOLD) or (ansi + 1)

    private fun repeatCount(params: String): Int = params.toIntOrNull()?.coerceIn(1, MAX_CURSOR_STEP) ?: 1

    private fun eraseInLine(params: String) {
        when (params.toIntOrNull() ?: 0) {
            0 -> truncate(cursor)
            2 -> truncate(0)
        }
    }

    private fun truncate(length: Int) {
        if (length >= chars.length) return
        chars.setLength(length)
        styles.subList(length, styles.size).clear()
    }

    /** Writes [char] at the cursor (overwriting like a terminal) and advances it. */
    private fun put(char: Char) {
        if (cursor < chars.length) {
            chars.setCharAt(cursor, char)
            styles[cursor] = style
        } else {
            while (chars.length < cursor) {
                chars.append(' ')
                styles.add(PLAIN)
            }
            chars.append(char)
            styles.add(style)
        }
        cursor += 1
    }

    private fun endLine() {
        val plain = chars.toString()
        val prompt = PROMPT_PATTERNS.firstNotNullOfOrNull { pattern -> pattern.find(plain) }?.value.orEmpty()
        lines.add(TerminalLine(prompt = prompt, text = plain.substring(prompt.length), spans = spans()))
        chars.setLength(0)
        styles.clear()
        cursor = 0
    }

    private fun spans(): List<TerminalSpan> {
        if (styles.none { it != PLAIN }) return emptyList()
        val spans = ArrayList<TerminalSpan>()
        var start = 0
        for (index in 1..styles.size) {
            if (index == styles.size || styles[index] != styles[start]) {
                val packed = styles[start]
                if (packed != PLAIN) {
                    val tone = (packed and TONE_MASK).takeIf { it != 0 }?.let { TONES[it - 1] }
                    spans.add(TerminalSpan(start, index, tone, bold = packed and BOLD != 0))
                }
                start = index
            }
        }
        return spans
    }
}

private const val ESC = '\u001B'
private const val BEL = '\u0007'
private const val TAB_WIDTH = 8
private const val PLAIN = 0
private const val TONE_MASK = 0x0F
private const val BOLD = 0x10
private const val ANSI_COLORS = 8
private const val BASE_COLORS = 15
private const val MAX_CURSOR_STEP = 1_024
private const val MAX_CURSOR_COLUMN = 4_096

/** SGR colour index 0–7 → tone (black reads as gray on the dark well). */
private val TONES = listOf(
    AnsiTone.Gray,
    AnsiTone.Red,
    AnsiTone.Green,
    AnsiTone.Yellow,
    AnsiTone.Blue,
    AnsiTone.Magenta,
    AnsiTone.Cyan,
    AnsiTone.White,
)

private const val VENV = "(?:\\([^()\\s]{1,40}\\) )?"

private val PROMPT_PATTERNS = listOf(
    Regex("^$VENV[\\w.-]{1,64}@[\\w.-]{1,253}(?::[^\\s$#%>]*)?\\s?[$#%>] "),
    Regex("^$VENV\\[[^\\]\\n]{1,200}\\][$#] "),
    Regex("^$VENV[\\w.-]{1,64}:[~/][^\\s$#]*\\s?[$#] "),
    Regex("^\\$ "),
)
