package com.stansful.sshvpnclient.data.proxy

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import com.stansful.sshvpnclient.domain.model.ProxyImportResult
import com.stansful.sshvpnclient.domain.model.ProxyProfile
import com.stansful.sshvpnclient.domain.model.ProxyProfileSource
import com.stansful.sshvpnclient.domain.model.ProxyProfileSummary
import com.stansful.sshvpnclient.domain.model.ProxyTunnelTestResult
import com.stansful.sshvpnclient.domain.repository.ProxyProfileRepository
import com.stansful.sshvpnclient.domain.repository.ProxySourceConnectionFactory
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The If-None-Match decision, and its round trip through the synchronizer's SharedPreferences. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class PublicProxySourceEtagTest {
    private val repository = ImportCountingRepository()

    @Test
    fun `successful sync stores the ETag with the parser revision and the next sync sends it`() = runBlocking {
        val synchronizer = synchronizer()
        val first = OneResponseSource(HttpURLConnection.HTTP_OK, etag = ETAG)
        val second = OneResponseSource(HttpURLConnection.HTTP_NOT_MODIFIED)

        synchronizer.synchronize(force = false, connectionFactory = first)
        val result = synchronizer.synchronize(force = false, connectionFactory = second)

        assertNull(first.ifNoneMatch())
        assertEquals(ETAG, second.ifNoneMatch())
        assertTrue(result.notModified)
        assertEquals(1, repository.imports)
    }

    @Test
    fun `ETag stored by a build without parser revisions is not sent after the upgrade`() = runBlocking {
        // Exactly what builds before parser revisions wrote; the keys are part of the upgrade contract.
        preferences().edit(commit = true) {
            putString("etag", ETAG)
            putString("etag_url", SOURCE_URL)
        }
        val upgraded = OneResponseSource(HttpURLConnection.HTTP_OK, etag = ETAG)
        val next = OneResponseSource(HttpURLConnection.HTTP_NOT_MODIFIED)

        val result = synchronizer().synchronize(force = false, connectionFactory = upgraded)
        synchronizer().synchronize(force = false, connectionFactory = next)

        assertNull(upgraded.ifNoneMatch())
        assertFalse(result.notModified)
        assertEquals(1, repository.imports)
        assertEquals(ETAG, next.ifNoneMatch())
    }

    @Test
    fun `ETag stored by an older parser revision is not sent by the synchronizer`() = runBlocking {
        preferences().edit(commit = true) {
            putString("etag", ETAG)
            putString("etag_url", SOURCE_URL)
            putInt("etag_parser_revision", IMPORT_PARSER_REVISION - 1)
        }
        val source = OneResponseSource(HttpURLConnection.HTTP_OK, etag = ETAG)

        synchronizer().synchronize(force = false, connectionFactory = source)

        assertNull(source.ifNoneMatch())
        assertEquals(1, repository.imports)
    }

    @Test
    fun `conditional request reuses the ETag issued for the current source`() {
        assertEquals(
            ETAG,
            proxySourceIfNoneMatch(
                force = false,
                storedEtag = ETAG,
                storedEtagUrl = SOURCE_URL,
                storedEtagParserRevision = IMPORT_PARSER_REVISION,
                sourceUrl = SOURCE_URL,
            ),
        )
    }

    @Test
    fun `forced refresh never sends If-None-Match`() {
        assertNull(
            proxySourceIfNoneMatch(
                force = true,
                storedEtag = ETAG,
                storedEtagUrl = SOURCE_URL,
                storedEtagParserRevision = IMPORT_PARSER_REVISION,
                sourceUrl = SOURCE_URL,
            ),
        )
    }

    @Test
    fun `ETag issued by a previous source URL is not sent to the new source`() {
        assertNull(
            proxySourceIfNoneMatch(
                force = false,
                storedEtag = ETAG,
                storedEtagUrl = "https://previous.example.test/list.txt",
                storedEtagParserRevision = IMPORT_PARSER_REVISION,
                sourceUrl = SOURCE_URL,
            ),
        )
    }

    @Test
    fun `legacy ETag without a recorded URL forces one full download`() {
        assertNull(
            proxySourceIfNoneMatch(
                force = false,
                storedEtag = ETAG,
                storedEtagUrl = null,
                storedEtagParserRevision = IMPORT_PARSER_REVISION,
                sourceUrl = SOURCE_URL,
            ),
        )
    }

    @Test
    fun `missing ETag makes an unconditional request`() {
        assertNull(
            proxySourceIfNoneMatch(
                force = false,
                storedEtag = null,
                storedEtagUrl = SOURCE_URL,
                storedEtagParserRevision = IMPORT_PARSER_REVISION,
                sourceUrl = SOURCE_URL,
            ),
        )
    }

    @Test
    fun `ETag stored by an older parser revision forces one full download`() {
        assertNull(
            proxySourceIfNoneMatch(
                force = false,
                storedEtag = ETAG,
                storedEtagUrl = SOURCE_URL,
                storedEtagParserRevision = IMPORT_PARSER_REVISION - 1,
                sourceUrl = SOURCE_URL,
            ),
        )
    }

    @Test
    fun `ETag stored before parser revisions were recorded forces one full download`() {
        assertNull(
            proxySourceIfNoneMatch(
                force = false,
                storedEtag = ETAG,
                storedEtagUrl = SOURCE_URL,
                storedEtagParserRevision = null,
                sourceUrl = SOURCE_URL,
            ),
        )
    }

    private fun synchronizer() = PublicProxySourceSynchronizer(
        context = ApplicationProvider.getApplicationContext(),
        proxyProfileRepository = repository,
        sourceUrl = SOURCE_URL,
        preferencesName = PREFERENCES_NAME,
    )

    private fun preferences(): SharedPreferences = ApplicationProvider.getApplicationContext<Application>()
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private companion object {
        const val ETAG = "\"list-v2\""
        const val SOURCE_URL = "https://current.example.test/list.txt"
        const val PREFERENCES_NAME = "etag-test-proxy-sync"
    }
}

