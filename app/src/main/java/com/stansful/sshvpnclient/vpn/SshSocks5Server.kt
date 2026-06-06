package com.stansful.sshvpnclient.vpn

import com.jcraft.jsch.Channel
import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.Session
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

class SshSocks5Server(
    private val session: Session,
    private val log: (String) -> Unit,
) {
    private val stopped = AtomicBoolean(false)
    private val unsupportedUdpLogged = AtomicBoolean(false)
    private val executor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, THREAD_NAME).apply { isDaemon = true }
    }
    private val activeSockets = Collections.synchronizedSet(mutableSetOf<Socket>())
    private val activeUdpSockets = Collections.synchronizedSet(mutableSetOf<DatagramSocket>())
    private val activeChannels = Collections.synchronizedSet(mutableSetOf<Channel>())

    @Volatile
    private var serverSocket: ServerSocket? = null

    fun start(): Int {
        check(session.isConnected) { "SSH session is not connected" }
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(InetAddress.getByName(LOOPBACK_ADDRESS), 0))
        serverSocket = server
        executor.execute { acceptLoop(server) }
        return server.localPort
    }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        closeQuietly(serverSocket)
        snapshot(activeSockets).forEach(::closeQuietly)
        snapshot(activeUdpSockets).forEach(::closeQuietly)
        snapshot(activeChannels).forEach { channel -> channel.disconnect() }
        executor.shutdownNow()
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!stopped.get()) {
            try {
                val client = server.accept()
                activeSockets += client
                executor.execute { handleClient(client) }
            } catch (error: SocketException) {
                if (!stopped.get()) {
                    log("SOCKS accept failed: ${error.message}")
                }
            } catch (error: IOException) {
                if (!stopped.get()) {
                    log("SOCKS accept failed: ${error.message}")
                }
            }
        }
    }

    private fun handleClient(client: Socket) {
        try {
            client.tcpNoDelay = true
            client.soTimeout = HANDSHAKE_TIMEOUT_MS

            val input = DataInputStream(client.getInputStream())
            val output = client.getOutputStream()
            negotiate(input, output)

            val request = readRequest(input)
            when (request.command) {
                SOCKS_COMMAND_CONNECT -> handleConnect(client, output, request)
                SOCKS_COMMAND_UDP_ASSOCIATE -> handleUdpAssociate(client, input, output)
                else -> sendReply(output, SOCKS_REPLY_COMMAND_NOT_SUPPORTED)
            }
        } catch (_: EOFException) {
            // Client closed the SOCKS connection during handshake.
        } catch (error: IOException) {
            if (!isExpectedShutdown(error)) {
                log("SOCKS client failed: ${error.message}")
            }
        } catch (error: RuntimeException) {
            if (!isExpectedShutdown(error)) {
                log("SOCKS client failed: ${error.message}")
            }
        } finally {
            activeSockets -= client
            closeQuietly(client)
        }
    }

    private fun negotiate(input: DataInputStream, output: OutputStream) {
        val version = input.readUnsignedByte()
        val methodCount = input.readUnsignedByte()
        val methods = ByteArray(methodCount)
        input.readFully(methods)

        if (version != SOCKS_VERSION || methods.none { method -> method.toInt() == SOCKS_AUTH_NONE }) {
            output.write(byteArrayOf(SOCKS_VERSION.toByte(), SOCKS_AUTH_UNSUPPORTED.toByte()))
            output.flush()
            throw IOException("Unsupported SOCKS authentication method")
        }

        output.write(byteArrayOf(SOCKS_VERSION.toByte(), SOCKS_AUTH_NONE.toByte()))
        output.flush()
    }

    private fun readRequest(input: DataInputStream): SocksRequest {
        val version = input.readUnsignedByte()
        if (version != SOCKS_VERSION) {
            throw IOException("Unsupported SOCKS version: $version")
        }
        val command = input.readUnsignedByte()
        input.readUnsignedByte()
        val address = readSocksAddress(input)
        val port = input.readUnsignedShort()
        return SocksRequest(command, address, port)
    }

    private fun readSocksAddress(input: DataInputStream): SocksAddress {
        val addressType = input.readUnsignedByte()
        return when (addressType) {
            SOCKS_ADDRESS_IPV4 -> {
                val bytes = ByteArray(IPV4_BYTES)
                input.readFully(bytes)
                SocksAddress(
                    host = InetAddress.getByAddress(bytes).hostAddress.orEmpty(),
                    header = byteArrayOf(addressType.toByte()) + bytes,
                )
            }

            SOCKS_ADDRESS_DOMAIN -> {
                val length = input.readUnsignedByte()
                val bytes = ByteArray(length)
                input.readFully(bytes)
                SocksAddress(
                    host = String(bytes, StandardCharsets.US_ASCII),
                    header = byteArrayOf(addressType.toByte(), length.toByte()) + bytes,
                )
            }

            SOCKS_ADDRESS_IPV6 -> {
                val bytes = ByteArray(IPV6_BYTES)
                input.readFully(bytes)
                SocksAddress(
                    host = InetAddress.getByAddress(bytes).hostAddress.orEmpty(),
                    header = byteArrayOf(addressType.toByte()) + bytes,
                )
            }

            else -> throw IOException("Unsupported SOCKS address type: $addressType")
        }
    }

    private fun handleConnect(
        client: Socket,
        output: OutputStream,
        request: SocksRequest,
    ) {
        var channel: ChannelDirectTCPIP? = null
        var replySent = false
        try {
            channel = session.openChannel("direct-tcpip") as ChannelDirectTCPIP
            activeChannels += channel
            channel.setHost(request.address.host)
            channel.setPort(request.port)
            channel.setOrgIPAddress(client.inetAddress?.hostAddress ?: LOOPBACK_ADDRESS)
            channel.setOrgPort(client.port)

            val sshInput = channel.getInputStream()
            val sshOutput = channel.getOutputStream()
            channel.connect(CHANNEL_CONNECT_TIMEOUT_MS)

            sendReply(output, SOCKS_REPLY_SUCCESS)
            replySent = true
            client.soTimeout = 0

            val upstream: Future<*> = executor.submit {
                try {
                    copyStream(client.getInputStream(), sshOutput)
                } finally {
                    closeQuietly(sshOutput)
                }
            }
            try {
                copyStream(sshInput, client.getOutputStream())
            } finally {
                upstream.cancel(true)
            }
        } catch (error: Exception) {
            if (!replySent && !stopped.get()) {
                runCatching {
                    sendReply(output, SOCKS_REPLY_HOST_UNREACHABLE)
                }
            }
            if (!isExpectedShutdown(error)) {
                log("SOCKS CONNECT ${request.address.host}:${request.port} failed: ${error.message}")
            }
        } finally {
            channel?.let {
                activeChannels -= it
                it.disconnect()
            }
        }
    }

    private fun handleUdpAssociate(
        client: Socket,
        input: DataInputStream,
        output: OutputStream,
    ) {
        val udpSocket = DatagramSocket(
            InetSocketAddress(InetAddress.getByName(LOOPBACK_ADDRESS), 0),
        )
        val alive = AtomicBoolean(true)
        activeUdpSockets += udpSocket
        sendReply(
            output = output,
            replyCode = SOCKS_REPLY_SUCCESS,
            bindAddress = InetAddress.getByName(LOOPBACK_ADDRESS),
            bindPort = udpSocket.localPort,
        )

        val relay = executor.submit { udpRelayLoop(udpSocket, alive) }
        try {
            client.soTimeout = 0
            while (alive.get() && input.read() != -1) {
                // The TCP control connection lifetime owns the UDP association.
            }
        } catch (_: IOException) {
            // Closing the TCP control connection tears down the UDP association.
        } finally {
            alive.set(false)
            relay.cancel(true)
            activeUdpSockets -= udpSocket
            closeQuietly(udpSocket)
        }
    }

    private fun udpRelayLoop(udpSocket: DatagramSocket, alive: AtomicBoolean) {
        val buffer = ByteArray(MAX_UDP_PACKET_BYTES)
        while (!stopped.get() && alive.get()) {
            try {
                val datagram = DatagramPacket(buffer, buffer.size)
                udpSocket.receive(datagram)
                val clientEndpoint = InetSocketAddress(datagram.address, datagram.port)
                val packet = parseUdpPacket(datagram.data, datagram.length) ?: continue
                if (packet.fragment != SOCKS_UDP_FRAGMENT_NONE) {
                    continue
                }
                if (packet.port == DNS_PORT) {
                    executor.execute {
                        handleDnsPacket(udpSocket, clientEndpoint, packet)
                    }
                } else if (unsupportedUdpLogged.compareAndSet(false, true)) {
                    log("UDP forwarding over SSH is limited to DNS; non-DNS UDP packets are dropped")
                }
            } catch (_: SocketException) {
                if (!alive.get() || stopped.get()) return
            } catch (error: IOException) {
                if (!isExpectedShutdown(error) && alive.get()) {
                    log("SOCKS UDP relay failed: ${error.message}")
                }
            } catch (error: RuntimeException) {
                if (!isExpectedShutdown(error) && alive.get()) {
                    log("SOCKS UDP relay failed: ${error.message}")
                }
            }
        }
    }

    private fun parseUdpPacket(data: ByteArray, length: Int): SocksUdpPacket? {
        if (length < MIN_UDP_HEADER_BYTES) return null
        if (data[0].toInt() != 0 || data[1].toInt() != 0) return null
        val fragment = data[2].toInt() and BYTE_MASK
        var offset = 3
        val addressType = data[offset].toInt() and BYTE_MASK
        offset += 1

        val address = when (addressType) {
            SOCKS_ADDRESS_IPV4 -> {
                if (length < offset + IPV4_BYTES + PORT_BYTES) return null
                val bytes = data.copyOfRange(offset, offset + IPV4_BYTES)
                offset += IPV4_BYTES
                SocksAddress(
                    host = InetAddress.getByAddress(bytes).hostAddress.orEmpty(),
                    header = byteArrayOf(addressType.toByte()) + bytes,
                )
            }

            SOCKS_ADDRESS_DOMAIN -> {
                if (length < offset + 1) return null
                val domainLength = data[offset].toInt() and BYTE_MASK
                offset += 1
                if (length < offset + domainLength + PORT_BYTES) return null
                val bytes = data.copyOfRange(offset, offset + domainLength)
                offset += domainLength
                SocksAddress(
                    host = String(bytes, StandardCharsets.US_ASCII),
                    header = byteArrayOf(addressType.toByte(), domainLength.toByte()) + bytes,
                )
            }

            SOCKS_ADDRESS_IPV6 -> {
                if (length < offset + IPV6_BYTES + PORT_BYTES) return null
                val bytes = data.copyOfRange(offset, offset + IPV6_BYTES)
                offset += IPV6_BYTES
                SocksAddress(
                    host = InetAddress.getByAddress(bytes).hostAddress.orEmpty(),
                    header = byteArrayOf(addressType.toByte()) + bytes,
                )
            }

            else -> return null
        }

        val port = ((data[offset].toInt() and BYTE_MASK) shl BYTE_BITS) or
            (data[offset + 1].toInt() and BYTE_MASK)
        offset += PORT_BYTES
        if (offset > length) return null

        return SocksUdpPacket(
            fragment = fragment,
            address = address,
            port = port,
            payload = data.copyOfRange(offset, length),
        )
    }

    private fun handleDnsPacket(
        udpSocket: DatagramSocket,
        clientEndpoint: InetSocketAddress,
        packet: SocksUdpPacket,
    ) {
        try {
            val response = queryDnsOverSsh(packet.address.host, packet.port, packet.payload)
            val responsePacket = buildUdpResponse(packet.address, packet.port, response)
            synchronized(udpSocket) {
                udpSocket.send(
                    DatagramPacket(
                        responsePacket,
                        responsePacket.size,
                        clientEndpoint.address,
                        clientEndpoint.port,
                    ),
                )
            }
        } catch (error: Exception) {
            if (!isExpectedShutdown(error)) {
                log("DNS over SSH failed for ${packet.address.host}:${packet.port}: ${error.message}")
            }
        }
    }

    private fun queryDnsOverSsh(host: String, port: Int, query: ByteArray): ByteArray {
        var channel: ChannelDirectTCPIP? = null
        try {
            channel = session.openChannel("direct-tcpip") as ChannelDirectTCPIP
            activeChannels += channel
            channel.setHost(host)
            channel.setPort(port)
            channel.setOrgIPAddress(LOOPBACK_ADDRESS)
            channel.setOrgPort(0)

            val remoteInput = DataInputStream(channel.getInputStream())
            val remoteOutput = channel.getOutputStream()
            channel.connect(DNS_CONNECT_TIMEOUT_MS)

            remoteOutput.write((query.size shr BYTE_BITS) and BYTE_MASK)
            remoteOutput.write(query.size and BYTE_MASK)
            remoteOutput.write(query)
            remoteOutput.flush()

            val responseLength = remoteInput.readUnsignedShort()
            if (responseLength <= 0 || responseLength > MAX_DNS_RESPONSE_BYTES) {
                throw IOException("Invalid DNS response length: $responseLength")
            }
            val response = ByteArray(responseLength)
            remoteInput.readFully(response)
            return response
        } finally {
            channel?.let {
                activeChannels -= it
                it.disconnect()
            }
        }
    }

    private fun buildUdpResponse(
        address: SocksAddress,
        port: Int,
        payload: ByteArray,
    ): ByteArray {
        return byteArrayOf(0, 0, SOCKS_UDP_FRAGMENT_NONE.toByte()) +
            address.header +
            byteArrayOf(
                ((port shr BYTE_BITS) and BYTE_MASK).toByte(),
                (port and BYTE_MASK).toByte(),
            ) +
            payload
    }

    private fun sendReply(
        output: OutputStream,
        replyCode: Int,
        bindAddress: InetAddress = InetAddress.getByName(ANY_IPV4_ADDRESS),
        bindPort: Int = 0,
    ) {
        val address = bindAddress.address
        output.write(
            byteArrayOf(
                SOCKS_VERSION.toByte(),
                replyCode.toByte(),
                0,
                SOCKS_ADDRESS_IPV4.toByte(),
            ) + address + byteArrayOf(
                ((bindPort shr BYTE_BITS) and BYTE_MASK).toByte(),
                (bindPort and BYTE_MASK).toByte(),
            ),
        )
        output.flush()
    }

    private fun copyStream(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        while (!stopped.get()) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            output.write(buffer, 0, read)
            output.flush()
        }
    }

    private fun closeQuietly(socket: Socket?) {
        try {
            socket?.close()
        } catch (_: IOException) {
        }
    }

    private fun closeQuietly(socket: ServerSocket?) {
        try {
            socket?.close()
        } catch (_: IOException) {
        }
    }

    private fun closeQuietly(socket: DatagramSocket?) {
        socket?.close()
    }

    private fun closeQuietly(output: OutputStream?) {
        try {
            output?.close()
        } catch (_: IOException) {
        }
    }

    private fun isExpectedShutdown(error: Throwable): Boolean {
        val message = error.message.orEmpty()
        if (stopped.get() || Thread.currentThread().isInterrupted) return true
        return message.contains("executor", ignoreCase = true) &&
            message.contains("shut", ignoreCase = true)
    }

    private fun <T> snapshot(set: MutableSet<T>): List<T> {
        return synchronized(set) { set.toList() }
    }

    private data class SocksRequest(
        val command: Int,
        val address: SocksAddress,
        val port: Int,
    )

    private data class SocksAddress(
        val host: String,
        val header: ByteArray,
    )

    private data class SocksUdpPacket(
        val fragment: Int,
        val address: SocksAddress,
        val port: Int,
        val payload: ByteArray,
    )

    private companion object {
        const val THREAD_NAME = "ssh-socks5"
        const val LOOPBACK_ADDRESS = "127.0.0.1"
        const val ANY_IPV4_ADDRESS = "0.0.0.0"
        const val SOCKS_VERSION = 5
        const val SOCKS_AUTH_NONE = 0
        const val SOCKS_AUTH_UNSUPPORTED = 0xFF
        const val SOCKS_COMMAND_CONNECT = 1
        const val SOCKS_COMMAND_UDP_ASSOCIATE = 3
        const val SOCKS_REPLY_SUCCESS = 0
        const val SOCKS_REPLY_HOST_UNREACHABLE = 4
        const val SOCKS_REPLY_COMMAND_NOT_SUPPORTED = 7
        const val SOCKS_ADDRESS_IPV4 = 1
        const val SOCKS_ADDRESS_DOMAIN = 3
        const val SOCKS_ADDRESS_IPV6 = 4
        const val SOCKS_UDP_FRAGMENT_NONE = 0
        const val IPV4_BYTES = 4
        const val IPV6_BYTES = 16
        const val PORT_BYTES = 2
        const val BYTE_BITS = 8
        const val BYTE_MASK = 0xFF
        const val DNS_PORT = 53
        const val HANDSHAKE_TIMEOUT_MS = 15_000
        const val CHANNEL_CONNECT_TIMEOUT_MS = 20_000
        const val DNS_CONNECT_TIMEOUT_MS = 10_000
        const val STREAM_BUFFER_BYTES = 16 * 1024
        const val MAX_UDP_PACKET_BYTES = 65_535
        const val MAX_DNS_RESPONSE_BYTES = 65_535
        const val MIN_UDP_HEADER_BYTES = 4
    }
}
