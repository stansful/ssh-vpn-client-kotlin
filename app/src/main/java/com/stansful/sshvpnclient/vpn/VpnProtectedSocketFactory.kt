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
) : SocketFactory {
    override fun createSocket(host: String, port: Int): Socket {
        val socket = Socket()
        try {
            if (!protectSocket(socket)) {
                throw IOException("Could not protect SSH socket from VPN routing")
            }
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            return socket
        } catch (error: UnknownHostException) {
            socket.close()
            throw error
        } catch (error: IOException) {
            socket.close()
            throw error
        } catch (error: RuntimeException) {
            socket.close()
            throw error
        }
    }

    override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()

    override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()
}
