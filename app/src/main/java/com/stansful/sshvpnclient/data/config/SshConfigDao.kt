package com.stansful.sshvpnclient.data.config

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class SshConfigDao {
    @Query("SELECT * FROM ssh_configs ORDER BY updatedAt DESC")
    abstract fun observeAll(): Flow<List<SshConfigEntity>>

    @Query("SELECT * FROM ssh_configs WHERE isSelected = 1 LIMIT 1")
    abstract fun observeSelected(): Flow<SshConfigEntity?>

    @Query(
        """
        SELECT c.id, c.name, c.host, c.port, c.username, c.authType,
               c.privateKeyId, k.name AS keyName, c.fingerprint,
               c.keepAliveIntervalSec, c.enableUdpForwarding, c.note,
               c.isSelected, c.updatedAt
        FROM ssh_configs AS c
        LEFT JOIN ssh_private_keys AS k ON k.id = c.privateKeyId
        ORDER BY c.updatedAt DESC
        """,
    )
    abstract fun observeSummaries(): Flow<List<SshConfigSummaryRow>>

    @Query(
        """
        SELECT c.id, c.name, c.host, c.port, c.username, c.authType,
               c.privateKeyId, k.name AS keyName, c.fingerprint,
               c.keepAliveIntervalSec, c.enableUdpForwarding, c.note,
               c.isSelected, c.updatedAt
        FROM ssh_configs AS c
        LEFT JOIN ssh_private_keys AS k ON k.id = c.privateKeyId
        WHERE c.isSelected = 1
        LIMIT 1
        """,
    )
    abstract fun observeSelectedSummary(): Flow<SshConfigSummaryRow?>

    @Query("SELECT * FROM ssh_configs ORDER BY updatedAt DESC")
    abstract suspend fun getAll(): List<SshConfigEntity>

    @Query("SELECT * FROM ssh_configs WHERE id = :id LIMIT 1")
    abstract suspend fun getById(id: String): SshConfigEntity?

    @Query("SELECT * FROM ssh_configs WHERE isSelected = 1 LIMIT 1")
    abstract suspend fun getSelected(): SshConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(entity: SshConfigEntity)

    @Query("DELETE FROM ssh_configs WHERE id = :id")
    abstract suspend fun deleteById(id: String)

    @Query("UPDATE ssh_configs SET isSelected = 0")
    protected abstract suspend fun clearSelection()

    @Query("UPDATE ssh_configs SET isSelected = 1 WHERE id = :id")
    protected abstract suspend fun markSelected(id: String)

    @Query("SELECT COUNT(*) FROM ssh_configs WHERE privateKeyId = :keyId")
    abstract suspend fun countByPrivateKeyId(keyId: String): Int

    @Transaction
    open suspend fun selectConfig(id: String) {
        clearSelection()
        markSelected(id)
    }
}
