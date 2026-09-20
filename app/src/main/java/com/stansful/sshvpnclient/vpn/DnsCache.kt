package com.stansful.sshvpnclient.vpn

/**
 * Just enough DNS wire format to reuse an answer.
 *
 * A miss costs a fresh SSH `direct-tcpip` channel to the resolver plus two round trips through the
 * tunnel, and one page load asks for dozens of names, so reusing answers is the difference between
 * a page that opens and a page that crawls. Everything here is defensive: any message that does not
 * parse exactly as expected is reported as "do not cache" rather than guessed at.
 */
internal object DnsMessage {
    const val HEADER_SIZE = 12

    private const val FLAGS_OFFSET = 2
    private const val QUESTION_COUNT_OFFSET = 4
    private const val ANSWER_COUNT_OFFSET = 6
    private const val AUTHORITY_COUNT_OFFSET = 8
    private const val ADDITIONAL_COUNT_OFFSET = 10
    private const val FLAG_RESPONSE = 0x8000
    private const val OPCODE_MASK = 0x7800
    private const val RCODE_MASK = 0x000F
    private const val RCODE_NO_ERROR = 0
    private const val RCODE_NAME_ERROR = 3
    private const val LABEL_POINTER_MASK = 0xC0
    private const val QUESTION_FIXED_FIELDS = 4
    private const val RECORD_FIXED_FIELDS = 10
    private const val RECORD_TTL_OFFSET = 4
    private const val RECORD_RDLENGTH_OFFSET = 8
    private const val TYPE_OPT = 41
    private const val MAX_NAME_BYTES = 255
    private const val MAX_KEY_LENGTH = 320
    private const val UPPERCASE_A = 'A'.code
    private const val UPPERCASE_Z = 'Z'.code
    private const val CASE_BIT = 0x20

    /** The identifier a client matches its outstanding query against. */
    fun transactionId(message: ByteArray): Int =
        if (message.size < HEADER_SIZE) 0 else readUnsignedShort(message, 0)

    /**
     * Cache key for a query, or null when the query must not be served from cache. Names are
     * case-insensitive on the wire and resolvers randomise the case of what they send, so the name
     * is lower-cased; the resolver's address is part of the key because two resolvers may answer
     * the same name differently.
     */
    @Suppress("ReturnCount")
    fun questionKey(query: ByteArray, dnsServerAddress: Int): String? {
        if (query.size < HEADER_SIZE) return null
        val flags = readUnsignedShort(query, FLAGS_OFFSET)
        if (flags and FLAG_RESPONSE != 0) return null
        if (flags and OPCODE_MASK != 0) return null
        if (readUnsignedShort(query, QUESTION_COUNT_OFFSET) != 1) return null

        val key = StringBuilder()
        key.append(dnsServerAddress).append('|')
        var offset = HEADER_SIZE
        while (true) {
            if (offset >= query.size) return null
            val labelLength = query[offset].toInt() and 0xFF
            if (labelLength == 0) {
                offset += 1
                break
            }
            // A question name is never compressed; a pointer here means the query is not one we
            // should be matching against a cache.
            if (labelLength and LABEL_POINTER_MASK != 0) return null
            if (offset + 1 + labelLength > query.size) return null
            for (index in offset + 1 until offset + 1 + labelLength) {
                val byte = query[index].toInt() and 0xFF
                val lowered = if (byte in UPPERCASE_A..UPPERCASE_Z) byte or CASE_BIT else byte
                key.append(lowered.toChar())
            }
            key.append('.')
            offset += 1 + labelLength
            if (key.length > MAX_KEY_LENGTH) return null
        }
        if (offset + QUESTION_FIXED_FIELDS > query.size) return null
        key.append('|').append(readUnsignedShort(query, offset))
        key.append('|').append(readUnsignedShort(query, offset + 2))
        return key.toString()
    }

