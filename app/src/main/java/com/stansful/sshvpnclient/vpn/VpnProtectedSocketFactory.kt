package com.stansful.sshvpnclient.vpn

import com.jcraft.jsch.SocketFactory
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class VpnProtectedSocketFactory(
    private val protectSocket: (Socket) -> Boolean,
    private val connectTimeoutMs: Int,
    private val log: (String) -> Unit,
) : SocketFactory {
    override fun createSocket(host: String, port: Int): Socket {
        val startedAt = System.currentTimeMillis()
        val deadlineNanos = System.nanoTime() + connectTimeoutMs.coerceAtLeast(1) * NANOS_PER_MILLISECOND
        var socket: Socket? = null
        try {
            val route = (protectSocket as? VpnSocketRouteProvider)?.routeFor(host)
            val addresses = route?.addresses ?: InetAddress.getAllByName(host).toList()
            if (addresses.isEmpty()) throw UnknownHostException(host)
            log(
                "SSH socket: resolved $host to ${addresses.size} address(es)" +
                    (route?.let { " on underlying network ${it.description}" } ?: ""),
            )
            var lastError: IOException? = null
            addresses.forEachIndexed { index, address ->
                val candidate = Socket()
                socket = candidate
                try {
                    log("SSH socket: opening TCP socket to ${address.hostAddress}:$port")
                    configureSocket(candidate)
                    route?.bind(candidate)
                    if (route != null) {
                        log("SSH socket: bound unconnected socket to underlying network ${route.description}")
                    }
                    val protected = protectSocket(candidate)
                    log("SSH socket: protect unconnected socket result=$protected")
                    if (!protected) {
                        throw IOException("Could not protect SSH socket from VPN routing")
                    }
                    val connectStartedAt = System.currentTimeMillis()
                    val addressTimeoutMs = remainingAddressTimeoutMs(deadlineNanos)
                    candidate.connect(InetSocketAddress(address, port), addressTimeoutMs)
                    log(
                        "SSH socket: TCP connected in ${System.currentTimeMillis() - connectStartedAt}ms; " +
                            "local=${candidate.safeLocalEndpoint()} remote=${candidate.safeRemoteEndpoint()}",
                    )
                    return candidate
                } catch (error: IOException) {
                    lastError = error
                    runCatching { candidate.close() }
                    if (index < addresses.lastIndex) {
                        log(
                            "SSH socket: address ${address.hostAddress} failed " +
                                "(${error.message ?: error::class.java.simpleName}); trying next address",
                        )
                    }
                }
            }
            throw lastError ?: IOException("Could not connect SSH socket")
        } catch (error: UnknownHostException) {
            log("SSH socket: unknown host after ${System.currentTimeMillis() - startedAt}ms: ${error.message}")
            runCatching { socket?.close() }
            throw error
        } catch (error: IOException) {
            log(
                "SSH socket: IO failure after ${System.currentTimeMillis() - startedAt}ms: " +
                    "${error::class.java.simpleName}: ${error.message}",
            )
            runCatching { socket?.close() }
            throw error
        } catch (error: RuntimeException) {
            log(
                "SSH socket: runtime failure after ${System.currentTimeMillis() - startedAt}ms: " +
                    "${error::class.java.simpleName}: ${error.message}",
            )
            runCatching { socket?.close() }
            throw error
        }
    }

    override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()

    override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()

    private fun configureSocket(socket: Socket) {
        runCatching { socket.tcpNoDelay = true }
            .onFailure { log("SSH socket: could not enable TCP_NODELAY: ${it.message}") }
        runCatching { socket.keepAlive = true }
            .onFailure { log("SSH socket: could not enable SO_KEEPALIVE: ${it.message}") }
        runCatching { socket.sendBufferSize = SOCKET_BUFFER_SIZE_BYTES }
            .onFailure { log("SSH socket: could not request send buffer: ${it.message}") }
        runCatching { socket.receiveBufferSize = SOCKET_BUFFER_SIZE_BYTES }
            .onFailure { log("SSH socket: could not request receive buffer: ${it.message}") }
        log(
            "SSH socket options: tcpNoDelay=${runCatching { socket.tcpNoDelay }.getOrNull()}; " +
                "keepAlive=${runCatching { socket.keepAlive }.getOrNull()}; " +
                "sendBuffer=${runCatching { socket.sendBufferSize }.getOrNull()}; " +
                "receiveBuffer=${runCatching { socket.receiveBufferSize }.getOrNull()}",
        )
    }

    private fun remainingAddressTimeoutMs(deadlineNanos: Long): Int {
        val remainingNanos = deadlineNanos - System.nanoTime()
        if (remainingNanos <= 0L) {
            throw SocketTimeoutException("SSH socket connect deadline exceeded")
        }
        val remainingMs = (remainingNanos + NANOS_PER_MILLISECOND - 1L) / NANOS_PER_MILLISECOND
        return minOf(remainingMs, MAX_PER_ADDRESS_CONNECT_TIMEOUT_MS.toLong()).toInt().coerceAtLeast(1)
    }

    private fun Socket.safeLocalEndpoint(): String {
        return runCatching { "${localAddress?.hostAddress ?: "unknown"}:$localPort" }
            .getOrElse { "unknown" }
    }

    private fun Socket.safeRemoteEndpoint(): String {
        return runCatching { "${inetAddress?.hostAddress ?: "unknown"}:$port" }
            .getOrElse { "unknown" }
    }

    private companion object {
        const val SOCKET_BUFFER_SIZE_BYTES = 4 * 1_024 * 1_024
        const val MAX_PER_ADDRESS_CONNECT_TIMEOUT_MS = 5_000
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
