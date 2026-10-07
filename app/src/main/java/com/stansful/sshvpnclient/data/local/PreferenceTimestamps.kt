package com.stansful.sshvpnclient.data.local

import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn

/**
 * The epoch-millis time stored under [key] (null while it was never written, or is not positive), then
 * every change of it. The stored value is first read on [ioDispatcher] (it can wait for the preferences
 * file to load); later changes arrive through the preferences' change listener.
 */
internal fun SharedPreferences.timestampFlow(key: String, ioDispatcher: CoroutineDispatcher): Flow<Long?> =
    callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { preferences, changed ->
            // null: the whole file was cleared (API 30+).
            if (changed == key || changed == null) trySend(preferences.timestamp(key))
        }
        // Listen first, so a write between the read and the registration is not missed.
        registerOnSharedPreferenceChangeListener(listener)
        send(timestamp(key))
        awaitClose { unregisterOnSharedPreferenceChangeListener(listener) }
    }
        .flowOn(ioDispatcher)
        .distinctUntilChanged()

private fun SharedPreferences.timestamp(key: String): Long? = getLong(key, 0L).takeIf { it > 0L }
