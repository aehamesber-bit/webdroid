package io.github.takafu.webdroid.automation

import android.webkit.WebView
import kotlin.math.max
import kotlin.math.minOf

/**
 * DOM interaction operations: click, type, wait, find, scroll.
 *
 * Each operation builds a self-contained JavaScript snippet (all dynamic values
 * are injected as a JSON object so no manual escaping is needed), runs it via
 * [BrowserAutomationManager.evalJson], and returns the parsed result.
 *
 * All operations report errors through a consistent envelope:
 *   { "ok": false, "error": { "code": "...", "message": "..." } }
 * so the HTTP layer can map them to stable error codes without guessing.
 */
class DomInteractionHandler(private val manager: BrowserAutomationManager) {

    private fun paramsJson(params: Map<String, Any>): String = manager.gson.toJson(params)

    // ------------------------------------------------------------------
    // click
    // ------------------------------------------------------------------

    /**
     * Click the element at [index] matching [selector].
     *
     * The click uses the browser's native [Element.click]. This is NOT a full
     * user-interaction simulation: it does not move a pointer, does not
     * synthesize hover/real touch events, and may not be honored by custom
     * input frameworks that listen only to synthetic `input`/`change` events.
     */
    fun click(webView: WebView?, selector: String, index: Int, timeoutMs: Long): JsResult {
        if (webView == null) return JsResult.Error("WebView is not available", BrowserAutomationManager.JsErrorCode.WEBVIEW_UNAVAILABLE)
        val safeIndex = max(0, index)
        val params = mapOf("selector" to selector, "index" to safeIndex)
        val script = """
            (function() {
                var p = $paramsJson;
                var els;
                try { els = document.querySelectorAll(p.selector || ""); } catch (e) {
                    return JSON.stringify({ok:false, error:{code:"INVALID_SELECTOR", message:"Invalid CSS selector: " + String(p.selector || "")}});
                }
                if (!els || els.length === 0) {
                    return JSON.stringify({ok:false, error:{code:"ELEMENT_NOT_FOUND", message:"No element matched the supplied selector"}});
                }
                var i = typeof p.index === 'number' ? p.index : 0;
                if (i < 0 || i >= els.length) {
                    return JSON.stringify({ok:false, error:{code:"ELEMENT_NOT_FOUND", message:"Element index $i out of range (0-${els.length - 1})"}});
                }
                var el = els[i];
                if (!el) {
                    return JSON.stringify({ok:false, error:{code:"ELEMENT_NOT_FOUND", message:"Element at index not found"}});
                }
                try {
                    if (el.disabled === true) {
                        return JSON.stringify({ok:false, error:{code:"ELEMENT_NOT_INTERACTABLE", message:"Element is disabled"}});
                    }
                } catch (e) {}
                try {
                    if (el.matches && el.matches(':disabled')) {
                        return JSON.stringify({ok:false, error:{code:"ELEMENT_NOT_INTERACTABLE", message:"Element is disabled"}});
                    }
                } catch (e) {}
                var rect = el.getBoundingClientRect ? el.getBoundingClientRect() : {width:0, height:0};
                var visible = el.offsetParent !== null
                    || (rect.width > 0 && rect.height > 0)
                    || (el.clientWidth > 0 && el.clientHeight > 0);
                if (!visible) {
                    try { el.scrollIntoView({behavior:'instant', block:'center', inline:'center'}); } catch (e) {}
                    rect = el.getBoundingClientRect ? el.getBoundingClientRect() : {width:0, height:0};
                    visible = el.offsetParent !== null || (rect.width > 0 && rect.height > 0) || (el.clientWidth > 0 && el.clientHeight > 0);
                    if (!visible) {
                        return JSON.stringify({ok:false, error:{code:"ELEMENT_NOT_INTERACTABLE", message:"Element is not visible or is outside the viewport"}});
                    }
                } else {
                    try { el.scrollIntoView({behavior:'instant', block:'center', inline:'center'}); } catch (e) {}
                }
                var clicked = false;
                try {
                    if (typeof el.click === 'function') {
                        el.click();
                        clicked = true;
                    }
                } catch (e) {
                    return JSON.stringify({ok:false, error:{code:"JAVASCRIPT_ERROR", message:"Failed to click element: " + String(e && e.message ? e.message : e)}});
                }
                return JSON.stringify({ok:true, data:{tag:(el.tagName || "").toUpperCase(), index:i, total:els.length, clicked:clicked, visible:visible}});
            })()
        """.trimIndent().replace("""$paramsJson""", paramsJson(params))
        return manager.evalJson(webView, script, timeoutMs)
    }

