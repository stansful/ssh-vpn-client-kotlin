package com.stansful.sshvpnclient.xray

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import com.stansful.sshvpnclient.domain.model.AndroidAbi
import com.stansful.sshvpnclient.domain.model.ProxyProfile
import com.stansful.sshvpnclient.domain.model.ProxyTestStatus
import com.stansful.sshvpnclient.domain.model.ProxyTunnelTestResult
import com.stansful.sshvpnclient.vpn.VpnConnectionException
import com.stansful.sshvpnclient.vpn.VpnRuntimeLease
import dalvik.system.DexClassLoader
import java.io.File
import java.io.InputStream
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicReferenceArray
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class XrayCoreBridge(
    context: Context,
    private val configBuilder: XrayConfigBuilder,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val appContext = context.applicationContext
    private val testMutex = Mutex()
    private val runtimeStartMutex = Mutex()
    private val nativeLifecycleGate = XrayNativeLifecycleGate()
    private val bindingLock = Any()
    private val runtimeStateLock = Any()
    private val batchSecureRandom = SecureRandom()
    private var runtimeGeneration = 0L
    private var activeRuntimeGeneration: Long? = null
    private var activeRuntimeOwner: Any? = null
    @Volatile
    private var cachedBinding: XrayBinding? = null
    @Volatile
    private var bindingLoaded = false
    @Volatile
    private var coreRestartPending = false

    val isAvailable: Boolean
        get() = binding() != null

    fun isRunning(): Boolean = nativeLifecycleGate.withLock {
        binding()?.isRunning() == true
    }

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
        owner: Any,
        lease: VpnRuntimeLease,
        profile: ProxyProfile,
        tunFd: Int,
        dnsServer: String?,
        protectSocket: (Int) -> Boolean,
        protectListenerSocket: (Int) -> Boolean = protectSocket,
        onGenerationReserved: (Long) -> Unit = {},
    ): Long = runtimeStartMutex.withLock {
        testMutex.withLock {
            require(lease.owner === owner) { "Xray owner must match runtime lease" }
            withContext(ioDispatcher) {
                val operationContext = currentCoroutineContext()
                nativeLifecycleGate.withLock {
                    val activeBinding = binding() ?: error(CORE_UNAVAILABLE_MESSAGE)
                    val generation = lease.requireCurrent {
                        synchronized(runtimeStateLock) {
                            if (!lease.isCurrent()) {
                                throw CancellationException("Xray runtime lease was superseded")
                            }
                            if (activeRuntimeOwner != null && activeRuntimeOwner !== owner) {
                                throw VpnConnectionException(
                                    "Xray runtime belongs to another service instance",
                                )
                            }
                            runtimeGeneration += 1L
                            activeRuntimeOwner = owner
                            activeRuntimeGeneration = runtimeGeneration
                            runtimeGeneration.also(onGenerationReserved)
                        }
                    }
                    try {
                        synchronized(runtimeStateLock) {
                            if (!lease.isCurrent() ||
                                activeRuntimeOwner !== owner ||
                                activeRuntimeGeneration != generation
                            ) {
                                throw CancellationException("Xray runtime generation was superseded")
                            }
                            // Native stop and protector replacement share the generation lock with
                            // stopBlocking, so a concurrent service stop cannot leave a stale delegate.
                            activeBinding.stop()
                            activeBinding.resetDns()
                            activeBinding.clearSocketProtector()
                            activeBinding.updateSocketProtectors(
                                dialerProtector = protectSocket,
                                listenerProtector = protectListenerSocket,
                            )
                            dnsServer?.let(activeBinding::initDns)
                        }
                        ensureRuntimeGenerationCurrent(owner, generation, lease)
                        activeBinding.setTunFd(tunFd)
                        ensureRuntimeGenerationCurrent(owner, generation, lease)
                        activeBinding.runFromJson(
                            dataDirectory = xrayDataDirectory().absolutePath,
                            configJson = configBuilder.buildTunConfig(profile),
                        )
                        operationContext.ensureActive()
                        ensureRuntimeGenerationCurrent(owner, generation, lease)
                        generation
                    } catch (error: Exception) {
                        stopBlocking(owner, generation)
                        throw error
                    }
                }
            }
        }
    }

    fun stopBlocking(owner: Any, expectedGeneration: Long): Boolean =
        nativeLifecycleGate.withLock {
            stopBlockingNativeLocked(owner, expectedGeneration)
        }

    private fun stopBlockingNativeLocked(owner: Any, expectedGeneration: Long): Boolean =
        synchronized(runtimeStateLock) {
            if (activeRuntimeOwner !== owner || activeRuntimeGeneration != expectedGeneration) {
                return@synchronized false
            }
            val activeBinding = binding() ?: return@synchronized false
            runCatching { activeBinding.stop() }.getOrElse { return@synchronized false }
            runCatching { activeBinding.resetDns() }
            activeBinding.clearSocketProtector()
            activeRuntimeGeneration = null
            activeRuntimeOwner = null
            true
        }

    fun isRuntimeGenerationCurrent(owner: Any, generation: Long): Boolean {
        return synchronized(runtimeStateLock) {
            activeRuntimeOwner === owner && activeRuntimeGeneration == generation
        }
    }

    private fun ensureRuntimeGenerationCurrent(
        owner: Any,
        generation: Long,
        lease: VpnRuntimeLease,
    ) {
        if (!lease.isCurrent() || !isRuntimeGenerationCurrent(owner, generation)) {
            throw CancellationException("Xray runtime generation was superseded")
        }
    }

    suspend fun test(profile: ProxyProfile): ProxyTunnelTestResult = testMutex.withLock {
        withContext(ioDispatcher) {
            if (hasActiveRuntimeOwner()) {
                throw XrayRuntimeBusyException()
            }
            val startedAtNanos = System.nanoTime()
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
            var configFile: File? = null
            try {
                val createdConfigFile = File.createTempFile("xray-test-", ".json", appContext.cacheDir)
                configFile = createdConfigFile
                createdConfigFile.writeText(configBuilder.buildSocksTestConfig(profile, port))
                val latencyMs = activeBinding.ping(
                    dataDirectory = xrayDataDirectory().absolutePath,
                    configPath = createdConfigFile.absolutePath,
                    timeoutSeconds = TEST_TIMEOUT_SECONDS,
                    url = TEST_URL,
                    proxyUrl = "socks5://127.0.0.1:$port",
                )
                val elapsedNanos = System.nanoTime() - startedAtNanos
                classifyTunnelTestLatency(
                    profileId = profile.id,
                    latencyMs = effectiveTunnelTestLatency(latencyMs, elapsedNanos),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                ProxyTunnelTestResult(
                    profileId = profile.id,
                    status = ProxyTestStatus.UNAVAILABLE,
                    message = error.message ?: "Tunnel check failed",
                )
            } finally {
                configFile?.delete()
            }
        }
    }

    /**
     * Starts one Xray server for the whole list and selects a tagged outbound through SOCKS5
     * credentials. This keeps Xray's process-global state single-instance while the lightweight
     * HTTP probes run concurrently.
     */
    suspend fun testBatch(
        profiles: List<ProxyProfile>,
        deadlineNanos: Long = System.nanoTime() +
            XRAY_BATCH_TOTAL_BUDGET_MS * NANOS_PER_MILLISECOND,
        onResult: suspend (ProxyTunnelTestResult) -> Unit = {},
    ): List<ProxyTunnelTestResult> = testMutex.withLock {
        if (profiles.isEmpty()) return@withLock emptyList()
        withContext(ioDispatcher) {
            if (hasActiveRuntimeOwner()) throw XrayRuntimeBusyException()
            val activeBinding = binding() ?: run {
                val notTested = profiles.map { profile ->
                    ProxyTunnelTestResult(
                        profileId = profile.id,
                        status = ProxyTestStatus.NOT_TESTED,
                        message = CORE_UNAVAILABLE_MESSAGE,
                    )
                }
                notTested.forEach { result -> onResult(result) }
                return@withContext notTested
            }
            val password = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ByteArray(BATCH_PASSWORD_BYTES).also(batchSecureRandom::nextBytes),
            )
            val entries = profiles.mapIndexed { index, profile ->
                XrayBatchSocksTestEntry(profile, "probe-$index")
            }
            val physicalNetwork = findPhysicalNetwork() ?: run {
                val notTested = profiles.map { profile ->
                    ProxyTunnelTestResult(
                        profileId = profile.id,
                        status = ProxyTestStatus.NOT_TESTED,
                        message = "No physical network is available for tunnel checks",
                    )
                }
                notTested.forEach { result -> onResult(result) }
                return@withContext notTested
            }
            val isolationAttempts = AtomicInteger(0)
            testBatchEntries(
                activeBinding = activeBinding,
                entries = entries,
                password = password,
                physicalNetwork = physicalNetwork,
                deadlineNanos = deadlineNanos,
                onResult = onResult,
                isolationAttempts = isolationAttempts,
            )
        }
    }

    private suspend fun testBatchEntries(
        activeBinding: XrayBinding,
        entries: List<XrayBatchSocksTestEntry>,
        password: String,
        physicalNetwork: Network,
        deadlineNanos: Long,
        onResult: suspend (ProxyTunnelTestResult) -> Unit,
        isolationAttempts: AtomicInteger,
    ): List<ProxyTunnelTestResult> {
        if (!hasFullProbeBudget(deadlineNanos)) {
            return entries.map { entry ->
                deadlineNotTestedResult(entry.profile.id).also { result -> onResult(result) }
            }
        }
        val socksPort = reserveLoopbackPort()
        val configJson = try {
            configBuilder.buildBatchSocksTestConfig(
                entries = entries,
                socksPort = socksPort,
                password = password,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return isolateInvalidBatchEntries(
                activeBinding = activeBinding,
                entries = entries,
                password = password,
                physicalNetwork = physicalNetwork,
                deadlineNanos = deadlineNanos,
                onResult = onResult,
                failure = error,
                configFailure = true,
                isolationAttempts = isolationAttempts,
            )
        }
        if (!hasFullProbeBudget(deadlineNanos)) {
            return entries.map { entry ->
                deadlineNotTestedResult(entry.profile.id).also { result -> onResult(result) }
            }
        }

        var runtimeStartAttempted = false
        val bindFailed = AtomicBoolean(false)
        if (isolationAttempts.incrementAndGet() > MAX_BATCH_RUNTIME_START_ATTEMPTS) {
            return entries.map { entry ->
                ProxyTunnelTestResult(
                    profileId = entry.profile.id,
                    status = ProxyTestStatus.NOT_TESTED,
                    message = "Batch runtime start-attempt limit reached",
                ).also { result -> onResult(result) }
            }
        }
        val startFailure = runCatching {
            nativeLifecycleGate.withLock {
                if (hasActiveRuntimeOwner()) throw XrayRuntimeBusyException()
                runtimeStartAttempted = true
                activeBinding.stop()
                activeBinding.updateSocketProtectors(
                    dialerProtector = { fd ->
                        bindSocketToNetwork(physicalNetwork, fd).also { bound ->
                            if (!bound) bindFailed.set(true)
                        }
                    },
                    listenerProtector = { true },
                )
                activeBinding.runFromJson(
                    dataDirectory = xrayDataDirectory().absolutePath,
                    configJson = configJson,
                )
            }
            currentCoroutineContext().ensureActive()
            check(activeBinding.isRunning()) { "Batch Xray runtime did not start" }
        }.exceptionOrNull()
        if (startFailure != null) {
            stopBatchRuntime(activeBinding, runtimeStartAttempted)
            if (startFailure is Error) throw startFailure
            if (startFailure is CancellationException) throw startFailure
            if (startFailure is XrayRuntimeBusyException) throw startFailure
            if (isLoopbackBindConflict(startFailure) &&
                isolationAttempts.get() < MAX_BATCH_LOOPBACK_BIND_ATTEMPTS &&
                hasFullProbeBudget(deadlineNanos)
            ) {
                // The reservation socket must close before Xray can listen. Retry the rare TOCTOU
                // collision with a new ephemeral port instead of discarding the whole batch.
                return testBatchEntries(
                    activeBinding = activeBinding,
                    entries = entries,
                    password = password,
                    physicalNetwork = physicalNetwork,
                    deadlineNanos = deadlineNanos,
                    onResult = onResult,
                    isolationAttempts = isolationAttempts,
                )
            }
            val configFailure = isLikelyXrayConfigFailure(startFailure)
            if (!configFailure) {
                return entries.map { entry ->
                    ProxyTunnelTestResult(
                        profileId = entry.profile.id,
                        status = ProxyTestStatus.NOT_TESTED,
                        message = startFailure.message ?: "Batch Xray runtime could not start",
                    ).also { result -> onResult(result) }
                }
            }
            return isolateInvalidBatchEntries(
                activeBinding = activeBinding,
                entries = entries,
                password = password,
                physicalNetwork = physicalNetwork,
                deadlineNanos = deadlineNanos,
                onResult = onResult,
                failure = startFailure,
                configFailure = true,
                isolationAttempts = isolationAttempts,
            )
        }

        return try {
            val remainingMs = ((deadlineNanos - System.nanoTime()) / NANOS_PER_MILLISECOND)
                .coerceAtLeast(1L)
            val concurrency = deviceAwareBatchProbeConcurrency(
                requested = batchProbeConcurrency(entries.size, remainingMs),
                minimumForDeadline = minimumBatchProbeConcurrencyForDeadline(
                    total = entries.size,
                    remainingMs = remainingMs,
                ),
                isLowRamDevice = appContext
                    .getSystemService(ActivityManager::class.java)
                    ?.isLowRamDevice == true,
                isPowerSaveMode = appContext
                    .getSystemService(PowerManager::class.java)
                    ?.isPowerSaveMode == true,
            )
            val probeDispatcher = Dispatchers.IO.limitedParallelism(concurrency)
            val results = mapBatchConcurrentOrdered(
                values = entries,
                maxConcurrency = concurrency,
                dispatcher = probeDispatcher,
                onResult = { result -> onResult(result) },
            ) { entry ->
                probeBatchTunnel(
                    entry = entry,
                    socksPort = socksPort,
                    password = password,
                    deadlineNanos = deadlineNanos,
                )
            }
            val infrastructureFailure = when {
                bindFailed.get() -> "Could not bind Xray sockets to the physical network"
                !activeBinding.isRunning() -> "Batch Xray runtime stopped before checks completed"
                else -> null
            }
            if (infrastructureFailure != null) {
                results.map { result ->
                    ProxyTunnelTestResult(
                        profileId = result.profileId,
                        status = ProxyTestStatus.NOT_TESTED,
                        message = infrastructureFailure,
                    )
                }
            } else {
                results
            }
        } finally {
            stopBatchRuntime(activeBinding, runtimeStartAttempted = true)
        }
    }

    private suspend fun isolateInvalidBatchEntries(
        activeBinding: XrayBinding,
        entries: List<XrayBatchSocksTestEntry>,
        password: String,
        physicalNetwork: Network,
        deadlineNanos: Long,
        onResult: suspend (ProxyTunnelTestResult) -> Unit,
        failure: Throwable,
        configFailure: Boolean,
        isolationAttempts: AtomicInteger,
    ): List<ProxyTunnelTestResult> {
        if (entries.size == 1) {
            val result = ProxyTunnelTestResult(
                profileId = entries.single().profile.id,
                status = if (configFailure) {
                    ProxyTestStatus.UNSUPPORTED
                } else {
                    ProxyTestStatus.NOT_TESTED
                },
                message = failure.message ?: "Xray rejected this configuration",
            )
            onResult(result)
            return listOf(result)
        }
        if (!hasFullProbeBudget(deadlineNanos)) {
            return entries.map { entry ->
                deadlineNotTestedResult(entry.profile.id).also { result -> onResult(result) }
            }
        }
        val midpoint = entries.size / 2
        val left = testBatchEntries(
            activeBinding,
            entries.subList(0, midpoint),
            password,
            physicalNetwork,
            deadlineNanos,
            onResult,
            isolationAttempts,
        )
        val right = testBatchEntries(
            activeBinding,
            entries.subList(midpoint, entries.size),
            password,
            physicalNetwork,
            deadlineNanos,
            onResult,
            isolationAttempts,
        )
        return left + right
    }

    private fun isLikelyXrayConfigFailure(error: Throwable): Boolean {
        val message = generateSequence(error) { it.cause }
            .mapNotNull(Throwable::message)
            .joinToString(" ")
            .lowercase()
        return listOf(
            "invalid config",
            "outbound config",
            "outbound",
            "protocol",
            "uuid",
            "reality",
            "public key",
            "short id",
            "failed to build",
            "failed to parse",
        ).any(message::contains)
    }

    private fun isLoopbackBindConflict(error: Throwable): Boolean {
        val message = generateSequence(error) { it.cause }
            .mapNotNull(Throwable::message)
            .joinToString(" ")
            .lowercase()
        return "address already in use" in message ||
            "eaddrinuse" in message ||
            ("failed to listen" in message && "in use" in message)
    }

    private fun stopBatchRuntime(activeBinding: XrayBinding, runtimeStartAttempted: Boolean) {
        if (!runtimeStartAttempted) return
        nativeLifecycleGate.withLock {
            val firstStopFailure = runCatching { activeBinding.stop() }.exceptionOrNull()
            val secondStopFailure = if (activeBinding.isRunning()) {
                runCatching { activeBinding.stop() }.exceptionOrNull()
            } else {
                null
            }
            if (!activeBinding.isRunning()) {
                activeBinding.clearSocketProtector()
            } else {
                // Keep the protector attached to a runtime that may still own sockets. Propagating
                // this failure prevents a later VPN start from silently sharing leaked native state.
                throw IllegalStateException(
                    "Batch Xray runtime did not stop; restart the app before using Xray again",
                    firstStopFailure ?: secondStopFailure ?:
                        IllegalStateException("Xray still reports a running server"),
                )
            }
        }
    }

    private fun reserveLoopbackPort(): Int {
        return ServerSocket(0, 1, InetAddress.getByName(BATCH_LOOPBACK_HOST))
            .use(ServerSocket::getLocalPort)
    }

    @Suppress("DEPRECATION")
    private fun findPhysicalNetwork(): Network? {
        val manager = appContext.getSystemService(ConnectivityManager::class.java) ?: return null
        val candidates = manager.allNetworks.mapNotNull { network ->
            val capabilities = manager.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            ) {
                return@mapNotNull null
            }
            network to capabilities
        }
        val active = manager.activeNetwork
        return candidates.firstOrNull { (network, capabilities) ->
            network == active && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }?.first ?: candidates.firstOrNull { (_, capabilities) ->
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        }?.first ?: candidates.firstOrNull { (_, capabilities) ->
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }?.first ?: candidates.firstOrNull { (network, _) ->
            network == active
        }?.first ?: candidates.firstOrNull { (_, capabilities) ->
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        }?.first ?: candidates.firstOrNull()?.first
    }

    private fun bindSocketToNetwork(network: Network, fd: Int): Boolean {
        return runCatching {
            ParcelFileDescriptor.fromFd(fd).use { duplicate ->
                network.bindSocket(duplicate.fileDescriptor)
            }
            true
        }.getOrDefault(false)
    }

    private fun hasActiveRuntimeOwner(): Boolean = synchronized(runtimeStateLock) {
        activeRuntimeOwner != null
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
        private const val TEST_TIMEOUT_SECONDS = 2
        private const val TEST_URL = "https://www.youtube.com/generate_204"
    }
}

