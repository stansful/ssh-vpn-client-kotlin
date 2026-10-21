package com.stansful.sshvpnclient.data.secret

import java.security.GeneralSecurityException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ReadableSecretIdsTest {
    @Test
    fun `only the values that don't decrypt are unreadable`() = runBlocking {
        val ids = List(600) { index -> "secret-$index" }
        val storage = FakeSecretStorage(
            values = ids.associateWith { id -> "value of $id" },
            undecryptable = setOf("secret-3", "secret-512"),
        )

        val readable = storage.readableSecretIds(ids + "secret-3" + "missing")

        assertEquals(ids.toSet() - "secret-3" - "secret-512", readable)
        // Read in batches, never the whole catalog at once.
        assertEquals(listOf(256, 256, 89), storage.batchSizes)
    }

    @Test
    fun `a store that can't be read throws instead of calling every value unreadable`() = runBlocking {
        val failure = GeneralSecurityException("Keystore operation failed")
        val storage = FakeSecretStorage(values = mapOf("secret" to "value"), unavailable = failure)

        try {
            storage.readableSecretIds(listOf("secret"))
            fail("Expected the readability check to fail")
        } catch (error: GeneralSecurityException) {
            assertSame(failure, error)
        }
        assertTrue("nothing is read from a store that can't be opened", storage.batchSizes.isEmpty())
    }

    @Test
    fun `no ids need no store`() = runBlocking {
        val storage = FakeSecretStorage(values = emptyMap(), unavailable = GeneralSecurityException("unused"))

        assertEquals(emptySet<String>(), storage.readableSecretIds(emptyList()))
    }

    /** Like Tink's store: a failed read is a missing value, whether one ciphertext or the keyset failed. */
    private class FakeSecretStorage(
        private val values: Map<String, String>,
        private val undecryptable: Set<String> = emptySet(),
        private val unavailable: Exception? = null,
    ) : SecretStorage {
        val batchSizes = mutableListOf<Int>()

        override suspend fun saveSecret(id: String, value: String) {
            error("read-only")
        }

        override suspend fun getSecret(id: String): String? = getSecrets(listOf(id))[id]

        override suspend fun getSecrets(ids: Collection<String>): Map<String, String> {
            batchSizes += ids.size
            if (unavailable != null) return emptyMap()
            return ids.filterNot(undecryptable::contains).mapNotNull { id -> values[id]?.let { id to it } }.toMap()
        }

        override suspend fun deleteSecret(id: String) {
            error("read-only")
        }

        override suspend fun checkAvailable() {
            unavailable?.let { throw it }
        }
    }
}
