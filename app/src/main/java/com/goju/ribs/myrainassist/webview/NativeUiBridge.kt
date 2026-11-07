package com.goju.ribs.myrainassist.webview

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface

/**
 * Page → app calls for UI the page draws itself (docs/webview-interface.md 5). The page checks
 * `window.RainAssistNative` before using it, so older pages simply never call in and the app
 * keeps its own 기록 button.
 */
class NativeUiBridge(
    private val onOpenEventLog: () -> Unit,
    private val onWebOwnsEventLogButton: (Boolean) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())

    /** The page's 기록 button was tapped. */
    @JavascriptInterface
    fun openEventLog() {
        main.post(onOpenEventLog)
    }

    /** true: the page draws its own 기록 button, so the app hides its overlay button (which would sit on top of the page's sheet). */
    @JavascriptInterface
    fun setWebOwnsEventLogButton(owns: Boolean) {
        main.post { onWebOwnsEventLogButton(owns) }
    }

    companion object {
        const val INTERFACE_NAME = "RainAssistNative"
    }
}
