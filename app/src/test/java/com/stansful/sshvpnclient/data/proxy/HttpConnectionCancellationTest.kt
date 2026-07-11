package com.stansful.sshvpnclient.data.proxy

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpConnectionCancellationTest {
    @Test
    fun `cancellation promptly disconnects a blocking HTTP call`() = runBlocking {
        val connection = BlockingHttpConnection()
        val request = launch(Dispatchers.IO) {
            connection.useDisconnectingOnCancellation { responseCode }
        }
        assertTrue("HTTP call did not start", connection.responseEntered.await(1, TimeUnit.SECONDS))

        request.cancel()

        assertTrue("Cancellation did not disconnect HTTP", connection.disconnected.await(1, TimeUnit.SECONDS))
        withTimeout(2_000L) { request.join() }
        assertTrue(request.isCancelled)
    }

    @Test
    fun `normal completion disconnects once and leaves no watcher child`() = runBlocking {
        val connection = ImmediateHttpConnection()

        val response = withTimeout(1_000L) {
            connection.useDisconnectingOnCancellation { responseCode }
        }

        assertEquals(HttpURLConnection.HTTP_NOT_MODIFIED, response)
        assertEquals(1, connection.disconnectCount.get())
    }
}

private class BlockingHttpConnection : HttpURLConnection(URL("https://example.test")) {
    val responseEntered = CountDownLatch(1)
    val disconnected = CountDownLatch(1)
    private val releaseResponse = CountDownLatch(1)

    override fun getResponseCode(): Int {
        responseEntered.countDown()
        check(releaseResponse.await(5, TimeUnit.SECONDS)) { "HTTP response was never released" }
        throw IOException("connection disconnected")
    }

    override fun disconnect() {
        disconnected.countDown()
        releaseResponse.countDown()
    }

    override fun usingProxy(): Boolean = false

    override fun connect() = Unit
}

private class ImmediateHttpConnection : HttpURLConnection(URL("https://example.test")) {
    val disconnectCount = AtomicInteger(0)

    override fun getResponseCode(): Int = HTTP_NOT_MODIFIED

    override fun disconnect() {
        disconnectCount.incrementAndGet()
    }

    override fun usingProxy(): Boolean = false

    override fun connect() = Unit
}
