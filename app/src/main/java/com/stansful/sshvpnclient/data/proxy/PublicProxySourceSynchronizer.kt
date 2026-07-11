package com.stansful.sshvpnclient.data.proxy

import android.content.Context
import androidx.core.content.edit
import com.stansful.sshvpnclient.domain.model.OpenSourcePolicy
import com.stansful.sshvpnclient.domain.model.ProxyImportResult
import com.stansful.sshvpnclient.domain.model.ProxyProfileSource
import com.stansful.sshvpnclient.domain.model.ProxySyncResult
import com.stansful.sshvpnclient.domain.repository.ProxyProfileRepository
import com.stansful.sshvpnclient.domain.repository.ProxySourceConnectionFactory
import com.stansful.sshvpnclient.domain.repository.ProxySourceSynchronizer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class PublicProxySourceSynchronizer(
    context: Context,
    private val proxyProfileRepository: ProxyProfileRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ProxySourceSynchronizer {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val synchronizationMutex = Mutex()

    override suspend fun synchronize(
        force: Boolean,
        connectionFactory: ProxySourceConnectionFactory?,
    ): ProxySyncResult = synchronizationMutex.withLock {
        withContext(ioDispatcher) {
            synchronizeLocked(force, connectionFactory)
        }
    }

    private suspend fun synchronizeLocked(
        force: Boolean,
        connectionFactory: ProxySourceConnectionFactory?,
    ): ProxySyncResult {
        val url = URL(OpenSourcePolicy.SOURCE_URL)
        val rawConnection = connectionFactory?.open(url) ?: url.openConnection()
        val connection = (rawConnection as? HttpURLConnection)
            ?: error("Public configuration source did not open an HTTP connection")
        connection.apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            setRequestProperty("Accept", "text/plain")
            setRequestProperty("User-Agent", USER_AGENT)
            if (!force) {
                preferences.getString(KEY_ETAG, null)?.let { etag ->
                    setRequestProperty("If-None-Match", etag)
                }
            }
        }

        return connection.useDisconnectingOnCancellation {
            currentCoroutineContext().ensureActive()
            when (val responseCode = responseCode) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> ProxySyncResult(
                    importResult = emptyResult(),
                    notModified = true,
                )
                HttpURLConnection.HTTP_OK -> {
                    val raw = readLimited(this)
                    currentCoroutineContext().ensureActive()
                    val result = proxyProfileRepository.import(
                        text = raw,
                        source = ProxyProfileSource.REMOTE,
                        sourceUrl = OpenSourcePolicy.SOURCE_URL,
                    )
                    preferences.edit {
                        getHeaderField("ETag")?.let { putString(KEY_ETAG, it) }
                        putLong(KEY_LAST_SUCCESS_AT, System.currentTimeMillis())
                    }
                    ProxySyncResult(result, notModified = false)
                }
                else -> {
                    val message = "Public configuration source returned HTTP $responseCode"
                    if (isTransientHttpStatus(responseCode)) {
                        throw IOException(message)
                    }
                    error(message)
                }
            }
        }
    }

    private fun readLimited(connection: HttpURLConnection): String {
        val declaredLength = connection.contentLengthLong
        require(declaredLength <= MAX_RESPONSE_BYTES || declaredLength < 0L) {
            "Public configuration source response is too large"
        }
        val output = ByteArrayOutputStream()
        connection.inputStream.buffered().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= MAX_RESPONSE_BYTES) {
                    "Public configuration source response is too large"
                }
                output.write(buffer, 0, read)
            }
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }

    private fun emptyResult() = ProxyImportResult(
        added = 0,
        updated = 0,
        duplicates = 0,
        invalid = 0,
        unsupported = 0,
        total = 0,
    )

    private fun isTransientHttpStatus(responseCode: Int): Boolean {
        return responseCode == HttpURLConnection.HTTP_CLIENT_TIMEOUT ||
            responseCode == HTTP_TOO_MANY_REQUESTS ||
            responseCode in HTTP_SERVER_ERROR_RANGE
    }

    private companion object {
        const val PREFERENCES_NAME = "open-source-proxy-sync"
        const val KEY_ETAG = "etag"
        const val KEY_LAST_SUCCESS_AT = "last_success_at"
        const val USER_AGENT = "shadow-ssh-android-opensource-sync"
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 15_000
        const val MAX_RESPONSE_BYTES = 2 * 1_024 * 1_024
        const val BUFFER_SIZE = 16 * 1_024
        const val HTTP_TOO_MANY_REQUESTS = 429
        val HTTP_SERVER_ERROR_RANGE = 500..599
    }
}

/**
 * [HttpURLConnection] performs blocking connect/read calls that coroutine cancellation cannot
 * interrupt. A structured child waits for cancellation on the shared IO pool and disconnects the
 * connection, which unblocks the owner coroutine. The surrounding scope cannot finish while the
 * watcher is still alive.
 */
internal suspend fun <T> HttpURLConnection.useDisconnectingOnCancellation(
    block: suspend HttpURLConnection.() -> T,
): T = coroutineScope {
    val blockFinished = AtomicBoolean(false)
    val cancellationWatcher = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            if (!blockFinished.get()) {
                runCatching { disconnect() }
            }
        }
    }
    try {
        try {
            block().also { currentCoroutineContext().ensureActive() }
        } catch (error: Throwable) {
            // A disconnect commonly surfaces as IOException. Preserve cancellation as the
            // externally visible cause so callers never turn a stopped worker into a retry.
            currentCoroutineContext().ensureActive()
            throw error
        }
    } finally {
        blockFinished.set(true)
        cancellationWatcher.cancel()
        runCatching { disconnect() }
    }
}
