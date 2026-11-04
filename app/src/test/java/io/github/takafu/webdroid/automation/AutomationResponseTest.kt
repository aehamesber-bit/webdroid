package io.github.takafu.webdroid.automation

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertFailsWith

class AutomationResponseTest {

    private val gson = Gson()

    @Test
    fun `evalJsValue returns null for null input`() {
        assertNull(evalJsValue(gson, null))
    }

    @Test
    fun `evalJsValue returns null for JSON null`() {
        assertNull(evalJsValue(gson, "null"))
    }

    @Test
    fun `evalJsValue returns null for blank`() {
        assertNull(evalJsValue(gson, ""))
        assertNull(evalJsValue(gson, "   "))
    }

    @Test
    fun `evalJsValue parses JSON object`() {
        val result = evalJsValue(gson, "{\"ok\":true,\"count\":3}")
        assertTrue(result is Map<*, *>)
        assertEquals(true, (result as Map<*, *>) ["ok"])
        assertEquals(3.0, (result as Map<*, *>) ["count"])
    }

    @Test
    fun `evalJsValue parses JSON array`() {
        val result = evalJsValue(gson, "[1,2,3]")
        assertTrue(result is List<*>)
        assertEquals(3, (result as List<*>).size)
    }

    @Test
    fun `evalJsValue parses quoted JSON string from evaluateJavascript`() {
        // evaluateJavascript wraps results in quotes; the inner JSON should be unquoted then parsed.
        val raw = gson.toJson("{\"ok\":true}")
        val result = evalJsValue(gson, raw)
        assertTrue(result is Map<*, *>)
        assertEquals(true, (result as Map<*, *>)["ok"])
    }

    @Test
    fun `evalJsValue returns raw text for non-JSON`() {
        val result = evalJsValue(gson, "hello world")
        assertEquals("hello world", result)
    }

    @Test
    fun `evalJsValue handles number`() {
        assertEquals(42.0, evalJsValue(gson, "42"))
    }

    @Test
    fun `jsErrorCodeToApiCode maps all codes`() {
        assertEquals("TIMEOUT", jsErrorCodeToApiCode(BrowserAutomationManager.JsErrorCode.TIMEOUT))
        assertEquals("WEBVIEW_UNAVAILABLE", jsErrorCodeToApiCode(BrowserAutomationManager.JsErrorCode.WEBVIEW_UNAVAILABLE))
        assertEquals("REQUEST_INTERRUPTED", jsErrorCodeToApiCode(BrowserAutomationManager.JsErrorCode.INTERRUPTED))
        assertEquals("JAVASCRIPT_ERROR", jsErrorCodeToApiCode(BrowserAutomationManager.JsErrorCode.JAVASCRIPT_ERROR))
        assertEquals("INVALID_PARAMETER", jsErrorCodeToApiCode(BrowserAutomationManager.JsErrorCode.INVALID_TIMEOUT))
        assertEquals("INVALID_PARAMETER", jsErrorCodeToApiCode(BrowserAutomationManager.JsErrorCode.INVALID_PARAMETER))
        assertEquals("INVALID_PARAMETER", jsErrorCodeToApiCode(BrowserAutomationManager.JsErrorCode.MAIN_THREAD_CALL))
        assertEquals("JAVASCRIPT_ERROR", jsErrorCodeToApiCode(BrowserAutomationManager.JsErrorCode.SCHEDULE_FAILED))
    }
}
