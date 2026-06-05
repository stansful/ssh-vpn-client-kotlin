package com.stansful.sshvpnclient.data.secret

object SecretIds {
    fun configPassword(configId: String) = "config-password-$configId"
    fun privateKey(keyId: String) = "private-key-$keyId"
    fun privateKeyPassphrase(keyId: String) = "private-key-passphrase-$keyId"
}