internal const val MAX_TUNNEL_TEST_LATENCY_MS = 2_000L
internal const val XRAY_RUNTIME_BUSY_MESSAGE =
    "Xray runtime is busy with an active VPN connection; disconnect it before checking tunnels"

internal class XrayRuntimeBusyException : IllegalStateException(XRAY_RUNTIME_BUSY_MESSAGE)

internal fun classifyTunnelTestLatency(
    profileId: String,
    latencyMs: Long,
): ProxyTunnelTestResult {
    return if (latencyMs < MAX_TUNNEL_TEST_LATENCY_MS) {
        ProxyTunnelTestResult(
            profileId = profileId,
            status = ProxyTestStatus.AVAILABLE,
            latencyMs = latencyMs,
        )
    } else {
        ProxyTunnelTestResult(
            profileId = profileId,
            status = ProxyTestStatus.UNAVAILABLE,
            message = "Tunnel response took ${latencyMs}ms; " +
                "limit is under ${MAX_TUNNEL_TEST_LATENCY_MS}ms",
        )
    }
}

internal fun effectiveTunnelTestLatency(nativeLatencyMs: Long, elapsedNanos: Long): Long {
    val nonNegativeNanos = elapsedNanos.coerceAtLeast(0L)
    val elapsedMillisCeiling = nonNegativeNanos / NANOS_PER_MILLISECOND +
        if (nonNegativeNanos % NANOS_PER_MILLISECOND == 0L) 0L else 1L
    return maxOf(nativeLatencyMs, elapsedMillisCeiling)
}

