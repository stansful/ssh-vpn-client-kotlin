package com.stansful.sshvpnclient.data.proxy

import com.stansful.sshvpnclient.data.secret.SecretStorage
import com.stansful.sshvpnclient.domain.model.ProxyProfileSource
import com.stansful.sshvpnclient.domain.model.ProxyProtocol
import com.stansful.sshvpnclient.domain.model.ProxySecurity
import com.stansful.sshvpnclient.domain.model.ProxyTestStatus
import com.stansful.sshvpnclient.domain.model.ProxyTransport
import com.stansful.sshvpnclient.domain.model.ProxyTunnelTestResult
import com.stansful.sshvpnclient.domain.usecase.proxy.ProxyParseResult
import com.stansful.sshvpnclient.domain.usecase.proxy.ProxyShareLinkParser
import java.security.GeneralSecurityException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `hysteria 2 import persists the protocol with QUIC transport and TLS`() = runBlocking {
        val dao = RecordingProxyProfileDao(emptyMap())
        val secretStorage = RecordingSecretStorage(emptyMap())
        val repository = repository(dao, secretStorage)

        val result = repository.import(text = HY2_LINK, source = ProxyProfileSource.MANUAL, sourceUrl = null)

        assertEquals(1, result.added)
        assertEquals(0, result.unsupported)
        assertEquals(0, result.unsupportedSkipped)
        val entity = dao.remainingEntities().single()
        assertEquals(ProxyProtocol.HYSTERIA2.name, entity.protocol)
        assertEquals(ProxyTransport.HYSTERIA.name, entity.transport)
        assertEquals(ProxySecurity.TLS.name, entity.security)
        val profile = repository.getById(entity.id)
        assertEquals(ProxyProtocol.HYSTERIA2, profile?.protocol)
        assertEquals(ProxyTransport.HYSTERIA, profile?.transport)
        assertEquals(ProxySecurity.TLS, profile?.security)
        assertEquals(HY2_LINK, profile?.rawUri)
    }

    @Test
    fun `hy2 and hysteria2 links of one server import once`() = runBlocking {
        val dao = RecordingProxyProfileDao(emptyMap())
        val secretStorage = RecordingSecretStorage(emptyMap())
        val repository = repository(dao, secretStorage)

        val result = repository.import(
            text = listOf(HY2_LINK, HY2_ALIAS_LINK).joinToString("\n"),
            source = ProxyProfileSource.MANUAL,
            sourceUrl = null,
        )

        assertEquals(1, result.added)
        assertEquals(1, result.duplicates)
        assertEquals(0, result.invalid)
        assertEquals(1, dao.remainingEntities().size)
        assertEquals(1, secretStorage.savedSecrets.size)
    }

    @Test
    fun `hysteria v1 link is counted as unsupported and never saved`() = runBlocking {
        val dao = RecordingProxyProfileDao(emptyMap())
        val secretStorage = RecordingSecretStorage(emptyMap())
        val repository = repository(dao, secretStorage)

        val result = repository.import(text = HYSTERIA_V1_LINK, source = ProxyProfileSource.REMOTE, sourceUrl = null)

        assertEquals(1, result.unsupported)
        assertEquals(1, result.unsupportedSkipped)
        assertEquals(0, result.invalid)
        assertEquals(0, result.added)
        assertEquals(1, result.total)
        assertTrue(dao.remainingEntities().isEmpty())
        assertTrue(secretStorage.savedSecrets.isEmpty())
    }

    @Test
    fun `unknown hysteria 2 obfuscation is unsupported while a malformed line stays invalid`() = runBlocking {
        val dao = RecordingProxyProfileDao(emptyMap())
        val secretStorage = RecordingSecretStorage(emptyMap())
        val repository = repository(dao, secretStorage)

        val result = repository.import(
            text = listOf(HY2_UNKNOWN_OBFS_LINK, "not a share link").joinToString("\n"),
            source = ProxyProfileSource.MANUAL,
            sourceUrl = null,
        )

        assertEquals(1, result.unsupported)
        assertEquals(1, result.unsupportedSkipped)
        assertEquals(1, result.invalid)
        assertEquals(2, result.total)
        assertTrue(dao.remainingEntities().isEmpty())
        assertTrue(secretStorage.savedSecrets.isEmpty())
    }

    @Test
    fun `update with a hysteria v1 link reports unsupported and keeps the profile`() = runBlocking {
        val existing = profileEntity(id = "existing")
        val dao = RecordingProxyProfileDao(mapOf(existing.id to existing))
        val secretStorage = RecordingSecretStorage(mapOf(existing.secretId to "uri://${existing.id}"))
        val repository = repository(dao, secretStorage)

        val result = repository.update(id = existing.id, rawUri = HYSTERIA_V1_LINK)

        assertEquals(1, result.unsupported)
        assertEquals(1, result.unsupportedSkipped)
        assertEquals(0, result.invalid)
        assertEquals(0, result.updated)
        assertEquals(existing, dao.entity(existing.id))
        assertTrue(secretStorage.savedSecrets.isEmpty())
        assertTrue(secretStorage.deletedSecretIds.isEmpty())
    }

    @Test
    fun `a saved route with an unknown transport is unsupported but not skipped`() = runBlocking {
        val dao = RecordingProxyProfileDao(emptyMap())
        val secretStorage = RecordingSecretStorage(emptyMap())
        val repository = repository(dao, secretStorage)

        val first = repository.import(UNKNOWN_TRANSPORT_LINK, ProxyProfileSource.MANUAL, sourceUrl = null)
        val again = repository.import(UNKNOWN_TRANSPORT_LINK, ProxyProfileSource.MANUAL, sourceUrl = null)

        assertEquals(1, first.added)
        assertEquals(1, first.unsupported)
        assertEquals(0, first.unsupportedSkipped)
        // The repeated line is only a duplicate: nothing was left out because the engine can't run it.
        assertEquals(1, again.duplicates)
        assertEquals(0, again.unsupportedSkipped)
        assertEquals(1, dao.remainingEntities().size)
    }

    @Test
    fun `update with a hysteria 2 link the engine can't run is skipped as unsupported`() = runBlocking {
        val existing = profileEntity(id = "existing")
        val dao = RecordingProxyProfileDao(mapOf(existing.id to existing))
        val secretStorage = RecordingSecretStorage(mapOf(existing.secretId to "uri://${existing.id}"))
        val repository = repository(dao, secretStorage)

        val unsupported = repository.update(id = existing.id, rawUri = HY2_UNKNOWN_OBFS_LINK)
        val malformed = repository.update(id = existing.id, rawUri = "hy2://correct-horse@")

        assertEquals(1, unsupported.unsupportedSkipped)
        assertEquals(0, malformed.unsupportedSkipped)
        assertEquals(1, malformed.invalid)
        assertEquals(existing, dao.entity(existing.id))
    }

    @Test
    fun `a remote sync gives a route whose secret store started over its link back`() = runBlocking {
        val dao = RecordingProxyProfileDao(emptyMap())
        val secretStorage = RecordingSecretStorage(emptyMap())
        val repository = repository(dao, secretStorage)
        repository.import(text = HY2_LINK, source = ProxyProfileSource.REMOTE, sourceUrl = SOURCE_URL)
        val original = dao.remainingEntities().single()
        secretStorage.startOver()
        assertNull(repository.getById(original.id))

        val result = repository.import(text = HY2_LINK, source = ProxyProfileSource.REMOTE, sourceUrl = SOURCE_URL)

        assertEquals(1, result.updated)
        val healed = dao.remainingEntities().single()
        assertEquals(original.id, healed.id)
        assertNotEquals(original.secretId, healed.secretId)
        assertEquals(HY2_LINK, repository.getById(original.id)?.rawUri)
        assertEquals(listOf(original.id), repository.getByIds(listOf(original.id)).map { profile -> profile.id })
        assertTrue(original.secretId in secretStorage.deletedSecretIds)
    }

    @Test
    fun `a remote sync keeps the stored link of a route that can still read it`() = runBlocking {
        val dao = RecordingProxyProfileDao(emptyMap())
        val secretStorage = RecordingSecretStorage(emptyMap())
        val repository = repository(dao, secretStorage)
        repository.import(text = HY2_LINK, source = ProxyProfileSource.REMOTE, sourceUrl = SOURCE_URL)
        val original = dao.remainingEntities().single()

        val result = repository.import(text = HY2_LINK, source = ProxyProfileSource.REMOTE, sourceUrl = SOURCE_URL)

        assertEquals(1, result.updated)
        assertEquals(original.secretId, dao.remainingEntities().single().secretId)
        assertEquals(1, secretStorage.savedSecrets.size)
        assertTrue(secretStorage.deletedSecretIds.isEmpty())
    }

    @Test
    fun `importing a route that lost its link again restores it instead of counting a duplicate`() = runBlocking {
        val dao = RecordingProxyProfileDao(emptyMap())
        val secretStorage = RecordingSecretStorage(emptyMap())
        val repository = repository(dao, secretStorage)
        repository.import(text = HY2_LINK, source = ProxyProfileSource.MANUAL, sourceUrl = null)
        val original = dao.remainingEntities().single()
        secretStorage.startOver()

        val result = repository.import(text = HY2_LINK, source = ProxyProfileSource.MANUAL, sourceUrl = null)
        val again = repository.import(text = HY2_LINK, source = ProxyProfileSource.MANUAL, sourceUrl = null)

        assertEquals(0, result.duplicates)
        assertEquals(1, result.updated)
        assertEquals(original.copy(secretId = dao.remainingEntities().single().secretId), dao.entity(original.id))
        assertEquals(HY2_LINK, repository.getById(original.id)?.rawUri)
        // Once it reads again, the same paste is an ordinary duplicate.
        assertEquals(1, again.duplicates)
        assertEquals(0, again.updated)
    }

    @Test
    fun `pasting the link of a synced route that lost it restores the route and leaves it synced`() = runBlocking {
        val dao = RecordingProxyProfileDao(emptyMap())
        val secretStorage = RecordingSecretStorage(emptyMap())
        val repository = repository(dao, secretStorage)
        repository.import(text = HY2_LINK, source = ProxyProfileSource.REMOTE, sourceUrl = SOURCE_URL)
        val original = dao.remainingEntities().single()
        secretStorage.startOver()

        val result = repository.import(text = HY2_LINK, source = ProxyProfileSource.MANUAL, sourceUrl = null)

        assertEquals(1, result.updated)
        val healed = dao.remainingEntities().single()
        assertEquals(ProxyProfileSource.REMOTE.name, healed.source)
        assertEquals(SOURCE_URL, healed.sourceUrl)
        assertEquals(HY2_LINK, repository.getById(original.id)?.rawUri)
    }

    @Test
    fun `an import while the secret store can't be read fails and keeps the routes' own links`() = runBlocking {
        val dao = RecordingProxyProfileDao(emptyMap())
        val secretStorage = RecordingSecretStorage(emptyMap())
        val repository = repository(dao, secretStorage)
        repository.import(text = OWN_LINK, source = ProxyProfileSource.MANUAL, sourceUrl = null)
        repository.import(text = HY2_LINK, source = ProxyProfileSource.REMOTE, sourceUrl = SOURCE_URL)
        val before = dao.remainingEntities().toSet()
        val savedBefore = secretStorage.savedSecrets.toMap()
        // The keyset fails to load for the readability check only; the save right after it would work.
        secretStorage.keysetLoadFailures = 1

        val failure = runCatching {
            repository.import(
                text = listOf(SAME_ROUTE_OTHER_NAME_LINK, HY2_LINK).joinToString("\n"),
                source = ProxyProfileSource.REMOTE,
                sourceUrl = SOURCE_URL,
            )
        }.exceptionOrNull()

        assertTrue(failure is GeneralSecurityException)
        assertEquals(before, dao.remainingEntities().toSet())
        assertEquals(savedBefore, secretStorage.savedSecrets)
        assertTrue(secretStorage.deletedSecretIds.isEmpty())
        val own = dao.remainingEntities().single { entity -> entity.source == ProxyProfileSource.MANUAL.name }
        assertEquals(OWN_LINK, repository.getById(own.id)?.rawUri)
    }

    @Test
    fun `once the secret store reads again the same import is a plain duplicate`() = runBlocking {
        val dao = RecordingProxyProfileDao(emptyMap())
        val secretStorage = RecordingSecretStorage(emptyMap())
        val repository = repository(dao, secretStorage)
        repository.import(text = OWN_LINK, source = ProxyProfileSource.MANUAL, sourceUrl = null)
        val own = dao.remainingEntities().single()
        secretStorage.keysetLoadFailures = 1
        runCatching { repository.import(SAME_ROUTE_OTHER_NAME_LINK, ProxyProfileSource.MANUAL, sourceUrl = null) }

        val result = repository.import(SAME_ROUTE_OTHER_NAME_LINK, ProxyProfileSource.MANUAL, sourceUrl = null)

        assertEquals(1, result.duplicates)
        assertEquals(0, result.updated)
        assertEquals(own, dao.entity(own.id))
        assertEquals(OWN_LINK, repository.getById(own.id)?.rawUri)
        assertTrue(secretStorage.deletedSecretIds.isEmpty())
    }

    @Test
    fun `a sync finds the route an older build saved a plus credential under and keeps its state`() = runBlocking {
        val dao = RecordingProxyProfileDao(emptyMap())
        val secretStorage = RecordingSecretStorage(emptyMap())
        val repository = repository(dao, secretStorage)
        val saved = dao.savedByOlderBuild(repository, PLUS_LINK, ProxyProfileSource.REMOTE, SOURCE_URL)
        dao.upsert(saved.copy(isSelected = true, isPinned = true))

        val result = repository.import(text = PLUS_LINK, source = ProxyProfileSource.REMOTE, sourceUrl = SOURCE_URL)

        assertEquals(0, result.added)
        assertEquals(1, result.updated)
        val synced = dao.remainingEntities().single()
        assertEquals(saved.id, synced.id)
        assertEquals(currentFingerprint(PLUS_LINK), synced.fingerprint)
        assertEquals(saved.secretId, synced.secretId)
        assertTrue(synced.isSelected && synced.isPinned && !synced.isStale)
        assertEquals(PLUS_LINK, repository.getById(saved.id)?.rawUri)
    }

    @Test
    fun `pasting a plus link again finds the route an older build saved it as`() = runBlocking {
        val dao = RecordingProxyProfileDao(emptyMap())
        val secretStorage = RecordingSecretStorage(emptyMap())
        val repository = repository(dao, secretStorage)
        val saved = dao.savedByOlderBuild(repository, PLUS_LINK, ProxyProfileSource.MANUAL, sourceUrl = null)

        val result = repository.import(text = PLUS_LINK, source = ProxyProfileSource.MANUAL, sourceUrl = null)
        val again = repository.import(text = PLUS_LINK, source = ProxyProfileSource.MANUAL, sourceUrl = null)

        assertEquals(0, result.added)
        assertEquals(1, result.duplicates)
        // The route now carries the current fingerprint, so the next paste finds it directly.
        assertEquals(saved.copy(fingerprint = currentFingerprint(PLUS_LINK)), dao.entity(saved.id))
        assertEquals(1, again.duplicates)
        assertEquals(1, dao.remainingEntities().size)
        assertEquals(1, secretStorage.savedSecrets.size)
        assertTrue(secretStorage.deletedSecretIds.isEmpty())
    }

    @Test
    fun `a route whose credential has a real space is not taken for the plus link`() = runBlocking {
        val dao = RecordingProxyProfileDao(emptyMap())
        val repository = repository(dao, RecordingSecretStorage(emptyMap()))
        repository.import(text = SPACE_LINK, source = ProxyProfileSource.MANUAL, sourceUrl = null)
        val spaceRoute = dao.remainingEntities().single()
        // Older builds read the plus link the same way, so both share that fingerprint.
        assertEquals(spaceRoute.fingerprint, legacyFingerprint(PLUS_LINK))

        val result = repository.import(text = PLUS_LINK, source = ProxyProfileSource.MANUAL, sourceUrl = null)

        assertEquals(1, result.added)
        assertEquals(spaceRoute, dao.entity(spaceRoute.id))
        assertEquals(
            setOf(spaceRoute.fingerprint, currentFingerprint(PLUS_LINK)),
            dao.remainingEntities().map(ProxyProfileEntity::fingerprint).toSet(),
        )
    }

    @Test
    fun `a route saved under the older fingerprint whose link can't be read is left alone`() = runBlocking {
        val dao = RecordingProxyProfileDao(emptyMap())
        val secretStorage = RecordingSecretStorage(emptyMap())
        val repository = repository(dao, secretStorage)
        val saved = dao.savedByOlderBuild(repository, PLUS_LINK, ProxyProfileSource.MANUAL, sourceUrl = null)
        secretStorage.startOver()

        val result = repository.import(text = PLUS_LINK, source = ProxyProfileSource.MANUAL, sourceUrl = null)

        // Nothing proves the row is this link, so it is neither given the link nor deleted.
        assertEquals(1, result.added)
        assertEquals(saved, dao.entity(saved.id))
        assertTrue(secretStorage.deletedSecretIds.isEmpty())
    }

    @Test
    fun `editing another route to a plus link an older build saved is a duplicate`() = runBlocking {
        val dao = RecordingProxyProfileDao(emptyMap())
        val secretStorage = RecordingSecretStorage(emptyMap())
        val repository = repository(dao, secretStorage)
        val saved = dao.savedByOlderBuild(repository, PLUS_LINK, ProxyProfileSource.MANUAL, sourceUrl = null)
        repository.import(text = OWN_LINK, source = ProxyProfileSource.MANUAL, sourceUrl = null)
        val other = dao.remainingEntities().single { entity -> entity.id != saved.id }

        val result = repository.update(id = other.id, rawUri = PLUS_LINK)
        val sameRoute = repository.update(id = saved.id, rawUri = PLUS_LINK.replace("#Plus", "#Renamed"))

        assertEquals(1, result.duplicates)
        assertEquals(other, dao.entity(other.id))
        // The route itself takes its own link and, with it, the current fingerprint.
        assertEquals(1, sameRoute.updated)
        assertEquals(currentFingerprint(PLUS_LINK), dao.entity(saved.id)?.fingerprint)
    }

    /** Imports [link] and gives its row the fingerprint an older build saved it under. */
    private suspend fun RecordingProxyProfileDao.savedByOlderBuild(
        repository: RoomProxyProfileRepository,
        link: String,
        source: ProxyProfileSource,
        sourceUrl: String?,
    ): ProxyProfileEntity {
        repository.import(text = link, source = source, sourceUrl = sourceUrl)
        val imported = remainingEntities().single { entity -> entity.fingerprint == currentFingerprint(link) }
        return imported.copy(fingerprint = legacyFingerprint(link)).also { upsert(it) }
    }

    private fun currentFingerprint(link: String): String = parsed(link).fingerprint

    private fun legacyFingerprint(link: String): String = checkNotNull(parsed(link).legacyFingerprint)

    private fun parsed(link: String) = (ProxyShareLinkParser().parse(link) as ProxyParseResult.Success).profile

    private fun repository(
        dao: ProxyProfileDao,
        secretStorage: SecretStorage,
    ) = RoomProxyProfileRepository(
        dao = dao,
        secretStorage = secretStorage,
        parser = ProxyShareLinkParser(),
        ioDispatcher = Dispatchers.Unconfined,
    )

    private companion object {
        const val SOURCE_URL = "https://example.test/proxies"
        const val HY2_LINK = "hysteria2://correct-horse@hy2.example.test:443?sni=hy2.example.test#Hy2"

        // The alias scheme, the default port and `peer` for `sni` describe the same outbound.
        const val HY2_ALIAS_LINK = "hy2://correct-horse@hy2.example.test/?peer=hy2.example.test#Alias"
        const val HY2_UNKNOWN_OBFS_LINK =
            "hysteria2://correct-horse@hy2.example.test:443?obfs=xplus&obfs-password=battery#Xplus"
        const val HYSTERIA_V1_LINK =
            "hysteria://hy1.example.test:443?protocol=udp&auth=correct-horse&upmbps=10&downmbps=50#V1"
        const val UNKNOWN_TRANSPORT_LINK =
            "vless://00000000-0000-4000-8000-000000000001@quic.example.test:443?type=quic#Quic"

        // One outbound under two names: the same fingerprint, a different stored link.
        const val OWN_LINK = "vless://00000000-0000-4000-8000-000000000002@own.example.test:443?security=tls#Mine"
        const val SAME_ROUTE_OTHER_NAME_LINK =
            "vless://00000000-0000-4000-8000-000000000002@own.example.test:443?security=tls#Shared"

        // Older builds read the `+` as a space: the plus link then had the space link's fingerprint.
        const val PLUS_LINK = "trojan://correct+horse@trojan.example.test:443?security=tls#Plus"
        const val SPACE_LINK = "trojan://correct%20horse@trojan.example.test:443?security=tls#Space"
    }
}

