package io.github.takafu.webdroid.automation

import android.os.Looper
import android.util.Log
import android.webkit.WebView
import com.google.gson.Gson
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
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
 * A bounded [Semaphore] caps the number of concurrent JavaScript operations so a
 * slow page or frozen WebView cannot cause unbounded request accumulation on the
 * ~1.7 GB Galaxy A10.
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
        private const val MIN_TIMEOUT_MS: Long = 500L
        private const val MAX_TIMEOUT_MS: Long = 300000L
        // Cap concurrent JavaScript operations to protect the WebView and RAM.
        private const val MAX_CONCURRENT_JS = 4
    }

    // Bounded concurrency: acquire before running JS, release when the operation
    // finishes (success, error, timeout, or interruption).
    private val jsSemaphore = Semaphore(MAX_CONCURRENT_JS, true)

    /**
     * Error codes returned by [runJs]/[evalJson].
     */
    enum class JsErrorCode {
        WEBVIEW_UNAVAILABLE,
        MAIN_THREAD_CALL,
        SCHEDULE_FAILED,
        TIMEOUT,
        INTERRUPTED,
        JAVASCRIPT_ERROR,
        INVALID_TIMEOUT
    }

    /**
     * Bound [timeoutMs] to [MIN_TIMEOUT_MS]..[MAX_TIMEOUT_MS].
     */
    internal fun boundTimeout(timeoutMs: Long): Long {
        return timeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
    }

    /**
     * Execute [script] in [webView] on the Android main thread and wait (bounded)
     * for the evaluateJavascript callback. Never call from the main thread.
     *
     * On timeout the request is marked expired, so a late main-thread callback
     * delivered after the deadline is ignored and cannot overwrite the result.
     *
     * @return [JsResult.Error] with a structured [JsErrorCode] on any failure.
     */
    fun runJs(webView: WebView?, script: String, timeoutMs: Long): JsResult {
        if (webView == null) {
            return JsResult.Error("WebView is not available", JsErrorCode.WEBVIEW_UNAVAILABLE)
        }
        // Defensive guard: running on the main thread would deadlock because we
        // block here waiting for a callback that can only run on the main thread.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.w(TAG, "runJs must not be called from the main thread")
            return JsResult.Error("runJs must not be called from the main thread", JsErrorCode.MAIN_THREAD_CALL)
        }
        val bounded = boundTimeout(timeoutMs)
        if (bounded != timeoutMs) {
            Log.w(TAG, "Clamped timeout from ${timeoutMs}ms to ${bounded}ms")
        }

        // Reject invalid/zero/negative timeouts up front (boundTimeout clamps
        // them, but an explicit non-positive value is a programming error).
        if (timeoutMs <= 0) {
            return JsResult.Error("Invalid timeout: $timeoutMs ms (must be > 0)", JsErrorCode.INVALID_TIMEOUT)
        }

        // Acquire concurrency permit. If the WebView is busy, wait bounded so we
        // never accumulate unbounded requests.
        var acquired = false
        try {
            acquired = jsSemaphore.tryAcquire(bounded, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return JsResult.Error("Interrupted while waiting for JavaScript slot", JsErrorCode.INTERRUPTED)
        }
        if (!acquired) {
            Log.w(TAG, "JavaScript slot unavailable within ${bounded}ms; rejecting request")
            return JsResult.Error("JavaScript engine busy: too many concurrent operations", JsErrorCode.TIMEOUT)
        }

        try {
            val latch = CountDownLatch(1)
            val completed = AtomicBoolean(false)
            val expired = AtomicBoolean(false)
            val resultHolder = AtomicReference<Any?>(null)
            val errorHolder = AtomicReference<String?>(null)
            val errorCodeHolder = AtomicReference<JsErrorCode>(JsErrorCode.JAVASCRIPT_ERROR)

            var scheduleFailed = false
            var scheduleError: String? = null
            try {
                runOnMainThread {
                    if (expired.get()) return@runOnMainThread
                    try {
                        webView.evaluateJavascript(script) { value ->
                            // A late callback must not overwrite an already-final result.
                            if (completed.compareAndSet(false, true)) {
                                resultHolder.set(value)
                                latch.countDown()
                            }
                        }
                    } catch (e: Exception) {
                        if (completed.compareAndSet(false, true)) {
                            errorHolder.set(e.message ?: e.javaClass.simpleName)
                            errorCodeHolder.set(JsErrorCode.JAVASCRIPT_ERROR)
                            latch.countDown()
                        }
                    }
                }
            } catch (e: Exception) {
                // runOnMainThread itself failed to schedule (e.g. main thread looper quit).
                scheduleFailed = true
                scheduleError = e.message ?: e.javaClass.simpleName
                if (completed.compareAndSet(false, true)) {
                    errorHolder.set(scheduleError)
                    errorCodeHolder.set(JsErrorCode.SCHEDULE_FAILED)
                    latch.countDown()
                }
            }

            if (scheduleFailed && !completed.get()) {
                if (completed.compareAndSet(false, true)) {
                    errorHolder.set(scheduleError ?: "Failed to schedule JavaScript on main thread")
                    errorCodeHolder.set(JsErrorCode.SCHEDULE_FAILED)
                    latch.countDown()
                }
            }

            val waited = latch.await(bounded, TimeUnit.MILLISECONDS)
            if (!waited) {
                // Expire the request so any late main-thread callback is ignored and
                // cannot overwrite the result. Do not keep processing after timeout.
                expired.set(true)
                if (completed.compareAndSet(false, true)) {
                    errorHolder.set("Operation timed out after ${bounded}ms")
                    errorCodeHolder.set(JsErrorCode.TIMEOUT)
                }
                Log.w(TAG, "JavaScript operation timed out after ${bounded}ms")
                return JsResult.Error("Operation timed out after ${bounded}ms", JsErrorCode.TIMEOUT)
            }

            val error = errorHolder.get()
            if (error != null) {
                Log.w(TAG, "JavaScript operation failed: $error")
                return JsResult.Error(error, errorCodeHolder.get())
            }
            return JsResult.Value(resultHolder.get())
        } finally {
            // Always release the concurrency permit so the next operation can run.
            jsSemaphore.release()
        }
    }

    /**
     * Execute [script] and parse the returned JSON. Returns [JsResult.Error] on
     * timeout, WebView errors, scheduling failures, or callback failures.
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
