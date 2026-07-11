package com.stansful.sshvpnclient.vpn

import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchChangedHostKeyException
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.Logger
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import com.stansful.sshvpnclient.domain.model.AuthType
import com.stansful.sshvpnclient.domain.model.SshConfig
import com.stansful.sshvpnclient.domain.model.SshPrivateKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.Properties

class SshConnectionManager {
    private val sessionLock = Any()
    private var connectionGeneration = 0L
    private var connectingSession: Session? = null
    private var connectingOwner: Any? = null
    private var connectingKeepAliveIntervalSec: Int? = null
    private var activeOwner: Any? = null
    private var activeKeepAliveIntervalSec: Int? = null
    private var deviceInteractive: Boolean = true

    @Volatile
    private var activeSession: Session? = null

    suspend fun connect(
        owner: Any,
        lease: VpnRuntimeLease,
        config: SshConfig,
        privateKey: SshPrivateKey?,
        log: (String) -> Unit = {},
        socketProtector: ((Socket) -> Boolean)? = null,
        connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
        verboseDiagnostics: Boolean = true,
    ): Session = withContext(Dispatchers.IO) {
        require(lease.owner === owner) { "SSH owner must match runtime lease" }
        val attemptGeneration = beginConnectionAttempt(owner, lease)
        var attemptedSession: Session? = null

        try {
            val safeLog: (String) -> Unit = { message ->
                sanitizeSshDiagnostic(message).takeIf(String::isNotBlank)?.let(log)
            }
            val detailLog: (String) -> Unit = if (verboseDiagnostics) safeLog else NO_OP_LOG
            installJschLogger()
            configureEdDsaSupport(detailLog)
            val jsch = JSch()
            jsch.setHostKeyRepository(
                FingerprintHostKeyRepository(
                    expectedFingerprint = config.fingerprint,
                    log = detailLog,
                ),
            )
            if (config.authType == AuthType.PRIVATE_KEY) {
                val key = privateKey ?: throw VpnConnectionException("Selected SSH key not found")
                detailLog("Loading private key into SSH client")
                addPrivateKeyIdentity(jsch, key)
                detailLog("Private key loaded")
                if (verboseDiagnostics) {
                    logPrivateKeyFingerprint(jsch, key, detailLog)
                }
            }

            try {
                safeLog("Opening SSH session to ${config.host}:${config.port}")
                val session = jsch.getSession(config.username, config.host, config.port)
                attemptedSession = session
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
                val configuredKeepAliveIntervalSec = config.keepAliveIntervalSec.coerceIn(
                    MIN_KEEP_ALIVE_INTERVAL_SEC,
                    MAX_KEEP_ALIVE_INTERVAL_SEC,
                )
                val initialKeepAliveIntervalSec = synchronized(sessionLock) {
                    effectiveKeepAliveIntervalSec(configuredKeepAliveIntervalSec, deviceInteractive)
                }
                session.setServerAliveInterval(initialKeepAliveIntervalSec * 1000)
                session.setServerAliveCountMax(SERVER_ALIVE_COUNT_MAX)

                if (config.authType == AuthType.PASSWORD) {
                    val passwordBytes = (config.password ?: throw VpnConnectionException("Authentication failed"))
                        .toByteArray(Charsets.UTF_8)
                    try {
                        session.setPassword(passwordBytes)
                    } finally {
                        passwordBytes.fill(0)
                    }
                }

                val connectionRegistered = registerConnectingSession(
                    generation = attemptGeneration,
                    lease = lease,
                    session = session,
                    configuredKeepAliveIntervalSec = configuredKeepAliveIntervalSec,
                )
                if (!connectionRegistered) {
                    throw CancellationException("SSH connection attempt was superseded")
                }
                safeLog(
                    "SSH auth method: ${config.authType.label}; " +
                        "keepAlive=${session.serverAliveInterval / 1_000}s; connectTimeout=${connectTimeoutMs}ms",
                )

                if (verboseDiagnostics) {
                    withJschLog(detailLog) {
                        session.connect(connectTimeoutMs)
                    }
                } else {
                    session.connect(connectTimeoutMs)
                }
                if (!promoteConnectedSession(attemptGeneration, lease, session)) {
                    throw CancellationException("SSH connection attempt was cancelled")
                }
                safeLog("SSH transport connected")
                session
            } catch (error: JSchException) {
                safeLog("JSch exception: ${error.message.orEmpty().ifBlank { error::class.java.simpleName }}")
                if (isPrivateKeyAuthFailure(error, config)) {
                    safeLog(
                        "Authentication hint: server rejected the selected key; verify username, host, " +
                            "and that this public key is present in the server authorized_keys",
                    )
                }
                throw mapJschError(error, config)
            }
        } finally {
            finishConnectionAttempt(attemptGeneration, attemptedSession)
        }
    }

