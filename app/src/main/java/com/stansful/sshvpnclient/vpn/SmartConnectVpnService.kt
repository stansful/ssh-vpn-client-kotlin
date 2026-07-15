package com.stansful.sshvpnclient.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Network
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.stansful.sshvpnclient.SshVpnApplication
import com.stansful.sshvpnclient.domain.model.AppSettings
import com.stansful.sshvpnclient.domain.model.ProxyProfile
import com.stansful.sshvpnclient.domain.model.SmartConnectPhase
import com.stansful.sshvpnclient.domain.model.VpnConnectionStatus
import com.stansful.sshvpnclient.domain.model.VpnMode
import com.stansful.sshvpnclient.domain.model.VpnSessionOwner
import com.stansful.sshvpnclient.domain.model.VpnTransportType
import com.stansful.sshvpnclient.domain.repository.ProxySourceConnectionFactory
import com.stansful.sshvpnclient.xray.XrayCoreBridge
import com.stansful.sshvpnclient.xray.XrayLiveHealthHandle
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * User-started, self-healing Xray VPN backed by the isolated Smart Connect catalog.
 *
 * WorkManager is intentionally not involved: health monitoring belongs to this visible foreground
 * VPN lifecycle, while all waits are cancellable and event-driven.
 */
class SmartConnectVpnService : android.net.VpnService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()
    private val vpnTunnelOwner = Any()
    private val connectionRunId = AtomicLong(0L)
    private val lifecycleCommandId = AtomicLong(0L)
    private val networkRevision = AtomicLong(0L)
    private val settingsRevision = AtomicLong(0L)
    private val socketRoutingFailureGeneration = AtomicLong(NO_XRAY_GENERATION)
    private val socketRoutingFailures = GenerationFailureCounter()
    private val runtimeStoppedObservations = GenerationFailureCounter()
    private val workflowSignal = Channel<Unit>(Channel.CONFLATED)
    private val xrayTransportGeneration = XrayRuntimeGenerationTracker()
    private val trafficActivityMonitor = VpnTrafficActivityMonitor()

    @Volatile
    private var connectionJob: Job? = null
    @Volatile
    private var settingsJob: Job? = null
    @Volatile
    private var runtimeLease: VpnRuntimeLease? = null
    @Volatile
    private var transportNetwork: Network? = null
    @Volatile
    private var userRequestedStop = true
    @Volatile
    private var serviceDestroyed = false
    @Volatile
    private var deviceInteractive = true
    @Volatile
    private var lastStartId = 0
    @Volatile
    private var lastNotificationKey: String? = null

    private var receiverRegistered = false
    private lateinit var powerManager: PowerManager
    private lateinit var underlyingNetworkMonitor: UnderlyingNetworkMonitor

    private val deviceStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> deviceInteractive = false
                Intent.ACTION_SCREEN_ON -> deviceInteractive = true
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> Unit
                else -> return
            }
            workflowSignal.trySend(Unit)
        }
    }

    private val appContainer
        get() = (application as SshVpnApplication).container

    override fun onCreate() {
        super.onCreate()
        powerManager = getSystemService(POWER_SERVICE) as PowerManager
        deviceInteractive = powerManager.isInteractive
        underlyingNetworkMonitor = UnderlyingNetworkMonitor(
            context = this,
            onNetworkChanged = ::onUnderlyingNetworkChanged,
        )
        ContextCompat.registerReceiver(
            this,
            deviceStateReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
        observeRoutingSettings()
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when {
            intent?.action == ACTION_STOP -> requestStop(startId, "Smart Connect stopped")
            !appContainer.smartConnectStateStore.desiredActive -> {
                // ConnectSmartVpnUseCase persists the desired state before it starts us. A queued
                // or redelivered ACTION_START observed after ACTION_STOP is stale and must never
                // resurrect the VPN.
                userRequestedStop = true
                stopSelfResult(startId)
            }
            intent?.action == ACTION_START || intent == null -> {
                userRequestedStop = false
                val text = if (intent == null) "Restoring Smart Connect" else "Starting Smart Connect"
                startSmartForeground(NotificationPhase.STARTING, text, force = true)
                underlyingNetworkMonitor.start()
                startSession(startId)
            }
            else -> {
                // START_REDELIVER_INTENT normally preserves ACTION_START. This fallback also
                // restores state on platform/OEM variants that redeliver a null or altered intent.
                userRequestedStop = false
                startSmartForeground(NotificationPhase.STARTING, "Restoring Smart Connect", force = true)
                underlyingNetworkMonitor.start()
                startSession(startId)
            }
        }
        return if (appContainer.smartConnectStateStore.desiredActive && !userRequestedStop) {
            START_REDELIVER_INTENT
        } else {
            START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        serviceDestroyed = true
        appContainer.vpnRuntimeLeaseRegistry.invalidate(vpnTunnelOwner)
        lifecycleCommandId.incrementAndGet()
        connectionRunId.incrementAndGet()
        userRequestedStop = true
        connectionJob?.cancel()
        settingsJob?.cancel()
        stopOwnedXrayTransport()
        runCatching { appContainer.vpnTunnelManager.close(vpnTunnelOwner) }
        transportNetwork = null
        if (::underlyingNetworkMonitor.isInitialized) underlyingNetworkMonitor.close()
        if (receiverRegistered) {
            runCatching { unregisterReceiver(deviceStateReceiver) }
            receiverRegistered = false
        }
        if (appContainer.vpnConnectionRepository.currentState.sessionOwner == VpnSessionOwner.SMART_CONNECT) {
            appContainer.vpnConnectionRepository.setDisconnected()
        }
        if (appContainer.smartConnectStateStore.desiredActive) {
            appContainer.smartConnectStateStore.publish { state ->
                state.copy(
                    phase = SmartConnectPhase.STARTING,
                    message = "Waiting for Android to restore Smart Connect",
                )
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onRevoke() {
        appContainer.vpnConnectionRepository.appendDiagnostic("Smart Connect VPN permission revoked by Android")
        requestStop(lastStartId, "VPN permission revoked by Android")
        super.onRevoke()
    }

    private fun observeRoutingSettings() {
        settingsJob = serviceScope.launch {
            var previous = appContainer.appSettingsRepository.settings.value.smartRoutingSettings()
            appContainer.appSettingsRepository.settings.collect { settings ->
                val next = settings.smartRoutingSettings()
                if (next != previous) {
                    previous = next
                    settingsRevision.incrementAndGet()
                    stopOwnedXrayTransport()
                    workflowSignal.trySend(Unit)
                }
            }
        }
    }

    private fun startSession(startId: Int) {
        val runId = connectionRunId.incrementAndGet()
        val commandId = lifecycleCommandId.incrementAndGet()
        serviceScope.launch {
            lifecycleMutex.withLock {
                if (serviceDestroyed || lifecycleCommandId.get() != commandId) return@withLock
                val currentOwner = appContainer.vpnConnectionRepository.currentState.sessionOwner
                if (currentOwner != null && currentOwner != VpnSessionOwner.SMART_CONNECT) {
                    rejectStartForForeignOwner(runId, commandId, startId)
                    return@withLock
                }
                userRequestedStop = false
                val lease = appContainer.vpnRuntimeLeaseRegistry.claim(
                    owner = vpnTunnelOwner,
                    sessionOwner = VpnSessionOwner.SMART_CONNECT,
                )
                if (lease == null) {
                    rejectStartForBusyRuntime(runId, commandId, startId)
                    return@withLock
                }
                runtimeLease = lease
                stopOwnedXrayTransport()
                val previousJob = connectionJob
                previousJob?.cancel()
                previousJob?.let { job ->
                    withTimeoutOrNull(CONNECTION_JOB_JOIN_GRACE_MS) { job.join() }
                }
                cleanupOwnedVpnRuntime()
                if (!shouldKeepSessionAlive(runId) || lifecycleCommandId.get() != commandId) {
                    return@withLock
                }
                if (!rehydrateGlobalOwnerIfNeeded(runId)) {
                    appContainer.vpnRuntimeLeaseRegistry.invalidate(vpnTunnelOwner)
                    rejectStartForForeignOwner(runId, commandId, startId)
                    return@withLock
                }
                connectionJob = serviceScope.launch {
                    try {
                        runSession(runId, commandId, startId, lease)
                    } catch (error: CancellationException) {
                        // The serialized stop/restart transition owns cleanup.
                    } catch (error: Exception) {
                        if (shouldKeepSessionAlive(runId)) {
                            failAndStop(
                                runId = runId,
                                commandId = commandId,
                                startId = startId,
                                message = error.safeMessage(),
                            )
                        }
                    } finally {
                        if (connectionRunId.get() == runId) connectionJob = null
                    }
                }
            }
        }
    }

    private suspend fun runSession(
        runId: Long,
        commandId: Long,
        startId: Int,
        lease: VpnRuntimeLease,
    ) {
        if (!appContainer.xrayCoreBridge.isAvailable) {
            failAndStop(runId, commandId, startId, XrayCoreBridge.CORE_UNAVAILABLE_MESSAGE)
            return
        }

        var activeProfile: ProxyProfile? = null
        val profileCooldowns = SmartProfileCooldowns(SystemClock::elapsedRealtime)
        var catalogRetryAttempt = 0
        var consecutiveRuntimeFailures = 0
        var everVerified = false

        while (shouldKeepSessionAlive(runId)) {
            val physicalNetwork = awaitPhysicalNetwork(runId)
            if (!shouldKeepSessionAlive(runId)) break
            val expectedNetworkRevision = networkRevision.get()
            val settings = appContainer.appSettingsRepository.settings.value
            val expectedRoutingSettings = settings.smartRoutingSettings()
            val expectedSettingsRevision = settingsRevision.get()
            if (!settings.hasValidSmartRouting()) {
                failAndStop(runId, commandId, startId, "No apps selected")
                return
            }

            if (activeProfile == null) {
                cleanupOwnedVpnRuntime()
                startSmartForeground(NotificationPhase.PREPARING, "Preparing Smart Connect tunnels")
                activeProfile = try {
                    appContainer.smartConnectCatalogManager.refreshCheckPruneAndSelect(
                        connectionFactory = ProxySourceConnectionFactory { url ->
                            physicalNetwork.openConnection(url)
                        },
                        excludedFingerprints = profileCooldowns.activeFingerprints(),
                        preferredPhysicalNetwork = physicalNetwork,
                        workflowIsCurrent = {
                            shouldKeepSessionAlive(runId) &&
                                underlyingNetworkMonitor.currentNetwork() == physicalNetwork &&
                                networkRevision.get() == expectedNetworkRevision &&
                                routingSettingsAreCurrent(
                                    expectedRoutingSettings,
                                    expectedSettingsRevision,
                                )
                        },
                    ).also {
                        catalogRetryAttempt = 0
                        consecutiveRuntimeFailures = 0
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    val exponentialRetryDelayMs = smartCatalogRetryDelayMs(catalogRetryAttempt++)
                    val retryDelayMs = profileCooldowns.remainingUntilNextExpiryMs()
                        ?.let { cooldownRemainingMs ->
                            minOf(exponentialRetryDelayMs, cooldownRemainingMs.coerceAtLeast(1L))
                        }
                        ?: exponentialRetryDelayMs
                    appContainer.vpnConnectionRepository.appendDiagnostic(
                        "Smart Connect catalog unavailable: ${error.safeMessage()}; " +
                            "retrying in ${retryDelayMs}ms",
                    )
                    appContainer.smartConnectStateStore.publish { state ->
                        state.copy(
                            phase = SmartConnectPhase.RETRY_WAIT,
                            retryDelayMs = retryDelayMs,
                            message = "No verified tunnel; retrying in ${retryDelayMs / 1_000L}s",
                        )
                    }
                    startSmartForeground(
                        NotificationPhase.RETRY,
                        "No verified tunnel; retrying in ${retryDelayMs / 1_000L}s",
                    )
                    drainWorkflowSignals()
                    if (underlyingNetworkMonitor.currentNetwork() != physicalNetwork ||
                        !routingSettingsAreCurrent(
                            expected = expectedRoutingSettings,
                            expectedRevision = expectedSettingsRevision,
                        )
                    ) {
                        // Do not sleep through a handoff/settings event whose conflated wake-up
                        // was already queued when stale signals were drained.
                        continue
                    }
                    withTimeoutOrNull(retryDelayMs) { workflowSignal.receive() }
                    continue
                }
                if (underlyingNetworkMonitor.currentNetwork() != physicalNetwork ||
                    networkRevision.get() != expectedNetworkRevision
                ) {
                    // Catalog results remain useful, but the actual tunnel must bind the new
                    // physical network. Do not mark the selected profile unavailable.
                    continue
                }
                if (!routingSettingsAreCurrent(expectedRoutingSettings, expectedSettingsRevision)) {
                    continue
                }
            }

            val profile: ProxyProfile = activeProfile
            if (!publishConnectingState(runId, profile, reconnecting = everVerified)) break
            appContainer.smartConnectStateStore.publish { state ->
                state.copy(
                    phase = SmartConnectPhase.CONNECTING,
                    activeProfileId = profile.id,
                    activeProfileName = profile.name,
                    activeProfileLatencyMs = profile.lastLatencyMs,
                    retryDelayMs = null,
                    message = "Connecting to ${profile.name}",
                )
            }
            startSmartForeground(NotificationPhase.CONNECTING, "Connecting to ${profile.name}")

            val outcome = try {
                connectAndMonitor(
                    profile = profile,
                    settings = settings,
                    physicalNetwork = physicalNetwork,
                    expectedNetworkRevision = expectedNetworkRevision,
                    expectedRoutingSettings = expectedRoutingSettings,
                    expectedSettingsRevision = expectedSettingsRevision,
                    runId = runId,
                    commandId = commandId,
                    lease = lease,
                    onVerified = {
                        everVerified = true
                        consecutiveRuntimeFailures = 0
                        catalogRetryAttempt = 0
                    },
                )
            } catch (error: CancellationException) {
                currentCoroutineContext().ensureActive()
                when {
                    networkRevision.get() != expectedNetworkRevision -> ConnectionOutcome.NetworkChanged
                    !routingSettingsAreCurrent(
                        expectedRoutingSettings,
                        expectedSettingsRevision,
                    ) -> ConnectionOutcome.SettingsChanged
                    else -> throw error
                }
            } catch (error: Exception) {
                ConnectionOutcome.RuntimeFailure(error.safeMessage())
            }

            // A NetworkCallback or routing-settings update can land after the confirming health
            // probe returned but before this coroutine handles its result. Reclassify that narrow
            // race as a structural restart so it can never poison the profile health record.
            val guardedOutcome = if (outcome is ConnectionOutcome.ConfirmedHealthFailure) {
                connectionContextOutcome(
                    runId = runId,
                    physicalNetwork = physicalNetwork,
                    expectedNetworkRevision = expectedNetworkRevision,
                    expectedRoutingSettings = expectedRoutingSettings,
                    expectedSettingsRevision = expectedSettingsRevision,
                ) ?: outcome
            } else {
                outcome
            }

            when (guardedOutcome) {
                ConnectionOutcome.Stopped -> break
                ConnectionOutcome.NetworkChanged -> {
                    cleanupOwnedVpnRuntime()
                    consecutiveRuntimeFailures = 0
                    appContainer.vpnConnectionRepository.appendDiagnostic(
                        "Smart Connect physical network changed; restarting the current tunnel",
                    )
                }
                ConnectionOutcome.SettingsChanged -> {
                    cleanupOwnedVpnRuntime()
                    consecutiveRuntimeFailures = 0
                    appContainer.vpnConnectionRepository.appendDiagnostic(
                        "Smart Connect VPN routing settings changed; rebuilding the current tunnel",
                    )
                }
                is ConnectionOutcome.ConfirmedHealthFailure -> {
                    // Keep this guard immediately adjacent to the durable mutation. The first
                    // post-probe guard above closes the common race; this second one also covers
                    // a handoff/settings event delivered while the outcome was being dispatched.
                    val contextOutcome = connectionContextOutcome(
                        runId = runId,
                        physicalNetwork = physicalNetwork,
                        expectedNetworkRevision = expectedNetworkRevision,
                        expectedRoutingSettings = expectedRoutingSettings,
                        expectedSettingsRevision = expectedSettingsRevision,
                    )
                    if (contextOutcome != null) {
                        when (contextOutcome) {
                            ConnectionOutcome.Stopped -> return
                            ConnectionOutcome.NetworkChanged -> {
                                cleanupOwnedVpnRuntime()
                                consecutiveRuntimeFailures = 0
                                appContainer.vpnConnectionRepository.appendDiagnostic(
                                    "Smart Connect physical network changed; " +
                                        "discarding a stale health failure",
                                )
                            }
                            ConnectionOutcome.SettingsChanged -> {
                                cleanupOwnedVpnRuntime()
                                consecutiveRuntimeFailures = 0
                                appContainer.vpnConnectionRepository.appendDiagnostic(
                                    "Smart Connect VPN routing settings changed; " +
                                        "discarding a stale health failure",
                                )
                            }
                            is ConnectionOutcome.ConfirmedHealthFailure ->
                                error("Unexpected health-failure context outcome")
                            is ConnectionOutcome.RuntimeFailure ->
                                error("Unexpected runtime-failure context outcome")
                        }
                        continue
                    }
                    // Persist the failure before teardown so the following full catalog pass can
                    // prune it even if Android kills the service during recovery.
                    appContainer.smartConnectCatalogManager.markUnavailable(
                        profile,
                        guardedOutcome.message,
                    )
                    profileCooldowns.exclude(
                        profile.fingerprint,
                        SMART_HEALTH_FAILURE_COOLDOWN_MS,
                    )
                    appContainer.smartConnectStateStore.publish { state ->
                        state.copy(
                            phase = SmartConnectPhase.FAILING_OVER,
                            message = "Tunnel failed; selecting a replacement",
                        )
                    }
                    publishReconnectingState(runId, profile)
                    startSmartForeground(NotificationPhase.PREPARING, "Selecting a replacement tunnel")
                    cleanupOwnedVpnRuntime()
                    activeProfile = null
                    consecutiveRuntimeFailures = 0
                }
                is ConnectionOutcome.RuntimeFailure -> {
                    cleanupOwnedVpnRuntime()
                    consecutiveRuntimeFailures += 1
                    appContainer.vpnConnectionRepository.appendDiagnostic(
                        "Smart Connect runtime failure: ${guardedOutcome.message}",
                    )
                    if (consecutiveRuntimeFailures >= MAX_SAME_PROFILE_RUNTIME_FAILURES) {
                        // DNS/link properties, TUN establishment, socket binding and native-core
                        // startup are client infrastructure. They must never poison/delete a
                        // profile. Exclude it only from this in-memory attempt; the next batch is
                        // responsible for making a profile-attributable test decision.
                        profileCooldowns.exclude(
                            profile.fingerprint,
                            SMART_RUNTIME_FAILURE_COOLDOWN_MS,
                        )
                        activeProfile = null
                        consecutiveRuntimeFailures = 0
                    } else {
                        delay(RUNTIME_RESTART_DELAY_MS)
                    }
                }
            }
        }
    }

    private suspend fun connectAndMonitor(
        profile: ProxyProfile,
        settings: AppSettings,
        physicalNetwork: Network,
        expectedNetworkRevision: Long,
        expectedRoutingSettings: SmartRoutingSettings,
        expectedSettingsRevision: Long,
        runId: Long,
        commandId: Long,
        lease: VpnRuntimeLease,
        onVerified: () -> Unit,
    ): ConnectionOutcome {
        if (!lease.isCurrent()) throw CancellationException("VPN runtime lease was superseded")
        if (!routingSettingsAreCurrent(expectedRoutingSettings, expectedSettingsRevision)) {
            return ConnectionOutcome.SettingsChanged
        }
        transportNetwork = physicalNetwork
        val dnsServer = underlyingNetworkMonitor.dnsEndpointFor(physicalNetwork)
            ?: throw VpnConnectionException("No DNS server is available on the physical network")
        val vpnInterface = appContainer.vpnTunnelManager.establish(
            owner = vpnTunnelOwner,
            lease = lease,
            service = this,
            sessionName = VPN_SESSION_NAME,
            appSettings = settings,
            mode = VpnTunnelMode.XRAY,
            underlyingNetwork = physicalNetwork,
            log = appContainer.vpnConnectionRepository::appendDiagnostic,
        )
        val attemptGeneration = AtomicLong(NO_XRAY_GENERATION)
        val healthEndpoint = appContainer.xrayCoreBridge.createLiveHealthEndpoint()
        val healthHandle = appContainer.xrayCoreBridge.startTun(
            owner = vpnTunnelOwner,
            lease = lease,
            profile = profile,
            tunFd = vpnInterface.fd,
            dnsServer = dnsServer,
            liveHealthEndpoint = healthEndpoint,
            protectSocket = { fd ->
                bindAndProtectXraySocket(
                    fd = fd,
                    runId = runId,
                    generation = attemptGeneration.get(),
                )
            },
            protectListenerSocket = ::protect,
            onGenerationReserved = { generation ->
                attemptGeneration.set(generation)
                socketRoutingFailureGeneration.set(NO_XRAY_GENERATION)
                socketRoutingFailures.resetForGeneration(generation)
                runtimeStoppedObservations.resetForGeneration(generation)
                xrayTransportGeneration.publish(generation)
            },
        )
        val structuralOutcome = structuralOutcome(
            runId = runId,
            physicalNetwork = physicalNetwork,
            expectedNetworkRevision = expectedNetworkRevision,
            expectedRoutingSettings = expectedRoutingSettings,
            expectedSettingsRevision = expectedSettingsRevision,
            generation = healthHandle.generation,
        )
        if (structuralOutcome != null) return structuralOutcome

        appContainer.smartConnectStateStore.publish { state ->
            state.copy(
                phase = SmartConnectPhase.VERIFYING,
                activeProfileId = profile.id,
                activeProfileName = profile.name,
                activeProfileLatencyMs = profile.lastLatencyMs,
                message = "Verifying ${profile.name}",
            )
        }
        trafficActivityMonitor.resetBaseline()
        return monitorLiveTunnel(
            profile = profile,
            healthHandle = healthHandle,
            physicalNetwork = physicalNetwork,
            expectedNetworkRevision = expectedNetworkRevision,
            expectedRoutingSettings = expectedRoutingSettings,
            expectedSettingsRevision = expectedSettingsRevision,
            runId = runId,
            commandId = commandId,
            onVerified = onVerified,
        )
    }

    private suspend fun monitorLiveTunnel(
        profile: ProxyProfile,
        healthHandle: XrayLiveHealthHandle,
        physicalNetwork: Network,
        expectedNetworkRevision: Long,
        expectedRoutingSettings: SmartRoutingSettings,
        expectedSettingsRevision: Long,
        runId: Long,
        commandId: Long,
        onVerified: () -> Unit,
    ): ConnectionOutcome {
        val connectedAtMs = SystemClock.elapsedRealtime()
        var nextProbeAtMs = connectedAtMs
        var healthPublished = false
        var disruptionDeferralStartedAtMs = NO_TIMESTAMP
        var activeTransferDeferralLogged = false
        var confirmedHealthFailureRounds = 0
        var firstConfirmedHealthFailureAtMs = NO_TIMESTAMP

        while (shouldKeepSessionAlive(runId)) {
            structuralOutcome(
                runId = runId,
                physicalNetwork = physicalNetwork,
                expectedNetworkRevision = expectedNetworkRevision,
                expectedRoutingSettings = expectedRoutingSettings,
                expectedSettingsRevision = expectedSettingsRevision,
                generation = healthHandle.generation,
            )?.let { return it }

            val nowMs = SystemClock.elapsedRealtime()
            val waitMs = (nextProbeAtMs - nowMs).coerceAtLeast(0L)
            if (waitMs > 0L) {
                withTimeoutOrNull(waitMs) { workflowSignal.receive() }
                continue
            }

            // A probe is an extra connection through the same public account. Some free servers
            // enforce one active stream and can evict a browser download merely because this
            // auxiliary YouTube connection was opened. Sample before probing and postpone the
            // probe while user traffic itself proves that the tunnel is alive.
            val trafficBeforeProbe = trafficActivityMonitor.sampleSinceLast()
            if (healthPublished) {
                val trafficIsPotentiallyActive = trafficBeforeProbe.receivedRecently ||
                    trafficBeforeProbe.receivedBytes >= ACTIVE_VPN_TRAFFIC_THRESHOLD_BYTES ||
                    trafficBeforeProbe.transmittedBytes >= ACTIVE_VPN_TRAFFIC_THRESHOLD_BYTES
                if (trafficIsPotentiallyActive && disruptionDeferralStartedAtMs == NO_TIMESTAMP) {
                    disruptionDeferralStartedAtMs = nowMs
                } else if (!trafficIsPotentiallyActive) {
                    disruptionDeferralStartedAtMs = NO_TIMESTAMP
                }
                val activeTransferElapsedMs = if (disruptionDeferralStartedAtMs == NO_TIMESTAMP) {
                    maxOf(MAX_TX_ONLY_TRAFFIC_CHECK_DEFERRAL_MS, MAX_DEVICE_ONLY_RX_DEFERRAL_MS)
                } else {
                    nowMs - disruptionDeferralStartedAtMs
                }
                if (shouldDeferVpnDisruption(trafficBeforeProbe, activeTransferElapsedMs)) {
                    confirmedHealthFailureRounds = 0
                    firstConfirmedHealthFailureAtMs = NO_TIMESTAMP
                    if (!activeTransferDeferralLogged) {
                        activeTransferDeferralLogged = true
                        appContainer.vpnConnectionRepository.appendDiagnostic(
                            "Smart Connect health probe postponed for active transfer " +
                                "(rx=${trafficBeforeProbe.receivedBytes / 1_024L} KiB, " +
                                "uidRx=${trafficBeforeProbe.uidReceivedBytes / 1_024L} KiB)",
                        )
                    }
                    nextProbeAtMs = nowMs + smartHealthCheckIntervalMs(
                        connectedDurationMs = nowMs - connectedAtMs,
                        isInteractive = deviceInteractive,
                        isPowerSaveMode = powerManager.isPowerSaveMode,
                    )
                    continue
                }
            }
            activeTransferDeferralLogged = false

            val firstProbe = try {
                appContainer.xrayCoreBridge.probeLiveTunnel(vpnTunnelOwner, healthHandle)
            } catch (error: CancellationException) {
                currentCoroutineContext().ensureActive()
                return structuralOutcome(
                    runId,
                    physicalNetwork,
                    expectedNetworkRevision,
                    expectedRoutingSettings,
                    expectedSettingsRevision,
                    healthHandle.generation,
                ) ?: ConnectionOutcome.RuntimeFailure("Xray health runtime was superseded")
            }

            if (firstProbe.isHealthy) {
                confirmedHealthFailureRounds = 0
                firstConfirmedHealthFailureAtMs = NO_TIMESTAMP
                if (!healthPublished) {
                    if (!publishConnectedState(runId, commandId, profile)) return ConnectionOutcome.Stopped
                    onVerified()
                    healthPublished = true
                    startSmartForeground(NotificationPhase.CONNECTED, "Connected via ${profile.name}")
                }
                publishHealthySmartState(profile, firstProbe.latencyMs)
                trafficActivityMonitor.sampleSinceLast()
                disruptionDeferralStartedAtMs = NO_TIMESTAMP
                nextProbeAtMs = SystemClock.elapsedRealtime() + smartHealthCheckIntervalMs(
                    connectedDurationMs = SystemClock.elapsedRealtime() - connectedAtMs,
                    isInteractive = deviceInteractive,
                    isPowerSaveMode = powerManager.isPowerSaveMode,
                )
                continue
            }

            delay(SMART_HEALTH_FAILURE_CONFIRM_DELAY_MS)
            structuralOutcome(
                runId = runId,
                physicalNetwork = physicalNetwork,
                expectedNetworkRevision = expectedNetworkRevision,
                expectedRoutingSettings = expectedRoutingSettings,
                expectedSettingsRevision = expectedSettingsRevision,
                generation = healthHandle.generation,
            )?.let { return it }
            val confirmation = try {
                appContainer.xrayCoreBridge.probeLiveTunnel(vpnTunnelOwner, healthHandle)
            } catch (error: CancellationException) {
                currentCoroutineContext().ensureActive()
                return structuralOutcome(
                    runId,
                    physicalNetwork,
                    expectedNetworkRevision,
                    expectedRoutingSettings,
                    expectedSettingsRevision,
                    healthHandle.generation,
                ) ?: ConnectionOutcome.RuntimeFailure("Xray health runtime was superseded")
            }
            if (confirmation.isHealthy) {
                confirmedHealthFailureRounds = 0
                firstConfirmedHealthFailureAtMs = NO_TIMESTAMP
                if (!healthPublished) {
                    if (!publishConnectedState(runId, commandId, profile)) return ConnectionOutcome.Stopped
                    onVerified()
                    healthPublished = true
                    startSmartForeground(NotificationPhase.CONNECTED, "Connected via ${profile.name}")
                }
                publishHealthySmartState(profile, confirmation.latencyMs)
                trafficActivityMonitor.sampleSinceLast()
                disruptionDeferralStartedAtMs = NO_TIMESTAMP
                nextProbeAtMs = SystemClock.elapsedRealtime() + smartHealthCheckIntervalMs(
                    connectedDurationMs = SystemClock.elapsedRealtime() - connectedAtMs,
                    isInteractive = deviceInteractive,
                    isPowerSaveMode = powerManager.isPowerSaveMode,
                )
                continue
            }

            structuralOutcome(
                runId = runId,
                physicalNetwork = physicalNetwork,
                expectedNetworkRevision = expectedNetworkRevision,
                expectedRoutingSettings = expectedRoutingSettings,
                expectedSettingsRevision = expectedSettingsRevision,
                generation = healthHandle.generation,
            )?.let { return it }

            val traffic = trafficActivityMonitor.sampleSinceLast()
            val failureAtMs = SystemClock.elapsedRealtime()
            val trafficIsPotentiallyActive = traffic.receivedRecently ||
                traffic.receivedBytes >= ACTIVE_VPN_TRAFFIC_THRESHOLD_BYTES ||
                traffic.transmittedBytes >= ACTIVE_VPN_TRAFFIC_THRESHOLD_BYTES
            if (trafficIsPotentiallyActive && disruptionDeferralStartedAtMs == NO_TIMESTAMP) {
                disruptionDeferralStartedAtMs = failureAtMs
            } else if (!trafficIsPotentiallyActive) {
                disruptionDeferralStartedAtMs = NO_TIMESTAMP
            }
            val deferralElapsedMs = if (disruptionDeferralStartedAtMs == NO_TIMESTAMP) {
                maxOf(MAX_TX_ONLY_TRAFFIC_CHECK_DEFERRAL_MS, MAX_DEVICE_ONLY_RX_DEFERRAL_MS)
            } else {
                failureAtMs - disruptionDeferralStartedAtMs
            }
            if (healthPublished && shouldDeferVpnDisruption(traffic, deferralElapsedMs)) {
                confirmedHealthFailureRounds = 0
                firstConfirmedHealthFailureAtMs = NO_TIMESTAMP
                appContainer.smartConnectStateStore.publish { state ->
                    state.copy(
                        phase = if (healthPublished) SmartConnectPhase.CONNECTED else SmartConnectPhase.VERIFYING,
                        message = "Health retry deferred while VPN traffic is active",
                    )
                }
                nextProbeAtMs = failureAtMs + smartHealthCheckIntervalMs(
                    connectedDurationMs = failureAtMs - connectedAtMs,
                    isInteractive = deviceInteractive,
                    isPowerSaveMode = powerManager.isPowerSaveMode,
                )
                continue
            }

            val message = confirmation.message ?: firstProbe.message ?: "YouTube health check failed"
            if (healthPublished) {
                if (firstConfirmedHealthFailureAtMs == NO_TIMESTAMP) {
                    firstConfirmedHealthFailureAtMs = failureAtMs
                }
                confirmedHealthFailureRounds += 1
                val failureDurationMs = failureAtMs - firstConfirmedHealthFailureAtMs
                if (!shouldTriggerVerifiedTunnelFailover(
                        confirmedFailureRounds = confirmedHealthFailureRounds,
                        elapsedSinceFirstConfirmedFailureMs = failureDurationMs,
                    )
                ) {
                    appContainer.vpnConnectionRepository.appendDiagnostic(
                        "Smart Connect auxiliary health failure " +
                            "$confirmedHealthFailureRounds/" +
                            "$SMART_HEALTH_FAILURE_ROUNDS_BEFORE_FAILOVER; keeping verified tunnel",
                    )
                    // Once a verified tunnel starts failing, confirm recovery/failure promptly;
                    // the long screen-off/Battery Saver cadence is for healthy idle tunnels.
                    nextProbeAtMs = failureAtMs + SMART_HEALTH_FAILURE_RETRY_INTERVAL_MS
                    continue
                }
            }
            return ConnectionOutcome.ConfirmedHealthFailure(message)
        }
        return ConnectionOutcome.Stopped
    }

    private fun structuralOutcome(
        runId: Long,
        physicalNetwork: Network,
        expectedNetworkRevision: Long,
        expectedRoutingSettings: SmartRoutingSettings,
        expectedSettingsRevision: Long,
        generation: Long,
    ): ConnectionOutcome? {
        connectionContextOutcome(
            runId = runId,
            physicalNetwork = physicalNetwork,
            expectedNetworkRevision = expectedNetworkRevision,
            expectedRoutingSettings = expectedRoutingSettings,
            expectedSettingsRevision = expectedSettingsRevision,
        )?.let { return it }
        if (socketRoutingFailureGeneration.get() == generation) {
            return ConnectionOutcome.RuntimeFailure("Xray physical socket routing failed")
        }
        if (!appContainer.xrayCoreBridge.isRuntimeGenerationCurrent(vpnTunnelOwner, generation)) {
            return ConnectionOutcome.RuntimeFailure("Xray core stopped unexpectedly")
        }
        if (appContainer.xrayCoreBridge.isRunning()) {
            runtimeStoppedObservations.recordSuccess(generation)
        } else {
            val progress = runtimeStoppedObservations.recordFailure(
                generation,
                SystemClock.elapsedRealtime(),
            )
            if (progress != null &&
                progress.count >= XRAY_STOPPED_OBSERVATIONS_BEFORE_REBUILD &&
                progress.elapsedMs >= XRAY_STOPPED_MIN_DURATION_MS
            ) {
                return ConnectionOutcome.RuntimeFailure(
                    "Xray core reported stopped repeatedly",
                )
            }
        }
        return null
    }

    private fun connectionContextOutcome(
        runId: Long,
        physicalNetwork: Network,
        expectedNetworkRevision: Long,
        expectedRoutingSettings: SmartRoutingSettings,
        expectedSettingsRevision: Long,
    ): ConnectionOutcome? {
        if (!shouldKeepSessionAlive(runId)) return ConnectionOutcome.Stopped
        if (networkRevision.get() != expectedNetworkRevision ||
            underlyingNetworkMonitor.currentNetwork() != physicalNetwork
        ) {
            return ConnectionOutcome.NetworkChanged
        }
        if (!routingSettingsAreCurrent(expectedRoutingSettings, expectedSettingsRevision)) {
            return ConnectionOutcome.SettingsChanged
        }
        return null
    }

    private fun routingSettingsAreCurrent(
        expected: SmartRoutingSettings,
        expectedRevision: Long,
    ): Boolean {
        return settingsRevision.get() == expectedRevision &&
            appContainer.appSettingsRepository.settings.value.smartRoutingSettings() == expected
    }

    private suspend fun awaitPhysicalNetwork(runId: Long): Network {
        if (underlyingNetworkMonitor.currentNetwork() == null) {
            appContainer.smartConnectStateStore.publish { state ->
                state.copy(
                    phase = SmartConnectPhase.WAITING_FOR_NETWORK,
                    retryDelayMs = null,
                    message = "Waiting for a physical network",
                )
            }
            startSmartForeground(NotificationPhase.WAITING, "Waiting for a physical network")
        }
        val network = underlyingNetworkMonitor.awaitUsableNetwork()
        if (!shouldKeepSessionAlive(runId)) throw CancellationException("Smart Connect stopped")
        return network
    }

    private fun onUnderlyingNetworkChanged(old: Network?, new: Network?) {
        val runId = connectionRunId.get()
        serviceScope.launch {
            if (underlyingNetworkMonitor.currentNetwork() != new || !shouldKeepSessionAlive(runId)) {
                return@launch
            }
            appContainer.vpnTunnelManager.updateUnderlyingNetwork(
                owner = vpnTunnelOwner,
                service = this@SmartConnectVpnService,
                network = new,
            )
            if (underlyingNetworkMonitor.currentNetwork() != new || !shouldKeepSessionAlive(runId)) {
                return@launch
            }
            // UnderlyingNetworkMonitor suppresses its initial discovery callback, so every event
            // reaching here is a real handoff/loss. Invalidate catalog work too, even before a
            // live transport exists, so results from the old Network cannot be committed.
            networkRevision.incrementAndGet()
            if (!shouldRestartForNetworkChange(transportNetwork, new)) {
                workflowSignal.trySend(Unit)
                return@launch
            }
            appContainer.vpnConnectionRepository.appendDiagnostic(
                "Smart Connect physical network changed (${old ?: "none"} -> ${new ?: "none"})",
            )
            stopOwnedXrayTransport()
            workflowSignal.trySend(Unit)
        }
    }

    private fun bindAndProtectXraySocket(fd: Int, runId: Long, generation: Long): Boolean {
        if (!shouldKeepSessionAlive(runId)) return false
        val protected = runCatching { protect(fd) }.getOrDefault(false)
        if (!protected) {
            reportSocketRoutingFailure(generation, "could not protect socket from VPN routing")
            return false
        }
        val network = underlyingNetworkMonitor.currentNetwork()
        if (network == null) {
            reportSocketRoutingFailure(generation, "physical network disappeared")
            return true
        }
        return try {
            ParcelFileDescriptor.fromFd(fd).use { duplicate ->
                network.bindSocket(duplicate.fileDescriptor)
            }
            socketRoutingFailures.recordSuccess(generation)
            true
        } catch (error: Exception) {
            appContainer.vpnConnectionRepository.appendDiagnostic(
                "Smart Connect socket was protected but explicit bind failed; " +
                    "using Android VPN underlying network: ${error.safeMessage()}",
            )
            socketRoutingFailures.recordSuccess(generation)
            true
        }
    }

    private fun reportSocketRoutingFailure(generation: Long, reason: String) {
        if (generation == NO_XRAY_GENERATION || xrayTransportGeneration.snapshot() != generation) return
        val progress = socketRoutingFailures.recordFailure(
            generation,
            SystemClock.elapsedRealtime(),
        ) ?: return
        appContainer.vpnConnectionRepository.appendDiagnostic(
            "Smart Connect socket routing failure ${progress.count}/" +
                "$SOCKET_ROUTING_FAILURES_BEFORE_REBUILD " +
                "(${progress.elapsedMs}ms): $reason",
        )
        if (progress.count < SOCKET_ROUTING_FAILURES_BEFORE_REBUILD ||
            progress.elapsedMs < SOCKET_ROUTING_FAILURE_MIN_DURATION_MS
        ) {
            return
        }
        if (!socketRoutingFailureGeneration.compareAndSet(NO_XRAY_GENERATION, generation)) return
        workflowSignal.trySend(Unit)
    }

    private fun requestStop(startId: Int, message: String) {
        appContainer.smartConnectStateStore.stop(message)
        appContainer.vpnRuntimeLeaseRegistry.invalidate(vpnTunnelOwner)
        val commandId = lifecycleCommandId.incrementAndGet()
        userRequestedStop = true
        transportNetwork = null
        connectionJob?.cancel()
        serviceScope.launch {
            lifecycleMutex.withLock {
                if (serviceDestroyed || lifecycleCommandId.get() != commandId) return@withLock
                val cleanupRunId = connectionRunId.incrementAndGet()
                stopOwnedXrayTransport()
                val previousJob = connectionJob
                previousJob?.cancel()
                previousJob?.let { job ->
                    withTimeoutOrNull(CONNECTION_JOB_JOIN_GRACE_MS) { job.join() }
                }
                cleanupOwnedVpnRuntime()
                finishStopIfCurrent(cleanupRunId, commandId, startId)
            }
        }
    }

    private suspend fun failAndStop(
        runId: Long,
        commandId: Long,
        startId: Int,
        message: String,
    ) {
        if (!isActiveCommandCurrent(runId, commandId)) return
        appContainer.smartConnectStateStore.fail(message, keepDesiredActive = false)
        appContainer.vpnRuntimeLeaseRegistry.invalidate(vpnTunnelOwner)
        userRequestedStop = true
        cleanupOwnedVpnRuntime()
        withContext(Dispatchers.Main.immediate) {
            if (!isLifecycleCommandCurrent(runId, commandId, startId)) return@withContext
            if (!stopSelfResult(startId)) return@withContext
            val repository = appContainer.vpnConnectionRepository
            if (repository.currentState.sessionOwner == VpnSessionOwner.SMART_CONNECT ||
                repository.currentState.sessionOwner == null
            ) {
                repository.setError(repository.currentState.activeConfigId, message)
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private suspend fun finishStopIfCurrent(runId: Long, commandId: Long, startId: Int) {
        withContext(Dispatchers.Main.immediate) {
            if (!isLifecycleCommandCurrent(runId, commandId, startId)) return@withContext
            if (!stopSelfResult(startId)) return@withContext
            val repository = appContainer.vpnConnectionRepository
            if (repository.currentState.sessionOwner == VpnSessionOwner.SMART_CONNECT) {
                repository.setDisconnected()
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private suspend fun rehydrateGlobalOwnerIfNeeded(runId: Long): Boolean {
        return withContext(Dispatchers.Main.immediate) {
            if (!shouldKeepSessionAlive(runId)) return@withContext false
            val repository = appContainer.vpnConnectionRepository
            val state = repository.currentState
            if (state.sessionOwner == null) {
                repository.setConnecting(
                    configId = null,
                    transport = VpnTransportType.XRAY,
                    sessionOwner = VpnSessionOwner.SMART_CONNECT,
                )
            }
            repository.currentState.sessionOwner == VpnSessionOwner.SMART_CONNECT
        }
    }

    private suspend fun rejectStartForForeignOwner(
        runId: Long,
        commandId: Long,
        startId: Int,
    ) {
        userRequestedStop = true
        appContainer.smartConnectStateStore.fail(
            message = "Another VPN session is active",
            keepDesiredActive = false,
        )
        withContext(Dispatchers.Main.immediate) {
            if (!isLifecycleCommandCurrent(runId, commandId, startId)) return@withContext
            stopSelfResult(startId)
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private suspend fun rejectStartForBusyRuntime(
        runId: Long,
        commandId: Long,
        startId: Int,
    ) {
        val message = "Another VPN runtime is still stopping; try again"
        userRequestedStop = true
        appContainer.smartConnectStateStore.fail(message, keepDesiredActive = false)
        withContext(Dispatchers.Main.immediate) {
            if (!isLifecycleCommandCurrent(runId, commandId, startId)) return@withContext
            val repository = appContainer.vpnConnectionRepository
            if (repository.currentState.sessionOwner == VpnSessionOwner.SMART_CONNECT) {
                repository.setError(repository.currentState.activeConfigId, message)
            }
            stopSelfResult(startId)
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private suspend fun publishConnectingState(
        runId: Long,
        profile: ProxyProfile,
        reconnecting: Boolean,
    ): Boolean = mutateGlobalStateIfOwned(runId) { repository ->
        if (reconnecting || repository.currentState.status == VpnConnectionStatus.CONNECTED) {
            repository.setReconnecting(
                profile.id,
                VpnTransportType.XRAY,
                VpnSessionOwner.SMART_CONNECT,
            )
        } else if (repository.currentState.activeConfigId != profile.id) {
            repository.setConnecting(
                profile.id,
                VpnTransportType.XRAY,
                VpnSessionOwner.SMART_CONNECT,
            )
        }
    }

    private suspend fun publishReconnectingState(runId: Long, profile: ProxyProfile): Boolean {
        return mutateGlobalStateIfOwned(runId) { repository ->
            repository.setReconnecting(
                profile.id,
                VpnTransportType.XRAY,
                VpnSessionOwner.SMART_CONNECT,
            )
        }
    }

    private suspend fun publishConnectedState(
        runId: Long,
        commandId: Long,
        profile: ProxyProfile,
    ): Boolean {
        if (!isActiveCommandCurrent(runId, commandId)) return false
        return mutateGlobalStateIfOwned(runId) { repository ->
            repository.setConnected(
                profile.id,
                VpnTransportType.XRAY,
                VpnSessionOwner.SMART_CONNECT,
            )
            repository.appendDiagnostic("Smart Connect verified ${profile.name} through YouTube")
        }
    }

    private suspend fun mutateGlobalStateIfOwned(
        runId: Long,
        mutation: (com.stansful.sshvpnclient.domain.repository.VpnConnectionRepository) -> Unit,
    ): Boolean = withContext(Dispatchers.Main.immediate) {
        if (!shouldKeepSessionAlive(runId)) return@withContext false
        val repository = appContainer.vpnConnectionRepository
        if (repository.currentState.sessionOwner != VpnSessionOwner.SMART_CONNECT) {
            return@withContext false
        }
        mutation(repository)
        true
    }

    private fun publishHealthySmartState(profile: ProxyProfile, healthLatencyMs: Long) {
        appContainer.smartConnectStateStore.publish { state ->
            state.copy(
                phase = SmartConnectPhase.CONNECTED,
                activeProfileId = profile.id,
                activeProfileName = profile.name,
                activeProfileLatencyMs = profile.lastLatencyMs,
                lastHealthLatencyMs = healthLatencyMs,
                retryDelayMs = null,
                message = "Connected via ${profile.name}",
            )
        }
    }

    private fun cleanupOwnedVpnRuntime() {
        stopOwnedXrayTransport()
        runCatching { appContainer.vpnTunnelManager.close(vpnTunnelOwner) }
        transportNetwork = null
    }

    private fun stopOwnedXrayTransport(): Boolean {
        val generation = xrayTransportGeneration.snapshot() ?: return false
        val stopped = runCatching {
            appContainer.xrayCoreBridge.stopBlocking(vpnTunnelOwner, generation)
        }.getOrDefault(false)
        val stillCurrent = appContainer.xrayCoreBridge.isRuntimeGenerationCurrent(
            vpnTunnelOwner,
            generation,
        )
        if (stopped || !stillCurrent) xrayTransportGeneration.clearIfMatches(generation)
        return stopped
    }

    private fun shouldKeepSessionAlive(runId: Long): Boolean {
        return connectionRunId.get() == runId &&
            !userRequestedStop &&
            !serviceDestroyed &&
            appContainer.smartConnectStateStore.desiredActive &&
            runtimeLease?.isCurrent() == true
    }

    private fun isActiveCommandCurrent(runId: Long, commandId: Long): Boolean {
        return shouldKeepSessionAlive(runId) && lifecycleCommandId.get() == commandId
    }

    private fun isLifecycleCommandCurrent(runId: Long, commandId: Long, startId: Int): Boolean {
        return isVpnLifecycleCommandCurrent(
            expectedRunId = runId,
            currentRunId = connectionRunId.get(),
            expectedCommandId = commandId,
            currentCommandId = lifecycleCommandId.get(),
            expectedStartId = startId,
            serviceDestroyed = serviceDestroyed,
        )
    }

    private fun drainWorkflowSignals() {
        while (workflowSignal.tryReceive().isSuccess) {
            // Drain every stale wake-up before entering a timed wait.
        }
    }

    private fun startSmartForeground(
        phase: NotificationPhase,
        text: String,
        force: Boolean = false,
    ) {
        val notificationKey = "${phase.name}:$text"
        if (!force && lastNotificationKey == notificationKey) return
        val manager = getSystemService(Service.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Smart Connect VPN",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val stopPendingIntent = PendingIntent.getService(
            this,
            NOTIFICATION_ID,
            stopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("Smart Connect")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Stop", stopPendingIntent)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        lastNotificationKey = notificationKey
    }

    private fun AppSettings.hasValidSmartRouting(): Boolean {
        return vpnMode != VpnMode.SELECTED_APPS || selectedAppPackages.isNotEmpty()
    }

    private fun AppSettings.smartRoutingSettings(): SmartRoutingSettings {
        return SmartRoutingSettings(
            vpnMode = vpnMode,
            selectedAppPackages = if (vpnMode == VpnMode.SELECTED_APPS) {
                selectedAppPackages
            } else {
                emptySet()
            },
        )
    }

    private fun Throwable.safeMessage(): String = message ?: javaClass.simpleName

    companion object {
        private const val ACTION_START = "com.stansful.sshvpnclient.action.START_SMART_CONNECT"
        private const val ACTION_STOP = "com.stansful.sshvpnclient.action.STOP_SMART_CONNECT"
        private const val CHANNEL_ID = "smart_connect_vpn"
        private const val NOTIFICATION_ID = 3003
        private const val VPN_SESSION_NAME = "Smart Connect"
        private const val CONNECTION_JOB_JOIN_GRACE_MS = 1_000L
        private const val RUNTIME_RESTART_DELAY_MS = 1_000L
        private const val MAX_SAME_PROFILE_RUNTIME_FAILURES = 2
        private const val SOCKET_ROUTING_FAILURES_BEFORE_REBUILD = 3
        private const val SOCKET_ROUTING_FAILURE_MIN_DURATION_MS = 5_000L
        private const val XRAY_STOPPED_OBSERVATIONS_BEFORE_REBUILD = 3
        private const val XRAY_STOPPED_MIN_DURATION_MS = 5_000L
        private const val NO_XRAY_GENERATION = Long.MIN_VALUE
        private const val NO_TIMESTAMP = -1L

        fun startIntent(context: Context): Intent {
            return Intent(context, SmartConnectVpnService::class.java).setAction(ACTION_START)
        }

        fun stopIntent(context: Context): Intent {
            return Intent(context, SmartConnectVpnService::class.java).setAction(ACTION_STOP)
        }
    }
}

private data class SmartRoutingSettings(
    val vpnMode: VpnMode,
    val selectedAppPackages: Set<String>,
)

private sealed interface ConnectionOutcome {
    data object Stopped : ConnectionOutcome
    data object NetworkChanged : ConnectionOutcome
    data object SettingsChanged : ConnectionOutcome
    data class ConfirmedHealthFailure(val message: String) : ConnectionOutcome
    data class RuntimeFailure(val message: String) : ConnectionOutcome
}

private enum class NotificationPhase {
    STARTING,
    WAITING,
    PREPARING,
    CONNECTING,
    CONNECTED,
    RETRY,
}
