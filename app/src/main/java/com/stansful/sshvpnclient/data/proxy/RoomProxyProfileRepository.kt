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

    override suspend fun getById(id: String): ProxyProfile? = withContext(ioDispatcher) {
        dao.getById(id)?.toDomain(secretStorage)
    }

    override suspend fun getSelected(): ProxyProfile? = withContext(ioDispatcher) {
        dao.getSelected()?.toDomain(secretStorage)
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

            uniqueProfiles.forEach { parsed ->
                if (parsed.transport == ProxyTransport.UNKNOWN || parsed.security == ProxySecurity.UNKNOWN) {
                    unsupported += 1
                }
                val existing = dao.getByFingerprint(parsed.fingerprint)
                if (existing != null && (source != ProxyProfileSource.REMOTE || existing.source != source.name)) {
                    duplicates += 1
                    return@forEach
                }

                val id = existing?.id ?: UUID.randomUUID().toString()
                val secretId = existing?.secretId ?: SecretIds.proxyProfile(id)
                secretStorage.saveSecret(secretId, parsed.rawUri)
                dao.upsert(
                    parsed.toEntity(
                        id = id,
                        source = source,
                        sourceUrl = sourceUrl,
                        secretId = secretId,
                        existing = existing,
                        now = syncStartedAt,
                    ),
                )
                if (existing == null) added += 1 else updated += 1
            }

            if (source == ProxyProfileSource.REMOTE && sourceUrl != null && uniqueProfiles.isNotEmpty()) {
                dao.markRemoteProfilesStale(sourceUrl, syncStartedAt)
            }
            ensureSelection(syncStartedAt)

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
            secretStorage.saveSecret(existing.secretId, parsed.profile.rawUri)
            dao.upsert(
                parsed.profile.toEntity(
                    id = id,
                    source = ProxyProfileSource.MANUAL,
                    sourceUrl = null,
                    secretId = existing.secretId,
                    existing = existing,
                    now = System.currentTimeMillis(),
                ),
            )
            emptyImportResult(updated = 1)
        }
    }

    override suspend fun select(id: String) = withContext(ioDispatcher) {
        dao.select(id)
    }

    override suspend fun setPinned(id: String, pinned: Boolean) = withContext(ioDispatcher) {
        dao.setPinned(id, pinned)
    }

    override suspend fun delete(ids: Set<String>) = mutationMutex.withLock {
        withContext(ioDispatcher) {
            if (ids.isEmpty()) return@withContext
            val entities = dao.getByIds(ids)
            dao.deleteByIds(ids)
            entities.forEach { entity -> secretStorage.deleteSecret(entity.secretId) }
            ensureSelection(System.currentTimeMillis())
        }
    }

    override suspend fun saveTestResult(result: ProxyTunnelTestResult) = withContext(ioDispatcher) {
        dao.updateTestResult(
            id = result.profileId,
            status = result.status.name,
            latencyMs = result.latencyMs,
            testedAt = System.currentTimeMillis(),
        )
    }

    private suspend fun ensureSelection(now: Long) {
        if (dao.getSelected() == null) {
            dao.getFirstAvailableId()?.let { id -> dao.select(id) }
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
