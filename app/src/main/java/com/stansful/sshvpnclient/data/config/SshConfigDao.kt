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

    /**
     * Writes [entity] with the fingerprint saved now instead of its own, read and written in one
     * transaction: a host key pinned on first use or accepted while the editor was open survives a save
     * that did not touch the field. Writes nothing if the server is gone.
     */
    @Transaction
    open suspend fun upsertKeepingFingerprint(entity: SshConfigEntity) {
        val saved = getById(entity.id) ?: return
        upsert(entity.copy(fingerprint = saved.fingerprint))
    }

    @Query("DELETE FROM ssh_configs WHERE id = :id")
    abstract suspend fun deleteById(id: String)

    @Query("UPDATE ssh_configs SET isSelected = 0")
    protected abstract suspend fun clearSelection()

    @Query("UPDATE ssh_configs SET isSelected = 1 WHERE id = :id")
    protected abstract suspend fun markSelected(id: String)

    @Query("SELECT COUNT(*) FROM ssh_configs WHERE privateKeyId = :keyId")
    abstract suspend fun countByPrivateKeyId(keyId: String): Int

    /**
     * Trust on first use: saves [fingerprint] only while the server has none, in one statement, so a
     * fingerprint the user saved meanwhile is never overwritten. `updatedAt` is left alone: pinning does
     * not reorder the server list. Returns the number of rows changed (0 or 1).
     */
    @Query(
        "UPDATE ssh_configs SET fingerprint = :fingerprint " +
            "WHERE id = :id AND (fingerprint IS NULL OR TRIM(fingerprint) = '')",
    )
    abstract suspend fun pinFingerprintIfAbsent(id: String, fingerprint: String): Int

    /**
     * Replaces the host key fingerprint, but only while the saved one is still [expected] (the pin the
     * user reviewed), in one statement. Returns the number of rows changed (0 or 1).
     */
    @Query(
        "UPDATE ssh_configs SET fingerprint = :fingerprint " +
            "WHERE id = :id AND TRIM(fingerprint) = :expected",
    )
    abstract suspend fun replaceFingerprint(id: String, expected: String, fingerprint: String): Int

    @Transaction
    open suspend fun selectConfig(id: String) {
        clearSelection()
        markSelected(id)
    }
}
