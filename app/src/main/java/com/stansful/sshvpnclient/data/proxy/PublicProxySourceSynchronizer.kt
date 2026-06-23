package com.stansful.sshvpnclient.data.proxy

import android.content.Context
import androidx.core.content.edit
import com.stansful.sshvpnclient.domain.model.OpenSourcePolicy
import com.stansful.sshvpnclient.domain.model.ProxyImportResult
import com.stansful.sshvpnclient.domain.model.ProxyProfileSource
import com.stansful.sshvpnclient.domain.model.ProxySyncResult
import com.stansful.sshvpnclient.domain.repository.ProxyProfileRepository
import com.stansful.sshvpnclient.domain.repository.ProxySourceSynchronizer
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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

    override suspend fun synchronize(): ProxySyncResult = withContext(ioDispatcher) {
        val connection = (URL(OpenSourcePolicy.SOURCE_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            setRequestProperty("Accept", "text/plain")
            setRequestProperty("User-Agent", USER_AGENT)
            preferences.getString(KEY_ETAG, null)?.let { etag ->
                setRequestProperty("If-None-Match", etag)
            }
        }

        try {
            when (val responseCode = connection.responseCode) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> ProxySyncResult(
                    importResult = emptyResult(),
                    notModified = true,
                )
                HttpURLConnection.HTTP_OK -> {
                    val raw = readLimited(connection)
                    val result = proxyProfileRepository.import(
                        text = raw,
                        source = ProxyProfileSource.REMOTE,
                        sourceUrl = OpenSourcePolicy.SOURCE_URL,
                    )
                    preferences.edit {
                        connection.getHeaderField("ETag")?.let { putString(KEY_ETAG, it) }
                        putLong(KEY_LAST_SUCCESS_AT, System.currentTimeMillis())
                    }
                    ProxySyncResult(result, notModified = false)
                }
                else -> error("Public configuration source returned HTTP $responseCode")
            }
        } finally {
            connection.disconnect()
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

    private companion object {
        const val PREFERENCES_NAME = "open-source-proxy-sync"
        const val KEY_ETAG = "etag"
        const val KEY_LAST_SUCCESS_AT = "last_success_at"
        const val USER_AGENT = "shadow-ssh-android-opensource-sync"
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 15_000
        const val MAX_RESPONSE_BYTES = 2 * 1_024 * 1_024
        const val BUFFER_SIZE = 16 * 1_024
    }
}