internal fun batchProbeConcurrency(
    total: Int,
    remainingMs: Long = XRAY_BATCH_TOTAL_BUDGET_MS,
): Int {
    if (total <= 0) return 1
    val targetProbeWindowMs = remainingMs.coerceAtMost(BATCH_TARGET_PROBE_WINDOW_MS)
    val availableWaves = (targetProbeWindowMs / BATCH_PROBE_TIMEOUT_MS)
        .coerceAtLeast(1L)
        .toInt()
    val requiredConcurrency = (total + availableWaves - 1) / availableWaves
    return requiredConcurrency.coerceIn(1, MAX_BATCH_PROBE_CONCURRENCY)
}

internal fun deviceAwareBatchProbeConcurrency(
    requested: Int,
    minimumForDeadline: Int = 1,
    isLowRamDevice: Boolean,
    isPowerSaveMode: Boolean,
): Int {
    require(requested > 0) { "Requested concurrency must be positive" }
    require(minimumForDeadline > 0) { "Deadline concurrency must be positive" }
    val deviceCap = when {
        isLowRamDevice -> LOW_RAM_BATCH_PROBE_CONCURRENCY
        isPowerSaveMode -> POWER_SAVE_BATCH_PROBE_CONCURRENCY
        else -> MAX_BATCH_PROBE_CONCURRENCY
    }
    return minOf(requested, maxOf(deviceCap, minimumForDeadline))
}

