# API Reference

## Base URL

`http://127.0.0.1:8765`

All endpoints, including `/ping`, require `Authorization: Bearer <token>`. Copy
the per-install token from the WebDroid app and set it in Termux:

```bash
read -rsp "WebDroid API token: " WEB_DROID_TOKEN; echo; export WEB_DROID_TOKEN
```

The server binds only to IPv4 loopback, not Wi-Fi/LAN. Keep the token private.
Request bodies are limited to 256 KiB. `/eval` and `/execute` return
`UNSAFE_JS_DISABLED` until privileged JavaScript is explicitly enabled in the
app UI. Arbitrary JavaScript can read page passwords and other secrets; password
field values are redacted by the structured `type`, `find`, `extract`, and `/html`
endpoints, but that does not constrain explicitly enabled JavaScript.

## Response Format

### Success (automation endpoints)
```json
{
  "success": true,
  "data": { ... },
  "error": null
}
```

### Error (automation endpoints)
```json
{
  "success": false,
  "data": null,
  "error": { "code": "...", "message": "..." }
}
```

Legacy navigation/info endpoints keep the older `{"success": true, "message": "...", ...}` shape.

### Error codes

| Code | Meaning |
|------|---------|
| `INVALID_SELECTOR` | CSS selector could not be parsed |
| `ELEMENT_NOT_FOUND` | No element matched the selector / index |
| `ELEMENT_NOT_INTERACTABLE` | Element disabled, hidden, read-only, type=file, or off-screen |
| `INVALID_PARAMETER` | Bad request parameter (state, direction, timeout, etc.) |
| `TIMEOUT` | Operation did not complete within the deadline |
| `WEBVIEW_UNAVAILABLE` | WebView not ready or not laid out |
| `JAVASCRIPT_ERROR` | JavaScript threw or failed to schedule |
| `SCREENSHOT_FAILED` | Bitmap allocation or encoding failed |
| `REQUEST_INTERRUPTED` | Thread was interrupted while waiting |
| `NOT_FOUND` | Unknown endpoint |
| `MISSING_PARAMETER` | Required body parameter absent |
| `UNAUTHORIZED` | Missing or invalid Bearer token |
| `UNSAFE_JS_DISABLED` | Privileged JavaScript is disabled in the app UI |

---

## Navigation

### POST /navigate

Navigate to a URL.

```bash
curl -X POST http://127.0.0.1:8765/navigate \
  -H "Authorization: Bearer $WEB_DROID_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"url":"https://example.com"}'
```

### POST /back

Go back in history.

### POST /forward

Go forward in history.

### POST /refresh

Reload the page.

---

## Information

### GET /url

Get current URL.

```bash
curl http://127.0.0.1:8765/url -H "Authorization: Bearer $WEB_DROID_TOKEN"
# {"success":true,"url":"https://example.com/"}
```

### GET /title

Get page title.

### GET /html

Get full page HTML (JSON escaped).

### GET /screenshot

Get screenshot as Base64 PNG.

```bash
curl -s http://127.0.0.1:8765/screenshot -H "Authorization: Bearer $WEB_DROID_TOKEN" | jq -r '.screenshot' | base64 -d > screenshot.png
```

---

## JavaScript

### POST /execute

Run JavaScript without return value (fast). Requires privileged JavaScript to
be enabled in the app UI; this gives the caller access to page secrets.

```bash
curl -X POST http://127.0.0.1:8765/execute \
  -H "Authorization: Bearer $WEB_DROID_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"script":"console.log(\"Hello\")"}'
```

### POST /eval

Run JavaScript and get result (5s timeout). Requires privileged JavaScript to
be enabled in the app UI; treat this as full access to page data.

```bash
curl -X POST http://127.0.0.1:8765/eval \
  -H "Authorization: Bearer $WEB_DROID_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"script":"document.title"}'
```

---

## Utility

### GET /ping

Health check.

```bash
curl http://127.0.0.1:8765/ping -H "Authorization: Bearer $WEB_DROID_TOKEN"
# {"success":true,"message":"pong","status":"ok"}
```

---

## Client Library

```bash
source ~/webdroid/client/browser.sh
export WEB_DROID_TOKEN="<paste the token copied from the app>"

browser_goto <url>          # Navigate
browser_back                # Go back
browser_forward             # Go forward
browser_refresh             # Reload
browser_url                 # Get URL
browser_title               # Get title
browser_html                # Get HTML
browser_execute <script>    # Run JS (no return)
browser_eval <script>       # Run JS (with return)
browser_screenshot [file]   # Take screenshot
browser_ping                # Health check
```

---

## Error Handling

| Error | Cause | Solution |
|-------|-------|----------|
| `Timeout` | Operation took too long | Wait longer, retry |
| `Missing parameter` | Required param missing | Check request JSON |
| `Not found` | Invalid endpoint | Check URL |
