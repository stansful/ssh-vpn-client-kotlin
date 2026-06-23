package com.stansful.sshvpnclient.domain.model

object AndroidAbi {
    const val ARM64_V8A = "arm64-v8a"
    const val ARMEABI_V7A = "armeabi-v7a"
    const val X86 = "x86"
    const val X86_64 = "x86_64"

    val KNOWN_ABIS = listOf(ARM64_V8A, ARMEABI_V7A, X86, X86_64)

    fun runtimeAbi(
        supportedAbis: List<String>,
        osArch: String = System.getProperty("os.arch").orEmpty(),
    ): String {
        val normalizedArch = osArch.lowercase()
        val detected = when {
            normalizedArch == "aarch64" || normalizedArch == "arm64" -> ARM64_V8A
            normalizedArch.startsWith("arm") -> ARMEABI_V7A
            normalizedArch == "amd64" || normalizedArch == "x86_64" -> X86_64
            normalizedArch == "x86" ||
                normalizedArch == "i386" ||
                normalizedArch == "i686" -> X86
            else -> null
        }
        val normalizedSupported = supportedAbis.map(String::lowercase)
        return detected
            ?.takeIf { it.lowercase() in normalizedSupported }
            ?: supportedAbis.firstOrNull()
            ?: ARM64_V8A
    }

    fun assetNameMatchesAbi(name: String, abi: String): Boolean {
        val normalizedName = name.lowercase()
        if (abi.equals(X86, ignoreCase = true)) {
            return normalizedName.contains(X86) &&
                !normalizedName.contains(X86_64) &&
                !normalizedName.contains("x86-64")
        }
        return abiMarkers(abi).any(normalizedName::contains)
    }

    private fun abiMarkers(abi: String): List<String> {
        return when (abi.lowercase()) {
            ARM64_V8A -> listOf(ARM64_V8A, "arm64", "aarch64")
            ARMEABI_V7A -> listOf(ARMEABI_V7A, "armv7", "arm32")
            X86_64 -> listOf(X86_64, "x86-64", "x64")
            X86 -> listOf(X86)
            else -> listOf(abi.lowercase())
        }
    }
}
