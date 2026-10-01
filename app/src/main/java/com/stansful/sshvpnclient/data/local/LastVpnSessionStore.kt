package com.stansful.sshvpnclient.data.local

import android.content.Context
import androidx.core.content.edit
import com.stansful.sshvpnclient.domain.model.VpnSessionOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The mode of the last committed VPN start. VpnConnectionState.sessionOwner is cleared on every
 * disconnect, error and process death; this value is not, so the quick settings tile can start
 * the same mode again.
 */
class LastVpnSessionStore(context: Context) {
    private val stateLock = Any()
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val mutableLastOwner = MutableStateFlow(
        vpnSessionOwnerFromStorageValue(preferences.getString(KEY_LAST_SESSION_OWNER, null)),
    )

    val lastOwner: StateFlow<VpnSessionOwner?> = mutableLastOwner.asStateFlow()

    fun record(owner: VpnSessionOwner) {
        synchronized(stateLock) {
            if (mutableLastOwner.value == owner) return
            preferences.edit { putString(KEY_LAST_SESSION_OWNER, owner.toStorageValue()) }
            mutableLastOwner.value = owner
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "vpn-session-history"
        const val KEY_LAST_SESSION_OWNER = "last_session_owner"
    }
}

// Persisted values: they must survive renames of VpnSessionOwner entries.
internal fun VpnSessionOwner.toStorageValue(): String {
    return when (this) {
        VpnSessionOwner.SHADOW_SSH -> "shadow-ssh"
        VpnSessionOwner.OPEN_SOURCE -> "opensource"
        VpnSessionOwner.SMART_CONNECT -> "smart-connect"
    }
}

internal fun vpnSessionOwnerFromStorageValue(value: String?): VpnSessionOwner? {
    return VpnSessionOwner.entries.firstOrNull { owner -> owner.toStorageValue() == value }
}
