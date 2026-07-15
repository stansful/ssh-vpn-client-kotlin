package com.stansful.sshvpnclient.data.local

import android.content.Context
import androidx.core.content.edit
import com.stansful.sshvpnclient.domain.model.SmartConnectPhase
import com.stansful.sshvpnclient.domain.model.SmartConnectState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SmartConnectStateStore(context: Context) {
    private val stateLock = Any()
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val mutableState = MutableStateFlow(
        if (preferences.getBoolean(KEY_DESIRED_ACTIVE, false)) {
            SmartConnectState(
                phase = SmartConnectPhase.STARTING,
                desiredActive = true,
                message = "Restoring Smart Connect",
            )
        } else {
            SmartConnectState()
        },
    )

    val state: StateFlow<SmartConnectState> = mutableState.asStateFlow()
    val desiredActive: Boolean
        get() = synchronized(stateLock) {
            preferences.getBoolean(KEY_DESIRED_ACTIVE, false)
        }

    fun begin() {
        synchronized(stateLock) {
            preferences.edit { putBoolean(KEY_DESIRED_ACTIVE, true) }
            mutableState.value = SmartConnectState(
                phase = SmartConnectPhase.STARTING,
                desiredActive = true,
                message = "Starting Smart Connect",
            )
        }
    }

    fun publish(transform: (SmartConnectState) -> SmartConnectState) {
        synchronized(stateLock) {
            if (!preferences.getBoolean(KEY_DESIRED_ACTIVE, false)) return
            mutableState.value = transform(mutableState.value).copy(desiredActive = true)
        }
    }

    fun stop(message: String? = null) {
        synchronized(stateLock) {
            preferences.edit { putBoolean(KEY_DESIRED_ACTIVE, false) }
            mutableState.value = SmartConnectState(
                phase = SmartConnectPhase.IDLE,
                desiredActive = false,
                message = message,
            )
        }
    }

    fun fail(message: String, keepDesiredActive: Boolean) {
        synchronized(stateLock) {
            val effectiveDesiredActive = keepDesiredActive &&
                preferences.getBoolean(KEY_DESIRED_ACTIVE, false)
            preferences.edit { putBoolean(KEY_DESIRED_ACTIVE, effectiveDesiredActive) }
            mutableState.value = mutableState.value.copy(
                phase = SmartConnectPhase.ERROR,
                desiredActive = effectiveDesiredActive,
                retryDelayMs = null,
                message = message,
            )
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "smart-connect-state"
        const val KEY_DESIRED_ACTIVE = "desired_active"
    }
}
