package com.stansful.sshvpnclient.data.smart

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.stansful.sshvpnclient.data.proxy.ProxyTestResultUpdate
import com.stansful.sshvpnclient.data.proxy.sqliteQueryBatches
import com.stansful.sshvpnclient.domain.repository.SmartProxyBatchSupersededException
import kotlinx.coroutines.flow.Flow

@Dao
abstract class SmartProxyProfileDao {
    @Query("SELECT * FROM smart_proxy_profiles ORDER BY isStale ASC, updatedAt DESC")
    abstract fun observeAll(): Flow<List<SmartProxyProfileEntity>>

    @Query("SELECT * FROM smart_proxy_profiles WHERE id = :id LIMIT 1")
    abstract suspend fun getById(id: String): SmartProxyProfileEntity?

    @Query("SELECT * FROM smart_proxy_profiles WHERE fingerprint = :fingerprint LIMIT 1")
    abstract suspend fun getByFingerprint(fingerprint: String): SmartProxyProfileEntity?

    @Query("SELECT * FROM smart_proxy_profiles WHERE fingerprint IN (:fingerprints)")
    abstract suspend fun getByFingerprints(fingerprints: List<String>): List<SmartProxyProfileEntity>

    @Query("SELECT * FROM smart_proxy_profiles WHERE isSelected = 1 AND isStale = 0 LIMIT 1")
    abstract suspend fun getSelected(): SmartProxyProfileEntity?

