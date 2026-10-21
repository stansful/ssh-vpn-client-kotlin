package com.stansful.sshvpnclient.data.secret

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.KeyGenerator

/**
 * Loads the secret keyset, and starts over when it provably can't be read.
 *
 * The keyset is encrypted with an Android Keystore key, and Keystore keys never leave the device. A
 * keyset that arrives without its key - restored or transferred from another phone, or left behind
 * when the system dropped the app's keys - fails to load on every access, and every save fails with
 * it. Nothing can decrypt it or the ciphertexts under it any more, so both are discarded and a fresh
 * keyset is made: the saved secrets are lost either way, but the user can enter them again.
 *
 * Only that case is recovered: a keyset is stored, loading it failed with a crypto or I/O error, and
 * a working Keystore answers that the key is gone. Anything else - the key exists, the Keystore
 * can't be asked, a runtime error from the provider - is passed on unchanged, because the data may
 * still be readable on the next try.
 */
internal class RecoveringKeysetLoader<T>(
    private val loadKeyset: () -> T,
    private val isKeysetStored: () -> Boolean,
    /** Whether the Keystore holds the master key; null when it can't be trusted to say. */
    private val masterKeyExists: () -> Boolean?,
    private val discardKeyset: () -> Unit,
    private val log: (String) -> Unit,
) {
    fun load(): T {
        val failure = try {
            return loadKeyset()
        } catch (error: GeneralSecurityException) {
            error
        } catch (error: IOException) {
            error
        }
        if (!isKeysetStored() || masterKeyExists() != false) throw failure
        log(KEYSET_RESET_MESSAGE)
        discardKeyset()
        return loadKeyset()
    }

    companion object {
        const val KEYSET_RESET_MESSAGE =
            "Saved passwords and keys can't be decrypted: their Android Keystore key is missing " +
                "(app data restored or moved from another device). Starting a new secret store: " +
                "enter SSH passwords and keys again and re-import routes added by hand; " +
                "synced routes come back with their next sync."
    }
}

/**
 * Whether the Android Keystore holds [alias]: true or false only when the Keystore demonstrably
 * answered, null otherwise. See [keystoreHasKey] for how "absent" is told apart from a failure.
 */
internal fun androidKeystoreHasKey(alias: String): Boolean? {
    val keystore = runCatching { AndroidKeystoreLookup() }.getOrNull() ?: return null
    return keystoreHasKey(alias, keystore) { Thread.sleep(KEYSTORE_RECHECK_DELAY_MS) }
}

/** The Keystore calls [keystoreHasKey] makes. */
internal interface KeystoreLookup {
    /** False for an absent alias and, as Android's provider answers, for any Keystore error too. */
    fun containsAlias(alias: String): Boolean

    fun createProbeKey(alias: String)

    fun deleteEntry(alias: String)
}

/**
 * Whether [keystore] holds [alias]; null when its answer can't be trusted.
 *
 * `containsAlias` can't tell "absent" from "couldn't ask": AndroidKeyStoreSpi answers false for any
 * Keystore error - before Android 12 whenever its service is unreachable, and since then for every
 * error but KEY_NOT_FOUND too, which it only logs. A wrong "absent" here wipes every saved secret,
 * so it counts only when all checks agree. A probe key is created; then, in each of
 * [KEYSTORE_ABSENCE_CHECKS] rounds a [pause] apart, the key is looked up first and the probe right
 * after it. A Keystore that fails from the key's lookup on misses the probe, which gives no
 * verdict; a failure of the key's lookup alone has to happen again after the pause to count.
 */
internal fun keystoreHasKey(
    alias: String,
    keystore: KeystoreLookup,
    pause: () -> Unit,
): Boolean? = runCatching {
    if (keystore.containsAlias(alias)) return@runCatching true
    val probeAlias = "$alias$KEYSTORE_PROBE_SUFFIX"
    keystore.createProbeKey(probeAlias)
    try {
        repeat(KEYSTORE_ABSENCE_CHECKS) { round ->
            if (round > 0) pause()
            if (keystore.containsAlias(alias)) return@runCatching true
            if (!keystore.containsAlias(probeAlias)) return@runCatching null
        }
        false
    } finally {
        keystore.deleteEntry(probeAlias)
    }
}.getOrNull()

private class AndroidKeystoreLookup : KeystoreLookup {
    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    override fun containsAlias(alias: String): Boolean = keyStore.containsAlias(alias)

    override fun createProbeKey(alias: String) {
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    override fun deleteEntry(alias: String) {
        keyStore.deleteEntry(alias)
    }
}

internal const val KEYSTORE_ABSENCE_CHECKS = 2
internal const val KEYSTORE_PROBE_SUFFIX = "_probe"

// Long enough for a call that failed on a passing hiccup - a binder error, the Keystore service
// restarting - to go through again. It is only waited once a stored keyset already failed to load.
private const val KEYSTORE_RECHECK_DELAY_MS = 500L
private const val ANDROID_KEYSTORE = "AndroidKeyStore"
