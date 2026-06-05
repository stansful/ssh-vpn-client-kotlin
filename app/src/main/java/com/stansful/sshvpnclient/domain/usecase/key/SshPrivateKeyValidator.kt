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
        } else if (looksLikePublicKey(key.privateKey)) {
            add(ValidationError("privateKey", "Paste the private key file, not the .pub public key"))
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

    private fun looksLikePublicKey(value: String): Boolean {
        val trimmed = value.trim()
        return trimmed.startsWith("ssh-rsa ") ||
            trimmed.startsWith("ssh-ed25519 ") ||
            trimmed.startsWith("ecdsa-sha2-nistp256 ") ||
            trimmed.startsWith("ecdsa-sha2-nistp384 ") ||
            trimmed.startsWith("ecdsa-sha2-nistp521 ")
    }
}
