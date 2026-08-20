package com.stansful.sshvpnclient.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migration tests for [AppDatabase].
 *
 * These exist because a broken migration is otherwise only discovered in production, where the
 * failure mode is a user losing their stored SSH configurations and Tink-encrypted secrets.
 *
 * ## Why the v1 database is built from raw DDL
 *
 * `exportSchema` was `false` until version 4, so Room never wrote `1.json`, `2.json` or `3.json`
 * and cannot generate them retroactively. `MigrationTestHelper.createDatabase(name, 1)` would
 * therefore fail with "Cannot find the schema file in the assets folder".
 *
 * The historical schema is instead recreated from explicit DDL in [createVersion1Schema]. That is
 * safe to assert against because migrations 1→2, 2→3 and 3→4 never touch `ssh_configs` or
 * `ssh_private_keys`, so their v1 shape is identical to their current shape.
 *
 * [migrateAll1To4_matchesExportedSchema] additionally validates the end state against Room's own
 * exported `4.json` and therefore requires `app/schemas` to be committed.
 *
 * When [AppDatabase] gains a version: add the migration to [ALL_MIGRATIONS], add a
 * `migrate<N>To<N+1>_...` test asserting that *data* survives, and commit the new schema file.
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    private var openedDatabase: SupportSQLiteDatabase? = null

    @After
    fun tearDown() {
        openedDatabase?.takeIf { it.isOpen }?.close()
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(TEST_DB)
    }

    @Test
    fun migrate1To2_createsProxyProfilesAndKeepsSshData() {
        val db = openVersion1Database()
        db.insertSshPrivateKey(id = "key-1", name = "laptop")
        db.insertSshConfig(id = "config-1", name = "home", privateKeyId = "key-1")

        MIGRATION_1_2.migrate(db)

        assertEquals("home", db.selectString("SELECT name FROM ssh_configs WHERE id = 'config-1'"))
        assertEquals("laptop", db.selectString("SELECT name FROM ssh_private_keys WHERE id = 'key-1'"))
        assertTrue("proxy_profiles table is missing", db.hasTable("proxy_profiles"))
        listOf(
            "index_proxy_profiles_fingerprint",
            "index_proxy_profiles_isSelected",
            "index_proxy_profiles_source_sourceUrl",
        ).forEach { index -> assertTrue("missing index $index", db.hasIndex(index)) }
    }

    @Test
    fun migrate2To3_addsIsPinnedDefaultingToZeroForExistingRows() {
        val db = openVersion1Database()
        MIGRATION_1_2.migrate(db)
        db.insertProxyProfile(id = "proxy-1", fingerprint = "fp-1")

        MIGRATION_2_3.migrate(db)

        assertEquals(
            "existing proxy rows must survive with isPinned defaulted to 0",
            0L,
            db.selectLong("SELECT isPinned FROM proxy_profiles WHERE id = 'proxy-1'"),
        )
        assertTrue(db.hasIndex("index_proxy_profiles_isPinned"))
    }

    @Test
    fun migrate3To4_createsSmartProxyProfilesAndLeavesPublicRoutesUntouched() {
        val db = openVersion1Database()
        MIGRATION_1_2.migrate(db)
        MIGRATION_2_3.migrate(db)
        db.insertProxyProfile(id = "proxy-1", fingerprint = "fp-1")

        MIGRATION_3_4.migrate(db)

        assertTrue("smart_proxy_profiles table is missing", db.hasTable("smart_proxy_profiles"))
        assertEquals(
            "the public-routes table must not be touched by the smart-connect migration",
            1L,
            db.selectLong("SELECT COUNT(*) FROM proxy_profiles"),
        )
        listOf(
            "index_smart_proxy_profiles_fingerprint",
            "index_smart_proxy_profiles_isSelected",
            "index_smart_proxy_profiles_isPinned",
            "index_smart_proxy_profiles_source_sourceUrl",
            "index_smart_proxy_profiles_isStale_lastTestStatus_lastLatencyMs",
        ).forEach { index -> assertTrue("missing index $index", db.hasIndex(index)) }
    }

    @Test
    fun migrateAll1To4_preservesSecretIdsOfStoredCredentials() {
        val db = openVersion1Database()
        db.insertSshPrivateKey(id = "key-1", name = "laptop")
        db.insertSshConfig(id = "config-1", name = "home", privateKeyId = "key-1")

        ALL_MIGRATIONS.forEach { migration -> migration.migrate(db) }

        // Secret ids are the only link between Room rows and Tink-encrypted material.
        // Losing them silently orphans every stored password and private key.
        assertEquals(
            "secret-key-1",
            db.selectString("SELECT privateKeySecretId FROM ssh_private_keys WHERE id = 'key-1'"),
        )
        assertEquals(
            "key-1",
            db.selectString("SELECT privateKeyId FROM ssh_configs WHERE id = 'config-1'"),
        )
    }

    /**
     * Validates the migrated schema against Room's own exported `4.json`.
     *
     * This is the test that catches a migration whose hand-written DDL has drifted from the
     * entity definitions (a wrong column type, a missing index, a forgotten NOT NULL).
     */
    @Test
    fun migrateAll1To4_matchesExportedSchema() {
        openVersion1Database().use { db ->
            db.insertSshPrivateKey(id = "key-1", name = "laptop")
            db.insertSshConfig(id = "config-1", name = "home", privateKeyId = "key-1")
        }
        openedDatabase = null

        helper.runMigrationsAndValidate(TEST_DB, LATEST_VERSION, true, *ALL_MIGRATIONS).use { db ->
            assertEquals(
                1L,
                db.selectLong("SELECT COUNT(*) FROM ssh_configs WHERE id = 'config-1'"),
            )
        }
    }

    /**
     * Opens [TEST_DB] with the schema as it existed at database version 1, before
     * `proxy_profiles` and `smart_proxy_profiles` were introduced.
     */
    private fun openVersion1Database(): SupportSQLiteDatabase {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(TEST_DB)

        val callback = object : SupportSQLiteOpenHelper.Callback(VERSION_1) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                createVersion1Schema(db)
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                error("the fixture database must never be upgraded implicitly")
            }
        }
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(TEST_DB)
            .callback(callback)
            .build()

        return FrameworkSQLiteOpenHelperFactory()
            .create(configuration)
            .writableDatabase
            .also { openedDatabase = it }
    }

    private fun createVersion1Schema(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `ssh_configs` (
                `id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `host` TEXT NOT NULL,
                `port` INTEGER NOT NULL,
                `username` TEXT NOT NULL,
                `authType` TEXT NOT NULL,
                `passwordSecretId` TEXT,
                `privateKeyId` TEXT,
                `fingerprint` TEXT,
                `keepAliveIntervalSec` INTEGER NOT NULL,
                `enableUdpForwarding` INTEGER NOT NULL,
                `note` TEXT,
                `isSelected` INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_ssh_configs_privateKeyId` " +
                "ON `ssh_configs` (`privateKeyId`)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `ssh_private_keys` (
                `id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `privateKeySecretId` TEXT NOT NULL,
                `passphraseSecretId` TEXT,
                `note` TEXT,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
    }

    private fun SupportSQLiteDatabase.insertSshConfig(
        id: String,
        name: String,
        privateKeyId: String?,
    ) {
        execSQL(
            """
            INSERT INTO ssh_configs (
                id, name, host, port, username, authType, passwordSecretId, privateKeyId,
                fingerprint, keepAliveIntervalSec, enableUdpForwarding, note, isSelected,
                createdAt, updatedAt
            ) VALUES (?, ?, 'example.com', 22, 'root', 'PRIVATE_KEY', NULL, ?, NULL, 30, 0, NULL, 1, 1, 1)
            """.trimIndent(),
            arrayOf<Any?>(id, name, privateKeyId),
        )
    }

    private fun SupportSQLiteDatabase.insertSshPrivateKey(id: String, name: String) {
        execSQL(
            """
            INSERT INTO ssh_private_keys (
                id, name, privateKeySecretId, passphraseSecretId, note, createdAt, updatedAt
            ) VALUES (?, ?, ?, NULL, NULL, 1, 1)
            """.trimIndent(),
            arrayOf<Any?>(id, name, "secret-$id"),
        )
    }

    private fun SupportSQLiteDatabase.insertProxyProfile(id: String, fingerprint: String) {
        execSQL(
            """
            INSERT INTO proxy_profiles (
                id, name, protocol, host, port, transport, security, flow, source, sourceUrl,
                secretId, fingerprint, isSelected, isStale, lastTestStatus, lastLatencyMs,
                lastTestAt, createdAt, updatedAt, lastSeenAt
            ) VALUES (?, 'profile', 'VLESS', 'example.com', 443, 'TCP', 'TLS', NULL, 'MANUAL',
                      NULL, ?, ?, 0, 0, 'UNKNOWN', NULL, NULL, 1, 1, 1)
            """.trimIndent(),
            arrayOf<Any?>(id, "secret-$id", fingerprint),
        )
    }

    private fun SupportSQLiteDatabase.hasTable(name: String): Boolean =
        selectLong("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = '$name'") == 1L

    private fun SupportSQLiteDatabase.hasIndex(name: String): Boolean =
        selectLong("SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = '$name'") == 1L

    private fun SupportSQLiteDatabase.selectLong(sql: String): Long =
        query(sql).use { cursor ->
            assertTrue("query returned no rows: $sql", cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun SupportSQLiteDatabase.selectString(sql: String): String? =
        query(sql).use { cursor ->
            assertTrue("query returned no rows: $sql", cursor.moveToFirst())
            if (cursor.isNull(0)) null else cursor.getString(0)
        }

    private companion object {
        const val TEST_DB = "migration-test.db"
        const val VERSION_1 = 1
        const val LATEST_VERSION = 4

        val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
    }
}
