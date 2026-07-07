package com.goju.ribs.myrainassist.analysis

import android.content.Context
import android.util.Log
import org.json.JSONObject
import kotlin.math.abs

/**
 * Persisted state machine for rain-arrival notifications. Once an "incoming" or "active rain"
 * alert has fired, it suppresses further alerts for the same episode (no repeat spam while it
 * keeps raining) until the rain blob has moved away from the user by [STOP_DISTANCE_KM] for
 * [STOP_CONFIRM_CYCLES] consecutive cycles, at which point it fires a "rain stopped" alert and
 * resets back to idle so the next approaching cloud can trigger a fresh cycle.
 */
class NotificationDedup(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    sealed class Action {
        data class NotifyIncoming(val etaMinutes: Int) : Action()
        data object NotifyActiveRain : Action()
        data object NotifyRainStopped : Action()
        data object None : Action()
    }

    data class Signal(val etaMinutesRounded: Int?, val nearestRainDistanceKm: Double?)

    /** Snapshot of the persisted state machine, for attaching to debug/event logs. */
    fun debugSnapshot(): JSONObject = JSONObject()
        .put("watching", prefs.getBoolean(KEY_WATCHING, false))
        .put("stoppedStreak", prefs.getInt(KEY_STOPPED_STREAK, 0))
        .put("activeRainNotified", prefs.getBoolean(KEY_ACTIVE_RAIN_NOTIFIED, false))
        .put("lastEtaBucket", if (prefs.contains(KEY_LAST_ETA_BUCKET)) prefs.getInt(KEY_LAST_ETA_BUCKET, -1) else JSONObject.NULL)

    fun evaluate(signal: Signal): Action {
        val watching = prefs.getBoolean(KEY_WATCHING, false)
        val action = if (!watching) evaluateIdle(signal) else evaluateWatching(signal)
        Log.d(TAG, "evaluate: watching=$watching signal=$signal -> $action")
        return action
    }

    private fun evaluateIdle(signal: Signal): Action {
        val eta = signal.etaMinutesRounded ?: return Action.None

        val editor = prefs.edit().putBoolean(KEY_WATCHING, true).putInt(KEY_STOPPED_STREAK, 0)
        return if (eta <= 0) {
            editor.putBoolean(KEY_ACTIVE_RAIN_NOTIFIED, true).apply()
            Action.NotifyActiveRain
        } else {
            editor.putInt(KEY_LAST_ETA_BUCKET, eta).putBoolean(KEY_ACTIVE_RAIN_NOTIFIED, false).apply()
            Action.NotifyIncoming(eta)
        }
    }

    private fun evaluateWatching(signal: Signal): Action {
        val hasNearbyRain = signal.etaMinutesRounded != null ||
            (signal.nearestRainDistanceKm != null && signal.nearestRainDistanceKm < STOP_DISTANCE_KM)

        if (!hasNearbyRain) {
            val streak = prefs.getInt(KEY_STOPPED_STREAK, 0) + 1
            Log.d(TAG, "evaluateWatching: no nearby rain, streak=$streak/$STOP_CONFIRM_CYCLES")
            if (streak >= STOP_CONFIRM_CYCLES) {
                prefs.edit().clear().apply()
                return Action.NotifyRainStopped
            }
            prefs.edit().putInt(KEY_STOPPED_STREAK, streak).apply()
            return Action.None
        }
        prefs.edit().putInt(KEY_STOPPED_STREAK, 0).apply()

        val activeRainNotified = prefs.getBoolean(KEY_ACTIVE_RAIN_NOTIFIED, false)
        val eta = signal.etaMinutesRounded

        if (!activeRainNotified && eta != null && eta <= 0) {
            prefs.edit().putBoolean(KEY_ACTIVE_RAIN_NOTIFIED, true).apply()
            return Action.NotifyActiveRain
        }
        if (!activeRainNotified && eta != null) {
            val lastEtaBucket = if (prefs.contains(KEY_LAST_ETA_BUCKET)) prefs.getInt(KEY_LAST_ETA_BUCKET, -1) else null
            if (lastEtaBucket == null || abs(eta - lastEtaBucket) >= MATERIAL_CHANGE_MINUTES) {
                prefs.edit().putInt(KEY_LAST_ETA_BUCKET, eta).apply()
                return Action.NotifyIncoming(eta)
            }
        }
        return Action.None
    }

    private companion object {
        const val TAG = "NotificationDedup"
        const val PREFS_NAME = "rain_notification_state"
        const val KEY_WATCHING = "watching"
        const val KEY_STOPPED_STREAK = "stopped_streak"
        const val KEY_LAST_ETA_BUCKET = "last_eta_bucket"
        const val KEY_ACTIVE_RAIN_NOTIFIED = "active_rain_notified"
        const val STOP_DISTANCE_KM = 5.0
        const val STOP_CONFIRM_CYCLES = 2
        const val MATERIAL_CHANGE_MINUTES = 20
    }
}
