package com.stansful.sshvpnclient.domain.usecase.key

import com.stansful.sshvpnclient.domain.model.SshPrivateKey
import com.stansful.sshvpnclient.domain.model.ValidationError

class SshPrivateKeyValidator {
    fun validate(key: SshPrivateKey): List<ValidationError> = buildList {
        if (key.name.isBlank()) {
            add(ValidationError("name", "Key name is required"))
        }
        if (key.privateKey.isBlank()) {
            add(ValidationError("privateKey", "Private key is required"))
        } else if (!looksLikePrivateKey(key.privateKey)) {
            add(ValidationError("privateKey", "Invalid private key format"))
        }
    }

    fun looksLikePrivateKey(value: String): Boolean {
        val trimmed = value.trim()
        return (
            trimmed.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----") &&
                trimmed.endsWith("-----END OPENSSH PRIVATE KEY-----")
            ) || (
            trimmed.startsWith("-----BEGIN RSA PRIVATE KEY-----") &&
                trimmed.endsWith("-----END RSA PRIVATE KEY-----")
            )
    }
}
