package com.stansful.sshvpnclient.vpn

import com.stansful.sshvpnclient.domain.model.VpnSessionOwner
import com.stansful.sshvpnclient.domain.repository.VpnConnectionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Android's own start of the VPN. With Always-on VPN set for this app, Android starts
 * `Intent(VpnService.SERVICE_INTERFACE).setPackage(app)` when Always-on is turned on, when the phone is
 * unlocked after a reboot and after an app update. That intent reaches whichever of the three VPN
 * services the package manager lists first, so each of them hands the start here and stops again
 * unless it turns out to run the mode.
 *
 * This brings back the mode started last, through the tile's [LastVpnModeStarter] and so through the
 * mode's own use case. A session that is already starting, running or stopping is left alone. Nobody
 * is looking at the phone, so a missing server, route, engine or consent only adds a line to the log.
 *
 * It runs in the application scope, not the receiving service's: ConnectVpnUseCase stops the Routes and
 * Auto services, which may be the one that received the start.
 *
 * [lastOwner] reads LastVpnSessionStore; [mainDispatcher] is the main thread the services call from
 * (replaced in JVM tests).
 */
internal class AlwaysOnVpnStarter(
    private val applicationScope: CoroutineScope,
    private val vpnConnectionRepository: VpnConnectionRepository,
    private val lastOwner: () -> VpnSessionOwner?,
    private val modeStarter: VpnModeStarter,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    /** Main thread only. */
    private var inFlight: Job? = null

    /**
     * Runs the receiving service's startForeground(). Android allowlists the app for a moment when it
     * starts it, so this normally succeeds; on a build that refuses a foreground start from the
     * background, the start goes on without the notice (the mode's own start then fails and is logged)
     * instead of crashing the app at every unlock.
     */
    fun enterForeground(startForeground: () -> Unit) {
        try {
            startForeground()
        } catch (error: Exception) {
            log("Always-on VPN: could not show the notification: ${error.message ?: error::class.java.simpleName}")
        }
    }

    /**
     * Called on the main thread by the service Android started. [onFinished] runs on the main thread once
     * the mode was started or the start was dropped; the service decides there whether to stop again.
     */
    fun start(onFinished: () -> Unit) {
        val previous = inFlight
        inFlight = applicationScope.launch(mainDispatcher) {
            try {
                if (previous?.isActive == true) {
                    // Android may deliver its start twice (AOSP says so for a VPN that is still setting
                    // up); the second one waits for the first instead of starting the mode again.
                    previous.join()
                } else {
                    startLastMode()
                }
            } finally {
                onFinished()
            }
        }
    }

    private suspend fun startLastMode() {
        try {
            val owner = startTarget(lastOwner()) ?: return
            val check = modeStarter.check(owner)
            // The lookups suspended: the app or the tile may have started a session meanwhile.
            if (startTarget(owner) == null) return
            when (val plan = resolveQuickTileConnectPlan(check.preconditions)) {
                is QuickTileConnectPlan.Connect -> {
                    val failure = modeStarter.connect(plan.owner, check.failureConfigId)
                    // A use case that gave up returns normally too: the state it left says what happened.
                    log(alwaysOnConnectDiagnostic(plan.owner, failure, vpnConnectionRepository.currentState))
                }
                is QuickTileConnectPlan.OpenApp -> log(alwaysOnSkippedDiagnostic(owner, plan.reason))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log("Always-on VPN: start failed: ${error.message ?: error::class.java.simpleName}")
        }
    }

    /** The mode to start, or null after logging why Android's start is dropped. */
    private fun startTarget(lastOwner: VpnSessionOwner?): VpnSessionOwner? {
        return when (val decision = resolveAlwaysOnStart(vpnConnectionRepository.currentState, lastOwner)) {
            is AlwaysOnStartDecision.Start -> decision.owner
            is AlwaysOnStartDecision.Ignore -> {
                log(alwaysOnIgnoredDiagnostic(decision))
                null
            }
            AlwaysOnStartDecision.NoRecordedMode -> {
                log(ALWAYS_ON_NO_RECORDED_MODE_DIAGNOSTIC)
                null
            }
        }
    }

    private fun log(message: String) {
        vpnConnectionRepository.appendDiagnostic(message)
    }
}
