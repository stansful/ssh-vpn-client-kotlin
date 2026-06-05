package com.stansful.sshvpnclient.domain.model

data class SshConfig(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    val authType: AuthType,
    val password: String?,
    val privateKeyId: String?,
    val fingerprint: String?,
    val keepAliveIntervalSec: Int,
    val enableUdpForwarding: Boolean,
    val note: String?,
    val createdAt: Long,
    val updatedAt: Long,
)
