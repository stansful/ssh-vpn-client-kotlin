package com.stansful.sshvpnclient.data.key

import com.stansful.sshvpnclient.data.config.SshConfigDao
import com.stansful.sshvpnclient.data.secret.SecretIds
import com.stansful.sshvpnclient.data.secret.SecretStorage
import com.stansful.sshvpnclient.domain.model.KeyInUseException
import com.stansful.sshvpnclient.domain.model.SshPrivateKey
import com.stansful.sshvpnclient.domain.model.SshPrivateKeySummary
import com.stansful.sshvpnclient.domain.repository.SshPrivateKeyRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class RoomSshPrivateKeyRepository(
    private val keyDao: SshPrivateKeyDao,
    private val configDao: SshConfigDao,
    private val secretStorage: SecretStorage,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SshPrivateKeyRepository {
    override fun observeAll(): Flow<List<SshPrivateKey>> {
        return keyDao.observeAll().map { entities ->
            entities.map { it.toDomain(secretStorage) }
        }.flowOn(ioDispatcher)
    }

    override fun observeSummaries(): Flow<List<SshPrivateKeySummary>> {
        return keyDao.observeSummaries().map { rows ->
            rows.map { row -> row.toDomain() }
        }.flowOn(ioDispatcher)
    }

    override suspend fun getAll(): List<SshPrivateKey> {
        return withContext(ioDispatcher) {
            keyDao.getAll().map { it.toDomain(secretStorage) }
        }
    }

    override suspend fun getById(id: String): SshPrivateKey? {
        return withContext(ioDispatcher) {
            keyDao.getById(id)?.toDomain(secretStorage)
        }
    }

    override suspend fun create(key: SshPrivateKey) {
        withContext(ioDispatcher) {
            val privateKeySecretId = SecretIds.privateKey(key.id)
            val passphraseSecretId = key.passphrase
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    val secretId = SecretIds.privateKeyPassphrase(key.id)
                    secretStorage.saveSecret(secretId, it)
                    secretId
                }

            secretStorage.saveSecret(privateKeySecretId, key.privateKey)

            keyDao.upsert(
                key.toEntity(
                    privateKeySecretId = privateKeySecretId,
                    passphraseSecretId = passphraseSecretId,
                    createdAt = key.createdAt,
                ),
            )
        }
    }

    override suspend fun update(key: SshPrivateKey) {
        withContext(ioDispatcher) {
            val existing = keyDao.getById(key.id) ?: return@withContext

            secretStorage.saveSecret(existing.privateKeySecretId, key.privateKey)

            val passphraseSecretId = key.passphrase
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    val secretId = existing.passphraseSecretId ?: SecretIds.privateKeyPassphrase(key.id)
                    secretStorage.saveSecret(secretId, it)
                    secretId
                }

            if (passphraseSecretId == null) {
                existing.passphraseSecretId?.let { secretStorage.deleteSecret(it) }
            }

            keyDao.upsert(
                key.toEntity(
                    privateKeySecretId = existing.privateKeySecretId,
                    passphraseSecretId = passphraseSecretId,
                    createdAt = existing.createdAt,
                ),
            )
        }
    }

    override suspend fun delete(id: String) {
        withContext(ioDispatcher) {
            val usageCount = configDao.countByPrivateKeyId(id)
            if (usageCount > 0) {
                throw KeyInUseException(usageCount)
            }

            val existing = keyDao.getById(id) ?: return@withContext
            keyDao.deleteById(id)
            secretStorage.deleteSecret(existing.privateKeySecretId)
            existing.passphraseSecretId?.let { secretStorage.deleteSecret(it) }
        }
    }

    override suspend fun getUsageCount(keyId: String): Int {
        return withContext(ioDispatcher) { configDao.countByPrivateKeyId(keyId) }
    }

    private fun SshPrivateKey.toEntity(
        privateKeySecretId: String,
        passphraseSecretId: String?,
        createdAt: Long,
    ): SshPrivateKeyEntity {
        return SshPrivateKeyEntity(
            id = id,
            name = name,
            privateKeySecretId = privateKeySecretId,
            passphraseSecretId = passphraseSecretId,
            note = note,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }

    private fun SshPrivateKeySummaryRow.toDomain(): SshPrivateKeySummary {
        return SshPrivateKeySummary(
            id = id,
            name = name,
            note = note,
            createdAt = createdAt,
            updatedAt = updatedAt,
            usageCount = usageCount,
        )
    }
}
