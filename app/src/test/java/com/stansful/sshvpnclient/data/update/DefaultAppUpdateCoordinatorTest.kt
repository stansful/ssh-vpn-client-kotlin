package com.stansful.sshvpnclient.data.update

import com.stansful.sshvpnclient.domain.model.AppUpdateCheckResult
import com.stansful.sshvpnclient.domain.model.AppUpdateDownloadState
import com.stansful.sshvpnclient.domain.model.AppUpdateInfo
import com.stansful.sshvpnclient.domain.repository.AppUpdateDownloader
import com.stansful.sshvpnclient.domain.repository.AppUpdateRepository
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultAppUpdateCoordinatorTest {
    @Test
    fun `automatic check starts without requiring the ssh tab view model`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val update = testUpdate()
        val repository = FakeRepository(AppUpdateCheckResult.Available(update))

        val coordinator = DefaultAppUpdateCoordinator(
            repository = repository,
            downloader = FakeDownloader(),
            applicationScope = scope,
            automaticCheckDelayMs = 0L,
        )

        assertEquals(1, repository.checkCount)
        assertEquals(update, coordinator.state.value.availableUpdate)
        scope.cancel()
    }

    @Test
    fun `available update is kept in process wide state until an action is taken`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val update = testUpdate()
        val coordinator = DefaultAppUpdateCoordinator(
            repository = FakeRepository(AppUpdateCheckResult.Available(update)),
            downloader = FakeDownloader(),
            applicationScope = scope,
            automaticCheckDelayMs = null,
        )

        coordinator.checkForUpdates()

        assertFalse(coordinator.state.value.isChecking)
        assertEquals(update, coordinator.state.value.availableUpdate)
        assertNull(coordinator.state.value.statusMessage)
        scope.cancel()
    }

    @Test
    fun `download is started once and shared state follows downloader progress`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val update = testUpdate()
        val downloader = FakeDownloader()
        val coordinator = DefaultAppUpdateCoordinator(
            repository = FakeRepository(AppUpdateCheckResult.Available(update)),
            downloader = downloader,
            applicationScope = scope,
            automaticCheckDelayMs = null,
        )
        coordinator.checkForUpdates()

        coordinator.downloadAvailableUpdate()
        coordinator.downloadAvailableUpdate()

        assertEquals(listOf(update), downloader.downloads)
        assertNull(coordinator.state.value.availableUpdate)
        assertEquals(
            AppUpdateDownloadState.Downloading(
                versionName = "2.5.8",
                downloadedBytes = 0L,
                totalBytes = update.apkSizeBytes,
            ),
            coordinator.state.value.downloadState,
        )
        scope.cancel()
    }

    @Test
    fun `manual check requested during automatic check is rerun with force`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repository = BlockingFirstRepository()
        val coordinator = DefaultAppUpdateCoordinator(
            repository = repository,
            downloader = FakeDownloader(),
            applicationScope = scope,
            automaticCheckDelayMs = null,
        )

        coordinator.checkForUpdates(manual = false)
        withTimeout(2_000L) { repository.firstCheckStarted.await() }
        coordinator.checkForUpdates(manual = true)
        repository.releaseFirstCheck.complete(Unit)
        withTimeout(2_000L) {
            while (
                repository.forceArguments.size < 2 ||
                coordinator.state.value.statusMessage != "shadow-ssh is up to date"
            ) {
                yield()
            }
        }

        assertEquals(listOf(false, true), repository.forceArguments.toList())
        assertEquals("shadow-ssh is up to date", coordinator.state.value.statusMessage)
        scope.cancel()
    }

    @Test
    fun `available dialog is suppressed while an update is downloading or ready`() {
        assertFalse(
            canPresentAvailableUpdate(
                AppUpdateDownloadState.Downloading(versionName = "2.5.8"),
            ),
        )
        assertFalse(
            canPresentAvailableUpdate(
                AppUpdateDownloadState.ReadyToInstall(
                    versionName = "2.5.8",
                    contentUri = "content://update.apk",
                ),
            ),
        )
        assertTrue(canPresentAvailableUpdate(AppUpdateDownloadState.Idle))
        assertTrue(
            canPresentAvailableUpdate(
                AppUpdateDownloadState.Failed(message = "Temporary network failure"),
            ),
        )
    }

    private class FakeRepository(
        private val result: AppUpdateCheckResult,
    ) : AppUpdateRepository {
        var checkCount: Int = 0
            private set

        override suspend fun checkForUpdate(force: Boolean): AppUpdateCheckResult {
            checkCount += 1
            return result
        }
    }

    private class FakeDownloader : AppUpdateDownloader {
        private val mutableState = MutableStateFlow<AppUpdateDownloadState>(AppUpdateDownloadState.Idle)
        val downloads = mutableListOf<AppUpdateInfo>()

        override val state: StateFlow<AppUpdateDownloadState> = mutableState

        override fun download(update: AppUpdateInfo) {
            downloads += update
            mutableState.value = AppUpdateDownloadState.Downloading(
                versionName = update.versionName,
                totalBytes = update.apkSizeBytes,
            )
        }

        override fun resume() = Unit
    }

    private class BlockingFirstRepository : AppUpdateRepository {
        val firstCheckStarted = CompletableDeferred<Unit>()
        val releaseFirstCheck = CompletableDeferred<Unit>()
        val forceArguments = Collections.synchronizedList(mutableListOf<Boolean>())

        override suspend fun checkForUpdate(force: Boolean): AppUpdateCheckResult {
            forceArguments += force
            if (forceArguments.size == 1) {
                firstCheckStarted.complete(Unit)
                releaseFirstCheck.await()
            }
            return AppUpdateCheckResult.UpToDate
        }
    }

    private companion object {
        fun testUpdate() = AppUpdateInfo(
            versionName = "2.5.8",
            title = "shadow-ssh 2.5.8",
            releaseNotes = "Updater fixes",
            releaseUrl = "https://github.com/stansful/ssh-vpn-client-kotlin/releases/tag/2.5.8",
            apkName = "app-arm64-v8a-release.apk",
            apkUrl = "https://github.com/stansful/ssh-vpn-client-kotlin/releases/download/2.5.8/app-arm64-v8a-release.apk",
            apkSizeBytes = 4_000_000L,
            sha256Digest = null,
        )
    }
}
