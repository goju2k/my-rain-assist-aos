package com.goju.ribs.myrainassist.debug

import android.content.Context
import android.content.pm.ApplicationInfo
import com.goju.ribs.myrainassist.data.LatLon

/**
 * Test-only knobs, honored only in debuggable builds (set via adb through the debug-only
 * DebugCommandReceiver, see DEVELOPMENT.md). Release builds never read them.
 */
object DebugOverrides {

    private const val PREFS_NAME = "debug_overrides"
    private const val KEY_LAT = "lat"
    private const val KEY_LON = "lon"

    private fun isDebuggable(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** A fixed location to use instead of the device's real one, or null. */
    fun location(context: Context): LatLon? {
        if (!isDebuggable(context)) return null
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_LAT) || !prefs.contains(KEY_LON)) return null
        return LatLon(prefs.getFloat(KEY_LAT, 0f).toDouble(), prefs.getFloat(KEY_LON, 0f).toDouble())
    }

    fun setLocation(context: Context, location: LatLon?) {
        val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        if (location == null) {
            editor.remove(KEY_LAT).remove(KEY_LON)
        } else {
            editor.putFloat(KEY_LAT, location.lat.toFloat()).putFloat(KEY_LON, location.lon.toFloat())
        }
        editor.apply()
    }
}
