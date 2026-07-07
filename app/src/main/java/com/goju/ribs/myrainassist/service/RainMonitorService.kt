package com.goju.ribs.myrainassist.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.IBinder
import android.util.Log
import com.goju.ribs.myrainassist.analysis.ForecastEngine
import com.goju.ribs.myrainassist.analysis.NotificationDedup
import com.goju.ribs.myrainassist.data.LatLon
import com.goju.ribs.myrainassist.data.RadarApi
import com.goju.ribs.myrainassist.notification.NotificationHelper
import com.goju.ribs.myrainassist.notification.RainEventLog
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * Persistent foreground service that polls the radar API on a ~5.5 minute cadence, runs the
 * forecast analysis, publishes results to [RainForecastBus] for the UI, and raises notifications.
 */
class RainMonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pollingJob: Job? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var notificationDedup: NotificationDedup
    private var ongoingContentText: String = NotificationHelper.DEFAULT_ONGOING_TEXT

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        notificationDedup = NotificationDedup(this)

        if (!startOngoingForeground()) {
            stopSelf()
            return
        }

        if (pollingJob == null) {
            pollingJob = scope.launch { pollLoop() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // Some OEMs (notably Samsung One UI) silently drop the ongoing notification while leaving
    // the service itself alive and foreground, even with battery usage set to "unrestricted".
    // Re-asserting startForeground() with a fresh notification each poll cycle recreates it.
    private fun startOngoingForeground(): Boolean {
        return try {
            startForeground(
                NotificationHelper.ONGOING_NOTIFICATION_ID,
                NotificationHelper.buildOngoingNotification(this, ongoingContentText),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Missing permission to start location foreground service", e)
            false
        }
    }

    private suspend fun pollLoop() {
        while (true) {
            startOngoingForeground()
            runCatching { runCycle() }.onFailure { Log.w(TAG, "Poll cycle failed", it) }
            delay(POLL_INTERVAL_MS)
        }
    }

    private suspend fun runCycle() {
        val dedupBefore = notificationDedup.debugSnapshot()
        val locationFix = getCurrentLocation()
        if (locationFix == null) {
            Log.w(TAG, "runCycle: location unavailable, skipping cycle (dedup=$dedupBefore)")
            RainEventLog.append(this, "SKIP_LOCATION", "위치 정보를 가져오지 못해 강수 체크를 건너뛰었어요", dedupBefore)
            return
        }
        val location = locationFix.location
        if (locationFix.cached) {
            Log.w(TAG, "runCycle: using cached location, ageMs=${locationFix.ageMs}")
        }
        val response = runCatching { RadarApi.fetchFrames() }
            .onFailure { Log.w(TAG, "runCycle: fetchFrames failed", it) }
            .getOrNull()
        if (response == null) {
            RainEventLog.append(this, "SKIP_FETCH", "레이더 데이터 수신 실패로 건너뛰었어요", dedupBefore)
            return
        }
        val result = ForecastEngine.computeForecast(response, location, System.currentTimeMillis())
        if (result == null) {
            Log.d(TAG, "runCycle: computeForecast returned null (frames=${response.frames.size}, location=$location)")
            val debug = dedupBefore.put("frameCount", response.frames.size)
                .put("userLat", location.lat)
                .put("userLon", location.lon)
                .put("locationCached", locationFix.cached)
                .put("locationAgeMs", locationFix.ageMs)
            RainEventLog.append(this, "SKIP_FORECAST", "위치가 레이더 범위를 벗어나 건너뛰었어요", debug)
            return
        }
        Log.d(TAG, "runCycle: state=${result.state} etaMinutes=${result.etaMinutes} nearestRainDistanceKm=${result.nearestRainDistanceKm}")
        RainForecastBus.publish(result)

        val signal = NotificationDedup.Signal(result.etaMinutes, result.nearestRainDistanceKm)
        val action = notificationDedup.evaluate(signal)
        if (action != NotificationDedup.Action.None) {
            val frameDebug = dedupBefore
                .put("latestFrameTm", result.latestFrameTm)
                .put("frameCount", result.frameCount)
                .put("lagMinutes", result.lagMinutes)
                .put("nearestRainDistanceKm", result.nearestRainDistanceKm ?: JSONObject.NULL)
                .put("etaMinutes", result.etaMinutes ?: JSONObject.NULL)
                .put("userLat", location.lat)
                .put("userLon", location.lon)
                .put("locationCached", locationFix.cached)
                .put("locationAgeMs", locationFix.ageMs)
            when (action) {
                is NotificationDedup.Action.NotifyIncoming -> {
                    val message = NotificationHelper.showIncomingRainAlert(this, action.etaMinutes)
                    RainEventLog.append(this, "INCOMING", message, frameDebug)
                }
                NotificationDedup.Action.NotifyActiveRain -> {
                    val message = NotificationHelper.showActiveRainAlert(this)
                    RainEventLog.append(this, "ACTIVE", message, frameDebug)
                }
                NotificationDedup.Action.NotifyRainStopped -> {
                    val message = NotificationHelper.showRainStoppedAlert(this)
                    RainEventLog.append(this, "STOPPED", message, frameDebug)
                }
                NotificationDedup.Action.None -> Unit
            }
        }

        // Refresh the ongoing notification text right away so it tracks the phase that was just
        // computed, rather than waiting for the next loop iteration's pre-cycle re-assert.
        ongoingContentText = NotificationHelper.ongoingTextFor(result.state, action == NotificationDedup.Action.NotifyRainStopped)
        startOngoingForeground()
    }

    /** [ageMs] is 0 for a fresh fix; for a [cached] fix it's how long ago the OS last recorded it. */
    private data class LocationFix(val location: LatLon, val cached: Boolean, val ageMs: Long)

    // getCurrentLocation() can fail indoors (no GPS/Wi-Fi fix) even though the user hasn't moved,
    // which would otherwise skip the poll cycle entirely and stall rain-stopped detection. Falling
    // back to the OS's last-known fix keeps the check running as long as it isn't too stale to
    // trust the user is still there.
    private suspend fun getCurrentLocation(): LocationFix? {
        requestFreshLocation()?.let { return LocationFix(LatLon(it.latitude, it.longitude), cached = false, ageMs = 0L) }

        val cached = requestLastKnownLocation() ?: return null
        val ageMs = System.currentTimeMillis() - cached.time
        if (ageMs > MAX_CACHED_LOCATION_AGE_MS) {
            Log.w(TAG, "getCurrentLocation: cached location too stale (ageMs=$ageMs), ignoring")
            return null
        }
        return LocationFix(LatLon(cached.latitude, cached.longitude), cached = true, ageMs = ageMs)
    }

    private suspend fun requestFreshLocation(): Location? = suspendCancellableCoroutine { cont ->
        val cancellationSource = CancellationTokenSource()
        try {
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cancellationSource.token)
                .addOnSuccessListener { location -> cont.resume(location) }
                .addOnFailureListener {
                    Log.w(TAG, "getCurrentLocation: fusedLocationClient failed", it)
                    cont.resume(null)
                }
        } catch (e: SecurityException) {
            Log.w(TAG, "getCurrentLocation: missing location permission", e)
            cont.resume(null)
        }
        cont.invokeOnCancellation { cancellationSource.cancel() }
    }

    private suspend fun requestLastKnownLocation(): Location? = suspendCancellableCoroutine { cont ->
        try {
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location -> cont.resume(location) }
                .addOnFailureListener {
                    Log.w(TAG, "requestLastKnownLocation: failed", it)
                    cont.resume(null)
                }
        } catch (e: SecurityException) {
            cont.resume(null)
        }
    }

    private companion object {
        const val TAG = "RainMonitorService"
        const val POLL_INTERVAL_MS = 5 * 60 * 1000L + 30_000L
        const val MAX_CACHED_LOCATION_AGE_MS = 6 * 60 * 60 * 1000L
    }
}
