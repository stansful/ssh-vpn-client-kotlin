package com.stansful.sshvpnclient.data.secret

object SecretIds {
    fun configPassword(configId: String) = "config-password-$configId"
    fun privateKey(keyId: String) = "private-key-$keyId"
    fun privateKeyPassphrase(keyId: String) = "private-key-passphrase-$keyId"
    fun proxyProfile(profileId: String) = "proxy-profile-$profileId"
    fun proxyProfileRevision(profileId: String, revisionId: String) =
        "proxy-profile-$profileId-$revisionId"
    fun smartProxyProfileRevision(profileId: String, revisionId: String) =
        "smart-proxy-profile-$profileId-$revisionId"
}
