package io.github.takafu.webdroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import fi.iki.elonen.NanoHTTPD
import io.github.takafu.webdroid.automation.*
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

sealed class BodyParseResult {
    data class Success(val params: Map<String, Any>) : BodyParseResult()
    object Invalid : BodyParseResult()
}

class AutomationService : Service() {

    companion object {
        private const val TAG = "AutomationService"
        private const val PORT = 8765
        private const val ACTION_STOP_SERVICES = "io.github.takafu.webdroid.STOP_SERVICES"
        private var server: AutomationServer? = null
        private val gson = Gson()

        // Event callbacks
        fun onPageEvent(event: String, data: String) {
            Log.d(TAG, "Page event: $event - $data")
        }

        fun onConsoleMessage(message: String) {
            Log.d(TAG, "Console: $message")
        }

        fun onProgressChanged(progress: Int) {
            // Progress update
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                1,
                createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(1, createNotification())
        }

        startServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICES) {
            stopService(Intent(this, FloatingBubbleService::class.java))
            stopSelf()
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "browser_automation",
                "Browser Automation",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "HTTP server for browser automation"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, AutomationService::class.java).apply {
            action = ACTION_STOP_SERVICES
        }
        val stopPendingIntent = android.app.PendingIntent.getService(
            this, 0, stopIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "browser_automation")
                .setContentTitle("Browser Automation")
                .setContentText("HTTP Server running on port $PORT")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setDeleteIntent(stopPendingIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
                .build()
        } else {
            Notification.Builder(this)
                .setContentTitle("Browser Automation")
                .setContentText("HTTP Server running on port $PORT")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setDeleteIntent(stopPendingIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
                .build()
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopServer()
    }

    private fun startServer() {
        try {
            server = AutomationServer()
            server?.start()
            Log.i(TAG, "HTTP Server started on port $PORT")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start server", e)
        }
    }

    private fun stopServer() {
        server?.stop()
        server = null
    }

    inner class AutomationServer : NanoHTTPD(PORT) {

        private val automation = BrowserAutomationManager(AutomationService.gson) { action ->
            Handler(Looper.getMainLooper()).post(action)
        }
        private val dom = DomInteractionHandler(automation)
        private val pages = PageExtractor(automation)
        private val screenshots = ScreenshotManager()

        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            val method = session.method

            Log.d(TAG, "Request: $method $uri")

            return when {
                uri == "/navigate" && method == Method.POST -> handleNavigate(session)
                uri == "/execute" && method == Method.POST -> handleExecute(session)
                uri == "/eval" && method == Method.POST -> handleEval(session)
                uri == "/screenshot" && method == Method.GET -> handleScreenshot(session)
                uri == "/click" && method == Method.POST -> handleClick(session)
                uri == "/type" && method == Method.POST -> handleType(session)
                uri == "/wait" && method == Method.POST -> handleWait(session)
                uri == "/find" && method == Method.POST -> handleFind(session)
                uri == "/extract" && method == Method.POST -> handleExtract(session)
                uri == "/scroll" && method == Method.POST -> handleScroll(session)
                uri == "/links" && method == Method.GET -> handleLinks(session)
                uri == "/forms" && method == Method.GET -> handleForms(session)
                uri == "/page-state" && method == Method.GET -> handlePageState(session)
                uri == "/accessibility" && method == Method.GET -> handleAccessibility(session)
                uri == "/back" && method == Method.POST -> handleBack()
                uri == "/forward" && method == Method.POST -> handleForward()
                uri == "/refresh" && method == Method.POST -> handleRefresh()
                uri == "/url" && method == Method.GET -> handleGetUrl()
                uri == "/title" && method == Method.GET -> handleGetTitle()
                uri == "/html" && method == Method.GET -> handleGetHtml()
                uri == "/ping" && method == Method.GET -> handlePing()
                uri == "/bubble/start" && method == Method.POST -> handleStartBubble()
                uri == "/bubble/stop" && method == Method.POST -> handleStopBubble()
                uri == "/bubble/minimize" && method == Method.POST -> handleMinimizeBubble()
                // UserAgent preset endpoints
                uri == "/ua" && method == Method.GET -> handleGetUa()
                uri == "/ua/default" && method == Method.POST -> handleSetUaDefault()
                uri == "/ua/google-login" && method == Method.POST -> handleSetUaGoogleLogin()
                uri == "/ua/custom" && method == Method.POST -> handleSetUaCustom(session)
                else -> newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    "application/json",
                    """{"success":false,"error":{"code":"NOT_FOUND","message":"Endpoint not found"}}"""
                )
            }
        }

