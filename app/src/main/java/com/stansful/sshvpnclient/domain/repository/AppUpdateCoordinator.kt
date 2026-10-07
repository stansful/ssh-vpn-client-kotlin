package com.stansful.sshvpnclient.domain.repository

import com.stansful.sshvpnclient.domain.model.AppUpdateState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface AppUpdateCoordinator {
    val state: StateFlow<AppUpdateState>

    /** When GitHub last answered an update check (epoch millis; null = never), then every later change. */
    val lastSuccessfulCheckAt: Flow<Long?>

    fun checkForUpdates(manual: Boolean = true)

    fun dismissAvailableUpdate()

    fun downloadAvailableUpdate()

    fun onActionFailed(message: String)
}
