package com.stansful.sshvpnclient.screenshots.servers

import com.stansful.sshvpnclient.domain.model.AuthType
import com.stansful.sshvpnclient.domain.model.SshConfigSummary
import com.stansful.sshvpnclient.domain.model.SshPrivateKeySummary
import com.stansful.sshvpnclient.ui.configs.ConfigListItem
import com.stansful.sshvpnclient.ui.configs.ConfigListUiState
import com.stansful.sshvpnclient.ui.keys.KeyListItem
import com.stansful.sshvpnclient.ui.keys.KeyListUiState
import com.stansful.sshvpnclient.ui.keys.SshKeyFormat
import com.stansful.sshvpnclient.ui.keys.SshKeyTraits
import java.time.LocalDate
import java.time.ZoneId

/** The shared sample data of the design brief (§9): servers, keys and their dates. */
internal object ServersFixtures {
    val today: LocalDate = LocalDate.of(2026, 10, 6)

    private fun day(month: Int, dayOfMonth: Int): Long =
        LocalDate.of(2026, month, dayOfMonth).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    val workKey = SshPrivateKeySummary(
        id = "work",
        name = "work-ed25519",
        note = "Deploy access to the office network",
        createdAt = day(9, 1),
        updatedAt = day(10, 3),
        usageCount = 1,
    )
    val piKey = SshPrivateKeySummary(
        id = "pi",
        name = "pi-rsa",
        note = null,
        createdAt = day(9, 1),
        updatedAt = day(9, 21),
        usageCount = 1,
    )
    val oldKey = SshPrivateKeySummary(
        id = "old",
        name = "old-laptop",
        note = "From the previous laptop. No longer needed.",
        createdAt = day(6, 1),
        updatedAt = day(6, 2),
        usageCount = 0,
    )
    val keySummaries = listOf(workKey, piKey, oldKey)
    val keyTraits = mapOf(
        "work" to SshKeyTraits(SshKeyFormat.OPENSSH, hasPassphrase = false),
        "pi" to SshKeyTraits(SshKeyFormat.RSA_PEM, hasPassphrase = true),
        "old" to SshKeyTraits(SshKeyFormat.OPENSSH, hasPassphrase = false),
    )

    private fun server(
        id: String,
        name: String,
        user: String,
        host: String,
        port: Int,
        key: SshPrivateKeySummary?,
        fingerprint: String?,
        note: String?,
        selected: Boolean,
        updatedAt: Long,
    ) = ConfigListItem(
        config = SshConfigSummary(
            id = id,
            name = name,
            host = host,
            port = port,
            username = user,
            authType = if (key == null) AuthType.PASSWORD else AuthType.PRIVATE_KEY,
            privateKeyId = key?.id,
            keyName = key?.name,
            fingerprint = fingerprint,
            keepAliveIntervalSec = 30,
            enableUdpForwarding = false,
            note = note,
            isSelected = selected,
            updatedAt = updatedAt,
        ),
        keyName = key?.name,
        isSelected = selected,
    )

    fun servers(selectedId: String? = "home"): List<ConfigListItem> = listOf(
        server(
            "home", "Home VPS", "root", "vps.example.net", 22, null,
            "SHA256:atzfmdcrqQzoXZfKHLarePDyMw/G5NYfJ3h1eHUVS9g", "Main VPS in Helsinki",
            selectedId == "home", day(10, 4),
        ),
        server(
            "office", "Office bastion", "deploy", "198.51.100.10", 2222, workKey,
            "SHA256:Zm9yZ2V0LW1lLW5vdC10aGlzLWlzLWZpbmdlcnByaW50", null, selectedId == "office", day(10, 3),
        ),
        server(
            "pi", "Raspberry Pi", "pi", "home.example.org", 22, piKey,
            null, null, selectedId == "pi", day(9, 21),
        ),
    )

    fun serverList(selectedId: String? = "home") = ConfigListUiState(items = servers(selectedId), isLoaded = true)

    val keyList = KeyListUiState(
        items = keySummaries.map { KeyListItem(key = it, usageCount = it.usageCount, traits = keyTraits[it.id]) },
        isLoaded = true,
    )

    const val OPENSSH_KEY = """-----BEGIN OPENSSH PRIVATE KEY-----
b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW
QyNTUxOQAAACB7Rk2pQe9VhX3cL0yN5tGw8jZ1aU4mS6dK0rFvTbH2qQAAAJh0kR3EdJEd
xAAAAAtzc2gtZWQyNTUxOQAAACB7Rk2pQe9VhX3cL0yN5tGw8jZ1aU4mS6dK0rFvTbH2qQ
AAAEC9mWq1Lx4sT8yE3nR0aK6vG2hJ7cU5oP1dF9iZ4bN3XHtGTalB71WFfdwvTI3m0bDy
bWVkaWEtc2VydmVyQHNoYWRvdwECAwQFBg==
-----END OPENSSH PRIVATE KEY-----"""

    const val PUBLIC_KEY = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIHtGTalB71WFfdwvTI3m0bDyPWbTtOcm8VdA media@laptop"
}
