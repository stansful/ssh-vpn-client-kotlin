package com.stansful.sshvpnclient.data.config

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stansful.sshvpnclient.data.local.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The real SQL of the host key queries, on an in-memory Room database. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class SshConfigDaoFingerprintTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: SshConfigDao

    @Before
    fun open() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Application>(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.sshConfigDao()
    }

    @After
    fun close() {
        database.close()
    }

    @Test
    fun `pin if absent fills only an empty fingerprint and keeps updatedAt`() = runBlocking {
        dao.upsert(configEntity(id = "unpinned", fingerprint = null))
        dao.upsert(configEntity(id = "blank", fingerprint = " "))
        dao.upsert(configEntity(id = "user", fingerprint = USER_KEY))

        assertEquals(1, dao.pinFingerprintIfAbsent("unpinned", FIRST_KEY))
        assertEquals(1, dao.pinFingerprintIfAbsent("blank", FIRST_KEY))
        assertEquals(0, dao.pinFingerprintIfAbsent("user", FIRST_KEY))
        assertEquals(0, dao.pinFingerprintIfAbsent("missing", FIRST_KEY))
        // A second first-use pin (another key) does not replace the first one either.
        assertEquals(0, dao.pinFingerprintIfAbsent("unpinned", OTHER_KEY))

        assertEquals(FIRST_KEY, dao.getById("unpinned")?.fingerprint)
        assertEquals(FIRST_KEY, dao.getById("blank")?.fingerprint)
        assertEquals(USER_KEY, dao.getById("user")?.fingerprint)
        assertEquals(configEntity(id = "unpinned", fingerprint = FIRST_KEY), dao.getById("unpinned"))
    }

    @Test
    fun `update fingerprint replaces a saved one`() = runBlocking {
        dao.upsert(configEntity(id = "user", fingerprint = USER_KEY))

        assertEquals(1, dao.updateFingerprint("user", OTHER_KEY))
        assertEquals(0, dao.updateFingerprint("missing", OTHER_KEY))

        assertEquals(OTHER_KEY, dao.getById("user")?.fingerprint)
    }

    @Test
    fun `upsert keeping the fingerprint writes the row but keeps the saved key`() = runBlocking {
        dao.upsert(configEntity(id = "pinned", fingerprint = FIRST_KEY))
        dao.upsert(configEntity(id = "unpinned", fingerprint = null))

        dao.upsertKeepingFingerprint(configEntity(id = "pinned", fingerprint = null).copy(note = "Rack 2"))
        dao.upsertKeepingFingerprint(configEntity(id = "unpinned", fingerprint = OTHER_KEY).copy(note = "Rack 3"))

        assertEquals(configEntity(id = "pinned", fingerprint = FIRST_KEY).copy(note = "Rack 2"), dao.getById("pinned"))
        assertEquals(configEntity(id = "unpinned", fingerprint = null).copy(note = "Rack 3"), dao.getById("unpinned"))
    }

    @Test
    fun `upsert keeping the fingerprint does not bring back a deleted server`() = runBlocking {
        dao.upsertKeepingFingerprint(configEntity(id = "deleted", fingerprint = FIRST_KEY))

        assertNull(dao.getById("deleted"))
    }

    private companion object {
        const val FIRST_KEY = "SHA256:47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU"
        const val OTHER_KEY = "SHA256:LCa0a2j/xo/5m0U8HTBBNBNCLXBkg7+g+YpeiGJm564"
        const val USER_KEY = "SHA256:atzfmdcrqQzoXZfKHLarePDyMw/G5NYfJ3h1eHUVS9g"
    }
}