        // ------------------------------------------------------------------
        // Navigation (existing behavior preserved)
        // ------------------------------------------------------------------

        private fun handleNavigate(session: IHTTPSession): Response {
            val parse = parseBody(session)
            if (parse is BodyParseResult.Invalid) {
                return automationErr("INVALID_PARAMETER", "Invalid or missing JSON body", Response.Status.BAD_REQUEST)
            }
            val params = (parse as BodyParseResult.Success).params
            val url = params["url"] as? String

            return if (url != null && url.isNotBlank()) {
                runOnMainThread {
                    BrowserActivity.webView?.loadUrl(url)
                }
                successResponse("Navigating to $url")
            } else {
                errorResponse("Missing 'url' parameter")
            }
        }

        private fun handleExecute(session: IHTTPSession): Response {
            val parse = parseBody(session)
            if (parse is BodyParseResult.Invalid) {
                return automationErr("INVALID_PARAMETER", "Invalid or missing JSON body", Response.Status.BAD_REQUEST)
            }
            val params = (parse as BodyParseResult.Success).params
            val script = params["script"] as? String

            return if (script != null && script.isNotBlank()) {
                runOnMainThread {
                    BrowserActivity.webView?.evaluateJavascript(script, null)
                }
                successResponse("Script executed")
            } else {
                errorResponse("Missing 'script' parameter")
            }
        }

        // ------------------------------------------------------------------
        // JavaScript evaluation with bounded CountDownLatch sync.
        // ------------------------------------------------------------------

        private fun handleEval(session: IHTTPSession): Response {
            val parse = parseBody(session)
            if (parse is BodyParseResult.Invalid) {
                return automationErr("INVALID_PARAMETER", "Invalid or missing JSON body", Response.Status.BAD_REQUEST)
            }
            val params = (parse as BodyParseResult.Success).params
            val script = params["script"] as? String
            val timeout = parseTimeout(params, 5000L)

            return if (script != null && script.isNotBlank()) {
                val result = automation.evalJson(BrowserActivity.webView, script, timeout)
                jsResultToAutomationResponse(result)
            } else {
                errorResponse("Missing 'script' parameter")
            }
        }

        private fun handleGetUrl(): Response {
            val webView = BrowserActivity.webView ?: return successResponse("about:blank", "url" to null)
            val result = automation.evalJson(webView, "location.href", 3000)
            return when (result) {
                is JsResult.Error -> automationErr(jsErrorCodeToApiCode(result.code), result.message)
                is JsResult.Value -> successResponse(result.value?.toString() ?: "about:blank", "url" to result.value)
                is JsResult.Empty -> successResponse("about:blank", "url" to null)
            }
        }

        private fun handleGetTitle(): Response {
            val webView = BrowserActivity.webView ?: return successResponse("", "title" to null)
            val result = automation.evalJson(webView, "document.title", 3000)
            return when (result) {
                is JsResult.Error -> automationErr(jsErrorCodeToApiCode(result.code), result.message)
                is JsResult.Value -> successResponse(result.value?.toString() ?: "", "title" to result.value)
                is JsResult.Empty -> successResponse("", "title" to null)
            }
        }

        private fun handleGetHtml(): Response {
            val webView = BrowserActivity.webView ?: return successResponse("HTML retrieved", "html" to null)
            val result = automation.evalJson(webView, "document.documentElement.outerHTML", 5000)
            return when (result) {
                is JsResult.Error -> automationErr(jsErrorCodeToApiCode(result.code), result.message)
                is JsResult.Value -> successResponse("HTML retrieved", "html" to result.value)
                is JsResult.Empty -> successResponse("HTML retrieved", "html" to null)
            }
        }

