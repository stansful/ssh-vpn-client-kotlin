package com.stansful.sshvpnclient.domain.model

data class SshPrivateKey(
    val id: String,
    val name: String,
    val privateKey: String,
    val passphrase: String?,
    val note: String?,
    val createdAt: Long,
    val updatedAt: Long,
)
