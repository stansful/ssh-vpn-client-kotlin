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
import java.net.InetAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.Properties
import java.util.concurrent.atomic.AtomicLong

internal const val SSH_SERVER_ALIVE_COUNT_MAX = 3

class SshConnectionManager internal constructor(
    private val linkClock: LinkClock,
    private val linkConfig: SshTransportLinkConfig,
    private val tuneTransportSocket: (socket: Socket, userTimeoutMs: Int, log: (String) -> Unit) -> Boolean,
) {
    constructor() : this(
        linkClock = AndroidLinkClock,
        linkConfig = SshTransportLinkConfig(),
        tuneTransportSocket = ::applyTcpUserTimeout,
    )

    private val sessionLock = Any()
    private val linkIds = AtomicLong(0L)
    private var connectionGeneration = 0L
    private var connectingSession: Session? = null
    private var connectingLink: SshTransportLink? = null
    private var connectingOwner: Any? = null
    private var connectingKeepAliveIntervalSec: Int? = null
    private var activeOwner: Any? = null
    private var activeLink: SshTransportLink? = null
    private var activeKeepAliveIntervalSec: Int? = null
    private var deviceInteractive: Boolean = true

    @Volatile
    private var activeSession: Session? = null

    /** Lock-free copy of (owner, link) for callers on the TUN read thread; written under [sessionLock]. */
    @Volatile
    private var activeLinkRef: ActiveLinkRef? = null

    suspend fun connect(
        owner: Any,
        lease: VpnRuntimeLease,
        config: SshConfig,
        privateKey: SshPrivateKey?,
        log: (String) -> Unit = {},
        socketProtector: ((Socket) -> Boolean)? = null,
        connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
        verboseDiagnostics: Boolean = true,
        onTransportDead: (linkId: Long, reason: String) -> Unit = { _, _ -> },
    ): Session = withContext(Dispatchers.IO) {
        require(lease.owner === owner) { "SSH owner must match runtime lease" }
        val attemptGeneration = beginConnectionAttempt(owner, lease)
        var attemptedSession: Session? = null
        var attemptedLink: SshTransportLink? = null

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
                    val link = createTransportLink(
                        protector = socketProtector,
                        connectTimeoutMs = connectTimeoutMs,
                        log = safeLog,
                        detailLog = detailLog,
                        onTransportDead = onTransportDead,
                    )
                    attemptedLink = link
                    session.setSocketFactory(link.socketFactory)
                    detailLog("SSH socket protection enabled (${link.label})")
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
                session.setServerAliveCountMax(SSH_SERVER_ALIVE_COUNT_MAX)

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
                    link = attemptedLink,
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
                        connectTransport(session, attemptedLink, connectTimeoutMs)
                    }
                } else {
                    connectTransport(session, attemptedLink, connectTimeoutMs)
                }
                if (!promoteConnectedSession(attemptGeneration, lease, session)) {
                    throw CancellationException("SSH connection attempt was cancelled")
                }
                attemptedLink?.startWatchdog()
                safeLog("SSH transport connected" + attemptedLink?.let { link -> " (${link.label})" }.orEmpty())
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
            finishConnectionAttempt(attemptGeneration, attemptedSession, attemptedLink)
        }
    }

    private fun createTransportLink(
        protector: (Socket) -> Boolean,
        connectTimeoutMs: Int,
        log: (String) -> Unit,
        detailLog: (String) -> Unit,
        onTransportDead: (Long, String) -> Unit,
    ): SshTransportLink {
        val stats = LinkStats(linkClock)
        val userTimeoutMs = linkConfig.tcpUserTimeoutMs
        val factory = VpnProtectedSocketFactory(
            protectSocket = protector,
            connectTimeoutMs = connectTimeoutMs,
            log = detailLog,
            linkStats = stats,
            tuneSocket = { socket ->
                if (tuneTransportSocket(socket, userTimeoutMs, log)) {
                    detailLog("SSH socket: TCP_USER_TIMEOUT=${userTimeoutMs}ms")
                }
            },
        )
        return SshTransportLink(
            id = linkIds.incrementAndGet(),
            socketFactory = factory,
            stats = stats,
            config = linkConfig,
            log = log,
            onDead = { link, reason, detail -> onLinkDead(link, reason, detail, onTransportDead) },
        )
    }

    private fun connectTransport(session: Session, link: SshTransportLink?, connectTimeoutMs: Int) {
        if (link == null) {
            session.connect(connectTimeoutMs)
        } else {
            link.connect(
                session = session,
                handshakeTimeoutMs = connectTimeoutMs,
                totalDeadlineMs = totalSshConnectDeadlineMs(connectTimeoutMs),
            )
        }
    }

    /** Runs on the watchdog timer thread with the socket already closed: only hand the news on. */
    private fun onLinkDead(
        link: SshTransportLink,
        reason: LinkDeathReason,
        detail: String,
        onTransportDead: (Long, String) -> Unit,
    ) {
        val isActive = synchronized(sessionLock) { activeLink === link }
        if (isActive) onTransportDead(link.id, "${reason.name}: $detail")
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

    /**
     * Releases every SSH transport of [owner]. Never blocks on the network: sockets are closed first,
     * `Session.disconnect()` runs off the caller's thread.
     */
    fun disconnectOwner(owner: Any): Boolean {
        val transportsToClose = synchronized(sessionLock) {
            val ownsConnecting = connectingOwner === owner
            val ownsActive = activeOwner === owner
            if (!ownsConnecting && !ownsActive) return@synchronized emptyList()
            connectionGeneration += 1L
            buildList {
                if (ownsConnecting) {
                    connectingSession?.let { session -> add(session to connectingLink) }
                    clearConnectingLocked()
                }
                if (ownsActive) {
                    activeSession?.let { session -> add(session to activeLink) }
                    clearActiveLocked()
                }
            }.distinctBySession()
        }
        transportsToClose.forEach { (session, link) -> closeTransport(session, link, "disconnect") }
        return transportsToClose.isNotEmpty()
    }

    /**
     * Closes the sockets of [owner]'s transports without touching bookkeeping, so that whatever the
     * caller tears down next (TUN flows, channels) cannot wait on a JSch write to a dead path.
     */
    internal fun killTransport(owner: Any, reason: String) {
        val links = synchronized(sessionLock) {
            listOfNotNull(
                connectingLink.takeIf { connectingOwner === owner },
                activeLink.takeIf { activeOwner === owner },
            )
        }
        links.forEach { link -> link.kill(reason) }
    }

    /** Sends one SSH keepalive on [owner]'s active link; the verdict arrives through `onTransportDead`. */
    internal fun probeTransport(owner: Any): LinkProbeDecision? = activeLinkOf(owner)?.probe()

    /** Probes only if the active link has been silent for [quietMs]; cheap enough for every new flow. */
    internal fun probeTransportIfQuiet(owner: Any, quietMs: Long): LinkProbeDecision? {
        return activeLinkOf(owner)?.probeIfQuiet(quietMs)
    }

    /** `isConnected` alone lags a dead path; a killed or condemned link is not a transport either. */
    internal fun isTransportAlive(session: Session): Boolean {
        if (!session.isConnected) return false
        val link = synchronized(sessionLock) {
            when {
                activeSession === session -> activeLink
                connectingSession === session -> connectingLink
                else -> return false
            }
        }
        return link?.isUsable ?: true
    }

    internal fun describeTransport(owner: Any): String? = activeLinkOf(owner)?.describe()

    /** Id of [owner]'s active link, the generation that watchdog verdicts are matched against. */
    internal fun activeLinkId(owner: Any): Long? = activeLinkOf(owner)?.id

    /** Id and local address of [owner]'s active link, read together so they describe the same socket. */
    internal fun transportEndpoint(owner: Any): TransportEndpoint? {
        val link = activeLinkOf(owner) ?: return null
        return TransportEndpoint(link.id, link.localAddress())
    }

    private fun activeLinkOf(owner: Any): SshTransportLink? {
        return activeLinkRef?.takeIf { ref -> ref.owner === owner }?.link
    }

    private fun clearConnectingLocked() {
        connectingSession = null
        connectingLink = null
        connectingOwner = null
        connectingKeepAliveIntervalSec = null
    }

    private fun clearActiveLocked() {
        activeSession = null
        activeLink = null
        activeLinkRef = null
        activeOwner = null
        activeKeepAliveIntervalSec = null
    }

    private fun closeTransport(session: Session, link: SshTransportLink?, reason: String) {
        if (link != null) {
            link.destroy(reason)
        } else {
            runCatching { session.disconnect() }
        }
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
                val previousTransports = listOfNotNull(
                    connectingSession?.let { session -> session to connectingLink },
                    activeSession?.let { session -> session to activeLink },
                ).distinctBySession()
                clearConnectingLocked()
                connectingOwner = owner
                clearActiveLocked()
                nextGeneration to previousTransports
            }
        }
        sessionsToDisconnect.forEach { (session, link) -> closeTransport(session, link, "superseded by a new attempt") }
        return generation
    }

    private fun registerConnectingSession(
        generation: Long,
        lease: VpnRuntimeLease,
        session: Session,
        link: SshTransportLink?,
        configuredKeepAliveIntervalSec: Int,
    ): Boolean {
        return lease.requireCurrent {
            synchronized(sessionLock) {
                if (!lease.isCurrent()) return@synchronized false
                if (connectionGeneration != generation) return@synchronized false
                connectingSession = session
                connectingLink = link
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
                val owner = connectingOwner
                activeOwner = owner
                activeLink = connectingLink
                activeLinkRef = connectingLink?.let { link -> ActiveLinkRef(owner, link) }
                activeKeepAliveIntervalSec = connectingKeepAliveIntervalSec
                clearConnectingLocked()
                activeSession = session
                true
            }
        }
    }

    private fun finishConnectionAttempt(generation: Long, session: Session?, link: SshTransportLink?) {
        val shouldDisconnect = synchronized(sessionLock) {
            if (connectionGeneration == generation && activeSession !== session) {
                clearConnectingLocked()
            }
            session != null && activeSession !== session
        }
        if (shouldDisconnect && session != null) {
            closeTransport(session, link, "connect attempt abandoned")
        } else if (session == null) {
            link?.destroy("connect attempt abandoned")
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

    internal fun disconnectIfActive(expectedSession: Session): Boolean {
        val link = synchronized(sessionLock) {
            if (activeSession !== expectedSession) return false
            connectionGeneration += 1L
            val link = activeLink
            clearActiveLocked()
            link
        }
        closeTransport(expectedSession, link, "stale transport")
        return true
    }

    internal fun transportSessionSnapshot(owner: Any): Session? = synchronized(sessionLock) {
        when {
            connectingOwner === owner -> connectingSession
            activeOwner === owner -> activeSession
            else -> null
        }
    }

    internal fun disconnectIfCurrent(expectedSession: Session): Boolean {
        return disconnectIfCurrent(expectedSession, beforeDisconnect = {})
    }

    internal fun disconnectIfCurrent(
        expectedSession: Session,
        beforeDisconnect: () -> Unit,
    ): Boolean {
        val link = synchronized(sessionLock) {
            val isConnecting = connectingSession === expectedSession
            val isActive = activeSession === expectedSession
            if (!isConnecting && !isActive) return false
            val link = if (isActive) activeLink else connectingLink
            // Close the socket before anything else touches this transport: a JSch write stuck on the
            // old path would otherwise make the pause below (channel closes) wait for it, here, under
            // the lock that the main thread also takes on screen on/off.
            link?.kill("transport replaced")
            // Claim the exact current session before mutating the TUN transport. Holding this lock
            // prevents a reconnect from promoting session B between the check and pause callback.
            beforeDisconnect()
            connectionGeneration += 1L
            if (isConnecting) clearConnectingLocked()
            if (isActive) clearActiveLocked()
            link
        }
        closeTransport(expectedSession, link, "transport replaced")
        return true
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

    /**
     * Proves that forwarding through the SSH session works. By default the forwarded connection goes
     * back to the SSH server itself - its loopback on the port the app dials, then on 22 for a server
     * behind a port forward, then the address the app connected to for an sshd that does not listen
     * on loopback - so the check needs nothing but the SSH port and makes the server connect nowhere
     * else.
     */
    suspend fun checkTcpForward(
        host: String? = null,
        port: Int? = null,
        log: (String) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        val session = activeSession?.takeIf { it.isConnected }
            ?: throw VpnConnectionException("Tunnel check failed: SSH session is not connected")
        val targets = if (host != null) {
            listOf(host to (port ?: session.port))
        } else {
            val sshPort = port ?: session.port
            val serverAddress = activeLinkRef?.link?.socketFactory?.currentSocket?.inetAddress?.hostAddress
            listOfNotNull(
                LOOPBACK_ADDRESS to sshPort,
                (LOOPBACK_ADDRESS to DEFAULT_SSH_PORT).takeIf { port == null },
                serverAddress?.let { address -> address to sshPort },
            ).distinct()
        }
        var lastError: Exception? = null
        for ((targetHost, targetPort) in targets) {
            val startedAt = System.currentTimeMillis()
            var channel: ChannelDirectTCPIP? = null
            try {
                log("Tunnel check: opening SSH direct TCP to $targetHost:$targetPort on the SSH server")
                channel = session.openChannel("direct-tcpip") as ChannelDirectTCPIP
                channel.setHost(targetHost)
                channel.setPort(targetPort)
                channel.setOrgIPAddress(LOOPBACK_ADDRESS)
                channel.setOrgPort(0)
                channel.connect(TUNNEL_CHECK_TIMEOUT_MS)
                val elapsedMs = System.currentTimeMillis() - startedAt
                log("Tunnel check succeeded: $targetHost:$targetPort reachable through SSH in ${elapsedMs}ms")
                return@withContext
            } catch (error: Exception) {
                lastError = error
                val message = error.message ?: error::class.java.simpleName
                log("Tunnel check: $targetHost:$targetPort failed: $message")
            } finally {
                channel?.disconnect()
            }
        }
        val message = lastError?.message ?: lastError?.let { it::class.java.simpleName } ?: "no target"
        log("Tunnel check failed: $message")
        throw VpnConnectionException("Tunnel check failed: $message", lastError)
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
        const val TERMINAL_CONNECT_TIMEOUT_MS = 10_000
        const val TERMINAL_PTY_TYPE = "xterm"
        const val TUNNEL_CHECK_TIMEOUT_MS = 10_000
        const val LOOPBACK_ADDRESS = "127.0.0.1"
        const val DEFAULT_SSH_PORT = 22
        val NO_OP_LOG: (String) -> Unit = {}

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

/**
 * One deadline for TCP, key exchange and authentication together: `session.connect(timeout)`
 * alone bounds each handshake read, not the attempt.
 */
internal fun totalSshConnectDeadlineMs(connectTimeoutMs: Int): Long {
    return (connectTimeoutMs.toLong() * 2L).coerceAtLeast(MIN_TOTAL_SSH_CONNECT_DEADLINE_MS)
}

private const val MIN_TOTAL_SSH_CONNECT_DEADLINE_MS = 15_000L

private class ActiveLinkRef(val owner: Any?, val link: SshTransportLink)

internal data class TransportEndpoint(val linkId: Long, val localAddress: InetAddress?)

/**
 * JSch diagnostics of the attempt that runs on this thread. Inheritable on purpose: the JSch session
 * thread started by `connect()` reports into the same connection log.
 */
private val jschThreadLog = InheritableThreadLocal<((String) -> Unit)?>()

/** For long-lived pool threads that were created inside an attempt and must not keep its logger. */
internal fun detachJschLogFromCurrentThread() {
    jschThreadLog.remove()
}

/** Connecting and active can be the same session object; close it once. Identity, not equals(). */
private fun List<Pair<Session, SshTransportLink?>>.distinctBySession(): List<Pair<Session, SshTransportLink?>> {
    val distinct = ArrayList<Pair<Session, SshTransportLink?>>(size)
    forEach { transport -> if (distinct.none { it.first === transport.first }) distinct += transport }
    return distinct
}

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
