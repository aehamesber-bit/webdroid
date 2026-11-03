#!/data/data/com.termux/files/usr/bin/bash

# Browser Automation Client Library
# Helper functions to control Android WebDroid from Termux via HTTP API.
#
# All JSON is built with jq --arg / --argjson so that URLs, JavaScript code,
# Unicode text, quotes, backslashes and multiline content are transmitted
# safely. No Python, Node.js, Playwright or Chromium is required.

BASE_URL="http://localhost:8765"
# Set WEB_DROID_RAW=1 to make functions print the raw JSON response body.
WEB_DROID_RAW="${WEB_DROID_RAW:-0}"

# ---------------------------------------------------------------------------
# Internal HTTP helpers
# ---------------------------------------------------------------------------

# Sets RESPONSE_BODY, RESPONSE_CODE, RESPONSE_ERROR.
# Returns 0 only on a 2xx HTTP response; non-zero on transport or HTTP error.
api_request() {
    local method="$1"; local path="$2"; local body="$3"; shift 3
    RESPONSE_BODY=""; RESPONSE_CODE=""; RESPONSE_ERROR=""
    local tmp code
    tmp=$(mktemp) || { RESPONSE_ERROR="failed to create temp file"; return 1; }
    local curl_args=(-sS -o "$tmp" -w "%{http_code}" --connect-timeout 10 --max-time 300 "$@")
    if [ -n "$body" ]; then
        code=$(curl "${curl_args[@]}" -X "$method" -H "Content-Type: application/json" -d "$body" "$BASE_URL$path" 2>"$tmp.err") \
            || { RESPONSE_ERROR="connection failed: $BASE_URL$path"; rm -f "$tmp" "$tmp.err"; return 1; }
    else
        code=$(curl "${curl_args[@]}" -X "$method" "$BASE_URL$path" 2>"$tmp.err") \
            || { RESPONSE_ERROR="connection failed: $BASE_URL$path"; rm -f "$tmp" "$tmp.err"; return 1; }
    fi
    RESPONSE_CODE="$code"
    RESPONSE_BODY=$(cat "$tmp" 2>/dev/null)
    rm -f "$tmp" "$tmp.err"
    if [ -z "$RESPONSE_CODE" ]; then
        RESPONSE_ERROR="empty response from server"
        return 1
    fi
    if [ "${RESPONSE_CODE:0:1}" != "2" ]; then
        RESPONSE_ERROR="HTTP ${RESPONSE_CODE}: ${RESPONSE_BODY}"
        return 1
    fi
    return 0
}

# Require a non-empty argument.
require_arg() {
    local name="$1"; local value="$2"
    if [ -z "$value" ]; then
        echo "ERROR: $name is required" >&2
        return 1
    fi
    return 0
}

# Return 0 if the response body is a successful JSON envelope.
json_expect_success() {
    local body="$1"
    if echo "$body" | jq -e '.success == true' >/dev/null 2>&1; then
        return 0
    fi
    local msg
    msg=$(echo "$body" | jq -r '.error.message // .error // "request failed"' 2>/dev/null)
    echo "ERROR: $msg" >&2
    return 1
}

# Call a JSON endpoint returning the standard automation envelope:
#   { "success": true, "data": {...}, "error": null }
# On success prints .data (or .message) unless WEB_DROID_RAW=1 (whole body).
api_call() {
    local method="$1"; local path="$2"; local body="$3"
    api_request "$method" "$path" "$body" || { echo "ERROR: $RESPONSE_ERROR" >&2; return 1; }
    if [ "$WEB_DROID_RAW" = "1" ]; then
        echo "$RESPONSE_BODY"
        return 0
    fi
    json_expect_success "$RESPONSE_BODY" || return 1
    echo "$RESPONSE_BODY" | jq -r 'if .data != null then .data else (.message // "ok") end'
}

# ---------------------------------------------------------------------------
# Navigation (legacy endpoints - preserved)
# ---------------------------------------------------------------------------

