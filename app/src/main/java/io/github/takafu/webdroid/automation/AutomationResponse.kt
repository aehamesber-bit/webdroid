package io.github.takafu.webdroid.automation

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException

/**
 * Standard error payload used by the automation endpoints.
 */
data class AutomationError(
    val code: String,
    val message: String
)

/**
 * Standard envelope for automation API responses.
 *
 * Success: { "success": true, "data": { ... }, "error": null }
 * Error:   { "success": false, "data": null, "error": { "code": "...", "message": "..." } }
 */
data class AutomationResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val error: AutomationError? = null
)

/**
 * Result of evaluating JavaScript in the WebView.
 */
sealed class JsResult {
    object Empty : JsResult()
    data class Value(val value: Any?) : JsResult()
    data class Error(val message: String, val code: BrowserAutomationManager.JsErrorCode = BrowserAutomationManager.JsErrorCode.JAVASCRIPT_ERROR) : JsResult()
}

/**
 * Parses a value returned by WebView.evaluateJavascript.
 *
 * evaluateJavascript always delivers the result as a JSON-encoded string (e.g.
 * the string {@code "{\"ok\":true}"} arrives as the Kotlin String
 * {@code "{\"ok\":true}"} with surrounding quotes and escaped inner quotes).
 * This helper unquotes the value, then parses the inner JSON into Maps/Lists/
 * primitives via Gson.
 */
fun evalJsValue(gson: Gson, rawValue: Any?): Any? {
    if (rawValue == null) return null
    var text: String
    try {
        text = gson.fromJson(rawValue.toString(), String::class.java)
    } catch (e: Exception) {
        text = rawValue.toString()
    }
    if (text.isBlank()) return null
    if (text.equals("null", ignoreCase = true)) return null
    return try {
        gson.fromJson(text, Any::class.java)
    } catch (e: JsonSyntaxException) {
        // Not JSON - return the raw text as-is.
        text
    }
}

/**
 * Serializes an [AutomationResponse] to compact JSON.
 */
fun automationResponseToJson(gson: Gson, success: Boolean, data: Any?, error: AutomationError?): String {
    return gson.toJson(AutomationResponse(success, data, error))
}

/**
 * Maps a [BrowserAutomationManager.JsErrorCode] to a stable API error code.
 */
internal fun jsErrorCodeToApiCode(code: BrowserAutomationManager.JsErrorCode): String {
    return when (code) {
        BrowserAutomationManager.JsErrorCode.WEBVIEW_UNAVAILABLE -> "WEBVIEW_UNAVAILABLE"
        BrowserAutomationManager.JsErrorCode.TIMEOUT -> "TIMEOUT"
        BrowserAutomationManager.JsErrorCode.INTERRUPTED -> "REQUEST_INTERRUPTED"
        BrowserAutomationManager.JsErrorCode.INVALID_TIMEOUT -> "INVALID_PARAMETER"
        BrowserAutomationManager.JsErrorCode.SCHEDULE_FAILED -> "JAVASCRIPT_ERROR"
        BrowserAutomationManager.JsErrorCode.MAIN_THREAD_CALL -> "INVALID_PARAMETER"
        BrowserAutomationManager.JsErrorCode.JAVASCRIPT_ERROR -> "JAVASCRIPT_ERROR"
    }
}
