package com.realtimetranspose.browser

import android.webkit.JavascriptInterface

/**
 * Exposed to the WebView's page JS as `window.TransposeBridge`. Only accepts
 * plain strings for logging/state-reporting — no side effects beyond updating
 * [BrowserProbeBus], so the usual addJavascriptInterface reflection risk
 * (relevant pre-API 17 anyway; this app targets 26+) doesn't apply here.
 */
class TransposeJsBridge {

    @JavascriptInterface
    fun onHookResult(result: String) {
        BrowserProbeBus.reportHookResult(result)
    }

    @JavascriptInterface
    fun onLevel(levelStr: String, contextState: String) {
        BrowserProbeBus.reportLevel(levelStr.toFloatOrNull() ?: 0f, contextState)
    }

    /** Called by the ad-block script every time it actually strips something. */
    @JavascriptInterface
    fun onAdBlockEvent(tag: String) {
        BrowserProbeBus.reportAdBlockEvent(tag)
    }
}
