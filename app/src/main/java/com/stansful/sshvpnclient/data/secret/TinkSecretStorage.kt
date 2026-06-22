@file:Suppress("DEPRECATION")

package com.stansful.sshvpnclient.data.secret

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

    override suspend fun deleteSecret(id: String) {
        withContext(ioDispatcher) {
            ensureLegacyMigrationAttempted()
            preferences.edit {
                remove(id)
            }
            deleteLegacySecret(id)
        }
    }

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
                var count = 0
                legacyPreferences.all.forEach { (id, value) ->
                    if (id.isLegacyInternalKey() || value !is String) return@forEach
                    saveEncrypted(id, value)
                    legacyPreferences.edit {
                        remove(id)
                    }
                    count += 1
                }
                count
            }.getOrElse { error ->
                markLegacyMigrationFailed(error)
                return
            }
            markLegacyMigrationComplete(migratedCount)
        }
    }

    private fun saveEncrypted(id: String, value: String) {
        val ciphertext = aead.encrypt(value.toByteArray(StandardCharsets.UTF_8), associatedData(id))
        preferences.edit {
            putString(id, Base64.getEncoder().encodeToString(ciphertext))
        }
    }

    private fun decrypt(id: String, encryptedValue: String): String? {
        return runCatching {
            val ciphertext = Base64.getDecoder().decode(encryptedValue)
            val plaintext = aead.decrypt(ciphertext, associatedData(id))
            String(plaintext, StandardCharsets.UTF_8)
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
        migrationPreferences.edit {
            putBoolean(KEY_LEGACY_MIGRATION_COMPLETE, true)
            putInt(KEY_LEGACY_MIGRATION_COUNT, migratedCount)
            remove(KEY_LEGACY_MIGRATION_ERROR)
        }
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
