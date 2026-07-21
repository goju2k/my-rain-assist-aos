package com.goju.ribs.myrainassist.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.goju.ribs.myrainassist.analysis.ForecastEngine
import com.goju.ribs.myrainassist.analysis.NotificationDedup
import com.goju.ribs.myrainassist.data.RadarApi
import com.goju.ribs.myrainassist.location.LocationFixProvider
import com.goju.ribs.myrainassist.notification.LastCycleDebug
import com.goju.ribs.myrainassist.notification.NotificationHelper
import com.goju.ribs.myrainassist.notification.RainEventLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Persistent foreground service that polls the radar API on a ~5.5 minute cadence, runs the
 * forecast analysis, publishes results to [RainForecastBus] for the UI, and raises notifications.
 */
class RainMonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pollingJob: Job? = null
    private lateinit var locationProvider: LocationFixProvider
    private lateinit var notificationDedup: NotificationDedup
    private var ongoingContentText: String = NotificationHelper.DEFAULT_ONGOING_TEXT

    // Conflated so a burst of foreground-return triggers (e.g. pulling down and dismissing the
    // notification shade) collapses to a single extra cycle instead of piling up.
    private val immediateCheckTrigger = Channel<Unit>(Channel.CONFLATED)

    override fun onCreate() {
        super.onCreate()
        locationProvider = LocationFixProvider(this)
        notificationDedup = NotificationDedup(this)

        if (!startOngoingForeground()) {
            stopSelf()
            return
        }

        if (pollingJob == null) {
            pollingJob = scope.launch { pollLoop() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CHECK_NOW) {
            immediateCheckTrigger.trySend(Unit)
        }
        return START_STICKY
    }

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
            // Waits out the normal cadence, but an immediate-check request (app opened/foregrounded)
            // cuts this short so the next cycle runs right away instead of waiting up to ~5.5 min.
            withTimeoutOrNull(POLL_INTERVAL_MS) { immediateCheckTrigger.receive() }
        }
    }

    private suspend fun runCycle() {
        val dedupBefore = notificationDedup.debugSnapshot()
        val locationFix = locationProvider.getCurrentLocation()
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
        Log.d(TAG, "runCycle: state=${result.state} etaMinutes=${result.etaMinutes} nearestRainDistanceKm=${result.nearestRainDistanceKm} latestFrameTm=${result.latestFrameTm} lagMinutes=${result.lagMinutes}")
        RainForecastBus.publish(result)
        // Recorded every cycle regardless of whether anything notification-worthy happened —
        // RainEventLog only captures state changes, so a long "still nothing nearby" stretch would
        // otherwise leave no way to tell which radar frame the last executed cycle actually saw.
        LastCycleDebug.save(this, result)

        val signal = NotificationDedup.Signal(result.etaMinutes, result.intensityMmh)
        val action = notificationDedup.evaluate(signal)
        if (action != NotificationDedup.Action.None) {
            val frameDebug = dedupBefore
                .put("latestFrameTm", result.latestFrameTm)
                .put("frameCount", result.frameCount)
                .put("lagMinutes", result.lagMinutes)
                .put("nearestRainDistanceKm", result.nearestRainDistanceKm ?: JSONObject.NULL)
                .put("etaMinutes", result.etaMinutes ?: JSONObject.NULL)
                .put("intensityMmh", result.intensityMmh ?: JSONObject.NULL)
                .put("userLat", location.lat)
                .put("userLon", location.lon)
                .put("locationCached", locationFix.cached)
                .put("locationAgeMs", locationFix.ageMs)
            val notified: Pair<String, String>? = when (action) {
                is NotificationDedup.Action.NotifyIncoming -> {
                    val message = NotificationHelper.showIncomingRainAlert(this, action.etaMinutes, result.intensityMmh)
                    RainEventLog.append(this, "INCOMING", message, frameDebug)
                    "INCOMING" to message
                }
                NotificationDedup.Action.NotifyActiveRain -> {
                    val message = NotificationHelper.showActiveRainAlert(this, result.intensityMmh)
                    RainEventLog.append(this, "ACTIVE", message, frameDebug)
                    "ACTIVE" to message
                }
                NotificationDedup.Action.NotifyRainStopped -> {
                    val message = NotificationHelper.showRainStoppedAlert(this)
                    RainEventLog.append(this, "STOPPED", message, frameDebug)
                    "STOPPED" to message
                }
                NotificationDedup.Action.ResetToIdle -> {
                    // A forecast never actually arrived — not useful enough to interrupt the user
                    // with an alert, so just log it for debugging and quietly reset to idle.
                    RainEventLog.append(this, "MISSED", "비가 오지 않고 지나갔어요 (알림 없이 초기화)", frameDebug)
                    null
                }
                NotificationDedup.Action.None -> null
            }
            // Pushed to the WebView verbatim so it never has to regenerate wording that could
            // drift from what the OS notification just showed — see docs/webview-interface.md.
            if (notified != null) {
                val (state, message) = notified
                NotificationEventBus.publish(
                    NotificationEvent(state, message, result.etaMinutes, result.intensityMmh, System.currentTimeMillis()),
                )
            }
        }

        // Refresh the ongoing notification text right away so it tracks the phase that was just
        // computed, rather than waiting for the next loop iteration's pre-cycle re-assert.
        val justStopped = action == NotificationDedup.Action.NotifyRainStopped
        ongoingContentText = NotificationHelper.ongoingTextFor(result.state, justStopped, result.etaMinutes, result.intensityMmh)
        startOngoingForeground()
    }

    companion object {
        private const val TAG = "RainMonitorService"
        private const val POLL_INTERVAL_MS = 5 * 60 * 1000L + 30_000L
        private const val ACTION_CHECK_NOW = "com.goju.ribs.myrainassist.action.CHECK_NOW"

        /** Requests an out-of-cadence forecast cycle right away, e.g. when the app comes to the foreground. */
        fun requestImmediateCheck(context: Context) {
            val intent = Intent(context, RainMonitorService::class.java).setAction(ACTION_CHECK_NOW)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
