package com.goju.ribs.myrainassist.service

import org.json.JSONObject

/**
 * The exact content of a rain notification the OS just showed (or a stop/retract in lieu of one).
 * Pushed to the WebView verbatim via [com.goju.ribs.myrainassist.webview.WebBridge.pushNotificationToWebView]
 * so the page never has to regenerate wording that could drift from what the user already saw in
 * the notification shade.
 */
data class NotificationEvent(
    val state: String,
    val message: String,
    val etaMinutes: Int?,
    val intensityMmh: Double?,
    val timestampEpochMs: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("state", state)
        .put("message", message)
        .put("etaMinutes", etaMinutes ?: JSONObject.NULL)
        .put("intensityMmh", intensityMmh ?: JSONObject.NULL)
        .put("timestampEpochMs", timestampEpochMs)
}
