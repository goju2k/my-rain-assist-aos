package com.goju.ribs.myrainassist.location

import android.content.Context
import android.location.Location
import android.util.Log
import com.goju.ribs.myrainassist.data.LatLon
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** [ageMs] is 0 for a fresh fix; for a [cached] fix it's how long ago the OS last recorded it. */
data class LocationFix(val location: LatLon, val cached: Boolean, val ageMs: Long)

/**
 * Shared location lookup used by both the background poller and the WebView's geolocation bridge.
 * A fresh fix can fail indoors (no GPS/Wi-Fi) even though the user hasn't moved, so this falls back
 * to the OS's last-known fix rather than reporting no location at all, as long as it isn't so old
 * that the user may have since moved elsewhere.
 */
class LocationFixProvider(context: Context) {

    private val fusedLocationClient: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)

    suspend fun getCurrentLocation(): LocationFix? {
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
        const val TAG = "LocationFixProvider"
        const val MAX_CACHED_LOCATION_AGE_MS = 6 * 60 * 60 * 1000L
    }
}