        // ------------------------------------------------------------------
        // Screenshot (memory-efficient, actual WebView dimensions)
        // ------------------------------------------------------------------

        private fun handleScreenshot(session: IHTTPSession): Response {
            val query = session.getParms()
            val format = query["format"] ?: "jpeg"
            val quality = parseInt(query["quality"], 75).coerceIn(0, 100)
            val scale = parseFloat(query["scale"], 1.0f)
            val maxWidth = parseInt(query["max_width"], 720).coerceIn(1, 2048)
            val maxHeight = parseInt(query["max_height"], 1280).coerceIn(1, 2048)

            if (scale.isNaN() || scale.isInfinite() || scale <= 0f) {
                return automationErr("INVALID_PARAMETER", "Invalid scale: must be a positive finite number")
            }

            val options = ScreenshotOptions(format, quality, scale, maxWidth, maxHeight)
            val webView = BrowserActivity.webView

            if (webView == null) {
                return automationErr("WEBVIEW_UNAVAILABLE", "WebView is not available")
            }

            var screenshot: ScreenshotResult? = null
            var captureError: String? = null
            val latch = CountDownLatch(1)

            runOnMainThread {
                try {
                    screenshot = screenshots.capture(webView, options)
                    if (screenshot == null) {
                        captureError = "Failed to capture screenshot"
                    }
                } catch (e: IllegalArgumentException) {
                    captureError = e.message ?: "Invalid screenshot options"
                } catch (e: IllegalStateException) {
                    captureError = e.message ?: "WebView not ready"
                } catch (e: Exception) {
                    captureError = e.message ?: e.javaClass.simpleName
                    Log.e(TAG, "Screenshot failed", e)
                } finally {
                    latch.countDown()
                }
            }

            val waited = latch.await(15000, TimeUnit.MILLISECONDS)
            if (!waited) {
                return automationErr("TIMEOUT", "Screenshot capture timed out")
            }
            if (captureError != null) {
                return automationErr("SCREENSHOT_FAILED", captureError)
            }
            if (screenshot == null) {
                return automationErr("WEBVIEW_UNAVAILABLE", "WebView is not available")
            }

            return automationOk(mapOf(
                "screenshot" to screenshot.base64,
                "format" to screenshot.format,
                "quality" to screenshot.quality,
                "dimensions" to mapOf("width" to screenshot.width, "height" to screenshot.height),
                "size_bytes" to screenshot.sizeBytes,
                "requested" to mapOf("max_width" to maxWidth, "max_height" to maxHeight)
            ))
        }

        // ------------------------------------------------------------------
        // DOM interaction endpoints
        // ------------------------------------------------------------------

        private fun handleClick(session: IHTTPSession): Response {
            val parse = parseBody(session)
            if (parse is BodyParseResult.Invalid) {
                return automationErr("INVALID_PARAMETER", "Invalid or missing JSON body", Response.Status.BAD_REQUEST)
            }
            val params = (parse as BodyParseResult.Success).params
            val selector = params["selector"] as? String
            if (selector == null || selector.isBlank()) {
                return automationErr("MISSING_PARAMETER", "Missing 'selector' parameter")
            }
            val index = parseNumber(params["index"], 0).coerceAtLeast(0)
            val timeout = parseTimeout(params, automation.DEFAULT_TIMEOUT_MS)
            val result = dom.click(BrowserActivity.webView, selector, index, timeout)
            return jsResultToAutomationResponse(result)
        }

        private fun handleType(session: IHTTPSession): Response {
            val parse = parseBody(session)
            if (parse is BodyParseResult.Invalid) {
                return automationErr("INVALID_PARAMETER", "Invalid or missing JSON body", Response.Status.BAD_REQUEST)
            }
            val params = (parse as BodyParseResult.Success).params
            val selector = params["selector"] as? String
            if (selector == null || selector.isBlank()) {
                return automationErr("MISSING_PARAMETER", "Missing 'selector' parameter")
            }
            val text = params["text"] as? String ?: ""
            val clear = params["clear"] == true
            val timeout = parseTimeout(params, automation.DEFAULT_TIMEOUT_MS)
            val result = dom.type(BrowserActivity.webView, selector, text, clear, timeout)
            return jsResultToAutomationResponse(result)
        }

