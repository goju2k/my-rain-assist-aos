package com.goju.ribs.myrainassist.webview

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import androidx.core.content.ContextCompat

/** Auto-grants the page's browser geolocation prompt only if the app itself already holds the OS permission. */
class RainWebChromeClient(private val context: Context) : WebChromeClient() {

    override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
        val hasLocationPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        callback.invoke(origin, hasLocationPermission, false)
    }
}
