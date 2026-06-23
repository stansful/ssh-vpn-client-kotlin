package com.stansful.sshvpnclient.domain.model

data class XrayCoreAsset(
    val abi: String,
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val sha256Digest: String?,
    val universal: Boolean = false,
)

data class XrayCoreRelease(
    val versionName: String,
    val title: String,
    val releaseUrl: String,
    val runtimeAbi: String,
    val assets: List<XrayCoreAsset>,
)
