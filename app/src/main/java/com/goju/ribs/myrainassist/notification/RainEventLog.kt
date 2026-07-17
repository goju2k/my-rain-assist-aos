package com.goju.ribs.myrainassist.notification

import android.content.Context
import com.goju.ribs.myrainassist.service.NotificationEvent
import org.json.JSONObject
import java.io.File

data class RainEventLogEntry(
    val timestampEpochMs: Long,
    val state: String,
    val message: String,
    /** Frame/location/dedup diagnostics captured at the moment this entry was recorded, if any. */
    val debug: JSONObject? = null,
)

/**
 * Persists a bounded history of rain notification events to a JSON-lines file in app-private
 * storage, so they can be reviewed later even though the background service can't be watched live.
 */
object RainEventLog {

    private const val FILE_NAME = "rain_events.jsonl"
    private const val MAX_ENTRIES = 200

    @Synchronized
    fun append(context: Context, state: String, message: String, debug: JSONObject? = null) {
        val entry = JSONObject()
            .put("timestampEpochMs", System.currentTimeMillis())
            .put("state", state)
            .put("message", message)
        if (debug != null) entry.put("debug", debug)

        val file = File(context.filesDir, FILE_NAME)
        val lines = (if (file.exists()) file.readLines() else emptyList()).toMutableList()
        lines.add(entry.toString())
        while (lines.size > MAX_ENTRIES) lines.removeAt(0)
        file.writeText(lines.joinToString("\n") { it } + "\n")
    }

    @Synchronized
    fun clear(context: Context) {
        File(context.filesDir, FILE_NAME).delete()
    }

    private val WEBVIEW_NOTIFIABLE_STATES = setOf("INCOMING", "ACTIVE", "STOPPED")

    /**
     * Reconstructs the last real notification from disk, for a fresh WebView load in a process
     * that never (re-)fired one in memory — e.g. the app process was killed (backgrounded too long,
     * force-stopped, low-memory reclaim) and, since restarting, conditions haven't materially
     * changed enough to fire a new alert. Without this, the WebView would fall back to the generic
     * "감지하고 있어요" idle text even while the last real alert (still accurate) sits unseen on disk.
     */
    @Synchronized
    fun lastNotifiedEvent(context: Context): NotificationEvent? {
        val entry = readAll(context).firstOrNull { it.state in WEBVIEW_NOTIFIABLE_STATES } ?: return null
        val debug = entry.debug
        return NotificationEvent(
            state = entry.state,
            message = entry.message,
            etaMinutes = debug?.optNullableDouble("etaMinutes")?.toInt(),
            intensityMmh = debug?.optNullableDouble("intensityMmh"),
            timestampEpochMs = entry.timestampEpochMs,
        )
    }

    private fun JSONObject.optNullableDouble(key: String): Double? = if (isNull(key)) null else optDouble(key)

    @Synchronized
    fun readAll(context: Context): List<RainEventLogEntry> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return emptyList()
        return file.readLines()
            .mapNotNull { line ->
                if (line.isBlank()) return@mapNotNull null
                runCatching {
                    val json = JSONObject(line)
                    RainEventLogEntry(
                        timestampEpochMs = json.getLong("timestampEpochMs"),
                        state = json.getString("state"),
                        message = json.getString("message"),
                        debug = json.optJSONObject("debug"),
                    )
                }.getOrNull()
            }
            .sortedByDescending { it.timestampEpochMs }
    }
}
