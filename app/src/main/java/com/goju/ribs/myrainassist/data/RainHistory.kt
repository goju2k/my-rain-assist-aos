package com.goju.ribs.myrainassist.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One spell of rain/snow at the user's location, for the user-facing history screen. [endMs] is
 * null while it's still going. A [missed] entry is a forecast that never arrived (start = end =
 * when the app gave up on it).
 */
data class RainEpisode(
    val startMs: Long,
    val endMs: Long?,
    /** Last cycle that still saw it overhead — where an episode is closed if the stop was never seen. */
    val lastSeenMs: Long,
    val kind: PrecipKind,
    val missed: Boolean = false,
)

/**
 * Turns each monitoring cycle into history entries. Pure (no Android) so it's unit-tested; the
 * service feeds it, [RainHistoryStore] persists the result.
 */
object RainHistoryRecorder {

    const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000

    // An open episode that nothing has confirmed for this long (service killed, phone off, the
    // stop alert suppressed) is closed at its last confirmed cycle rather than left running.
    const val STALE_OPEN_MS = 20L * 60 * 1000

    /**
     * @param active the forecast says it's raining/snowing here now
     * @param stopped the app just decided it stopped (the "그쳤어요" alert)
     * @param missed the app just dropped a forecast that never arrived
     */
    fun update(
        episodes: List<RainEpisode>,
        nowMs: Long,
        active: Boolean,
        stopped: Boolean,
        missed: Boolean,
        kind: PrecipKind,
    ): List<RainEpisode> {
        val out = episodes.filter { (it.endMs ?: it.lastSeenMs) >= nowMs - RETENTION_MS }.toMutableList()
        var openIndex = out.indexOfLast { it.endMs == null && !it.missed }
        if (openIndex >= 0 && nowMs - out[openIndex].lastSeenMs > STALE_OPEN_MS) {
            out[openIndex] = out[openIndex].copy(endMs = out[openIndex].lastSeenMs)
            openIndex = -1
        }
        when {
            active && openIndex < 0 -> out += RainEpisode(nowMs, null, nowMs, kind)
            active -> out[openIndex] = out[openIndex].copy(lastSeenMs = nowMs)
            stopped && openIndex >= 0 -> out[openIndex] = out[openIndex].copy(endMs = nowMs)
            missed && openIndex < 0 -> out += RainEpisode(nowMs, nowMs, nowMs, kind, missed = true)
        }
        return out
    }
}

/** App-private JSON file behind the history screen. Small: a month of episodes. */
object RainHistoryStore {

    private const val FILE_NAME = "rain_history.json"

    @Synchronized
    fun read(context: Context): List<RainEpisode> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                RainEpisode(
                    startMs = o.getLong("startMs"),
                    endMs = if (o.isNull("endMs")) null else o.getLong("endMs"),
                    lastSeenMs = o.getLong("lastSeenMs"),
                    // 0.10.0 stored a "snow" flag; later builds store the kind name.
                    kind = if (o.has("kind")) PrecipKind.fromName(o.optString("kind")) else if (o.optBoolean("snow")) PrecipKind.SNOW else PrecipKind.RAIN,
                    missed = o.optBoolean("missed"),
                )
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun record(context: Context, nowMs: Long, active: Boolean, stopped: Boolean, missed: Boolean, kind: PrecipKind) {
        val before = read(context)
        val after = RainHistoryRecorder.update(before, nowMs, active, stopped, missed, kind)
        if (after == before) return
        val array = JSONArray()
        after.forEach { e ->
            array.put(
                JSONObject()
                    .put("startMs", e.startMs)
                    .put("endMs", e.endMs ?: JSONObject.NULL)
                    .put("lastSeenMs", e.lastSeenMs)
                    .put("kind", e.kind.name)
                    .put("missed", e.missed),
            )
        }
        File(context.filesDir, FILE_NAME).writeText(array.toString())
    }
}
