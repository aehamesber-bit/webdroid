package io.github.takafu.webdroid.automation

import android.webkit.WebView
import kotlin.math.max
import kotlin.math.minOf

/**
 * DOM interaction operations: click, type, wait, find, extract, scroll.
 *
 * Each operation builds a self-contained JavaScript snippet (all dynamic values
 * are injected as a JSON object so no manual escaping is needed), runs it via
 * [BrowserAutomationManager.evalJson], and returns the parsed result.
 */
class DomInteractionHandler(private val manager: BrowserAutomationManager) {

    fun click(webView: WebView?, selector: String, index: Int, timeoutMs: Long): JsResult {
        if (webView == null) return JsResult.Error("WebView is not available")
        val params = mapOf("selector" to selector, "index" to max(0, index))
        val script = """
            (function() {
                var p = $paramsJson;
                var els = document.querySelectorAll(p.selector || "");
                if (!els || els.length === 0) {
                    return JSON.stringify({ok:false, error:{code:"ELEMENT_NOT_FOUND", message:"No element matched the supplied selector"}});
                }
                var i = Math.min(typeof p.index === 'number' ? p.index : 0, els.length - 1);
                var el = els[i];
                if (!el) {
                    return JSON.stringify({ok:false, error:{code:"ELEMENT_NOT_FOUND", message:"Element at index not found"}});
                }
                try {
                    if (el.matches && el.matches(':disabled')) {
                        return JSON.stringify({ok:false, error:{code:"ELEMENT_NOT_INTERACTABLE", message:"Element is disabled"}});
                    }
                } catch (e) {}
                var rect = el.getBoundingClientRect ? el.getBoundingClientRect() : {width:0, height:0};
                var visible = el.offsetParent !== null || rect.width > 0 || rect.height > 0;
                if (!visible) {
                    return JSON.stringify({ok:false, error:{code:"ELEMENT_NOT_INTERACTABLE", message:"Element is not visible or interactable"}});
                }
                try { el.scrollIntoView({behavior:'instant', block:'center', inline:'center'}); } catch (e) {}
                el.click();
                return JSON.stringify({ok:true, data:{tag:el.tagName, index:i, total:els.length, clicked:true}});
            })()
        """.trimIndent().replace("""$paramsJson""", manager.gson.toJson(params))
        return manager.evalJson(webView, script, timeoutMs)
    }

    fun type(webView: WebView?, selector: String, text: String, clear: Boolean, timeoutMs: Long): JsResult {
        if (webView == null) return JsResult.Error("WebView is not available")
        val params = mapOf("selector" to selector, "text" to text, "clear" to clear)
        val script = """
            (function() {
                var p = $paramsJson;
                var els = document.querySelectorAll(p.selector || "");
                if (!els || els.length === 0) {
                    return JSON.stringify({ok:false, error:{code:"ELEMENT_NOT_FOUND", message:"No element matched the supplied selector"}});
                }
                var el = els[0];
                var tag = (el.tagName || "").toUpperCase();
                var editable = tag === "INPUT" || tag === "TEXTAREA" || el.isContentEditable === true;
                if (!editable) {
                    return JSON.stringify({ok:false, error:{code:"ELEMENT_NOT_INTERACTABLE", message:"Element is not an input, textarea, or contenteditable"}});
                }
                try {
                    if (el.matches && el.matches(':disabled')) {
                        return JSON.stringify({ok:false, error:{code:"ELEMENT_NOT_INTERACTABLE", message:"Element is disabled"}});
                    }
                } catch (e) {}
                try { el.focus(); } catch (e) {}
                var text = p.text == null ? "" : String(p.text);
                var cleared = false;
                if (p.clear === true) {
                    if (el.isContentEditable === true) { el.textContent = ""; }
                    else { el.value = ""; }
                    dispatch(el);
                    cleared = true;
                }
                if (tag === "INPUT" || tag === "TEXTAREA") {
                    try {
                        var setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value").set
                            || Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, "value").set;
                        if (setter) { setter.call(el, text); }
                        else { el.value = text; }
                    } catch (e) { el.value = text; }
                    dispatch(el);
                } else {
                    el.textContent = text;
                    dispatch(el);
                }
                var finalValue = el.isContentEditable === true ? el.textContent : el.value;
                return JSON.stringify({ok:true, data:{tag:tag, value:finalValue, cleared:cleared}});
                function dispatch(element) {
                    try { element.dispatchEvent(new Event('input', {bubbles:true})); } catch (e) {}
                    try { element.dispatchEvent(new Event('change', {bubbles:true})); } catch (e) {}
                }
            })()
        """.trimIndent().replace("""$paramsJson""", manager.gson.toJson(params))
        return manager.evalJson(webView, script, timeoutMs)
    }

    fun wait(webView: WebView?, selector: String, state: String, timeoutMs: Long): JsResult {
        if (webView == null) return JsResult.Error("WebView is not available")
        val params = mapOf("selector" to selector, "state" to state)
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
        """.trimIndent().replace("""$paramsJson""", manager.gson.toJson(params))

        val start = System.currentTimeMillis()
        while (true) {
            val elapsed = System.currentTimeMillis() - start
            if (elapsed >= timeoutMs) {
                return JsResult.Error("TIMEOUT: condition '${state}' not met within ${timeoutMs}ms")
            }
            val perCall = minOf(2000L, timeoutMs - elapsed)
            val result = manager.evalJson(webView, checkScript, perCall)
            when (result) {
                is JsResult.Error -> return result
                is JsResult.Value -> {
                    val map = result.value as? Map<*, *>
                    if (map != null && map.get("ok") == true) {
                        return JsResult.Value(map)
                    }
                }
                is JsResult.Empty -> { }
            }
            try { Thread.sleep(200L) } catch (e: InterruptedException) {
                return JsResult.Error("Interrupted while waiting")
            }
        }
    }

    fun find(webView: WebView?, selector: String, all: Boolean, limit: Int, timeoutMs: Long): JsResult {
        if (webView == null) return JsResult.Error("WebView is not available")
        val params = mapOf("selector" to selector, "all" to all, "limit" to max(1, limit))
        val script = """
            (function() {
                var p = $paramsJson;
                var els = document.querySelectorAll(p.selector || "");
                var results = [];
                var count = p.all === true ? els.length : Math.min(1, els.length);
                count = Math.min(count, p.limit);
                for (var k = 0; k < count; k++) {
                    var el = els[k];
                    var rect = el.getBoundingClientRect ? el.getBoundingClientRect() : {};
                    results.push({
                        index: k,
                        tag: el.tagName || "",
                        text: (el.textContent || "").trim().substring(0, 200),
                        href: el.href || null,
                        value: el.value || null,
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

    fun scroll(webView: WebView?, direction: String, amount: Int, selector: String?, timeoutMs: Long): JsResult {
        if (webView == null) return JsResult.Error("WebView is not available")
        val params = mapOf("direction" to direction, "amount" to max(1, amount), "selector" to selector)
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
                if (p.selector) { el = document.querySelector(p.selector); }
                var target = el != null ? el : window;
                if (el) { el.scrollBy({left: dx, top: dy}); }
                else { window.scrollBy(dx, dy); }
                return JSON.stringify({ok:true, data:{direction:dir, amount:amount, scrollX:window.scrollX, scrollY:window.scrollY}});
            })()
        """.trimIndent().replace("""$paramsJson""", manager.gson.toJson(params))
        return manager.evalJson(webView, script, timeoutMs)
    }
}
