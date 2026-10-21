package com.stansful.sshvpnclient.ui.configedit

import android.app.Application
import android.os.Looper
import com.stansful.sshvpnclient.data.config.InMemorySshHostKeyChangeRepository
import com.stansful.sshvpnclient.domain.model.AuthType
import com.stansful.sshvpnclient.domain.model.SshConfig
import com.stansful.sshvpnclient.domain.model.SshConfigSummary
import com.stansful.sshvpnclient.domain.model.SshHostKeyChange
import com.stansful.sshvpnclient.domain.model.SshPrivateKey
import com.stansful.sshvpnclient.domain.model.SshPrivateKeySummary
import com.stansful.sshvpnclient.domain.repository.SshConfigRepository
import com.stansful.sshvpnclient.domain.repository.SshPrivateKeyRepository
import com.stansful.sshvpnclient.domain.usecase.config.AcceptSshHostKeyUseCase
import com.stansful.sshvpnclient.domain.usecase.config.AddSshConfigUseCase
import com.stansful.sshvpnclient.domain.usecase.config.GetSshConfigByIdUseCase
import com.stansful.sshvpnclient.domain.usecase.config.UpdateSshConfigUseCase
import com.stansful.sshvpnclient.domain.usecase.key.GetSshPrivateKeyByIdUseCase
import com.stansful.sshvpnclient.domain.usecase.key.GetSshPrivateKeyListUseCase
import com.stansful.sshvpnclient.ui.keys.SshKeyTraitsReader
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Host identity in the server editor (viewModelScope needs the Robolectric main looper). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class EditConfigViewModelHostKeyTest {
    private val changes = InMemorySshHostKeyChangeRepository()

    @Test
    fun `accepting a changed host key pins the new key and drops the warning`() = runBlocking {
        val configs = FakeConfigs(server(fingerprint = OLD_KEY))
        val change = SshHostKeyChange(configId = SERVER_ID, pinnedFingerprint = OLD_KEY, observedFingerprint = NEW_KEY)
        changes.record(change)
        val viewModel = viewModel(configs)
        idle()
        assertEquals(change, viewModel.uiState.value.hostKeyChange)

        viewModel.acceptNewHostKey()
        idle()

        val state = viewModel.uiState.value
        assertEquals(NEW_KEY, configs.stored().fingerprint)
        assertNull(state.hostKeyChange)
        assertNull(changes.observe(SERVER_ID).first())
        assertEquals(NEW_KEY, state.form.fingerprint)
        assertEquals(HostKeyAcceptResult.Accepted, state.hostKeyAcceptResult)
        // Accepting is not a save: the server keeps its place in the list.
        assertEquals(server(fingerprint = NEW_KEY), configs.stored())

        viewModel.consumeHostKeyAcceptResult()
        assertNull(viewModel.uiState.value.hostKeyAcceptResult)
    }

    @Test
    fun `saving other edits keeps a key pinned on first use while the editor was open`() {
        val configs = FakeConfigs(server(fingerprint = null))
        val viewModel = viewModel(configs)
        idle()
        assertEquals("", viewModel.uiState.value.form.fingerprint)

        runBlocking { configs.pinFingerprintIfAbsent(SERVER_ID, NEW_KEY) }
        viewModel.updateForm { it.copy(note = "Rack 2") }
        viewModel.save()
        idle()

        assertTrue(viewModel.uiState.value.isSaved)
        assertEquals(NEW_KEY, configs.stored().fingerprint)
        assertEquals("Rack 2", configs.stored().note)
    }

    @Test
    fun `clearing the fingerprint turns trust on first use back on`() {
        val configs = FakeConfigs(server(fingerprint = OLD_KEY))
        val viewModel = viewModel(configs)
        idle()

        viewModel.updateForm { it.copy(fingerprint = "") }
        viewModel.save()
        idle()

        assertNull(configs.stored().fingerprint)
    }

    @Test
    fun `a fingerprint typed by hand is saved and settles the warning`() = runBlocking {
        val configs = FakeConfigs(server(fingerprint = OLD_KEY))
        changes.record(SshHostKeyChange(SERVER_ID, pinnedFingerprint = OLD_KEY, observedFingerprint = NEW_KEY))
        val viewModel = viewModel(configs)
        idle()

        viewModel.updateForm { it.copy(fingerprint = " $USER_KEY ") }
        viewModel.save()
        idle()

        assertEquals(USER_KEY, configs.stored().fingerprint)
        assertNull(changes.observe(SERVER_ID).first())
    }

    @Test
    fun `accepting fails visibly when the server is gone`() {
        val configs = FakeConfigs(server(fingerprint = OLD_KEY))
        changes.record(SshHostKeyChange(SERVER_ID, pinnedFingerprint = OLD_KEY, observedFingerprint = NEW_KEY))
        val viewModel = viewModel(configs)
        idle()

        runBlocking { configs.delete(SERVER_ID) }
        viewModel.acceptNewHostKey()
        idle()

        assertEquals(HostKeyAcceptResult.Failed, viewModel.uiState.value.hostKeyAcceptResult)
        assertEquals(OLD_KEY, viewModel.uiState.value.form.fingerprint)
    }

    private fun viewModel(configs: FakeConfigs): EditConfigViewModel {
        val keys = NoKeys()
        return EditConfigViewModel(
            configId = SERVER_ID,
            addSshConfigUseCase = AddSshConfigUseCase(configs),
            updateSshConfigUseCase = UpdateSshConfigUseCase(configs),
            getSshConfigByIdUseCase = GetSshConfigByIdUseCase(configs),
            getSshPrivateKeyListUseCase = GetSshPrivateKeyListUseCase(keys),
            keyTraitsReader = SshKeyTraitsReader(GetSshPrivateKeyByIdUseCase(keys)),
            hostKeyChanges = changes,
            acceptSshHostKeyUseCase = AcceptSshHostKeyUseCase(configs, changes),
        )
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun server(fingerprint: String?) = SshConfig(
        id = SERVER_ID,
        name = "Home VPS",
        host = "vps.example.net",
        port = 22,
        username = "root",
        authType = AuthType.PASSWORD,
        password = "night-owl 2049",
        privateKeyId = null,
        fingerprint = fingerprint,
        keepAliveIntervalSec = 30,
        enableUdpForwarding = false,
        note = null,
        createdAt = 1L,
        updatedAt = 2L,
    )

    private class FakeConfigs(initial: SshConfig) : SshConfigRepository {
        private val configs = linkedMapOf(initial.id to initial)

        fun stored(): SshConfig = configs.getValue(SERVER_ID)

        override suspend fun getById(id: String): SshConfig? = configs[id]

        override suspend fun create(config: SshConfig) {
            configs[config.id] = config
        }

        override suspend fun update(config: SshConfig) {
            if (config.id in configs) configs[config.id] = config
        }

        override suspend fun delete(id: String) {
            configs.remove(id)
        }

        override suspend fun pinFingerprintIfAbsent(id: String, fingerprint: String): Boolean {
            val config = configs[id]?.takeIf { it.fingerprint.isNullOrBlank() } ?: return false
            configs[id] = config.copy(fingerprint = fingerprint)
            return true
        }

        override suspend fun replaceFingerprint(id: String, fingerprint: String): Boolean {
            val config = configs[id] ?: return false
            configs[id] = config.copy(fingerprint = fingerprint)
            return true
        }

        override fun observeAll(): Flow<List<SshConfig>> = flowOf(configs.values.toList())
        override fun observeSelectedConfig(): Flow<SshConfig?> = flowOf(null)
        override fun observeSummaries(): Flow<List<SshConfigSummary>> = flowOf(emptyList())
        override fun observeSelectedSummary(): Flow<SshConfigSummary?> = flowOf(null)
        override suspend fun getAll(): List<SshConfig> = configs.values.toList()
        override suspend fun selectConfig(id: String) = Unit
        override suspend fun getSelectedConfig(): SshConfig? = null
        override suspend fun getUsageCountForKey(keyId: String): Int = 0
    }

    private class NoKeys : SshPrivateKeyRepository {
        override fun observeAll(): Flow<List<SshPrivateKey>> = flowOf(emptyList())
        override fun observeSummaries(): Flow<List<SshPrivateKeySummary>> = flowOf(emptyList())
        override suspend fun getAll(): List<SshPrivateKey> = emptyList()
        override suspend fun getById(id: String): SshPrivateKey? = null
        override suspend fun create(key: SshPrivateKey) = Unit
        override suspend fun update(key: SshPrivateKey) = Unit
        override suspend fun delete(id: String) = Unit
        override suspend fun getUsageCount(keyId: String): Int = 0
    }

    private companion object {
        const val SERVER_ID = "home-vps"
        const val OLD_KEY = "SHA256:atzfmdcrqQzoXZfKHLarePDyMw/G5NYfJ3h1eHUVS9g"
        const val NEW_KEY = "SHA256:47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU"
        const val USER_KEY = "SHA256:LCa0a2j/xo/5m0U8HTBBNBNCLXBkg7+g+YpeiGJm564"
    }
}
