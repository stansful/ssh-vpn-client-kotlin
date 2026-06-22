package com.stansful.sshvpnclient.domain.repository

import com.stansful.sshvpnclient.domain.model.AppUpdateDownloadState
import com.stansful.sshvpnclient.domain.model.AppUpdateInfo
import kotlinx.coroutines.flow.StateFlow

interface AppUpdateDownloader {
    val state: StateFlow<AppUpdateDownloadState>

    fun download(update: AppUpdateInfo)

    fun consumeInstallerRequest()
}
