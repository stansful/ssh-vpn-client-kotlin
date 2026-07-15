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

internal const val SMART_HEALTH_PROBE_TIMEOUT_MS = 5_000L
internal const val SMART_HEALTH_FAILURE_CONFIRM_DELAY_MS = 2_000L
internal const val SMART_HEALTH_WARM_UP_DURATION_MS = 60_000L
internal const val SMART_HEALTH_WARM_UP_INTERVAL_MS = 10_000L
internal const val SMART_HEALTH_INTERACTIVE_INTERVAL_MS = 30_000L
internal const val SMART_HEALTH_SCREEN_OFF_INTERVAL_MS = 120_000L
internal const val SMART_HEALTH_POWER_SAVE_INTERVAL_MS = 300_000L
private val SMART_CATALOG_RETRY_DELAYS_MS = longArrayOf(
    30_000L,
    60_000L,
    120_000L,
    300_000L,
    900_000L,
)
private val SMART_CONNECT_EXCLUDED_NAME_MARKERS = setOf("🇷🇺")
