package com.stansful.sshvpnclient.vpn

import com.stansful.sshvpnclient.domain.model.ProxyProfileSummary
import com.stansful.sshvpnclient.domain.model.ProxyTestStatus

internal fun smartHealthCheckIntervalMs(
    connectedDurationMs: Long,
    isInteractive: Boolean,
    isPowerSaveMode: Boolean,
): Long {
    require(connectedDurationMs >= 0L)
    return when {
        isPowerSaveMode -> SMART_HEALTH_POWER_SAVE_INTERVAL_MS
        connectedDurationMs < SMART_HEALTH_WARM_UP_DURATION_MS ->
            SMART_HEALTH_WARM_UP_INTERVAL_MS
        !isInteractive -> SMART_HEALTH_SCREEN_OFF_INTERVAL_MS
        else -> SMART_HEALTH_INTERACTIVE_INTERVAL_MS
    }
}

internal fun smartCatalogRetryDelayMs(attempt: Int): Long {
    require(attempt >= 0)
    return SMART_CATALOG_RETRY_DELAYS_MS[
        attempt.coerceAtMost(SMART_CATALOG_RETRY_DELAYS_MS.lastIndex)
    ]
}

internal fun selectBestSmartCandidate(
    profiles: Collection<ProxyProfileSummary>,
    excludedFingerprints: Set<String> = emptySet(),
): ProxyProfileSummary? {
    return profiles
        .asSequence()
        .filter { profile ->
            !profile.isStale &&
                !isSmartConnectExcludedProfileName(profile.name) &&
                profile.lastTestStatus == ProxyTestStatus.AVAILABLE &&
                profile.lastLatencyMs != null &&
                profile.fingerprint !in excludedFingerprints
        }
        .minWithOrNull(
            compareBy<ProxyProfileSummary> { it.lastLatencyMs }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                .thenBy { it.id },
        )
}

internal fun isSmartConnectExcludedProfileName(name: String): Boolean {
    return SMART_CONNECT_EXCLUDED_NAME_MARKERS.any(name::contains)
}

/** A verified tunnel is not destroyed by one short-lived auxiliary endpoint failure. */
internal fun shouldTriggerVerifiedTunnelFailover(
    confirmedFailureRounds: Int,
    elapsedSinceFirstConfirmedFailureMs: Long,
): Boolean {
    require(confirmedFailureRounds >= 0)
    require(elapsedSinceFirstConfirmedFailureMs >= 0L)
    return confirmedFailureRounds >= SMART_HEALTH_FAILURE_ROUNDS_BEFORE_FAILOVER &&
        elapsedSinceFirstConfirmedFailureMs >= SMART_HEALTH_MIN_FAILURE_DURATION_MS
}

/**
 * Smart Connect may briefly postpone an auxiliary health probe while a transfer is active, but
 * never after the tunnel has produced a confirmed health failure and never beyond a hard limit.
 */
internal fun shouldDeferSmartHealthProbe(
    traffic: VpnTrafficDelta,
    elapsedSinceDeferralStartedMs: Long,
    confirmedFailureRounds: Int,
): Boolean {
    require(elapsedSinceDeferralStartedMs >= 0L)
    require(confirmedFailureRounds >= 0)
    return confirmedFailureRounds == 0 &&
        elapsedSinceDeferralStartedMs < SMART_MAX_ACTIVE_TRANSFER_HEALTH_DEFERRAL_MS &&
        shouldDeferVpnDisruption(traffic, elapsedSinceDeferralStartedMs)
}

/** Keeps recently failed public profiles out of both probes and selection until their cooldown. */
internal class SmartProfileCooldowns(
    private val elapsedRealtimeMs: () -> Long,
) {
    private val lock = Any()
    private val retryAfterByFingerprint = linkedMapOf<String, Long>()

    fun exclude(fingerprint: String, durationMs: Long) {
        require(durationMs >= 0L)
        val retryAfterMs = elapsedRealtimeMs() + durationMs
        synchronized(lock) {
            retryAfterByFingerprint[fingerprint] = maxOf(
                retryAfterByFingerprint[fingerprint] ?: Long.MIN_VALUE,
                retryAfterMs,
            )
        }
    }

    fun activeFingerprints(): Set<String> {
        val nowMs = elapsedRealtimeMs()
        return synchronized(lock) {
            removeExpiredLocked(nowMs)
            retryAfterByFingerprint.keys.toSet()
        }
    }

    fun remainingUntilNextExpiryMs(): Long? {
        val nowMs = elapsedRealtimeMs()
        return synchronized(lock) {
            removeExpiredLocked(nowMs)
            retryAfterByFingerprint.values.minOrNull()?.minus(nowMs)
        }
    }

    private fun removeExpiredLocked(nowMs: Long) {
        retryAfterByFingerprint.entries.removeAll { (_, retryAfterMs) -> retryAfterMs <= nowMs }
    }
}

internal const val SMART_HEALTH_PROBE_TIMEOUT_MS = 5_000L
internal const val SMART_HEALTH_FAILURE_CONFIRM_DELAY_MS = 2_000L
internal const val SMART_HEALTH_FAILURE_RETRY_INTERVAL_MS = 10_000L
internal const val SMART_HEALTH_WARM_UP_DURATION_MS = 60_000L
internal const val SMART_HEALTH_WARM_UP_INTERVAL_MS = 10_000L
internal const val SMART_HEALTH_INTERACTIVE_INTERVAL_MS = 30_000L
internal const val SMART_HEALTH_SCREEN_OFF_INTERVAL_MS = 120_000L
internal const val SMART_HEALTH_POWER_SAVE_INTERVAL_MS = 300_000L
internal const val SMART_HEALTH_FAILURE_ROUNDS_BEFORE_FAILOVER = 3
internal const val SMART_HEALTH_MIN_FAILURE_DURATION_MS = 30_000L
internal const val SMART_MAX_ACTIVE_TRANSFER_HEALTH_DEFERRAL_MS = 5L * 60_000L
internal const val SMART_HEALTH_FAILURE_COOLDOWN_MS = 15L * 60_000L
internal const val SMART_RUNTIME_FAILURE_COOLDOWN_MS = 2L * 60_000L
private val SMART_CATALOG_RETRY_DELAYS_MS = longArrayOf(
    30_000L,
    60_000L,
    120_000L,
    300_000L,
    900_000L,
)
private val SMART_CONNECT_EXCLUDED_NAME_MARKERS = setOf("🇷🇺")