    // ------------------------------------------------------------------
    // type
    // ------------------------------------------------------------------

    /**
     * Type [text] into the first element matching [selector].
     *
     * Supports <input>, <textarea>, and contenteditable elements. Uses the
     * element-appropriate native value setter (HTMLInputElement.prototype.value
     * for inputs, HTMLTextAreaElement.prototype.value for textareas) so
     * React-style controlled inputs are notified. Dispatches synthetic `input`
     * and `change` events. These are script-dispatched events, not real Android
     * touch events.
     */
    fun type(webView: WebView?, selector: String, text: String, clear: Boolean, timeoutMs: Long): JsResult {
        if (webView == null) return JsResult.Error("WebView is not available", BrowserAutomationManager.JsErrorCode.WEBVIEW_UNAVAILABLE)
        val params = mapOf("selector" to selector, "text" to text, "clear" to clear)
        val script = """
            (function() {
                var p = $paramsJson;
                var els;
                try { els = document.querySelectorAll(p.selector || ""); } catch (e) {
                    return JSON.stringify({ok:false, error:{code:"INVALID_SELECTOR", message:"Invalid CSS selector: " + String(p.selector || "")}});
                }
                if (!els || els.length === 0) {
                    return JSON.stringify({ok:false, error:{code:"ELEMENT_NOT_FOUND", message:"No element matched the supplied selector"}});
                }
                var el = els[0];
                var tag = (el.tagName || "").toUpperCase();
                var isPassword = tag === "INPUT" && String(el.type || "").toLowerCase() === "password";
                var editable = tag === "INPUT" || tag === "TEXTAREA" || el.isContentEditable === true;
                if (!editable) {
                    return JSON.stringify({ok:false, error:{code:"ELEMENT_NOT_INTERACTABLE", message:"Element is not an input, textarea, or contenteditable"}});
                }
                if (el.readOnly === true) {
                    return JSON.stringify({ok:false, error:{code:"ELEMENT_NOT_INTERACTABLE", message:"Element is read-only"}});
                }
                try {
                    if (el.disabled === true) {
                        return JSON.stringify({ok:false, error:{code:"ELEMENT_NOT_INTERACTABLE", message:"Element is disabled"}});
                    }
                } catch (e) {}
                if (tag === "INPUT" && (el.type || "").toLowerCase() === "file") {
                    return JSON.stringify({ok:false, error:{code:"ELEMENT_NOT_INTERACTABLE", message:"Cannot set a value on an input type=file element"}});
                }
                try { el.focus(); } catch (e) {}
                var text = p.text == null ? "" : String(p.text);
                var cleared = false;
                if (p.clear === true) {
                    if (el.isContentEditable === true) { el.textContent = ""; }
                    else { el.value = ""; }
                    dispatch(el);
                    cleared = true;
                }
                var setOk = false;
                if (tag === "INPUT" || tag === "TEXTAREA") {
                    try {
                        var proto = tag === "TEXTAREA"
                            ? HTMLTextAreaElement.prototype
                            : HTMLInputElement.prototype;
                        var desc = Object.getOwnPropertyDescriptor(proto, "value");
                        if (desc && desc.set) {
                            desc.set.call(el, text);
                            setOk = true;
                        }
                    } catch (e) {
                        // fall through to direct assignment
                    }
                    if (!setOk) {
                        try { el.value = text; setOk = true; } catch (e2) {}
                    }
                    dispatch(el);
                } else {
                    el.textContent = text;
                    dispatch(el);
                }
                var finalValue = el.isContentEditable === true ? el.textContent : el.value;
                return JSON.stringify({ok:true, data:{tag:tag, value:isPassword ? null : finalValue, sensitive:isPassword, cleared:cleared, set:setOk}});
                function dispatch(element) {
                    try { element.dispatchEvent(new Event('input', {bubbles:true})); } catch (e) {}
                    try { element.dispatchEvent(new Event('change', {bubbles:true})); } catch (e) {}
                }
            })()
        """.trimIndent().replace("""$paramsJson""", paramsJson(params))
        return manager.evalJson(webView, script, timeoutMs)
    }

