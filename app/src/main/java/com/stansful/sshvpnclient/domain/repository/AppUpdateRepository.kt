package com.stansful.sshvpnclient.domain.repository

import com.stansful.sshvpnclient.domain.model.AppUpdateCheckResult

interface AppUpdateRepository {
    suspend fun checkForUpdate(force: Boolean = false): AppUpdateCheckResult
}
