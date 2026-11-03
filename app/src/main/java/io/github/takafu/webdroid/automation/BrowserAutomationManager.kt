package io.github.takafu.webdroid.automation

import android.util.Log
import android.webkit.WebView
import com.google.gson.Gson
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Central hub for all JavaScript execution against the WebView.
 *
 * Every asynchronous WebView operation funnels through [runJs], which uses a
 * [CountDownLatch] with a bounded timeout instead of raw Object.wait/notify.
 * This eliminates lost notifications and guarantees every operation returns an
 * explicit result or error instead of blocking indefinitely.
 */
class BrowserAutomationManager(
    internal val gson: Gson,
    private val runOnMainThread: (() -> Unit) -> Unit
) {
    private companion object {
        private const val TAG = "BrowserAutomation"
        const val DEFAULT_TIMEOUT_MS: Long = 10000L
        const val POLL_INTERVAL_MS: Long = 200L
    }

    /**
     * Execute [script] in [webView] on the Android main thread and wait (bounded)
     * for the evaluateJavascript callback. Never call from the main thread.
     */
    fun runJs(webView: WebView?, script: String, timeoutMs: Long): JsResult {
        if (webView == null) return JsResult.Error("WebView is not available")
        val latch = CountDownLatch(1)
        val completed = AtomicBoolean(false)
        var resultHolder: Any? = null
        var errorHolder: String? = null

        runOnMainThread {
            if (completed.get()) return@runOnMainThread
            try {
                webView.evaluateJavascript(script) { value ->
                    if (completed.compareAndSet(false, true)) {
                        resultHolder = value
                        latch.countDown()
                    }
                }
            } catch (e: Exception) {
                if (completed.compareAndSet(false, true)) {
                    errorHolder = e.message ?: e.javaClass.simpleName
                    latch.countDown()
                }
            }
        }

        val waited = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        if (!waited) {
            Log.w(TAG, "JavaScript operation timed out after ${timeoutMs}ms")
            return JsResult.Error("Operation timed out after ${timeoutMs}ms")
        }
        if (errorHolder != null) {
            Log.w(TAG, "JavaScript operation failed: $errorHolder")
            return JsResult.Error(errorHolder)
        }
        return JsResult.Value(resultHolder)
    }

    /**
     * Execute [script] and parse the returned JSON. Returns [JsResult.Error] on
     * timeout, WebView errors, or callback failures.
     */
    fun evalJson(webView: WebView?, script: String, timeoutMs: Long): JsResult {
        val raw = runJs(webView, script, timeoutMs)
        return when (raw) {
            is JsResult.Error -> raw
            is JsResult.Empty -> JsResult.Empty
            is JsResult.Value -> {
                val parsed = evalJsValue(gson, raw.value)
                JsResult.Value(parsed)
            }
        }
    }

    /**
     * Default timeout wrapper.
     */
    fun evalJson(webView: WebView?, script: String): JsResult {
        return evalJson(webView, script, DEFAULT_TIMEOUT_MS)
    }
}
