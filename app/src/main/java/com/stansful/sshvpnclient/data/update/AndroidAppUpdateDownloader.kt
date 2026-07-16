package com.stansful.sshvpnclient.data.update

import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Network
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import com.stansful.sshvpnclient.data.network.ValidatedPhysicalNetworkSelector
import com.stansful.sshvpnclient.domain.model.AppUpdateDownloadState
import com.stansful.sshvpnclient.domain.model.AppUpdateInfo
import com.stansful.sshvpnclient.domain.model.SemanticVersion
import com.stansful.sshvpnclient.domain.repository.AppUpdateDownloader
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Downloads application updates in the app process instead of Android DownloadManager.
 *
 * DownloadManager runs in a separate system UID which can remain WAITING_FOR_NETWORK while this
 * application owns a full-tunnel VPN. An app-owned connection can be pinned to the validated
 * physical network, while a resumable partial file keeps short Wi-Fi/mobile handoffs cheap.
 */
class AndroidAppUpdateDownloader(
    context: Context,
    private val applicationScope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AppUpdateDownloader {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager
    private val physicalNetworkSelector = ValidatedPhysicalNetworkSelector(appContext)
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow<AppUpdateDownloadState>(AppUpdateDownloadState.Idle)
    private val operationMutex = Mutex()
    private val launchLock = Any()
    private val restoreJob: Job
    private var downloadJob: Job? = null

    override val state: StateFlow<AppUpdateDownloadState> = mutableState.asStateFlow()

    init {
        restoreJob = applicationScope.launch {
            operationMutex.withLock {
                withContext(ioDispatcher) { restorePendingDownload() }
            }
        }
    }

    override fun download(update: AppUpdateInfo) {
        synchronized(launchLock) {
            if (downloadJob?.isActive == true || mutableState.value is AppUpdateDownloadState.Downloading) {
                return
            }
            lateinit var launchedJob: Job
            launchedJob = applicationScope.launch(start = CoroutineStart.LAZY) {
                try {
                    restoreJob.join()
                    operationMutex.withLock {
                        withContext(ioDispatcher) { downloadAndValidate(update) }
                    }
                } catch (error: CancellationException) {
                    withContext(NonCancellable + ioDispatcher) {
                        failPreservingPartial(
                            "Update download interrupted. Use Resume update download.",
                        )
                    }
                    throw error
                } catch (error: IOException) {
                    withContext(ioDispatcher) {
                        failPreservingPartial(
                            "${error.message ?: "Update network error"}. " +
                                "Use Resume update download.",
                        )
                    }
                } catch (error: Exception) {
                    withContext(ioDispatcher) {
                        failAndClear(error.message ?: "Unable to download application update")
                    }
                } finally {
                    synchronized(launchLock) {
                        if (downloadJob === launchedJob) downloadJob = null
                    }
                }
            }
            downloadJob = launchedJob
            launchedJob.start()
        }
    }

    override fun resume() {
        val update = pendingUpdateSnapshot()
        if (update == null) {
            mutableState.value = AppUpdateDownloadState.Failed(
                message = "No interrupted application update is available to resume",
            )
            return
        }
        download(update)
    }

    private suspend fun downloadAndValidate(update: AppUpdateInfo) {
        validateInitialDownloadUrl(update.apkUrl)
        val version = SemanticVersion.parse(update.versionName)
            ?: throw AppUpdateException("Update version is invalid: ${update.versionName}")
        if (update.apkSizeBytes <= 0L) {
            throw AppUpdateException("GitHub release did not provide a valid APK size")
        }
        if (update.apkSizeBytes > MAX_APK_DOWNLOAD_BYTES) {
            throw AppUpdateException("Application update is too large")
        }

        val updateDirectory = updateDirectory()
        val destination = File(updateDirectory, "shadow-ssh-$version.apk")
        val partial = destination.partialFile()
        val sameDownload = preferences.getString(KEY_VERSION_NAME, null) == version.toString() &&
            preferences.getString(KEY_DOWNLOAD_URL, null) == update.apkUrl &&
            preferences.getString(KEY_FILE_PATH, null) == destination.absolutePath &&
            preferences.getLong(KEY_EXPECTED_SIZE, INVALID_SIZE) == update.apkSizeBytes

        if (!sameDownload) {
            clearUpdateDirectory(updateDirectory)
            clearPendingMetadata()
        } else if (destination.isFile) {
            destination.delete()
        }
        if (partial.length() > update.apkSizeBytes) {
            resetPartial(partial)
            clearResumeValidator()
        }

        preferences.edit(commit = true) {
            putString(KEY_FILE_PATH, destination.absolutePath)
            putString(KEY_VERSION_NAME, version.toString())
            putString(KEY_DOWNLOAD_URL, update.apkUrl)
            putLong(KEY_EXPECTED_SIZE, update.apkSizeBytes)
            putString(KEY_SHA256, update.sha256Digest)
        }
        publishProgress(version.toString(), partial.length(), update.apkSizeBytes)

        downloadAcrossAvailableRoutes(
            sourceUrl = update.apkUrl,
            partial = partial,
            expectedSize = update.apkSizeBytes,
            versionName = version.toString(),
        )

        if (partial.length() != update.apkSizeBytes) {
            throw IOException(
                "Update download is incomplete (${partial.length()} of ${update.apkSizeBytes} bytes)",
            )
        }
        if (destination.exists() && !destination.delete()) {
            throw IOException("Unable to replace the previous update file")
        }
        if (!partial.renameTo(destination)) {
            throw IOException("Unable to store the downloaded update")
        }
        validateDownloadedApk()
    }

    /**
     * Starts with the app-owned default route so an active SSH tunnel remains useful against DPI,
     * then alternates with a freshly selected physical network. Unlike Android DownloadManager,
     * both connections belong to this process and a Wi-Fi/mobile handoff can be retried safely.
     */
    private suspend fun downloadAcrossAvailableRoutes(
        sourceUrl: String,
        partial: File,
        expectedSize: Long,
        versionName: String,
    ) {
        var lastFailure: Exception? = null
        repeat(MAX_ROUTE_ATTEMPTS) { attempt ->
            coroutineContext.ensureActive()
            val network = if (attempt % 2 == 0) null else physicalNetworkSelector.select()
            try {
                downloadToPartial(
                    sourceUrl = sourceUrl,
                    partial = partial,
                    expectedSize = expectedSize,
                    versionName = versionName,
                    network = network,
                )
                validateExpectedLength(partial.length(), expectedSize)
                return
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                lastFailure = error
                if (attempt + 1 < MAX_ROUTE_ATTEMPTS) {
                    delay(ROUTE_RETRY_DELAYS_MS[attempt])
                }
            }
        }
        val detail = lastFailure?.message ?: "all available routes failed"
        throw IOException("Update download failed: $detail", lastFailure)
    }

    private suspend fun downloadToPartial(
        sourceUrl: String,
        partial: File,
        expectedSize: Long,
        versionName: String,
        network: Network?,
    ) {
        var mayRestartFromZero = true
        while (true) {
            coroutineContext.ensureActive()
            val existingBytes = partial.length().coerceAtLeast(0L)
            val ifRange = preferences.getString(KEY_ETAG, null)
                ?: preferences.getString(KEY_LAST_MODIFIED, null)
            val connection = openFollowingRedirects(
                sourceUrl = sourceUrl,
                network = network,
                rangeStart = existingBytes,
                ifRange = ifRange,
            )
            try {
                val responseCode = connection.responseCode
                val contentRange = parseAppUpdateContentRange(
                    connection.getHeaderField(HEADER_CONTENT_RANGE),
                )
                val action = chooseAppUpdateResponseAction(
                    existingBytes = existingBytes,
                    responseCode = responseCode,
                    contentRange = contentRange,
                )
                when (action) {
                    AppUpdateResponseAction.COMPLETE -> {
                        validateExpectedLength(existingBytes, expectedSize)
                        return
                    }
                    AppUpdateResponseAction.RETRY_FROM_ZERO -> {
                        if (!mayRestartFromZero) {
                            throw AppUpdateException("Update server returned an invalid byte range")
                        }
                        mayRestartFromZero = false
                        resetPartial(partial)
                        clearResumeValidator()
                        continue
                    }
                    AppUpdateResponseAction.FAIL -> {
                        throw responseError(responseCode)
                    }
                    AppUpdateResponseAction.APPEND,
                    AppUpdateResponseAction.RESTART,
                    -> Unit
                }

                val append = action == AppUpdateResponseAction.APPEND
                val startingBytes = if (append) existingBytes else 0L
                if (isRejectedAppUpdateContentType(connection.contentType)) {
                    throw AppUpdateException("Update server returned non-APK content")
                }
                val serverTotal = when {
                    contentRange?.total != null -> contentRange.total
                    connection.contentLengthLong > 0L -> startingBytes + connection.contentLengthLong
                    else -> null
                }
                if (serverTotal != null && serverTotal != expectedSize) {
                    throw AppUpdateException(
                        "GitHub APK size changed ($serverTotal instead of $expectedSize bytes)",
                    )
                }
                connection.getHeaderField(HEADER_ETAG)?.let { etag ->
                    preferences.edit { putString(KEY_ETAG, etag) }
                }
                connection.getHeaderField(HEADER_LAST_MODIFIED)?.let { value ->
                    preferences.edit { putString(KEY_LAST_MODIFIED, value) }
                }
                streamResponse(
                    connection = connection,
                    partial = partial,
                    append = append,
                    startingBytes = startingBytes,
                    expectedSize = expectedSize,
                    versionName = versionName,
                )
                return
            } finally {
                connection.disconnect()
            }
        }
    }

    private suspend fun streamResponse(
        connection: HttpURLConnection,
        partial: File,
        append: Boolean,
        startingBytes: Long,
        expectedSize: Long,
        versionName: String,
    ) {
        RandomAccessFile(partial, "rw").use { output ->
            if (append) {
                output.seek(startingBytes)
            } else {
                output.setLength(0L)
            }
            var downloadedBytes = startingBytes
            var lastPublishedBytes = startingBytes
            var lastPublishedAtNs = System.nanoTime()
            connection.inputStream.use { input ->
                val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                while (true) {
                    coroutineContext.ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    downloadedBytes += read
                    if (downloadedBytes > expectedSize || downloadedBytes > MAX_APK_DOWNLOAD_BYTES) {
                        throw AppUpdateException("Downloaded APK exceeds its published size")
                    }
                    val nowNs = System.nanoTime()
                    if (
                        downloadedBytes - lastPublishedBytes >= PROGRESS_STEP_BYTES ||
                        nowNs - lastPublishedAtNs >= PROGRESS_INTERVAL_NS
                    ) {
                        publishProgress(versionName, downloadedBytes, expectedSize)
                        lastPublishedBytes = downloadedBytes
                        lastPublishedAtNs = nowNs
                    }
                }
            }
            output.fd.sync()
            publishProgress(versionName, downloadedBytes, expectedSize)
        }
    }

    private fun openFollowingRedirects(
        sourceUrl: String,
        network: Network?,
        rangeStart: Long,
        ifRange: String?,
    ): HttpURLConnection {
        var currentUrl = URL(sourceUrl)
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val rawConnection = if (network != null) {
                network.openConnection(currentUrl)
            } else {
                currentUrl.openConnection()
            }
            val connection = rawConnection as? HttpURLConnection
                ?: throw AppUpdateException("GitHub returned a non-HTTP update connection")
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.setRequestProperty(HEADER_USER_AGENT, USER_AGENT)
            connection.setRequestProperty(HEADER_ACCEPT_ENCODING, "identity")
            if (rangeStart > 0L) {
                connection.setRequestProperty(HEADER_RANGE, "bytes=$rangeStart-")
                ifRange?.let { connection.setRequestProperty(HEADER_IF_RANGE, it) }
            }

            try {
                val responseCode = connection.responseCode
                if (responseCode !in REDIRECT_RESPONSE_CODES) return connection
                val location = connection.getHeaderField(HEADER_LOCATION)
                connection.disconnect()
                if (location.isNullOrBlank() || redirectCount >= MAX_REDIRECTS) {
                    throw AppUpdateException("GitHub update redirect chain is invalid")
                }
                val nextUrl = URL(currentUrl, location)
                if (!isTrustedAppUpdateRedirectUrl(nextUrl.toString())) {
                    throw AppUpdateException("GitHub update redirected to an untrusted URL")
                }
                currentUrl = nextUrl
            } catch (error: Exception) {
                connection.disconnect()
                throw error
            }
        }
        throw AppUpdateException("GitHub update has too many redirects")
    }

    private fun responseError(responseCode: Int): Exception {
        return if (
            responseCode == HttpURLConnection.HTTP_CLIENT_TIMEOUT ||
            responseCode == 429 ||
            responseCode >= HttpURLConnection.HTTP_INTERNAL_ERROR
        ) {
            IOException("Update download failed with HTTP $responseCode")
        } else {
            AppUpdateException("Update download failed with HTTP $responseCode")
        }
    }

    private fun restorePendingDownload() {
        removeLegacyDownloadManagerJob()
        val file = preferences.getString(KEY_FILE_PATH, null)?.let(::File)
        if (file?.isFile == true && file.length() > 0L) {
            validateDownloadedApk()
            return
        }
        if (pendingUpdateSnapshot() != null) {
            mutableState.value = AppUpdateDownloadState.Failed(
                message = "An interrupted update is ready to resume.",
                canResume = true,
            )
        } else if (file != null || preferences.all.isNotEmpty()) {
            failAndClear("Previous update download could not be restored")
        }
    }

    private fun removeLegacyDownloadManagerJob() {
        val legacyId = preferences.getLong(KEY_LEGACY_DOWNLOAD_ID, INVALID_DOWNLOAD_ID)
        if (legacyId == INVALID_DOWNLOAD_ID) return
        runCatching {
            appContext.getSystemService(DownloadManager::class.java)?.remove(legacyId)
        }
        preferences.edit(commit = true) { remove(KEY_LEGACY_DOWNLOAD_ID) }
    }

    private fun validateDownloadedApk() {
        val file = preferences.getString(KEY_FILE_PATH, null)?.let(::File)
            ?: return failAndClear("Downloaded update path is missing")
        val expectedVersion = preferences.getString(KEY_VERSION_NAME, null)
            ?: return failAndClear("Downloaded update version is missing")
        val expectedSize = preferences.getLong(KEY_EXPECTED_SIZE, INVALID_SIZE)
        if (!file.isFile || file.length() == 0L) {
            return failAndClear("Downloaded update file is missing")
        }
        if (expectedSize > 0L && file.length() != expectedSize) {
            return failAndClear("Downloaded update size does not match the GitHub release")
        }

        val expectedDigest = preferences.getString(KEY_SHA256, null)
        if (!expectedDigest.isNullOrBlank() && sha256(file) != expectedDigest.lowercase()) {
            return failAndClear("Downloaded update SHA-256 verification failed")
        }

        val archiveInfo = getArchivePackageInfo(file)
            ?: return failAndClear("Downloaded file is not a valid APK")
        val installedInfo = getInstalledPackageInfo()
        if (archiveInfo.packageName != appContext.packageName) {
            return failAndClear("Downloaded APK has an unexpected package name")
        }
        if (SemanticVersion.parse(archiveInfo.versionName) != SemanticVersion.parse(expectedVersion)) {
            return failAndClear("Downloaded APK version does not match the GitHub release")
        }
        if (PackageInfoCompat.getLongVersionCode(archiveInfo) <= PackageInfoCompat.getLongVersionCode(installedInfo)) {
            file.delete()
            clearPendingMetadata()
            mutableState.value = AppUpdateDownloadState.Idle
            return
        }
        if (!signaturesMatch(installedInfo, archiveInfo)) {
            return failAndClear("Downloaded APK signing certificate does not match the installed app")
        }

        val contentUri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            file,
        )
        mutableState.value = AppUpdateDownloadState.ReadyToInstall(
            versionName = expectedVersion,
            contentUri = contentUri.toString(),
        )
    }

    @Suppress("DEPRECATION")
    private fun getArchivePackageInfo(file: File): PackageInfo? {
        return packageManager.getPackageArchiveInfo(file.absolutePath, PACKAGE_INFO_FLAGS)
    }

    @Suppress("DEPRECATION")
    private fun getInstalledPackageInfo(): PackageInfo {
        return packageManager.getPackageInfo(appContext.packageName, PACKAGE_INFO_FLAGS)
    }

    @Suppress("DEPRECATION")
    private fun signaturesMatch(installed: PackageInfo, archive: PackageInfo): Boolean {
        val installedSignatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            installed.signingInfo?.signingCertificateHistory.orEmpty()
        } else {
            installed.signatures.orEmpty()
        }.map { signature -> sha256(signature.toByteArray()) }.toSet()
        val archiveSignatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archive.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            archive.signatures.orEmpty()
        }.map { signature -> sha256(signature.toByteArray()) }.toSet()
        return archiveSignatures.isNotEmpty() && installedSignatures.containsAll(archiveSignatures)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(HASH_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun sha256(value: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value)
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun validateInitialDownloadUrl(value: String) {
        if (!isTrustedAppUpdateInitialUrl(value)) {
            throw AppUpdateException("GitHub release returned an invalid APK URL")
        }
    }

    private fun validateExpectedLength(actual: Long, expected: Long) {
        if (actual != expected) {
            throw IOException("Update download is incomplete ($actual of $expected bytes)")
        }
    }

    private fun publishProgress(versionName: String, downloadedBytes: Long, totalBytes: Long) {
        mutableState.value = AppUpdateDownloadState.Downloading(
            versionName = versionName,
            downloadedBytes = downloadedBytes.coerceIn(0L, totalBytes),
            totalBytes = totalBytes,
        )
    }

    private fun resetPartial(partial: File) {
        RandomAccessFile(partial, "rw").use { it.setLength(0L) }
    }

    private fun clearResumeValidator() {
        preferences.edit(commit = true) {
            remove(KEY_ETAG)
            remove(KEY_LAST_MODIFIED)
        }
    }

    private fun failPreservingPartial(message: String) {
        mutableState.value = AppUpdateDownloadState.Failed(
            message = message,
            canResume = pendingUpdateSnapshot() != null,
        )
    }

    private fun failAndClear(message: String) {
        preferences.getString(KEY_FILE_PATH, null)?.let(::File)?.let { file ->
            runCatching { file.delete() }
            runCatching { file.partialFile().delete() }
        }
        clearPendingMetadata()
        mutableState.value = AppUpdateDownloadState.Failed(message)
    }

    private fun clearPendingMetadata() {
        preferences.edit(commit = true) { clear() }
    }

    private fun pendingUpdateSnapshot(): AppUpdateInfo? {
        val versionName = preferences.getString(KEY_VERSION_NAME, null)
            ?.takeIf { SemanticVersion.parse(it) != null }
            ?: return null
        val apkUrl = preferences.getString(KEY_DOWNLOAD_URL, null)
            ?.takeIf(String::isNotBlank)
            ?: return null
        val expectedSize = preferences.getLong(KEY_EXPECTED_SIZE, INVALID_SIZE)
            .takeIf { it in 1..MAX_APK_DOWNLOAD_BYTES }
            ?: return null
        if (preferences.getString(KEY_FILE_PATH, null).isNullOrBlank()) return null
        return AppUpdateInfo(
            versionName = versionName,
            title = "shadow-ssh $versionName",
            releaseNotes = "",
            releaseUrl = "",
            apkName = "shadow-ssh-$versionName.apk",
            apkUrl = apkUrl,
            apkSizeBytes = expectedSize,
            sha256Digest = preferences.getString(KEY_SHA256, null),
        )
    }

    private fun updateDirectory(): File {
        return File(
            checkNotNull(appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)) {
                "External downloads directory is unavailable"
            },
            UPDATES_DIRECTORY,
        ).apply { mkdirs() }
    }

    private fun clearUpdateDirectory(directory: File) {
        directory.listFiles()?.forEach { oldFile -> runCatching { oldFile.delete() } }
    }

    private fun File.partialFile(): File = File(parentFile, "$name.part")

    private companion object {
        const val PREFERENCES_NAME = "shadow-ssh-update-download"
        const val KEY_LEGACY_DOWNLOAD_ID = "download_id"
        const val KEY_FILE_PATH = "file_path"
        const val KEY_VERSION_NAME = "version_name"
        const val KEY_DOWNLOAD_URL = "download_url"
        const val KEY_EXPECTED_SIZE = "expected_size"
        const val KEY_SHA256 = "sha256"
        const val KEY_ETAG = "etag"
        const val KEY_LAST_MODIFIED = "last_modified"
        const val UPDATES_DIRECTORY = "updates"
        const val INVALID_DOWNLOAD_ID = -1L
        const val INVALID_SIZE = -1L
        const val HASH_BUFFER_SIZE = 32 * 1_024
        const val DOWNLOAD_BUFFER_SIZE = 64 * 1_024
        const val PROGRESS_STEP_BYTES = 512L * 1_024L
        const val PROGRESS_INTERVAL_NS = 250_000_000L
        const val CONNECT_TIMEOUT_MS = 12_000
        const val READ_TIMEOUT_MS = 30_000
        const val MAX_REDIRECTS = 5
        const val MAX_ROUTE_ATTEMPTS = 4
        const val MAX_APK_DOWNLOAD_BYTES = 512L * 1_024L * 1_024L
        val ROUTE_RETRY_DELAYS_MS = longArrayOf(250L, 750L, 1_500L)
        const val USER_AGENT = "shadow-ssh-android-app-updater"
        const val HEADER_USER_AGENT = "User-Agent"
        const val HEADER_ACCEPT_ENCODING = "Accept-Encoding"
        const val HEADER_RANGE = "Range"
        const val HEADER_IF_RANGE = "If-Range"
        const val HEADER_CONTENT_RANGE = "Content-Range"
        const val HEADER_ETAG = "ETag"
        const val HEADER_LAST_MODIFIED = "Last-Modified"
        const val HEADER_LOCATION = "Location"
        val REDIRECT_RESPONSE_CODES = setOf(
            HttpURLConnection.HTTP_MOVED_PERM,
            HttpURLConnection.HTTP_MOVED_TEMP,
            HttpURLConnection.HTTP_SEE_OTHER,
            307,
            308,
        )
        val PACKAGE_INFO_FLAGS: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
    }
}

