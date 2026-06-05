package com.stansful.sshvpnclient.data.config

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "ssh_configs",
    indices = [Index("privateKeyId")],
)
data class SshConfigEntity(
    @PrimaryKey val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    val authType: String,
    val passwordSecretId: String?,
    val privateKeyId: String?,
    val fingerprint: String?,
    val keepAliveIntervalSec: Int,
    val enableUdpForwarding: Boolean,
    val note: String?,
    val isSelected: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)
