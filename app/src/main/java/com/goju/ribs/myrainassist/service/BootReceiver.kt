package com.goju.ribs.myrainassist.service

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val hasCoarseLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val hasBackgroundLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasCoarseLocation || !hasBackgroundLocation) {
            Log.i(TAG, "Skipping service restart on boot: required permissions not granted")
            return
        }

        ContextCompat.startForegroundService(context, Intent(context, RainMonitorService::class.java))
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
