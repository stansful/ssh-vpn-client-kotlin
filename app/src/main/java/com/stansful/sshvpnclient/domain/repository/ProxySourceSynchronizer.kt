package com.stansful.sshvpnclient.domain.repository

import com.stansful.sshvpnclient.domain.model.ProxySyncResult

interface ProxySourceSynchronizer {
    suspend fun synchronize(force: Boolean = false): ProxySyncResult
}
