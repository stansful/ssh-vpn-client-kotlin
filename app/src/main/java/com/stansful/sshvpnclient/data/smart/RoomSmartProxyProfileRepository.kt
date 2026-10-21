package com.stansful.sshvpnclient.data.smart

import com.stansful.sshvpnclient.data.proxy.ProxyTestResultUpdate
import com.stansful.sshvpnclient.data.proxy.SQLITE_QUERY_BATCH_SIZE
import com.stansful.sshvpnclient.data.proxy.legacyFingerprintMatches
import com.stansful.sshvpnclient.data.proxy.sqliteQueryBatches
import com.stansful.sshvpnclient.data.secret.SecretIds
import com.stansful.sshvpnclient.data.secret.SecretStorage
import com.stansful.sshvpnclient.data.secret.readableSecretIds
import com.stansful.sshvpnclient.domain.model.ParsedProxyProfile
import com.stansful.sshvpnclient.domain.model.ProxyImportResult
import com.stansful.sshvpnclient.domain.model.ProxyProfile
import com.stansful.sshvpnclient.domain.model.ProxyProfileSource
import com.stansful.sshvpnclient.domain.model.ProxyProfileSummary
import com.stansful.sshvpnclient.domain.model.ProxySecurity
import com.stansful.sshvpnclient.domain.model.ProxyTestStatus
import com.stansful.sshvpnclient.domain.model.ProxyTransport
import com.stansful.sshvpnclient.domain.model.ProxyTunnelTestResult
import com.stansful.sshvpnclient.domain.repository.SmartProxyProfileRepository
import com.stansful.sshvpnclient.domain.repository.SmartProxyBatchFinalization
import com.stansful.sshvpnclient.domain.usecase.proxy.ProxyParseResult
import com.stansful.sshvpnclient.domain.usecase.proxy.ProxyShareLinkParser
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class RoomSmartProxyProfileRepository(
    private val dao: SmartProxyProfileDao,
    private val secretStorage: SecretStorage,
    private val parser: ProxyShareLinkParser,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SmartProxyProfileRepository {
    private val mutationMutex = Mutex()

    override fun observeSummaries(): Flow<List<ProxyProfileSummary>> {
        return dao.observeAll()
            .map { entities -> entities.map(SmartProxyProfileEntity::toSummary) }
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
                    .associateBy(SmartProxyProfileEntity::id)
                val secretsById = secretStorage.getSecrets(
                    entitiesById.values.map(SmartProxyProfileEntity::secretId),
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

    override suspend fun selectBestAvailable(excludedIds: Set<String>): ProxyProfile? =
        mutationMutex.withLock {
            withContext(ioDispatcher) {
                dao.selectBestAvailable(excludedIds)
                    ?.let { id -> dao.getById(id)?.toDomain(secretStorage) }
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
            val policyRejectedProfiles = successful.filter { profile ->
                profile.name.contains(SMART_EXCLUDED_NAME_MARKER)
            }
            val policyAllowedProfiles = successful.filterNot { profile ->
                profile.name.contains(SMART_EXCLUDED_NAME_MARKER)
            }
            val uniqueProfiles = policyAllowedProfiles.distinctBy(ParsedProxyProfile::fingerprint)
            val syncStartedAt = System.currentTimeMillis()
            var added = 0
            var updated = 0
            var duplicates = policyAllowedProfiles.size - uniqueProfiles.size
            // Well-known links the engine can't run (Hysteria v1…) are reported apart from malformed ones.
            val unsupportedSkipped = parsedResults.count { it is ProxyParseResult.Failure && it.unsupported }
            var unsupported = successful.size - policyAllowedProfiles.size + unsupportedSkipped

            val existingByFingerprint = successful
                .flatMap { parsed -> listOfNotNull(parsed.fingerprint, parsed.legacyFingerprint) }
                .distinct()
                .chunked(SQLITE_QUERY_BATCH_SIZE)
                .flatMap { fingerprints -> dao.getByFingerprints(fingerprints) }
                .associateBy(SmartProxyProfileEntity::fingerprint)
            // A row saved before a `+` in a VLESS or Trojan credential was kept has an older
            // fingerprint; it is found by that one and takes the current fingerprint below.
            val legacyRows = legacyFingerprintMatches(
                profiles = successful.distinctBy(ParsedProxyProfile::fingerprint),
                rowsByFingerprint = existingByFingerprint,
                secretIdOf = SmartProxyProfileEntity::secretId,
                secretStorage = secretStorage,
                parser = parser,
            )
            val existingFor = { fingerprint: String ->
                existingByFingerprint[fingerprint] ?: legacyRows[fingerprint]
            }
            val allowedFingerprints = uniqueProfiles.mapTo(hashSetOf(), ParsedProxyProfile::fingerprint)
            val policyRejectedIds = policyRejectedProfiles
                .asSequence()
                .map(ParsedProxyProfile::fingerprint)
                .filterNot(allowedFingerprints::contains)
                .mapNotNull { fingerprint -> existingFor(fingerprint)?.id }
                .toSet()
            // A row can outlive its link: the secret store starts over when its Keystore key is
            // gone. Such a row takes the link from this sync, or it would stay unusable for good.
            // A store that can't be read right now fails the sync here, before anything changes.
            val readableSecretIds = secretStorage.readableSecretIds(
                uniqueProfiles.mapNotNull { parsed -> existingFor(parsed.fingerprint)?.secretId },
            )
            val entitiesToUpsert = ArrayList<SmartProxyProfileEntity>(uniqueProfiles.size)
            val secretsToSave = LinkedHashMap<String, String>(uniqueProfiles.size)
            val unreadableSecretIds = ArrayList<String>()

            uniqueProfiles.forEach { parsed ->
                if (parsed.transport == ProxyTransport.UNKNOWN || parsed.security == ProxySecurity.UNKNOWN) {
                    unsupported += 1
                }
                val existing = existingFor(parsed.fingerprint)
                val existingReadable = existing != null && existing.secretId in readableSecretIds
                if (existing != null && (source != ProxyProfileSource.REMOTE || existing.source != source.name)) {
                    if (existingReadable) {
                        duplicates += 1
                        if (existing.fingerprint != parsed.fingerprint) {
                            entitiesToUpsert += existing.copy(fingerprint = parsed.fingerprint)
                        }
                        return@forEach
                    }
                    // Still a duplicate of that row, so the row keeps its source and state; only
                    // its lost link is restored.
                    val secretId = SecretIds.smartProxyProfileRevision(
                        profileId = existing.id,
                        revisionId = UUID.randomUUID().toString(),
                    ).also { newSecretId -> secretsToSave[newSecretId] = parsed.rawUri }
                    entitiesToUpsert += existing.copy(secretId = secretId, fingerprint = parsed.fingerprint)
                    unreadableSecretIds += existing.secretId
                    updated += 1
                    return@forEach
                }

                val id = existing?.id ?: UUID.randomUUID().toString()
                val secretId = existing?.secretId?.takeIf { existingReadable } ?: SecretIds.smartProxyProfileRevision(
                    profileId = id,
                    revisionId = UUID.randomUUID().toString(),
                ).also { newSecretId -> secretsToSave[newSecretId] = parsed.rawUri }
                if (existing != null && !existingReadable) {
                    unreadableSecretIds += existing.secretId
                }
                entitiesToUpsert += parsed.toSmartEntity(
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
                    val rejectedEntities = dao.applyImport(
                        entities = entitiesToUpsert,
                        policyRejectedIds = policyRejectedIds,
                        syncStartedAt = syncStartedAt,
                        markRemoteStale = source == ProxyProfileSource.REMOTE &&
                            successful.isNotEmpty(),
                    )
                    deleteSecretsBestEffort(rejectedEntities.map(SmartProxyProfileEntity::secretId))
                    // Whatever is left under the replaced ids can't be read; nothing points at it.
                    deleteSecretsBestEffort(unreadableSecretIds)
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
                invalid = parsedResults.count { it is ProxyParseResult.Failure && !it.unsupported },
                unsupported = unsupported,
                total = parsedResults.size,
                unsupportedSkipped = unsupportedSkipped,
            )
        }
    }

    override suspend fun update(id: String, rawUri: String): ProxyImportResult =
        mutationMutex.withLock {
            withContext(ioDispatcher) {
                val existing = dao.getById(id) ?: return@withContext emptyImportResult(invalid = 1)
                val parsed = when (val result = parser.parse(rawUri)) {
                    is ProxyParseResult.Success -> result
                    is ProxyParseResult.Failure -> return@withContext if (result.unsupported) {
                        emptyImportResult(unsupported = 1, unsupportedSkipped = 1)
                    } else {
                        emptyImportResult(invalid = 1)
                    }
                }
                if (parsed.profile.name.contains(SMART_EXCLUDED_NAME_MARKER)) {
                    return@withContext emptyImportResult(unsupported = 1)
                }
                val duplicate = dao.getByFingerprint(parsed.profile.fingerprint)
                    ?: legacyRowOf(parsed.profile, exceptId = id)
                if (duplicate != null && duplicate.id != id) {
                    return@withContext emptyImportResult(duplicates = 1)
                }
                val nextSecretId = SecretIds.smartProxyProfileRevision(
                    profileId = id,
                    revisionId = UUID.randomUUID().toString(),
                )
                try {
                    secretStorage.saveSecret(nextSecretId, parsed.profile.rawUri)
                    withContext(NonCancellable) {
                        dao.upsert(
                            parsed.profile.toSmartEntity(
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
                deleteSecretsBestEffort(entities.map(SmartProxyProfileEntity::secretId))
            }
            Unit
        }
    }

    override suspend fun deleteUnavailableExceptPinned(): Int = mutationMutex.withLock {
        withContext(ioDispatcher) {
            withContext(NonCancellable) {
                val entities = dao.deleteUnavailableExceptPinnedAndSelectFallback()
                deleteSecretsBestEffort(entities.map(SmartProxyProfileEntity::secretId))
                entities.size
            }
        }
    }

    override suspend fun deleteStaleExceptPinned(): Int = mutationMutex.withLock {
        withContext(ioDispatcher) {
            withContext(NonCancellable) {
                val entities = dao.deleteStaleExceptPinnedAndSelectFallback()
                deleteSecretsBestEffort(entities.map(SmartProxyProfileEntity::secretId))
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

    override suspend fun finalizeVerifiedBatch(
        results: List<ProxyTunnelTestResult>,
        selectedId: String,
        workflowIsCurrent: () -> Boolean,
    ): SmartProxyBatchFinalization {
        require(results.any { result ->
            result.profileId == selectedId && result.status == ProxyTestStatus.AVAILABLE
        }) { "Selected Smart Connect profile must be available in the committed batch" }
        return mutationMutex.withLock {
            withContext(ioDispatcher) {
                val mutation = dao.finalizeVerifiedBatch(
                    results = results.map { result ->
                        ProxyTestResultUpdate(
                            profileId = result.profileId,
                            profileFingerprint = result.profileFingerprint,
                            status = result.status,
                            latencyMs = result.latencyMs,
                        )
                    },
                    testedAt = System.currentTimeMillis(),
                    selectedId = selectedId,
                    workflowIsCurrent = workflowIsCurrent,
                )
                withContext(NonCancellable) {
                    deleteSecretsBestEffort(
                        (mutation.unavailable + mutation.stale)
                            .map(SmartProxyProfileEntity::secretId),
                    )
                }
                SmartProxyBatchFinalization(
                    removedUnavailable = mutation.unavailable.size,
                    removedStale = mutation.stale.size,
                )
            }
        }
    }

    /** A row other than [exceptId] that [profile] was saved to under its older fingerprint. */
    private suspend fun legacyRowOf(profile: ParsedProxyProfile, exceptId: String): SmartProxyProfileEntity? {
        val legacyFingerprint = profile.legacyFingerprint ?: return null
        val row = dao.getByFingerprint(legacyFingerprint)?.takeIf { row -> row.id != exceptId } ?: return null
        return legacyFingerprintMatches(
            profiles = listOf(profile),
            rowsByFingerprint = mapOf(legacyFingerprint to row),
            secretIdOf = SmartProxyProfileEntity::secretId,
            secretStorage = secretStorage,
            parser = parser,
        )[profile.fingerprint]
    }

    private suspend fun deleteSecretsBestEffort(ids: Collection<String>) {
        try {
            secretStorage.deleteSecrets(ids)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // The Room row is already gone or points at a new revision; retain an orphan safely.
        }
    }

    private fun emptyImportResult(
        updated: Int = 0,
        duplicates: Int = 0,
        invalid: Int = 0,
        unsupported: Int = 0,
        unsupportedSkipped: Int = 0,
    ) = ProxyImportResult(
        added = 0,
        updated = updated,
        duplicates = duplicates,
        invalid = invalid,
        unsupported = unsupported,
        total = 1,
        unsupportedSkipped = unsupportedSkipped,
    )

    private companion object {
        const val SMART_EXCLUDED_NAME_MARKER = "🇷🇺"
    }
}

private fun ParsedProxyProfile.toSmartEntity(
    id: String,
    source: ProxyProfileSource,
    sourceUrl: String?,
    secretId: String,
    existing: SmartProxyProfileEntity?,
    now: Long,
): SmartProxyProfileEntity {
    return SmartProxyProfileEntity(
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

private fun SmartProxyProfileEntity.toSummary(): ProxyProfileSummary {
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

private suspend fun SmartProxyProfileEntity.toDomain(secretStorage: SecretStorage): ProxyProfile? {
    val rawUri = secretStorage.getSecret(secretId) ?: return null
    return toDomain(rawUri)
}

private fun SmartProxyProfileEntity.toDomain(secretsById: Map<String, String>): ProxyProfile? {
    return secretsById[secretId]?.let(::toDomain)
}

private fun SmartProxyProfileEntity.toDomain(rawUri: String): ProxyProfile {
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
