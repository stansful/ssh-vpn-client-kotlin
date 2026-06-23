package com.stansful.sshvpnclient.data.proxy

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "proxy_profiles",
    indices = [
        Index(value = ["fingerprint"], unique = true),
        Index(value = ["isSelected"]),
        Index(value = ["isPinned"]),
        Index(value = ["source", "sourceUrl"]),
    ],
)
data class ProxyProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val protocol: String,
    val host: String,
    val port: Int,
    val transport: String,
    val security: String,
    val flow: String?,
    val source: String,
    val sourceUrl: String?,
    val secretId: String,
    val fingerprint: String,
    val isSelected: Boolean,
    val isPinned: Boolean,
    val isStale: Boolean,
    val lastTestStatus: String,
    val lastLatencyMs: Long?,
    val lastTestAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    val lastSeenAt: Long,
)
