package com.goju.ribs.myrainassist.ui

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.goju.ribs.myrainassist.location.LocationFixProvider
import com.goju.ribs.myrainassist.webview.RainWebChromeClient
import com.goju.ribs.myrainassist.webview.RainWebViewClient
import com.goju.ribs.myrainassist.webview.WebLocationBridge

// ?webview=true tells the page to stop running its own standalone rain-prediction logic and
// wait for the app to push results/notification text via window.RainAssistBridge instead — a
// query param (present before any page JS runs) avoids a race that a post-load interface call
// would have with the page's own init code. See docs/webview-interface.md.
private const val RAIN_URL = "https://www.ribs.kr/rain-assist?webview=true"

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RainWebViewScreen(onWebViewCreated: (WebView) -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val isDebuggable = (LocalContext.current.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    if (isDebuggable) {
        // Lets chrome://inspect attach to this WebView (Settings > System > Developer options >
        // USB debugging + a USB/adb connection) — debug builds only, never enabled in release.
        WebView.setWebContentsDebuggingEnabled(true)
    }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            val locationProvider = LocationFixProvider(context)
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.setGeolocationEnabled(true)
                addJavascriptInterface(
                    WebLocationBridge(context, scope, locationProvider) { this },
                    WebLocationBridge.INTERFACE_NAME,
                )
                webViewClient = RainWebViewClient(context)
                webChromeClient = RainWebChromeClient(context)
                loadUrl(RAIN_URL)
                onWebViewCreated(this)
            }
        },
    )
}
