package com.stansful.sshvpnclient.data.key

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SshPrivateKeyDao {
    @Query("SELECT * FROM ssh_private_keys ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<SshPrivateKeyEntity>>

    @Query(
        """
        SELECT k.id, k.name, k.note, k.createdAt, k.updatedAt,
               COUNT(c.id) AS usageCount
        FROM ssh_private_keys AS k
        LEFT JOIN ssh_configs AS c ON c.privateKeyId = k.id
        GROUP BY k.id, k.name, k.note, k.createdAt, k.updatedAt
        ORDER BY k.updatedAt DESC
        """,
    )
    fun observeSummaries(): Flow<List<SshPrivateKeySummaryRow>>

    @Query("SELECT * FROM ssh_private_keys ORDER BY updatedAt DESC")
    suspend fun getAll(): List<SshPrivateKeyEntity>

    @Query("SELECT * FROM ssh_private_keys WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): SshPrivateKeyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SshPrivateKeyEntity)

    @Query("DELETE FROM ssh_private_keys WHERE id = :id")
    suspend fun deleteById(id: String)
}