internal fun minimumBatchProbeConcurrencyForDeadline(total: Int, remainingMs: Long): Int {
    if (total <= 0) return 1
    val remainingWaves = (remainingMs / BATCH_PROBE_TIMEOUT_MS).coerceAtLeast(1L)
    val required = ((total + remainingWaves - 1L) / remainingWaves).toInt()
    return required.coerceIn(1, MAX_BATCH_PROBE_CONCURRENCY)
}

internal fun hasFullProbeBudget(
    deadlineNanos: Long,
    nowNanos: Long = System.nanoTime(),
): Boolean {
    return deadlineNanos - nowNanos >= BATCH_PROBE_TIMEOUT_MS * NANOS_PER_MILLISECOND
}

private fun deadlineNotTestedResult(profileId: String): ProxyTunnelTestResult {
    return ProxyTunnelTestResult(
        profileId = profileId,
        status = ProxyTestStatus.NOT_TESTED,
        message = "Batch check deadline reached before a complete probe",
    )
}

private suspend fun probeBatchTunnel(
    entry: XrayBatchSocksTestEntry,
    socksPort: Int,
    password: String,
    deadlineNanos: Long,
): ProxyTunnelTestResult {
    val remainingMillis = (deadlineNanos - System.nanoTime()) / NANOS_PER_MILLISECOND
    if (remainingMillis <= 0L) return deadlineNotTestedResult(entry.profile.id)
    val probeTimeoutMs = minOf(BATCH_PROBE_TIMEOUT_MS, remainingMillis)
    val hadFullProbeWindow = probeTimeoutMs >= BATCH_PROBE_TIMEOUT_MS
    val startedAtNanos = System.nanoTime()
    val succeeded = try {
        withTimeoutOrNull(probeTimeoutMs) {
            withClosingSocketOnCancellation { activeSocket ->
                executeSocks5TlsProbe(
                    activeSocket = activeSocket,
                    socksPort = socksPort,
                    username = entry.username,
                    password = password,
                    timeoutMs = probeTimeoutMs.toInt(),
                )
            }
        } ?: false
    } catch (error: CancellationException) {
        throw error
    } catch (error: XrayBatchProbeInfrastructureException) {
        return ProxyTunnelTestResult(
            profileId = entry.profile.id,
            status = ProxyTestStatus.NOT_TESTED,
            message = error.message ?: "Local batch probe infrastructure failed",
        )
    } catch (error: Exception) {
        return ProxyTunnelTestResult(
            profileId = entry.profile.id,
            status = if (hadFullProbeWindow) {
                ProxyTestStatus.UNAVAILABLE
            } else {
                // A partial slot cannot prove that the tunnel exceeded its two-second limit.
                ProxyTestStatus.NOT_TESTED
            },
            message = error.message ?: if (hadFullProbeWindow) {
                "Batch tunnel probe failed"
            } else {
                "Partial deadline probe did not finish"
            },
        )
    }
    val elapsedNanos = System.nanoTime() - startedAtNanos
    if (!succeeded) {
        return ProxyTunnelTestResult(
            profileId = entry.profile.id,
            status = if (hadFullProbeWindow) {
                ProxyTestStatus.UNAVAILABLE
            } else {
                ProxyTestStatus.NOT_TESTED
            },
            message = if (hadFullProbeWindow) {
                "Tunnel did not respond within ${BATCH_PROBE_TIMEOUT_MS}ms"
            } else {
                "Partial deadline probe did not finish in ${probeTimeoutMs}ms"
            },
        )
    }
    return classifyTunnelTestLatency(
        profileId = entry.profile.id,
        latencyMs = effectiveTunnelTestLatency(0L, elapsedNanos),
    )
}