    @Query(
        """
        SELECT * FROM smart_proxy_profiles
        WHERE isStale = 0 AND lastTestStatus = 'AVAILABLE' AND lastLatencyMs IS NOT NULL
        ORDER BY lastLatencyMs ASC, lastTestAt DESC, updatedAt DESC
        """,
    )
    protected abstract suspend fun getAvailableCandidates(): List<SmartProxyProfileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(entity: SmartProxyProfileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertAll(entities: List<SmartProxyProfileEntity>)

    @Query("DELETE FROM smart_proxy_profiles WHERE id IN (:ids)")
    abstract suspend fun deleteByIds(ids: List<String>)

    @Query("SELECT * FROM smart_proxy_profiles WHERE id IN (:ids)")
    abstract suspend fun getByIds(ids: List<String>): List<SmartProxyProfileEntity>

    @Query("SELECT * FROM smart_proxy_profiles WHERE lastTestStatus = 'UNAVAILABLE' AND isPinned = 0")
    protected abstract suspend fun getUnavailableUnpinned(): List<SmartProxyProfileEntity>

    @Query("SELECT * FROM smart_proxy_profiles WHERE isStale = 1 AND isPinned = 0")
    protected abstract suspend fun getStaleUnpinned(): List<SmartProxyProfileEntity>

    @Query("UPDATE smart_proxy_profiles SET isSelected = 0")
    protected abstract suspend fun clearSelection()

    @Query("UPDATE smart_proxy_profiles SET isSelected = 1 WHERE id = :id")
    protected abstract suspend fun markSelected(id: String)

    @Query("UPDATE smart_proxy_profiles SET isPinned = :isPinned WHERE id = :id")
    abstract suspend fun setPinned(id: String, isPinned: Boolean)

    @Query(
        """
        UPDATE smart_proxy_profiles
        SET isStale = 1
        WHERE source = 'REMOTE' AND sourceUrl = :sourceUrl AND lastSeenAt < :syncStartedAt
        """,
    )
    abstract suspend fun markRemoteProfilesStale(sourceUrl: String, syncStartedAt: Long)

    @Query(
        """
        UPDATE smart_proxy_profiles
        SET lastTestStatus = :status, lastLatencyMs = :latencyMs, lastTestAt = :testedAt
        WHERE id = :id AND (:fingerprint IS NULL OR fingerprint = :fingerprint)
        """,
    )
    abstract suspend fun updateTestResult(
        id: String,
        fingerprint: String?,
        status: String,
        latencyMs: Long?,
        testedAt: Long,
    )

    @Transaction
    open suspend fun updateTestResults(
        results: List<ProxyTestResultUpdate>,
        testedAt: Long,
    ) {
        results.forEach { result ->
            updateTestResult(
                id = result.profileId,
                fingerprint = result.profileFingerprint,
                status = result.status.name,
                latencyMs = result.latencyMs,
                testedAt = testedAt,
            )
        }
    }

    /**
     * One rollback-capable finalization boundary for Smart Connect. The callback intentionally
     * remains a cheap in-process revision check; throwing from it makes Room roll back status,
     * deletion and selection changes together.
     */
    @Transaction
    open suspend fun finalizeVerifiedBatch(
        results: List<ProxyTestResultUpdate>,
        testedAt: Long,
        selectedId: String,
        workflowIsCurrent: () -> Boolean,
    ): SmartProxyBatchMutation {
        ensureWorkflowCurrent(workflowIsCurrent)
        results.forEach { result ->
            updateTestResult(
                id = result.profileId,
                fingerprint = result.profileFingerprint,
                status = result.status.name,
                latencyMs = result.latencyMs,
                testedAt = testedAt,
            )
        }
        ensureWorkflowCurrent(workflowIsCurrent)

        val unavailable = getUnavailableUnpinned()
        unavailable
            .map(SmartProxyProfileEntity::id)
            .sqliteQueryBatches()
            .forEach { batch -> deleteByIds(batch) }
        ensureWorkflowCurrent(workflowIsCurrent)

        val stale = getStaleUnpinned()
        stale
            .map(SmartProxyProfileEntity::id)
            .sqliteQueryBatches()
            .forEach { batch -> deleteByIds(batch) }
        ensureWorkflowCurrent(workflowIsCurrent)

        clearSelection()
        markSelected(selectedId)
        ensureWorkflowCurrent(workflowIsCurrent)
        return SmartProxyBatchMutation(
            unavailable = unavailable,
            stale = stale,
        )
    }

    @Transaction
    open suspend fun select(id: String) {
        clearSelection()
        markSelected(id)
    }

    /** Selects only a fresh, fully checked tunnel; NOT_TESTED is never an automatic fallback. */
    @Transaction
    open suspend fun selectBestAvailable(excludedIds: Set<String> = emptySet()): String? {
        return selectBestAvailableLocked(excludedIds)
    }

    private suspend fun selectBestAvailableLocked(excludedIds: Set<String> = emptySet()): String? {
        val selectedId = getAvailableCandidates()
            .firstOrNull { candidate -> candidate.id !in excludedIds }
            ?.id
        clearSelection()
        selectedId?.let { id -> markSelected(id) }
        return selectedId
    }

    @Transaction
    open suspend fun applyImport(
        entities: List<SmartProxyProfileEntity>,
        policyRejectedIds: Set<String>,
        remoteSourceUrl: String?,
        syncStartedAt: Long,
        markRemoteStale: Boolean,
    ): List<SmartProxyProfileEntity> {
        val rejectedEntities = policyRejectedIds
            .sqliteQueryBatches()
            .flatMap { batch -> getByIds(batch) }
        policyRejectedIds
            .sqliteQueryBatches()
            .forEach { batch -> deleteByIds(batch) }
        if (entities.isNotEmpty()) {
            upsertAll(entities)
        }
        if (markRemoteStale && remoteSourceUrl != null) {
            markRemoteProfilesStale(remoteSourceUrl, syncStartedAt)
        }
        if (getSelected() == null) {
            selectBestAvailableLocked()
        }
        return rejectedEntities
    }

    @Transaction
    open suspend fun deleteAndSelectFallback(ids: Set<String>): List<SmartProxyProfileEntity> {
        val batches = ids.sqliteQueryBatches()
        val deletedEntities = batches.flatMap { batch -> getByIds(batch) }
        batches.forEach { batch -> deleteByIds(batch) }
        selectFallbackIfNeeded()
        return deletedEntities
    }

    @Transaction
    open suspend fun deleteUnavailableExceptPinnedAndSelectFallback(): List<SmartProxyProfileEntity> {
        val deletedEntities = getUnavailableUnpinned()
        deletedEntities
            .map(SmartProxyProfileEntity::id)
            .sqliteQueryBatches()
            .forEach { batch -> deleteByIds(batch) }
        selectFallbackIfNeeded()
        return deletedEntities
    }

    @Transaction
    open suspend fun deleteStaleExceptPinnedAndSelectFallback(): List<SmartProxyProfileEntity> {
        val deletedEntities = getStaleUnpinned()
        deletedEntities
            .map(SmartProxyProfileEntity::id)
            .sqliteQueryBatches()
            .forEach { batch -> deleteByIds(batch) }
        selectFallbackIfNeeded()
        return deletedEntities
    }

    private suspend fun selectFallbackIfNeeded() {
        if (getSelected() == null) {
            selectBestAvailableLocked()
        }
    }
}

data class SmartProxyBatchMutation(
    val unavailable: List<SmartProxyProfileEntity>,
    val stale: List<SmartProxyProfileEntity>,
)

private fun ensureWorkflowCurrent(workflowIsCurrent: () -> Boolean) {
    if (!workflowIsCurrent()) throw SmartProxyBatchSupersededException()
}
