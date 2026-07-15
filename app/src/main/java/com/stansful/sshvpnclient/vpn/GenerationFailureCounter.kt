package com.stansful.sshvpnclient.vpn

/** Generation-scoped streak state; late callbacks can never mutate a newer runtime's counter. */
internal class GenerationFailureCounter {
    private val lock = Any()
    private var generation = NO_GENERATION
    private var count = 0
    private var firstFailureAtMs = NO_TIMESTAMP

    fun resetForGeneration(candidate: Long) {
        synchronized(lock) {
            if (generation != NO_GENERATION && candidate < generation) return
            generation = candidate
            count = 0
            firstFailureAtMs = NO_TIMESTAMP
        }
    }

    fun recordSuccess(candidate: Long) {
        synchronized(lock) {
            if (generation != candidate) return
            count = 0
            firstFailureAtMs = NO_TIMESTAMP
        }
    }

    fun recordFailure(candidate: Long, nowMs: Long): GenerationFailureProgress? {
        return synchronized(lock) {
            if (generation != candidate) return@synchronized null
            if (firstFailureAtMs == NO_TIMESTAMP) firstFailureAtMs = nowMs
            count += 1
            GenerationFailureProgress(
                count = count,
                elapsedMs = (nowMs - firstFailureAtMs).coerceAtLeast(0L),
            )
        }
    }

    private companion object {
        const val NO_GENERATION = Long.MIN_VALUE
        const val NO_TIMESTAMP = Long.MIN_VALUE
    }
}

internal data class GenerationFailureProgress(
    val count: Int,
    val elapsedMs: Long,
)
