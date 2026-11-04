package io.github.takafu.webdroid.automation

import android.webkit.WebView
import kotlin.math.max

/**
 * Read-only page inspection operations: extract, links, forms, page-state,
 * accessibility snapshot.
 *
 * Output is intentionally bounded (element limits, text truncation) so the AI
 * agent never receives an unbounded document on a ~1.7 GB device.
 */
class PageExtractor(private val manager: BrowserAutomationManager) {

    private companion object {
        const val DEFAULT_LIMIT = 50
        const val ABSOLUTE_LIMIT = 500
        const val TEXT_TRUNCATE = 2000
        const val NAME_TRUNCATE = 120
        const val MAX_FORMS = 20
        const val MAX_FIELDS_PER_FORM = 50
        const val MAX_SELECT_OPTIONS = 30
        const val MAX_ACCESSIBILITY_DEPTH = 12
        const val MAX_ACCESSIBILITY_ELEMENTS = 300
    }

    // ------------------------------------------------------------------
    // extract
    // ------------------------------------------------------------------

    /**
     * Extract fields from elements matching [selector].
     *
     * [fields] is a list of {name, kind, attribute?} where kind is one of:
     * text, innerHTML, outerHTML, attribute, link, value.
     * When [all] is false only the first match is returned. Results and text
     * lengths are capped.
     */
    fun extract(webView: WebView?, selector: String, fields: List<*>, all: Boolean, limit: Int, timeoutMs: Long): JsResult {
        if (webView == null) return JsResult.Error("WebView is not available", BrowserAutomationManager.JsErrorCode.WEBVIEW_UNAVAILABLE)
        val safeFields = if (fields.size > ABSOLUTE_LIMIT) fields.subList(0, ABSOLUTE_LIMIT) else fields
        val safeLimit = max(1, limit).coerceAtMost(ABSOLUTE_LIMIT)
        val validKinds = setOf("text", "innerHTML", "outerHTML", "attribute", "link", "value")
        val params = mapOf(
            "selector" to selector,
            "fields" to safeFields,
            "all" to all,
            "limit" to safeLimit,
            "validKinds" to validKinds.toList()
        )
        val script = """
            (function() {
                var p = ${'$'}paramsJson;
                var els;
                try { els = document.querySelectorAll(p.selector || ""); } catch (e) {
                    return JSON.stringify({ok:false, error:{code:"INVALID_SELECTOR", message:"Invalid CSS selector: " + String(p.selector || "")}});
                }
                if (!els || els.length === 0) {
                    return JSON.stringify({ok:true, data:{results: [], returned: 0, total: 0}});
                }
                var count = p.all === true ? els.length : Math.min(1, els.length);
                count = Math.min(count, p.limit);
                var fields = p.fields || [];
                var validKinds = p.validKinds || [];
                var out = [];
                function isPasswordInput(node) {
                    return !!node && node.nodeType === 1 && node.tagName === 'INPUT'
                        && String(node.type || '').toLowerCase() === 'password';
                }
                function safeMarkup(node, outer) {
                    var copy = node.cloneNode(true);
                    if (isPasswordInput(copy)) {
                        copy.value = '';
                        copy.removeAttribute('value');
                    }
                    var inputs = copy.querySelectorAll ? copy.querySelectorAll('input') : [];
                    for (var j = 0; j < inputs.length; j++) {
                        if (isPasswordInput(inputs[j])) {
                            inputs[j].value = '';
                            inputs[j].removeAttribute('value');
                        }
                    }
                    return outer ? (copy.outerHTML || '') : (copy.innerHTML || '');
                }
                for (var k = 0; k < count; k++) {
                    var el = els[k];
                    var isPassword = isPasswordInput(el);
                    var fieldValues = {};
                    for (var f = 0; f < fields.length; f++) {
                        var field = fields[f] || {};
                        var name = field.name != null ? String(field.name) : ("field" + f);
                        var kind = field.kind || "text";
                        var val = null;
                        if (validKinds.indexOf(kind) < 0) {
                            val = null;
                        } else if (kind === "text") {
                            val = el.textContent || "";
                            if (typeof val === 'string') val = val.substring(0, 2000);
                        } else if (kind === "innerHTML") {
                            val = safeMarkup(el, false);
                            if (typeof val === 'string') val = val.substring(0, 4000);
                        } else if (kind === "outerHTML") {
                            val = safeMarkup(el, true);
                            if (typeof val === 'string') val = val.substring(0, 4000);
                        } else if (kind === "attribute") {
                            var attributeName = String(field.attribute || '');
                            val = isPassword && attributeName.toLowerCase() === 'value'
                                ? null : (el.getAttribute(attributeName) || null);
                        } else if (kind === "link") {
                            val = el.href || null;
                        } else if (kind === "value") {
                            val = isPassword ? null : (el.value || null);
                        }
                        fieldValues[name] = val;
                    }
                    out.push({index: k, fields: fieldValues});
                }
                return JSON.stringify({ok:true, data:{results: out, returned: out.length, total: els.length}});
            })()
        """.trimIndent().replace("${'$'}paramsJson", manager.gson.toJson(params))
        return manager.evalJson(webView, script, timeoutMs)
    }