internal data class AppUpdateContentRange(
    val start: Long?,
    val endInclusive: Long?,
    val total: Long?,
)

internal enum class AppUpdateResponseAction {
    APPEND,
    RESTART,
    COMPLETE,
    RETRY_FROM_ZERO,
    FAIL,
}

internal fun chooseAppUpdateResponseAction(
    existingBytes: Long,
    responseCode: Int,
    contentRange: AppUpdateContentRange?,
): AppUpdateResponseAction = when {
    responseCode == HttpURLConnection.HTTP_OK -> AppUpdateResponseAction.RESTART
    responseCode == HttpURLConnection.HTTP_PARTIAL &&
        contentRange?.start == existingBytes &&
        contentRange.endInclusive != null &&
        contentRange.endInclusive >= existingBytes -> AppUpdateResponseAction.APPEND
    responseCode == HttpURLConnection.HTTP_PARTIAL -> AppUpdateResponseAction.RETRY_FROM_ZERO
    responseCode == 416 && existingBytes > 0L && contentRange?.total == existingBytes ->
        AppUpdateResponseAction.COMPLETE
    responseCode == 416 -> AppUpdateResponseAction.RETRY_FROM_ZERO
    else -> AppUpdateResponseAction.FAIL
}

internal fun parseAppUpdateContentRange(value: String?): AppUpdateContentRange? {
    val match = value?.trim()?.let(CONTENT_RANGE_PATTERN::matchEntire) ?: return null
    val start = match.groupValues[1].takeIf(String::isNotEmpty)?.toLongOrNull()
    val end = match.groupValues[2].takeIf(String::isNotEmpty)?.toLongOrNull()
    val total = match.groupValues[3].takeUnless { it == "*" }?.toLongOrNull()
    if ((start == null) != (end == null)) return null
    if (start != null && (start < 0L || end!! < start)) return null
    if (total != null && total <= 0L) return null
    return AppUpdateContentRange(start = start, endInclusive = end, total = total)
}

