package com.goju.ribs.myrainassist.notification

import android.content.Context
import java.io.File

/**
 * Where the debug logs ([RainEventLog], [CycleLog]) live: the app's external files folder, which
 * `adb` can read on a release build (`run-as` only works on debuggable builds):
 *
 *   adb pull /sdcard/Android/data/com.goju.ribs.myrainassist/files/debug/
 *
 * Nothing in the app shows these any more — the user-facing history is separate. Falls back to
 * app-private storage when external storage isn't available. A file left in the old app-private
 * spot (0.9 and earlier) is moved over on first use.
 */
object DebugFiles {

    fun file(context: Context, name: String): File {
        val dir = context.getExternalFilesDir("debug") ?: return File(context.filesDir, name)
        val target = File(dir, name)
        val legacy = File(context.filesDir, name)
        if (legacy.exists() && !target.exists()) {
            runCatching { legacy.copyTo(target) }.onSuccess { legacy.delete() }
        }
        return target
    }
}
