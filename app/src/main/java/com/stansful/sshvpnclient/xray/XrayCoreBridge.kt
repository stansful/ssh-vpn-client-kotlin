package com.stansful.sshvpnclient.xray

import android.content.Context
import android.os.Build
import com.stansful.sshvpnclient.domain.model.AndroidAbi
import com.stansful.sshvpnclient.domain.model.ProxyProfile
import com.stansful.sshvpnclient.domain.model.ProxyTestStatus
import com.stansful.sshvpnclient.domain.model.ProxyTunnelTestResult
import dalvik.system.DexClassLoader
import java.io.File
import java.io.InputStream
import java.lang.reflect.Proxy
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
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
    private val bindingLock = Any()
    @Volatile
    private var cachedBinding: XrayBinding? = null
    @Volatile
    private var bindingLoaded = false
    @Volatile
    private var coreRestartPending = false

    val isAvailable: Boolean
        get() = binding() != null

    fun isRunning(): Boolean = binding()?.isRunning() == true

    suspend fun installCore(input: InputStream): XrayCoreInstallResult = withContext(ioDispatcher) {
        synchronized(bindingLock) {
            val previousBinding = cachedBinding.takeIf { bindingLoaded }
            val result = XrayCoreStore(appContext).install(
                input = input,
                activeCoreLoaded = previousBinding != null,
            )
            var effectiveResult = if (coreRestartPending && result == XrayCoreInstallResult.ALREADY_INSTALLED) {
                XrayCoreInstallResult.INSTALLED_AFTER_RESTART
            } else {
                result
            }
            val nextBinding = try {
                when (effectiveResult) {
                    XrayCoreInstallResult.INSTALLED -> XrayBinding.loadInstalledRequired(appContext)
                    XrayCoreInstallResult.ALREADY_INSTALLED ->
                        previousBinding ?: XrayBinding.loadInstalledRequired(appContext)
                    XrayCoreInstallResult.INSTALLED_AFTER_RESTART ->
                        previousBinding ?: XrayBinding.loadInstalledRequired(appContext)
                }
            } catch (error: Throwable) {
                if (error.isXrayNativeLibraryAlreadyLoaded()) {
                    effectiveResult = XrayCoreInstallResult.INSTALLED_AFTER_RESTART
                    previousBinding
                } else {
                    throw error
                }
            }
            cachedBinding = nextBinding
            bindingLoaded = true
            coreRestartPending = effectiveResult == XrayCoreInstallResult.INSTALLED_AFTER_RESTART
            effectiveResult
        }
    }

    suspend fun startTun(
        profile: ProxyProfile,
        tunFd: Int,
        protectSocket: (Int) -> Boolean,
    ) = withContext(ioDispatcher) {
        val activeBinding = binding() ?: error(CORE_UNAVAILABLE_MESSAGE)
        activeBinding.stop()
        activeBinding.registerSocketProtector(protectSocket)
        activeBinding.setTunFd(tunFd)
        activeBinding.runFromJson(
            dataDirectory = xrayDataDirectory().absolutePath,
            configJson = configBuilder.buildTunConfig(profile),
        )
    }

    suspend fun stop() = withContext(ioDispatcher) {
        binding()?.stop()
    }

    fun stopBlocking() {
        binding()?.stop()
    }

    suspend fun test(profile: ProxyProfile): ProxyTunnelTestResult = testMutex.withLock {
        withContext(ioDispatcher) {
            val activeBinding = binding() ?: return@withContext ProxyTunnelTestResult(
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

    private fun binding(): XrayBinding? {
        if (bindingLoaded) return cachedBinding
        return synchronized(bindingLock) {
            if (!bindingLoaded) {
                cachedBinding = XrayBinding.load(appContext)
                bindingLoaded = true
            }
            cachedBinding
        }
    }

    companion object {
        const val CORE_UNAVAILABLE_MESSAGE =
            "Xray runtime core is not installed. Download it from opensource settings."
        private const val TEST_TIMEOUT_SECONDS = 5
        private const val TEST_URL = "https://www.youtube.com/generate_204"
    }
}

enum class XrayCoreInstallResult {
    INSTALLED,
    ALREADY_INSTALLED,
    INSTALLED_AFTER_RESTART,
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
        fun load(context: Context): XrayBinding? {
            return loadWithDetails(context).binding
        }

        fun loadRequired(context: Context): XrayBinding {
            val result = loadWithDetails(context)
            return result.binding ?: error(
                buildString {
                    append("Installed Xray core could not be loaded")
                    if (result.errors.isNotEmpty()) {
                        append(": ")
                        append(result.errors.joinToString("; "))
                    }
                },
            )
        }

        fun loadInstalledRequired(context: Context): XrayBinding {
            val errors = mutableListOf<String>()
            val runtimeClassLoader = runCatching { XrayCoreStore(context).loadClassLoader() }
                .onFailure { error ->
                    errors += "runtime core prepare failed: ${error.xrayLoadMessage()}"
                }
                .getOrNull()
            runtimeClassLoader?.let { classLoader ->
                loadFromClassLoader(classLoader, errors)?.let { return it }
            }
            error(
                buildString {
                    append("Installed Xray core could not be loaded")
                    if (errors.isNotEmpty()) {
                        append(": ")
                        append(errors.joinToString("; "))
                    }
                },
            )
        }

        private fun loadWithDetails(context: Context): XrayBindingLoadResult {
            val errors = mutableListOf<String>()
            val runtimeClassLoader = runCatching { XrayCoreStore(context).loadClassLoader() }
                .onFailure { error ->
                    errors += "runtime core prepare failed: ${error.xrayLoadMessage()}"
                }
                .getOrNull()
            runtimeClassLoader?.let { classLoader ->
                loadFromClassLoader(classLoader, errors)?.let {
                    return XrayBindingLoadResult(it, errors)
                }
            }
            loadFromClassLoader(context.classLoader, errors)?.let {
                return XrayBindingLoadResult(it, errors)
            }
            return XrayBindingLoadResult(null, errors)
        }

        private fun loadFromClassLoader(
            classLoader: ClassLoader,
            errors: MutableList<String>,
        ): XrayBinding? {
            XRAY_CLASS_NAMES.forEach { className ->
                runCatching { Class.forName(className, true, classLoader) }
                    .onSuccess { apiClass -> return XrayBinding(apiClass) }
                    .onFailure { error ->
                        errors += "$className: ${error.xrayLoadMessage()}"
                    }
            }
            return null
        }

        private val XRAY_CLASS_NAMES = listOf("libXray.LibXray", "libxray.LibXray")
    }

    private data class XrayBindingLoadResult(
        val binding: XrayBinding?,
        val errors: List<String>,
    )
}

private class XrayCoreStore(context: Context) {
    private val appContext = context.applicationContext
    private val rootDir = File(appContext.filesDir, CORE_DIRECTORY_NAME)
    private val preparedDir = File(rootDir, PREPARED_DIRECTORY_NAME)
    private val optimizedDexDir = File(rootDir, OPTIMIZED_DEX_DIRECTORY_NAME)
    private val stampFile = File(preparedDir, STAMP_FILE_NAME)
    val coreFile = File(rootDir, CORE_FILE_NAME)

    fun install(input: InputStream, activeCoreLoaded: Boolean): XrayCoreInstallResult {
        rootDir.mkdirs()
        val downloadedFile = File(rootDir, "$CORE_FILE_NAME.download")
        val tempFile = File(rootDir, "$CORE_FILE_NAME.tmp")
        runCatching { downloadedFile.delete() }
        runCatching { tempFile.delete() }
        input.use { source ->
            downloadedFile.outputStream().use { destination -> source.copyTo(destination) }
        }
        val abi = validateCore(downloadedFile)
        writeSlimCore(downloadedFile, tempFile, abi)
        val alreadyInstalled = coreFile.isFile &&
            runCatching { corePayloadDigest(coreFile, abi) == corePayloadDigest(tempFile, abi) }
                .getOrDefault(false)
        if (alreadyInstalled) {
            downloadedFile.delete()
            tempFile.delete()
            return XrayCoreInstallResult.ALREADY_INSTALLED
        }
        if (coreFile.exists()) check(coreFile.delete()) { "Unable to replace existing Xray core" }
        check(tempFile.renameTo(coreFile)) { "Unable to install Xray core" }
        downloadedFile.delete()
        if (activeCoreLoaded) {
            return XrayCoreInstallResult.INSTALLED_AFTER_RESTART
        }
        preparedDir.deleteRecursively()
        optimizedDexDir.deleteRecursively()
        return XrayCoreInstallResult.INSTALLED
    }

    fun loadClassLoader(): ClassLoader? {
        if (!coreFile.isFile) return null
        val prepared = prepareCore()
        return DexClassLoader(
            prepared.dexFile.absolutePath,
            optimizedDexDir.apply { mkdirs() }.absolutePath,
            prepared.nativeLibraryDir.absolutePath,
            appContext.classLoader,
        )
    }

    private fun prepareCore(): PreparedCore {
        val stamp = coreStamp()
        val dexFile = File(preparedDir, CLASSES_DEX_NAME)
        val abi = runtimeAbi()
        val nativeLibraryDir = File(preparedDir, abi)
        val nativeLibrary = File(nativeLibraryDir, NATIVE_LIBRARY_NAME)
        if (
            stampFile.readTextOrNull() == stamp &&
            dexFile.isFile &&
            nativeLibrary.isFile
        ) {
            markReadOnly(dexFile)
            markReadOnly(nativeLibrary)
            return PreparedCore(dexFile, nativeLibraryDir)
        }

        preparedDir.deleteRecursively()
        nativeLibraryDir.mkdirs()
        ZipFile(coreFile).use { zip ->
            extractEntry(zip, CLASSES_DEX_NAME, dexFile)
            extractEntry(zip, "jni/$abi/$NATIVE_LIBRARY_NAME", nativeLibrary)
        }
        markReadOnly(dexFile)
        markReadOnly(nativeLibrary)
        stampFile.writeText(stamp)
        return PreparedCore(dexFile, nativeLibraryDir)
    }

    private fun validateCore(file: File): String {
        val abi = runtimeAbi()
        ZipFile(file).use { zip ->
            require(zip.getEntry(CLASSES_DEX_NAME) != null) {
                "Xray core archive is outdated: missing $CLASSES_DEX_NAME. Rebuild release assets."
            }
            require(zip.getEntry("jni/$abi/$NATIVE_LIBRARY_NAME") != null) {
                "Xray core archive does not contain native library for runtime ABI $abi"
            }
        }
        return abi
    }

    private fun runtimeAbi(): String {
        return AndroidAbi.runtimeAbi(Build.SUPPORTED_ABIS.toList())
    }

    private fun coreStamp(): String {
        return "${coreFile.length()}:${coreFile.lastModified()}:${runtimeAbi()}"
    }

    private fun extractEntry(zip: ZipFile, name: String, destination: File) {
        val entry = zip.getEntry(name) ?: error("Xray core archive is missing $name")
        destination.parentFile?.mkdirs()
        zip.getInputStream(entry).use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private fun writeSlimCore(source: File, destination: File, abi: String) {
        ZipFile(source).use { zip ->
            ZipOutputStream(destination.outputStream()).use { output ->
                copyEntry(zip, CLASSES_DEX_NAME, output)
                copyEntry(zip, "jni/$abi/$NATIVE_LIBRARY_NAME", output)
            }
        }
    }

    private fun corePayloadDigest(file: File, abi: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        ZipFile(file).use { zip ->
            listOf(CLASSES_DEX_NAME, "jni/$abi/$NATIVE_LIBRARY_NAME").forEach { name ->
                digest.update(name.toByteArray(StandardCharsets.UTF_8))
                val entry = zip.getEntry(name) ?: error("Xray core archive is missing $name")
                zip.getInputStream(entry).use { input ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                    }
                }
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun copyEntry(zip: ZipFile, name: String, output: ZipOutputStream) {
        val sourceEntry = zip.getEntry(name) ?: error("Xray core archive is missing $name")
        val targetEntry = ZipEntry(name).apply {
            time = sourceEntry.time
            method = ZipEntry.DEFLATED
        }
        output.putNextEntry(targetEntry)
        zip.getInputStream(sourceEntry).use { input -> input.copyTo(output) }
        output.closeEntry()
    }

    private fun markReadOnly(file: File) {
        check(file.setWritable(false, false) || !file.canWrite()) {
            "Unable to make ${file.name} read-only"
        }
    }

    private fun File.readTextOrNull(): String? {
        return runCatching { readText() }.getOrNull()
    }

    private data class PreparedCore(
        val dexFile: File,
        val nativeLibraryDir: File,
    )

    private companion object {
        const val CORE_DIRECTORY_NAME = "xray-core"
        const val PREPARED_DIRECTORY_NAME = "prepared"
        const val OPTIMIZED_DEX_DIRECTORY_NAME = "dex"
        const val CORE_FILE_NAME = "libXray.aar"
        const val CLASSES_DEX_NAME = "classes.dex"
        const val NATIVE_LIBRARY_NAME = "libgojni.so"
        const val STAMP_FILE_NAME = "stamp"
        const val COPY_BUFFER_SIZE = 32 * 1_024
    }
}

private fun defaultValue(type: Class<*>): Any? = when (type) {
    Boolean::class.javaPrimitiveType -> false
    Int::class.javaPrimitiveType -> 0
    Long::class.javaPrimitiveType -> 0L
    else -> null
}

private fun Throwable.xrayLoadMessage(): String {
    return if (isXrayNativeLibraryAlreadyLoaded()) {
        "Xray native library is already loaded by a previous runtime loader. Restart the app to finish installing the core."
    } else {
        message ?: this::class.java.simpleName
    }
}

private fun Throwable.isXrayNativeLibraryAlreadyLoaded(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current.message?.contains("already opened by ClassLoader", ignoreCase = true) == true) {
            return true
        }
        current = current.cause
    }
    return false
}
