package com.goju.ribs.myrainassist.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.goju.ribs.myrainassist.data.LatLon
import com.goju.ribs.myrainassist.service.RainMonitorService

/**
 * Debug builds only — lets a connected PC drive real-device tests over adb:
 *
 *   adb shell am broadcast -n com.goju.ribs.myrainassist/.debug.DebugCommandReceiver \
 *       -a com.goju.ribs.myrainassist.debug.SET_LOCATION --es lat 35.18 --es lon 129.07
 *   ... -a com.goju.ribs.myrainassist.debug.CLEAR_LOCATION
 *   ... -a com.goju.ribs.myrainassist.debug.CHECK_NOW
 *
 * Location changes also trigger an immediate forecast cycle.
 */
class DebugCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_SET_LOCATION -> {
                val lat = intent.getStringExtra("lat")?.toDoubleOrNull()
                val lon = intent.getStringExtra("lon")?.toDoubleOrNull()
                if (lat == null || lon == null) {
                    Log.w(TAG, "SET_LOCATION needs --es lat <deg> --es lon <deg>")
                    return
                }
                DebugOverrides.setLocation(context, LatLon(lat, lon))
                Log.i(TAG, "location override set to $lat,$lon")
            }
            ACTION_CLEAR_LOCATION -> {
                DebugOverrides.setLocation(context, null)
                Log.i(TAG, "location override cleared")
            }
            ACTION_CHECK_NOW -> Log.i(TAG, "check now")
            else -> return
        }
        RainMonitorService.requestImmediateCheck(context)
    }

    private companion object {
        const val TAG = "DebugCommand"
        const val ACTION_SET_LOCATION = "com.goju.ribs.myrainassist.debug.SET_LOCATION"
        const val ACTION_CLEAR_LOCATION = "com.goju.ribs.myrainassist.debug.CLEAR_LOCATION"
        const val ACTION_CHECK_NOW = "com.goju.ribs.myrainassist.debug.CHECK_NOW"
    }
}
