package com.stansful.sshvpnclient.ui.shell

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.stansful.sshvpnclient.domain.model.AppSettings
import com.stansful.sshvpnclient.domain.model.OpenSourcePolicy
import com.stansful.sshvpnclient.domain.repository.AppSettingsRepository
import com.stansful.sshvpnclient.ui.system.ConsentKind
import com.stansful.sshvpnclient.ui.system.ConsentSheet
import com.stansful.sshvpnclient.work.ProxySourceSyncWorker

/**
 * Asks for the public-routes consent before Auto or Routes is used.
 *
 * - Auto: once — while `showSmartConnectWarningOnEnter` and the stored Auto consent version is
 *   older than [OpenSourcePolicy.CONSENT_VERSION].
 * - Routes: until the stored Routes consent version is current (whatever the warning switch says:
 *   the quick tile and the background list refresh need it), then while
 *   `showOpenSourceWarningOnEnter`, at most once per app process (after Continue it is not asked
 *   again until the process restarts).
 *
 * When no consent is needed the request calls `onGranted` right away. Continue persists the
 * consent version (Routes also schedules the background list sync when auto-update is on, and
 * applies "Don't show this again"); Not now / Back / tap outside calls `onDenied` — callers keep the
 * user on Home in the previous mode.
 */
@Stable
class ConsentGate internal constructor(
    private val settingsRepository: AppSettingsRepository,
    private val applicationContext: Context,
) {
    internal var pending: PendingConsent? by mutableStateOf(null)
        private set

    fun requestAuto(onGranted: () -> Unit, onDenied: () -> Unit = {}) {
        request(ConsentKind.AUTO, onGranted, onDenied)
    }

    fun requestRoutes(onGranted: () -> Unit, onDenied: () -> Unit = {}) {
        request(ConsentKind.ROUTES, onGranted, onDenied)
    }

    private fun request(kind: ConsentKind, onGranted: () -> Unit, onDenied: () -> Unit) {
        val settings = settingsRepository.settings.value
        val required = when (kind) {
            ConsentKind.AUTO -> autoConsentRequired(settings)
            ConsentKind.ROUTES -> routesConsentRequired(settings, RoutesConsentSession.acknowledged)
        }
        if (!required) {
            onGranted()
            return
        }
        pending?.onDenied?.invoke()
        pending = PendingConsent(kind, onGranted, onDenied)
    }

    internal fun onContinue(dontShowAgain: Boolean) {
        val request = pending ?: return
        pending = null
        when (request.kind) {
            ConsentKind.AUTO -> {
                settingsRepository.setSmartConnectConsentVersion(OpenSourcePolicy.CONSENT_VERSION)
                if (dontShowAgain) settingsRepository.setShowSmartConnectWarningOnEnter(false)
            }
            ConsentKind.ROUTES -> {
                settingsRepository.setOpenSourceConsentVersion(OpenSourcePolicy.CONSENT_VERSION)
                if (settingsRepository.settings.value.openSourceAutoUpdateEnabled) {
                    ProxySourceSyncWorker.schedule(applicationContext)
                }
                if (dontShowAgain) settingsRepository.setShowOpenSourceWarningOnEnter(false)
                RoutesConsentSession.acknowledged = true
            }
        }
        request.onGranted()
    }

    /**
     * A Routes session is already running (quick tile, restored session) while Home shows it: the
     * stored consent covers this process, so its end doesn't ask again.
     */
    fun acknowledgeRunningRoutes() {
        val settings = settingsRepository.settings.value
        if (settings.openSourceConsentVersion >= OpenSourcePolicy.CONSENT_VERSION) {
            RoutesConsentSession.acknowledged = true
        }
    }

    internal fun onNotNow() {
        val request = pending ?: return
        pending = null
        request.onDenied()
    }
}

internal class PendingConsent(
    val kind: ConsentKind,
    val onGranted: () -> Unit,
    val onDenied: () -> Unit,
)

/** Remembers the gate and renders its [ConsentSheet] while a request is pending. */
@Composable
fun rememberConsentGate(): ConsentGate {
    val container = LocalAppContainer.current
    val gate = remember(container) {
        ConsentGate(
            settingsRepository = container.appSettingsRepository,
            applicationContext = container.applicationContext,
        )
    }
    gate.pending?.let { request ->
        ConsentSheet(
            kind = request.kind,
            onContinue = gate::onContinue,
            onNotNow = gate::onNotNow,
        )
    }
    return gate
}

/** Auto asks once per consent version, unless the user turned the warning off. */
internal fun autoConsentRequired(settings: AppSettings): Boolean =
    settings.showSmartConnectWarningOnEnter &&
        settings.smartConnectConsentVersion < OpenSourcePolicy.CONSENT_VERSION

/**
 * Routes asks until its consent version is stored (once, even with the warning switched off before
 * it was ever accepted), then while the warning is on, at most once per process.
 */
internal fun routesConsentRequired(settings: AppSettings, acknowledgedThisProcess: Boolean): Boolean =
    settings.openSourceConsentVersion < OpenSourcePolicy.CONSENT_VERSION ||
        (settings.showOpenSourceWarningOnEnter && !acknowledgedThisProcess)

/** Process-wide memory of the Routes consent (reset when the process dies). */
internal object RoutesConsentSession {
    @Volatile
    var acknowledged: Boolean = false
}
