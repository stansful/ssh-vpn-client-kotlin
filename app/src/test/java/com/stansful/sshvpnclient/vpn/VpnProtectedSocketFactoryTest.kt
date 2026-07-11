package com.stansful.sshvpnclient.vpn

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnProtectedSocketFactoryTest {
    @Test
    fun `resolves binds and protects before connecting`() {
        val events = mutableListOf<String>()
        val protector = object : (Socket) -> Boolean, VpnSocketRouteProvider {
            override fun routeFor(host: String): VpnSocketRoute {
                events += "resolve:$host"
                return object : VpnSocketRoute {
                    override val addresses = listOf(InetAddress.getLoopbackAddress())
                    override val description = "test-network"

                    override fun bind(socket: Socket) {
                        assertFalse(socket.isConnected)
                        events += "bind"
                    }
                }
            }

            override fun invoke(socket: Socket): Boolean {
                assertFalse(socket.isConnected)
                assertTrue(socket.tcpNoDelay)
                assertTrue(socket.keepAlive)
                events += "protect"
                return true
            }
        }

        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            VpnProtectedSocketFactory(
                protectSocket = protector,
                connectTimeoutMs = 2_000,
                log = {},
            ).createSocket("ssh.example", server.localPort).use { socket ->
                assertTrue(socket.isConnected)
            }
        }

        assertEquals(listOf("resolve:ssh.example", "bind", "protect"), events)
    }

    @Test
    fun `falls back to the next resolved address after a pre-connect failure`() {
        var protectAttempts = 0
        val protector = object : (Socket) -> Boolean, VpnSocketRouteProvider {
            override fun routeFor(host: String): VpnSocketRoute {
                return object : VpnSocketRoute {
                    override val addresses = listOf(
                        InetAddress.getLoopbackAddress(),
                        InetAddress.getLoopbackAddress(),
                    )
                    override val description = "test-network"

                    override fun bind(socket: Socket) = Unit
                }
            }

            override fun invoke(socket: Socket): Boolean {
                protectAttempts += 1
                return protectAttempts > 1
            }
        }

        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            VpnProtectedSocketFactory(
                protectSocket = protector,
                connectTimeoutMs = 2_000,
                log = {},
            ).createSocket("ssh.example", server.localPort).use { socket ->
                assertTrue(socket.isConnected)
            }
        }

        assertEquals(2, protectAttempts)
    }
}
