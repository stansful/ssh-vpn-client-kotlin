package com.stansful.sshvpnclient.domain.repository

import com.stansful.sshvpnclient.domain.model.InstalledAppInfo

interface InstalledAppsRepository {
    suspend fun getInstalledApps(): List<InstalledAppInfo>
}
