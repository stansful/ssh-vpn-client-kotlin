package com.stansful.sshvpnclient.vpn

import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Logger
import com.jcraft.jsch.Session
import com.stansful.sshvpnclient.domain.model.AuthType
import com.stansful.sshvpnclient.domain.model.SshConfig
import com.stansful.sshvpnclient.domain.model.SshPrivateKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Socket
import java.util.Properties

class SshConnectionManager {
    private var activeSession: Session? = null

    suspend fun connect(
        config: SshConfig,
        privateKey: SshPrivateKey?,
        log: (String) -> Unit = {},
        socketProtector: ((Socket) -> Boolean)? = null,
    ): Session = withContext(Dispatchers.IO) {
        disconnect()

        installJschLogger(log)
        configureEdDsaSupport(log)
        val jsch = JSch()
        if (config.authType == AuthType.PRIVATE_KEY) {
            val key = privateKey ?: throw VpnConnectionException("Selected SSH key not found")
            log("Loading private key into SSH client")
            addPrivateKeyIdentity(jsch, key)
            log("Private key loaded; passphrase present: ${!key.passphrase.isNullOrBlank()}")
        }

        try {
            log("Opening SSH session to ${config.username}@${config.host}:${config.port}")
            val session = jsch.getSession(config.username, config.host, config.port)
            if (socketProtector != null) {
                session.setSocketFactory(
                    VpnProtectedSocketFactory(
                        protectSocket = socketProtector,
                        connectTimeoutMs = CONNECT_TIMEOUT_MS,
                    ),
                )
                log("SSH socket protection enabled")
            }
            session.setConfig(connectionConfig(config.authType))
            session.setServerAliveInterval(config.keepAliveIntervalSec * 1000)
            log("SSH auth method: ${config.authType.label}; keepAlive=${config.keepAliveIntervalSec}s")

            if (config.authType == AuthType.PASSWORD) {
                session.setPassword(
                    config.password ?: throw VpnConnectionException("Authentication failed"),
                )
            }

            session.connect(CONNECT_TIMEOUT_MS)
            log("SSH transport connected")
            verifyFingerprintIfNeeded(jsch, session, config.fingerprint, log)
            activeSession = session
            session
        } catch (error: JSchException) {
            log("JSch exception: ${error.message.orEmpty().ifBlank { error::class.java.simpleName }}")
            throw mapJschError(error, config)
        }
    }

    fun disconnect() {
        activeSession?.takeIf { it.isConnected }?.disconnect()
        activeSession = null
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
            message.contains("UnknownHost", ignoreCase = true) -> "Host unreachable"
            message.contains("protect SSH socket", ignoreCase = true) ->
                "Could not protect SSH socket from VPN routing"
            message.contains("invalid privatekey", ignoreCase = true) -> "Invalid private key format"
            else -> "Unknown connection error"
        }
        return VpnConnectionException(userMessage, error)
    }

    private fun installJschLogger(log: (String) -> Unit) {
        JSch.setLogger(
            object : Logger {
                override fun isEnabled(level: Int): Boolean = true

                override fun log(level: Int, message: String?) {
                    val value = message?.trim().orEmpty()
                    if (value.isBlank()) return
                    log("JSch ${levelLabel(level)}: $value")
                }
            },
        )
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
        const val CONNECT_TIMEOUT_MS = 20_000
    }
}
