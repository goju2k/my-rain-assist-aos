package com.goju.ribs.myrainassist.webview

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.goju.ribs.myrainassist.notification.NotificationHelper
import com.goju.ribs.myrainassist.service.NotificationEvent
import com.goju.ribs.myrainassist.service.NotificationEventBus
import com.goju.ribs.myrainassist.service.RainForecastBus

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

    // The service can run — and fire a real notification — for a long time with no Activity/WebView
    // alive at all (user backgrounded the app; RainMonitorService keeps polling as a foreground
    // service). If the user then taps that notification, MainActivity is created fresh and this
    // fires on the very first load: it must show the notification they just tapped, not a generic
    // idle message, or the tap would look broken. NotificationEventBus is a StateFlow, so its
    // `.value` already holds that last-fired event (if any) regardless of whether anyone was
    // collecting it when it was published — only fall back to the idle default when nothing has
    // ever fired (true first-ever launch).
    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        // Same idea as the notification fallback below: RainMonitorService may already have a
        // computed forecast (blob paths etc.) sitting in the bus from before this page load
        // existed. Push it immediately rather than waiting up to ~5.5 minutes for the next poll.
        RainForecastBus.forecast.value?.let { WebBridge.pushForecastToWebView(view, it) }
        val event = NotificationEventBus.events.value ?: NotificationEvent(
            state = "IDLE",
            message = NotificationHelper.DEFAULT_ONGOING_TEXT,
            etaMinutes = null,
            intensityMmh = null,
            timestampEpochMs = System.currentTimeMillis(),
        )
        // Firing this in the same tick as the applyForecast call above, right at cold load, has
        // been observed to lose the notification text — the page is still mid-hydration at
        // onPageFinished, and the two pushes appear to race there (calling either one alone, or
        // both once the page is idle, does not reproduce it). A short delay sidesteps the race;
        // it's a native-side workaround, not a fix — the real fix belongs on the web side.
        view.postDelayed({ WebBridge.pushNotificationToWebView(view, event) }, 300)
    }

    private companion object {
        const val ALLOWED_HOST = "www.ribs.kr"
    }
}
