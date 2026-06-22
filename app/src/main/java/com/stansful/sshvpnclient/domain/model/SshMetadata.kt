package com.stansful.sshvpnclient.domain.model

data class SshConfigSummary(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    val authType: AuthType,
    val privateKeyId: String?,
    val keyName: String?,
    val fingerprint: String?,
    val keepAliveIntervalSec: Int,
    val enableUdpForwarding: Boolean,
    val note: String?,
    val isSelected: Boolean,
    val updatedAt: Long,
)

data class SshPrivateKeySummary(
    val id: String,
    val name: String,
    val note: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val usageCount: Int,
)
