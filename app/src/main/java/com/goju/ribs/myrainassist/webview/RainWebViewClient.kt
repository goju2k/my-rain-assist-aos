package com.goju.ribs.myrainassist.webview

import android.content.Context
import android.content.Intent
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/** Keeps navigation inside the app for the ribs.kr origin; anything else opens in an external browser. */
class RainWebViewClient(private val context: Context) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val uri = request.url
        if (uri.host == ALLOWED_HOST) return false
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    private companion object {
        const val ALLOWED_HOST = "www.ribs.kr"
    }
}
