package com.stansful.sshvpnclient.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `proxy_profiles` (
                `id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `protocol` TEXT NOT NULL,
                `host` TEXT NOT NULL,
                `port` INTEGER NOT NULL,
                `transport` TEXT NOT NULL,
                `security` TEXT NOT NULL,
                `flow` TEXT,
                `source` TEXT NOT NULL,
                `sourceUrl` TEXT,
                `secretId` TEXT NOT NULL,
                `fingerprint` TEXT NOT NULL,
                `isSelected` INTEGER NOT NULL,
                `isStale` INTEGER NOT NULL,
                `lastTestStatus` TEXT NOT NULL,
                `lastLatencyMs` INTEGER,
                `lastTestAt` INTEGER,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                `lastSeenAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_proxy_profiles_fingerprint` " +
                "ON `proxy_profiles` (`fingerprint`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_proxy_profiles_isSelected` " +
                "ON `proxy_profiles` (`isSelected`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_proxy_profiles_source_sourceUrl` " +
                "ON `proxy_profiles` (`source`, `sourceUrl`)",
        )
    }
}
