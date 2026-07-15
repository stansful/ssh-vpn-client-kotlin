package com.stansful.sshvpnclient.data.smart

import com.stansful.sshvpnclient.data.proxy.ProxyTestResultUpdate
import com.stansful.sshvpnclient.data.proxy.SQLITE_QUERY_BATCH_SIZE
import com.stansful.sshvpnclient.data.secret.SecretStorage
import com.stansful.sshvpnclient.domain.model.ProxyProfileSource
import com.stansful.sshvpnclient.domain.model.ProxyProtocol
import com.stansful.sshvpnclient.domain.model.ProxySecurity
import com.stansful.sshvpnclient.domain.model.ProxyTestStatus
import com.stansful.sshvpnclient.domain.model.ProxyTransport
import com.stansful.sshvpnclient.domain.model.ProxyTunnelTestResult
import com.stansful.sshvpnclient.domain.repository.SmartProxyBatchSupersededException
import com.stansful.sshvpnclient.domain.usecase.proxy.ProxyShareLinkParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomSmartProxyProfileRepositoryBatchTest {
    @Test
    fun `bulk read chunks Room queries and loads smart secrets once in input order`() = runBlocking {
        val entities = (0 until 1_801)
            .map { index -> smartProfileEntity(id = "profile-$index") }
            .associateBy(SmartProxyProfileEntity::id)
        val dao = RecordingSmartProxyProfileDao(entities)
        val missingSecretId = entities.getValue("profile-17").secretId
        val secretStorage = RecordingSmartSecretStorage(
            entities.values.associate { entity -> entity.secretId to "uri://${entity.id}" } - missingSecretId,
        )
        val repository = repository(dao, secretStorage)
        val ids = entities.keys.toList() + listOf("profile-0", "missing-profile")

        val profiles = repository.getByIds(ids)

        assertEquals(listOf(900, 900, 2), dao.getByIdsBatches.map(List<String>::size))
        assertTrue(dao.getByIdsBatches.all { batch -> batch.size <= SQLITE_QUERY_BATCH_SIZE })
        assertEquals(1, secretStorage.getSecretsCalls)
        assertEquals(
            ids.filter { id -> id in entities && id != "profile-17" },
            profiles.map { profile -> profile.id },
        )
    }

    @Test
    fun `bulk result save uses one smart DAO transaction and keeps fingerprint guards`() = runBlocking {
        val dao = RecordingSmartProxyProfileDao(emptyMap())
        val repository = repository(dao, RecordingSmartSecretStorage(emptyMap()))
        val results = listOf(
            ProxyTunnelTestResult(
                profileId = "available",
                status = ProxyTestStatus.AVAILABLE,
                latencyMs = 42,
                profileFingerprint = "fingerprint-available",
            ),
            ProxyTunnelTestResult("unavailable", ProxyTestStatus.UNAVAILABLE),
            ProxyTunnelTestResult("unsupported", ProxyTestStatus.UNSUPPORTED),
        )

        repository.saveTestResults(results)
        repository.saveTestResults(emptyList())

        assertEquals(1, dao.updateTestResultsCalls)
        assertEquals(
            results.map { result ->
                ProxyTestResultUpdate(
                    profileId = result.profileId,
                    profileFingerprint = result.profileFingerprint,
                    status = result.status,
                    latencyMs = result.latencyMs,
                )
            },
            dao.lastTestResults,
        )
        assertTrue(dao.lastTestedAt > 0L)
    }

    @Test
    fun `verified batch finalization updates prunes and selects in one guarded DAO call`() = runBlocking {
        val winner = smartProfileEntity(id = "winner")
        val failed = smartProfileEntity(id = "failed")
        val stale = smartProfileEntity(id = "stale", isStale = true)
        val pinnedFailed = smartProfileEntity(id = "pinned", isPinned = true)
        val entities = listOf(winner, failed, stale, pinnedFailed)
            .associateBy(SmartProxyProfileEntity::id)
        val secrets = RecordingSmartSecretStorage(
            entities.values.associate { entity -> entity.secretId to "uri://${entity.id}" },
        )
        val dao = RecordingSmartProxyProfileDao(entities)
        val repository = repository(dao, secrets)

        val finalization = repository.finalizeVerifiedBatch(
            results = listOf(
                ProxyTunnelTestResult(
                    profileId = winner.id,
                    profileFingerprint = winner.fingerprint,
                    status = ProxyTestStatus.AVAILABLE,
                    latencyMs = 15L,
                ),
                ProxyTunnelTestResult(
                    profileId = failed.id,
                    profileFingerprint = failed.fingerprint,
                    status = ProxyTestStatus.UNAVAILABLE,
                ),
                ProxyTunnelTestResult(
                    profileId = pinnedFailed.id,
                    profileFingerprint = pinnedFailed.fingerprint,
                    status = ProxyTestStatus.UNAVAILABLE,
                ),
            ),
            selectedId = winner.id,
            workflowIsCurrent = { true },
        )

        assertEquals(1, finalization.removedUnavailable)
        assertEquals(1, finalization.removedStale)
        assertEquals(setOf(winner.id, pinnedFailed.id), dao.remainingIds())
        assertTrue(dao.entity(winner.id)?.isSelected == true)
        assertTrue(dao.entity(pinnedFailed.id)?.isPinned == true)
        assertEquals(
            setOf(failed.secretId, stale.secretId),
            secrets.deletedSecretIds,
        )
    }

    @Test(expected = SmartProxyBatchSupersededException::class)
    fun `verified batch finalization rejects a stale workflow before mutation`() {
        runBlocking {
            val winner = smartProfileEntity(id = "winner")
            val dao = RecordingSmartProxyProfileDao(mapOf(winner.id to winner))
            val repository = repository(
                dao,
                RecordingSmartSecretStorage(mapOf(winner.secretId to "uri://${winner.id}")),
            )

            repository.finalizeVerifiedBatch(
                results = listOf(
                    ProxyTunnelTestResult(
                        profileId = winner.id,
                        profileFingerprint = winner.fingerprint,
                        status = ProxyTestStatus.AVAILABLE,
                        latencyMs = 15L,
                    ),
                ),
                selectedId = winner.id,
                workflowIsCurrent = { false },
            )
        }
    }

    @Test
    fun `bulk delete is isolated batched and selects lowest-latency available fallback`() = runBlocking {
        val doomed = (0 until 1_801).map { index ->
            smartProfileEntity(
                id = "doomed-$index",
                status = ProxyTestStatus.UNAVAILABLE,
                isSelected = index == 0,
            )
        }
        val pinned = smartProfileEntity(
            id = "pinned-unavailable",
            status = ProxyTestStatus.UNAVAILABLE,
            isPinned = true,
        )
        val notTested = smartProfileEntity(
            id = "not-tested",
            status = ProxyTestStatus.NOT_TESTED,
        )
        val slower = smartProfileEntity(
            id = "slower",
            status = ProxyTestStatus.AVAILABLE,
            latencyMs = 90L,
        )
        val best = smartProfileEntity(
            id = "best",
            status = ProxyTestStatus.AVAILABLE,
            latencyMs = 12L,
        )
        val entities = (doomed + pinned + notTested + slower + best)
            .associateBy(SmartProxyProfileEntity::id)
        val dao = RecordingSmartProxyProfileDao(entities)
        val secretStorage = RecordingSmartSecretStorage(
            entities.values.associate { entity -> entity.secretId to "uri://${entity.id}" },
        )
        val repository = repository(dao, secretStorage)

        val deleted = repository.deleteUnavailableExceptPinned()

        assertEquals(doomed.size, deleted)
        assertEquals(listOf(900, 900, 1), dao.deleteByIdsBatches.map(List<String>::size))
        assertEquals(
            doomed.mapTo(hashSetOf(), SmartProxyProfileEntity::secretId),
            secretStorage.deletedSecretIds,
        )
        assertEquals(setOf(pinned.id, notTested.id, slower.id, best.id), dao.remainingIds())
        assertTrue(dao.entity(pinned.id)?.isPinned == true)
        assertTrue(dao.entity(best.id)?.isSelected == true)
        assertTrue(dao.entity(notTested.id)?.isSelected == false)
    }

    @Test
    fun `stale cleanup preserves pinned profiles and atomically selects fresh fallback`() = runBlocking {
        val stale = (0 until 901).map { index ->
            smartProfileEntity(
                id = "stale-$index",
                isSelected = index == 0,
                isStale = true,
            )
        }
        val pinnedStale = smartProfileEntity(
            id = "pinned-stale",
            isPinned = true,
            isStale = true,
        )
        val fallback = smartProfileEntity(
            id = "fallback",
            status = ProxyTestStatus.AVAILABLE,
            latencyMs = 15L,
        )
        val entities = (stale + pinnedStale + fallback).associateBy(SmartProxyProfileEntity::id)
        val dao = RecordingSmartProxyProfileDao(entities)
        val secrets = RecordingSmartSecretStorage(
            entities.values.associate { entity -> entity.secretId to "uri://${entity.id}" },
        )
        val repository = repository(dao, secrets)

        val deleted = repository.deleteStaleExceptPinned()

        assertEquals(stale.size, deleted)
        assertEquals(listOf(900, 1), dao.deleteByIdsBatches.map(List<String>::size))
        assertEquals(
            stale.mapTo(hashSetOf(), SmartProxyProfileEntity::secretId),
            secrets.deletedSecretIds,
        )
        assertEquals(setOf(pinnedStale.id, fallback.id), dao.remainingIds())
        assertTrue(dao.entity(pinnedStale.id)?.isPinned == true)
        assertTrue(dao.entity(fallback.id)?.isSelected == true)
    }

    @Test
    fun `best selection skips excluded unavailable stale and missing-latency profiles`() = runBlocking {
        val best = smartProfileEntity(
            id = "best",
            status = ProxyTestStatus.AVAILABLE,
            latencyMs = 10L,
        )
        val second = smartProfileEntity(
            id = "second",
            status = ProxyTestStatus.AVAILABLE,
            latencyMs = 20L,
        )
        val stale = smartProfileEntity(
            id = "stale",
            status = ProxyTestStatus.AVAILABLE,
            latencyMs = 1L,
            isStale = true,
        )
        val missingLatency = smartProfileEntity(
            id = "missing-latency",
            status = ProxyTestStatus.AVAILABLE,
            latencyMs = null,
        )
        val entities = listOf(best, second, stale, missingLatency)
            .associateBy(SmartProxyProfileEntity::id)
        val secrets = entities.values.associate { entity -> entity.secretId to "uri://${entity.id}" }
        val dao = RecordingSmartProxyProfileDao(entities)
        val repository = repository(dao, RecordingSmartSecretStorage(secrets))

        val selected = repository.selectBestAvailable(excludedIds = setOf(best.id))
        val exhausted = repository.selectBestAvailable(excludedIds = setOf(best.id, second.id))

        assertEquals(second.id, selected?.id)
        assertNull(exhausted)
        assertTrue(dao.remainingEntities().none(SmartProxyProfileEntity::isSelected))
    }

    @Test
    fun `smart import writes only smart-prefixed secret ids`() = runBlocking {
        val dao = RecordingSmartProxyProfileDao(emptyMap())
        val secrets = RecordingSmartSecretStorage(emptyMap())
        val repository = repository(dao, secrets)

        val result = repository.import(
            text = "vless://00000000-0000-4000-8000-000000000001@example.test:443?security=tls#Smart",
            source = ProxyProfileSource.REMOTE,
            sourceUrl = "https://example.test/smart",
        )

        assertEquals(1, result.added)
        assertEquals(1, secrets.savedSecrets.size)
        assertTrue(secrets.savedSecrets.keys.single().startsWith("smart-proxy-profile-"))
        assertTrue(dao.remainingEntities().single().secretId.startsWith("smart-proxy-profile-"))
    }

    @Test
    fun `smart import excludes Russian-flag names before secret save and upsert`() = runBlocking {
        val dao = RecordingSmartProxyProfileDao(emptyMap())
        val secrets = RecordingSmartSecretStorage(emptyMap())
        val repository = repository(dao, secrets)

        val result = repository.import(
            text = listOf(
                "vless://00000000-0000-4000-8000-000000000001@allowed.test:443?security=tls#Allowed",
                "vless://00000000-0000-4000-8000-000000000002@excluded.test:443?security=tls#" +
                    "Fast%20%F0%9F%87%B7%F0%9F%87%BA%20RU",
            ).joinToString("\n"),
            source = ProxyProfileSource.REMOTE,
            sourceUrl = "https://example.test/smart",
        )

        assertEquals(1, result.added)
        assertEquals(1, result.unsupported)
        assertEquals(1, dao.remainingEntities().size)
        assertEquals("Allowed", dao.remainingEntities().single().name)
        assertEquals(1, secrets.savedSecrets.size)
        assertTrue(secrets.savedSecrets.values.none { rawUri -> rawUri.contains("excluded.test") })
    }

    @Test
    fun `smart import removes an existing fingerprint renamed with Russian flag`() = runBlocking {
        val dao = RecordingSmartProxyProfileDao(emptyMap())
        val secrets = RecordingSmartSecretStorage(emptyMap())
        val repository = repository(dao, secrets)
        val uriPrefix =
            "vless://00000000-0000-4000-8000-000000000003@renamed.test:443?security=tls#"
        repository.import(
            text = "${uriPrefix}Initially%20allowed",
            source = ProxyProfileSource.REMOTE,
            sourceUrl = "https://example.test/smart",
        )
        val originalSecretId = dao.remainingEntities().single().secretId

        repository.import(
            text = uriPrefix + "Now%20%F0%9F%87%B7%F0%9F%87%BA",
            source = ProxyProfileSource.REMOTE,
            sourceUrl = "https://example.test/smart",
        )

        assertTrue(dao.remainingEntities().isEmpty())
        assertTrue(originalSecretId in secrets.deletedSecretIds)
    }

    @Test
    fun `smart update rejects Russian-flag name before secret save or Room mutation`() = runBlocking {
        val existing = smartProfileEntity(id = "existing")
        val dao = RecordingSmartProxyProfileDao(mapOf(existing.id to existing))
        val secrets = RecordingSmartSecretStorage(
            mapOf(existing.secretId to "uri://${existing.id}"),
        )
        val repository = repository(dao, secrets)

        val result = repository.update(
            id = existing.id,
            rawUri = "vless://00000000-0000-4000-8000-000000000004@updated.test:443" +
                "?security=tls#Blocked%20%F0%9F%87%B7%F0%9F%87%BA",
        )

        assertEquals(1, result.unsupported)
        assertEquals(existing, dao.entity(existing.id))
        assertTrue(secrets.savedSecrets.isEmpty())
        assertTrue(secrets.deletedSecretIds.isEmpty())
    }

    private fun repository(
        dao: SmartProxyProfileDao,
        secretStorage: SecretStorage,
    ) = RoomSmartProxyProfileRepository(
        dao = dao,
        secretStorage = secretStorage,
        parser = ProxyShareLinkParser(),
        ioDispatcher = Dispatchers.Unconfined,
    )
}