private suspend fun <T> withClosingSocketOnCancellation(
    block: (AtomicReference<Socket?>) -> T,
): T = coroutineScope {
    val activeSocket = AtomicReference<Socket?>(null)
    val blockFinished = AtomicBoolean(false)
    val cancellationWatcher = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            if (!blockFinished.get()) {
                runCatching { activeSocket.getAndSet(null)?.close() }
            }
        }
    }
    try {
        try {
            block(activeSocket).also { currentCoroutineContext().ensureActive() }
        } catch (error: Throwable) {
            currentCoroutineContext().ensureActive()
            throw error
        }
    } finally {
        blockFinished.set(true)
        cancellationWatcher.cancel()
        runCatching { activeSocket.getAndSet(null)?.close() }
    }
}

private fun executeSocks5TlsProbe(
    activeSocket: AtomicReference<Socket?>,
    socksPort: Int,
    username: String,
    password: String,
    timeoutMs: Int,
): Boolean {
    val rawSocket = Socket()
    activeSocket.set(rawSocket)
    val (input, output) = try {
        rawSocket.tcpNoDelay = true
        rawSocket.soTimeout = timeoutMs
        rawSocket.connect(InetSocketAddress(BATCH_LOOPBACK_HOST, socksPort), timeoutMs)
        val input = rawSocket.getInputStream()
        val output = rawSocket.getOutputStream()
        output.write(SOCKS5_PASSWORD_GREETING)
        output.flush()
        readSocks5MethodSelection(input)
        output.write(buildSocks5UserPasswordRequest(username, password))
        output.flush()
        readSocks5PasswordAuthentication(input)
        input to output
    } catch (error: Exception) {
        throw XrayBatchProbeInfrastructureException(
            "Local batch SOCKS endpoint failed: ${error.message ?: error::class.java.simpleName}",
            error,
        )
    }

    output.write(buildSocks5ConnectRequest(BATCH_TEST_HOST, BATCH_TEST_PORT))
    output.flush()
    readSocks5ConnectReply(input)

    val sslSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
    val sslSocket = (sslSocketFactory.createSocket(
        rawSocket,
        BATCH_TEST_HOST,
        BATCH_TEST_PORT,
        true,
    ) as SSLSocket).apply {
        soTimeout = timeoutMs
        sslParameters = sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
    }
    activeSocket.set(sslSocket)
    sslSocket.startHandshake()
    sslSocket.outputStream.write(BATCH_HTTP_HEAD_REQUEST)
    sslSocket.outputStream.flush()
    return sslSocket.inputStream.readAsciiLine(MAX_HTTP_STATUS_LINE_BYTES).startsWith("HTTP/")
}

