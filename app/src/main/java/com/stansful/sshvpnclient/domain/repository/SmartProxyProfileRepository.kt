package com.stansful.sshvpnclient.domain.repository

import com.stansful.sshvpnclient.domain.model.ProxyProfile
import com.stansful.sshvpnclient.domain.model.ProxyTunnelTestResult

/** A type-safe boundary for the Smart Connect catalog, which is isolated from OpenSource data. */
interface SmartProxyProfileRepository : ProxyProfileRepository {
    suspend fun selectBestAvailable(excludedIds: Set<String> = emptySet()): ProxyProfile?
    suspend fun deleteStaleExceptPinned(): Int

    /**
     * Atomically commits one verified batch, prunes failed/stale rows and selects its winner.
     * The guard is evaluated inside the database transaction; a false value must roll back every
     * row mutation so a physical-network handoff cannot persist a mixed/obsolete snapshot.
     */
    suspend fun finalizeVerifiedBatch(
        results: List<ProxyTunnelTestResult>,
        selectedId: String,
        workflowIsCurrent: () -> Boolean,
    ): SmartProxyBatchFinalization
}

data class SmartProxyBatchFinalization(
    val removedUnavailable: Int,
    val removedStale: Int,
)

class SmartProxyBatchSupersededException : IllegalStateException(
    "Smart Connect batch was superseded before its database transaction completed",
)
