# API Reference

## Base URL

`http://localhost:8765`

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

---

## Navigation

### POST /navigate

Navigate to a URL.

```bash
curl -X POST http://localhost:8765/navigate \
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
curl http://localhost:8765/url
# {"success":true,"url":"https://example.com/"}
```

### GET /title

Get page title.

### GET /html

Get full page HTML (JSON escaped).

### GET /screenshot

Get screenshot as Base64 PNG.

```bash
curl -s http://localhost:8765/screenshot | jq -r '.screenshot' | base64 -d > screenshot.png
```

---

## JavaScript

### POST /execute

Run JavaScript without return value (fast).

```bash
curl -X POST http://localhost:8765/execute \
  -H "Content-Type: application/json" \
  -d '{"script":"console.log(\"Hello\")"}'
```

### POST /eval

Run JavaScript and get result (5s timeout).

```bash
curl -X POST http://localhost:8765/eval \
  -H "Content-Type: application/json" \
  -d '{"script":"document.title"}'
```

---

## Utility

### GET /ping

Health check.

```bash
curl http://localhost:8765/ping
# {"success":true,"message":"pong","status":"ok"}
```

---

## Client Library

```bash
source ~/webdroid/client/browser.sh

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