internal fun buildSocks5UserPasswordRequest(username: String, password: String): ByteArray {
    val usernameBytes = username.toByteArray(StandardCharsets.UTF_8)
    val passwordBytes = password.toByteArray(StandardCharsets.UTF_8)
    require(usernameBytes.size in 1..255) { "SOCKS username must be 1..255 bytes" }
    require(passwordBytes.size in 1..255) { "SOCKS password must be 1..255 bytes" }
    return ByteArray(3 + usernameBytes.size + passwordBytes.size).also { request ->
        var offset = 0
        request[offset++] = SOCKS5_AUTH_VERSION
        request[offset++] = usernameBytes.size.toByte()
        usernameBytes.copyInto(request, offset)
        offset += usernameBytes.size
        request[offset++] = passwordBytes.size.toByte()
        passwordBytes.copyInto(request, offset)
    }
}

internal fun buildSocks5ConnectRequest(host: String, port: Int): ByteArray {
    val hostBytes = host.toByteArray(StandardCharsets.US_ASCII)
    require(hostBytes.size in 1..255) { "SOCKS destination must be 1..255 bytes" }
    require(port in 1..65535) { "SOCKS destination port is invalid" }
    return ByteArray(7 + hostBytes.size).also { request ->
        request[0] = SOCKS5_VERSION
        request[1] = SOCKS5_CONNECT_COMMAND
        request[2] = 0
        request[3] = SOCKS5_DOMAIN_ADDRESS
        request[4] = hostBytes.size.toByte()
        hostBytes.copyInto(request, 5)
        request[5 + hostBytes.size] = (port ushr 8).toByte()
        request[6 + hostBytes.size] = port.toByte()
    }
}

