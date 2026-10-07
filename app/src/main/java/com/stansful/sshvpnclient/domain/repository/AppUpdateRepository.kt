package com.stansful.sshvpnclient.domain.repository

import com.stansful.sshvpnclient.domain.model.AppUpdateCheckResult
import kotlinx.coroutines.flow.Flow

interface AppUpdateRepository {
    suspend fun checkForUpdate(force: Boolean = false): AppUpdateCheckResult

    /**
     * When GitHub last answered a check (epoch millis; null = never), then every later change. Reading
     * it never blocks the caller's thread.
     */
    fun lastSuccessfulCheckAt(): Flow<Long?>
}
