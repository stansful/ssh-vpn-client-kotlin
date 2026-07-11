package com.stansful.sshvpnclient.data.proxy

import com.stansful.sshvpnclient.data.secret.SecretStorage
import com.stansful.sshvpnclient.domain.model.ProxyProfileSource
import com.stansful.sshvpnclient.domain.model.ProxyProtocol
import com.stansful.sshvpnclient.domain.model.ProxySecurity
import com.stansful.sshvpnclient.domain.model.ProxyTestStatus
import com.stansful.sshvpnclient.domain.model.ProxyTransport
import com.stansful.sshvpnclient.domain.model.ProxyTunnelTestResult
import com.stansful.sshvpnclient.domain.usecase.proxy.ProxyShareLinkParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomProxyProfileRepositoryBatchTest {
    @Test
    fun `bulk read chunks Room queries and loads secrets once while preserving input order`() = runBlocking {
        val entities = (0 until 1_801)
            .map { index -> profileEntity(id = "profile-$index") }
            .associateBy(ProxyProfileEntity::id)
        val dao = RecordingProxyProfileDao(entities)
        val missingSecretId = entities.getValue("profile-17").secretId
        val secretStorage = RecordingSecretStorage(
            entities.values.associate { entity -> entity.secretId to "uri://${entity.id}" } - missingSecretId,
        )
        val repository = RoomProxyProfileRepository(
            dao = dao,
            secretStorage = secretStorage,
            parser = ProxyShareLinkParser(),
            ioDispatcher = Dispatchers.Unconfined,
        )
        val ids = entities.keys.toList() + listOf("profile-0", "missing-profile")

        val profiles = repository.getByIds(ids)

        assertEquals(listOf(900, 900, 2), dao.getByIdsBatches.map(List<String>::size))
        assertTrue(dao.getByIdsBatches.all { batch -> batch.size <= SQLITE_QUERY_BATCH_SIZE })
        assertEquals(0, dao.getByIdCalls)
        assertEquals(1, secretStorage.getSecretsCalls)
        assertEquals(
            ids.filter { id -> id in entities && id != "profile-17" },
            profiles.map { profile -> profile.id },
        )
    }

    @Test
    fun `bulk result save calls one DAO batch and keeps all result values`() = runBlocking {
        val dao = RecordingProxyProfileDao(emptyMap())
        val repository = RoomProxyProfileRepository(
            dao = dao,
            secretStorage = RecordingSecretStorage(emptyMap()),
            parser = ProxyShareLinkParser(),
            ioDispatcher = Dispatchers.Unconfined,
        )
        val results = listOf(
            ProxyTunnelTestResult(
                "available",
                ProxyTestStatus.AVAILABLE,
                latencyMs = 42,
                profileFingerprint = "fingerprint-available",
            ),
            ProxyTunnelTestResult("unavailable", ProxyTestStatus.UNAVAILABLE),
            ProxyTunnelTestResult("unsupported", ProxyTestStatus.UNSUPPORTED, message = "ignored by persistence"),
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
        assertEquals(results.size, dao.individualTestUpdates.size)
        assertEquals(
            setOf(dao.lastTestedAt),
            dao.individualTestUpdates.map(TestUpdateCall::testedAt).toSet(),
        )
    }

    @Test
    fun `bulk delete removes unavailable unpinned in SQLite batches and selects fallback`() = runBlocking {
        val doomed = (0 until 1_801).map { index ->
            profileEntity(
                id = "doomed-$index",
                status = ProxyTestStatus.UNAVAILABLE,
                isSelected = index == 0,
            )
        }
        val pinned = profileEntity(
            id = "pinned-unavailable",
            status = ProxyTestStatus.UNAVAILABLE,
            isPinned = true,
            updatedAt = 3L,
        )
        val notTested = profileEntity(
            id = "not-tested",
            status = ProxyTestStatus.NOT_TESTED,
            updatedAt = 4L,
        )
        val fallback = profileEntity(
            id = "fallback",
            status = ProxyTestStatus.AVAILABLE,
            updatedAt = 10L,
        )
        val entities = (doomed + pinned + notTested + fallback).associateBy(ProxyProfileEntity::id)
        val dao = RecordingProxyProfileDao(entities)
        val secretStorage = RecordingSecretStorage(
            entities.values.associate { entity -> entity.secretId to "uri://${entity.id}" },
        )
        val repository = RoomProxyProfileRepository(
            dao = dao,
            secretStorage = secretStorage,
            parser = ProxyShareLinkParser(),
            ioDispatcher = Dispatchers.Unconfined,
        )

        val deleted = repository.deleteUnavailableExceptPinned()

        assertEquals(doomed.size, deleted)
        assertEquals(listOf(900, 900, 1), dao.deleteByIdsBatches.map(List<String>::size))
        assertTrue(dao.deleteByIdsBatches.all { batch -> batch.size <= SQLITE_QUERY_BATCH_SIZE })
        assertEquals(
            doomed.mapTo(hashSetOf(), ProxyProfileEntity::secretId),
            secretStorage.deletedSecretIds,
        )
        assertEquals(setOf(pinned.id, notTested.id, fallback.id), dao.remainingIds())
        assertTrue(dao.entity(pinned.id)?.isPinned == true)
        assertTrue(dao.entity(fallback.id)?.isSelected == true)
    }
}

private class RecordingSecretStorage(
    private val secrets: Map<String, String>,
) : SecretStorage {
    var getSecretsCalls = 0
        private set
    val deletedSecretIds = hashSetOf<String>()

    override suspend fun saveSecret(id: String, value: String) = Unit

    override suspend fun getSecret(id: String): String? = secrets[id]

    override suspend fun getSecrets(ids: Collection<String>): Map<String, String> {
        getSecretsCalls += 1
        return ids.mapNotNull { id -> secrets[id]?.let { value -> id to value } }.toMap()
    }

    override suspend fun deleteSecret(id: String) {
        deletedSecretIds += id
    }
}

private class RecordingProxyProfileDao(
    initialEntities: Map<String, ProxyProfileEntity>,
) : ProxyProfileDao() {
    private val entities = initialEntities.toMutableMap()
    val getByIdsBatches = mutableListOf<List<String>>()
    val deleteByIdsBatches = mutableListOf<List<String>>()
    var getByIdCalls = 0
        private set
    var updateTestResultsCalls = 0
        private set
    var lastTestResults = emptyList<ProxyTestResultUpdate>()
        private set
    var lastTestedAt = 0L
        private set
    val individualTestUpdates = mutableListOf<TestUpdateCall>()

    override fun observeAll(): Flow<List<ProxyProfileEntity>> = flowOf(entities.values.toList())

    override suspend fun getById(id: String): ProxyProfileEntity? {
        getByIdCalls += 1
        return entities[id]
    }

    override suspend fun getByFingerprint(fingerprint: String): ProxyProfileEntity? = null

    override suspend fun getByFingerprints(fingerprints: List<String>): List<ProxyProfileEntity> = emptyList()

    override suspend fun getSelected(): ProxyProfileEntity? {
        return entities.values.firstOrNull { entity -> entity.isSelected && !entity.isStale }
    }

    override suspend fun getFirstAvailableId(): String? {
        return entities.values
            .filterNot(ProxyProfileEntity::isStale)
            .maxByOrNull(ProxyProfileEntity::updatedAt)
            ?.id
    }

    override suspend fun upsert(entity: ProxyProfileEntity) = Unit

    override suspend fun upsertAll(entities: List<ProxyProfileEntity>) = Unit

    override suspend fun deleteByIds(ids: List<String>) {
        deleteByIdsBatches += ids
        ids.forEach(entities::remove)
    }

    override suspend fun getByIds(ids: List<String>): List<ProxyProfileEntity> {
        getByIdsBatches += ids
        return ids.mapNotNull(entities::get)
    }

    override suspend fun getUnavailableUnpinned(): List<ProxyProfileEntity> {
        return entities.values.filter { entity ->
            entity.lastTestStatus == ProxyTestStatus.UNAVAILABLE.name && !entity.isPinned
        }
    }

    override suspend fun clearSelection() {
        entities.entries.forEach { entry ->
            entry.setValue(entry.value.copy(isSelected = false))
        }
    }

    override suspend fun markSelected(id: String) {
        entities[id]?.let { entity -> entities[id] = entity.copy(isSelected = true) }
    }

    override suspend fun setPinned(id: String, isPinned: Boolean) = Unit

    override suspend fun markRemoteProfilesStale(sourceUrl: String, syncStartedAt: Long) = Unit

    override suspend fun updateTestResult(
        id: String,
        fingerprint: String?,
        status: String,
        latencyMs: Long?,
        testedAt: Long,
    ) {
        individualTestUpdates += TestUpdateCall(id, fingerprint, status, latencyMs, testedAt)
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

    fun entity(id: String): ProxyProfileEntity? = entities[id]
}

private data class TestUpdateCall(
    val id: String,
    val fingerprint: String?,
    val status: String,
    val latencyMs: Long?,
    val testedAt: Long,
)

private fun profileEntity(
    id: String,
    status: ProxyTestStatus = ProxyTestStatus.NOT_TESTED,
    isPinned: Boolean = false,
    isSelected: Boolean = false,
    updatedAt: Long = 1L,
): ProxyProfileEntity {
    return ProxyProfileEntity(
        id = id,
        name = id,
        protocol = ProxyProtocol.VLESS.name,
        host = "127.0.0.1",
        port = 443,
        transport = ProxyTransport.RAW.name,
        security = ProxySecurity.TLS.name,
        flow = null,
        source = ProxyProfileSource.REMOTE.name,
        sourceUrl = "https://example.test/proxies",
        secretId = "secret-$id",
        fingerprint = "fingerprint-$id",
        isSelected = isSelected,
        isPinned = isPinned,
        isStale = false,
        lastTestStatus = status.name,
        lastLatencyMs = null,
        lastTestAt = null,
        createdAt = 1L,
        updatedAt = updatedAt,
        lastSeenAt = 1L,
    )
}
