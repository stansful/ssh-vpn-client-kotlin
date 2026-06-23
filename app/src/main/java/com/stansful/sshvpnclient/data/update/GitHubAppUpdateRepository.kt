package com.stansful.sshvpnclient.data.update

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.edit
import com.stansful.sshvpnclient.domain.model.AndroidAbi
import com.stansful.sshvpnclient.domain.model.AppUpdateCheckResult
import com.stansful.sshvpnclient.domain.model.AppUpdateInfo
import com.stansful.sshvpnclient.domain.model.SemanticVersion
import com.stansful.sshvpnclient.domain.repository.AppUpdateRepository
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

class GitHubAppUpdateRepository(
    context: Context,
    private val currentVersionName: String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val cpuDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val supportedAbis: List<String> = Build.SUPPORTED_ABIS.toList(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) : AppUpdateRepository {
    private val appContext = context.applicationContext
    private val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val checkMutex = Mutex()

    override suspend fun checkForUpdate(force: Boolean): AppUpdateCheckResult = checkMutex.withLock {
        val now = nowMs()
        val lastCheckAtMs = withContext(ioDispatcher) {
            preferences.getLong(KEY_LAST_SUCCESSFUL_CHECK_AT, 0L)
        }
        if (!force && now - lastCheckAtMs in 0 until CHECK_INTERVAL_MS) {
            val cachedResponse = withContext(ioDispatcher) {
                preferences.getString(KEY_CACHED_RESPONSE, null)
            } ?: return@withLock AppUpdateCheckResult.NotDue
            return@withLock withContext(cpuDispatcher) { evaluateRelease(cachedResponse) }
        }

        val response = withContext(ioDispatcher) { loadLatestRelease() }
        val result = withContext(cpuDispatcher) { evaluateRelease(response.body) }
        withContext(ioDispatcher) {
            preferences.edit {
                putLong(KEY_LAST_SUCCESSFUL_CHECK_AT, now)
                putString(KEY_CACHED_RESPONSE, response.body)
                response.etag?.let { putString(KEY_ETAG, it) }
            }
        }

        result
    }

    private fun evaluateRelease(raw: String): AppUpdateCheckResult {
        val update = parseRelease(raw)
        val currentVersion = SemanticVersion.parse(currentVersionName)
            ?: throw AppUpdateException("Current app version is not valid SemVer: $currentVersionName")
        val latestVersion = SemanticVersion.parse(update.versionName)
            ?: throw AppUpdateException("GitHub release tag is not valid SemVer: ${update.versionName}")

        return if (latestVersion > currentVersion) {
            AppUpdateCheckResult.Available(update.copy(versionName = latestVersion.toString()))
        } else {
            AppUpdateCheckResult.UpToDate
        }
    }

    private fun loadLatestRelease(): CachedHttpResponse {
        val etag = preferences.getString(KEY_ETAG, null)
        val connection = openConnection(URL(LATEST_RELEASE_API_URL))
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", GITHUB_API_VERSION)
            connection.setRequestProperty("User-Agent", USER_AGENT)
            etag?.let { connection.setRequestProperty("If-None-Match", it) }

            return when (val responseCode = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> CachedHttpResponse(
                    body = readLimitedResponse(connection),
                    etag = connection.getHeaderField("ETag"),
                )

                HttpURLConnection.HTTP_NOT_MODIFIED -> {
                    val cached = preferences.getString(KEY_CACHED_RESPONSE, null)
                        ?: throw AppUpdateException("GitHub returned 304 without a cached release")
                    CachedHttpResponse(body = cached, etag = etag)
                }

                else -> throw AppUpdateException("GitHub update check failed with HTTP $responseCode")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: URL): HttpURLConnection {
        val network = findValidatedNonVpnNetwork()
        val connection = if (network != null) {
            network.openConnection(url)
        } else {
            url.openConnection()
        }
        return connection as HttpURLConnection
    }

    private fun findValidatedNonVpnNetwork(): Network? {
        val network = connectivityManager.activeNetwork ?: return null
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return null
        return network.takeIf {
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        }
    }

    private fun readLimitedResponse(connection: HttpURLConnection): String {
        connection.inputStream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(RESPONSE_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (output.size() + read > MAX_RESPONSE_BYTES) {
                    throw AppUpdateException("GitHub release response is too large")
                }
                output.write(buffer, 0, read)
            }
            return output.toString(Charsets.UTF_8.name())
        }
    }

    private fun parseRelease(raw: String): AppUpdateInfo {
        val release = runCatching { JSONObject(raw) }
            .getOrElse { error -> throw AppUpdateException("Invalid GitHub release response", error) }
        val tagName = release.requireString("tag_name")
        val version = SemanticVersion.parse(tagName)
            ?: throw AppUpdateException("GitHub release tag is not valid SemVer: $tagName")
        val assets = release.optJSONArray("assets")
            ?: throw AppUpdateException("GitHub release does not contain assets")

        val apkCandidates = buildList {
            for (index in 0 until assets.length()) {
                val asset = assets.optJSONObject(index) ?: continue
                val name = asset.optString("name")
                if (name.endsWith(".apk", ignoreCase = true)) add(asset)
            }
        }
        val versionText = version.toString()
        val apk = selectBestApkAsset(
            apkCandidates = apkCandidates,
            versionText = versionText,
            supportedAbis = supportedAbis,
        )
        ?: throw AppUpdateException("GitHub release does not contain an APK asset")

        return AppUpdateInfo(
            versionName = tagName,
            title = release.optString("name").ifBlank { "shadow-ssh $versionText" },
            releaseNotes = release.optString("body").trim(),
            releaseUrl = release.requireString("html_url"),
            apkName = apk.requireString("name"),
            apkUrl = apk.requireString("browser_download_url"),
            apkSizeBytes = apk.optLong("size", 0L).coerceAtLeast(0L),
            sha256Digest = apk.optString("digest")
                .takeIf { it.startsWith(SHA256_PREFIX, ignoreCase = true) }
                ?.substringAfter(':')
                ?.lowercase(),
        )
    }

    private fun JSONObject.requireString(name: String): String {
        return optString(name).takeIf { it.isNotBlank() }
            ?: throw AppUpdateException("GitHub release is missing '$name'")
    }

    private data class CachedHttpResponse(
        val body: String,
        val etag: String?,
    )

    private companion object {
        const val LATEST_RELEASE_API_URL =
            "https://api.github.com/repos/stansful/ssh-vpn-client-kotlin/releases/latest"
        const val GITHUB_API_VERSION = "2026-03-10"
        const val USER_AGENT = "shadow-ssh-android-updater"
        const val PREFERENCES_NAME = "shadow-ssh-update-check"
        const val KEY_LAST_SUCCESSFUL_CHECK_AT = "last_successful_check_at"
        const val KEY_CACHED_RESPONSE = "cached_response"
        const val KEY_ETAG = "etag"
        const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1_000L
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 8_000
        const val MAX_RESPONSE_BYTES = 1_048_576
        const val RESPONSE_BUFFER_SIZE = 8 * 1_024
        const val SHA256_PREFIX = "sha256:"
    }
}

internal fun selectBestApkAssetName(
    apkNames: List<String>,
    versionText: String,
    supportedAbis: List<String>,
): String? {
    if (apkNames.isEmpty()) return null
    return apkNames
        .mapIndexed { index, name ->
            ApkAssetScore(
                index = index,
                name = name,
                score = apkAssetScore(name, versionText, supportedAbis),
            )
        }
        .maxWithOrNull(
            compareBy<ApkAssetScore> { it.score }
                .thenByDescending { -it.index },
        )
        ?.name
}

private fun selectBestApkAsset(
    apkCandidates: List<JSONObject>,
    versionText: String,
    supportedAbis: List<String>,
): JSONObject? {
    val selectedName = selectBestApkAssetName(
        apkNames = apkCandidates.map { asset -> asset.optString("name") },
        versionText = versionText,
        supportedAbis = supportedAbis,
    ) ?: return null
    return apkCandidates.firstOrNull { asset -> asset.optString("name") == selectedName }
}

private fun apkAssetScore(
    name: String,
    versionText: String,
    supportedAbis: List<String>,
): Int {
    val normalizedName = name.lowercase()
    val versionBonus = if (normalizedName.contains(versionText.lowercase())) VERSION_MATCH_BONUS else 0
    val abiRank = supportedAbis.indexOfFirst { abi -> normalizedName.matchesAbi(abi) }

    return when {
        abiRank >= 0 -> ABI_MATCH_BASE_SCORE - abiRank + versionBonus
        normalizedName.contains("universal") -> UNIVERSAL_MATCH_SCORE + versionBonus
        versionBonus > 0 -> VERSION_ONLY_SCORE + versionBonus
        else -> 0
    }
}

private fun String.matchesAbi(abi: String): Boolean {
    return AndroidAbi.assetNameMatchesAbi(this, abi)
}

private data class ApkAssetScore(
    val index: Int,
    val name: String,
    val score: Int,
)

private const val ABI_MATCH_BASE_SCORE = 10_000
private const val UNIVERSAL_MATCH_SCORE = 5_000
private const val VERSION_ONLY_SCORE = 1_000
private const val VERSION_MATCH_BONUS = 100

class AppUpdateException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
