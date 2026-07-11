package com.stansful.sshvpnclient.data.secret

interface SecretStorage {
    suspend fun saveSecret(id: String, value: String)

    suspend fun saveSecrets(values: Map<String, String>) {
        values.forEach { (id, value) -> saveSecret(id, value) }
    }

    suspend fun getSecret(id: String): String?

    suspend fun getSecrets(ids: Collection<String>): Map<String, String> {
        return buildMap {
            ids.forEach { id ->
                getSecret(id)?.let { value -> put(id, value) }
            }
        }
    }

    suspend fun deleteSecret(id: String)

    suspend fun deleteSecrets(ids: Collection<String>) {
        ids.forEach { id -> deleteSecret(id) }
    }
}
