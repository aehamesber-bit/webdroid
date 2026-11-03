package io.github.takafu.webdroid.automation

import android.os.Looper
import android.util.Log
import android.webkit.WebView
import com.google.gson.Gson
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Central hub for all JavaScript execution against the WebView.
 *
 * Every asynchronous WebView operation funnels through [runJs], which uses a
 * [CountDownLatch] with a bounded timeout instead of raw Object.wait/notify.
 * This eliminates lost notifications and guarantees every operation returns an
 * explicit result or error instead of blocking indefinitely.
 *
 * Threading model:
 * - [runOnMainThread] delivers [WebView.evaluateJavascript] to the Android main
 *   thread (WebView must only be touched there).
 * - The caller must NOT be the main thread; [runJs] blocks the calling thread
 *   (an HTTP worker thread) on the latch with an explicit timeout. Calling from
 *   the main thread would deadlock and is rejected up front.
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
     *
     * On timeout the request is marked expired, so a late main-thread callback
     * delivered after the deadline is ignored and cannot overwrite the result.
     */
    fun runJs(webView: WebView?, script: String, timeoutMs: Long): JsResult {
        if (webView == null) return JsResult.Error("WebView is not available")
        // Defensive guard: running on the main thread would deadlock because
        // we block here waiting for a callback that can only run on the main thread.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.w(TAG, "runJs must not be called from the main thread")
            return JsResult.Error("runJs must not be called from the main thread")
        }
        val latch = CountDownLatch(1)
        val completed = AtomicBoolean(false)
        val resultHolder = AtomicReference<Any?>(null)
        val errorHolder = AtomicReference<String?>(null)

        runOnMainThread {
            if (completed.get()) return@runOnMainThread
            try {
                webView.evaluateJavascript(script) { value ->
                    if (completed.compareAndSet(false, true)) {
                        resultHolder.set(value)
                        latch.countDown()
                    }
                }
            } catch (e: Exception) {
                if (completed.compareAndSet(false, true)) {
                    errorHolder.set(e.message ?: e.javaClass.simpleName)
                    latch.countDown()
                }
            }
        }

        val waited = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        if (!waited) {
            // Expire the request so any late callback is ignored.
            completed.set(true)
            Log.w(TAG, "JavaScript operation timed out after ${timeoutMs}ms")
            return JsResult.Error("Operation timed out after ${timeoutMs}ms")
        }
        val error = errorHolder.get()
        if (error != null) {
            Log.w(TAG, "JavaScript operation failed: $error")
            return JsResult.Error(error)
        }
        return JsResult.Value(resultHolder.get())
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
