package com.stansful.sshvpnclient.data.update

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.net.toUri
import com.stansful.sshvpnclient.domain.model.AndroidAbi
import com.stansful.sshvpnclient.domain.model.XrayCoreAsset
import com.stansful.sshvpnclient.domain.model.XrayCoreRelease
import com.stansful.sshvpnclient.domain.repository.XrayCoreUpdateRepository
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

class GitHubXrayCoreUpdateRepository(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val supportedAbis: List<String> = Build.SUPPORTED_ABIS.toList(),
) : XrayCoreUpdateRepository {
    private val appContext = context.applicationContext
    private val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)

    override val runtimeAbi: String = AndroidAbi.runtimeAbi(supportedAbis)

    override suspend fun loadLatestRelease(): XrayCoreRelease = withContext(ioDispatcher) {
        val response = loadLatestReleaseBody()
        parseRelease(response)
    }

    override suspend fun download(asset: XrayCoreAsset): File = XrayCoreDownloadGate.runExclusive {
        withContext(ioDispatcher) {
            require(asset.abi == runtimeAbi) {
                "Xray core ${asset.abi} is not compatible with this device runtime ABI: $runtimeAbi"
            }
            validateDownloadUrl(asset.downloadUrl)

            val downloadDir = File(appContext.filesDir, XRAY_CORE_DOWNLOAD_DIRECTORY).apply { mkdirs() }
            downloadDir.listFiles()
                ?.filter { oldFile -> oldFile.name != asset.safeFileName() }
                ?.forEach { oldFile -> runCatching { oldFile.delete() } }

            val targetFile = File(downloadDir, asset.safeFileName())
            if (
                targetFile.isFile &&
                targetFile.length() > 0L &&
                targetFile.matchesDigest(asset.sha256Digest) &&
                targetFile.isUsableXrayCoreAsset(asset.abi)
            ) {
                return@withContext targetFile
            }
            if (targetFile.isFile) {
                runCatching { targetFile.delete() }
            }
            val tempFile = File(downloadDir, "${targetFile.name}.tmp")
            runCatching { tempFile.delete() }

            val digest = MessageDigest.getInstance("SHA-256")
            val connection = openConnection(URL(asset.downloadUrl))
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = DOWNLOAD_READ_TIMEOUT_MS
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", USER_AGENT)

                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw AppUpdateException("Xray core download failed with HTTP $responseCode")
                }
                tempFile.outputStream().use { output ->
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                        var totalBytes = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            totalBytes += read
                            if (totalBytes > MAX_CORE_DOWNLOAD_BYTES) {
                                throw AppUpdateException("Xray core download is too large")
                            }
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                        }
                    }
                }
            } catch (error: Throwable) {
                tempFile.delete()
                throw error
            } finally {
                connection.disconnect()
            }

            val expectedDigest = asset.sha256Digest
            val actualDigest = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
            if (!expectedDigest.isNullOrBlank() && actualDigest != expectedDigest.lowercase()) {
                tempFile.delete()
                throw AppUpdateException("Downloaded Xray core SHA-256 verification failed")
            }
            if (!tempFile.isUsableXrayCoreAsset(asset.abi)) {
                tempFile.delete()
                throw AppUpdateException(
                    "Downloaded Xray core asset is outdated or invalid: " +
                        "missing classes.dex or native library for ${asset.abi}",
                )
            }
            if (targetFile.exists()) targetFile.delete()
            check(tempFile.renameTo(targetFile)) { "Unable to store downloaded Xray core" }
            targetFile
        }
    }

    private fun loadLatestReleaseBody(): String {
        val connection = openConnection(URL(LATEST_RELEASE_API_URL))
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", GITHUB_API_VERSION)
            connection.setRequestProperty("User-Agent", USER_AGENT)

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw AppUpdateException("Xray core release check failed with HTTP $responseCode")
            }
            return readLimitedResponse(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseRelease(raw: String): XrayCoreRelease {
        val release = runCatching { JSONObject(raw) }
            .getOrElse { error -> throw AppUpdateException("Invalid Xray core release response", error) }
        val tagName = release.requireString("tag_name")
        val releaseUrl = release.requireString("html_url")
        val assets = release.optJSONArray("assets")
            ?: throw AppUpdateException("Xray core release does not contain assets")

        val parsedAssets = buildList {
            for (index in 0 until assets.length()) {
                val asset = assets.optJSONObject(index) ?: continue
                val name = asset.optString("name")
                if (!name.endsWith(".aar", ignoreCase = true)) continue
                val downloadUrl = asset.optString("browser_download_url")
                if (downloadUrl.isBlank()) continue

                val matchingAbis = AndroidAbi.KNOWN_ABIS.filter { abi ->
                    AndroidAbi.assetNameMatchesAbi(name, abi)
                }
                val assetAbis = if (matchingAbis.isEmpty() && name.isUniversalXrayAar()) {
                    AndroidAbi.KNOWN_ABIS
                } else {
                    matchingAbis
                }

                assetAbis.forEach { abi ->
                    add(
                        XrayCoreAsset(
                            abi = abi,
                            name = name,
                            downloadUrl = downloadUrl,
                            sizeBytes = asset.optLong("size", 0L).coerceAtLeast(0L),
                            sha256Digest = asset.optString("digest")
                                .takeIf { it.startsWith(SHA256_PREFIX, ignoreCase = true) }
                                ?.substringAfter(':')
                                ?.lowercase(),
                            universal = matchingAbis.isEmpty(),
                        ),
                    )
                }
            }
        }.distinctBy { asset -> asset.abi to asset.downloadUrl }

        return XrayCoreRelease(
            versionName = tagName,
            title = release.optString("name").ifBlank { "libXray $tagName" },
            releaseUrl = releaseUrl,
            runtimeAbi = runtimeAbi,
            assets = parsedAssets.sortedWith(compareBy<XrayCoreAsset> { asset ->
                AndroidAbi.KNOWN_ABIS.indexOf(asset.abi).takeIf { it >= 0 } ?: Int.MAX_VALUE
            }.thenBy { it.name }),
        )
    }

    private fun String.isUniversalXrayAar(): Boolean {
        val normalized = lowercase()
        return normalized == "libxray.aar" ||
            (normalized.contains("libxray") && normalized.contains("universal"))
    }

    private fun XrayCoreAsset.safeFileName(): String {
        val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return if (universal) "${abi}-$safeName" else safeName
    }

    private fun validateDownloadUrl(value: String) {
        val uri = value.toUri()
        if (
            uri.scheme != "https" ||
            uri.host != "github.com" ||
            !uri.path.orEmpty().startsWith(XRAY_CORE_RELEASE_PATH_PREFIX)
        ) {
            throw AppUpdateException("GitHub release returned an invalid Xray core URL")
        }
    }

    private fun File.matchesDigest(expectedDigest: String?): Boolean {
        if (expectedDigest.isNullOrBlank()) return true
        return sha256() == expectedDigest.lowercase()
    }

    private fun File.isUsableXrayCoreAsset(abi: String): Boolean {
        return runCatching {
            ZipFile(this).use { zip ->
                zip.getEntry(CLASSES_DEX_NAME) != null &&
                    zip.getEntry("jni/$abi/$NATIVE_LIBRARY_NAME") != null
            }
        }.getOrDefault(false)
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(this).use { input ->
            val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
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
                    throw AppUpdateException("Xray core release response is too large")
                }
                output.write(buffer, 0, read)
            }
            return output.toString(Charsets.UTF_8.name())
        }
    }

    private fun JSONObject.requireString(name: String): String {
        return optString(name).takeIf { it.isNotBlank() }
            ?: throw AppUpdateException("Xray core release is missing '$name'")
    }

    private companion object {
        const val LATEST_RELEASE_API_URL =
            "https://api.github.com/repos/stansful/ssh-vpn-client-kotlin/releases/latest"
        const val XRAY_CORE_RELEASE_PATH_PREFIX = "/stansful/ssh-vpn-client-kotlin/releases/download/"
        const val GITHUB_API_VERSION = "2026-03-10"
        const val USER_AGENT = "shadow-ssh-android-xray-core-updater"
        const val XRAY_CORE_DOWNLOAD_DIRECTORY = "xray-core-downloads"
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 8_000
        const val DOWNLOAD_READ_TIMEOUT_MS = 60_000
        const val MAX_RESPONSE_BYTES = 1_048_576
        const val MAX_CORE_DOWNLOAD_BYTES = 90L * 1_024L * 1_024L
        const val RESPONSE_BUFFER_SIZE = 8 * 1_024
        const val DOWNLOAD_BUFFER_SIZE = 32 * 1_024
        const val SHA256_PREFIX = "sha256:"
        const val CLASSES_DEX_NAME = "classes.dex"
        const val NATIVE_LIBRARY_NAME = "libgojni.so"
    }
}

/**
 * Smart and OpenSource expose the same updater through different activity-scoped ViewModels.
 * Serialize every repository instance in this process so their shared .tmp and target files
 * cannot delete or overwrite one another.
 */
internal object XrayCoreDownloadGate {
    private val mutex = Mutex()

    suspend fun <T> runExclusive(block: suspend () -> T): T = mutex.withLock { block() }
}
