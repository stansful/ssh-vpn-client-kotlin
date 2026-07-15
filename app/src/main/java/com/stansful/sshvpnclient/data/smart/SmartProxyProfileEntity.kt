package com.stansful.sshvpnclient.data.smart

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Smart Connect owns a separate profile catalog. Keeping it in its own table prevents selection,
 * pin, health-status and remote-source refreshes from mutating the OpenSource catalog.
 */
@Entity(
    tableName = "smart_proxy_profiles",
    indices = [
        Index(value = ["fingerprint"], unique = true),
        Index(value = ["isSelected"]),
        Index(value = ["isPinned"]),
        Index(value = ["source", "sourceUrl"]),
        Index(value = ["isStale", "lastTestStatus", "lastLatencyMs"]),
    ],
)
data class SmartProxyProfileEntity(
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
