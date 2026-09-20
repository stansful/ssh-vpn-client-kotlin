package com.stansful.sshvpnclient.vpn

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.io.Closeable

/**
 * The only places where the SSH runtime keeps the CPU from sleeping, all bounded.
 *
 * - A partial wake lock for one connect attempt while the screen is off, capped by the attempt
 *   deadline. Without it the CPU can suspend between the SYN and the SSH handshake, and a
 *   reconnect that has started simply stops until something else wakes the phone.
 * - An allow-while-idle alarm for a backoff wait longer than a few seconds. Coroutine delays run on
 *   uptime, which stands still in deep sleep; the alarm runs on elapsed real time and also grants
 *   the short network allowlist an idle device needs for the attempt it triggers.
 * - A wake lock for a backoff wait of a few seconds instead: alarms are inexact and rate-limited in
 *   Doze, a 250 ms wait is not worth one.
 *
 * Holders are counted here, not by the platform lock: each [Hold] keeps the CPU up until its own
 * deadline or release, so a late release from an older attempt can never end a newer one's.
 */
internal class ReconnectWakeHelper(
    private val context: Context,
    private val onRetryAlarm: () -> Unit,
) : Closeable {
    private val wakeLock: PowerManager.WakeLock? = context.getSystemService(PowerManager::class.java)
        ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
        ?.apply { setReferenceCounted(false) }
    private val alarmManager: AlarmManager? = context.getSystemService(AlarmManager::class.java)
    private val alarmAction = "${context.packageName}$ALARM_ACTION_SUFFIX"
    private val alarmIntent: PendingIntent = PendingIntent.getBroadcast(
        context,
        ALARM_REQUEST_CODE,
        Intent(alarmAction).setPackage(context.packageName),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != alarmAction) return
            // The alarm manager's own wake lock ends with onReceive; keep the CPU up until the
            // connection loop has picked the event up and taken the attempt wake lock.
            acquire(Hold(), ALARM_HANDOFF_WAKE_MS)
            onRetryAlarm()
        }
    }
    private val holdLock = Any()
    private val holdDeadlines = HashMap<Hold, Long>()

    /** One holder's claim on the wake lock. */
    class Hold

    @Volatile
    private var closed = false

    init {
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(alarmAction),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /** Arms the alarm [delayMs] from now, replacing one armed earlier. */
    fun scheduleRetryAlarm(delayMs: Long) {
        if (closed) return
        runCatching {
            alarmManager?.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayMs,
                alarmIntent,
            )
        }
    }

    fun cancelRetryAlarm() {
        runCatching { alarmManager?.cancel(alarmIntent) }
    }

    override fun close() {
        closed = true
        cancelRetryAlarm()
        runCatching { context.unregisterReceiver(receiver) }
        synchronized(holdLock) {
            holdDeadlines.clear()
            applyLocked(SystemClock.elapsedRealtime())
        }
    }

    /** Keeps the CPU awake for [hold] for at most [timeoutMs]; [release] of the same hold ends it early. */
    fun acquire(hold: Hold, timeoutMs: Long) {
        if (closed) return
        synchronized(holdLock) {
            val nowMs = SystemClock.elapsedRealtime()
            holdDeadlines[hold] = nowMs + timeoutMs
            applyLocked(nowMs)
        }
    }

    fun release(hold: Hold) {
        synchronized(holdLock) {
            if (holdDeadlines.remove(hold) == null) return
            applyLocked(SystemClock.elapsedRealtime())
        }
    }

    /** The platform lock is not reference counted: re-acquiring replaces its timeout with the latest deadline. */
    private fun applyLocked(nowMs: Long) {
        holdDeadlines.values.removeAll { deadlineMs -> deadlineMs <= nowMs }
        val untilMs = holdDeadlines.values.maxOrNull()
        runCatching {
            if (untilMs == null) {
                if (wakeLock?.isHeld == true) wakeLock.release()
            } else {
                wakeLock?.acquire(untilMs - nowMs)
            }
        }
    }

    private companion object {
        const val WAKE_LOCK_TAG = "shadow-ssh:reconnect"
        const val ALARM_ACTION_SUFFIX = ".action.SSH_RECONNECT_RETRY"
        const val ALARM_REQUEST_CODE = 3_002
        const val ALARM_HANDOFF_WAKE_MS = 10_000L
    }
}
