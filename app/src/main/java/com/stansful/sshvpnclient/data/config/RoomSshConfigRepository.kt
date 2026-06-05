package com.stansful.sshvpnclient.data.config

import com.stansful.sshvpnclient.data.secret.SecretIds
import com.stansful.sshvpnclient.data.secret.SecretStorage
import com.stansful.sshvpnclient.domain.model.AuthType
import com.stansful.sshvpnclient.domain.model.SshConfig
import com.stansful.sshvpnclient.domain.repository.SshConfigRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomSshConfigRepository(
    private val dao: SshConfigDao,
    private val secretStorage: SecretStorage,
) : SshConfigRepository {
    override fun observeAll(): Flow<List<SshConfig>> {
        return dao.observeAll().map { entities ->
            entities.map { it.toDomain(secretStorage) }
        }
    }

    override fun observeSelectedConfig(): Flow<SshConfig?> {
        return dao.observeSelected().map { entity ->
            entity?.toDomain(secretStorage)
        }
    }

    override suspend fun getAll(): List<SshConfig> {
        return dao.getAll().map { it.toDomain(secretStorage) }
    }

    override suspend fun getById(id: String): SshConfig? {
        return dao.getById(id)?.toDomain(secretStorage)
    }

    override suspend fun create(config: SshConfig) {
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

    override suspend fun update(config: SshConfig) {
        val existing = dao.getById(config.id) ?: return
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

    override suspend fun delete(id: String) {
        val existing = dao.getById(id) ?: return
        dao.deleteById(id)
        existing.passwordSecretId?.let { secretStorage.deleteSecret(it) }
    }

    override suspend fun selectConfig(id: String) {
        dao.selectConfig(id)
    }

    override suspend fun getSelectedConfig(): SshConfig? {
        return dao.getSelected()?.toDomain(secretStorage)
    }

    override suspend fun getUsageCountForKey(keyId: String): Int {
        return dao.countByPrivateKeyId(keyId)
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
}
