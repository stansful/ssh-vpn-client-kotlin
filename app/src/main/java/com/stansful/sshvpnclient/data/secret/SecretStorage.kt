package com.stansful.sshvpnclient.data.secret

interface SecretStorage {
    suspend fun saveSecret(id: String, value: String)
    suspend fun getSecret(id: String): String?
    suspend fun deleteSecret(id: String)
}
