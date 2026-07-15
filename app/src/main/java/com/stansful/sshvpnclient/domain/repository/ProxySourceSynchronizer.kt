package com.stansful.sshvpnclient.domain.repository

import com.stansful.sshvpnclient.domain.model.ProxySyncResult
import java.net.URL
import java.net.URLConnection

fun interface ProxySourceConnectionFactory {
    fun open(url: URL): URLConnection
}

interface ProxySourceSynchronizer {
    suspend fun synchronize(
        force: Boolean = false,
        connectionFactory: ProxySourceConnectionFactory? = null,
    ): ProxySyncResult
}

/** Marker boundary preventing Smart Connect from being wired to the OpenSource synchronizer. */
interface SmartProxySourceSynchronizer : ProxySourceSynchronizer
