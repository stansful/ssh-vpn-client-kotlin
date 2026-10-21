package com.stansful.sshvpnclient.ui.shell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPermissionPolicyTest {
    @Test
    fun `asks on android 13 and later while not granted and never asked`() {
        assertTrue(shouldRequestNotificationPermission(sdkInt = 33, granted = false, alreadyRequested = false))
        assertTrue(shouldRequestNotificationPermission(sdkInt = 37, granted = false, alreadyRequested = false))
    }

    @Test
    fun `never asks before android 13 where the permission is granted at install`() {
        assertFalse(shouldRequestNotificationPermission(sdkInt = 26, granted = false, alreadyRequested = false))
        assertFalse(shouldRequestNotificationPermission(sdkInt = 32, granted = false, alreadyRequested = false))
    }

    @Test
    fun `does not ask when the permission is already granted`() {
        assertFalse(shouldRequestNotificationPermission(sdkInt = 34, granted = true, alreadyRequested = false))
    }

    @Test
    fun `asks at most once per install so a denial is final`() {
        assertFalse(shouldRequestNotificationPermission(sdkInt = 34, granted = false, alreadyRequested = true))
    }
}
