package com.goju.ribs.myrainassist.ui

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.goju.ribs.myrainassist.location.LocationFixProvider
import com.goju.ribs.myrainassist.webview.RainWebChromeClient
import com.goju.ribs.myrainassist.webview.RainWebViewClient
import com.goju.ribs.myrainassist.webview.WebLocationBridge

private const val RAIN_URL = "https://www.ribs.kr/rain-assist"

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RainWebViewScreen(onWebViewCreated: (WebView) -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
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
