@file:Suppress("DEPRECATION")

package com.stansful.sshvpnclient.data.secret

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TinkSecretStorage(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SecretStorage {
    private val appContext = context.applicationContext
    private val migrationLock = Any()

    private val preferences: SharedPreferences by lazy {
        appContext.getSharedPreferences(SECRET_FILE_NAME, Context.MODE_PRIVATE)
    }

    private val migrationPreferences: SharedPreferences by lazy {
        appContext.getSharedPreferences(MIGRATION_FILE_NAME, Context.MODE_PRIVATE)
    }

    private val aead: Aead by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(appContext, KEYSET_NAME, KEYSET_PREF_FILE_NAME)
            .withKeyTemplate(KeyTemplates.get(TINK_AEAD_TEMPLATE))
            .withMasterKeyUri(ANDROID_KEYSTORE_URI)
            .build()
            .keysetHandle
            .getPrimitive(Aead::class.java)
    }

    override suspend fun saveSecret(id: String, value: String) {
        withContext(ioDispatcher) {
            ensureLegacyMigrationAttempted()
            saveEncrypted(id, value)
            deleteLegacySecret(id)
        }
    }

    override suspend fun saveSecrets(values: Map<String, String>) {
        if (values.isEmpty()) return
        withContext(ioDispatcher) {
            ensureLegacyMigrationAttempted()
            saveEncrypted(values)
            values.keys.forEach(::deleteLegacySecret)
        }
    }

    override suspend fun getSecret(id: String): String? {
        return withContext(ioDispatcher) {
            ensureLegacyMigrationAttempted()
            val encryptedValue = preferences.getString(id, null)
            if (encryptedValue != null) {
                return@withContext decrypt(id, encryptedValue)
            }

            val legacyValue = readLegacySecret(id) ?: return@withContext null
            saveEncrypted(id, legacyValue)
            deleteLegacySecret(id)
            legacyValue
        }
    }

    override suspend fun getSecrets(ids: Collection<String>): Map<String, String> {
        if (ids.isEmpty()) return emptyMap()
        return withContext(ioDispatcher) {
            ensureLegacyMigrationAttempted()
            val uniqueIds = ids.distinct()
            val secrets = LinkedHashMap<String, String>(uniqueIds.size)
            uniqueIds.forEach { id ->
                val encryptedValue = preferences.getString(id, null) ?: return@forEach
                decrypt(id, encryptedValue)?.let { value -> secrets[id] = value }
            }

            if (!migrationPreferences.getBoolean(KEY_LEGACY_MIGRATION_COMPLETE, false)) {
                val missingIds = uniqueIds.filterNot(secrets::containsKey)
                val legacyPreferences = missingIds
                    .takeIf { it.isNotEmpty() }
                    ?.let { runCatching { createLegacyPreferences(appContext) }.getOrNull() }
                val legacySecrets = legacyPreferences?.let { legacy ->
                    buildMap {
                        missingIds.forEach { id ->
                            legacy.getString(id, null)?.let { value -> put(id, value) }
                        }
                    }
                }.orEmpty()
                if (legacySecrets.isNotEmpty()) {
                    saveEncrypted(legacySecrets)
                    legacyPreferences?.edit {
                        legacySecrets.keys.forEach { id -> remove(id) }
                    }
                    secrets.putAll(legacySecrets)
                }
            }
            secrets
        }
    }

    override suspend fun deleteSecret(id: String) {
        withContext(ioDispatcher) {
            ensureLegacyMigrationAttempted()
            preferences.edit {
                remove(id)
            }
            deleteLegacySecret(id)
        }
    }

    override suspend fun deleteSecrets(ids: Collection<String>) {
        if (ids.isEmpty()) return
        withContext(ioDispatcher) {
            ensureLegacyMigrationAttempted()
            preferences.edit {
                ids.forEach { id -> remove(id) }
            }
            ids.forEach(::deleteLegacySecret)
        }
    }

    @SuppressLint("UseKtx") // The boolean commit result is required before writing the completion marker.
    private fun ensureLegacyMigrationAttempted() {
        if (migrationPreferences.getBoolean(KEY_LEGACY_MIGRATION_COMPLETE, false)) return

        synchronized(migrationLock) {
            if (migrationPreferences.getBoolean(KEY_LEGACY_MIGRATION_COMPLETE, false)) return

            if (!legacyPreferenceFile().exists()) {
                markLegacyMigrationComplete(migratedCount = 0)
                return
            }

            val legacyPreferences = runCatching { createLegacyPreferences(appContext) }
                .getOrElse { error ->
                    markLegacyMigrationFailed(error)
                    return
                }

            val migratedCount = runCatching {
                val legacySecrets = buildMap {
                    legacyPreferences.all.forEach { (id, value) ->
                        if (!id.isLegacyInternalKey() && value is String) {
                            put(id, value)
                        }
                    }
                }
                saveEncrypted(legacySecrets)
                val legacyEditor = legacyPreferences.edit()
                legacySecrets.keys.forEach { id -> legacyEditor.remove(id) }
                check(legacyEditor.commit()) { "Could not remove migrated legacy secrets" }
                legacySecrets.size
            }.getOrElse { error ->
                markLegacyMigrationFailed(error)
                return
            }
            markLegacyMigrationComplete(migratedCount)
        }
    }

    private fun saveEncrypted(id: String, value: String) {
        saveEncrypted(mapOf(id to value))
    }

    private fun saveEncrypted(values: Map<String, String>) {
        if (values.isEmpty()) return
        val editor = preferences.edit()
        values.forEach { (id, value) ->
            editor.putString(id, encrypt(id, value))
        }
        val committed = editor.commit()
        check(committed) { "Could not persist encrypted secret data" }
    }

    private fun encrypt(id: String, value: String): String {
        val plaintext = value.toByteArray(StandardCharsets.UTF_8)
        return try {
            val ciphertext = aead.encrypt(plaintext, associatedData(id))
            Base64.getEncoder().encodeToString(ciphertext)
        } finally {
            plaintext.fill(0)
        }
    }

    private fun decrypt(id: String, encryptedValue: String): String? {
        return runCatching {
            val ciphertext = Base64.getDecoder().decode(encryptedValue)
            val plaintext = aead.decrypt(ciphertext, associatedData(id))
            try {
                String(plaintext, StandardCharsets.UTF_8)
            } finally {
                plaintext.fill(0)
            }
        }.getOrNull()
    }

    private fun readLegacySecret(id: String): String? {
        if (migrationPreferences.getBoolean(KEY_LEGACY_MIGRATION_COMPLETE, false)) return null
        if (!legacyPreferenceFile().exists()) return null
        return runCatching {
            createLegacyPreferences(appContext).getString(id, null)
        }.getOrNull()
    }

    private fun deleteLegacySecret(id: String) {
        if (migrationPreferences.getBoolean(KEY_LEGACY_MIGRATION_COMPLETE, false)) return
        if (!legacyPreferenceFile().exists()) return
        runCatching {
            createLegacyPreferences(appContext).edit {
                remove(id)
            }
        }
    }

    private fun associatedData(id: String): ByteArray {
        return "$ASSOCIATED_DATA_PREFIX$id".toByteArray(StandardCharsets.UTF_8)
    }

    private fun legacyPreferenceFile(): File {
        return File(appContext.applicationInfo.dataDir, "shared_prefs/$LEGACY_SECRET_FILE_NAME.xml")
    }

    private fun markLegacyMigrationComplete(migratedCount: Int) {
        val committed = migrationPreferences.edit()
            .putBoolean(KEY_LEGACY_MIGRATION_COMPLETE, true)
            .putInt(KEY_LEGACY_MIGRATION_COUNT, migratedCount)
            .remove(KEY_LEGACY_MIGRATION_ERROR)
            .commit()
        check(committed) { "Could not persist secret migration state" }
    }

    private fun markLegacyMigrationFailed(error: Throwable) {
        migrationPreferences.edit {
            putString(
                KEY_LEGACY_MIGRATION_ERROR,
                error.message ?: error::class.java.simpleName,
            )
        }
    }

    private companion object {
        const val SECRET_FILE_NAME = "ssh_vpn_tink_secrets"
        const val KEYSET_PREF_FILE_NAME = "ssh_vpn_tink_keyset"
        const val KEYSET_NAME = "ssh_vpn_secret_keyset"
        const val MIGRATION_FILE_NAME = "ssh_vpn_secret_migration"
        const val KEY_LEGACY_MIGRATION_COMPLETE = "legacy_migration_complete"
        const val KEY_LEGACY_MIGRATION_COUNT = "legacy_migration_count"
        const val KEY_LEGACY_MIGRATION_ERROR = "legacy_migration_error"
        const val LEGACY_SECRET_FILE_NAME = "ssh_vpn_secrets"
        const val ANDROID_KEYSTORE_URI = "android-keystore://ssh_vpn_secret_master_key"
        const val TINK_AEAD_TEMPLATE = "AES256_GCM"
        const val ASSOCIATED_DATA_PREFIX = "ssh-vpn-secret:"
    }
}

private fun createLegacyPreferences(context: Context): SharedPreferences {
    val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    return EncryptedSharedPreferences.create(
        context,
        "ssh_vpn_secrets",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
}

private fun String.isLegacyInternalKey(): Boolean {
    return startsWith("__androidx_security_crypto_")
}