private class RecordingSmartSecretStorage(
    initialSecrets: Map<String, String>,
) : SecretStorage {
    private val secrets = initialSecrets.toMutableMap()
    var getSecretsCalls = 0
        private set
    val savedSecrets = linkedMapOf<String, String>()
    val deletedSecretIds = hashSetOf<String>()

    override suspend fun saveSecret(id: String, value: String) {
        secrets[id] = value
        savedSecrets[id] = value
    }

    override suspend fun getSecret(id: String): String? = secrets[id]

    override suspend fun getSecrets(ids: Collection<String>): Map<String, String> {
        getSecretsCalls += 1
        return ids.mapNotNull { id -> secrets[id]?.let { value -> id to value } }.toMap()
    }

    override suspend fun deleteSecret(id: String) {
        secrets.remove(id)
        deletedSecretIds += id
    }
}

private class RecordingSmartProxyProfileDao(
    initialEntities: Map<String, SmartProxyProfileEntity>,
) : SmartProxyProfileDao() {
    private val entities = initialEntities.toMutableMap()
    val getByIdsBatches = mutableListOf<List<String>>()
    val deleteByIdsBatches = mutableListOf<List<String>>()
    var updateTestResultsCalls = 0
        private set
    var lastTestResults = emptyList<ProxyTestResultUpdate>()
        private set
    var lastTestedAt = 0L
        private set

    override fun observeAll(): Flow<List<SmartProxyProfileEntity>> = flowOf(entities.values.toList())

    override suspend fun getById(id: String): SmartProxyProfileEntity? = entities[id]

    override suspend fun getByFingerprint(fingerprint: String): SmartProxyProfileEntity? {
        return entities.values.firstOrNull { entity -> entity.fingerprint == fingerprint }
    }

    override suspend fun getByFingerprints(fingerprints: List<String>): List<SmartProxyProfileEntity> {
        return entities.values.filter { entity -> entity.fingerprint in fingerprints }
    }

    override suspend fun getSelected(): SmartProxyProfileEntity? {
        return entities.values.firstOrNull { entity -> entity.isSelected && !entity.isStale }
    }

    override suspend fun getAvailableCandidates(): List<SmartProxyProfileEntity> {
        return entities.values
            .filter { entity ->
                !entity.isStale &&
                    entity.lastTestStatus == ProxyTestStatus.AVAILABLE.name &&
                    entity.lastLatencyMs != null
            }
            .sortedWith(
                compareBy<SmartProxyProfileEntity> { entity -> entity.lastLatencyMs }
                    .thenByDescending { entity -> entity.lastTestAt }
                    .thenByDescending { entity -> entity.updatedAt },
            )
    }

    override suspend fun upsert(entity: SmartProxyProfileEntity) {
        entities[entity.id] = entity
    }

    override suspend fun upsertAll(entities: List<SmartProxyProfileEntity>) {
        entities.forEach { entity -> this.entities[entity.id] = entity }
    }

    override suspend fun deleteByIds(ids: List<String>) {
        deleteByIdsBatches += ids
        ids.forEach(entities::remove)
    }

    override suspend fun getByIds(ids: List<String>): List<SmartProxyProfileEntity> {
        getByIdsBatches += ids
        return ids.mapNotNull(entities::get)
    }

    override suspend fun getUnavailableUnpinned(): List<SmartProxyProfileEntity> {
        return entities.values.filter { entity ->
            entity.lastTestStatus == ProxyTestStatus.UNAVAILABLE.name && !entity.isPinned
        }
    }

    override suspend fun getStaleUnpinned(): List<SmartProxyProfileEntity> {
        return entities.values.filter { entity -> entity.isStale && !entity.isPinned }
    }

    override suspend fun clearSelection() {
        entities.entries.forEach { entry ->
            entry.setValue(entry.value.copy(isSelected = false))
        }
    }

    override suspend fun markSelected(id: String) {
        entities[id]?.let { entity -> entities[id] = entity.copy(isSelected = true) }
    }

    override suspend fun setPinned(id: String, isPinned: Boolean) {
        entities[id]?.let { entity -> entities[id] = entity.copy(isPinned = isPinned) }
    }

    override suspend fun markRemoteProfilesStale(sourceUrl: String, syncStartedAt: Long) {
        entities.entries.forEach { entry ->
            val entity = entry.value
            if (entity.source == ProxyProfileSource.REMOTE.name &&
                entity.sourceUrl == sourceUrl &&
                entity.lastSeenAt < syncStartedAt
            ) {
                entry.setValue(entity.copy(isStale = true))
            }
        }
    }

    override suspend fun updateTestResult(
        id: String,
        fingerprint: String?,
        status: String,
        latencyMs: Long?,
        testedAt: Long,
    ) {
        entities[id]?.takeIf { entity -> fingerprint == null || entity.fingerprint == fingerprint }
            ?.let { entity ->
                entities[id] = entity.copy(
                    lastTestStatus = status,
                    lastLatencyMs = latencyMs,
                    lastTestAt = testedAt,
                )
            }
    }

    override suspend fun updateTestResults(
        results: List<ProxyTestResultUpdate>,
        testedAt: Long,
    ) {
        updateTestResultsCalls += 1
        lastTestResults = results
        lastTestedAt = testedAt
        super.updateTestResults(results, testedAt)
    }

    fun remainingIds(): Set<String> = entities.keys

    fun remainingEntities(): Collection<SmartProxyProfileEntity> = entities.values

    fun entity(id: String): SmartProxyProfileEntity? = entities[id]
}

private fun smartProfileEntity(
    id: String,
    status: ProxyTestStatus = ProxyTestStatus.NOT_TESTED,
    latencyMs: Long? = null,
    isPinned: Boolean = false,
    isSelected: Boolean = false,
    isStale: Boolean = false,
    updatedAt: Long = 1L,
): SmartProxyProfileEntity {
    return SmartProxyProfileEntity(
        id = id,
        name = id,
        protocol = ProxyProtocol.VLESS.name,
        host = "127.0.0.1",
        port = 443,
        transport = ProxyTransport.RAW.name,
        security = ProxySecurity.TLS.name,
        flow = null,
        source = ProxyProfileSource.REMOTE.name,
        sourceUrl = "https://example.test/smart",
        secretId = "smart-secret-$id",
        fingerprint = "smart-fingerprint-$id",
        isSelected = isSelected,
        isPinned = isPinned,
        isStale = isStale,
        lastTestStatus = status.name,
        lastLatencyMs = latencyMs,
        lastTestAt = 1L,
        createdAt = 1L,
        updatedAt = updatedAt,
        lastSeenAt = 1L,
    )
}
