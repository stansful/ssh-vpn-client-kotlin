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
        val settings = container.appSettingsRepository.settings.value
        when {
            settings.openSourceAutoUpdateEnabled &&
                settings.openSourceConsentVersion >= OpenSourcePolicy.CONSENT_VERSION -> {
                ProxySourceSyncWorker.schedule(this)
            }
            settings.openSourceAutoUpdateEnabled -> {
                // Consent was revoked or its version changed; remove the previously scheduled work.
                ProxySourceSyncWorker.cancel(this)
            }
        }
    }
}
