package com.goju.ribs.myrainassist.webview

import android.webkit.WebView
import com.goju.ribs.myrainassist.analysis.RainForecastResult

/**
 * Native -> web push of the computed rain-cloud path vectors. The receiving `requestDrawRainPathVector`
 * JS function is expected to live on the https://www.ribs.kr/rain page; see project plan for the JSON contract.
 */
object WebBridge {
    fun pushForecastToWebView(webView: WebView, result: RainForecastResult) {
        webView.evaluateJavascript("requestDrawRainPathVector(${result.toJson()})", null)
    }
}
