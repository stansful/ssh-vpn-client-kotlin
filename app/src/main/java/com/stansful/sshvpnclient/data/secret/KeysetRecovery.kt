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
 * Whether the Android Keystore holds [alias]. True or false only when the Keystore demonstrably
 * answers, null otherwise: before Android 12 it reports any key as absent while its service is
 * unreachable, so "absent" only counts once a probe key could be created and found.
 */
internal fun androidKeystoreHasKey(alias: String): Boolean? = runCatching {
    val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    if (keyStore.containsAlias(alias)) return@runCatching true
    val probeAlias = "$alias$KEYSTORE_PROBE_SUFFIX"
    KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
        init(
            KeyGenParameterSpec.Builder(probeAlias, KeyProperties.PURPOSE_ENCRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        generateKey()
    }
    try {
        val keystoreAnswers = keyStore.containsAlias(probeAlias)
        val present = keyStore.containsAlias(alias)
        if (keystoreAnswers) present else null
    } finally {
        keyStore.deleteEntry(probeAlias)
    }
}.getOrNull()

private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val KEYSTORE_PROBE_SUFFIX = "_probe"
