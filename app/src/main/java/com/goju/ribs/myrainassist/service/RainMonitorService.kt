package com.goju.ribs.myrainassist.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.goju.ribs.myrainassist.analysis.ForecastEngine
import com.goju.ribs.myrainassist.analysis.ForecastState
import com.goju.ribs.myrainassist.analysis.NotificationDedup
import com.goju.ribs.myrainassist.data.RadarRepository
import com.goju.ribs.myrainassist.location.LocationFixProvider
import com.goju.ribs.myrainassist.notification.CycleLog
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
 * Persistent foreground service that checks the radar on an adaptive cadence (every 3 minutes
 * while rain is anywhere within [ForecastEngine.ROI_RADIUS_KM], every 10 when it's all clear),
 * runs the forecast, publishes results to [RainForecastBus] for the UI, and raises notifications.
 *
 * Scheduling: a foreground service alone doesn't keep the CPU awake, so a plain coroutine delay
 * stalls while the phone sleeps (Doze). Each cycle therefore books the next one with an
 * allow-while-idle alarm (exact when the user has granted "알람 및 리마인더"), and holds a partial
 * wake lock only while a cycle is actually running. The in-process timeout is just a backup.
 */
class RainMonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pollingJob: Job? = null
    private lateinit var locationProvider: LocationFixProvider
    private lateinit var notificationDedup: NotificationDedup
    private lateinit var wakeLock: PowerManager.WakeLock
    private val radarRepository = RadarRepository()
    private var ongoingContentText: String = NotificationHelper.DEFAULT_ONGOING_TEXT

    // Conflated so a burst of triggers (alarm + app foregrounding, or pulling down and dismissing
    // the notification shade) collapses to a single extra cycle instead of piling up. The value is
    // why the cycle ran, for the cycle log.
    private val cycleTrigger = Channel<String>(Channel.CONFLATED)

    private class CycleOutcome(val nextIntervalMs: Long, val log: JSONObject)

    override fun onCreate() {
        super.onCreate()
        locationProvider = LocationFixProvider(this)
        notificationDedup = NotificationDedup(this)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyRainAssist:cycle")
            .apply { setReferenceCounted(false) }

        if (!startOngoingForeground()) {
            stopSelf()
            return
        }

        if (pollingJob == null) {
            pollingJob = scope.launch { pollLoop() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every start (alarm, app foregrounding) arrives via startForegroundService, which must be
        // answered with startForeground even when the service is already running.
        startOngoingForeground()
        when (intent?.action) {
            ACTION_ALARM -> {
                // Keep the CPU up from the alarm's delivery until the cycle it triggers is done.
                wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
                cycleTrigger.trySend("alarm")
            }
            ACTION_CHECK_NOW -> cycleTrigger.trySend("foreground")
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        if (::wakeLock.isInitialized && wakeLock.isHeld) wakeLock.release()
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
        var trigger = "start"
        while (true) {
            startOngoingForeground()
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
            val startedAt = SystemClock.elapsedRealtime()
            val outcome = runCatching { runCycle() }.getOrElse {
                Log.w(TAG, "Poll cycle failed", it)
                CycleOutcome(DEFAULT_INTERVAL_MS, JSONObject().put("error", it.toString()))
            }
            scheduleNextCycle(outcome.nextIntervalMs)
            CycleLog.append(
                this,
                outcome.log
                    .put("trigger", trigger)
                    .put("durationMs", SystemClock.elapsedRealtime() - startedAt)
                    .put("nextMin", outcome.nextIntervalMs / 60_000.0),
            )
            if (wakeLock.isHeld) wakeLock.release()
            // Normally the alarm wakes us; the timeout is only a backup in case it never fires.
            trigger = withTimeoutOrNull(outcome.nextIntervalMs + BACKUP_SLACK_MS) { cycleTrigger.receive() } ?: "timer"
        }
    }

    private fun scheduleNextCycle(delayMs: Long) {
        val alarmManager = getSystemService(AlarmManager::class.java)
        val pendingIntent = PendingIntent.getForegroundService(
            this,
            0,
            Intent(this, RainMonitorService::class.java).setAction(ACTION_ALARM),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val at = SystemClock.elapsedRealtime() + delayMs
        if (alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pendingIntent)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pendingIntent)
        }
    }

    private suspend fun runCycle(): CycleOutcome {
        val dedupBefore = notificationDedup.debugSnapshot()
        val log = JSONObject()
        val locationFix = locationProvider.getCurrentLocation()
        if (locationFix == null) {
            Log.w(TAG, "runCycle: location unavailable, skipping cycle (dedup=$dedupBefore)")
            RainEventLog.append(this, "SKIP_LOCATION", "위치 정보를 가져오지 못해 강수 체크를 건너뛰었어요", dedupBefore)
            return CycleOutcome(DEFAULT_INTERVAL_MS, log.put("skip", "location"))
        }
        val location = locationFix.location
        log.put("lat", location.lat).put("lon", location.lon).put("locCached", locationFix.cached)
        if (locationFix.cached) {
            Log.w(TAG, "runCycle: using cached location, ageMs=${locationFix.ageMs}")
        }
        val fetch = runCatching { radarRepository.fetch(location) }
            .onFailure { Log.w(TAG, "runCycle: radar fetch failed", it) }
            .getOrNull()
        if (fetch == null) {
            RainEventLog.append(this, "SKIP_FETCH", "레이더 데이터 수신 실패로 건너뛰었어요", dedupBefore)
            return CycleOutcome(DEFAULT_INTERVAL_MS, log.put("skip", "fetch"))
        }
        log.put("source", fetch.source).put("bytes", fetch.bytes)
        val response = fetch.response
        val computeStartedAt = SystemClock.elapsedRealtime()
        val result = ForecastEngine.computeForecast(response, location, System.currentTimeMillis())
        log.put("computeMs", SystemClock.elapsedRealtime() - computeStartedAt)
        if (result == null) {
            Log.d(TAG, "runCycle: computeForecast returned null (frames=${response.frames.size}, location=$location)")
            val debug = dedupBefore.put("frameCount", response.frames.size)
                .put("userLat", location.lat)
                .put("userLon", location.lon)
                .put("locationCached", locationFix.cached)
                .put("locationAgeMs", locationFix.ageMs)
            RainEventLog.append(this, "SKIP_FORECAST", "위치가 레이더 범위를 벗어나 건너뛰었어요", debug)
            return CycleOutcome(CLEAR_INTERVAL_MS, log.put("skip", "forecast"))
        }
        Log.d(TAG, "runCycle: state=${result.state} etaMinutes=${result.etaMinutes} rainEndMinutes=${result.rainEndMinutes} nearestRainDistanceKm=${result.nearestRainDistanceKm} latestFrameTm=${result.latestFrameTm} lagMinutes=${result.lagMinutes}")
        log.put("tm", result.latestFrameTm)
            .put("lag", result.lagMinutes)
            .put("state", result.state.name)
            .put("eta", result.etaMinutes ?: JSONObject.NULL)
            .put("end", result.rainEndMinutes ?: JSONObject.NULL)
            .put("nearKm", result.nearestRainDistanceKm ?: JSONObject.NULL)
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
                .put("rainEndMinutes", result.rainEndMinutes ?: JSONObject.NULL)
                .put("intensityMmh", result.intensityMmh ?: JSONObject.NULL)
                .put("userLat", location.lat)
                .put("userLon", location.lon)
                .put("locationCached", locationFix.cached)
                .put("locationAgeMs", locationFix.ageMs)
                .put("source", fetch.source)
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
            log.put("notify", action.javaClass.simpleName)
        }

        // Refresh the ongoing notification text right away so it tracks the phase that was just
        // computed, rather than waiting for the next loop iteration's pre-cycle re-assert.
        val justStopped = action == NotificationDedup.Action.NotifyRainStopped
        ongoingContentText = NotificationHelper.ongoingTextFor(result.state, justStopped, result.etaMinutes, result.intensityMmh, result.rainEndMinutes)
        startOngoingForeground()

        val rainNearby = result.nearestRainDistanceKm != null || result.state != ForecastState.NONE
        return CycleOutcome(if (rainNearby) RAIN_NEARBY_INTERVAL_MS else CLEAR_INTERVAL_MS, log)
    }

    companion object {
        private const val TAG = "RainMonitorService"
        // Radar frames arrive every 5 min; checking every 3 while rain is around keeps the
        // added delay small, and a cycle with no new frame costs only an ETag revalidation.
        private const val RAIN_NEARBY_INTERVAL_MS = 3 * 60 * 1000L
        // Nothing within ROI_RADIUS_KM: even a 100 km/h band needs 1.5 h to arrive.
        private const val CLEAR_INTERVAL_MS = 10 * 60 * 1000L
        private const val DEFAULT_INTERVAL_MS = 5 * 60 * 1000L
        private const val BACKUP_SLACK_MS = 60 * 1000L
        // Upper bound in case a cycle hangs; normal cycles take a few seconds.
        private const val WAKE_LOCK_TIMEOUT_MS = 2 * 60 * 1000L
        private const val ACTION_CHECK_NOW = "com.goju.ribs.myrainassist.action.CHECK_NOW"
        private const val ACTION_ALARM = "com.goju.ribs.myrainassist.action.ALARM"

        /** Requests an out-of-cadence forecast cycle right away, e.g. when the app comes to the foreground. */
        fun requestImmediateCheck(context: Context) {
            val intent = Intent(context, RainMonitorService::class.java).setAction(ACTION_CHECK_NOW)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
