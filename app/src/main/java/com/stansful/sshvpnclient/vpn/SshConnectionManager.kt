package com.stansful.sshvpnclient.vpn

import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.Logger
import com.jcraft.jsch.Session
import com.stansful.sshvpnclient.domain.model.AuthType
import com.stansful.sshvpnclient.domain.model.SshConfig
import com.stansful.sshvpnclient.domain.model.SshPrivateKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.Properties

class SshConnectionManager {
    private var activeSession: Session? = null

    suspend fun connect(
        config: SshConfig,
        privateKey: SshPrivateKey?,
        log: (String) -> Unit = {},
        socketProtector: ((Socket) -> Boolean)? = null,
        connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
        verboseDiagnostics: Boolean = true,
    ): Session = withContext(Dispatchers.IO) {
        disconnect()

        val detailLog: (String) -> Unit = if (verboseDiagnostics) log else NO_OP_LOG
        installJschLogger()
        configureEdDsaSupport(detailLog)
        val jsch = JSch()
        if (config.authType == AuthType.PRIVATE_KEY) {
            val key = privateKey ?: throw VpnConnectionException("Selected SSH key not found")
            detailLog("Loading private key into SSH client")
            addPrivateKeyIdentity(jsch, key)
            detailLog("Private key loaded; passphrase present: ${!key.passphrase.isNullOrBlank()}")
            if (verboseDiagnostics) {
                logPrivateKeyFingerprint(jsch, key, detailLog)
            }
        }

        try {
            log("Opening SSH session to ${config.username}@${config.host}:${config.port}")
            val session = jsch.getSession(config.username, config.host, config.port)
            if (socketProtector != null) {
                session.setSocketFactory(
                    VpnProtectedSocketFactory(
                        protectSocket = socketProtector,
                        connectTimeoutMs = connectTimeoutMs,
                        log = detailLog,
                    ),
                )
                detailLog("SSH socket protection enabled")
            }
            session.setConfig(connectionConfig(config.authType))
            val effectiveKeepAliveIntervalSec = minOf(
                config.keepAliveIntervalSec,
                MAX_EFFECTIVE_KEEP_ALIVE_INTERVAL_SEC,
            )
            session.setServerAliveInterval(effectiveKeepAliveIntervalSec * 1000)
            session.setServerAliveCountMax(SERVER_ALIVE_COUNT_MAX)
            log(
                "SSH auth method: ${config.authType.label}; " +
                    "keepAlive=${effectiveKeepAliveIntervalSec}s; connectTimeout=${connectTimeoutMs}ms",
            )

            if (config.authType == AuthType.PASSWORD) {
                session.setPassword(
                    (config.password ?: throw VpnConnectionException("Authentication failed"))
                        .toByteArray(Charsets.UTF_8),
                )
            }

            if (verboseDiagnostics) {
                withJschLog(detailLog) {
                    session.connect(connectTimeoutMs)
                }
            } else {
                session.connect(connectTimeoutMs)
            }
            log("SSH transport connected")
            verifyFingerprintIfNeeded(jsch, session, config.fingerprint, detailLog)
            activeSession = session
            session
        } catch (error: JSchException) {
            log("JSch exception: ${error.message.orEmpty().ifBlank { error::class.java.simpleName }}")
            if (isPrivateKeyAuthFailure(error, config)) {
                log(
                    "Authentication hint: server rejected the selected key; verify username, host, " +
                        "and that this public key is present in the server authorized_keys",
                )
            }
            throw mapJschError(error, config)
        }
    }

    fun disconnect() {
        activeSession?.takeIf { it.isConnected }?.disconnect()
        activeSession = null
    }

    suspend fun checkActiveTransport(
        log: (String) -> Unit = {},
        timeoutMs: Int = WAKE_HEALTH_CHECK_TIMEOUT_MS,
    ): Boolean = withContext(Dispatchers.IO) {
        val session = activeSession?.takeIf { it.isConnected } ?: run {
            log("SSH transport probe failed: session is not connected")
            return@withContext false
        }
        var channel: ChannelDirectTCPIP? = null
        try {
            log("SSH transport probe: opening direct TCP to $WAKE_HEALTH_CHECK_HOST:$WAKE_HEALTH_CHECK_PORT")
            channel = session.openChannel("direct-tcpip") as ChannelDirectTCPIP
            channel.setHost(WAKE_HEALTH_CHECK_HOST)
            channel.setPort(WAKE_HEALTH_CHECK_PORT)
            channel.setOrgIPAddress(LOOPBACK_ADDRESS)
            channel.setOrgPort(0)
            channel.connect(timeoutMs)
            log("SSH transport probe succeeded")
            true
        } catch (error: Exception) {
            log("SSH transport probe failed: ${error.message ?: error::class.java.simpleName}")
            false
        } finally {
            channel?.disconnect()
        }
    }

