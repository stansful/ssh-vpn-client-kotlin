package com.stansful.sshvpnclient.domain.repository

import com.stansful.sshvpnclient.domain.model.SshConfig
import com.stansful.sshvpnclient.domain.model.SshConfigSummary
import kotlinx.coroutines.flow.Flow

interface SshConfigRepository {
    fun observeAll(): Flow<List<SshConfig>>
    fun observeSelectedConfig(): Flow<SshConfig?>
    fun observeSummaries(): Flow<List<SshConfigSummary>>
    fun observeSelectedSummary(): Flow<SshConfigSummary?>
    suspend fun getAll(): List<SshConfig>
    suspend fun getById(id: String): SshConfig?
    suspend fun create(config: SshConfig)
    /**
     * Saves [config] over the stored server. With [keepSavedFingerprint] the fingerprint stored at the
     * moment of the write is kept instead of [config]'s (the editor did not touch the field, and the
     * service may have pinned a key meanwhile).
     */
    suspend fun update(config: SshConfig, keepSavedFingerprint: Boolean = false)
    suspend fun delete(id: String)
    suspend fun selectConfig(id: String)
    suspend fun getSelectedConfig(): SshConfig?
    suspend fun getUsageCountForKey(keyId: String): Int

    /**
     * Pins [fingerprint] as server [id]'s host key if it has none saved yet (trust on first use). Never
     * replaces a saved fingerprint. Returns true when this call saved it.
     */
    suspend fun pinFingerprintIfAbsent(id: String, fingerprint: String): Boolean

    /** Replaces server [id]'s host key fingerprint (the user accepted a changed key). Returns true if saved. */
    suspend fun replaceFingerprint(id: String, fingerprint: String): Boolean
}
