package io.github.takafu.webdroid.automation

import android.webkit.WebView
import kotlin.math.max

/**
 * Read-only page inspection operations: extract, links, forms, page-state,
 * accessibility snapshot.
 *
 * Output is intentionally bounded (element limits, text truncation) so the AI
 * agent never receives an unbounded HTML document on a ~1.7 GB device.
 */
class PageExtractor(private val manager: BrowserAutomationManager) {

    private const val DEFAULT_LIMIT = 50
    private const val TEXT_TRUNCATE = 2000
    private const val NAME_TRUNCATE = 120

    fun extract(webView: WebView?, selector: String, fields: List<*>, all: Boolean, limit: Int, timeoutMs: Long): JsResult {
        if (webView == null) return JsResult.Error("WebView is not available")
        val params = mapOf(
            "selector" to selector,
            "fields" to fields,
            "all" to all,
            "limit" to max(1, limit)
        )
        val script = """
            (function() {
                var p = $paramsJson;
                var els = document.querySelectorAll(p.selector || "");
                var count = p.all === true ? els.length : Math.min(1, els.length);
                count = Math.min(count, p.limit);
                var fields = p.fields || [];
                var out = [];
                for (var k = 0; k < count; k++) {
                    var el = els[k];
                    var fieldValues = {};
                    for (var f = 0; f < fields.length; f++) {
                        var field = fields[f] || {};
                        var name = field.name != null ? String(field.name) : ("field" + f);
                        var kind = field.kind || "text";
                        var val = null;
                        if (kind === "text") { val = el.textContent || ""; }
                        else if (kind === "innerHTML") { val = el.innerHTML || ""; }
                        else if (kind === "outerHTML") { val = el.outerHTML || ""; }
                        else if (kind === "attribute") { val = el.getAttribute(field.attribute || ""); }
                        else if (kind === "link") { val = el.href || null; }
                        else if (kind === "value") { val = el.value || null; }
                        fieldValues[name] = val;
                    }
                    out.push({index: k, fields: fieldValues});
                }
                return JSON.stringify({ok:true, data:{results: out, returned: out.length}});
            })()
        """.trimIndent().replace("""$paramsJson""", manager.gson.toJson(params))
        return manager.evalJson(webView, script, timeoutMs)
    }

    fun links(webView: WebView?, limit: Int, timeoutMs: Long): JsResult {
        if (webView == null) return JsResult.Error("WebView is not available")
        val params = mapOf("limit" to max(0, limit))
        val script = """
            (function() {
                var p = $paramsJson;
                var lim = typeof p.limit === 'number' ? p.limit : 0;
                var els = document.querySelectorAll('a[href]');
                var count = lim > 0 ? Math.min(els.length, lim) : els.length;
                var out = [];
                for (var k = 0; k < count; k++) {
                    var a = els[k];
                    var abs = null;
                    try { abs = a.href; } catch (e) { abs = a.getAttribute('href') || null; }
                    out.push({
                        text: (a.textContent || '').trim().substring(0, 200),
                        url: abs,
                        title: a.title || null,
                        target: a.target || null
                    });
                }
                return JSON.stringify({ok:true, data:{links: out, count: out.length}});
            })()
        """.trimIndent().replace("""$paramsJson""", manager.gson.toJson(params))
        return manager.evalJson(webView, script, timeoutMs)
    }

    fun forms(webView: WebView?, timeoutMs: Long): JsResult {
        if (webView == null) return JsResult.Error("WebView is not available")
        val script = """
            (function() {
                var els = document.querySelectorAll('form');
                var out = [];
                for (var k = 0; k < els.length; k++) {
                    var form = els[k];
                    var inputs = [];
                    var allInputs = form.querySelectorAll('input, textarea, select, button');
                    for (var i = 0; i < allInputs.length; i++) {
                        var el = allInputs[i];
                        var tag = (el.tagName || '').toUpperCase();
                        var type = el.type || null;
                        if (type === 'password') {
                            inputs.push({name: el.name || null, type: 'password', value: null, placeholder: el.placeholder || null});
                            continue;
                        }
                        if (tag === 'SELECT') {
                            var opts = [];
                            for (var j = 0; j < el.options.length; j++) {
                                opts.push({value: el.options[j].value, text: el.options[j].text, selected: el.options[j].selected});
                            }
                            inputs.push({name: el.name || null, type: 'select', value: el.value || null, options: opts, placeholder: null});
                        } else if (tag === 'BUTTON') {
                            inputs.push({name: el.name || null, type: 'button', value: el.value || null, text: (el.textContent || '').trim(), placeholder: null});
                        } else {
                            inputs.push({name: el.name || null, type: type, value: el.value || null, placeholder: el.placeholder || null});
                        }
                    }
                    out.push({
                        action: form.action || null,
                        method: form.method || 'get',
                        id: form.id || null,
                        name: form.name || null,
                        inputs: inputs
                    });
                }
                return JSON.stringify({ok:true, data:{forms: out, count: out.length}});
            })()
        """.trimIndent()
        return manager.evalJson(webView, script, timeoutMs)
    }

