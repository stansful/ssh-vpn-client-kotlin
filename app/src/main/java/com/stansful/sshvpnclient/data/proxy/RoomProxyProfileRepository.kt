package com.stansful.sshvpnclient.data.proxy

import com.stansful.sshvpnclient.data.secret.SecretIds
import com.stansful.sshvpnclient.data.secret.SecretStorage
import com.stansful.sshvpnclient.domain.model.ParsedProxyProfile
import com.stansful.sshvpnclient.domain.model.ProxyImportResult
import com.stansful.sshvpnclient.domain.model.ProxyProfile
import com.stansful.sshvpnclient.domain.model.ProxyProfileSource
import com.stansful.sshvpnclient.domain.model.ProxyProfileSummary
import com.stansful.sshvpnclient.domain.model.ProxySecurity
import com.stansful.sshvpnclient.domain.model.ProxyTestStatus
import com.stansful.sshvpnclient.domain.model.ProxyTransport
import com.stansful.sshvpnclient.domain.model.ProxyTunnelTestResult
import com.stansful.sshvpnclient.domain.repository.ProxyProfileRepository
import com.stansful.sshvpnclient.domain.usecase.proxy.ProxyParseResult
import com.stansful.sshvpnclient.domain.usecase.proxy.ProxyShareLinkParser
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class RoomProxyProfileRepository(
    private val dao: ProxyProfileDao,
    private val secretStorage: SecretStorage,
    private val parser: ProxyShareLinkParser,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ProxyProfileRepository {
    private val mutationMutex = Mutex()

    override fun observeSummaries(): Flow<List<ProxyProfileSummary>> {
        return dao.observeAll()
            .map { entities -> entities.map(ProxyProfileEntity::toSummary) }
            .flowOn(ioDispatcher)
    }

    override suspend fun getById(id: String): ProxyProfile? = mutationMutex.withLock {
        withContext(ioDispatcher) {
            dao.getById(id)?.toDomain(secretStorage)
        }
    }

    override suspend fun getByIds(ids: List<String>): List<ProxyProfile> {
        if (ids.isEmpty()) return emptyList()
        return mutationMutex.withLock {
            withContext(ioDispatcher) {
                val uniqueIds = ids.distinct()
                val entitiesById = uniqueIds
                    .sqliteQueryBatches()
                    .flatMap { batch -> dao.getByIds(batch) }
                    .associateBy(ProxyProfileEntity::id)
                val secretsById = secretStorage.getSecrets(
                    entitiesById.values.map(ProxyProfileEntity::secretId),
                )

                ids.mapNotNull { id ->
                    entitiesById[id]?.toDomain(secretsById)
                }
            }
        }
    }

    override suspend fun getSelected(): ProxyProfile? = mutationMutex.withLock {
        withContext(ioDispatcher) {
            dao.getSelected()?.toDomain(secretStorage)
        }
    }

    override suspend fun import(
        text: String,
        source: ProxyProfileSource,
        sourceUrl: String?,
    ): ProxyImportResult = mutationMutex.withLock {
        withContext(ioDispatcher) {
            val parsedResults = parser.parseMany(text)
            val successful = parsedResults.mapNotNull { result ->
                (result as? ProxyParseResult.Success)?.profile
            }
            val uniqueProfiles = successful.distinctBy(ParsedProxyProfile::fingerprint)
            val syncStartedAt = System.currentTimeMillis()
            var added = 0
            var updated = 0
            var duplicates = successful.size - uniqueProfiles.size
            var unsupported = 0

            val existingByFingerprint = uniqueProfiles
                .map(ParsedProxyProfile::fingerprint)
                .chunked(SQLITE_QUERY_BATCH_SIZE)
                .flatMap { fingerprints -> dao.getByFingerprints(fingerprints) }
                .associateBy(ProxyProfileEntity::fingerprint)
            val entitiesToUpsert = ArrayList<ProxyProfileEntity>(uniqueProfiles.size)
            val secretsToSave = LinkedHashMap<String, String>(uniqueProfiles.size)

            uniqueProfiles.forEach { parsed ->
                if (parsed.transport == ProxyTransport.UNKNOWN || parsed.security == ProxySecurity.UNKNOWN) {
                    unsupported += 1
                }
                val existing = existingByFingerprint[parsed.fingerprint]
                if (existing != null && (source != ProxyProfileSource.REMOTE || existing.source != source.name)) {
                    duplicates += 1
                    return@forEach
                }

                val id = existing?.id ?: UUID.randomUUID().toString()
                // Equal canonical fingerprints describe the same outbound, so a remote refresh can
                // retain the current encrypted URI and avoid rewriting Tink/SharedPreferences.
                val secretId = existing?.secretId ?: SecretIds.proxyProfileRevision(
                    profileId = id,
                    revisionId = UUID.randomUUID().toString(),
                ).also { newSecretId -> secretsToSave[newSecretId] = parsed.rawUri }
                entitiesToUpsert += parsed.toEntity(
                    id = id,
                    source = source,
                    sourceUrl = sourceUrl,
                    secretId = secretId,
                    existing = existing,
                    now = syncStartedAt,
                )
                if (existing == null) added += 1 else updated += 1
            }

            try {
                secretStorage.saveSecrets(secretsToSave)
                withContext(NonCancellable) {
                    dao.applyImport(
                        entities = entitiesToUpsert,
                        remoteSourceUrl = sourceUrl.takeIf { source == ProxyProfileSource.REMOTE },
                        syncStartedAt = syncStartedAt,
                        markRemoteStale = source == ProxyProfileSource.REMOTE &&
                            sourceUrl != null &&
                            uniqueProfiles.isNotEmpty(),
                    )
                }
            } catch (error: Exception) {
                withContext(NonCancellable) {
                    try {
                        secretStorage.deleteSecrets(secretsToSave.keys)
                    } catch (cleanupError: Exception) {
                        error.addSuppressed(cleanupError)
                    }
                }
                throw error
            }

            ProxyImportResult(
                added = added,
                updated = updated,
                duplicates = duplicates,
                invalid = parsedResults.count { it is ProxyParseResult.Failure },
                unsupported = unsupported,
                total = parsedResults.size,
            )
        }
    }

    override suspend fun update(id: String, rawUri: String): ProxyImportResult = mutationMutex.withLock {
        withContext(ioDispatcher) {
            val existing = dao.getById(id) ?: return@withContext emptyImportResult(invalid = 1)
            val parsed = parser.parse(rawUri) as? ProxyParseResult.Success
                ?: return@withContext emptyImportResult(invalid = 1)
            val duplicate = dao.getByFingerprint(parsed.profile.fingerprint)
            if (duplicate != null && duplicate.id != id) {
                return@withContext emptyImportResult(duplicates = 1)
            }
            val nextSecretId = SecretIds.proxyProfileRevision(
                profileId = id,
                revisionId = UUID.randomUUID().toString(),
            )
            try {
                secretStorage.saveSecret(nextSecretId, parsed.profile.rawUri)
                withContext(NonCancellable) {
                    dao.upsert(
                        parsed.profile.toEntity(
                            id = id,
                            source = ProxyProfileSource.MANUAL,
                            sourceUrl = null,
                            secretId = nextSecretId,
                            existing = existing,
                            now = System.currentTimeMillis(),
                        ),
                    )
                }
            } catch (error: Exception) {
                withContext(NonCancellable) {
                    try {
                        secretStorage.deleteSecret(nextSecretId)
                    } catch (cleanupError: Exception) {
                        error.addSuppressed(cleanupError)
                    }
                }
                throw error
            }
            withContext(NonCancellable) {
                deleteSecretsBestEffort(listOf(existing.secretId))
            }
            emptyImportResult(updated = 1)
        }
    }

    override suspend fun select(id: String) = mutationMutex.withLock {
        withContext(ioDispatcher) {
            dao.select(id)
        }
    }

    override suspend fun setPinned(id: String, pinned: Boolean) = mutationMutex.withLock {
        withContext(ioDispatcher) {
            dao.setPinned(id, pinned)
        }
    }

    override suspend fun delete(ids: Set<String>) = mutationMutex.withLock {
        withContext(ioDispatcher) {
            if (ids.isEmpty()) return@withContext
            val entities = dao.deleteAndSelectFallback(ids)
            withContext(NonCancellable) {
                deleteSecretsBestEffort(entities.map(ProxyProfileEntity::secretId))
            }
            Unit
        }
    }

    override suspend fun deleteUnavailableExceptPinned(): Int = mutationMutex.withLock {
        withContext(ioDispatcher) {
            withContext(NonCancellable) {
                val entities = dao.deleteUnavailableExceptPinnedAndSelectFallback()
                deleteSecretsBestEffort(entities.map(ProxyProfileEntity::secretId))
                entities.size
            }
        }
    }

    override suspend fun saveTestResult(result: ProxyTunnelTestResult) {
        saveTestResults(listOf(result))
    }

    override suspend fun saveTestResults(results: List<ProxyTunnelTestResult>) {
        if (results.isEmpty()) return
        mutationMutex.withLock {
            withContext(ioDispatcher) {
                dao.updateTestResults(
                    results = results.map { result ->
                        ProxyTestResultUpdate(
                            profileId = result.profileId,
                            profileFingerprint = result.profileFingerprint,
                            status = result.status,
                            latencyMs = result.latencyMs,
                        )
                    },
                    testedAt = System.currentTimeMillis(),
                )
            }
        }
    }

    private suspend fun deleteSecretsBestEffort(ids: Collection<String>) {
        try {
            secretStorage.deleteSecrets(ids)
        } catch (_: Exception) {
            // The Room row already points at the new revision; an orphan is safer than rollback.
        }
    }

    private fun emptyImportResult(
        updated: Int = 0,
        duplicates: Int = 0,
        invalid: Int = 0,
    ) = ProxyImportResult(
        added = 0,
        updated = updated,
        duplicates = duplicates,
        invalid = invalid,
        unsupported = 0,
        total = 1,
    )

}

private fun ParsedProxyProfile.toEntity(
    id: String,
    source: ProxyProfileSource,
    sourceUrl: String?,
    secretId: String,
    existing: ProxyProfileEntity?,
    now: Long,
): ProxyProfileEntity {
    return ProxyProfileEntity(
        id = id,
        name = name,
        protocol = protocol.name,
        host = host,
        port = port,
        transport = transport.name,
        security = security.name,
        flow = flow,
        source = source.name,
        sourceUrl = sourceUrl,
        secretId = secretId,
        fingerprint = fingerprint,
        isSelected = existing?.isSelected ?: false,
        isPinned = existing?.isPinned ?: false,
        isStale = false,
        lastTestStatus = existing?.lastTestStatus ?: ProxyTestStatus.NOT_TESTED.name,
        lastLatencyMs = existing?.lastLatencyMs,
        lastTestAt = existing?.lastTestAt,
        createdAt = existing?.createdAt ?: now,
        updatedAt = now,
        lastSeenAt = now,
    )
}

private fun ProxyProfileEntity.toSummary(): ProxyProfileSummary {
    return ProxyProfileSummary(
        id = id,
        name = name,
        protocol = enumValueOf(protocol),
        host = host,
        port = port,
        transport = enumValueOf(transport),
        security = enumValueOf(security),
        flow = flow,
        fingerprint = fingerprint,
        source = enumValueOf(source),
        isSelected = isSelected,
        isPinned = isPinned,
        isStale = isStale,
        lastTestStatus = enumValueOf(lastTestStatus),
        lastLatencyMs = lastLatencyMs,
        updatedAt = updatedAt,
    )
}

private suspend fun ProxyProfileEntity.toDomain(secretStorage: SecretStorage): ProxyProfile? {
    val rawUri = secretStorage.getSecret(secretId) ?: return null
    return toDomain(rawUri)
}

private fun ProxyProfileEntity.toDomain(secretsById: Map<String, String>): ProxyProfile? {
    return secretsById[secretId]?.let(::toDomain)
}

private fun ProxyProfileEntity.toDomain(rawUri: String): ProxyProfile {
    return ProxyProfile(
        id = id,
        name = name,
        protocol = enumValueOf(protocol),
        host = host,
        port = port,
        transport = enumValueOf(transport),
        security = enumValueOf(security),
        flow = flow,
        source = enumValueOf(source),
        sourceUrl = sourceUrl,
        rawUri = rawUri,
        fingerprint = fingerprint,
        isSelected = isSelected,
        isPinned = isPinned,
        isStale = isStale,
        lastTestStatus = enumValueOf(lastTestStatus),
        lastLatencyMs = lastLatencyMs,
        lastTestAt = lastTestAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastSeenAt = lastSeenAt,
    )
}
