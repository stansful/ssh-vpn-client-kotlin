package com.stansful.sshvpnclient.data.config

data class SshConfigSummaryRow(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    val authType: String,
    val privateKeyId: String?,
    val keyName: String?,
    val fingerprint: String?,
    val keepAliveIntervalSec: Int,
    val enableUdpForwarding: Boolean,
    val note: String?,
    val isSelected: Boolean,
    val updatedAt: Long,
)
