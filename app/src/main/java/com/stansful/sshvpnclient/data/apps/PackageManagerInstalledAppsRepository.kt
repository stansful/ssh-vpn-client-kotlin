package com.stansful.sshvpnclient.data.apps

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import com.stansful.sshvpnclient.domain.model.InstalledAppInfo
import com.stansful.sshvpnclient.domain.repository.InstalledAppsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class PackageManagerInstalledAppsRepository(
    context: Context,
) : InstalledAppsRepository {
    private val packageManager = context.applicationContext.packageManager
    private val cacheMutex = Mutex()
    private var cachedApps: List<InstalledAppInfo>? = null
    private var cacheCreatedAtMs = 0L

    @Suppress("DEPRECATION")
    override suspend fun getInstalledApps(): List<InstalledAppInfo> = cacheMutex.withLock {
        val now = SystemClock.elapsedRealtime()
        cachedApps
            ?.takeIf { now - cacheCreatedAtMs < CACHE_TTL_MS }
            ?.let { return@withLock it }

        val apps = withContext(Dispatchers.IO) {
            packageManager
                .getInstalledApplications(PackageManager.GET_META_DATA)
                .map { appInfo ->
                    val label = appInfo.loadLabel(packageManager).toString()
                        .takeIf { it.isNotBlank() }
                        ?: appInfo.packageName
                    InstalledAppInfo(
                        label = label,
                        packageName = appInfo.packageName,
                        isSystem = appInfo.isSystemApp(),
                    )
                }
        }
        val sortedApps = withContext(Dispatchers.Default) {
            apps.distinctBy { it.packageName }
                .sortedWith(
                    compareBy<InstalledAppInfo> { it.label.lowercase() }
                        .thenBy { it.packageName },
                )
        }
        cachedApps = sortedApps
        cacheCreatedAtMs = now
        sortedApps
    }

    private fun ApplicationInfo.isSystemApp(): Boolean {
        return flags and ApplicationInfo.FLAG_SYSTEM != 0 ||
            flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
    }

    private companion object {
        const val CACHE_TTL_MS = 5 * 60 * 1_000L
    }
}
