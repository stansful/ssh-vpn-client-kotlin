package com.stansful.sshvpnclient.domain.repository

import com.stansful.sshvpnclient.domain.model.AppUpdateState
import kotlinx.coroutines.flow.StateFlow

interface AppUpdateCoordinator {
    val state: StateFlow<AppUpdateState>

    fun checkForUpdates(manual: Boolean = true)

    fun dismissAvailableUpdate()

    fun downloadAvailableUpdate()

    fun onActionFailed(message: String)
}