    // ------------------------------------------------------------------
    // wait
    // ------------------------------------------------------------------

    /**
     * Wait until [selector] matches [state], where [state] is one of:
     * `present`, `visible`, `hidden`.
     *
     * Any other state is rejected up front. The selector is validated before
     * polling begins so an invalid selector fails fast with INVALID_SELECTOR
     * instead of masquerading as a timeout.
     */
    fun wait(webView: WebView?, selector: String, state: String, timeoutMs: Long): JsResult {
        if (webView == null) return JsResult.Error("WebView is not available", BrowserAutomationManager.JsErrorCode.WEBVIEW_UNAVAILABLE)
        val validStates = setOf("present", "visible", "hidden")
        if (!validStates.contains(state)) {
            return JsResult.Error("Unsupported wait state '$state'. Supported states: ${validStates.sorted().joinToString(", ")}",
                BrowserAutomationManager.JsErrorCode.INVALID_PARAMETER)
        }
        // Validate the selector up front.
        val checkParams = mapOf("selector" to selector)
        val validateScript = """
            (function() {
                var p = $paramsJson;
                try { document.querySelector(p.selector || ""); } catch (e) {
                    return JSON.stringify({ok:false, error:{code:"INVALID_SELECTOR", message:"Invalid CSS selector: " + String(p.selector || "")}});
                }
                return JSON.stringify({ok:true});
            })()
        """.trimIndent().replace("""$paramsJson""", manager.gson.toJson(checkParams))
        val validation = manager.evalJson(webView, validateScript, minOf(2000L, timeoutMs))
        when (validation) {
            is JsResult.Error -> return validation
            is JsResult.Value -> {
                val map = validation.value as? Map<*, *>
                if (map != null && map["ok"] != true) {
                    return validation
                }
            }
        }

        val checkScript = """
            (function() {
                var p = $paramsJson;
                var el = document.querySelector(p.selector || "");
                var present = !!el;
                var visible = false;
                if (present) {
                    var style = window.getComputedStyle(el);
                    var rect = el.getBoundingClientRect ? el.getBoundingClientRect() : {};
                    visible = el.offsetParent !== null
                        && style.visibility !== "hidden"
                        && style.display !== "none"
                        && (rect.width > 0 || rect.height > 0);
                }
                var state = p.state || "present";
                var ok;
                if (state === "present") ok = present;
                else if (state === "visible") ok = visible;
                else ok = !present || !visible;
                return JSON.stringify({ok: ok, present: present, visible: visible});
            })()
        """.trimIndent().replace("""$paramsJson""", manager.gson.toJson(mapOf("selector" to selector, "state" to state)))

        val start = System.currentTimeMillis()
        while (true) {
            val elapsed = System.currentTimeMillis() - start
            if (elapsed >= timeoutMs) {
                return JsResult.Error("Timeout: condition '$state' not met within ${timeoutMs}ms", BrowserAutomationManager.JsErrorCode.TIMEOUT)
            }
            val remaining = timeoutMs - elapsed
            val perCall = minOf(2000L, remaining)
            val result = manager.evalJson(webView, checkScript, perCall)
            when (result) {
                is JsResult.Error -> return result
                is JsResult.Value -> {
                    val map = result.value as? Map<*, *>
                    if (map != null && map["ok"] == true) {
                        return JsResult.Value(map)
                    }
                }
                is JsResult.Empty -> { }
            }
            try { Thread.sleep(200L) } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return JsResult.Error("Interrupted while waiting", BrowserAutomationManager.JsErrorCode.INTERRUPTED)
            }
        }
    }

    // ------------------------------------------------------------------
    // find
    // ------------------------------------------------------------------

    /**
     * Find elements matching [selector]. When [all] is false only the first
     * match is returned. Results are capped at [limit] to bound memory use.
     */
    fun find(webView: WebView?, selector: String, all: Boolean, limit: Int, timeoutMs: Long): JsResult {
        if (webView == null) return JsResult.Error("WebView is not available", BrowserAutomationManager.JsErrorCode.WEBVIEW_UNAVAILABLE)
        val safeLimit = max(1, limit).coerceAtMost(500)
        val params = mapOf("selector" to selector, "all" to all, "limit" to safeLimit)
        val script = """
            (function() {
                var p = $paramsJson;
                var els;
                try { els = document.querySelectorAll(p.selector || ""); } catch (e) {
                    return JSON.stringify({ok:false, error:{code:"INVALID_SELECTOR", message:"Invalid CSS selector: " + String(p.selector || "")}});
                }
                var results = [];
                var count = p.all === true ? els.length : Math.min(1, els.length);
                count = Math.min(count, p.limit);
                for (var k = 0; k < count; k++) {
                    var el = els[k];
                    var rect = el.getBoundingClientRect ? el.getBoundingClientRect() : {};
                    var tag = (el.tagName || "").toLowerCase();
                    var isPassword = tag === "input" && String(el.type || "").toLowerCase() === "password";
                    results.push({
                        index: k,
                        tag: tag,
                        text: (el.textContent || "").trim().substring(0, 200),
                        href: el.href || null,
                        value: isPassword ? null : (el.value || null),
                        sensitive: isPassword,
                        type: el.type || null,
                        id: el.id || null,
                        visible: el.offsetParent !== null || (rect.width > 0 || rect.height > 0)
                    });
                }
                return JSON.stringify({ok:true, data:{results: results, total: els.length, returned: results.length}});
            })()
        """.trimIndent().replace("""$paramsJson""", manager.gson.toJson(params))
        return manager.evalJson(webView, script, timeoutMs)
    }

    // ------------------------------------------------------------------
    // scroll
    // ------------------------------------------------------------------

    /**
     * Scroll the page or an element in [direction] (up/down/left/right) by
     * [amount] pixels. Unknown directions are rejected.
     */
    fun scroll(webView: WebView?, direction: String, amount: Int, selector: String?, timeoutMs: Long): JsResult {
        if (webView == null) return JsResult.Error("WebView is not available", BrowserAutomationManager.JsErrorCode.WEBVIEW_UNAVAILABLE)
        val dir = direction.lowercase()
        val valid = setOf("up", "down", "left", "right")
        if (!valid.contains(dir)) {
            return JsResult.Error("Unsupported scroll direction '$direction'. Supported: ${valid.sorted().joinToString(", ")}",
                BrowserAutomationManager.JsErrorCode.INVALID_PARAMETER)
        }
        val safeAmount = max(1, amount).coerceAtMost(5000)
        val params = mapOf("direction" to dir, "amount" to safeAmount, "selector" to selector)
        val script = """
            (function() {
                var p = $paramsJson;
                var dir = p.direction || "down";
                var amount = typeof p.amount === 'number' ? p.amount : 300;
                var dx = 0, dy = 0;
                if (dir === "up") dy = -amount;
                else if (dir === "down") dy = amount;
                else if (dir === "left") dx = -amount;
                else if (dir === "right") dx = amount;
                var el = null;
                if (p.selector) {
                    try { el = document.querySelector(p.selector); } catch (e) {
                        return JSON.stringify({ok:false, error:{code:"INVALID_SELECTOR", message:"Invalid CSS selector: " + String(p.selector || "")}});
                    }
                    if (!el) {
                        return JSON.stringify({ok:false, error:{code:"ELEMENT_NOT_FOUND", message:"No element matched the scroll selector"}});
                    }
                }
                var target = el != null ? el : window;
                var beforeX = el != null ? el.scrollLeft : window.scrollX;
                var beforeY = el != null ? el.scrollTop : window.scrollY;
                if (el) { el.scrollBy({left: dx, top: dy}); }
                else { window.scrollBy(dx, dy); }
                var afterX = el != null ? el.scrollLeft : window.scrollX;
                var afterY = el != null ? el.scrollTop : window.scrollY;
                return JSON.stringify({ok:true, data:{direction:dir, amount:amount, target: el != null ? "element" : "window", scrollX:afterX, scrollY:afterY, scrolledX:afterX - beforeX, scrolledY:afterY - beforeY}});
            })()
        """.trimIndent().replace("""$paramsJson""", manager.gson.toJson(params))
        return manager.evalJson(webView, script, timeoutMs)
    }
}