        private fun handleWait(session: IHTTPSession): Response {
            val parse = parseBody(session)
            if (parse is BodyParseResult.Invalid) {
                return automationErr("INVALID_PARAMETER", "Invalid or missing JSON body", Response.Status.BAD_REQUEST)
            }
            val params = (parse as BodyParseResult.Success).params
            val selector = params["selector"] as? String
            if (selector == null || selector.isBlank()) {
                return automationErr("MISSING_PARAMETER", "Missing 'selector' parameter")
            }
            val state = params["state"] as? String ?: "present"
            val timeout = parseTimeout(params, automation.DEFAULT_TIMEOUT_MS).coerceAtLeast(1000).coerceAtMost(120000)
            val result = dom.wait(BrowserActivity.webView, selector, state, timeout)
            return jsResultToAutomationResponse(result)
        }

        private fun handleFind(session: IHTTPSession): Response {
            val parse = parseBody(session)
            if (parse is BodyParseResult.Invalid) {
                return automationErr("INVALID_PARAMETER", "Invalid or missing JSON body", Response.Status.BAD_REQUEST)
            }
            val params = (parse as BodyParseResult.Success).params
            val selector = params["selector"] as? String
            if (selector == null || selector.isBlank()) {
                return automationErr("MISSING_PARAMETER", "Missing 'selector' parameter")
            }
            val all = params["all"] == true
            val limit = parseNumber(params["limit"], 50).coerceAtLeast(1).coerceAtMost(500)
            val timeout = parseTimeout(params, automation.DEFAULT_TIMEOUT_MS)
            val result = dom.find(BrowserActivity.webView, selector, all, limit.toInt(), timeout)
            return jsResultToAutomationResponse(result)
        }

        private fun handleExtract(session: IHTTPSession): Response {
            val parse = parseBody(session)
            if (parse is BodyParseResult.Invalid) {
                return automationErr("INVALID_PARAMETER", "Invalid or missing JSON body", Response.Status.BAD_REQUEST)
            }
            val params = (parse as BodyParseResult.Success).params
            val selector = params["selector"] as? String
            if (selector == null || selector.isBlank()) {
                return automationErr("MISSING_PARAMETER", "Missing 'selector' parameter")
            }
            val fields = params["fields"] as? List<*> ?: emptyList()
            val all = params["all"] == true
            val limit = parseNumber(params["limit"], 50).coerceAtLeast(1).coerceAtMost(500)
            val timeout = parseTimeout(params, automation.DEFAULT_TIMEOUT_MS)
            val result = pages.extract(BrowserActivity.webView, selector, fields, all, limit.toInt(), timeout)
            return jsResultToAutomationResponse(result)
        }

        private fun handleScroll(session: IHTTPSession): Response {
            val parse = parseBody(session)
            if (parse is BodyParseResult.Invalid) {
                return automationErr("INVALID_PARAMETER", "Invalid or missing JSON body", Response.Status.BAD_REQUEST)
            }
            val params = (parse as BodyParseResult.Success).params
            val direction = params["direction"] as? String
            if (direction == null || direction.isBlank()) {
                return automationErr("MISSING_PARAMETER", "Missing 'direction' parameter")
            }
            val amount = parseNumber(params["amount"], 300).coerceAtLeast(1)
            val selector = params["selector"] as? String
            val timeout = parseTimeout(params, automation.DEFAULT_TIMEOUT_MS)
            val result = dom.scroll(BrowserActivity.webView, direction, amount.toInt(), selector, timeout)
            return jsResultToAutomationResponse(result)
        }

        // ------------------------------------------------------------------
        // Read-only page inspection endpoints
        // ------------------------------------------------------------------

