#!/data/data/com.termux/files/usr/bin/bash

# Browser Automation Client Library
# Helper functions to control Android WebDroid from Termux via HTTP API.
#
# All JSON is built with jq --arg / --argjson so that URLs, JavaScript code,
# Unicode text, quotes, backslashes and multiline content are transmitted
# safely. No Python, Node.js, Playwright or Chromium is required.

BASE_URL="${BASE_URL:-http://localhost:8765}"
# Set WEB_DROID_RAW=1 to make functions print the raw JSON response body.
WEB_DROID_RAW="${WEB_DROID_RAW:-0}"

# ---------------------------------------------------------------------------
# Dependency check
# ---------------------------------------------------------------------------

# Verify required external dependencies (jq, curl, base64) are available.
# Returns 0 when all are present, 1 otherwise. Does NOT exit the shell: callers
# (individual functions) decide how to fail, so sourcing this library never
# kills an interactive Termux session.
browser_require_deps() {
    local missing=0
    for cmd in jq curl base64; do
        if ! command -v "$cmd" >/dev/null 2>&1; then
            echo "ERROR: required dependency '$cmd' is not installed. Install it and retry." >&2
            missing=1
        fi
    done
    return $missing
}

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
    local tmp_err="$tmp.err"
    local curl_args=(-sS -o "$tmp" -w "%{http_code}" --connect-timeout 10 --max-time 300 "$@")
    if [ -n "$body" ]; then
        code=$(curl "${curl_args[@]}" -X "$method" -H "Content-Type: application/json" -d "$body" "$BASE_URL$path" 2>"$tmp_err") \
            || { RESPONSE_ERROR="connection failed: $BASE_URL$path"; rm -f "$tmp" "$tmp_err"; return 1; }
    else
        code=$(curl "${curl_args[@]}" -X "$method" "$BASE_URL$path" 2>"$tmp_err") \
            || { RESPONSE_ERROR="connection failed: $BASE_URL$path"; rm -f "$tmp" "$tmp_err"; return 1; }
    fi
    RESPONSE_CODE="$code"
    RESPONSE_BODY=$(cat "$tmp" 2>/dev/null)
    rm -f "$tmp" "$tmp_err"
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

# Require a finite number (integer or decimal). Rejects NaN, Infinity, empty,
# malformed values such as "1..2" or ".", and non-numeric input.
require_number() {
    local name="$1"; local value="$2"
    [ -n "$value" ] || { echo "ERROR: $name must be a number, got: (empty)" >&2; return 1; }
    case "$value" in
        NaN|nan|INF|inf|-INF|-inf|Infinity|infinity|-Infinity|-infinity)
            echo "ERROR: $name must be a finite number, got: $value" >&2; return 1 ;;
    esac
    if ! [[ "$value" =~ ^-?([0-9]+(\.[0-9]*)?|\.[0-9]+)$ ]]; then
        echo "ERROR: $name must be a number, got: $value" >&2
        return 1
    fi
    return 0
}

# Require a non-negative integer.
require_unsigned_integer() {
    local name="$1"; local value="$2"
    [ -n "$value" ] || { echo "ERROR: $name must be a non-negative integer, got: (empty)" >&2; return 1; }
    if ! [[ "$value" =~ ^[0-9]+$ ]]; then
        echo "ERROR: $name must be a non-negative integer, got: $value" >&2
        return 1
    fi
    return 0
}

# Require a boolean value (true/false).
require_boolean() {
    local name="$1"; local value="$2"
    case "$value" in
        true|false) return 0 ;;
        *)
            echo "ERROR: $name must be true or false, got: $value" >&2
            return 1 ;;
    esac
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
    browser_require_deps || return 1
    local url="$1"
    require_arg "url" "$url" || return 1
    local body
    body=$(jq -n --arg url "$url" '{url:$url}') || { echo "ERROR: failed to build JSON" >&2; return 1; }
    api_request POST /navigate "$body" || { echo "ERROR: $RESPONSE_ERROR" >&2; return 1; }
    echo "$RESPONSE_BODY" | jq -r '.message // empty'
}

browser_back() {
    browser_require_deps || return 1
    api_request POST /back "" || { echo "ERROR: $RESPONSE_ERROR" >&2; return 1; }
    echo "$RESPONSE_BODY" | jq -r '.message // empty'
}

browser_forward() {
    browser_require_deps || return 1
    api_request POST /forward "" || { echo "ERROR: $RESPONSE_ERROR" >&2; return 1; }
    echo "$RESPONSE_BODY" | jq -r '.message // empty'
}

browser_refresh() {
    browser_require_deps || return 1
    api_request POST /refresh "" || { echo "ERROR: $RESPONSE_ERROR" >&2; return 1; }
    echo "$RESPONSE_BODY" | jq -r '.message // empty'
}

