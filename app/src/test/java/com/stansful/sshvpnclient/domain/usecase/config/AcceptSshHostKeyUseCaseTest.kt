package com.stansful.sshvpnclient.domain.usecase.config

import com.stansful.sshvpnclient.data.config.InMemorySshHostKeyChangeRepository
import com.stansful.sshvpnclient.domain.model.SshConfig
import com.stansful.sshvpnclient.domain.model.SshConfigSummary
import com.stansful.sshvpnclient.domain.model.SshHostKeyChange
import com.stansful.sshvpnclient.domain.repository.SshConfigRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AcceptSshHostKeyUseCaseTest {
    private val changes = InMemorySshHostKeyChangeRepository()
    private val change = SshHostKeyChange("vps", pinnedFingerprint = OLD_KEY, observedFingerprint = NEW_KEY)

    @Test
    fun `accepting replaces the pinned fingerprint with the observed one and drops the warning`() = runBlocking {
        val configs = RecordingConfigs(existing = setOf("vps"))
        changes.record(change)

        assertTrue(AcceptSshHostKeyUseCase(configs, changes)(change))

        assertEquals(listOf("vps" to NEW_KEY), configs.replaced)
        assertNull(changes.observe("vps").first())
    }

    @Test
    fun `accepting for a deleted server reports it and still drops the stale warning`() = runBlocking {
        val configs = RecordingConfigs(existing = emptySet())
        changes.record(change)

        assertFalse(AcceptSshHostKeyUseCase(configs, changes)(change))

        assertNull(changes.observe("vps").first())
    }

    /** Only the host key calls matter here. */
    private class RecordingConfigs(private val existing: Set<String>) : SshConfigRepository {
        val replaced = mutableListOf<Pair<String, String>>()

        override suspend fun replaceFingerprint(id: String, fingerprint: String): Boolean {
            if (id !in existing) return false
            replaced += id to fingerprint
            return true
        }

        override suspend fun pinFingerprintIfAbsent(id: String, fingerprint: String): Boolean = error("not used")
        override fun observeAll(): Flow<List<SshConfig>> = emptyFlow()
        override fun observeSelectedConfig(): Flow<SshConfig?> = emptyFlow()
        override fun observeSummaries(): Flow<List<SshConfigSummary>> = emptyFlow()
        override fun observeSelectedSummary(): Flow<SshConfigSummary?> = emptyFlow()
        override suspend fun getAll(): List<SshConfig> = emptyList()
        override suspend fun getById(id: String): SshConfig? = null
        override suspend fun create(config: SshConfig) = Unit
        override suspend fun update(config: SshConfig) = Unit
        override suspend fun delete(id: String) = Unit
        override suspend fun selectConfig(id: String) = Unit
        override suspend fun getSelectedConfig(): SshConfig? = null
        override suspend fun getUsageCountForKey(keyId: String): Int = 0
    }

    private companion object {
        const val OLD_KEY = "SHA256:atzfmdcrqQzoXZfKHLarePDyMw/G5NYfJ3h1eHUVS9g"
        const val NEW_KEY = "SHA256:47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU"
    }
}