        private fun handleLinks(session: IHTTPSession): Response {
            val query = session.getParms()
            val limit = parseInt(query["limit"], 0).coerceAtLeast(0).coerceAtMost(500)
            val result = pages.links(BrowserActivity.webView, limit, 5000)
            return jsResultToAutomationResponse(result)
        }

        private fun handleForms(session: IHTTPSession): Response {
            val result = pages.forms(BrowserActivity.webView, 5000)
            return jsResultToAutomationResponse(result)
        }

        private fun handlePageState(session: IHTTPSession): Response {
            val result = pages.pageState(BrowserActivity.webView, 5000)
            return jsResultToAutomationResponse(result)
        }

        private fun handleAccessibility(session: IHTTPSession): Response {
            val query = session.getParms()
            val depth = parseInt(query["depth"], 6).coerceIn(1, 12)
            val limit = parseInt(query["limit"], 200).coerceIn(1, 1000)
            val result = pages.accessibility(BrowserActivity.webView, depth, limit, 5000)
            return jsResultToAutomationResponse(result)
        }

        // ------------------------------------------------------------------
        // Navigation history (existing behavior preserved)
        // ------------------------------------------------------------------

        private fun handleBack(): Response {
            runOnMainThread {
                BrowserActivity.webView?.goBack()
            }
            return successResponse("Navigated back")
        }

        private fun handleForward(): Response {
            runOnMainThread {
                BrowserActivity.webView?.goForward()
            }
            return successResponse("Navigated forward")
        }

        private fun handleRefresh(): Response {
            runOnMainThread {
                BrowserActivity.webView?.reload()
            }
            return successResponse("Page refreshed")
        }

        // ------------------------------------------------------------------
        // Utility / bubble / UA (existing behavior preserved)
        // ------------------------------------------------------------------

        private fun handlePing(): Response {
            return successResponse("pong", "status" to "ok")
        }

        private fun handleStartBubble(): Response {
            val intent = Intent(applicationContext, FloatingBubbleService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(intent)
            } else {
                applicationContext.startService(intent)
            }
            return successResponse("Floating bubble started")
        }

        private fun handleStopBubble(): Response {
            val intent = Intent(applicationContext, FloatingBubbleService::class.java)
            applicationContext.stopService(intent)
            return successResponse("Floating bubble stopped")
        }

        private fun handleMinimizeBubble(): Response {
            FloatingBubbleService.minimizeWindow()
            return successResponse("Window minimized to bubble")
        }

        private fun handleGetUa(): Response {
            val mode = FloatingBubbleService.getUaMode()
            val ua = FloatingBubbleService.getCurrentUa()
            return successResponse("Current UA mode: $mode",
                "mode" to mode,
                "userAgent" to ua
            )
        }

        private fun handleSetUaDefault(): Response {
            FloatingBubbleService.setUaMode(FloatingBubbleService.UA_MODE_DEFAULT)
            return successResponse("UA set to default (Desktop Chrome)",
                "mode" to FloatingBubbleService.UA_MODE_DEFAULT,
                "userAgent" to FloatingBubbleService.getCurrentUa()
            )
        }

        private fun handleSetUaGoogleLogin(): Response {
            FloatingBubbleService.setUaMode(FloatingBubbleService.UA_MODE_GOOGLE_LOGIN)
            return successResponse("UA set to google-login (Android Chrome + WebView bypass)",
                "mode" to FloatingBubbleService.UA_MODE_GOOGLE_LOGIN,
                "userAgent" to FloatingBubbleService.getCurrentUa()
            )
        }

        private fun handleSetUaCustom(session: IHTTPSession): Response {
            val parse = parseBody(session)
            if (parse is BodyParseResult.Invalid) {
                return automationErr("INVALID_PARAMETER", "Invalid or missing JSON body", Response.Status.BAD_REQUEST)
            }
            val params = (parse as BodyParseResult.Success).params
            val ua = params["ua"] as? String
            return if (ua != null && ua.isNotBlank()) {
                FloatingBubbleService.setUaMode(FloatingBubbleService.UA_MODE_CUSTOM, ua)
                successResponse("UA set to custom",
                    "mode" to FloatingBubbleService.UA_MODE_CUSTOM,
                    "userAgent" to ua
                )
            } else {
                errorResponse("Missing 'ua' parameter")
            }
        }