    // ------------------------------------------------------------------
    // links
    // ------------------------------------------------------------------

    fun links(webView: WebView?, limit: Int, timeoutMs: Long): JsResult {
        if (webView == null) return JsResult.Error("WebView is not available", BrowserAutomationManager.JsErrorCode.WEBVIEW_UNAVAILABLE)
        val safeLimit = max(0, limit).coerceAtMost(ABSOLUTE_LIMIT)
        val params = mapOf("limit" to safeLimit)
        val script = """
            (function() {
                var p = ${'$'}paramsJson;
                var lim = typeof p.limit === 'number' ? p.limit : 0;
                var els = document.querySelectorAll('a[href]');
                var count = lim > 0 ? Math.min(els.length, lim) : els.length;
                var out = [];
                for (var k = 0; k < count; k++) {
                    var a = els[k];
                    var abs = null;
                    try { abs = a.href; } catch (e) { abs = a.getAttribute('href') || null; }
                    if (!abs) continue;
                    out.push({
                        text: (a.textContent || '').trim().substring(0, 200),
                        url: abs,
                        title: a.title || null,
                        target: a.target || null
                    });
                }
                return JSON.stringify({ok:true, data:{links: out, count: out.length, total: els.length}});
            })()
        """.trimIndent().replace("${'$'}paramsJson", manager.gson.toJson(params))
        return manager.evalJson(webView, script, timeoutMs)
    }

    // ------------------------------------------------------------------
    // forms
    // ------------------------------------------------------------------

    fun forms(webView: WebView?, timeoutMs: Long): JsResult {
        if (webView == null) return JsResult.Error("WebView is not available", BrowserAutomationManager.JsErrorCode.WEBVIEW_UNAVAILABLE)
        val script = """
            (function() {
                var els = document.querySelectorAll('form');
                var maxForms = $MAX_FORMS;
                var maxFields = $MAX_FIELDS_PER_FORM;
                var maxOptions = $MAX_SELECT_OPTIONS;
                var out = [];
                var formCount = Math.min(els.length, maxForms);
                for (var k = 0; k < formCount; k++) {
                    var form = els[k];
                    var allInputs = form.querySelectorAll('input, textarea, select, button');
                    var inputs = [];
                    var fieldCount = Math.min(allInputs.length, maxFields);
                    for (var i = 0; i < fieldCount; i++) {
                        var el = allInputs[i];
                        var tag = (el.tagName || '').toUpperCase();
                        var type = el.type || null;
                        if (type === 'password') {
                            inputs.push({name: el.name || null, type: 'password', value: null, placeholder: el.placeholder || null});
                            continue;
                        }
                        if (tag === 'SELECT') {
                            var opts = [];
                            var optCount = Math.min(el.options.length, maxOptions);
                            for (var j = 0; j < optCount; j++) {
                                var o = el.options[j];
                                opts.push({value: o.value, text: (o.text || '').substring(0, 200), selected: o.selected === true});
                            }
                            inputs.push({name: el.name || null, type: 'select', value: el.value || null, options: opts, placeholder: null, truncated: el.options.length > maxOptions});
                        } else if (tag === 'BUTTON') {
                            inputs.push({name: el.name || null, type: 'button', value: el.value || null, text: (el.textContent || '').trim().substring(0, 200), placeholder: null});
                        } else {
                            var val = el.value || null;
                            if (typeof val === 'string') val = val.substring(0, 500);
                            inputs.push({name: el.name || null, type: type, value: val, placeholder: el.placeholder || null});
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
                return JSON.stringify({ok:true, data:{forms: out, count: out.length, total: els.length, truncated: els.length > maxForms}});
            })()
        """.trimIndent()
        return manager.evalJson(webView, script, timeoutMs)
    }