browser_goto() {
    local url="$1"
    require_arg "url" "$url" || return 1
    local body
    body=$(jq -n --arg url "$url" '{url:$url}') || { echo "ERROR: failed to build JSON" >&2; return 1; }
    api_request POST /navigate "$body" || { echo "ERROR: $RESPONSE_ERROR" >&2; return 1; }
    echo "$RESPONSE_BODY" | jq -r '.message // empty'
}

browser_back() {
    api_request POST /back "" || { echo "ERROR: $RESPONSE_ERROR" >&2; return 1; }
    echo "$RESPONSE_BODY" | jq -r '.message // empty'
}

browser_forward() {
    api_request POST /forward "" || { echo "ERROR: $RESPONSE_ERROR" >&2; return 1; }
    echo "$RESPONSE_BODY" | jq -r '.message // empty'
}

browser_refresh() {
    api_request POST /refresh "" || { echo "ERROR: $RESPONSE_ERROR" >&2; return 1; }
    echo "$RESPONSE_BODY" | jq -r '.message // empty'
}

# ---------------------------------------------------------------------------
# Information (legacy endpoints - preserved)
# ---------------------------------------------------------------------------

browser_url() {
    api_request GET /url "" || { echo "ERROR: $RESPONSE_ERROR" >&2; return 1; }
    echo "$RESPONSE_BODY" | jq -r '.url // empty'
}

browser_title() {
    api_request GET /title "" || { echo "ERROR: $RESPONSE_ERROR" >&2; return 1; }
    echo "$RESPONSE_BODY" | jq -r '.title // empty'
}

browser_html() {
    api_request GET /html "" || { echo "ERROR: $RESPONSE_ERROR" >&2; return 1; }
    echo "$RESPONSE_BODY" | jq -r '.html // empty'
}

# ---------------------------------------------------------------------------
# JavaScript (legacy endpoints - preserved, now with safe serialization)
# ---------------------------------------------------------------------------

browser_execute() {
    local script="$1"
    require_arg "script" "$script" || return 1
    local body
    body=$(jq -n --arg script "$script" '{script:$script}') || { echo "ERROR: failed to build JSON" >&2; return 1; }
    api_request POST /execute "$body" || { echo "ERROR: $RESPONSE_ERROR" >&2; return 1; }
    echo "$RESPONSE_BODY" | jq -r '.message // empty'
}

browser_eval() {
    local script="$1"
    require_arg "script" "$script" || return 1
    local body
    body=$(jq -n --arg script "$script" '{script:$script}') || { echo "ERROR: failed to build JSON" >&2; return 1; }
    api_request POST /eval "$body" || { echo "ERROR: $RESPONSE_ERROR" >&2; return 1; }
    echo "$RESPONSE_BODY" | jq -r '.result // empty'
}

# ---------------------------------------------------------------------------
# Screenshot (legacy endpoint - preserved, with efficiency options)
# ---------------------------------------------------------------------------

browser_screenshot() {
    local output="${1:-screenshot.png}"
    local format="${2:-jpeg}"
    local quality="${3:-75}"
    api_request GET "/screenshot?format=${format}&quality=${quality}" "" || { echo "ERROR: $RESPONSE_ERROR" >&2; return 1; }
    if [ "$WEB_DROID_RAW" = "1" ]; then
        echo "$RESPONSE_BODY"
        return 0
    fi
    if ! json_expect_success "$RESPONSE_BODY"; then return 1; fi
    local screenshot
    screenshot=$(echo "$RESPONSE_BODY" | jq -r '.data.screenshot // empty')
    if [ -z "$screenshot" ] || [ "$screenshot" = "null" ]; then
        echo "Screenshot failed: $(echo "$RESPONSE_BODY" | jq -r '.error.message // .error // "unknown error"')" >&2
        return 1
    fi
    echo "$screenshot" | base64 -d > "$output"
    local size
    size=$(wc -c < "$output" 2>/dev/null || echo 0)
    if [ "$size" -lt 100 ]; then
        echo "Screenshot failed: file too small (${size} bytes), WebView may not be ready" >&2
        rm -f "$output"
        return 1
    fi
    echo "Screenshot saved to $output (${size} bytes)"
}

# ---------------------------------------------------------------------------
# DOM interaction
# ---------------------------------------------------------------------------

