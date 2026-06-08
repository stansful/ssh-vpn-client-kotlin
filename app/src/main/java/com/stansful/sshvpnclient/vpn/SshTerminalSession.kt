package com.stansful.sshvpnclient.vpn

import com.jcraft.jsch.ChannelShell
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class SshTerminalSession internal constructor(
    private val channel: ChannelShell,
    private val inputStream: InputStream,
    private val outputStream: OutputStream,
    private val onOutput: (String) -> Unit,
    private val onClosed: (String) -> Unit,
) : Closeable {
    private val isOpen = AtomicBoolean(true)
    private val reader = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, READER_THREAD_NAME).apply {
            isDaemon = true
        }
    }

    val isActive: Boolean
        get() = isOpen.get() && channel.isConnected

    internal fun start() {
        reader.execute(::readLoop)
    }

    @Synchronized
    fun sendLine(command: String) {
        check(isActive) { "Terminal is closed" }
        outputStream.write(command.toByteArray(Charsets.UTF_8))
        outputStream.write(NEW_LINE)
        outputStream.flush()
    }

    override fun close() {
        if (isOpen.getAndSet(false)) {
            runCatching { channel.disconnect() }
        }
        reader.shutdownNow()
    }

    private fun readLoop() {
        val buffer = ByteArray(BUFFER_SIZE)
        try {
            while (isOpen.get()) {
                val bytesRead = inputStream.read(buffer)
                if (bytesRead < 0) break
                if (bytesRead > 0) {
                    onOutput(String(buffer, offset = 0, length = bytesRead, charset = Charsets.UTF_8))
                }
            }
            if (isOpen.get()) {
                onClosed("remote shell closed")
            }
        } catch (error: Exception) {
            if (isOpen.get()) {
                onClosed(error.message ?: error::class.java.simpleName)
            }
        } finally {
            close()
        }
    }

    private companion object {
        const val BUFFER_SIZE = 4096
        const val READER_THREAD_NAME = "ssh-terminal-reader"
        const val NEW_LINE = '\n'.code
    }
}
