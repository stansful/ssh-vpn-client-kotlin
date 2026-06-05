package com.stansful.sshvpnclient.vpn

import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.stansful.sshvpnclient.domain.model.AuthType
import com.stansful.sshvpnclient.domain.model.SshConfig
import com.stansful.sshvpnclient.domain.model.SshPrivateKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties

class SshConnectionManager {
    private var activeSession: Session? = null

    suspend fun connect(
        config: SshConfig,
        privateKey: SshPrivateKey?,
    ): Session = withContext(Dispatchers.IO) {
        disconnect()

        val jsch = JSch()
        if (config.authType == AuthType.PRIVATE_KEY) {
            val key = privateKey ?: throw VpnConnectionException("Selected SSH key not found")
            addPrivateKeyIdentity(jsch, key)
        }

        try {
            val session = jsch.getSession(config.username, config.host, config.port)
            session.setConfig(connectionConfig(config.authType))
            session.setServerAliveInterval(config.keepAliveIntervalSec * 1000)

            if (config.authType == AuthType.PASSWORD) {
                session.setPassword(
                    config.password ?: throw VpnConnectionException("Authentication failed"),
                )
            }

            session.connect(CONNECT_TIMEOUT_MS)
            verifyFingerprintIfNeeded(jsch, session, config.fingerprint)
            activeSession = session
            session
        } catch (error: JSchException) {
            throw mapJschError(error, config.authType)
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
    ) {
        val expected = expectedFingerprint?.trim().orEmpty()
        if (expected.isBlank()) return

        val actual = session.hostKey.getFingerPrint(jsch)
        if (normalizeFingerprint(actual) != normalizeFingerprint(expected)) {
            session.disconnect()
            throw VpnConnectionException("Fingerprint mismatch")
        }
    }

    private fun normalizeFingerprint(value: String): String {
        return value
            .lowercase()
            .replace(":", "")
            .replace(" ", "")
    }

    private fun mapJschError(error: JSchException, authType: AuthType): VpnConnectionException {
        val message = error.message.orEmpty()
        val userMessage = when {
            message.contains("Auth fail", ignoreCase = true) &&
                authType == AuthType.PRIVATE_KEY -> "Invalid private key passphrase"
            message.contains("Auth fail", ignoreCase = true) -> "Authentication failed"
            message.contains("timeout", ignoreCase = true) -> "Connection timeout"
            message.contains("UnknownHost", ignoreCase = true) -> "Host unreachable"
            message.contains("invalid privatekey", ignoreCase = true) -> "Invalid private key format"
            else -> "Unknown connection error"
        }
        return VpnConnectionException(userMessage, error)
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 20_000
    }
}