# ---------------------------------------------------------------------------
# Information (legacy endpoints - preserved)
# ---------------------------------------------------------------------------

browser_url() {
    browser_require_deps || return 1
    api_request GET /url "" || { echo "ERROR: $RESPONSE_ERROR" >&2; return 1; }
    echo "$RESPONSE_BODY" | jq -r '.url // empty'
}

browser_title() {
    browser_require_deps || return 1
    api_request GET /title "" || { echo "ERROR: $RESPONSE_ERROR" >&2; return 1; }
    echo "$RESPONSE_BODY" | jq -r '.title // empty'
}

browser_html() {
    browser_require_deps || return 1
    api_request GET /html "" || { echo "ERROR: $RESPONSE_ERROR" >&2; return 1; }
    echo "$RESPONSE_BODY" | jq -r '.html // empty'
}

# ---------------------------------------------------------------------------
# JavaScript (legacy endpoints - preserved, with safe serialization)
# ---------------------------------------------------------------------------

browser_execute() {
    browser_require_deps || return 1
    local script="$1"
    require_arg "script" "$script" || return 1
    local body
    body=$(jq -n --arg script "$script" '{script:$script}') || { echo "ERROR: failed to build JSON" >&2; return 1; }
    api_request POST /execute "$body" || { echo "ERROR: $RESPONSE_ERROR" >&2; return 1; }
    echo "$RESPONSE_BODY" | jq -r '.message // empty'
}

browser_eval() {
    browser_require_deps || return 1
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
    browser_require_deps || return 1
    local output="${1:-screenshot.png}"
    local format="${2:-jpeg}"
    local quality="${3:-75}"
    local scale="${4:-1.0}"
    local max_width="${5:-720}"
    local max_height="${6:-1280}"
    case "$format" in jpeg|png) ;; *) echo "ERROR: format must be jpeg or png, got: $format" >&2; return 1 ;; esac
    require_unsigned_integer "quality" "$quality" || return 1
    [ "$quality" -le 100 ] 2>/dev/null || { echo "ERROR: quality must be 0-100, got: $quality" >&2; return 1; }
    require_number "scale" "$scale" || return 1
    require_unsigned_integer "max_width" "$max_width" || return 1
    require_unsigned_integer "max_height" "$max_height" || return 1
    api_request GET "/screenshot?format=${format}&quality=${quality}&scale=${scale}&max_width=${max_width}&max_height=${max_height}" "" || { echo "ERROR: $RESPONSE_ERROR" >&2; return 1; }
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
    browser_require_deps || return 1
    local selector="$1"; local index="${2:-0}"; local timeout="${3:-10000}"
    require_arg "selector" "$selector" || return 1
    require_unsigned_integer "index" "$index" || return 1
    require_unsigned_integer "timeout" "$timeout" || return 1
    local body
    body=$(jq -n --arg selector "$selector" --argjson index "$index" --argjson timeout "$timeout" '{selector:$selector,index:$index,timeout:$timeout}') \
        || { echo "ERROR: failed to build JSON" >&2; return 1; }
    api_call POST /click "$body"
}

browser_type() {
    browser_require_deps || return 1
    local selector="$1"; local text="$2"; local clear="${3:-false}"; local timeout="${4:-10000}"
    require_arg "selector" "$selector" || return 1
    require_arg "text" "$text" || return 1
    require_boolean "clear" "$clear" || return 1
    require_unsigned_integer "timeout" "$timeout" || return 1
    local body
    body=$(jq -n --arg selector "$selector" --arg text "$text" --argjson clear "$clear" --argjson timeout "$timeout" '{selector:$selector,text:$text,clear:$clear,timeout:$timeout}') \
        || { echo "ERROR: failed to build JSON" >&2; return 1; }
    api_call POST /type "$body"
}

browser_wait() {
    browser_require_deps || return 1
    local selector="$1"; local state="${2:-present}"; local timeout="${3:-10000}"
    require_arg "selector" "$selector" || return 1
    case "$state" in present|visible|hidden) ;; *) echo "ERROR: state must be present|visible|hidden, got: $state" >&2; return 1 ;; esac
    require_unsigned_integer "timeout" "$timeout" || return 1
    local body
    body=$(jq -n --arg selector "$selector" --arg state "$state" --argjson timeout "$timeout" '{selector:$selector,state:$state,timeout:$timeout}') \
        || { echo "ERROR: failed to build JSON" >&2; return 1; }
    api_call POST /wait "$body"
}

