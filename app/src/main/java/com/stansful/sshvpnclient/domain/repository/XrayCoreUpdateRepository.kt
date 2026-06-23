package com.stansful.sshvpnclient.domain.repository

import com.stansful.sshvpnclient.domain.model.XrayCoreAsset
import com.stansful.sshvpnclient.domain.model.XrayCoreRelease
import java.io.File

interface XrayCoreUpdateRepository {
    val runtimeAbi: String

    suspend fun loadLatestRelease(): XrayCoreRelease

    suspend fun download(asset: XrayCoreAsset): File
}