    /** Adjusts the idle SSH probe cadence without reconnecting the active transport. */
    fun setDeviceInteractive(isInteractive: Boolean) {
        synchronized(sessionLock) {
            deviceInteractive = isInteractive
            connectingSession?.let { session ->
                connectingKeepAliveIntervalSec?.let { configured ->
                    applyKeepAliveInterval(session, configured, isInteractive)
                }
            }
            activeSession?.let { session ->
                activeKeepAliveIntervalSec?.let { configured ->
                    applyKeepAliveInterval(session, configured, isInteractive)
                }
            }
        }
    }

    fun disconnectOwner(owner: Any): Boolean {
        val sessionsToDisconnect = synchronized(sessionLock) {
            val ownsConnecting = connectingOwner === owner
            val ownsActive = activeOwner === owner
            if (!ownsConnecting && !ownsActive) return@synchronized emptyList()
            connectionGeneration += 1L
            buildList {
                if (ownsConnecting) {
                    connectingSession?.let(::add)
                    connectingSession = null
                    connectingOwner = null
                    connectingKeepAliveIntervalSec = null
                }
                if (ownsActive) {
                    activeSession?.let(::add)
                    activeSession = null
                    activeOwner = null
                    activeKeepAliveIntervalSec = null
                }
            }.distinct()
        }
        sessionsToDisconnect.forEach { session -> runCatching { session.disconnect() } }
        return sessionsToDisconnect.isNotEmpty()
    }

    private fun beginConnectionAttempt(owner: Any, lease: VpnRuntimeLease): Long {
        val (generation, sessionsToDisconnect) = lease.requireCurrent {
            synchronized(sessionLock) {
                if (!lease.isCurrent()) {
                    throw CancellationException("SSH runtime lease was superseded")
                }
                val currentOwner = activeOwner ?: connectingOwner
                if (currentOwner != null && currentOwner !== owner) {
                    throw VpnConnectionException("SSH runtime belongs to another service instance")
                }
                connectionGeneration += 1L
                val nextGeneration = connectionGeneration
                val previousSessions = listOfNotNull(connectingSession, activeSession).distinct()
                connectingSession = null
                connectingOwner = owner
                connectingKeepAliveIntervalSec = null
                activeSession = null
                activeOwner = null
                activeKeepAliveIntervalSec = null
                nextGeneration to previousSessions
            }
        }
        sessionsToDisconnect.forEach { session -> runCatching { session.disconnect() } }
        return generation
    }

    private fun registerConnectingSession(
        generation: Long,
        lease: VpnRuntimeLease,
        session: Session,
        configuredKeepAliveIntervalSec: Int,
    ): Boolean {
        return lease.requireCurrent {
            synchronized(sessionLock) {
                if (!lease.isCurrent()) return@synchronized false
                if (connectionGeneration != generation) return@synchronized false
                connectingSession = session
                connectingKeepAliveIntervalSec = configuredKeepAliveIntervalSec
                applyKeepAliveInterval(session, configuredKeepAliveIntervalSec, deviceInteractive)
                true
            }
        }
    }

    private fun promoteConnectedSession(
        generation: Long,
        lease: VpnRuntimeLease,
        session: Session,
    ): Boolean {
        return lease.requireCurrent {
            synchronized(sessionLock) {
                if (!lease.isCurrent()) return@synchronized false
                if (
                    connectionGeneration != generation ||
                    connectingSession !== session ||
                    !session.isConnected
                ) {
                    return@synchronized false
                }
                connectingSession = null
                activeOwner = connectingOwner
                connectingOwner = null
                activeKeepAliveIntervalSec = connectingKeepAliveIntervalSec
                connectingKeepAliveIntervalSec = null
                activeSession = session
                true
            }
        }
    }

