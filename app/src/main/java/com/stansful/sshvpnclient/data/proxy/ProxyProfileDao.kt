package com.stansful.sshvpnclient.data.proxy

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.stansful.sshvpnclient.domain.model.ProxyTestStatus
import kotlinx.coroutines.flow.Flow

data class ProxyTestResultUpdate(
    val profileId: String,
    val profileFingerprint: String?,
    val status: ProxyTestStatus,
    val latencyMs: Long?,
)

@Dao
abstract class ProxyProfileDao {
    @Query("SELECT * FROM proxy_profiles ORDER BY isStale ASC, updatedAt DESC")
    abstract fun observeAll(): Flow<List<ProxyProfileEntity>>

    @Query("SELECT * FROM proxy_profiles WHERE id = :id LIMIT 1")
    abstract suspend fun getById(id: String): ProxyProfileEntity?

    @Query("SELECT * FROM proxy_profiles WHERE fingerprint = :fingerprint LIMIT 1")
    abstract suspend fun getByFingerprint(fingerprint: String): ProxyProfileEntity?

    @Query("SELECT * FROM proxy_profiles WHERE fingerprint IN (:fingerprints)")
    abstract suspend fun getByFingerprints(fingerprints: List<String>): List<ProxyProfileEntity>

    @Query("SELECT * FROM proxy_profiles WHERE isSelected = 1 AND isStale = 0 LIMIT 1")
    abstract suspend fun getSelected(): ProxyProfileEntity?

    @Query("SELECT id FROM proxy_profiles WHERE isStale = 0 ORDER BY updatedAt DESC LIMIT 1")
    abstract suspend fun getFirstAvailableId(): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(entity: ProxyProfileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertAll(entities: List<ProxyProfileEntity>)

    @Query("DELETE FROM proxy_profiles WHERE id IN (:ids)")
    abstract suspend fun deleteByIds(ids: List<String>)

    @Query("SELECT * FROM proxy_profiles WHERE id IN (:ids)")
    abstract suspend fun getByIds(ids: List<String>): List<ProxyProfileEntity>

    @Query("SELECT * FROM proxy_profiles WHERE lastTestStatus = 'UNAVAILABLE' AND isPinned = 0")
    protected abstract suspend fun getUnavailableUnpinned(): List<ProxyProfileEntity>

    @Query("UPDATE proxy_profiles SET isSelected = 0")
    protected abstract suspend fun clearSelection()

    @Query("UPDATE proxy_profiles SET isSelected = 1 WHERE id = :id")
    protected abstract suspend fun markSelected(id: String)

    @Query("UPDATE proxy_profiles SET isPinned = :isPinned WHERE id = :id")
    abstract suspend fun setPinned(id: String, isPinned: Boolean)

    @Query(
        """
        UPDATE proxy_profiles
        SET isStale = 1
        WHERE source = 'REMOTE' AND sourceUrl = :sourceUrl AND lastSeenAt < :syncStartedAt
        """,
    )
    abstract suspend fun markRemoteProfilesStale(sourceUrl: String, syncStartedAt: Long)

    @Query(
        """
        UPDATE proxy_profiles
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

    @Transaction
    open suspend fun select(id: String) {
        clearSelection()
        markSelected(id)
    }

    @Transaction
    open suspend fun applyImport(
        entities: List<ProxyProfileEntity>,
        remoteSourceUrl: String?,
        syncStartedAt: Long,
        markRemoteStale: Boolean,
    ) {
        if (entities.isNotEmpty()) {
            upsertAll(entities)
        }
        if (markRemoteStale && remoteSourceUrl != null) {
            markRemoteProfilesStale(remoteSourceUrl, syncStartedAt)
        }
        if (getSelected() == null) {
            clearSelection()
            getFirstAvailableId()?.let { id -> markSelected(id) }
        }
    }

    @Transaction
    open suspend fun deleteAndSelectFallback(ids: Set<String>): List<ProxyProfileEntity> {
        val batches = ids.sqliteQueryBatches()
        val deletedEntities = batches.flatMap { batch -> getByIds(batch) }
        batches.forEach { batch -> deleteByIds(batch) }
        selectFallbackIfNeeded()
        return deletedEntities
    }

    @Transaction
    open suspend fun deleteUnavailableExceptPinnedAndSelectFallback(): List<ProxyProfileEntity> {
        val deletedEntities = getUnavailableUnpinned()
        deletedEntities
            .map(ProxyProfileEntity::id)
            .sqliteQueryBatches()
            .forEach { batch -> deleteByIds(batch) }
        selectFallbackIfNeeded()
        return deletedEntities
    }

    private suspend fun selectFallbackIfNeeded() {
        if (getSelected() == null) {
            clearSelection()
            getFirstAvailableId()?.let { id -> markSelected(id) }
        }
    }
}

// Leave headroom below SQLite's common 999 bind-parameter limit.
internal const val SQLITE_QUERY_BATCH_SIZE = 900

internal fun <T> Collection<T>.sqliteQueryBatches(
    batchSize: Int = SQLITE_QUERY_BATCH_SIZE,
): List<List<T>> {
    require(batchSize > 0) { "SQLite query batch size must be positive" }
    return chunked(batchSize)
}
