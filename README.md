# WebDroid

A lightweight, reliable Android Browser Automation Engine built on WebView,
controllable from Termux (or any HTTP client) via a local HTTP API. Designed to
serve as the browser-control backend for an external AI agent running through
Termux on resource-constrained devices such as the Samsung Galaxy A10 (Android
10, ~1.7 GB RAM, ARM 32-bit userspace).

## Target device

- Samsung Galaxy A10 SM-A105F, Android 10
- Minimum SDK: API 24 | Target SDK: API 34
- CPU: ARM 32-bit Android userspace
- Runtime controller: Termux
- Integration: Local HTTP API + Shell client
- No Playwright, Puppeteer, Selenium, Chromium, Shizuku, or root required.

## Features

- **Floating Bubble UI** — draggable overlay window with resize support
- **HTTP API** — control the browser from Termux on `localhost:8765`
- **Desktop / Google-login / Custom UA modes**
- **JavaScript execution** — run scripts and return results
- **Memory-efficient screenshot** — actual WebView dimensions, JPEG/PNG,
  configurable quality/scale/max-dimensions
- **DOM automation layer** — click, type, wait, find, extract, scroll
- **Page inspection** — links, forms, page-state, accessibility snapshot
- **Password manager support** — works with Bitwarden via auth dialog

## Architecture

```
Termux (curl / browser.sh)
      |
      | HTTP  (localhost:8765, no auth binding changed)
      v
AutomationService (NanoHTTPD)
      |
      +-- BrowserAutomationManager   (bounded CountDownLatch JS execution)
      |     +-- DomInteractionHandler  (click, type, wait, find, extract, scroll)
      |     +-- PageExtractor          (links, forms, page-state, accessibility)
      |     +-- ScreenshotManager      (memory-conscious screenshots)
      |
      v
FloatingBubbleService
      |
      v
WebView (floating overlay)
```

- All WebView operations run on the Android main thread.
- HTTP request processing stays on NanoHTTPD worker threads.
- Every asynchronous WebView operation uses a bounded `CountDownLatch` with an
  explicit timeout (no fragile `wait`/`notify`, no lost notifications, no
  indefinite blocking).
- Screenshots reuse the existing WebView and release Bitmap/stream resources
  promptly.

## Quick Start

### 1. Build & Install

Locally (Termux or any Gradle host):

```bash
cd ~/webdroid
gradle assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or download the debug APK from GitHub Actions (see "GitHub Actions APK
download" below).

### 2. Launch

On first launch, tap **Grant display-over-apps permission** and allow WebDroid
in Android Settings. The app starts its browser services after you return.

```bash
adb shell am start -n io.github.takafu.webdroid/.BrowserActivity
```

### 3. Control from Termux

```bash
source ~/webdroid/client/browser.sh

browser_goto "https://example.com"
browser_title
browser_screenshot screenshot.png
browser_eval "document.title"
```

Set `WEB_DROID_RAW=1` in the environment to make client functions print the raw
JSON response body (useful for AI agents).

## AI Agent Example

The shell client is small enough to be called directly by an external AI agent:

```bash
source ~/webdroid/client/browser.sh

# 1. Navigate to a website
browser_goto "https://example.com"

# 2. Retrieve a compact page-state snapshot (AI-agent friendly)
browser_state

# 3. Find a search input
browser_find 'input[name="q"]'

# 4. Type a query (clears the field first)
browser_type 'input[name="q"]' "webdroid automation" true

# 5. Click the search button
browser_click 'input[type="submit"]'

# 6. Wait for results to appear
browser_wait ".result" present 15000

# 7. Extract relevant content
browser_extract '.result' '[{"name":"text","kind":"text"},{"name":"url","kind":"link"}]'