    private fun finishConnectionAttempt(generation: Long, session: Session?) {
        val shouldDisconnect = synchronized(sessionLock) {
            if (connectionGeneration == generation && activeSession !== session) {
                connectingSession = null
                connectingOwner = null
                connectingKeepAliveIntervalSec = null
            }
            session != null && activeSession !== session
        }
        if (shouldDisconnect) {
            runCatching { session?.disconnect() }
        }
    }

    private fun applyKeepAliveInterval(
        session: Session,
        configuredIntervalSec: Int,
        isInteractive: Boolean,
    ) {
        val effectiveIntervalSec = effectiveKeepAliveIntervalSec(
            configuredIntervalSec = configuredIntervalSec,
            isInteractive = isInteractive,
        )
        runCatching { session.setServerAliveInterval(effectiveIntervalSec * 1_000) }
    }

    suspend fun checkActiveTransport(
        log: (String) -> Unit = {},
        timeoutMs: Int = WAKE_HEALTH_CHECK_TIMEOUT_MS,
    ): Boolean = probeActiveTransport(log, timeoutMs).healthy

    internal suspend fun probeActiveTransport(
        log: (String) -> Unit = {},
        timeoutMs: Int = WAKE_HEALTH_CHECK_TIMEOUT_MS,
        owner: Any? = null,
    ): SshTransportProbeResult = withContext(Dispatchers.IO) {
        val session = synchronized(sessionLock) {
            activeSession.takeIf { owner == null || activeOwner === owner }
        } ?: run {
            log("SSH transport probe failed: session is not connected")
            return@withContext SshTransportProbeResult(session = null, healthy = false)
        }
        if (!session.isConnected) {
            log("SSH transport probe failed: session is not connected")
            return@withContext SshTransportProbeResult(session = session, healthy = false)
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
            SshTransportProbeResult(session = session, healthy = true)
        } catch (error: Exception) {
            log("SSH transport probe failed: ${error.message ?: error::class.java.simpleName}")
            SshTransportProbeResult(session = session, healthy = false)
        } finally {
            channel?.disconnect()
        }
    }

    internal fun disconnectIfActive(expectedSession: Session): Boolean {
        val shouldDisconnect = synchronized(sessionLock) {
            if (activeSession !== expectedSession) return@synchronized false
            connectionGeneration += 1L
            activeSession = null
            activeOwner = null
            activeKeepAliveIntervalSec = null
            true
        }
        if (shouldDisconnect) {
            runCatching { expectedSession.disconnect() }
        }
        return shouldDisconnect
    }

    internal fun transportSessionSnapshot(owner: Any): Session? = synchronized(sessionLock) {
        when {
            connectingOwner === owner -> connectingSession
            activeOwner === owner -> activeSession
            else -> null
        }
    }

    internal fun disconnectIfCurrent(expectedSession: Session): Boolean {
        val shouldDisconnect = synchronized(sessionLock) {
            val isConnecting = connectingSession === expectedSession
            val isActive = activeSession === expectedSession
            if (!isConnecting && !isActive) return@synchronized false
            connectionGeneration += 1L
            if (isConnecting) {
                connectingSession = null
                connectingOwner = null
                connectingKeepAliveIntervalSec = null
            }
            if (isActive) {
                activeSession = null
                activeOwner = null
                activeKeepAliveIntervalSec = null
            }
            true
        }
        if (shouldDisconnect) {
            runCatching { expectedSession.disconnect() }
        }
        return shouldDisconnect
    }

