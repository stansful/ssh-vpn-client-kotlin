package com.stansful.sshvpnclient.data.update

import org.junit.Assert.assertEquals
import org.junit.Test

class GitHubAppUpdateRepositoryTest {
    @Test
    fun `selects published abi split name without embedded version`() {
        val selected = selectBestApkAssetName(
            apkNames = listOf(
                "app-armeabi-v7a-release.apk",
                "app-arm64-v8a-release.apk",
                "app-universal-release.apk",
                "app-x86-release.apk",
                "app-x86_64-release.apk",
            ),
            versionText = "2.5.7",
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
        )

        assertEquals("app-arm64-v8a-release.apk", selected)
    }

    @Test
    fun `falls back to published universal name for an unknown abi`() {
        val selected = selectBestApkAssetName(
            apkNames = listOf(
                "app-arm64-v8a-release.apk",
                "app-universal-release.apk",
                "app-x86_64-release.apk",
            ),
            versionText = "2.5.7",
            supportedAbis = listOf("riscv64"),
        )

        assertEquals("app-universal-release.apk", selected)
    }

    @Test
    fun `selects apk for the first supported device abi`() {
        val selected = selectBestApkAssetName(
            apkNames = listOf(
                "shadow-ssh-2.4.0-armeabi-v7a.apk",
                "shadow-ssh-2.4.0-arm64-v8a.apk",
                "shadow-ssh-2.4.0-x86_64.apk",
            ),
            versionText = "2.4.0",
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
        )

        assertEquals("shadow-ssh-2.4.0-arm64-v8a.apk", selected)
    }

    @Test
    fun `does not select x86_64 apk for x86 devices`() {
        val selected = selectBestApkAssetName(
            apkNames = listOf(
                "shadow-ssh-2.4.0-x86_64.apk",
                "shadow-ssh-2.4.0-x86.apk",
            ),
            versionText = "2.4.0",
            supportedAbis = listOf("x86"),
        )

        assertEquals("shadow-ssh-2.4.0-x86.apk", selected)
    }

    @Test
    fun `falls back to universal apk when no abi split matches`() {
        val selected = selectBestApkAssetName(
            apkNames = listOf(
                "shadow-ssh-2.4.0-x86_64.apk",
                "shadow-ssh-2.4.0-universal.apk",
            ),
            versionText = "2.4.0",
            supportedAbis = listOf("riscv64"),
        )

        assertEquals("shadow-ssh-2.4.0-universal.apk", selected)
    }

    @Test
    fun `keeps old single apk release compatible`() {
        val selected = selectBestApkAssetName(
            apkNames = listOf("shadow-ssh-2.4.0.apk"),
            versionText = "2.4.0",
            supportedAbis = listOf("arm64-v8a"),
        )

        assertEquals("shadow-ssh-2.4.0.apk", selected)
    }
}