internal fun readSocks5MethodSelection(input: InputStream) {
    val reply = input.readExact(2)
    check(reply[0] == SOCKS5_VERSION && reply[1] == SOCKS5_PASSWORD_METHOD) {
        "Batch SOCKS authentication method was rejected"
    }
}

internal fun readSocks5PasswordAuthentication(input: InputStream) {
    val reply = input.readExact(2)
    check(reply[0] == SOCKS5_AUTH_VERSION && reply[1] == 0.toByte()) {
        "Batch SOCKS credentials were rejected"
    }
}

internal fun readSocks5ConnectReply(input: InputStream) {
    val reply = input.readExact(4)
    check(reply[0] == SOCKS5_VERSION && reply[1] == 0.toByte()) {
        "Tunnel connection failed with SOCKS code ${reply[1].toInt() and 0xff}"
    }
    input.discardSocks5Address(reply[3])
    input.readExact(2) // bound port
}

private fun InputStream.readExact(size: Int): ByteArray {
    val result = ByteArray(size)
    var offset = 0
    while (offset < size) {
        val read = read(result, offset, size - offset)
        check(read >= 0) { "Unexpected end of SOCKS response" }
        offset += read
    }
    return result
}

private fun InputStream.discardSocks5Address(addressType: Byte) {
    when (addressType) {
        SOCKS5_IPV4_ADDRESS -> readExact(4)
        SOCKS5_DOMAIN_ADDRESS -> readExact(readExact(1)[0].toInt() and 0xff)
        SOCKS5_IPV6_ADDRESS -> readExact(16)
        else -> error("Unsupported SOCKS address type ${addressType.toInt() and 0xff}")
    }
}

private fun InputStream.readAsciiLine(maxBytes: Int): String {
    val bytes = ByteArray(maxBytes)
    var length = 0
    while (length < maxBytes) {
        val value = read()
        check(value >= 0) { "Unexpected end of HTTP response" }
        if (value == '\n'.code) break
        if (value != '\r'.code) bytes[length++] = value.toByte()
    }
    check(length < maxBytes) { "HTTP status line is too long" }
    return String(bytes, 0, length, StandardCharsets.US_ASCII)
}

internal suspend fun <T, R> mapBatchConcurrentOrdered(
    values: List<T>,
    maxConcurrency: Int,
    dispatcher: CoroutineDispatcher,
    onResult: suspend (R) -> Unit,
    transform: suspend (T) -> R,
): List<R> {
    require(maxConcurrency > 0) { "Concurrency must be positive" }
    if (values.isEmpty()) return emptyList()
    val nextIndex = AtomicInteger(0)
    val results = AtomicReferenceArray<XrayBatchMapResult<R>?>(values.size)
    coroutineScope {
        List(minOf(maxConcurrency, values.size)) {
            launch(dispatcher) {
                while (true) {
                    val index = nextIndex.getAndIncrement()
                    if (index >= values.size) break
                    val result = transform(values[index])
                    results.set(index, XrayBatchMapResult(result))
                    onResult(result)
                }
            }
        }.joinAll()
    }
    return List(values.size) { index ->
        checkNotNull(results.get(index)) { "Missing Xray batch result at index $index" }.value
    }
}

private data class XrayBatchMapResult<R>(val value: R)

private class XrayBatchProbeInfrastructureException(
    message: String,
    cause: Throwable,
) : IllegalStateException(message, cause)

private const val NANOS_PER_MILLISECOND = 1_000_000L
// Normal batches target ten seconds, but the larger hard budget lets every slow profile finish.
internal const val XRAY_BATCH_TOTAL_BUDGET_MS = 60_000L
internal const val XRAY_BATCH_TARGET_BUDGET_MS = 10_000L
private const val BATCH_PROBE_TIMEOUT_MS = 2_000L
private const val BATCH_RUNTIME_START_ALLOWANCE_MS = 2_000L
private const val BATCH_TARGET_PROBE_WINDOW_MS =
    XRAY_BATCH_TARGET_BUDGET_MS - BATCH_RUNTIME_START_ALLOWANCE_MS
