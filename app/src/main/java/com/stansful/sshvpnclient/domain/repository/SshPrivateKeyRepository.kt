package com.stansful.sshvpnclient.domain.repository

import com.stansful.sshvpnclient.domain.model.SshPrivateKey
import com.stansful.sshvpnclient.domain.model.SshPrivateKeySummary
import kotlinx.coroutines.flow.Flow

interface SshPrivateKeyRepository {
    fun observeAll(): Flow<List<SshPrivateKey>>
    fun observeSummaries(): Flow<List<SshPrivateKeySummary>>
    suspend fun getAll(): List<SshPrivateKey>
    suspend fun getById(id: String): SshPrivateKey?
    suspend fun create(key: SshPrivateKey)
    suspend fun update(key: SshPrivateKey)
    suspend fun delete(id: String)
    suspend fun getUsageCount(keyId: String): Int
}