browser_find() {
    browser_require_deps || return 1
    local selector="$1"; local all="${2:-false}"; local limit="${3:-50}"
    require_arg "selector" "$selector" || return 1
    require_boolean "all" "$all" || return 1
    require_unsigned_integer "limit" "$limit" || return 1
    local body
    body=$(jq -n --arg selector "$selector" --argjson all "$all" --argjson limit "$limit" '{selector:$selector,all:$all,limit:$limit}') \
        || { echo "ERROR: failed to build JSON" >&2; return 1; }
    api_call POST /find "$body"
}

browser_extract() {
    browser_require_deps || return 1
    local selector="$1"; local fields="$2"; local all="${3:-false}"; local limit="${4:-50}"
    require_arg "selector" "$selector" || return 1
    require_arg "fields" "$fields" || return 1
    require_boolean "all" "$all" || return 1
    require_unsigned_integer "limit" "$limit" || return 1
    # Validate that fields is valid JSON.
    echo "$fields" | jq -e . >/dev/null 2>&1 || { echo "ERROR: 'fields' must be valid JSON" >&2; return 1; }
    local body
    body=$(jq -n --arg selector "$selector" --argjson fields "$fields" --argjson all "$all" --argjson limit "$limit" '{selector:$selector,fields:$fields,all:$all,limit:$limit}') \
        || { echo "ERROR: failed to build JSON" >&2; return 1; }
    api_call POST /extract "$body"
}

browser_scroll() {
    browser_require_deps || return 1
    local direction="$1"; local amount="${2:-300}"; local selector="${3:-}"
    require_arg "direction" "$direction" || return 1
    case "$direction" in up|down|left|right) ;; *) echo "ERROR: direction must be up|down|left|right, got: $direction" >&2; return 1 ;; esac
    require_unsigned_integer "amount" "$amount" || return 1
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
    browser_require_deps || return 1
    local limit="${1:-0}"
    local path="/links"
    require_unsigned_integer "limit" "$limit" || return 1
    if [ "$limit" -gt 0 ] 2>/dev/null; then
        path="/links?limit=${limit}"
    fi
    api_call GET "$path" ""
}

browser_forms() {
    browser_require_deps || return 1
    api_call GET /forms ""
}

browser_state() {
    browser_require_deps || return 1
    api_call GET /page-state ""
}

browser_accessibility() {
    browser_require_deps || return 1
    local depth="${1:-6}"; local limit="${2:-200}"
    require_unsigned_integer "depth" "$depth" || return 1
    require_unsigned_integer "limit" "$limit" || return 1
    api_call GET "/accessibility?depth=${depth}&limit=${limit}" ""
}

# ---------------------------------------------------------------------------
# Utility (legacy - preserved)
# ---------------------------------------------------------------------------

browser_ping() {
    browser_require_deps || return 1
    api_request GET /ping "" || { echo "ERROR: $RESPONSE_ERROR" >&2; return 1; }
    echo "$RESPONSE_BODY" | jq -r '.status // empty'
}

browser_bubble_start() {
    browser_require_deps || return 1
    api_request POST /bubble/start "" || { echo "ERROR: $RESPONSE_ERROR" >&2; return 1; }
    echo "$RESPONSE_BODY" | jq -r '.message // empty'
}

browser_bubble_stop() {
    browser_require_deps || return 1
    api_request POST /bubble/stop "" || { echo "ERROR: $RESPONSE_ERROR" >&2; return 1; }
    echo "$RESPONSE_BODY" | jq -r '.message // empty'
}

browser_ua() {
    browser_require_deps || return 1
    api_request GET /ua "" || { echo "ERROR: $RESPONSE_ERROR" >&2; return 1; }
    echo "$RESPONSE_BODY" | jq -r '"Mode: \(.mode)\nUA: \(.userAgent)"'
}

browser_ua_default() {
    browser_require_deps || return 1
    api_request POST /ua/default "" || { echo "ERROR: $RESPONSE_ERROR" >&2; return 1; }
    echo "$RESPONSE_BODY" | jq -r '.message // empty'
}

browser_ua_google() {
    browser_require_deps || return 1
    api_request POST /ua/google-login "" || { echo "ERROR: $RESPONSE_ERROR" >&2; return 1; }
    echo "$RESPONSE_BODY" | jq -r '.message // empty'
}

browser_ua_custom() {
    browser_require_deps || return 1
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
  browser_screenshot [file] [format] [quality] [scale] [max_width] [max_height]
                                 Save screenshot (default: screenshot.png, jpeg, 75, 1.0, 720, 1280)
                                 format: jpeg|png
                                 scale: 0.1-5.0   max_width/max_height: 1-2048

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
  browser_type 'input[name="q"]' "webdroid automation" true
  browser_wait ".result" present 15000
  browser_extract '.result' '[{"name":"text","kind":"text"},{"name":"url","kind":"link"}]'
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
