package com.goju.ribs.myrainassist.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import com.goju.ribs.myrainassist.analysis.ForecastEngine
import com.goju.ribs.myrainassist.analysis.NotificationDedup
import com.goju.ribs.myrainassist.data.LatLon
import com.goju.ribs.myrainassist.data.RadarApi
import com.goju.ribs.myrainassist.notification.NotificationHelper
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

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        notificationDedup = NotificationDedup(this)

        try {
            startForeground(
                NotificationHelper.ONGOING_NOTIFICATION_ID,
                NotificationHelper.buildOngoingNotification(this),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "Missing permission to start location foreground service", e)
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

    private suspend fun pollLoop() {
        while (true) {
            runCatching { runCycle() }.onFailure { Log.w(TAG, "Poll cycle failed", it) }
            delay(POLL_INTERVAL_MS)
        }
    }

    private suspend fun runCycle() {
        val location = getCurrentLocation() ?: return
        val response = runCatching { RadarApi.fetchFrames(count = FRAME_COUNT) }
            .onFailure { Log.w(TAG, "runCycle: fetchFrames failed", it) }
            .getOrNull() ?: return
        val result = ForecastEngine.computeForecast(response, location, System.currentTimeMillis()) ?: return
        RainForecastBus.publish(result)

        val signal = NotificationDedup.Signal(result.etaMinutes, result.nearestRainDistanceKm)
        when (val action = notificationDedup.evaluate(signal)) {
            is NotificationDedup.Action.NotifyIncoming -> NotificationHelper.showIncomingRainAlert(this, action.etaMinutes)
            NotificationDedup.Action.NotifyActiveRain -> NotificationHelper.showActiveRainAlert(this)
            NotificationDedup.Action.NotifyRainStopped -> NotificationHelper.showRainStoppedAlert(this)
            NotificationDedup.Action.None -> Unit
        }
    }

    private suspend fun getCurrentLocation(): LatLon? = suspendCancellableCoroutine { cont ->
        val cancellationSource = CancellationTokenSource()
        try {
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cancellationSource.token)
                .addOnSuccessListener { location ->
                    cont.resume(location?.let { LatLon(it.latitude, it.longitude) })
                }
                .addOnFailureListener { cont.resume(null) }
        } catch (e: SecurityException) {
            cont.resume(null)
        }
        cont.invokeOnCancellation { cancellationSource.cancel() }
    }

    private companion object {
        const val TAG = "RainMonitorService"
        const val POLL_INTERVAL_MS = 5 * 60 * 1000L + 30_000L
        const val FRAME_COUNT = 6
    }
}
