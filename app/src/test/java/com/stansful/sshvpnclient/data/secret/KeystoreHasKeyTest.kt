package com.stansful.sshvpnclient.data.secret

import java.security.KeyStoreException
import java.security.ProviderException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeystoreHasKeyTest {
    @Test
    fun `a key that is found is present without a probe key`() {
        val keystore = FakeKeystore(MASTER_KEY)

        assertEquals(true, keystoreHasKey(MASTER_KEY, keystore, pause = {}))
        assertTrue(keystore.createdProbes.isEmpty())
    }

    @Test
    fun `a key that is gone is absent once every round agrees`() {
        val keystore = FakeKeystore()
        var pauses = 0

        assertEquals(false, keystoreHasKey(MASTER_KEY, keystore, pause = { pauses += 1 }))
        assertEquals(KEYSTORE_ABSENCE_CHECKS - 1, pauses)
        // The key is looked up before the probe in every round.
        assertEquals(
            listOf(MASTER_KEY) + List(KEYSTORE_ABSENCE_CHECKS) { listOf(MASTER_KEY, PROBE_KEY) }.flatten(),
            keystore.lookups,
        )
        assertEquals(listOf(PROBE_KEY), keystore.deletedEntries)
    }

    @Test
    fun `a keystore failing again right after the probe answered does not read a present key as gone`() {
        // The old order (the probe, then the key) took this for "gone": the first lookup and the one
        // right after the probe answered hit a Keystore error, which the provider reports as false.
        val keystore = FakeKeystore(MASTER_KEY).apply { failingCalls = setOf(1, 3) }

        assertEquals(true, keystoreHasKey(MASTER_KEY, keystore, pause = {}))
    }

    @Test
    fun `no stretch of keystore failures makes a present key read as gone`() {
        val calls = 1 + 2 * KEYSTORE_ABSENCE_CHECKS
        for (first in 1..calls) {
            for (last in first..calls) {
                val keystore = FakeKeystore(MASTER_KEY).apply { failingCalls = (first..last).toSet() }

                val verdict = keystoreHasKey(MASTER_KEY, keystore, pause = {})

                assertNotEquals("Keystore failing for calls $first..$last", false, verdict)
            }
        }
    }

    @Test
    fun `a key lookup that fails only once is retried after the pause`() {
        // The first lookup and round one's lookup of the key fail; the probe still answers.
        val keystore = FakeKeystore(MASTER_KEY).apply { failingCalls = setOf(1, 2) }
        var pauses = 0

        assertEquals(true, keystoreHasKey(MASTER_KEY, keystore, pause = { pauses += 1 }))
        assertEquals(1, pauses)
        assertEquals(listOf(PROBE_KEY), keystore.deletedEntries)
    }

    @Test
    fun `a keystore that can't make a probe key gives no verdict`() {
        val keystore = FakeKeystore().apply { probeFailure = ProviderException("Keystore unavailable") }

        assertNull(keystoreHasKey(MASTER_KEY, keystore, pause = {}))
    }

    @Test
    fun `a probe key that can't be removed again gives no verdict`() {
        val keystore = FakeKeystore().apply { deleteFailure = KeyStoreException("Keystore unavailable") }

        assertNull(keystoreHasKey(MASTER_KEY, keystore, pause = {}))
    }

    @Test
    fun `an interrupted pause gives no verdict and still removes the probe key`() {
        val keystore = FakeKeystore()

        assertNull(keystoreHasKey(MASTER_KEY, keystore, pause = { throw InterruptedException() }))
        assertEquals(listOf(PROBE_KEY), keystore.deletedEntries)
    }

    /**
     * A Keystore holding [aliases]; a lookup whose 1-based number is in [failingCalls] answers false,
     * as Android's provider does for any Keystore error.
     */
    private class FakeKeystore(vararg aliases: String) : KeystoreLookup {
        private val aliases = aliases.toMutableSet()
        var failingCalls: Set<Int> = emptySet()
        var probeFailure: RuntimeException? = null
        var deleteFailure: Exception? = null
        val lookups = mutableListOf<String>()
        val createdProbes = mutableListOf<String>()
        val deletedEntries = mutableListOf<String>()

        override fun containsAlias(alias: String): Boolean {
            lookups += alias
            return lookups.size !in failingCalls && alias in aliases
        }

        override fun createProbeKey(alias: String) {
            probeFailure?.let { throw it }
            createdProbes += alias
            aliases += alias
        }

        override fun deleteEntry(alias: String) {
            deleteFailure?.let { throw it }
            deletedEntries += alias
            aliases -= alias
        }
    }

    private companion object {
        const val MASTER_KEY = "ssh_vpn_secret_master_key"
        const val PROBE_KEY = MASTER_KEY + KEYSTORE_PROBE_SUFFIX
    }
}