    // ------------------------------------------------------------------
    // pageState
    // ------------------------------------------------------------------

    fun pageState(webView: WebView?, timeoutMs: Long): JsResult {
        if (webView == null) return JsResult.Error("WebView is not available", BrowserAutomationManager.JsErrorCode.WEBVIEW_UNAVAILABLE)
        val script = """
            (function() {
                var root = document.body || document.documentElement;
                var text = (root.innerText || root.textContent || '').trim();
                return JSON.stringify({
                    ok:true,
                    data:{
                        url: location.href || "",
                        title: document.title || "",
                        readyState: document.readyState || "",
                        textSummary: text.substring(0, $TEXT_TRUNCATE),
                        textLength: text.length,
                        interactiveElements: document.querySelectorAll('button, input, select, textarea, a[href]').length,
                        linkCount: document.querySelectorAll('a[href]').length,
                        formCount: document.querySelectorAll('form').length,
                        scrollX: window.scrollX,
                        scrollY: window.scrollY,
                        viewportWidth: window.innerWidth,
                        viewportHeight: window.innerHeight,
                        screenWidth: window.screen.width,
                        screenHeight: window.screen.height,
                        userAgent: navigator.userAgent || ""
                    }
                });
            })()
        """.trimIndent()
        return manager.evalJson(webView, script, timeoutMs)
    }

    // ------------------------------------------------------------------
    // accessibility
    // ------------------------------------------------------------------

    fun accessibility(webView: WebView?, depth: Int, limit: Int, timeoutMs: Long): JsResult {
        if (webView == null) return JsResult.Error("WebView is not available", BrowserAutomationManager.JsErrorCode.WEBVIEW_UNAVAILABLE)
        val safeDepth = max(1, depth).coerceAtMost(MAX_ACCESSIBILITY_DEPTH)
        val safeLimit = max(1, limit).coerceAtMost(MAX_ACCESSIBILITY_ELEMENTS)
        val params = mapOf("depth" to safeDepth, "limit" to safeLimit, "nameTruncate" to NAME_TRUNCATE)
        val script = """
            (function() {
                var p = ${'$'}paramsJson;
                var maxDepth = p.depth;
                var maxElements = p.limit;
                var nameTruncate = p.nameTruncate;
                var out = [];
                var count = 0;
                var seenIds = {};
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
                function accessibleName(el) {
                    var label = el.getAttribute('aria-label');
                    if (label != null && label !== '') return label;
                    var labelledby = el.getAttribute('aria-labelledby');
                    if (labelledby != null && labelledby !== '') {
                        var refs = String(labelledby).trim().split(/\s+/);
                        var names = [];
                        for (var r = 0; r < refs.length; r++) {
                            var ref = document.getElementById(refs[r]);
                            if (ref) names.push((ref.textContent || '').trim());
                        }
                        if (names.length > 0) return names.join(' ');
                    }
                    // Associated <label> for focusable fields.
                    var tag = (el.tagName || '').toUpperCase();
                    if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') {
                        var id = el.id;
                        if (id) {
                            var labelEl = document.querySelector('label[for="' + id + '"]');
                            if (labelEl) return (labelEl.textContent || '').trim();
                        }
                        return el.placeholder || el.name || '';
                    }
                    var text = (el.textContent || '').trim();
                    return text.substring(0, nameTruncate);
                }
                function walk(el, depth) {
                    if (count >= maxElements) return;
                    if (depth > maxDepth) return;
                    if (el.nodeType !== 1) return;
                    var id = el.id;
                    if (id && seenIds[id]) return;
                    if (id) seenIds[id] = true;
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
                            name: accessibleName(el),
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
                return JSON.stringify({ok:true, data:{elements: out, count: out.length, truncated: count >= maxElements, depth: maxDepth}});
            })()
        """.trimIndent().replace("${'$'}paramsJson", manager.gson.toJson(params))
        return manager.evalJson(webView, script, timeoutMs)
    }
}
