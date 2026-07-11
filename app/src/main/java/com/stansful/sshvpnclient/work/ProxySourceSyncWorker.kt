package com.stansful.sshvpnclient.work

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.stansful.sshvpnclient.SshVpnApplication
import com.stansful.sshvpnclient.domain.model.OpenSourcePolicy
import com.stansful.sshvpnclient.domain.repository.ProxySourceConnectionFactory
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

class ProxySourceSyncWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as SshVpnApplication).container
        if (
            container.appSettingsRepository.settings.value.openSourceConsentVersion <
            OpenSourcePolicy.CONSENT_VERSION ||
            !container.appSettingsRepository.settings.value.openSourceAutoUpdateEnabled
        ) {
            return Result.success()
        }
        val physicalNetwork = selectValidatedUnmeteredPhysicalNetwork(applicationContext)
            ?: return Result.success()
        return try {
            container.proxySourceSynchronizer.synchronize(
                connectionFactory = ProxySourceConnectionFactory { url ->
                    physicalNetwork.openConnection(url)
                },
            )
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (shouldRetryProxySourceSync(error) && runAttemptCount < MAX_RETRY_COUNT) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "public-proxy-source-sync"
        private const val MAX_RETRY_COUNT = 3

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresBatteryNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<ProxySourceSyncWorker>(
                REPEAT_INTERVAL_HOURS,
                TimeUnit.HOURS,
                FLEX_INTERVAL_HOURS,
                TimeUnit.HOURS,
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    RETRY_BACKOFF_MINUTES,
                    TimeUnit.MINUTES,
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        }

        private const val REPEAT_INTERVAL_HOURS = 12L
        private const val FLEX_INTERVAL_HOURS = 4L
        private const val RETRY_BACKOFF_MINUTES = 30L
    }
}

@Suppress("DEPRECATION")
internal fun selectValidatedUnmeteredPhysicalNetwork(context: Context): Network? {
    val connectivityManager = context.getSystemService(ConnectivityManager::class.java) ?: return null
    val candidates = connectivityManager.allNetworks.mapNotNull { network ->
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return@mapNotNull null
        BackgroundSyncNetworkCandidate(
            key = network,
            hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            isNotVpn = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
            isNotMetered = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            isEthernet = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
            isWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            isCellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
        )
    }
    return selectBackgroundSyncNetwork(candidates, connectivityManager.activeNetwork)
}

internal data class BackgroundSyncNetworkCandidate<K>(
    val key: K,
    val hasInternet: Boolean,
    val isValidated: Boolean,
    val isNotVpn: Boolean,
    val isNotMetered: Boolean,
    val isEthernet: Boolean = false,
    val isWifi: Boolean = false,
    val isCellular: Boolean = false,
)

internal fun <K> selectBackgroundSyncNetwork(
    candidates: Collection<BackgroundSyncNetworkCandidate<K>>,
    activeKey: K?,
): K? = candidates
    .asSequence()
    .filter { candidate ->
        candidate.hasInternet &&
            candidate.isValidated &&
            candidate.isNotVpn &&
            candidate.isNotMetered
    }
    .maxByOrNull { candidate ->
        when {
            candidate.key == activeKey -> 10_000
            candidate.isEthernet -> 400
            candidate.isWifi -> 300
            candidate.isCellular -> 200
            else -> 100
        }
    }
    ?.key

internal fun shouldRetryProxySourceSync(error: Throwable): Boolean = error is IOException
