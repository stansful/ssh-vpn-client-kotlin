package com.stansful.sshvpnclient.data.key

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ssh_private_keys")
data class SshPrivateKeyEntity(
    @PrimaryKey val id: String,
    val name: String,
    val privateKeySecretId: String,
    val passphraseSecretId: String?,
    val note: String?,
    val createdAt: Long,
    val updatedAt: Long,
)