    suspend fun openTerminal(
        log: (String) -> Unit = {},
        onOutput: (String) -> Unit,
        onClosed: (String) -> Unit,
    ): SshTerminalSession {
        var openedTerminal: SshTerminalSession? = null
        var openedChannel: ChannelShell? = null
        try {
            return withContext(Dispatchers.IO) {
                val session = activeSession?.takeIf { it.isConnected }
                    ?: throw VpnConnectionException("Terminal unavailable: SSH session is not connected")
                var channel: ChannelShell? = null

                try {
                    log("SSH terminal: opening shell channel")
                    channel = session.openChannel("shell") as ChannelShell
                    openedChannel = channel
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
                        openedTerminal = terminal
                        terminal.start()
                        log("SSH terminal connected")
                    }
                } catch (error: CancellationException) {
                    channel?.disconnect()
                    throw error
                } catch (error: Exception) {
                    channel?.disconnect()
                    val message = error.message ?: error::class.java.simpleName
                    log("SSH terminal failed: $message")
                    throw VpnConnectionException("SSH terminal failed: $message", error)
                }
            }
        } catch (error: CancellationException) {
            openedTerminal?.close() ?: openedChannel?.disconnect()
            throw error
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
        val privateKeyBytes = key.privateKey.toByteArray(Charsets.UTF_8)
        val passphraseBytes = key.passphrase?.toByteArray(Charsets.UTF_8)
        try {
            jsch.addIdentity(
                key.id,
                privateKeyBytes,
                null,
                passphraseBytes,
            )
        } catch (error: JSchException) {
            val message = error.message.orEmpty()
            if (message.contains("passphrase", ignoreCase = true)) {
                throw VpnConnectionException("Invalid private key passphrase", error)
            }
            throw VpnConnectionException("Invalid private key format", error)
        } finally {
            privateKeyBytes.fill(0)
            passphraseBytes?.fill(0)
        }
    }

    private fun logPrivateKeyFingerprint(
        jsch: JSch,
        key: SshPrivateKey,
        log: (String) -> Unit,
    ) {
        var keyPair: KeyPair? = null
        val privateKeyBytes = key.privateKey.toByteArray(Charsets.UTF_8)
        val passphraseBytes = key.passphrase?.toByteArray(Charsets.UTF_8)
        try {
            keyPair = KeyPair.load(
                jsch,
                privateKeyBytes,
                null,
            )
            if (keyPair.isEncrypted) {
                if (passphraseBytes == null || !keyPair.decrypt(passphraseBytes)) {
                    log("Selected private key fingerprint unavailable: passphrase required")
                    return
                }
            }

            val publicKeyBlob = keyPair.getPublicKeyBlob()
            val sha256Fingerprint = publicKeyBlob?.let(::openSshSha256Fingerprint)
                ?: "SHA256 unavailable"
            log(
                "Selected private key public fingerprint: " +
                    "${keyPair.getKeyTypeString()} $sha256Fingerprint",
            )
        } catch (error: JSchException) {
            log(
                "Selected private key fingerprint unavailable: " +
                    error.message.orEmpty().ifBlank { error::class.java.simpleName },
            )
        } finally {
            keyPair?.dispose()
            privateKeyBytes.fill(0)
            passphraseBytes?.fill(0)
        }
    }

    private fun connectionConfig(authType: AuthType): Properties {
        return Properties().apply {
            put("StrictHostKeyChecking", "yes")
            put("max_input_buffer_size", SSH_MAX_INPUT_BUFFER_SIZE_BYTES.toString())
            put(
                "PreferredAuthentications",
                when (authType) {
                    AuthType.PASSWORD -> "password"
                    AuthType.PRIVATE_KEY -> "publickey"
                },
            )
        }
    }

