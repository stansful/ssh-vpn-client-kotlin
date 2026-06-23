package com.stansful.sshvpnclient.data.proxy

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class ProxyProfileDao {
    @Query("SELECT * FROM proxy_profiles ORDER BY isSelected DESC, isStale ASC, updatedAt DESC")
    abstract fun observeAll(): Flow<List<ProxyProfileEntity>>

    @Query("SELECT * FROM proxy_profiles WHERE id = :id LIMIT 1")
    abstract suspend fun getById(id: String): ProxyProfileEntity?

    @Query("SELECT * FROM proxy_profiles WHERE fingerprint = :fingerprint LIMIT 1")
    abstract suspend fun getByFingerprint(fingerprint: String): ProxyProfileEntity?

    @Query("SELECT * FROM proxy_profiles WHERE isSelected = 1 AND isStale = 0 LIMIT 1")
    abstract suspend fun getSelected(): ProxyProfileEntity?

    @Query("SELECT id FROM proxy_profiles WHERE isStale = 0 ORDER BY updatedAt DESC LIMIT 1")
    abstract suspend fun getFirstAvailableId(): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(entity: ProxyProfileEntity)

    @Query("DELETE FROM proxy_profiles WHERE id IN (:ids)")
    abstract suspend fun deleteByIds(ids: Set<String>)

    @Query("SELECT * FROM proxy_profiles WHERE id IN (:ids)")
    abstract suspend fun getByIds(ids: Set<String>): List<ProxyProfileEntity>

    @Query("UPDATE proxy_profiles SET isSelected = 0")
    protected abstract suspend fun clearSelection()

    @Query("UPDATE proxy_profiles SET isSelected = 1, updatedAt = :updatedAt WHERE id = :id")
    protected abstract suspend fun markSelected(id: String, updatedAt: Long)

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
        WHERE id = :id
        """,
    )
    abstract suspend fun updateTestResult(
        id: String,
        status: String,
        latencyMs: Long?,
        testedAt: Long,
    )

    @Transaction
    open suspend fun select(id: String, updatedAt: Long) {
        clearSelection()
        markSelected(id, updatedAt)
    }
}
