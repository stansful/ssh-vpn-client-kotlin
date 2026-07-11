package com.stansful.sshvpnclient.vpn

import java.util.concurrent.atomic.AtomicLong

/**
 * Tracks the Xray generation owned by one service instance without allowing a stale attempt to
 * overwrite or clear a newer generation.
 */
internal class XrayRuntimeGenerationTracker {
    private val generation = AtomicLong(NO_GENERATION)

    fun publish(candidate: Long) {
        while (true) {
            val current = generation.get()
            if (current != NO_GENERATION && current > candidate) return
            if (generation.compareAndSet(current, candidate)) return
        }
    }

    fun snapshot(): Long? {
        return generation.get().takeUnless { it == NO_GENERATION }
    }

    fun clearIfMatches(expected: Long): Boolean {
        return generation.compareAndSet(expected, NO_GENERATION)
    }

    private companion object {
        const val NO_GENERATION = Long.MIN_VALUE
    }
}