    suspend fun openTerminal(
        log: (String) -> Unit = {},
        onOutput: (String) -> Unit,
        onClosed: (String) -> Unit,
    ): SshTerminalSession = withContext(Dispatchers.IO) {
        val session = activeSession?.takeIf { it.isConnected }
            ?: throw VpnConnectionException("Terminal unavailable: SSH session is not connected")
        var channel: ChannelShell? = null

        try {
            log("SSH terminal: opening shell channel")
            channel = session.openChannel("shell") as ChannelShell
            channel.setPty(true)
            channel.setPtyType(TERMINAL_PTY_TYPE)

            val inputStream = channel.inputStream
            val outputStream = channel.outputStream
            channel.connect(TERMINAL_CONNECT_TIMEOUT_MS)

            SshTerminalSession(
                channel = channel,
                inputStream = inputStream,
                outputStream = outputStream,
                onOutput = onOutput,
                onClosed = onClosed,
            ).also { terminal ->
                terminal.start()
                log("SSH terminal connected")
            }
        } catch (error: Exception) {
            channel?.disconnect()
            val message = error.message ?: error::class.java.simpleName
            log("SSH terminal failed: $message")
            throw VpnConnectionException("SSH terminal failed: $message", error)
        }
    }

    suspend fun checkTcpForward(
        host: String = DEFAULT_TUNNEL_CHECK_HOST,
        port: Int = DEFAULT_TUNNEL_CHECK_PORT,
        log: (String) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        val session = activeSession?.takeIf { it.isConnected }
            ?: throw VpnConnectionException("Tunnel check failed: SSH session is not connected")
        var channel: ChannelDirectTCPIP? = null
        val startedAt = System.currentTimeMillis()
        try {
            log("Tunnel check: opening SSH direct TCP to $host:$port")
            channel = session.openChannel("direct-tcpip") as ChannelDirectTCPIP
            channel.setHost(host)
            channel.setPort(port)
            channel.setOrgIPAddress(LOOPBACK_ADDRESS)
            channel.setOrgPort(0)
            channel.connect(TUNNEL_CHECK_TIMEOUT_MS)
            val elapsedMs = System.currentTimeMillis() - startedAt
            log("Tunnel check succeeded: $host:$port reachable through SSH in ${elapsedMs}ms")
        } catch (error: Exception) {
            val message = error.message ?: error::class.java.simpleName
            log("Tunnel check failed: $message")
            throw VpnConnectionException("Tunnel check failed: $message", error)
        } finally {
            channel?.disconnect()
        }
    }

    private fun addPrivateKeyIdentity(jsch: JSch, key: SshPrivateKey) {
        try {
            jsch.addIdentity(
                key.id,
                key.privateKey.toByteArray(Charsets.UTF_8),
                null,
                key.passphrase?.toByteArray(Charsets.UTF_8),
            )
        } catch (error: JSchException) {
            val message = error.message.orEmpty()
            if (message.contains("passphrase", ignoreCase = true)) {
                throw VpnConnectionException("Invalid private key passphrase", error)
            }
            throw VpnConnectionException("Invalid private key format", error)
        }
    }

    private fun logPrivateKeyFingerprint(
        jsch: JSch,
        key: SshPrivateKey,
        log: (String) -> Unit,
    ) {
        var keyPair: KeyPair? = null
        try {
            keyPair = KeyPair.load(
                jsch,
                key.privateKey.toByteArray(Charsets.UTF_8),
                null,
            )
            if (keyPair.isEncrypted) {
                val passphrase = key.passphrase?.toByteArray(Charsets.UTF_8)
                if (passphrase == null || !keyPair.decrypt(passphrase)) {
                    log("Selected private key fingerprint unavailable: passphrase required")
                    return
                }
            }

            val publicKeyBlob = keyPair.getPublicKeyBlob()
            val sha256Fingerprint = publicKeyBlob?.let(::openSshSha256Fingerprint)
                ?: "SHA256 unavailable"
            log(
                "Selected private key public fingerprint: " +
                    "${keyPair.getKeyTypeString()} $sha256Fingerprint; md5=${keyPair.getFingerPrint()}",
            )
        } catch (error: JSchException) {
            log(
                "Selected private key fingerprint unavailable: " +
                    error.message.orEmpty().ifBlank { error::class.java.simpleName },
            )
        } finally {
            keyPair?.dispose()
        }
    }

