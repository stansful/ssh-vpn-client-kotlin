package com.stansful.sshvpnclient.data.apps

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.stansful.sshvpnclient.domain.model.InstalledAppInfo
import com.stansful.sshvpnclient.domain.repository.InstalledAppsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PackageManagerInstalledAppsRepository(
    context: Context,
) : InstalledAppsRepository {
    private val packageManager = context.applicationContext.packageManager

    @Suppress("DEPRECATION")
    override suspend fun getInstalledApps(): List<InstalledAppInfo> = withContext(Dispatchers.IO) {
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
            .distinctBy { it.packageName }
            .sortedWith(
                compareBy<InstalledAppInfo> { it.label.lowercase() }
                    .thenBy { it.packageName },
            )
    }

    private fun ApplicationInfo.isSystemApp(): Boolean {
        return flags and ApplicationInfo.FLAG_SYSTEM != 0 ||
            flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
    }
}
