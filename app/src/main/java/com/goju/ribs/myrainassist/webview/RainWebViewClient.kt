package com.goju.ribs.myrainassist.webview

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/** Keeps navigation inside the app for the ribs.kr origin; anything else opens in an external browser. */
class RainWebViewClient(private val context: Context) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val uri = request.url
        if (uri.host == ALLOWED_HOST) return false
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    // Runs as the main frame starts loading, before the page's own scripts execute, so the
    // geolocation override is in place by the time page JS calls navigator.geolocation.
    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        view.evaluateJavascript(WebLocationBridge.GEOLOCATION_OVERRIDE_SCRIPT, null)
    }

    private companion object {
        const val ALLOWED_HOST = "www.ribs.kr"
    }
}
