package com.stansful.sshvpnclient.data.config

import com.stansful.sshvpnclient.domain.model.SshHostKeyChange
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InMemorySshHostKeyChangeRepositoryTest {
    private val repository = InMemorySshHostKeyChangeRepository()

    @Test
    fun `a recorded change is seen for its server only`() = runBlocking {
        val change = SshHostKeyChange("vps", pinnedFingerprint = OLD_KEY, observedFingerprint = NEW_KEY)

        repository.record(change)

        assertEquals(change, repository.observe("vps").first())
        assertNull(repository.observe("lab").first())
    }

    @Test
    fun `the latest change of a server replaces the earlier one`() = runBlocking {
        repository.record(SshHostKeyChange("vps", pinnedFingerprint = OLD_KEY, observedFingerprint = NEW_KEY))
        val latest = SshHostKeyChange("vps", pinnedFingerprint = OLD_KEY, observedFingerprint = OTHER_KEY)

        repository.record(latest)

        assertEquals(latest, repository.observe("vps").first())
    }

    @Test
    fun `clearing drops one server's change and leaves the others`() = runBlocking {
        val lab = SshHostKeyChange("lab", pinnedFingerprint = OLD_KEY, observedFingerprint = OTHER_KEY)
        repository.record(SshHostKeyChange("vps", pinnedFingerprint = OLD_KEY, observedFingerprint = NEW_KEY))
        repository.record(lab)

        repository.clear("vps")
        repository.clear("never-recorded")

        assertNull(repository.observe("vps").first())
        assertEquals(lab, repository.observe("lab").first())
    }

    private companion object {
        const val OLD_KEY = "SHA256:atzfmdcrqQzoXZfKHLarePDyMw/G5NYfJ3h1eHUVS9g"
        const val NEW_KEY = "SHA256:47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU"
        const val OTHER_KEY = "SHA256:LCa0a2j/xo/5m0U8HTBBNBNCLXBkg7+g+YpeiGJm564"
    }
}
