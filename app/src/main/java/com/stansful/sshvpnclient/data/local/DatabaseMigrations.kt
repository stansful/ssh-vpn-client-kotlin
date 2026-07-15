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

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `proxy_profiles` ADD COLUMN `isPinned` INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_proxy_profiles_isPinned` " +
                "ON `proxy_profiles` (`isPinned`)",
        )
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `smart_proxy_profiles` (
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
                `isPinned` INTEGER NOT NULL,
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
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_smart_proxy_profiles_fingerprint` " +
                "ON `smart_proxy_profiles` (`fingerprint`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_smart_proxy_profiles_isSelected` " +
                "ON `smart_proxy_profiles` (`isSelected`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_smart_proxy_profiles_isPinned` " +
                "ON `smart_proxy_profiles` (`isPinned`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_smart_proxy_profiles_source_sourceUrl` " +
                "ON `smart_proxy_profiles` (`source`, `sourceUrl`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS " +
                "`index_smart_proxy_profiles_isStale_lastTestStatus_lastLatencyMs` " +
                "ON `smart_proxy_profiles` (`isStale`, `lastTestStatus`, `lastLatencyMs`)",
        )
    }
}
