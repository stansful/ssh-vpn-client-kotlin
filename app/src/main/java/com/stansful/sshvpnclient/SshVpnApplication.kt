package com.stansful.sshvpnclient

import android.app.Application
import com.stansful.sshvpnclient.domain.model.OpenSourcePolicy
import com.stansful.sshvpnclient.work.ProxySourceSyncWorker

class SshVpnApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        if (
            container.appSettingsRepository.settings.value.openSourceConsentVersion >=
            OpenSourcePolicy.CONSENT_VERSION
        ) {
            ProxySourceSyncWorker.schedule(this)
        }
    }
}
