package com.stansful.sshvpnclient.data.secret

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class EncryptedPreferencesSecretStorage(
    context: Context,
) : SecretStorage {
    private val appContext = context.applicationContext

    private val preferences by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            appContext,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override suspend fun saveSecret(id: String, value: String) {
        preferences.edit().putString(id, value).apply()
    }

    override suspend fun getSecret(id: String): String? = preferences.getString(id, null)

    override suspend fun deleteSecret(id: String) {
        preferences.edit().remove(id).apply()
    }

    private companion object {
        const val FILE_NAME = "ssh_vpn_secrets"
    }
}