# 8. Capture a screenshot if needed
browser_screenshot result.png jpeg 60
```

## Shell Client (`client/browser.sh`)

Source the library and call functions. All JSON is built with `jq --arg /
--argjson` so URLs, JavaScript, Unicode text, quotes, backslashes, and
multiline content are transmitted safely. Every function validates required
arguments and reports connection / HTTP / invalid-JSON errors.

| Function | Description |
|----------|-------------|
| `browser_goto <url>` | Navigate to URL |
| `browser_back` | Go back |
| `browser_forward` | Go forward |
| `browser_refresh` | Reload page |
| `browser_url` | Get current URL |
| `browser_title` | Get page title |
| `browser_html` | Get page HTML |
| `browser_execute <script>` | Run JavaScript (no return) |
| `browser_eval <script>` | Run JavaScript and return result |
| `browser_screenshot [file] [format] [quality] [scale] [max_width] [max_height]` | Save screenshot |
| `browser_click <selector> [index] [timeout]` | Click a DOM element |
| `browser_type <selector> <text> [clear] [timeout]` | Type into an input |
| `browser_wait <selector> [state] [timeout]` | Wait for present/visible/hidden |
| `browser_find <selector> [all] [limit]` | Find matching elements |
| `browser_extract <selector> <fields-json> [all] [limit]` | Extract fields |
| `browser_scroll <direction> [amount] [selector]` | Scroll page or element |
| `browser_links [limit]` | List page links |
| `browser_forms` | List forms |
| `browser_state` | Compact page-state snapshot |
| `browser_accessibility [depth] [limit]` | Lightweight accessibility snapshot |
| `browser_ping` | Health check |
| `browser_bubble_start / stop` | Show / close floating bubble |
| `browser_ua / ua_default / ua_google / ua_custom <ua>` | User-agent modes |
| `browser_help` | Show help |

Environment: `WEB_DROID_RAW=1` (raw JSON), `BASE_URL` (override server URL).

### Screenshot parameters

`GET /screenshot?format=jpeg|png&quality=60&scale=0.5&max_width=720&max_height=1280`

Defaults prioritize low memory: `format=jpeg`, `quality=75`, `scale=1.0`,
`max_width=720`, `max_height=1280`. The response is Base64 by default for
backward compatibility; the client decodes it to a PNG file.

## API Endpoints

HTTP server runs on `localhost:8765`. The current HTTP binding and
authentication configuration are intentionally left untouched (handled in a
separate stage).

All automation endpoints return a standard envelope:

Success: `{"success": true, "data": {...}, "error": null}`
Error:   `{"success": false, "data": null, "error": {"code": "...", "message": "..."}}`

Legacy endpoints (navigation, JS execution, info) keep their existing
`{"success": true, "message": "...", ...}` shape for backward compatibility.

### Legacy endpoints (unchanged behavior)

| Method | Endpoint | Body / Query |
|--------|----------|--------------|
| GET | `/ping` | — |
| GET | `/url` | — |
| GET | `/title` | — |
| GET | `/html` | — |
| GET | `/screenshot` | `format`, `quality`, `scale`, `max_width`, `max_height` |
| POST | `/navigate` | `{"url": "..."}` |
| POST | `/back` | — |
| POST | `/forward` | — |
| POST | `/refresh` | — |
| POST | `/execute` | `{"script": "..."}` |
| POST | `/eval` | `{"script": "..."}` |

### Automation endpoints (standard envelope)

| Method | Endpoint | Body |
|--------|----------|------|
| POST | `/click` | `{"selector","index?","timeout?"}` |
| POST | `/type` | `{"selector","text","clear?","timeout?"}` |
| POST | `/wait` | `{"selector","state?","timeout?"}` |
| POST | `/find` | `{"selector","all?","limit?","timeout?"}` |
| POST | `/extract` | `{"selector","fields?","all?","limit?","timeout?"}` |
| POST | `/scroll` | `{"direction","amount?","selector?","timeout?"}` |
| GET | `/links` | `limit?` |
| GET | `/forms` | — |
| GET | `/page-state` | — |
| GET | `/accessibility` | `depth?`, `limit?` |

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

### Screenshot parameters

`GET /screenshot?format=jpeg|png&quality=60&scale=0.5&max_width=720&max_height=1280`

Defaults prioritize low memory: `format=jpeg`, `quality=75`, `scale=1.0`,
`max_width=720`, `max_height=1280`. The response is Base64 by default for
backward compatibility; the client decodes it to a file.

The response now also reports the **actual** image dimensions:

```json
{
  "success": true,
  "data": {
    "screenshot": "<base64>",
    "format": "jpeg",
    "quality": 75,
    "dimensions": {"width": 720, "height": 1280},
    "size_bytes": 42000,
    "requested": {"max_width": 720, "max_height": 1280}
  },
  "error": null
}
```

The WebView is never resized permanently for a screenshot. If the WebView has
not been laid out (zero width/height), capture fails with `WEBVIEW_UNAVAILABLE`
instead of fabricating dimensions.

### Example requests/responses

Click:
```bash
curl -X POST http://localhost:8765/click \
  -H "Content-Type: application/json" \
  -d '{"selector":"input[type=submit]","timeout":10000}'
