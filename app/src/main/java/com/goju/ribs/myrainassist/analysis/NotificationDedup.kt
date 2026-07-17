package com.goju.ribs.myrainassist.analysis

import android.content.Context
import android.util.Log
import com.goju.ribs.myrainassist.data.RadarLegend
import org.json.JSONObject
import kotlin.math.abs

/**
 * Persisted state machine for rain-arrival notifications. Once an "incoming" or "active rain"
 * alert has fired, it suppresses further alerts for the same episode (no repeat spam while it
 * keeps raining) until the rain blob has moved away from the user by [STOP_DISTANCE_KM] for
 * [STOP_CONFIRM_CYCLES] consecutive cycles, at which point it either fires a "rain stopped" alert
 * (rain was actually overhead) or silently resets to idle (a forecast never arrived — telling the
 * user "it passed without raining" isn't actionable enough to interrupt them for) so the next
 * approaching cloud can trigger a fresh cycle. While rain stays active, a fresh alert also fires if
 * the intensity crosses a 약한/보통/강한/매우 강한 tier boundary, so "지금 비가 오고 있어요" doesn't
 * go stale while a shower is still overhead but getting noticeably heavier or lighter.
 */
class NotificationDedup(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    sealed class Action {
        data class NotifyIncoming(val etaMinutes: Int) : Action()
        data object NotifyActiveRain : Action()
        data object NotifyRainStopped : Action()
        /** An INCOMING forecast never actually arrived — reset back to idle without alerting the user. */
        data object ResetToIdle : Action()
        data object None : Action()
    }

    data class Signal(val etaMinutesRounded: Int?, val nearestRainDistanceKm: Double?, val intensityMmh: Double?)

    /** Snapshot of the persisted state machine, for attaching to debug/event logs. */
    fun debugSnapshot(): JSONObject = JSONObject()
        .put("watching", prefs.getBoolean(KEY_WATCHING, false))
        .put("stoppedStreak", prefs.getInt(KEY_STOPPED_STREAK, 0))
        .put("activeRainNotified", prefs.getBoolean(KEY_ACTIVE_RAIN_NOTIFIED, false))
        .put("lastEtaBucket", if (prefs.contains(KEY_LAST_ETA_BUCKET)) prefs.getInt(KEY_LAST_ETA_BUCKET, -1) else JSONObject.NULL)
        .put("lastIntensityTier", if (prefs.contains(KEY_LAST_INTENSITY_TIER)) prefs.getInt(KEY_LAST_INTENSITY_TIER, -1) else JSONObject.NULL)

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
            editor.putBoolean(KEY_ACTIVE_RAIN_NOTIFIED, true)
                .putInt(KEY_LAST_INTENSITY_TIER, RadarLegend.intensityTier(signal.intensityMmh))
                .apply()
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
                val wasActive = prefs.getBoolean(KEY_ACTIVE_RAIN_NOTIFIED, false)
                prefs.edit().clear().apply()
                return if (wasActive) Action.NotifyRainStopped else Action.ResetToIdle
            }
            prefs.edit().putInt(KEY_STOPPED_STREAK, streak).apply()
            return Action.None
        }
        prefs.edit().putInt(KEY_STOPPED_STREAK, 0).apply()

        val activeRainNotified = prefs.getBoolean(KEY_ACTIVE_RAIN_NOTIFIED, false)
        val eta = signal.etaMinutesRounded

        if (!activeRainNotified && eta != null && eta <= 0) {
            prefs.edit().putBoolean(KEY_ACTIVE_RAIN_NOTIFIED, true)
                .putInt(KEY_LAST_INTENSITY_TIER, RadarLegend.intensityTier(signal.intensityMmh))
                .apply()
            return Action.NotifyActiveRain
        }
        if (!activeRainNotified && eta != null) {
            val lastEtaBucket = if (prefs.contains(KEY_LAST_ETA_BUCKET)) prefs.getInt(KEY_LAST_ETA_BUCKET, -1) else null
            if (lastEtaBucket == null || abs(eta - lastEtaBucket) >= MATERIAL_CHANGE_MINUTES) {
                prefs.edit().putInt(KEY_LAST_ETA_BUCKET, eta).apply()
                return Action.NotifyIncoming(eta)
            }
        }
        if (activeRainNotified && eta != null && eta <= 0) {
            val tier = RadarLegend.intensityTier(signal.intensityMmh)
            val lastTier = if (prefs.contains(KEY_LAST_INTENSITY_TIER)) prefs.getInt(KEY_LAST_INTENSITY_TIER, -1) else null
            if (lastTier == null || tier != lastTier) {
                Log.d(TAG, "evaluateWatching: intensity tier changed $lastTier -> $tier, re-alerting")
                prefs.edit().putInt(KEY_LAST_INTENSITY_TIER, tier).apply()
                return Action.NotifyActiveRain
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
        const val KEY_LAST_INTENSITY_TIER = "last_intensity_tier"
        const val STOP_DISTANCE_KM = 5.0
        const val STOP_CONFIRM_CYCLES = 2
        const val MATERIAL_CHANGE_MINUTES = 20
    }
}