browser_click() {
    local selector="$1"; local index="${2:-0}"; local timeout="${3:-10000}"
    require_arg "selector" "$selector" || return 1
    local body
    body=$(jq -n --arg selector "$selector" --argjson index "$index" --argjson timeout "$timeout" '{selector:$selector,index:$index,timeout:$timeout}') \
        || { echo "ERROR: failed to build JSON" >&2; return 1; }
    api_call POST /click "$body"
}

browser_type() {
    local selector="$1"; local text="$2"; local clear="${3:-false}"; local timeout="${4:-10000}"
    require_arg "selector" "$selector" || return 1
    require_arg "text" "$text" || return 1
    local body
    body=$(jq -n --arg selector "$selector" --arg text "$text" --argjson clear "$clear" --argjson timeout "$timeout" '{selector:$selector,text:$text,clear:$clear,timeout:$timeout}') \
        || { echo "ERROR: failed to build JSON" >&2; return 1; }
    api_call POST /type "$body"
}

browser_wait() {
    local selector="$1"; local state="${2:-present}"; local timeout="${3:-10000}"
    require_arg "selector" "$selector" || return 1
    local body
    body=$(jq -n --arg selector "$selector" --arg state "$state" --argjson timeout "$timeout" '{selector:$selector,state:$state,timeout:$timeout}') \
        || { echo "ERROR: failed to build JSON" >&2; return 1; }
    api_call POST /wait "$body"
}

browser_find() {
    local selector="$1"; local all="${2:-false}"; local limit="${3:-50}"
    require_arg "selector" "$selector" || return 1
    local body
    body=$(jq -n --arg selector "$selector" --argjson all "$all" --argjson limit "$limit" '{selector:$selector,all:$all,limit:$limit}') \
        || { echo "ERROR: failed to build JSON" >&2; return 1; }
    api_call POST /find "$body"
}

browser_extract() {
    local selector="$1"; local fields="$2"; local all="${3:-false}"; local limit="${4:-50}"
    require_arg "selector" "$selector" || return 1
    require_arg "fields" "$fields" || return 1
    # Validate that fields is valid JSON.
    echo "$fields" | jq -e . >/dev/null 2>&1 || { echo "ERROR: 'fields' must be valid JSON" >&2; return 1; }
    local body
    body=$(jq -n --arg selector "$selector" --argjson fields "$fields" --argjson all "$all" --argjson limit "$limit" '{selector:$selector,fields:$fields,all:$all,limit:$limit}') \
        || { echo "ERROR: failed to build JSON" >&2; return 1; }
    api_call POST /extract "$body"
}

browser_scroll() {
    local direction="$1"; local amount="${2:-300}"; local selector="${3:-}"
    require_arg "direction" "$direction" || return 1
    local body
    if [ -n "$selector" ]; then
        body=$(jq -n --arg direction "$direction" --argjson amount "$amount" --arg selector "$selector" '{direction:$direction,amount:$amount,selector:$selector}') \
            || { echo "ERROR: failed to build JSON" >&2; return 1; }
    else
        body=$(jq -n --arg direction "$direction" --argjson amount "$amount" '{direction:$direction,amount:$amount}') \
            || { echo "ERROR: failed to build JSON" >&2; return 1; }
    fi
    api_call POST /scroll "$body"
}

# ---------------------------------------------------------------------------
# Page inspection
# ---------------------------------------------------------------------------

browser_links() {
    local limit="${1:-0}"
    local path="/links"
    if [ "$limit" -gt 0 ] 2>/dev/null; then
        path="/links?limit=${limit}"
    fi
    api_call GET "$path" ""
}

browser_forms() {
    api_call GET /forms ""
}

browser_state() {
    api_call GET /page-state ""
}

browser_accessibility() {
    local depth="${1:-6}"; local limit="${2:-200}"
    api_call GET "/accessibility?depth=${depth}&limit=${limit}" ""
}

# ---------------------------------------------------------------------------
# Utility (legacy - preserved)
# ---------------------------------------------------------------------------

browser_ping() {
    api_request GET /ping "" || { echo "ERROR: $RESPONSE_ERROR" >&2; return 1; }
    echo "$RESPONSE_BODY" | jq -r '.status // empty'
}