# -> {"success":true,"data":{"tag":"INPUT","index":0,"total":1,"clicked":true},"error":null}
```

Type:
```bash
curl -X POST http://localhost:8765/type \
  -d '{"selector":"input[name=q]","text":"webdroid","clear":true}'
# -> {"success":true,"data":{"tag":"INPUT","value":"webdroid","cleared":true},"error":null}
```

Wait:
```bash
curl -X POST http://localhost:8765/wait \
  -d '{"selector":".result","state":"present","timeout":15000}'
```

Page state (compact, AI-agent friendly):
```bash
curl http://localhost:8765/page-state
# -> {"success":true,"data":{"url":"...","title":"...","readyState":"complete",...},"error":null}
```


## Known limitations

- No file upload, no popup windows/tabs, no downloads.
- No real hover/pointer/keyboard events: clicks and typing use synthetic
  `dispatchEvent` and the native `Element.click`/value setter. React-controlled
  inputs are handled via the native value setter, but some custom frameworks
  may still not react.
- JavaScript-driven typing may not work with every site's custom input
  framework.
- WebView-based rendering; sites that block WebView may require
  `browser_ua_google`.
- The accessibility snapshot is a lightweight DOM-based approximation, not a
  standards-complete accessibility tree (no Android Accessibility Tree).
- Screenshot capture requires the WebView to have been laid out (non-zero
  dimensions); a hidden/not-yet-rendered WebView returns `WEBVIEW_UNAVAILABLE`.
- Physical-device validation on a Samsung Galaxy A10 has not been performed in
  this environment; install and test on the target device before production use.
- HTTP server security (binding/auth) is intentionally out of scope for this
  release and will be addressed separately.
- No tabs, file upload, download management, or multi-window support.

## GitHub Actions APK download

1. Go to the **Actions** tab of the repository.
2. Select a completed "Build Android APK" run.
3. Under **Artifacts**, download:
   - `webdroid-galaxy-a10-debug` (debug APK — installable on the Galaxy A10)
   - `webdroid-galaxy-a10-release` (release APK, CI test-signed)
4. Artifacts are retained for 14 days.
5. Install: `adb install -r webdroid-galaxy-a10-debug/app-debug.apk`

## Build configuration

- Gradle 8.9, Android Gradle Plugin 8.7.3, Kotlin 2.2.21, JDK 17
- compileSdk 34, minSdk 24, targetSdk 34, Java 17 bytecode
- Dependencies: NanoHTTPD 2.3.1, Gson 2.10.1, AndroidX WebKit 1.8.0
- GitHub Actions builds on `ubuntu-latest` and triggers on push/PR to `main`
  plus manual `workflow_dispatch`.

The `gradle.properties` Termux-specific AAPT2 override
(`android.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2`)
is stripped by the CI workflow so GitHub-hosted builds use the Maven AAPT2;
local Termux builds are unaffected.

## Tech Stack

- Kotlin
- Gradle 8.9
- NanoHTTPD 2.3.1 (embedded HTTP server — not replaced)
- Gson 2.10.1
- AndroidX WebKit 1.8.0

## License

MIT License
