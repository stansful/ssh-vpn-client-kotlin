package com.stansful.sshvpnclient.xray

import android.content.Context
import com.stansful.sshvpnclient.domain.model.ProxyProfile
import com.stansful.sshvpnclient.domain.model.ProxyTestStatus
import com.stansful.sshvpnclient.domain.model.ProxyTunnelTestResult
import java.io.File
import java.lang.reflect.Proxy
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

class XrayCoreBridge(
    context: Context,
    private val configBuilder: XrayConfigBuilder,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val appContext = context.applicationContext
    private val testMutex = Mutex()
    private val binding: XrayBinding? by lazy { XrayBinding.load() }

    val isAvailable: Boolean
        get() = binding != null

    fun isRunning(): Boolean = binding?.isRunning() == true

    suspend fun startTun(
        profile: ProxyProfile,
        tunFd: Int,
        protectSocket: (Int) -> Boolean,
    ) = withContext(ioDispatcher) {
        val activeBinding = binding ?: error(CORE_UNAVAILABLE_MESSAGE)
        activeBinding.stop()
        activeBinding.registerSocketProtector(protectSocket)
        activeBinding.setTunFd(tunFd)
        activeBinding.runFromJson(
            dataDirectory = xrayDataDirectory().absolutePath,
            configJson = configBuilder.buildTunConfig(profile),
        )
    }

    suspend fun stop() = withContext(ioDispatcher) {
        binding?.stop()
    }

    fun stopBlocking() {
        binding?.stop()
    }

    suspend fun test(profile: ProxyProfile): ProxyTunnelTestResult = testMutex.withLock {
        withContext(ioDispatcher) {
            val activeBinding = binding ?: return@withContext ProxyTunnelTestResult(
                profileId = profile.id,
                status = ProxyTestStatus.UNSUPPORTED,
                message = CORE_UNAVAILABLE_MESSAGE,
            )
            if (profile.transport.name == "UNKNOWN" || profile.security.name == "UNKNOWN") {
                return@withContext ProxyTunnelTestResult(
                    profileId = profile.id,
                    status = ProxyTestStatus.UNSUPPORTED,
                    message = "Unsupported transport configuration",
                )
            }
            val port = ServerSocket(0).use { socket -> socket.localPort }
            val configFile = File(appContext.cacheDir, "xray-test-${profile.id}.json")
            runCatching {
                configFile.writeText(configBuilder.buildSocksTestConfig(profile, port))
                activeBinding.ping(
                    dataDirectory = xrayDataDirectory().absolutePath,
                    configPath = configFile.absolutePath,
                    timeoutSeconds = TEST_TIMEOUT_SECONDS,
                    url = TEST_URL,
                    proxyUrl = "socks5://127.0.0.1:$port",
                )
            }.fold(
                onSuccess = { latencyMs ->
                    ProxyTunnelTestResult(
                        profileId = profile.id,
                        status = ProxyTestStatus.AVAILABLE,
                        latencyMs = latencyMs,
                    )
                },
                onFailure = { error ->
                    ProxyTunnelTestResult(
                        profileId = profile.id,
                        status = ProxyTestStatus.UNAVAILABLE,
                        message = error.message ?: "Tunnel check failed",
                    )
                },
            ).also { configFile.delete() }
        }
    }

    private fun xrayDataDirectory(): File {
        return File(appContext.filesDir, "xray").apply { mkdirs() }
    }

    companion object {
        const val CORE_UNAVAILABLE_MESSAGE =
            "Xray core is not packaged. Run scripts/build-xray-core.sh first."
        private const val TEST_TIMEOUT_SECONDS = 8
        private const val TEST_URL = "https://www.youtube.com/generate_204"
    }
}

private class XrayBinding(private val apiClass: Class<*>) {
    fun registerSocketProtector(protector: (Int) -> Boolean) {
        listOf("registerDialerController", "registerListenerController").forEach { methodName ->
            val method = apiClass.methods.firstOrNull { it.name == methodName && it.parameterCount == 1 }
                ?: return@forEach
            val controllerType = method.parameterTypes.single()
            val controller = Proxy.newProxyInstance(
                controllerType.classLoader,
                arrayOf(controllerType),
            ) { _, invokedMethod, arguments ->
                when (invokedMethod.name) {
                    "protectFd", "ProtectFd" -> protector((arguments?.firstOrNull() as Number).toInt())
                    "toString" -> "shadow-ssh Xray socket protector"
                    else -> defaultValue(invokedMethod.returnType)
                }
            }
            method.invoke(null, controller)
        }
    }

    fun setTunFd(fd: Int) {
        invokeStatic("setTunFd", fd)
    }

    fun runFromJson(dataDirectory: String, configJson: String) {
        val requestMethod = apiClass.methods.firstOrNull {
            it.name == "newXrayRunFromJSONRequest" || it.name == "newXrayRunFromJsonRequest"
        } ?: error("Xray binding does not expose inline JSON requests")
        val request = when (requestMethod.parameterCount) {
            2 -> requestMethod.invoke(null, dataDirectory, configJson) as String
            3 -> requestMethod.invoke(null, dataDirectory, "", configJson) as String
            else -> error("Unsupported Xray request API")
        }
        decodeResponse(invokeStatic("runXrayFromJSON", request) as String)
    }

    fun ping(
        dataDirectory: String,
        configPath: String,
        timeoutSeconds: Int,
        url: String,
        proxyUrl: String,
    ): Long {
        val request = JSONObject()
            .put("datDir", dataDirectory)
            .put("configPath", configPath)
            .put("timeout", timeoutSeconds)
            .put("url", url)
            .put("proxy", proxyUrl)
            .toString()
        val encoded = Base64.getEncoder().encodeToString(request.toByteArray(StandardCharsets.UTF_8))
        val response = decodeResponse(invokeStatic("ping", encoded) as String)
        return response.optLong("data").takeIf { it >= 0L } ?: error("Tunnel check returned no latency")
    }

    fun stop() {
        runCatching { invokeStatic("stopXray") }
    }

    fun isRunning(): Boolean {
        return runCatching { invokeStatic("getXrayState") as Boolean }.getOrDefault(false)
    }

    private fun invokeStatic(name: String, vararg args: Any): Any? {
        val method = apiClass.methods.firstOrNull { it.name == name && it.parameterCount == args.size }
            ?: error("Xray binding method is missing: $name")
        return method.invoke(null, *args)
    }

    private fun decodeResponse(value: String): JSONObject {
        val decoded = String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8)
        val response = JSONObject(decoded)
        if (!response.optBoolean("success")) {
            error(response.optString("error", "Xray operation failed"))
        }
        return response
    }

    companion object {
        fun load(): XrayBinding? {
            val apiClass = XRAY_CLASS_NAMES.firstNotNullOfOrNull { className ->
                runCatching { Class.forName(className) }.getOrNull()
            }
            return apiClass?.let(::XrayBinding)
        }

        private val XRAY_CLASS_NAMES = listOf("libXray.LibXray", "libxray.LibXray")
    }
}

private fun defaultValue(type: Class<*>): Any? = when (type) {
    Boolean::class.javaPrimitiveType -> false
    Int::class.javaPrimitiveType -> 0
    Long::class.javaPrimitiveType -> 0L
    else -> null
}
