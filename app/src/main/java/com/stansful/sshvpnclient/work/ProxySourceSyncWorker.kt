package com.stansful.sshvpnclient.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.stansful.sshvpnclient.SshVpnApplication
import com.stansful.sshvpnclient.domain.model.OpenSourcePolicy
import java.util.concurrent.TimeUnit

class ProxySourceSyncWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as SshVpnApplication).container
        if (
            container.appSettingsRepository.settings.value.openSourceConsentVersion <
            OpenSourcePolicy.CONSENT_VERSION
        ) {
            return Result.success()
        }
        return runCatching { container.proxySourceSynchronizer.synchronize() }
            .fold(
                onSuccess = { Result.success() },
                onFailure = { if (runAttemptCount < MAX_RETRY_COUNT) Result.retry() else Result.failure() },
            )
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "public-proxy-source-sync"
        private const val MAX_RETRY_COUNT = 3

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<ProxySourceSyncWorker>(
                REPEAT_INTERVAL_HOURS,
                TimeUnit.HOURS,
                FLEX_INTERVAL_HOURS,
                TimeUnit.HOURS,
            )
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        private const val REPEAT_INTERVAL_HOURS = 6L
        private const val FLEX_INTERVAL_HOURS = 1L
    }
}