    private fun mapJschError(error: JSchException, config: SshConfig): VpnConnectionException {
        val message = error.message.orEmpty()
        val userMessage = when {
            error is JSchChangedHostKeyException ||
                message.contains("HostKey has been changed", ignoreCase = true) ->
                    "Fingerprint mismatch"
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

    private companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 20_000
        const val SSH_MAX_INPUT_BUFFER_SIZE_BYTES = 4 * 1_024 * 1_024
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
                        val value = sanitizeSshDiagnostic(message.orEmpty())
                        if (value.isBlank()) return
                        if (isExpectedJschDisconnectLog(value)) return
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

internal data class SshTransportProbeResult(
    val session: Session?,
    val healthy: Boolean,
)

internal class FingerprintHostKeyRepository(
    expectedFingerprint: String?,
    private val log: (String) -> Unit = {},
) : HostKeyRepository {
    private val expectedFingerprint = expectedFingerprint?.trim().orEmpty()

    override fun check(host: String?, key: ByteArray?): Int {
        if (key == null) return HostKeyRepository.CHANGED

        val actualFingerprint = openSshSha256Fingerprint(key)
        log("Server host key fingerprint: $actualFingerprint")
        if (expectedFingerprint.isBlank()) {
            log(
                "WARNING: SSH host identity is not verified because no fingerprint is configured; " +
                    "save the displayed fingerprint to enable pre-authentication verification",
            )
            return HostKeyRepository.OK
        }

        log("Checking configured SSH fingerprint before authentication")
        return if (matchesSshHostKeyFingerprint(expectedFingerprint, key)) {
            log("Fingerprint matched")
            HostKeyRepository.OK
        } else {
            log("Fingerprint mismatch; authentication was not attempted")
            HostKeyRepository.CHANGED
        }
    }

    override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit

    override fun remove(host: String?, type: String?) = Unit

    override fun remove(host: String?, type: String?, key: ByteArray?) = Unit

    override fun getKnownHostsRepositoryID(): String = "in-memory fingerprint verifier"

    override fun getHostKey(): Array<HostKey> = emptyArray()

    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
}

internal fun matchesSshHostKeyFingerprint(expected: String, hostKey: ByteArray): Boolean {
    val value = expected.trim()
    if (value.isBlank()) return false

    decodeSha256Fingerprint(value)?.let { expectedDigest ->
        val actualDigest = MessageDigest.getInstance("SHA-256").digest(hostKey)
        return MessageDigest.isEqual(actualDigest, expectedDigest)
    }

    decodeMd5Fingerprint(value)?.let { expectedDigest ->
        val actualDigest = MessageDigest.getInstance("MD5").digest(hostKey)
        return MessageDigest.isEqual(actualDigest, expectedDigest)
    }

    return false
}

internal fun sanitizeSshDiagnostic(message: String): String {
    val singleLine = message
        .replace(CONTROL_CHARACTERS, " ")
        .replace(REPEATED_WHITESPACE, " ")
        .trim()
        .replace(SENSITIVE_ASSIGNMENT) { match -> "${match.groupValues[1]}=<redacted>" }
    return if (singleLine.length <= MAX_SSH_DIAGNOSTIC_LENGTH) {
        singleLine
    } else {
        singleLine.take(MAX_SSH_DIAGNOSTIC_LENGTH - 1) + "…"
    }
}

private fun openSshSha256Fingerprint(publicKeyBlob: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(publicKeyBlob)
    val encoded = Base64.getEncoder().withoutPadding().encodeToString(digest)
    return "SHA256:$encoded"
}

private fun decodeSha256Fingerprint(value: String): ByteArray? {
    val encoded = when {
        value.startsWith("SHA256:", ignoreCase = true) -> value.substringAfter(':').trim()
        ':' !in value -> value
        else -> return null
    }.trimEnd('=')
    if (encoded.isBlank()) return null

    val padded = encoded + "=".repeat((4 - encoded.length % 4) % 4)
    return runCatching { Base64.getDecoder().decode(padded) }
        .getOrNull()
        ?.takeIf { it.size == SHA256_DIGEST_SIZE_BYTES }
}

private fun decodeMd5Fingerprint(value: String): ByteArray? {
    val withoutPrefix = if (value.startsWith("MD5:", ignoreCase = true)) {
        value.substringAfter(':')
    } else {
        value
    }
    val hex = withoutPrefix.replace(":", "").replace(" ", "")
    if (!hex.matches(MD5_HEX_PATTERN)) return null
    return ByteArray(MD5_DIGEST_SIZE_BYTES) { index ->
        hex.substring(index * 2, index * 2 + 2).toInt(radix = 16).toByte()
    }
}

internal fun isExpectedJschDisconnectLog(message: String): Boolean {
    return message.contains("leaving main loop due to Socket closed", ignoreCase = true) ||
        message.contains("leaving main loop due to Software caused connection abort", ignoreCase = true) ||
        message.contains("leaving main loop due to Connection reset", ignoreCase = true)
}

private const val SHA256_DIGEST_SIZE_BYTES = 32
private const val MD5_DIGEST_SIZE_BYTES = 16
private const val MAX_SSH_DIAGNOSTIC_LENGTH = 1_024
private val CONTROL_CHARACTERS = Regex("[\\u0000-\\u001F\\u007F]")
private val REPEATED_WHITESPACE = Regex("\\s+")
private val SENSITIVE_ASSIGNMENT = Regex(
    pattern = "(?i)\\b(password|passphrase|private[_ -]?key)\\s*[:=]\\s*\\S+",
)
private val MD5_HEX_PATTERN = Regex("(?i)^[0-9a-f]{32}$")