    /**
     * How long this answer may be reused: the smallest record TTL in it. Null means it must not be
     * stored - a failure other than "no such name", a message that does not parse, or a record that
     * asks not to be cached with a zero TTL.
     */
    @Suppress("ReturnCount")
    fun cacheableTtlSeconds(response: ByteArray): Long? {
        if (response.size < HEADER_SIZE) return null
        val flags = readUnsignedShort(response, FLAGS_OFFSET)
        if (flags and FLAG_RESPONSE == 0) return null
        if (flags and OPCODE_MASK != 0) return null
        val responseCode = flags and RCODE_MASK
        if (responseCode != RCODE_NO_ERROR && responseCode != RCODE_NAME_ERROR) return null

        var minimumTtl = Long.MAX_VALUE
        val walked = forEachRecord(response) { type, ttlOffset ->
            if (type != TYPE_OPT) {
                val ttl = readUnsignedInt(response, ttlOffset)
                if (ttl < minimumTtl) minimumTtl = ttl
            }
        }
        if (!walked) return null
        if (minimumTtl == Long.MAX_VALUE || minimumTtl <= 0L) return null
        return minimumTtl
    }

    /**
     * The stored answer as it would look arriving now: carrying this query's id and its question
     * bytes verbatim, and with every TTL cut to whatever is left of the entry's life. The question
     * is copied because resolvers randomise the case of the name they send and check that the answer
     * echoes it back byte for byte; the TTL is cut so a client that caches the answer in turn cannot
     * hold it longer than this cache would have. Null sends the caller to the resolver instead.
     */
    @Suppress("ReturnCount")
    fun copyAged(
        response: ByteArray,
        query: ByteArray,
        elapsedSeconds: Long,
        remainingSeconds: Long,
    ): ByteArray? {
        if (response.size < HEADER_SIZE || query.size < HEADER_SIZE) return null
        val questionLength = questionLength(response) ?: return null
        if (questionLength(query) != questionLength) return null
        val copy = response.copyOf()
        copy[0] = query[0]
        copy[1] = query[1]
        query.copyInto(copy, HEADER_SIZE, HEADER_SIZE, HEADER_SIZE + questionLength)
        val walked = forEachRecord(copy) { type, ttlOffset ->
            if (type != TYPE_OPT) {
                val aged = readUnsignedInt(copy, ttlOffset) - elapsedSeconds
                writeUnsignedInt(copy, ttlOffset, minOf(aged, remainingSeconds).coerceAtLeast(1L))
            }
        }
        return if (walked) copy else null
    }

    /** Byte length of the single question section, or null when it does not parse. */
    private fun questionLength(message: ByteArray): Int? {
        val nameEnd = skipName(message, HEADER_SIZE) ?: return null
        val questionEnd = nameEnd + QUESTION_FIXED_FIELDS
        if (questionEnd > message.size) return null
        return questionEnd - HEADER_SIZE
    }

    private fun forEachRecord(message: ByteArray, action: (Int, Int) -> Unit): Boolean {
        val questions = readUnsignedShort(message, QUESTION_COUNT_OFFSET)
        val records = readUnsignedShort(message, ANSWER_COUNT_OFFSET) +
            readUnsignedShort(message, AUTHORITY_COUNT_OFFSET) +
            readUnsignedShort(message, ADDITIONAL_COUNT_OFFSET)
        var offset = HEADER_SIZE
        repeat(questions) {
            offset = skipName(message, offset) ?: return false
            offset += QUESTION_FIXED_FIELDS
            if (offset > message.size) return false
        }
        repeat(records) {
            offset = skipName(message, offset) ?: return false
            if (offset + RECORD_FIXED_FIELDS > message.size) return false
            val type = readUnsignedShort(message, offset)
            val dataLength = readUnsignedShort(message, offset + RECORD_RDLENGTH_OFFSET)
            action(type, offset + RECORD_TTL_OFFSET)
            offset += RECORD_FIXED_FIELDS + dataLength
            if (offset > message.size) return false
        }
        return true
    }

