package com.stansful.sshvpnclient.data.key

import com.stansful.sshvpnclient.data.secret.SecretStorage
import com.stansful.sshvpnclient.domain.model.SshPrivateKey

suspend fun SshPrivateKeyEntity.toDomain(secretStorage: SecretStorage): SshPrivateKey {
    return SshPrivateKey(
        id = id,
        name = name,
        privateKey = secretStorage.getSecret(privateKeySecretId).orEmpty(),
        passphrase = passphraseSecretId?.let { secretStorage.getSecret(it) },
        note = note,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
