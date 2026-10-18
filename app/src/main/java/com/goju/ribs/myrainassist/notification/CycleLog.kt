package com.goju.ribs.myrainassist.notification

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * One line per executed poll cycle (bounded ring, app-private `cycles.jsonl`), unlike
 * [RainEventLog] which only records state changes. Its timestamps are what show whether the
 * background schedule actually held up — e.g. gaps while the phone sat in Doze — and each line
 * says what the cycle cost (download source/bytes, compute time) and what it concluded.
 * Read it with `adb shell run-as com.goju.ribs.myrainassist cat files/cycles.jsonl`.
 */
object CycleLog {

    private const val FILE_NAME = "cycles.jsonl"
    private const val MAX_ENTRIES = 500

    @Synchronized
    fun append(context: Context, entry: JSONObject) {
        val file = File(context.filesDir, FILE_NAME)
        val lines = (if (file.exists()) file.readLines().filter { it.isNotBlank() } else emptyList()).toMutableList()
        lines.add(entry.put("timestampEpochMs", System.currentTimeMillis()).toString())
        while (lines.size > MAX_ENTRIES) lines.removeAt(0)
        file.writeText(lines.joinToString("\n", postfix = "\n"))
    }
}