private const val MAX_BATCH_PROBE_CONCURRENCY = 128
private const val POWER_SAVE_BATCH_PROBE_CONCURRENCY = 64
private const val LOW_RAM_BATCH_PROBE_CONCURRENCY = 32
private const val BATCH_TEST_HOST = "www.youtube.com"
private const val BATCH_TEST_PORT = 443
private const val BATCH_LOOPBACK_HOST = "127.0.0.1"
private const val MAX_HTTP_STATUS_LINE_BYTES = 256
private const val BATCH_PASSWORD_BYTES = 16
private const val MAX_BATCH_LOOPBACK_BIND_ATTEMPTS = 3
private const val MAX_BATCH_RUNTIME_START_ATTEMPTS = 20
private val SOCKS5_VERSION = 5.toByte()
private val SOCKS5_PASSWORD_METHOD = 2.toByte()
private val SOCKS5_AUTH_VERSION = 1.toByte()
private val SOCKS5_CONNECT_COMMAND = 1.toByte()
private val SOCKS5_IPV4_ADDRESS = 1.toByte()
private val SOCKS5_DOMAIN_ADDRESS = 3.toByte()
private val SOCKS5_IPV6_ADDRESS = 4.toByte()
private val SOCKS5_PASSWORD_GREETING = byteArrayOf(SOCKS5_VERSION, 1, SOCKS5_PASSWORD_METHOD)
private val BATCH_HTTP_HEAD_REQUEST = (
    "HEAD /generate_204 HTTP/1.1\r\n" +
        "Host: $BATCH_TEST_HOST\r\n" +
        "Connection: close\r\n\r\n"
    ).toByteArray(StandardCharsets.US_ASCII)

enum class XrayCoreInstallResult {
    INSTALLED,
    ALREADY_INSTALLED,
    INSTALLED_AFTER_RESTART,
}

internal class XraySocketProtectorDelegate {
    private val current = AtomicReference<((Int) -> Boolean)?>(null)

    fun update(protector: (Int) -> Boolean) {
        current.set(protector)
    }

    fun clear() {
        current.set(null)
    }

    fun protect(fd: Int): Boolean = current.get()?.invoke(fd) ?: false
}

internal class XrayNativeLifecycleGate {
    private val monitor = Any()

    fun <T> withLock(block: () -> T): T = synchronized(monitor, block)
}

internal class XrayBinding(private val apiClass: Class<*>) {
    private val dialerSocketProtector = XraySocketProtectorDelegate()
    private val listenerSocketProtector = XraySocketProtectorDelegate()
    private val socketProtectorRegistrationLock = Any()
    private val registeredSocketProtectorMethods = mutableSetOf<String>()
    private var socketProtectorRegistrationFailure: Throwable? = null
    private var dialerControllerProxy: Any? = null

    fun updateSocketProtector(protector: (Int) -> Boolean) {
        updateSocketProtectors(protector, protector)
    }

    fun updateSocketProtectors(
        dialerProtector: (Int) -> Boolean,
        listenerProtector: (Int) -> Boolean,
    ) {
        ensureSocketProtectorRegistered()
        dialerSocketProtector.update(dialerProtector)
        listenerSocketProtector.update(listenerProtector)
    }

    fun clearSocketProtector() {
        dialerSocketProtector.clear()
        listenerSocketProtector.clear()
    }

    private fun ensureSocketProtectorRegistered() = synchronized(socketProtectorRegistrationLock) {
        socketProtectorRegistrationFailure?.let { failure ->
            throw IllegalStateException("Xray socket protector registration previously failed", failure)
        }
        listOf("registerDialerController", "registerListenerController").forEach { methodName ->
            if (methodName in registeredSocketProtectorMethods) return@forEach
            val method = apiClass.methods.firstOrNull {
                it.name == methodName && it.parameterCount == 1
            } ?: return@forEach
            val controllerType = method.parameterTypes.single()
            val controller = Proxy.newProxyInstance(
                controllerType.classLoader,
                arrayOf(controllerType),
            ) { _, invokedMethod, arguments ->
                when (invokedMethod.name) {
                    "protectFd", "ProtectFd" -> {
                        val delegate = if (methodName == "registerDialerController") {
                            dialerSocketProtector
                        } else {
                            listenerSocketProtector
                        }
                        delegate.protect((arguments?.firstOrNull() as Number).toInt())
                    }
                    "toString" -> "shadow-ssh Xray socket protector"
                    else -> defaultValue(invokedMethod.returnType)
                }
            }
            try {
                method.invoke(null, controller)
                if (methodName == "registerDialerController") {
                    dialerControllerProxy = controller
                }
                registeredSocketProtectorMethods += methodName
            } catch (error: Throwable) {
                // Native controller registries append entries and do not expose unregister. Never
                // retry a possibly partial registration, which could leak duplicate callbacks.
                socketProtectorRegistrationFailure = error
                throw error
            }
        }
    }

    fun setTunFd(fd: Int) {
        invokeStatic("setTunFd", fd)
    }

    fun initDns(server: String) {
        require(server.isNotBlank()) { "Xray DNS server must not be blank" }
        ensureSocketProtectorRegistered()
        val method = apiClass.methods.firstOrNull {
            it.name == "initDns" && it.parameterCount == 2
        } ?: error("Xray binding does not expose Android DNS initialization")
        val controller = synchronized(socketProtectorRegistrationLock) {
            dialerControllerProxy
        } ?: error("Xray dialer controller is unavailable for DNS initialization")
        method.invoke(null, controller, server)
    }

    fun resetDns() {
        apiClass.methods.firstOrNull {
            it.name == "resetDns" && it.parameterCount == 0
        }?.invoke(null)
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
        invokeStatic("stopXray")
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
