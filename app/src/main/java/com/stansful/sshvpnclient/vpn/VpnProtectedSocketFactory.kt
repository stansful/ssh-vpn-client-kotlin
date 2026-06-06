package com.stansful.sshvpnclient.vpn

import com.jcraft.jsch.SocketFactory
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException

class VpnProtectedSocketFactory(
    private val protectSocket: (Socket) -> Boolean,
    private val connectTimeoutMs: Int,
    private val log: (String) -> Unit,
) : SocketFactory {
    override fun createSocket(host: String, port: Int): Socket {
        val socket = Socket()
        val startedAt = System.currentTimeMillis()
        try {
            log("SSH socket: opening TCP socket for $host:$port")
            val connectStartedAt = System.currentTimeMillis()
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            log(
                "SSH socket: TCP connected in ${System.currentTimeMillis() - connectStartedAt}ms; " +
                    "local=${socket.safeLocalEndpoint()} remote=${socket.safeRemoteEndpoint()}",
            )
            val protected = protectSocket(socket)
            log("SSH socket: protect connected socket result=$protected")
            if (!protected) {
                throw IOException("Could not protect connected SSH socket from VPN routing")
            }
            return socket
        } catch (error: UnknownHostException) {
            log("SSH socket: unknown host after ${System.currentTimeMillis() - startedAt}ms: ${error.message}")
            socket.close()
            throw error
        } catch (error: IOException) {
            log(
                "SSH socket: IO failure after ${System.currentTimeMillis() - startedAt}ms: " +
                    "${error::class.java.simpleName}: ${error.message}",
            )
            socket.close()
            throw error
        } catch (error: RuntimeException) {
            log(
                "SSH socket: runtime failure after ${System.currentTimeMillis() - startedAt}ms: " +
                    "${error::class.java.simpleName}: ${error.message}",
            )
            socket.close()
            throw error
        }
    }

    override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()

    override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()

    private fun Socket.safeLocalEndpoint(): String {
        return runCatching { "${localAddress?.hostAddress ?: "unknown"}:$localPort" }
            .getOrElse { "unknown" }
    }

    private fun Socket.safeRemoteEndpoint(): String {
        return runCatching { "${inetAddress?.hostAddress ?: "unknown"}:$port" }
            .getOrElse { "unknown" }
    }
}
