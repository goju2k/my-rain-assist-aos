package com.goju.ribs.myrainassist.notification

import android.content.Context
import com.goju.ribs.myrainassist.analysis.RainForecastResult

data class LastCycleSnapshot(
    val timestampEpochMs: Long,
    val state: String,
    val latestFrameTm: String,
    val lagMinutes: Int,
    val etaMinutes: Int?,
    val nearestRainDistanceKm: Double?,
    val intensityMmh: Double?,
    val frameCount: Int,
)

/**
 * Persists a snapshot of only the most recently *executed* forecast cycle (overwritten every
 * cycle, not appended) — [RainEventLog] only records cycles that produced a notification-worthy
 * state change, so a long stretch of "still no rain nearby" cycles leaves no trace there at all.
 * When something looks wrong, knowing which radar frame (`tm`) and lag the last cycle actually
 * ran against is what narrows down whether the problem is stale data, a bad forecast, or the
 * service not running at all.
 */
object LastCycleDebug {

    private const val PREFS_NAME = "last_cycle_debug"
    private const val KEY_TIMESTAMP = "timestampEpochMs"
    private const val KEY_STATE = "state"
    private const val KEY_TM = "latestFrameTm"
    private const val KEY_LAG = "lagMinutes"
    private const val KEY_ETA = "etaMinutes"
    private const val KEY_DISTANCE = "nearestRainDistanceKm"
    private const val KEY_INTENSITY = "intensityMmh"
    private const val KEY_FRAME_COUNT = "frameCount"

    fun save(context: Context, result: RainForecastResult) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putLong(KEY_TIMESTAMP, result.generatedAtEpochMs)
            .putString(KEY_STATE, result.state.name)
            .putString(KEY_TM, result.latestFrameTm)
            .putInt(KEY_LAG, result.lagMinutes)
            .apply {
                if (result.etaMinutes != null) putInt(KEY_ETA, result.etaMinutes) else remove(KEY_ETA)
                if (result.nearestRainDistanceKm != null) putFloat(KEY_DISTANCE, result.nearestRainDistanceKm.toFloat()) else remove(KEY_DISTANCE)
                if (result.intensityMmh != null) putFloat(KEY_INTENSITY, result.intensityMmh.toFloat()) else remove(KEY_INTENSITY)
            }
            .putInt(KEY_FRAME_COUNT, result.frameCount)
            .apply()
    }

    fun read(context: Context): LastCycleSnapshot? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_TIMESTAMP)) return null
        return LastCycleSnapshot(
            timestampEpochMs = prefs.getLong(KEY_TIMESTAMP, 0L),
            state = prefs.getString(KEY_STATE, "") ?: "",
            latestFrameTm = prefs.getString(KEY_TM, "") ?: "",
            lagMinutes = prefs.getInt(KEY_LAG, 0),
            etaMinutes = if (prefs.contains(KEY_ETA)) prefs.getInt(KEY_ETA, 0) else null,
            nearestRainDistanceKm = if (prefs.contains(KEY_DISTANCE)) prefs.getFloat(KEY_DISTANCE, 0f).toDouble() else null,
            intensityMmh = if (prefs.contains(KEY_INTENSITY)) prefs.getFloat(KEY_INTENSITY, 0f).toDouble() else null,
            frameCount = prefs.getInt(KEY_FRAME_COUNT, 0),
        )
    }
}
