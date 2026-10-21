package com.stansful.sshvpnclient.data.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The "asked for the notification permission" flag survives process restarts (a new repository). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class NotificationPermissionFlagTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `a fresh install has not asked yet`() {
        assertFalse(SharedPreferencesAppSettingsRepository(app).settings.value.notificationPermissionRequested)
    }

    @Test
    fun `the flag is published at once and persisted`() {
        val repository = SharedPreferencesAppSettingsRepository(app)

        repository.setNotificationPermissionRequested(true)

        assertTrue(repository.settings.value.notificationPermissionRequested)
        assertTrue(SharedPreferencesAppSettingsRepository(app).settings.value.notificationPermissionRequested)
    }
}
