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

/**
 * The ids among [ids] whose secret can be read right now. A row can outlive its secret: the store
 * starts over when its Keystore key is gone, and a ciphertext can stop decrypting. Read in batches,
 * so checking a large catalog never holds all of its values at once.
 */
suspend fun SecretStorage.readableSecretIds(ids: Collection<String>): Set<String> {
    if (ids.isEmpty()) return emptySet()
    val readable = HashSet<String>(ids.size)
    ids.distinct().chunked(READABLE_CHECK_BATCH_SIZE).forEach { batch ->
        readable += getSecrets(batch).keys
    }
    return readable
}

private const val READABLE_CHECK_BATCH_SIZE = 256
