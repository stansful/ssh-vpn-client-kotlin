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
    suspend fun update(config: SshConfig)
    suspend fun delete(id: String)
    suspend fun selectConfig(id: String)
    suspend fun getSelectedConfig(): SshConfig?
    suspend fun getUsageCountForKey(keyId: String): Int
}
