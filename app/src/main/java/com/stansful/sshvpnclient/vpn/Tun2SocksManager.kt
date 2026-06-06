package com.stansful.sshvpnclient.vpn

import android.content.Context
import android.os.ParcelFileDescriptor
import com.jcraft.jsch.Session
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class Tun2SocksManager {
    var isRunning: Boolean = false
        private set

    private var socksServer: SshSocks5Server? = null
    private var tunnelExecutor: ExecutorService? = null
    private var configFile: File? = null

    fun start(
        context: Context,
        vpnInterface: ParcelFileDescriptor,
        sshSession: Session,
        enableUdpForwarding: Boolean,
        log: (String) -> Unit,
    ) {
        stop()
        check(vpnInterface.fileDescriptor.valid()) { "VPN interface is not valid" }
        check(sshSession.isConnected) { "SSH session is not connected" }

        val socks = SshSocks5Server(sshSession, log)
        val socksPort = try {
            socks.start()
        } catch (error: Exception) {
            throw VpnConnectionException("Could not start local SSH SOCKS bridge", error)
        }
        socksServer = socks
        log("Local SSH SOCKS bridge listening on 127.0.0.1:$socksPort")

        if (enableUdpForwarding) {
            log("UDP forwarding requested; current SSH bridge supports DNS over SSH and TCP traffic")
        }

        val config = writeHevConfig(context, socksPort)
        configFile = config
        log("Prepared TUN forwarding config")

        try {
            HevTunnelBridge.ensureLoaded()
        } catch (error: Throwable) {
            stop()
            throw VpnConnectionException("Could not load TUN forwarding engine", error)
        }

        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, HEV_THREAD_NAME).apply { isDaemon = true }
        }
        tunnelExecutor = executor
        isRunning = true
        executor.execute {
            try {
                HevTunnelBridge.start(config.absolutePath, vpnInterface.fd)
            } catch (error: Throwable) {
                if (isRunning) {
                    log("TUN forwarding engine stopped unexpectedly: ${rootMessage(error)}")
                }
            }
        }
        log("TUN forwarding engine started")
    }

    fun stop() {
        val socks = socksServer
        socksServer = null
        socks?.stop()

        val wasRunning = isRunning
        isRunning = false
        if (wasRunning) {
            try {
                HevTunnelBridge.stop()
            } catch (_: Throwable) {
            }
        }
        tunnelExecutor?.shutdownNow()
        tunnelExecutor = null
        configFile?.delete()
        configFile = null
    }

    private fun writeHevConfig(context: Context, socksPort: Int): File {
        val file = File(context.cacheDir, HEV_CONFIG_FILE_NAME)
        file.writeText(
            """
            tunnel:
              name: tun0
              mtu: 1500
              multi-queue: false
              ipv4: 10.10.0.2
            socks5:
              port: $socksPort
              address: 127.0.0.1
              udp: 'udp'
            misc:
              task-stack-size: 24576
              tcp-buffer-size: 4096
              connect-timeout: 10000
              tcp-read-write-timeout: 300000
              udp-read-write-timeout: 60000
              log-level: warn
            """.trimIndent(),
        )
        return file
    }

    private fun rootMessage(error: Throwable): String {
        return when (error) {
            is InvocationTargetException -> error.targetException?.message
            else -> error.message
        }.orEmpty().ifBlank { error::class.java.simpleName }
    }

    private object HevTunnelBridge {
        private val serviceClass: Class<*> by lazy {
            Class.forName("org.amnezia.awg.hevtunnel.TProxyService")
        }

        fun ensureLoaded() {
            serviceClass
        }

        fun start(configPath: String, fd: Int) {
            serviceClass
                .getMethod("TProxyStartService", String::class.java, Int::class.javaPrimitiveType!!)
                .invoke(null, configPath, fd)
        }

        fun stop() {
            serviceClass
                .getMethod("TProxyStopService")
                .invoke(null)
        }
    }

    private companion object {
        const val HEV_THREAD_NAME = "hev-socks5-tunnel"
        const val HEV_CONFIG_FILE_NAME = "hev-socks5-tunnel.yml"
    }
}
