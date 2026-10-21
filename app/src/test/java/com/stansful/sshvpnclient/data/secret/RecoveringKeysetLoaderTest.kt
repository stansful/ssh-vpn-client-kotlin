package com.stansful.sshvpnclient.data.secret

import java.io.CharConversionException
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.security.ProviderException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RecoveringKeysetLoaderTest {
    private var keysetStored = true
    private var masterKeyExists: Boolean? = true
    private var discards = 0
    private var keystoreQueries = 0
    private val logs = mutableListOf<String>()
    private val loads = ArrayDeque<() -> String>()

    @Test
    fun `a readable keyset loads without asking the keystore or discarding anything`() {
        loads += { "aead" }

        assertEquals("aead", loader().load())
        assertEquals(0, keystoreQueries)
        assertEquals(0, discards)
        assertTrue(logs.isEmpty())
    }

    @Test
    fun `a keyset whose keystore key is gone is discarded and replaced`() {
        // What Tink throws when the encrypted keyset arrived on a phone without its master key.
        loads += { throw InvalidKeyException("Keystore cannot load the key with ID: ssh_vpn_secret_master_key") }
        loads += { "fresh aead" }
        masterKeyExists = false

        assertEquals("fresh aead", loader().load())
        assertEquals(1, discards)
        assertEquals(listOf(RecoveringKeysetLoader.KEYSET_RESET_MESSAGE), logs)
    }

    @Test
    fun `an unreadable keyset whose key still exists is left alone`() {
        val failure = GeneralSecurityException("decryption failed")
        loads += { throw failure }
        masterKeyExists = true

        assertSame(failure, loadFailure())
        assertEquals(0, discards)
    }

    @Test
    fun `nothing is discarded while the keystore can't say whether the key exists`() {
        val failure = GeneralSecurityException("Keystore is temporarily unavailable")
        loads += { throw failure }
        masterKeyExists = null

        assertSame(failure, loadFailure())
        assertEquals(0, discards)
        assertTrue(logs.isEmpty())
    }

    @Test
    fun `a failure without a stored keyset is not a keyset to discard`() {
        val failure = GeneralSecurityException("cannot generate keyset")
        loads += { throw failure }
        keysetStored = false
        masterKeyExists = false

        assertSame(failure, loadFailure())
        assertEquals(0, discards)
    }

    @Test
    fun `runtime keystore errors are passed on without a verdict`() {
        val failure = ProviderException("Keystore operation failed")
        loads += { throw failure }
        masterKeyExists = false

        assertSame(failure, loadFailure())
        assertEquals(0, keystoreQueries)
        assertEquals(0, discards)
    }

    @Test
    fun `an unreadable keyset file counts like an undecryptable one`() {
        loads += { throw CharConversionException("can't read keyset") }
        loads += { "fresh aead" }
        masterKeyExists = false

        assertEquals("fresh aead", loader().load())
        assertEquals(1, discards)
    }

    @Test
    fun `a fresh keyset that fails too is reported, not retried forever`() {
        val second = GeneralSecurityException("cannot use Android Keystore")
        loads += { throw GeneralSecurityException("decryption failed") }
        loads += { throw second }
        masterKeyExists = false

        assertSame(second, loadFailure())
        assertEquals(1, discards)
    }

    private fun loader() = RecoveringKeysetLoader(
        loadKeyset = { loads.removeFirst()() },
        isKeysetStored = { keysetStored },
        masterKeyExists = {
            keystoreQueries += 1
            masterKeyExists
        },
        discardKeyset = { discards += 1 },
        log = logs::add,
    )

    private fun loadFailure(): Throwable {
        try {
            loader().load()
        } catch (error: Exception) {
            return error
        }
        fail("Expected the keyset load to fail")
        error("unreachable")
    }
}