    fun pageState(webView: WebView?, timeoutMs: Long): JsResult {
        if (webView == null) return JsResult.Error("WebView is not available")
        val script = """
            (function() {
                var root = document.body || document.documentElement;
                var text = (root.innerText || root.textContent || '').trim();
                return JSON.stringify({
                    ok:true,
                    data:{
                        url: location.href,
                        title: document.title || "",
                        readyState: document.readyState,
                        textSummary: text.substring(0, $TEXT_TRUNCATE),
                        interactiveElements: document.querySelectorAll('button, input, select, textarea, a[href]').length,
                        linkCount: document.querySelectorAll('a[href]').length,
                        formCount: document.querySelectorAll('form').length,
                        scrollX: window.scrollX,
                        scrollY: window.scrollY,
                        viewportWidth: window.innerWidth,
                        viewportHeight: window.innerHeight,
                        userAgent: navigator.userAgent || ""
                    }
                });
            })()
        """.trimIndent()
        return manager.evalJson(webView, script, timeoutMs)
    }

    fun accessibility(webView: WebView?, depth: Int, limit: Int, timeoutMs: Long): JsResult {
        if (webView == null) return JsResult.Error("WebView is not available")
        val params = mapOf("depth" to max(1, depth), "limit" to max(1, limit))
        val script = """
            (function() {
                var p = $paramsJson;
                var maxDepth = p.depth;
                var maxElements = p.limit;
                var out = [];
                var count = 0;
                function roleOf(el) {
                    var role = el.getAttribute('role');
                    if (role) return role;
                    var tag = (el.tagName || '').toUpperCase();
                    switch (tag) {
                        case 'A': return 'link';
                        case 'BUTTON': return 'button';
                        case 'INPUT': return el.type || 'input';
                        case 'TEXTAREA': return 'textbox';
                        case 'SELECT': return 'combobox';
                        case 'H1': case 'H2': case 'H3': case 'H4': case 'H5': case 'H6': return 'heading';
                        case 'IMG': return 'image';
                        case 'NAV': return 'navigation';
                        case 'MAIN': return 'main';
                        case 'FORM': return 'form';
                        default: return tag.toLowerCase();
                    }
                }
                function nameOf(el) {
                    var label = el.getAttribute('aria-label');
                    if (label) return label;
                    var labelledby = el.getAttribute('aria-labelledby');
                    if (labelledby) {
                        var ref = document.getElementById(labelledby);
                        if (ref) return (ref.textContent || '').trim();
                    }
                    var tag = (el.tagName || '').toUpperCase();
                    if (tag === 'INPUT' || tag === 'TEXTAREA') return el.placeholder || el.name || '';
                    var text = (el.textContent || '').trim();
                    return text.substring(0, $NAME_TRUNCATE);
                }
                function walk(el, depth) {
                    if (count >= maxElements) return;
                    if (depth > maxDepth) return;
                    if (el.nodeType !== 1) return;
                    var tag = (el.tagName || '').toUpperCase();
                    var interactive = tag === 'A' || tag === 'BUTTON' || tag === 'INPUT' || tag === 'TEXTAREA'
                        || tag === 'SELECT' || el.getAttribute('role') != null
                        || el.isContentEditable === true
                        || tag.match(/^H[1-6]$/)
                        || tag === 'NAV' || tag === 'MAIN' || tag === 'FORM';
                    if (interactive && count < maxElements) {
                        var rect = el.getBoundingClientRect ? el.getBoundingClientRect() : {};
                        var style = window.getComputedStyle(el);
                        var visible = el.offsetParent !== null && style.visibility !== 'hidden' && style.display !== 'none';
                        out.push({
                            tag: tag.toLowerCase(),
                            role: roleOf(el),
                            name: nameOf(el),
                            type: el.type || null,
                            id: el.id || null,
                            visible: visible,
                            disabled: el.disabled === true
                        });
                        count++;
                    }
                    for (var i = 0; i < el.children.length && count < maxElements; i++) {
                        walk(el.children[i], depth + 1);
                    }
                }
                walk(document.body, 0);
                return JSON.stringify({ok:true, data:{elements: out, count: out.length, truncated: count >= maxElements}});
            })()
        """.trimIndent().replace("""$paramsJson""", manager.gson.toJson(params))
        return manager.evalJson(webView, script, timeoutMs)
    }
}