private class RecordingSecretStorage(
    initialSecrets: Map<String, String>,
) : SecretStorage {
    private val secrets = initialSecrets.toMutableMap()
    var getSecretsCalls = 0
        private set
    val savedSecrets = linkedMapOf<String, String>()
    val deletedSecretIds = hashSetOf<String>()

    /**
     * How many of the next store accesses find the keyset failing to load, as Tink's lazy keyset
     * does for a moment while the Keystore hiccups: a read reports such a failure as missing values,
     * a write and [checkAvailable] throw it. The access after them loads it.
     */
    var keysetLoadFailures = 0

    override suspend fun saveSecret(id: String, value: String) {
        loadKeyset()
        secrets[id] = value
        savedSecrets[id] = value
    }

    override suspend fun getSecret(id: String): String? = if (keysetLoads()) secrets[id] else null

    override suspend fun getSecrets(ids: Collection<String>): Map<String, String> {
        getSecretsCalls += 1
        if (!keysetLoads()) return emptyMap()
        return ids.mapNotNull { id -> secrets[id]?.let { value -> id to value } }.toMap()
    }

    override suspend fun deleteSecret(id: String) {
        secrets.remove(id)
        deletedSecretIds += id
    }

    override suspend fun checkAvailable() {
        loadKeyset()
    }

    /** What the store looks like after it started over: every saved value is gone. */
    fun startOver() {
        secrets.clear()
    }

    private fun keysetLoads(): Boolean = runCatching(::loadKeyset).isSuccess

    private fun loadKeyset() {
        if (keysetLoadFailures > 0) {
            keysetLoadFailures -= 1
            throw GeneralSecurityException("Keystore operation failed")
        }
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

    override suspend fun getByFingerprint(fingerprint: String): ProxyProfileEntity? {
        return entities.values.firstOrNull { entity -> entity.fingerprint == fingerprint }
    }

    override suspend fun getByFingerprints(fingerprints: List<String>): List<ProxyProfileEntity> {
        return entities.values.filter { entity -> entity.fingerprint in fingerprints }
    }

    override suspend fun getSelected(): ProxyProfileEntity? {
        return entities.values.firstOrNull { entity -> entity.isSelected && !entity.isStale }
    }

    override suspend fun getFirstAvailableId(): String? {
        return entities.values
            .filterNot(ProxyProfileEntity::isStale)
            .maxByOrNull(ProxyProfileEntity::updatedAt)
            ?.id
    }

    override suspend fun upsert(entity: ProxyProfileEntity) {
        entities[entity.id] = entity
    }

    override suspend fun upsertAll(entities: List<ProxyProfileEntity>) {
        entities.forEach { entity -> this.entities[entity.id] = entity }
    }

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

    override suspend fun markRemoteProfilesStale(syncStartedAt: Long) = Unit

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

    fun remainingEntities(): Collection<ProxyProfileEntity> = entities.values

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
