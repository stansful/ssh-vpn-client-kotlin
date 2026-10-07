package com.stansful.sshvpnclient.ui.keys

import com.stansful.sshvpnclient.domain.model.SshPrivateKeySummary
import com.stansful.sshvpnclient.domain.usecase.key.GetSshPrivateKeyByIdUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Private key formats the app accepts (see `SshPrivateKeyValidator`). */
enum class SshKeyFormat(val label: String) {
    OPENSSH("OpenSSH"),
    RSA_PEM("RSA PEM"),
}

/** What the key cards and the key picker show about a stored key: its format and passphrase. */
data class SshKeyTraits(
    val format: SshKeyFormat?,
    val hasPassphrase: Boolean,
)

/** Format of [privateKey] read from its PEM markers, or null when it is neither accepted format. */
fun sshKeyFormatOf(privateKey: String): SshKeyFormat? {
    val trimmed = privateKey.trim()
    return when {
        trimmed.startsWith(OPENSSH_HEADER) && trimmed.endsWith(OPENSSH_FOOTER) -> SshKeyFormat.OPENSSH
        trimmed.startsWith(RSA_HEADER) && trimmed.endsWith(RSA_FOOTER) -> SshKeyFormat.RSA_PEM
        else -> null
    }
}

/**
 * Reads [SshKeyTraits] for listed keys. The secrets are decrypted once per key revision
 * ([SshPrivateKeySummary.updatedAt]) and dropped right away; only the derived traits are kept.
 */
class SshKeyTraitsReader(
    private val getSshPrivateKeyByIdUseCase: GetSshPrivateKeyByIdUseCase,
) {
    private val mutex = Mutex()
    private val cache = mutableMapOf<String, CachedTraits>()

    suspend fun read(keys: List<SshPrivateKeySummary>): Map<String, SshKeyTraits> = mutex.withLock {
        cache.keys.retainAll(keys.map { it.id }.toSet())
        keys.mapNotNull { summary ->
            val cached = cache[summary.id]?.takeIf { it.updatedAt == summary.updatedAt }
            val traits = cached?.traits ?: load(summary.id)?.also { traits ->
                cache[summary.id] = CachedTraits(summary.updatedAt, traits)
            }
            traits?.let { summary.id to it }
        }.toMap()
    }

    private suspend fun load(id: String): SshKeyTraits? {
        return try {
            getSshPrivateKeyByIdUseCase(id)?.let { key ->
                SshKeyTraits(
                    format = sshKeyFormatOf(key.privateKey),
                    hasPassphrase = !key.passphrase.isNullOrBlank(),
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Unreadable secret (e.g. a reset keystore): the card simply shows no format tag.
            null
        }
    }

    private data class CachedTraits(val updatedAt: Long, val traits: SshKeyTraits)
}

private const val OPENSSH_HEADER = "-----BEGIN OPENSSH PRIVATE KEY-----"
private const val OPENSSH_FOOTER = "-----END OPENSSH PRIVATE KEY-----"
private const val RSA_HEADER = "-----BEGIN RSA PRIVATE KEY-----"
private const val RSA_FOOTER = "-----END RSA PRIVATE KEY-----"
