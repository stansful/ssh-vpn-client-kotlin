package com.stansful.sshvpnclient.data.config

import com.stansful.sshvpnclient.data.secret.SecretStorage
import com.stansful.sshvpnclient.domain.model.AuthType
import com.stansful.sshvpnclient.domain.model.SshConfig

suspend fun SshConfigEntity.toDomain(secretStorage: SecretStorage): SshConfig {
    return SshConfig(
        id = id,
        name = name,
        host = host,
        port = port,
        username = username,
        authType = AuthType.valueOf(authType),
        password = passwordSecretId?.let { secretStorage.getSecret(it) },
        privateKeyId = privateKeyId,
        fingerprint = fingerprint,
        keepAliveIntervalSec = keepAliveIntervalSec,
        enableUdpForwarding = enableUdpForwarding,
        note = note,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