    /** Returns the offset just past the name, or null when the name runs off the message. */
    private fun skipName(message: ByteArray, start: Int): Int? {
        var offset = start
        var consumed = 0
        while (true) {
            if (offset >= message.size) return null
            val labelLength = message[offset].toInt() and 0xFF
            if (labelLength and LABEL_POINTER_MASK == LABEL_POINTER_MASK) {
                // A pointer always ends a name, and what it points at needs no walking here.
                return if (offset + 2 <= message.size) offset + 2 else null
            }
            if (labelLength and LABEL_POINTER_MASK != 0) return null
            if (labelLength == 0) return offset + 1
            offset += 1 + labelLength
            consumed += 1 + labelLength
            if (consumed > MAX_NAME_BYTES) return null
        }
    }

    private fun readUnsignedShort(message: ByteArray, offset: Int): Int =
        ((message[offset].toInt() and 0xFF) shl 8) or (message[offset + 1].toInt() and 0xFF)

    private fun readUnsignedInt(message: ByteArray, offset: Int): Long =
        ((message[offset].toLong() and 0xFF) shl 24) or
            ((message[offset + 1].toLong() and 0xFF) shl 16) or
            ((message[offset + 2].toLong() and 0xFF) shl 8) or
            (message[offset + 3].toLong() and 0xFF)

    private fun writeUnsignedInt(message: ByteArray, offset: Int, value: Long) {
        message[offset] = ((value ushr 24) and 0xFF).toByte()
        message[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        message[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        message[offset + 3] = (value and 0xFF).toByte()
    }
}

/**
 * Answers kept for as long as their own TTL allows, least-recently-used first out.
 *
 * The cache lives with the forwarder, so it survives an SSH reconnect - which is exactly when a
 * device is about to ask for everything at once again - and dies with the VPN interface.
 */
internal class DnsResponseCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxTtlSeconds: Long = DEFAULT_MAX_TTL_SECONDS,
    private val nowMs: () -> Long,
) {
    init {
        require(maxEntries > 0) { "DNS cache must hold at least one answer" }
        require(maxTtlSeconds > 0L) { "DNS cache TTL cap must be positive" }
    }

    private val entries = object : LinkedHashMap<String, CachedAnswer>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedAnswer>): Boolean =
            size > maxEntries
    }

    /** The stored answer re-addressed to this query, or null on a miss or an expired entry. */
    @Synchronized
    fun lookup(key: String, query: ByteArray): ByteArray? {
        val entry = entries[key] ?: return null
        val now = nowMs()
        if (now >= entry.expiresAtMs) {
            entries.remove(key)
            return null
        }
        val elapsedSeconds = (now - entry.storedAtMs) / MILLIS_PER_SECOND
        val remainingSeconds = (entry.expiresAtMs - now + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND
        return DnsMessage.copyAged(
            response = entry.response,
            query = query,
            elapsedSeconds = elapsedSeconds,
            remainingSeconds = remainingSeconds,
        )
    }

    @Synchronized
    fun store(key: String, response: ByteArray, ttlSeconds: Long) {
        if (response.size > MAX_CACHED_RESPONSE_BYTES) return
        val now = nowMs()
        val ttl = ttlSeconds.coerceIn(1L, maxTtlSeconds)
        entries[key] = CachedAnswer(
            response = response.copyOf(),
            storedAtMs = now,
            expiresAtMs = now + ttl * MILLIS_PER_SECOND,
        )
    }

    @Synchronized
    fun size(): Int = entries.size

    private class CachedAnswer(
        val response: ByteArray,
        val storedAtMs: Long,
        val expiresAtMs: Long,
    )

    companion object {
        const val DEFAULT_MAX_ENTRIES = 512
        const val DEFAULT_MAX_TTL_SECONDS = 600L
        private const val MAX_CACHED_RESPONSE_BYTES = 4 * 1024
        private const val INITIAL_CAPACITY = 64
        private const val LOAD_FACTOR = 0.75f
        private const val MILLIS_PER_SECOND = 1_000L
    }
}
