package com.stansful.sshvpnclient.ui.settings

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stansful.sshvpnclient.domain.model.AppUpdateDownloadState
import com.stansful.sshvpnclient.domain.model.AppUpdateInfo
import com.stansful.sshvpnclient.domain.repository.AppUpdateCoordinator
import com.stansful.sshvpnclient.ui.shell.LocalAppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Activity-scoped memory of the update flow that the process-wide coordinator does not keep: the last
 * offered release (notes, size and page stay available after "Later" and while it downloads), the last
 * download progress (shown when a download stops) and whether the update sheet is open. The sheet opens
 * by itself whenever a check offers a release, like the old "Update available" dialog.
 */
internal class AppUpdatePresenter(
    private val coordinator: AppUpdateCoordinator,
) : ViewModel() {
    private val mutableOffer = MutableStateFlow(coordinator.state.value.availableUpdate)
    private val mutableLastProgress = MutableStateFlow<AppUpdateDownloadState.Downloading?>(null)
    private val mutableSheetOpen = MutableStateFlow(false)
    private var downloadWhenOffered = false

    val state = coordinator.state
    val offer: StateFlow<AppUpdateInfo?> = mutableOffer.asStateFlow()
    val lastProgress: StateFlow<AppUpdateDownloadState.Downloading?> = mutableLastProgress.asStateFlow()
    val sheetOpen: StateFlow<Boolean> = mutableSheetOpen.asStateFlow()

    init {
        viewModelScope.launch {
            var previousAvailable: AppUpdateInfo? = null
            coordinator.state.collect { state ->
                val available = state.availableUpdate
                if (available != null) mutableOffer.value = available
                if (available != null && available != previousAvailable) {
                    if (downloadWhenOffered) {
                        downloadWhenOffered = false
                        coordinator.downloadAvailableUpdate()
                    }
                    mutableSheetOpen.value = true
                }
                previousAvailable = available
                (state.downloadState as? AppUpdateDownloadState.Downloading)?.let { mutableLastProgress.value = it }
            }
        }
    }

    fun openSheet() {
        mutableSheetOpen.value = true
    }

    fun closeSheet() {
        mutableSheetOpen.value = false
    }

    /** "Later": hides the offer until the next manual check or process start. */
    fun later() {
        coordinator.dismissAvailableUpdate()
        closeSheet()
    }

    fun checkForUpdates() = coordinator.checkForUpdates(manual = true)

    /**
     * Starts the offered download or resumes the interrupted one. An offer dismissed with "Later" is no
     * longer in the coordinator, so it is asked for again and downloads as soon as it is back.
     */
    fun download() {
        val state = coordinator.state.value
        val resumable = (state.downloadState as? AppUpdateDownloadState.Failed)?.canResume == true
        if (state.availableUpdate != null || resumable) coordinator.downloadAvailableUpdate() else downloadAgain()
    }

    /** After a rejected file: asks GitHub again and downloads the release as soon as it is offered. */
    fun downloadAgain() {
        downloadWhenOffered = true
        coordinator.checkForUpdates(manual = true)
    }

    fun onActionFailed(message: String) = coordinator.onActionFailed(message)

    class Factory(private val coordinator: AppUpdateCoordinator) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AppUpdatePresenter(coordinator) as T
    }
}

/** The Activity's [AppUpdatePresenter], shared by Settings and the update sheet. */
@Composable
internal fun appUpdatePresenter(): AppUpdatePresenter {
    val owner: ViewModelStoreOwner = LocalActivity.current as? ComponentActivity
        ?: checkNotNull(LocalViewModelStoreOwner.current) { "No ViewModelStoreOwner" }
    val coordinator = LocalAppContainer.current.appUpdateCoordinator
    return viewModel(viewModelStoreOwner = owner, factory = AppUpdatePresenter.Factory(coordinator))
}
