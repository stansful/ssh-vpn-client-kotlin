package com.stansful.sshvpnclient.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppUpdateDownloadStateTest {
    @Test
    fun `calculates download progress from transferred bytes`() {
        val state = AppUpdateDownloadState.Downloading(
            versionName = "2.2.0",
            downloadedBytes = 25L,
            totalBytes = 100L,
        )

        assertEquals(0.25f, state.progressFraction)
        assertEquals(25, state.progressPercent)
    }

    @Test
    fun `clamps progress when DownloadManager reports excess bytes`() {
        val state = AppUpdateDownloadState.Downloading(
            versionName = "2.2.0",
            downloadedBytes = 120L,
            totalBytes = 100L,
        )

        assertEquals(1f, state.progressFraction)
        assertEquals(100, state.progressPercent)
    }

    @Test
    fun `keeps progress indeterminate until total size is known`() {
        val state = AppUpdateDownloadState.Downloading(
            versionName = "2.2.0",
            downloadedBytes = 10L,
        )

        assertNull(state.progressFraction)
        assertNull(state.progressPercent)
    }
}
