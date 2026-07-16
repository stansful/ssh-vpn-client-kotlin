package com.stansful.sshvpnclient.data.update

import com.stansful.sshvpnclient.domain.model.AppUpdateCheckResult
import com.stansful.sshvpnclient.domain.model.AppUpdateDownloadState
import com.stansful.sshvpnclient.domain.model.AppUpdateState
import com.stansful.sshvpnclient.domain.repository.AppUpdateCoordinator
import com.stansful.sshvpnclient.domain.repository.AppUpdateDownloader
import com.stansful.sshvpnclient.domain.repository.AppUpdateRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Process-wide application-update state shared by every top-level tab. */
class DefaultAppUpdateCoordinator(
    private val repository: AppUpdateRepository,
    private val downloader: AppUpdateDownloader,
    private val applicationScope: CoroutineScope,
    automaticCheckDelayMs: Long? = AUTOMATIC_UPDATE_CHECK_DELAY_MS,
) : AppUpdateCoordinator {
    private val mutableState = MutableStateFlow(
        AppUpdateState(downloadState = downloader.state.value),
    )
    private val checkLock = Any()
    private val downloadLock = Any()
    private var checkJob: Job? = null
    private var activeCheckIsManual = false
    private var pendingManualCheck = false

    override val state: StateFlow<AppUpdateState> = mutableState.asStateFlow()

    init {
        applicationScope.launch {
            downloader.state.collect(::applyDownloadState)
        }
        if (automaticCheckDelayMs != null) {
            applicationScope.launch {
                delay(automaticCheckDelayMs)
                checkForUpdates(manual = false)
            }
        }
    }

    override fun checkForUpdates(manual: Boolean) {
        synchronized(checkLock) {
            if (checkJob?.isActive == true) {
                if (manual && !activeCheckIsManual) {
                    pendingManualCheck = true
                    mutableState.update {
                        it.copy(isChecking = true, statusMessage = null)
                    }
                }
                return
            }
            lateinit var launchedJob: Job
            launchedJob = applicationScope.launch(start = CoroutineStart.LAZY) {
                mutableState.update { current ->
                    current.copy(
                        isChecking = true,
                        statusMessage = if (manual) null else current.statusMessage,
                    )
                }
                try {
                    val result = repository.checkForUpdate(force = manual)
                    mutableState.update { current ->
                        when (result) {
                            is AppUpdateCheckResult.Available -> {
                                if (canPresentAvailableUpdate(downloader.state.value)) {
                                    current.copy(
                                        isChecking = false,
                                        availableUpdate = result.update,
                                        statusMessage = null,
                                    )
                                } else {
                                    current.copy(isChecking = false, availableUpdate = null)
                                }
                            }
                            AppUpdateCheckResult.UpToDate -> current.copy(
                                isChecking = false,
                                availableUpdate = null,
                                statusMessage = if (manual) {
                                    "shadow-ssh is up to date"
                                } else {
                                    null
                                },
                            )
                            AppUpdateCheckResult.NotDue -> current.copy(isChecking = false)
                        }
                    }
                } catch (error: CancellationException) {
                    mutableState.update { it.copy(isChecking = false) }
                    throw error
                } catch (error: Exception) {
                    mutableState.update { current ->
                        current.copy(
                            isChecking = false,
                            statusMessage = if (manual) {
                                error.message ?: "Unable to check for updates"
                            } else {
                                current.statusMessage
                            },
                        )
                    }
                } finally {
                    val runPendingManualCheck = synchronized(checkLock) {
                        if (checkJob === launchedJob) checkJob = null
                        activeCheckIsManual = false
                        pendingManualCheck.also { pendingManualCheck = false }
                    }
                    if (runPendingManualCheck) checkForUpdates(manual = true)
                }
            }
            activeCheckIsManual = manual
            checkJob = launchedJob
            launchedJob.start()
        }
    }

    override fun dismissAvailableUpdate() {
        mutableState.update { it.copy(availableUpdate = null) }
    }

    override fun downloadAvailableUpdate() {
        synchronized(downloadLock) {
            if (downloader.state.value is AppUpdateDownloadState.Downloading) return
            val update = mutableState.value.availableUpdate
            if (update != null) {
                downloader.download(update)
                mutableState.update {
                    it.copy(availableUpdate = null, statusMessage = null)
                }
            } else {
                downloader.resume()
                mutableState.update { it.copy(statusMessage = null) }
            }
        }
    }

    override fun onActionFailed(message: String) {
        mutableState.update { it.copy(statusMessage = message) }
    }

    private fun applyDownloadState(downloadState: AppUpdateDownloadState) {
        mutableState.update { current ->
            current.copy(
                downloadState = downloadState,
                statusMessage = downloadStatusMessage(current, downloadState),
            )
        }
    }

    private companion object {
        const val AUTOMATIC_UPDATE_CHECK_DELAY_MS = 1_500L
    }
}

internal fun downloadStatusMessage(
    previous: AppUpdateState,
    downloadState: AppUpdateDownloadState,
): String? = when (downloadState) {
    is AppUpdateDownloadState.Downloading -> {
        val progress = downloadState.progressPercent?.let { " · $it%" }.orEmpty()
        "Downloading shadow-ssh ${downloadState.versionName}$progress"
    }
    is AppUpdateDownloadState.Failed -> downloadState.message
    is AppUpdateDownloadState.ReadyToInstall ->
        "shadow-ssh ${downloadState.versionName} is ready to install"
    AppUpdateDownloadState.Idle -> {
        if (previous.downloadState is AppUpdateDownloadState.Idle) {
            previous.statusMessage
        } else {
            null
        }
    }
}

internal fun canPresentAvailableUpdate(downloadState: AppUpdateDownloadState): Boolean {
    return downloadState !is AppUpdateDownloadState.Downloading &&
        downloadState !is AppUpdateDownloadState.ReadyToInstall
}
