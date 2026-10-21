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

    /**
     * Throws when the store itself can't be read right now, e.g. its keyset failed to load. [getSecret]
     * and [getSecrets] report that as missing values, just like a single value that doesn't decrypt;
     * a caller that replaces or deletes what it can't read asks this first. A store that is always
     * readable keeps the default.
     */
    suspend fun checkAvailable() = Unit
}

/**
 * The ids among [ids] whose secret can be read right now. A row can outlive its secret: the store
 * starts over when its Keystore key is gone, and a ciphertext can stop decrypting. Only a value the
 * store failed to read is left out: a store that can't be read at all throws ([SecretStorage.checkAvailable])
 * instead of calling every value unreadable. Read in batches, so checking a large catalog never
 * holds all of its values at once.
 */
suspend fun SecretStorage.readableSecretIds(ids: Collection<String>): Set<String> {
    if (ids.isEmpty()) return emptySet()
    checkAvailable()
    val readable = HashSet<String>(ids.size)
    ids.distinct().chunked(READABLE_CHECK_BATCH_SIZE).forEach { batch ->
        readable += getSecrets(batch).keys
    }
    return readable
}

private const val READABLE_CHECK_BATCH_SIZE = 256
