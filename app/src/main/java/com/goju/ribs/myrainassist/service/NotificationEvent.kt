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
    /** A "rain stopped" message old enough that it no longer describes the present. */
    fun isStaleStop(nowEpochMs: Long): Boolean = state == "STOPPED" && nowEpochMs - timestampEpochMs > STOPPED_SHOWN_MS

    fun toJson(): JSONObject = JSONObject()
        .put("state", state)
        .put("message", message)
        .put("etaMinutes", etaMinutes ?: JSONObject.NULL)
        .put("intensityMmh", intensityMmh ?: JSONObject.NULL)
        .put("timestampEpochMs", timestampEpochMs)

    companion object {
        private const val STOPPED_SHOWN_MS = 30 * 60 * 1000L

        /** The WebView's default text — nothing to report (see docs/webview-interface.md 3.2.1). */
        fun idle(timestampEpochMs: Long) = NotificationEvent(
            state = "IDLE",
            message = com.goju.ribs.myrainassist.notification.NotificationHelper.DEFAULT_ONGOING_TEXT,
            etaMinutes = null,
            intensityMmh = null,
            timestampEpochMs = timestampEpochMs,
        )
    }
}