internal fun isTrustedAppUpdateInitialUrl(value: String): Boolean {
    val uri = parseSecureUpdateUri(value) ?: return false
    return uri.host.equals("github.com", ignoreCase = true) &&
        uri.path.orEmpty().startsWith(APP_UPDATE_RELEASE_PATH_PREFIX)
}

internal fun isTrustedAppUpdateRedirectUrl(value: String): Boolean {
    val uri = parseSecureUpdateUri(value) ?: return false
    val host = uri.host.lowercase()
    return host == "github.com" || host.endsWith(".githubusercontent.com")
}

internal fun isRejectedAppUpdateContentType(value: String?): Boolean {
    val mediaType = value
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase()
        .orEmpty()
    return mediaType.startsWith("text/") ||
        mediaType == "application/json" ||
        mediaType.endsWith("+json")
}

private fun parseSecureUpdateUri(value: String): URI? = runCatching { URI(value) }
    .getOrNull()
    ?.takeIf { uri ->
        uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null &&
            (uri.port == -1 || uri.port == 443)
    }

private val CONTENT_RANGE_PATTERN = Regex(
    pattern = """bytes (?:(\d+)-(\d+)|\*)/(\d+|\*)""",
    option = RegexOption.IGNORE_CASE,
)
private const val APP_UPDATE_RELEASE_PATH_PREFIX =
    "/stansful/ssh-vpn-client-kotlin/releases/download/"
