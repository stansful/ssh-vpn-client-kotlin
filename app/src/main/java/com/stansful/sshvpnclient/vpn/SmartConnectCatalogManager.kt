package com.stansful.sshvpnclient.vpn

import android.net.Network
import com.stansful.sshvpnclient.data.local.SmartConnectStateStore
import com.stansful.sshvpnclient.domain.model.ProxyProfile
import com.stansful.sshvpnclient.domain.model.ProxyProfileSummary
import com.stansful.sshvpnclient.domain.model.ProxyTestStatus
import com.stansful.sshvpnclient.domain.model.ProxyTunnelTestResult
import com.stansful.sshvpnclient.domain.model.SmartConnectPhase
import com.stansful.sshvpnclient.domain.repository.ProxySourceConnectionFactory
import com.stansful.sshvpnclient.domain.repository.SmartProxyBatchSupersededException
import com.stansful.sshvpnclient.domain.repository.SmartProxyProfileRepository
import com.stansful.sshvpnclient.domain.repository.SmartProxySourceSynchronizer
import com.stansful.sshvpnclient.xray.XrayCoreBridge
import com.stansful.sshvpnclient.xray.XRAY_BATCH_TOTAL_BUDGET_MS
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

class SmartConnectCatalogManager(
    private val profileRepository: SmartProxyProfileRepository,
    private val sourceSynchronizer: SmartProxySourceSynchronizer,
    private val xrayCoreBridge: XrayCoreBridge,
    private val stateStore: SmartConnectStateStore,
    private val log: (String) -> Unit,
) {
    suspend fun refreshCheckPruneAndSelect(
        connectionFactory: ProxySourceConnectionFactory,
        excludedFingerprints: Set<String> = emptySet(),
        preferredPhysicalNetwork: Network? = null,
        workflowIsCurrent: () -> Boolean = { true },
    ): ProxyProfile {
        // Both deadlines use the monotonic clock. Batch probes stop early enough to leave a small
        // reserve for persisting completed results, pruning, and selection inside the same hard
        // user-visible 60-second workflow budget.
        val workflowDeadlineNanos = deadlineAfterMillis(
            nowNanos = System.nanoTime(),
            durationMs = XRAY_BATCH_TOTAL_BUDGET_MS,
        )
        return withMonotonicDeadlineOrNull(workflowDeadlineNanos) {
            refreshCheckPruneAndSelectWithinBudget(
                connectionFactory = connectionFactory,
                excludedFingerprints = excludedFingerprints,
                preferredPhysicalNetwork = preferredPhysicalNetwork,
                workflowDeadlineNanos = workflowDeadlineNanos,
                workflowIsCurrent = workflowIsCurrent,
            )
        } ?: throw SmartConnectWorkflowTimeoutException()
    }

    private suspend fun refreshCheckPruneAndSelectWithinBudget(
        connectionFactory: ProxySourceConnectionFactory,
        excludedFingerprints: Set<String>,
        preferredPhysicalNetwork: Network?,
        workflowDeadlineNanos: Long,
        workflowIsCurrent: () -> Boolean,
    ): ProxyProfile {
        val catalogNeedsFullRefresh = profileRepository.observeSummaries().first().none { profile ->
            !profile.isStale && !isSmartConnectExcludedProfileName(profile.name)
        }
        stateStore.publish { state ->
            state.copy(
                phase = SmartConnectPhase.REFRESHING,
                checkCompleted = 0,
                checkTotal = 0,
                removedCount = 0,
                retryDelayMs = null,
                message = "Refreshing Smart Connect configurations",
            )
        }
        val syncFailure = try {
            sourceSynchronizer.synchronize(
                // Keep the refresh semantics while avoiding another multi-megabyte download when
                // ETag says the source is unchanged. An empty local catalog must bypass 304.
                force = catalogNeedsFullRefresh,
                connectionFactory = connectionFactory,
            )
            null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log("Smart Connect refresh failed; trying cached catalog: ${error.safeMessage()}")
            error
        }

        var summariesBeforeCheck = profileRepository.observeSummaries().first()
        val excludedByNameIds = summariesBeforeCheck
            .asSequence()
            .filter { profile -> isSmartConnectExcludedProfileName(profile.name) }
            .map(ProxyProfileSummary::id)
            .toSet()
        if (excludedByNameIds.isNotEmpty()) {
            profileRepository.delete(excludedByNameIds)
            log("Removed ${excludedByNameIds.size} Smart Connect configuration(s) marked 🇷🇺")
            summariesBeforeCheck = profileRepository.observeSummaries().first()
        }
        if (summariesBeforeCheck.isEmpty()) {
            throw SmartConnectPoolExhaustedException(
                message = syncFailure?.let { "Refresh failed and the Smart Connect catalog is empty" }
                    ?: "Smart Connect source returned no configurations",
                cause = syncFailure,
            )
        }
        val ids = summariesBeforeCheck
            .asSequence()
            .filterNot(ProxyProfileSummary::isStale)
            .map(ProxyProfileSummary::id)
            .toList()
        val profiles = profileRepository.getByIds(ids)
        val profilesById = profiles.associateBy(ProxyProfile::id)
        val missingSecretIds = ids.asSequence()
            .filterNot(profilesById::containsKey)
            .toSet()
        if (missingSecretIds.isNotEmpty()) {
            profileRepository.delete(missingSecretIds)
            log("Removed ${missingSecretIds.size} Smart Connect configuration(s) with missing secrets")
        }
        if (profiles.isEmpty()) {
            throw SmartConnectPoolExhaustedException("Smart Connect configurations could not be decrypted")
        }
        val eligibleProfiles = profiles.filterNot { profile ->
            profile.fingerprint in excludedFingerprints
        }
        if (eligibleProfiles.isEmpty()) {
            throw SmartConnectPoolExhaustedException(
                "Recently failed Smart Connect tunnels are cooling down",
            )
        }
        ensureSmartWorkflowCurrent(workflowIsCurrent)

        stateStore.publish { state ->
            state.copy(
                phase = SmartConnectPhase.CHECKING,
                checkCompleted = 0,
                checkTotal = eligibleProfiles.size,
                catalogSize = profiles.size,
                message = "Checking tunnels 0/${eligibleProfiles.size}",
            )
        }
        val fingerprintsById = eligibleProfiles.associate { profile ->
            profile.id to profile.fingerprint
        }
        val terminalResults = SmartTerminalResultAccumulator(fingerprintsById)
        val completed = AtomicInteger(0)
        val publicationStep = (
            (eligibleProfiles.size + MAX_PROGRESS_PUBLICATIONS - 1) / MAX_PROGRESS_PUBLICATIONS
            ).coerceAtLeast(1)
        val probeDeadlineNanos = smartConnectProbeDeadlineNanos(workflowDeadlineNanos)
        var batchFailure: Exception? = null
        val returnedResults = try {
            withMonotonicDeadlineOrNull(probeDeadlineNanos) {
                xrayCoreBridge.testBatch(
                    profiles = eligibleProfiles,
                    deadlineNanos = probeDeadlineNanos,
                    preferredPhysicalNetwork = preferredPhysicalNetwork,
                    onResult = { result ->
                        if (terminalResults.recordCompleted(result)) {
                            val completedNow = completed.incrementAndGet()
                                .coerceAtMost(eligibleProfiles.size)
                            if (completedNow == eligibleProfiles.size ||
                                completedNow % publicationStep == 0
                            ) {
                                stateStore.publish { state ->
                                    state.copy(
                                        phase = SmartConnectPhase.CHECKING,
                                        checkCompleted = maxOf(state.checkCompleted, completedNow),
                                        checkTotal = eligibleProfiles.size,
                                        message = "Checking tunnels $completedNow/${eligibleProfiles.size}",
                                    )
                                }
                            }
                        }
                    },
                )
            }
        } catch (error: CancellationException) {
            // An external Stop must cancel this workflow without any further repository mutation.
            throw error
        } catch (error: Exception) {
            // A terminal batch/cleanup error must not discard callbacks that already completed.
            batchFailure = error
            null
        }

        // A normally returned list is authoritative (for example, an infrastructure failure may
        // downgrade provisional probe callbacks to NOT_TESTED). On a probe deadline, the callback
        // accumulator is the only durable record of already completed tunnel probes.
        returnedResults?.let(terminalResults::reconcileReturned)
        currentCoroutineContext().ensureActive()
        ensureSmartWorkflowCurrent(workflowIsCurrent)
        val completedResults = terminalResults.snapshot()
        if (completedResults.isEmpty()) {
            throw SmartConnectInfrastructureException(
                "Tunnel checks produced no completed result; preserving the Smart Connect catalog",
            )
        }
        val hasCurrentAvailable = completedResults.any { result ->
            result.status == ProxyTestStatus.AVAILABLE
        }
        if (!hasCurrentAvailable &&
            completedResults.any { result -> result.status == ProxyTestStatus.UNAVAILABLE }
        ) {
            // When every completed proxy probe fails, first prove that the captured physical
            // network can still reach the trusted source. Otherwise an outage/handoff would turn
            // hundreds of healthy profiles into false UNAVAILABLE rows and prune the whole pool.
            try {
                sourceSynchronizer.synchronize(
                    force = false,
                    connectionFactory = connectionFactory,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                throw SmartConnectInfrastructureException(
                    "Physical network control probe failed; preserving tunnel results",
                    error,
                )
            }
            currentCoroutineContext().ensureActive()
            ensureSmartWorkflowCurrent(workflowIsCurrent)
        }
        if (!hasCurrentAvailable) {
            // Never destroy the entire pool from an all-negative snapshot. Even with a successful
            // control request, the physical link may have recovered only after the proxy probes.
            // The next bounded refresh/check can confirm the pool without losing its secrets.
            if (returnedResults == null) throw SmartConnectWorkflowTimeoutException()
            throw SmartConnectPoolExhaustedException(
                "No verified Smart Connect tunnel; preserving the catalog for the next refresh",
            )
        }
        batchFailure?.let { error -> throw error }

        stateStore.publish { state ->
            state.copy(
                phase = SmartConnectPhase.CLEANING,
                checkCompleted = completedResults.size,
                message = "Removing unavailable tunnels",
            )
        }
        val verifiedAvailableResultsById = completedResults.asSequence()
            .filter { result -> result.status == ProxyTestStatus.AVAILABLE }
            .associateBy(ProxyTunnelTestResult::profileId)
        val candidateSnapshot = profileRepository.observeSummaries().first()
        val best = selectBestSmartCandidate(
            profiles = candidateSnapshot.mapNotNull { profile ->
                val result = verifiedAvailableResultsById[profile.id]
                    ?.takeIf { verified -> verified.profileFingerprint == profile.fingerprint }
                    ?: return@mapNotNull null
                profile.copy(
                    lastTestStatus = result.status,
                    lastLatencyMs = result.latencyMs,
                )
            }.filter { profile -> profile.id in profilesById },
            excludedFingerprints = excludedFingerprints,
        ) ?: if (returnedResults == null) {
            throw SmartConnectWorkflowTimeoutException()
        } else {
            throw SmartConnectPoolExhaustedException(
                "No verified Smart Connect tunnels are available",
            )
        }
        ensureSmartWorkflowCurrent(workflowIsCurrent)
        val finalization = try {
            profileRepository.finalizeVerifiedBatch(
                results = completedResults,
                selectedId = best.id,
                workflowIsCurrent = workflowIsCurrent,
            )
        } catch (error: SmartProxyBatchSupersededException) {
            throw SmartConnectWorkflowSupersededException()
        }
        currentCoroutineContext().ensureActive()
        ensureSmartWorkflowCurrent(workflowIsCurrent)
        val removed = excludedByNameIds.size + missingSecretIds.size +
            finalization.removedUnavailable + finalization.removedStale
        val remaining = profileRepository.observeSummaries().first()
        val availableCount = remaining.count { profile ->
            verifiedAvailableResultsById[profile.id]?.profileFingerprint == profile.fingerprint &&
                !profile.isStale &&
                profile.lastTestStatus == ProxyTestStatus.AVAILABLE
        }
        stateStore.publish { state ->
            state.copy(
                phase = SmartConnectPhase.SELECTING,
                catalogSize = remaining.size,
                availableCount = availableCount,
                removedCount = removed,
                message = "Selecting the lowest-latency tunnel",
            )
        }
        return profilesById.getValue(best.id)
    }

    suspend fun markUnavailable(profile: ProxyProfile, message: String) {
        profileRepository.saveTestResult(
            ProxyTunnelTestResult(
                profileId = profile.id,
                profileFingerprint = profile.fingerprint,
                status = ProxyTestStatus.UNAVAILABLE,
                message = message,
            ),
        )
    }

    private fun Throwable.safeMessage(): String = message ?: javaClass.simpleName

    private companion object {
        const val MAX_PROGRESS_PUBLICATIONS = 100
    }
}

private fun ensureSmartWorkflowCurrent(workflowIsCurrent: () -> Boolean) {
    if (!workflowIsCurrent()) throw SmartConnectWorkflowSupersededException()
}

internal const val SMART_CONNECT_FINALIZATION_RESERVE_MS = 3_000L
private const val NANOS_PER_MILLISECOND = 1_000_000L

internal fun smartConnectProbeDeadlineNanos(workflowDeadlineNanos: Long): Long {
    val reserveNanos = SMART_CONNECT_FINALIZATION_RESERVE_MS * NANOS_PER_MILLISECOND
    return if (workflowDeadlineNanos < Long.MIN_VALUE + reserveNanos) {
        Long.MIN_VALUE
    } else {
        workflowDeadlineNanos - reserveNanos
    }
}

internal fun isTerminalSmartTunnelResult(status: ProxyTestStatus): Boolean {
    return status != ProxyTestStatus.NOT_TESTED && status != ProxyTestStatus.RUNNING
}

internal class SmartTerminalResultAccumulator(
    private val fingerprintsById: Map<String, String>,
) {
    private val resultsById = ConcurrentHashMap<String, ProxyTunnelTestResult>()

    /** Returns true only when this is the first terminal result observed for the profile. */
    fun recordCompleted(result: ProxyTunnelTestResult): Boolean {
        if (!isTerminalSmartTunnelResult(result.status)) return false
        return resultsById.put(result.profileId, result.withCurrentFingerprint()) == null
    }

    /** Replaces provisional callbacks with the authoritative normally returned batch results. */
    fun reconcileReturned(results: List<ProxyTunnelTestResult>) {
        results.forEach { result ->
            if (isTerminalSmartTunnelResult(result.status)) {
                resultsById[result.profileId] = result.withCurrentFingerprint()
            } else {
                resultsById.remove(result.profileId)
            }
        }
    }

    fun snapshot(): List<ProxyTunnelTestResult> {
        return resultsById.values.sortedBy(ProxyTunnelTestResult::profileId)
    }

    private fun ProxyTunnelTestResult.withCurrentFingerprint(): ProxyTunnelTestResult {
        return copy(profileFingerprint = fingerprintsById[profileId])
    }
}

private fun deadlineAfterMillis(nowNanos: Long, durationMs: Long): Long {
    val durationNanos = durationMs * NANOS_PER_MILLISECOND
    return if (nowNanos > Long.MAX_VALUE - durationNanos) {
        Long.MAX_VALUE
    } else {
        nowNanos + durationNanos
    }
}

internal suspend fun <T> withMonotonicDeadlineOrNull(
    deadlineNanos: Long,
    block: suspend () -> T,
): T? {
    val remainingNanos = deadlineNanos - System.nanoTime()
    if (remainingNanos <= 0L) return null
    val remainingMillisCeiling = remainingNanos / NANOS_PER_MILLISECOND +
        if (remainingNanos % NANOS_PER_MILLISECOND == 0L) 0L else 1L
    return withTimeoutOrNull(remainingMillisCeiling) { block() }
}

class SmartConnectPoolExhaustedException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

class SmartConnectWorkflowTimeoutException : IllegalStateException(
    "Smart Connect refresh and tunnel checks exceeded the 60-second budget",
)

class SmartConnectWorkflowSupersededException : IllegalStateException(
    "Smart Connect network or routing settings changed during tunnel checks",
)

class SmartConnectInfrastructureException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