    private fun openSshSha256Fingerprint(publicKeyBlob: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKeyBlob)
        val encoded = Base64.getEncoder().withoutPadding().encodeToString(digest)
        return "SHA256:$encoded"
    }

    private fun connectionConfig(authType: AuthType): Properties {
        return Properties().apply {
            put("StrictHostKeyChecking", "no")
            put(
                "PreferredAuthentications",
                when (authType) {
                    AuthType.PASSWORD -> "password"
                    AuthType.PRIVATE_KEY -> "publickey"
                },
            )
        }
    }

    private fun verifyFingerprintIfNeeded(
        jsch: JSch,
        session: Session,
        expectedFingerprint: String?,
        log: (String) -> Unit,
    ) {
        val actual = session.hostKey.getFingerPrint(jsch)
        log("Server host key fingerprint: $actual")

        val expected = expectedFingerprint?.trim().orEmpty()
        if (expected.isBlank()) {
            log("Fingerprint check skipped: no expected fingerprint configured")
            return
        }

        log("Checking configured SSH fingerprint")
        if (normalizeFingerprint(actual) != normalizeFingerprint(expected)) {
            session.disconnect()
            throw VpnConnectionException("Fingerprint mismatch")
        }
        log("Fingerprint matched")
    }

    private fun normalizeFingerprint(value: String): String {
        return value
            .lowercase()
            .replace(":", "")
            .replace(" ", "")
    }

    private fun mapJschError(error: JSchException, config: SshConfig): VpnConnectionException {
        val message = error.message.orEmpty()
        val userMessage = when {
            message.contains("Auth fail", ignoreCase = true) &&
                config.authType == AuthType.PRIVATE_KEY ->
                    "Authentication failed: server rejected this private key for user '${config.username}'"
            message.contains("Auth fail", ignoreCase = true) -> "Authentication failed"
            message.contains("timeout", ignoreCase = true) -> "Connection timeout"
            message.contains("ECONNABORTED", ignoreCase = true) ||
                message.contains("Software caused connection abort", ignoreCase = true) ->
                    "Connection timeout"
            message.contains("UnknownHost", ignoreCase = true) -> "Host unreachable"
            message.contains("protect SSH socket", ignoreCase = true) ->
                "Could not protect SSH socket from VPN routing"
            message.contains("invalid privatekey", ignoreCase = true) -> "Invalid private key format"
            else -> "Unknown connection error"
        }
        return VpnConnectionException(userMessage, error)
    }

    private fun isPrivateKeyAuthFailure(
        error: JSchException,
        config: SshConfig,
    ): Boolean {
        return config.authType == AuthType.PRIVATE_KEY &&
            error.message.orEmpty().contains("Auth fail", ignoreCase = true)
    }

    private fun configureEdDsaSupport(log: (String) -> Unit) {
        JSch.setConfig("keypairgen.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
        JSch.setConfig("keypairgen_fromprivate.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
        JSch.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
        JSch.setConfig("ssh-ed448", "com.jcraft.jsch.bc.SignatureEd448")
        log("Configured BouncyCastle-backed EdDSA support for JSch")
    }

    private fun levelLabel(level: Int): String {
        return when (level) {
            Logger.DEBUG -> "DEBUG"
            Logger.INFO -> "INFO"
            Logger.WARN -> "WARN"
            Logger.ERROR -> "ERROR"
            Logger.FATAL -> "FATAL"
            else -> level.toString()
        }
    }

    private fun isExpectedDisconnectLog(message: String): Boolean {
        return message.contains("leaving main loop due to Socket closed", ignoreCase = true)
    }

    private companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 20_000
        const val MAX_EFFECTIVE_KEEP_ALIVE_INTERVAL_SEC = 60
        const val SERVER_ALIVE_COUNT_MAX = 1
        const val TERMINAL_CONNECT_TIMEOUT_MS = 10_000
        const val TERMINAL_PTY_TYPE = "xterm"
        const val TUNNEL_CHECK_TIMEOUT_MS = 10_000
        const val WAKE_HEALTH_CHECK_TIMEOUT_MS = 4_000
        const val WAKE_HEALTH_CHECK_HOST = "1.1.1.1"
        const val WAKE_HEALTH_CHECK_PORT = 443
        const val DEFAULT_TUNNEL_CHECK_HOST = "youtube.com"
        const val DEFAULT_TUNNEL_CHECK_PORT = 443
        const val LOOPBACK_ADDRESS = "127.0.0.1"
        val NO_OP_LOG: (String) -> Unit = {}
        val jschThreadLog = InheritableThreadLocal<((String) -> Unit)?>()

        @Volatile
        var jschLoggerInstalled = false
    }

    private fun installJschLogger() {
        if (jschLoggerInstalled) return
        synchronized(SshConnectionManager::class.java) {
            if (jschLoggerInstalled) return
            JSch.setLogger(
                object : Logger {
                    override fun isEnabled(level: Int): Boolean = jschThreadLog.get() != null

                    override fun log(level: Int, message: String?) {
                        val value = message?.trim().orEmpty()
                        if (value.isBlank()) return
                        if (isExpectedDisconnectLog(value)) return
                        jschThreadLog.get()?.invoke("JSch ${levelLabel(level)}: $value")
                    }
                },
            )
            jschLoggerInstalled = true
        }
    }

    private inline fun <T> withJschLog(
        noinline log: (String) -> Unit,
        block: () -> T,
    ): T {
        val previous = jschThreadLog.get()
        jschThreadLog.set(log)
        return try {
            block()
        } finally {
            if (previous == null) {
                jschThreadLog.remove()
            } else {
                jschThreadLog.set(previous)
            }
        }
    }
}
