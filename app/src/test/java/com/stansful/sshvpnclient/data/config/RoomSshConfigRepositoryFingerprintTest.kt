package com.stansful.sshvpnclient.data.config

import com.stansful.sshvpnclient.data.secret.SecretStorage
import com.stansful.sshvpnclient.domain.model.AuthType
import com.stansful.sshvpnclient.domain.model.SshConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomSshConfigRepositoryFingerprintTest {
    @Test
    fun `first use pins the fingerprint of a server without one`() = runBlocking {
        val dao = FakeSshConfigDao(configEntity(id = "vps", fingerprint = null))
        val repository = repository(dao)

        assertTrue(repository.pinFingerprintIfAbsent("vps", " $FIRST_KEY "))

        assertEquals(FIRST_KEY, dao.entity("vps")?.fingerprint)
        assertEquals(listOf("vps" to FIRST_KEY), dao.pinCalls)
    }

    @Test
    fun `first use never overwrites a saved fingerprint`() = runBlocking {
        val dao = FakeSshConfigDao(configEntity(id = "vps", fingerprint = USER_KEY))
        val repository = repository(dao)

        assertFalse(repository.pinFingerprintIfAbsent("vps", FIRST_KEY))

        assertEquals(USER_KEY, dao.entity("vps")?.fingerprint)
    }

    @Test
    fun `a blank saved fingerprint counts as none`() = runBlocking {
        val dao = FakeSshConfigDao(configEntity(id = "vps", fingerprint = "  "))

        assertTrue(repository(dao).pinFingerprintIfAbsent("vps", FIRST_KEY))
        assertEquals(FIRST_KEY, dao.entity("vps")?.fingerprint)
    }

    @Test
    fun `pinning leaves the rest of the server and its list position alone`() = runBlocking {
        val before = configEntity(id = "vps", fingerprint = null)
        val dao = FakeSshConfigDao(before)

        repository(dao).pinFingerprintIfAbsent("vps", FIRST_KEY)

        assertEquals(before.copy(fingerprint = FIRST_KEY), dao.entity("vps"))
    }

    @Test
    fun `a blank fingerprint or a missing server pins nothing`() = runBlocking {
        val dao = FakeSshConfigDao(configEntity(id = "vps", fingerprint = null))
        val repository = repository(dao)

        assertFalse(repository.pinFingerprintIfAbsent("vps", "   "))
        assertFalse(repository.pinFingerprintIfAbsent("deleted", FIRST_KEY))

        assertNull(dao.entity("vps")?.fingerprint)
        assertEquals(listOf("deleted" to FIRST_KEY), dao.pinCalls)
    }

    @Test
    fun `accepting a changed key replaces the saved fingerprint`() = runBlocking {
        val dao = FakeSshConfigDao(configEntity(id = "vps", fingerprint = USER_KEY))
        val repository = repository(dao)

        assertTrue(repository.replaceFingerprint("vps", FIRST_KEY))
        assertFalse(repository.replaceFingerprint("vps", ""))
        assertFalse(repository.replaceFingerprint("deleted", FIRST_KEY))

        assertEquals(FIRST_KEY, dao.entity("vps")?.fingerprint)
    }

    @Test
    fun `saving a server that did not touch the fingerprint keeps the one saved at write time`() = runBlocking {
        val dao = FakeSshConfigDao(configEntity(id = "vps", fingerprint = null))
        val repository = repository(dao)
        val edited = server(fingerprint = null).copy(note = "Rack 2")
        // The service pins the key after the editor read the server, before its save is written.
        dao.beforeKeepingUpsert = { dao.pinFingerprintIfAbsent("vps", FIRST_KEY) }

        repository.update(edited, keepSavedFingerprint = true)

        assertEquals(FIRST_KEY, dao.entity("vps")?.fingerprint)
        assertEquals("Rack 2", dao.entity("vps")?.note)
    }

    @Test
    fun `saving a fingerprint typed in the editor replaces the saved one`() = runBlocking {
        val dao = FakeSshConfigDao(configEntity(id = "vps", fingerprint = USER_KEY))

        repository(dao).update(server(fingerprint = null), keepSavedFingerprint = false)

        assertNull(dao.entity("vps")?.fingerprint)
    }

    private fun repository(dao: SshConfigDao) = RoomSshConfigRepository(
        dao = dao,
        secretStorage = NoSecrets,
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun server(fingerprint: String?) = SshConfig(
        id = "vps",
        name = "Home VPS",
        host = "vps.example.net",
        port = 22,
        username = "root",
        authType = AuthType.PRIVATE_KEY,
        password = null,
        privateKeyId = "key-1",
        fingerprint = fingerprint,
        keepAliveIntervalSec = 30,
        enableUdpForwarding = false,
        note = null,
        createdAt = 1L,
        updatedAt = 3L,
    )

    private companion object {
        const val FIRST_KEY = "SHA256:47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU"
        const val USER_KEY = "SHA256:atzfmdcrqQzoXZfKHLarePDyMw/G5NYfJ3h1eHUVS9g"
    }
}

/** Mirrors the SQL of [SshConfigDao.pinFingerprintIfAbsent] and [SshConfigDao.updateFingerprint]. */
private class FakeSshConfigDao(vararg initial: SshConfigEntity) : SshConfigDao() {
    private val entities = initial.associateBy(SshConfigEntity::id).toMutableMap()
    val pinCalls = mutableListOf<Pair<String, String>>()

    /** Runs inside [upsertKeepingFingerprint] before its read: a write landing between the two steps. */
    var beforeKeepingUpsert: suspend () -> Unit = {}

    fun entity(id: String): SshConfigEntity? = entities[id]

    override suspend fun upsertKeepingFingerprint(entity: SshConfigEntity) {
        beforeKeepingUpsert()
        super.upsertKeepingFingerprint(entity)
    }

    override suspend fun pinFingerprintIfAbsent(id: String, fingerprint: String): Int {
        pinCalls += id to fingerprint
        val entity = entities[id] ?: return 0
        if (!entity.fingerprint.isNullOrBlank()) return 0
        entities[id] = entity.copy(fingerprint = fingerprint)
        return 1
    }

    override suspend fun updateFingerprint(id: String, fingerprint: String): Int {
        val entity = entities[id] ?: return 0
        entities[id] = entity.copy(fingerprint = fingerprint)
        return 1
    }

    override fun observeAll(): Flow<List<SshConfigEntity>> = flowOf(entities.values.toList())

    override fun observeSelected(): Flow<SshConfigEntity?> = flowOf(entities.values.firstOrNull { it.isSelected })

    override fun observeSummaries(): Flow<List<SshConfigSummaryRow>> = flowOf(emptyList())

    override fun observeSelectedSummary(): Flow<SshConfigSummaryRow?> = flowOf(null)

    override suspend fun getAll(): List<SshConfigEntity> = entities.values.toList()

    override suspend fun getById(id: String): SshConfigEntity? = entities[id]

    override suspend fun getSelected(): SshConfigEntity? = entities.values.firstOrNull { it.isSelected }

    override suspend fun upsert(entity: SshConfigEntity) {
        entities[entity.id] = entity
    }

    override suspend fun deleteById(id: String) {
        entities.remove(id)
    }

    override suspend fun clearSelection() {
        entities.replaceAll { _, entity -> entity.copy(isSelected = false) }
    }

    override suspend fun markSelected(id: String) {
        entities[id]?.let { entity -> entities[id] = entity.copy(isSelected = true) }
    }

    override suspend fun countByPrivateKeyId(keyId: String): Int = entities.values.count { it.privateKeyId == keyId }
}

private object NoSecrets : SecretStorage {
    override suspend fun saveSecret(id: String, value: String) = Unit

    override suspend fun getSecret(id: String): String? = null

    override suspend fun getSecrets(ids: Collection<String>): Map<String, String> = emptyMap()

    override suspend fun deleteSecret(id: String) = Unit
}

internal fun configEntity(id: String, fingerprint: String?) = SshConfigEntity(
    id = id,
    name = "Home VPS",
    host = "vps.example.net",
    port = 22,
    username = "root",
    authType = "PRIVATE_KEY",
    passwordSecretId = null,
    privateKeyId = "key-1",
    fingerprint = fingerprint,
    keepAliveIntervalSec = 30,
    enableUdpForwarding = false,
    note = null,
    isSelected = true,
    createdAt = 1L,
    updatedAt = 2L,
)
