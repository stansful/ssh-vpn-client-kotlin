package com.stansful.sshvpnclient.data.key

data class SshPrivateKeySummaryRow(
    val id: String,
    val name: String,
    val note: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val usageCount: Int,
)
