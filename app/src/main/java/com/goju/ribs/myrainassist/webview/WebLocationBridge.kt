package com.goju.ribs.myrainassist.webview

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.content.ContextCompat
import com.goju.ribs.myrainassist.location.LocationFixProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Answers the page's `navigator.geolocation` calls (see [GEOLOCATION_OVERRIDE_SCRIPT]) with the
 * same fused-location fix (with cached-location fallback) the background poller uses, instead of
 * WebView's own built-in geolocation implementation, which is frequently blocked or slow to
 * resolve depending on connectivity and OEM restrictions.
 */
class WebLocationBridge(
    private val context: Context,
    private val scope: CoroutineScope,
    private val locationProvider: LocationFixProvider,
    private val webViewRef: () -> WebView?,
) {
    @JavascriptInterface
    fun requestLocation(callbackId: String) {
        Log.d(TAG, "requestLocation: callbackId=$callbackId")
        scope.launch(Dispatchers.Default) {
            val hasPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) {
                Log.w(TAG, "requestLocation: permission not granted")
                respondError(callbackId, PERMISSION_DENIED, "location permission not granted")
                return@launch
            }

            val fix = locationProvider.getCurrentLocation()
            Log.d(TAG, "requestLocation: resolved fix=$fix")
            if (fix == null) {
                respondError(callbackId, POSITION_UNAVAILABLE, "location unavailable")
            } else {
                respondSuccess(callbackId, fix.location.lat, fix.location.lon)
            }
        }
    }

    private suspend fun respondSuccess(callbackId: String, lat: Double, lon: Double) {
        evaluateOnMain("window.__nativeLocationSuccess('$callbackId', $lat, $lon)")
    }

    private suspend fun respondError(callbackId: String, code: Int, message: String) {
        evaluateOnMain("window.__nativeLocationError('$callbackId', $code, '$message')")
    }

    private suspend fun evaluateOnMain(script: String) {
        withContext(Dispatchers.Main) {
            webViewRef()?.evaluateJavascript(script, null)
        }
    }

    companion object {
        private const val TAG = "WebLocationBridge"
        const val INTERFACE_NAME = "AndroidLocationBridge"
        private const val PERMISSION_DENIED = 1
        private const val POSITION_UNAVAILABLE = 2

        /**
         * Overrides the standard Geolocation API to route through [INTERFACE_NAME] instead of
         * WebView's built-in implementation. Must be evaluated before the page's own scripts call
         * `navigator.geolocation`, so it's injected from [RainWebViewClient.onPageStarted].
         */
        val GEOLOCATION_OVERRIDE_SCRIPT = """
            (function() {
                if (!window.$INTERFACE_NAME || !navigator.geolocation) return;
                var pending = {};
                var nextId = 0;
                window.__nativeLocationSuccess = function(id, lat, lon) {
                    var cb = pending[id];
                    if (!cb) return;
                    delete pending[id];
                    cb.success({
                        coords: {
                            latitude: lat,
                            longitude: lon,
                            accuracy: 50,
                            altitude: null,
                            altitudeAccuracy: null,
                            heading: null,
                            speed: null,
                        },
                        timestamp: Date.now(),
                    });
                };
                window.__nativeLocationError = function(id, code, message) {
                    var cb = pending[id];
                    if (!cb) return;
                    delete pending[id];
                    if (cb.error) cb.error({ code: code, message: message });
                };
                function requestOnce(success, error) {
                    var id = 'loc_' + (nextId++);
                    pending[id] = { success: success, error: error };
                    $INTERFACE_NAME.requestLocation(id);
                }
                navigator.geolocation.getCurrentPosition = function(success, error) {
                    requestOnce(success, error);
                };
                navigator.geolocation.watchPosition = function(success, error) {
                    var watchId = setInterval(function() { requestOnce(success, error); }, 60000);
                    requestOnce(success, error);
                    return watchId;
                };
                navigator.geolocation.clearWatch = function(id) {
                    clearInterval(id);
                };
            })();
        """.trimIndent()
    }
}
