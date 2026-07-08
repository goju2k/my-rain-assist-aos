package com.goju.ribs.myrainassist.analysis

import com.goju.ribs.myrainassist.data.LatLon
import org.json.JSONArray
import org.json.JSONObject

enum class ForecastState { NONE, INCOMING, ACTIVE }

data class PathPoint(val minutesFromNow: Int, val position: LatLon)

data class BlobForecast(
    val id: String,
    val sizeCells: Int,
    val centroid: LatLon,
    val headingDeg: Double,
    val speedKmh: Double,
    val path: List<PathPoint>,
    val arrivalMinutes: Int?,
    /** Heaviest rain tier found anywhere in this blob, per [com.goju.ribs.myrainassist.data.RadarLegend]. */
    val peakMmh: Double,
)

data class RainForecastResult(
    val generatedAtEpochMs: Long,
    val userLocation: LatLon,
    val state: ForecastState,
    val etaMinutes: Int?,
    val blobs: List<BlobForecast>,
    /** Straight-line distance from the user to the nearest currently-detected rain blob; null if no blobs at all. */
    val nearestRainDistanceKm: Double?,
    /** Estimated intensity (mm/h) behind [state] — the rain overhead for ACTIVE, or the approaching blob's peak for INCOMING. Null for NONE. */
    val intensityMmh: Double?,
    /** tm of the radar frame this forecast was computed from, kept for notification-time debugging. */
    val latestFrameTm: String,
    /** Number of frames returned by the radar API this cycle. */
    val frameCount: Int,
    /** Minutes the latest frame's tm lagged behind [generatedAtEpochMs]. */
    val lagMinutes: Int,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("generatedAtEpochMs", generatedAtEpochMs)
        put("userLocation", JSONObject().apply {
            put("lat", userLocation.lat)
            put("lon", userLocation.lon)
        })
        put("forecast", JSONObject().apply {
            put("willArrive", etaMinutes != null)
            put("etaMinutes", etaMinutes ?: JSONObject.NULL)
            put("state", state.name)
            put("intensityMmh", intensityMmh ?: JSONObject.NULL)
        })
        put("blobs", JSONArray().apply {
            blobs.filter { it.isFinite() }.forEach { put(it.toJson()) }
        })
    }

    private fun BlobForecast.isFinite(): Boolean =
        centroid.lat.isFinite() && centroid.lon.isFinite() && headingDeg.isFinite() && speedKmh.isFinite() &&
            path.all { it.position.lat.isFinite() && it.position.lon.isFinite() }

    private fun BlobForecast.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("sizeCells", sizeCells)
        put("centroid", JSONObject().apply {
            put("lat", centroid.lat)
            put("lon", centroid.lon)
        })
        put("headingDeg", headingDeg)
        put("speedKmh", speedKmh)
        put("peakMmh", peakMmh)
        put("path", JSONArray().apply {
            path.forEach { point ->
                put(JSONObject().apply {
                    put("minutesFromNow", point.minutesFromNow)
                    put("lat", point.position.lat)
                    put("lon", point.position.lon)
                })
            }
        })
    }
}