/** Serves one canned response and keeps the connection so the test can read its request headers. */
private class OneResponseSource(
    private val responseCode: Int,
    private val etag: String? = null,
) : ProxySourceConnectionFactory {
    private var connection: CannedHttpConnection? = null

    override fun open(url: URL): URLConnection {
        check(connection == null) { "Each sync must open its own source" }
        return CannedHttpConnection(url, responseCode, etag).also { connection = it }
    }

    fun ifNoneMatch(): String? = checkNotNull(connection) { "Source was never opened" }
        .getRequestProperty("If-None-Match")
}

private class CannedHttpConnection(
    url: URL,
    private val code: Int,
    private val etag: String?,
) : HttpURLConnection(url) {
    override fun getResponseCode(): Int = code

    override fun getHeaderField(name: String?): String? = etag.takeIf { name.equals("ETag", ignoreCase = true) }

    override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun disconnect() = Unit

    override fun usingProxy(): Boolean = false

    override fun connect() = Unit
}

private class ImportCountingRepository : ProxyProfileRepository {
    var imports = 0
        private set

    override suspend fun import(
        text: String,
        source: ProxyProfileSource,
        sourceUrl: String?,
    ): ProxyImportResult {
        imports += 1
        return ProxyImportResult(added = 0, updated = 0, duplicates = 0, invalid = 0, unsupported = 0, total = 0)
    }

    override fun observeSummaries(): Flow<List<ProxyProfileSummary>> = emptyFlow()

    override suspend fun getById(id: String): ProxyProfile? = null

    override suspend fun getSelected(): ProxyProfile? = null

    override suspend fun update(id: String, rawUri: String): ProxyImportResult = error("Not used by source sync")

    override suspend fun select(id: String) = Unit

    override suspend fun setPinned(id: String, pinned: Boolean) = Unit

    override suspend fun delete(ids: Set<String>) = Unit

    override suspend fun deleteUnavailableExceptPinned(): Int = 0

    override suspend fun saveTestResult(result: ProxyTunnelTestResult) = Unit
}