browser_bubble_start() {
    api_request POST /bubble/start "" || { echo "ERROR: $RESPONSE_ERROR" >&2; return 1; }
    echo "$RESPONSE_BODY" | jq -r '.message // empty'
}

browser_bubble_stop() {
    api_request POST /bubble/stop "" || { echo "ERROR: $RESPONSE_ERROR" >&2; return 1; }
    echo "$RESPONSE_BODY" | jq -r '.message // empty'
}

browser_ua() {
    api_request GET /ua "" || { echo "ERROR: $RESPONSE_ERROR" >&2; return 1; }
    echo "$RESPONSE_BODY" | jq -r '"Mode: \(.mode)\nUA: \(.userAgent)"'
}

browser_ua_default() {
    api_request POST /ua/default "" || { echo "ERROR: $RESPONSE_ERROR" >&2; return 1; }
    echo "$RESPONSE_BODY" | jq -r '.message // empty'
}

browser_ua_google() {
    api_request POST /ua/google-login "" || { echo "ERROR: $RESPONSE_ERROR" >&2; return 1; }
    echo "$RESPONSE_BODY" | jq -r '.message // empty'
}

browser_ua_custom() {
    local ua="$1"
    require_arg "ua" "$ua" || return 1
    local body
    body=$(jq -n --arg ua "$ua" '{ua:$ua}') || { echo "ERROR: failed to build JSON" >&2; return 1; }
    api_request POST /ua/custom "$body" || { echo "ERROR: $RESPONSE_ERROR" >&2; return 1; }
    echo "$RESPONSE_BODY" | jq -r '.message // empty'
}

# ---------------------------------------------------------------------------
# Help
# ---------------------------------------------------------------------------

browser_help() {
    cat << 'EOF'
Browser Automation Client - Usage

Navigation:
  browser_goto <url>          Navigate to URL
  browser_back                Go back
  browser_forward             Go forward
  browser_refresh             Reload page

Information:
  browser_url                 Get current URL
  browser_title               Get page title
  browser_html                Get full HTML

JavaScript:
  browser_execute <script>    Execute script (no return)
  browser_eval <script>       Execute script and get result

Screenshot:
  browser_screenshot [file] [format] [quality]
                              Save screenshot (default: screenshot.png, jpeg, 75)
                              format: jpeg|png

DOM interaction:
  browser_click <selector> [index] [timeout]
  browser_type <selector> <text> [clear] [timeout]
  browser_wait <selector> [state] [timeout]
                              state: present|visible|hidden
  browser_find <selector> [all] [limit]
  browser_extract <selector> <fields-json> [all] [limit]
  browser_scroll <direction> [amount] [selector]
                              direction: up|down|left|right

Page inspection:
  browser_links [limit]
  browser_forms
  browser_state
  browser_accessibility [depth] [limit]

UserAgent:
  browser_ua                  Show current UA mode
  browser_ua_default          Set to Desktop Chrome (default)
  browser_ua_google           Set to google-login mode (for Google login)
  browser_ua_custom <ua>      Set custom UA string

Other:
  browser_ping                Check server connection
  browser_bubble_start        Show floating bubble
  browser_bubble_stop         Close floating bubble
  browser_help                Show this help

Environment:
  WEB_DROID_RAW=1             Print raw JSON response bodies
  BASE_URL                    Override server URL (default: http://localhost:8765)

Examples:
  browser_goto "https://example.com"
  browser_title
  browser_eval "document.title"
  browser_click "input[type=submit]"
  browser_type 'input[name="q"]' "webdroid" true
  browser_wait "div.results" present 10000
  browser_extract 'a' '[{"name":"title","kind":"text"},{"name":"url","kind":"link"}]'
  browser_state
  browser_screenshot step1.png jpeg 60

  # External AI agent flow
  browser_goto "https://example.com"
  browser_state
  browser_type 'input[name="q"]' "webdroid automation" true
  browser_click "input[type=submit]"
  browser_wait "div.result" present 10000
  browser_extract 'div.result' '[{"name":"text","kind":"text"}]'
  browser_screenshot result.png jpeg 60
EOF
}
