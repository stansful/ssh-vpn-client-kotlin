package com.stansful.sshvpnclient.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAbiTest {
    @Test
    fun `detects runtime abi from process architecture`() {
        assertEquals(
            AndroidAbi.ARM64_V8A,
            AndroidAbi.runtimeAbi(
                supportedAbis = listOf(AndroidAbi.ARM64_V8A, AndroidAbi.ARMEABI_V7A),
                osArch = "aarch64",
            ),
        )
        assertEquals(
            AndroidAbi.X86_64,
            AndroidAbi.runtimeAbi(
                supportedAbis = listOf(AndroidAbi.X86_64, AndroidAbi.X86),
                osArch = "amd64",
            ),
        )
    }

    @Test
    fun `does not match x86_64 assets for x86`() {
        assertTrue(AndroidAbi.assetNameMatchesAbi("libXray-x86.aar", AndroidAbi.X86))
        assertFalse(AndroidAbi.assetNameMatchesAbi("libXray-x86_64.aar", AndroidAbi.X86))
        assertFalse(AndroidAbi.assetNameMatchesAbi("libXray-x86-64.aar", AndroidAbi.X86))
    }

    @Test
    fun `matches common arm abi aliases`() {
        assertTrue(AndroidAbi.assetNameMatchesAbi("libXray-aarch64.aar", AndroidAbi.ARM64_V8A))
        assertTrue(AndroidAbi.assetNameMatchesAbi("libXray-armv7.aar", AndroidAbi.ARMEABI_V7A))
    }
}