        // ------------------------------------------------------------------
        // Helpers
        // ------------------------------------------------------------------

        /**
         * Parse the request body. Returns [BodyParseResult.Invalid] when the body
         * is missing or is not a valid JSON object, instead of silently falling
         * back to an empty map (which would hide malformed requests).
         */
        private fun parseBody(session: IHTTPSession): BodyParseResult {
            val files = mutableMapOf<String, String>()
            try {
                session.parseBody(files)
                val postData = files["postData"] ?: return BodyParseResult.Invalid
                if (postData.isBlank()) return BodyParseResult.Invalid
                val parsed = gson.fromJson(postData, Map::class.java)
                if (parsed == null || parsed !is Map<*, *>) return BodyParseResult.Invalid
                return BodyParseResult.Success(parsed as Map<String, Any>)
            } catch (e: JsonSyntaxException) {
                Log.e(TAG, "Failed to parse JSON body", e)
                return BodyParseResult.Invalid
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse body", e)
                return BodyParseResult.Invalid
            }
        }

        private fun parseTimeout(params: Map<String, Any>, default: Long): Long {
            return (params["timeout"] as? Number)?.toLong()
                ?.coerceIn(1000L, 120000L) ?: default
        }

        private fun parseNumber(value: Any?, default: Int): Int {
            return (value as? Number)?.toInt() ?: default
        }

        private fun parseInt(str: String?, default: Int): Int {
            return str?.toInt().orElse(default)
        }

        private fun parseFloat(str: String?, default: Float): Float {
            return str?.toFloat().orElse(default)
        }

        // Legacy response shape (preserved for existing endpoints).
        private fun successResponse(message: String, vararg data: Pair<String, Any?>): Response {
            val result = mutableMapOf<String, Any?>(
                "success" to true,
                "message" to message
            )
            data.forEach { (key, value) ->
                result[key] = value
            }
            return newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                gson.toJson(result)
            )
        }

        private fun errorResponse(message: String): Response {
            val result = mapOf(
                "success" to false,
                "error" to message
            )
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "application/json",
                gson.toJson(result)
            )
        }

        // Standard automation envelope: { success, data, error }
        private fun automationOk(data: Any?): Response {
            return newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                AutomationResponse.automationResponseToJson(AutomationService.gson, true, data, null)
            )
        }

        private fun automationErr(code: String, message: String, status: Response.Status = Response.Status.BAD_REQUEST): Response {
            return newFixedLengthResponse(
                status,
                "application/json",
                AutomationResponse.automationResponseToJson(AutomationService.gson, false, null, AutomationError(code, message))
            )
        }

        private fun errorResponse(code: String, message: String, status: Response.Status): Response {
            val result = mapOf(
                "success" to false,
                "error" to mapOf("code" to code, "message" to message)
            )
            return newFixedLengthResponse(
                status,
                "application/json",
                gson.toJson(result)
            )
        }

        private fun jsResultToAutomationResponse(result: JsResult): Response {
            return when (result) {
                is JsResult.Error -> {
                    automationErr(mapJsErrorCode(result.code), result.message)
                }
                is JsResult.Value -> {
                    val map = result.value as? Map<*, *>
                    if (map != null) {
                        val ok = map["ok"] == true
                        if (!ok) {
                            val err = map["error"] as? Map<*, *>
                            val code = err?.get("code")?.toString() ?: "OPERATION_FAILED"
                            val msg = err?.get("message")?.toString() ?: "Operation failed"
                            return automationErr(code, msg)
                        }
                        return automationOk(map["data"])
                    }
                    return automationOk(result.value)
                }
                is JsResult.Empty -> automationOk(emptyMap())
            }
        }

        private fun runOnMainThread(action: () -> Unit) {
            Handler(Looper.getMainLooper()).post(action)
        }
    }
}
