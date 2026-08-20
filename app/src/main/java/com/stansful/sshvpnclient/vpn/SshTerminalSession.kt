package com.stansful.sshvpnclient.vpn

import com.jcraft.jsch.ChannelShell
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class SshTerminalSession internal constructor(
    private val channel: ChannelShell,
    private val inputStream: InputStream,
    private val outputStream: OutputStream,
    private val onOutput: (String) -> Unit,
    private val onClosed: (String) -> Unit,
    readerDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : Closeable {
    private val isOpen = AtomicBoolean(true)
    private val readerScope = CoroutineScope(SupervisorJob() + readerDispatcher)
    private val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var readerJob: Job? = null

    val isActive: Boolean
        get() = isOpen.get() && channel.isConnected

    internal fun start() {
        check(readerJob == null) { "Terminal reader is already started" }
        readerJob = readerScope.launch { readLoop() }
    }

    @Synchronized
    fun sendLine(command: String) {
        check(isActive) { "Terminal is closed" }
        outputStream.write(command.toByteArray(Charsets.UTF_8))
        outputStream.write(NEW_LINE)
        outputStream.flush()
    }

    override fun close() {
        if (!isOpen.getAndSet(false)) {
            return
        }
        readerScope.cancel()
        closeScope.launch {
            try {
                runCatching { channel.disconnect() }
            } finally {
                closeScope.cancel()
            }
        }
    }

    private fun readLoop() {
        val buffer = ByteArray(BUFFER_SIZE)
        val output = ByteArrayOutputStream(MAX_OUTPUT_BATCH_SIZE)
        try {
            while (isOpen.get()) {
                val bytesRead = inputStream.read(buffer)
                if (bytesRead < 0) break
                if (bytesRead == 0) continue

                output.reset()
                output.write(buffer, 0, bytesRead)
                while (inputStream.available() > 0 && output.size() < MAX_OUTPUT_BATCH_SIZE) {
                    val nextRead = inputStream.read(
                        buffer,
                        0,
                        minOf(buffer.size, MAX_OUTPUT_BATCH_SIZE - output.size()),
                    )
                    if (nextRead <= 0) break
                    output.write(buffer, 0, nextRead)
                }
                onOutput(output.toString(Charsets.UTF_8.name()))
            }
            if (isOpen.get()) {
                onClosed("remote shell closed")
            }
        } catch (cancellation: CancellationException) {
            // Scope cancellation is a normal shutdown path, not a remote terminal failure.
            throw cancellation
        } catch (error: Exception) {
            if (isOpen.get()) {
                onClosed(error.message ?: error::class.java.simpleName)
            }
        } finally {
            close()
        }
    }

    private companion object {
        const val BUFFER_SIZE = 8 * 1_024
        const val MAX_OUTPUT_BATCH_SIZE = 32 * 1_024
        const val NEW_LINE = '\n'.code
    }
}
