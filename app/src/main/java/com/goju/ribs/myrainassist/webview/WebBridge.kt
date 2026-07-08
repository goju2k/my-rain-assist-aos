package com.goju.ribs.myrainassist.webview

import android.webkit.WebView
import com.goju.ribs.myrainassist.analysis.RainForecastResult
import com.goju.ribs.myrainassist.service.NotificationEvent

/**
 * Native -> web push interface for https://www.ribs.kr/rain-assist when loaded in webview mode
 * (`?webview=true`). See docs/webview-interface.md for the full contract, JSON schemas, and
 * timing guarantees this implements against.
 *
 * Every call is guarded by a `typeof` check so it's a no-op (not a thrown JS error) if the page
 * hasn't finished loading yet or hasn't implemented `window.RainAssistBridge` yet.
 */
object WebBridge {

    fun pushForecastToWebView(webView: WebView, result: RainForecastResult) {
        callBridge(webView, "applyForecast", result.toJson().toString())
    }

    fun pushNotificationToWebView(webView: WebView, event: NotificationEvent) {
        callBridge(webView, "showNotification", event.toJson().toString())
    }

    /** Called on foreground-resume in place of a full page reload — asks the page to re-locate the user and redraw in place. */
    fun requestPositionRefresh(webView: WebView) {
        callBridge(webView, "refreshPosition", argument = null)
    }

    private fun callBridge(webView: WebView, method: String, argument: String?) {
        val args = argument ?: ""
        webView.evaluateJavascript(
            "if (window.RainAssistBridge && window.RainAssistBridge.$method) { window.RainAssistBridge.$method($args); }",
            null,
        )
    }
}
