package com.stansful.sshvpnclient.data.smart

import android.content.Context
import com.stansful.sshvpnclient.data.proxy.PublicProxySourceSynchronizer
import com.stansful.sshvpnclient.domain.model.ProxySyncResult
import com.stansful.sshvpnclient.domain.repository.ProxySourceConnectionFactory
import com.stansful.sshvpnclient.domain.repository.SmartProxyProfileRepository
import com.stansful.sshvpnclient.domain.repository.SmartProxySourceSynchronizer

/** Constructs the Smart source client only from the Smart repository type and private namespace. */
class IsolatedSmartProxySourceSynchronizer(
    context: Context,
    profileRepository: SmartProxyProfileRepository,
) : SmartProxySourceSynchronizer {
    private val delegate = PublicProxySourceSynchronizer(
        context = context,
        proxyProfileRepository = profileRepository,
        preferencesName = "smart-connect-proxy-sync",
        userAgent = "shadow-ssh-android-smart-connect-sync",
    )

    override suspend fun synchronize(
        force: Boolean,
        connectionFactory: ProxySourceConnectionFactory?,
    ): ProxySyncResult = delegate.synchronize(force, connectionFactory)
}
