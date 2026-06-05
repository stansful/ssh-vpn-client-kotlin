package com.stansful.sshvpnclient.domain.usecase.key

import com.stansful.sshvpnclient.domain.model.SshPrivateKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SshPrivateKeyValidatorTest {
    private val validator = SshPrivateKeyValidator()

    @Test
    fun publicKeyIsRejectedWithSpecificMessage() {
        val errors = validator.validate(
            key(
                privateKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAITestOnly user@example",
            ),
        )

        assertEquals("privateKey", errors.single().field)
        assertEquals("Paste the private key file, not the .pub public key", errors.single().message)
    }

    @Test
    fun openSshPrivateKeyShapeIsAccepted() {
        val errors = validator.validate(
            key(
                privateKey = """
                    -----BEGIN OPENSSH PRIVATE KEY-----
                    test
                    -----END OPENSSH PRIVATE KEY-----
                """.trimIndent(),
            ),
        )

        assertTrue(errors.isEmpty())
    }

    private fun key(privateKey: String): SshPrivateKey {
        return SshPrivateKey(
            id = "key-1",
            name = "Test key",
            privateKey = privateKey,
            passphrase = null,
            note = null,
            createdAt = 0L,
            updatedAt = 0L,
        )
    }
}
