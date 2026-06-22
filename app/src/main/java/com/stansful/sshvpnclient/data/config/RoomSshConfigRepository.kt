package com.stansful.sshvpnclient.data.config

import com.stansful.sshvpnclient.data.secret.SecretIds
import com.stansful.sshvpnclient.data.secret.SecretStorage
import com.stansful.sshvpnclient.domain.model.AuthType
import com.stansful.sshvpnclient.domain.model.SshConfig
import com.stansful.sshvpnclient.domain.model.SshConfigSummary
import com.stansful.sshvpnclient.domain.repository.SshConfigRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class RoomSshConfigRepository(
    private val dao: SshConfigDao,
    private val secretStorage: SecretStorage,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SshConfigRepository {
    override fun observeAll(): Flow<List<SshConfig>> {
        return dao.observeAll().map { entities ->
            entities.map { it.toDomain(secretStorage) }
        }.flowOn(ioDispatcher)
    }

    override fun observeSelectedConfig(): Flow<SshConfig?> {
        return dao.observeSelected().map { entity ->
            entity?.toDomain(secretStorage)
        }.flowOn(ioDispatcher)
    }

    override fun observeSummaries(): Flow<List<SshConfigSummary>> {
        return dao.observeSummaries().map { rows ->
            rows.map { row -> row.toDomain() }
        }
            .flowOn(ioDispatcher)
    }

    override fun observeSelectedSummary(): Flow<SshConfigSummary?> {
        return dao.observeSelectedSummary().map { row -> row?.toDomain() }
            .flowOn(ioDispatcher)
    }

    override suspend fun getAll(): List<SshConfig> {
        return withContext(ioDispatcher) {
            dao.getAll().map { it.toDomain(secretStorage) }
        }
    }

    override suspend fun getById(id: String): SshConfig? {
        return withContext(ioDispatcher) {
            dao.getById(id)?.toDomain(secretStorage)
        }
    }

    override suspend fun create(config: SshConfig) {
        withContext(ioDispatcher) {
            val passwordSecretId = savePasswordIfNeeded(config, existingSecretId = null)
            val shouldSelect = dao.getSelected() == null

            dao.upsert(
                config.toEntity(
                    passwordSecretId = passwordSecretId,
                    isSelected = shouldSelect,
                    createdAt = config.createdAt,
                ),
            )
        }
    }

    override suspend fun update(config: SshConfig) {
        withContext(ioDispatcher) {
            val existing = dao.getById(config.id) ?: return@withContext
            val passwordSecretId = savePasswordIfNeeded(config, existing.passwordSecretId)

            if (config.authType != AuthType.PASSWORD) {
                existing.passwordSecretId?.let { secretStorage.deleteSecret(it) }
            }

            dao.upsert(
                config.toEntity(
                    passwordSecretId = passwordSecretId,
                    isSelected = existing.isSelected,
                    createdAt = existing.createdAt,
                ),
            )
        }
    }

    override suspend fun delete(id: String) {
        withContext(ioDispatcher) {
            val existing = dao.getById(id) ?: return@withContext
            dao.deleteById(id)
            existing.passwordSecretId?.let { secretStorage.deleteSecret(it) }
        }
    }

    override suspend fun selectConfig(id: String) {
        withContext(ioDispatcher) { dao.selectConfig(id) }
    }

    override suspend fun getSelectedConfig(): SshConfig? {
        return withContext(ioDispatcher) {
            dao.getSelected()?.toDomain(secretStorage)
        }
    }

    override suspend fun getUsageCountForKey(keyId: String): Int {
        return withContext(ioDispatcher) { dao.countByPrivateKeyId(keyId) }
    }

    private suspend fun savePasswordIfNeeded(
        config: SshConfig,
        existingSecretId: String?,
    ): String? {
        if (config.authType != AuthType.PASSWORD) return null

        val secretId = existingSecretId ?: SecretIds.configPassword(config.id)
        secretStorage.saveSecret(secretId, config.password.orEmpty())
        return secretId
    }

    private fun SshConfig.toEntity(
        passwordSecretId: String?,
        isSelected: Boolean,
        createdAt: Long,
    ): SshConfigEntity {
        return SshConfigEntity(
            id = id,
            name = name,
            host = host,
            port = port,
            username = username,
            authType = authType.name,
            passwordSecretId = passwordSecretId,
            privateKeyId = privateKeyId.takeIf { authType == AuthType.PRIVATE_KEY },
            fingerprint = fingerprint,
            keepAliveIntervalSec = keepAliveIntervalSec,
            enableUdpForwarding = enableUdpForwarding,
            note = note,
            isSelected = isSelected,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }

    private fun SshConfigSummaryRow.toDomain(): SshConfigSummary {
        return SshConfigSummary(
            id = id,
            name = name,
            host = host,
            port = port,
            username = username,
            authType = AuthType.valueOf(authType),
            privateKeyId = privateKeyId,
            keyName = keyName,
            fingerprint = fingerprint,
            keepAliveIntervalSec = keepAliveIntervalSec,
            enableUdpForwarding = enableUdpForwarding,
            note = note,
            isSelected = isSelected,
            updatedAt = updatedAt,
        )
    }
}
