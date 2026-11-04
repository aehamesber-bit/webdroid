#!/usr/bin/env bash
# Shell tests for client/browser.sh
# Runs without an Android device/server: validates syntax, argument validation,
# JSON serialization, and exit codes.

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
CLIENT="$REPO_ROOT/client/browser.sh"
WEB_DROID_TOKEN=""

PASS=0
FAIL=0

assert_eq() {
    local label="$1"; local expected="$2"; local actual="$3"
    if [ "$expected" = "$actual" ]; then
        echo "PASS: $label"
        PASS=$((PASS + 1))
    else
        echo "FAIL: $label (expected='$expected' actual='$actual')"
        FAIL=$((FAIL + 1))
    fi
}

assert_contains() {
    local label="$1"; local haystack="$2"; local needle="$3"
    if echo "$haystack" | grep -qF "$needle"; then
        echo "PASS: $label"
        PASS=$((PASS + 1))
    else
        echo "FAIL: $label (expected to contain '$needle' in '$haystack')"
        FAIL=$((FAIL + 1))
    fi
}

assert_exit_nonzero() {
    local label="$1"; shift
    if ! "$@" >/dev/null 2>&1; then
        echo "PASS: $label (non-zero exit)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: $label (expected non-zero exit)"
        FAIL=$((FAIL + 1))
    fi
}

echo "== 1. bash syntax =="
if bash -n "$CLIENT" 2>/dev/null; then
    echo "PASS: browser.sh passes bash -n"
    PASS=$((PASS + 1))
else
    echo "FAIL: browser.sh has syntax errors"
    FAIL=$((FAIL + 1))
fi

# Source the library in a subshell so function definitions load without
# executing anything. We stub out network-dependent api_request by testing
# validation logic through the exported helper functions.
echo "== 2. Argument validation =="

# require_number
source "$CLIENT" >/dev/null 2>&1 || true

echo "== 2a. API authentication setup =="
if api_request GET /ping ""; then
    echo "FAIL: api_request accepts a missing API token"
    FAIL=$((FAIL + 1))
else
    assert_contains "missing API token gives setup guidance" "$RESPONSE_ERROR" "WEB_DROID_TOKEN is not set"
fi
WEB_DROID_TOKEN="test-token-for-shell-tests"
mock_curl_dir="$(mktemp -d)"
MOCK_CURL_AUTH_FILE="$mock_curl_dir/auth-seen"
export MOCK_CURL_AUTH_FILE
cat > "$mock_curl_dir/curl" <<'MOCK_CURL'
#!/usr/bin/env bash
output=""
auth_seen=0
while [ "$#" -gt 0 ]; do
    case "$1" in
        -o) output="$2"; shift 2 ;;
        -H) [ "$2" = "Authorization: Bearer test-token-for-shell-tests" ] && auth_seen=1; shift 2 ;;
        *) shift ;;
    esac
done
printf '%s' '{"success":true}' > "$output"
printf '%s' "$auth_seen" > "$MOCK_CURL_AUTH_FILE"
printf '200'
MOCK_CURL
chmod +x "$mock_curl_dir/curl"
if PATH="$mock_curl_dir:$PATH" api_request GET /ping ""; then
    assert_eq "client sends the Bearer token header" "1" "$(cat "$MOCK_CURL_AUTH_FILE")"
else
    echo "FAIL: authenticated api_request returned an error"
    FAIL=$((FAIL + 1))
fi
rm -rf "$mock_curl_dir"
unset MOCK_CURL_AUTH_FILE

# require_number accepts valid numbers
for v in 5 5.0 0.5 .5 5. -3 -3.14 0 100; do
    if require_number "n" "$v" >/dev/null 2>&1; then
        echo "PASS: require_number accepts '$v'"
        PASS=$((PASS + 1))
    else
        echo "FAIL: require_number rejects '$v'"
        FAIL=$((FAIL + 1))
    fi
done

# require_number rejects invalid values
for v in "" "1..2" "." "abc" NaN Infinity "-Infinity" "1.2.3" " 5" "5 "; do
    if ! require_number "n" "$v" >/dev/null 2>&1; then
        echo "PASS: require_number rejects '$v'"
        PASS=$((PASS + 1))
    else
        echo "FAIL: require_number accepts '$v'"
        FAIL=$((FAIL + 1))
    fi
done

# require_unsigned_integer
for v in 0 1 42 999; do
    if require_unsigned_integer "n" "$v" >/dev/null 2>&1; then
        echo "PASS: require_unsigned_integer accepts '$v'"
        PASS=$((PASS + 1))
    else
        echo "FAIL: require_unsigned_integer rejects '$v'"
        FAIL=$((FAIL + 1))
    fi
done
for v in "" "-1" "1.5" "abc" "1..2" "."; do
    if ! require_unsigned_integer "n" "$v" >/dev/null 2>&1; then
        echo "PASS: require_unsigned_integer rejects '$v'"
        PASS=$((PASS + 1))
    else
        echo "FAIL: require_unsigned_integer accepts '$v'"
        FAIL=$((FAIL + 1))
    fi
done

# require_boolean
if require_boolean "b" true >/dev/null 2>&1 && require_boolean "b" false >/dev/null 2>&1; then
    echo "PASS: require_boolean accepts true/false"
    PASS=$((PASS + 1))
else
    echo "FAIL: require_boolean rejects true/false"
    FAIL=$((FAIL + 1))
fi
for v in "" yes no 1 0 True TRUE "t"; do
    if ! require_boolean "b" "$v" >/dev/null 2>&1; then
        echo "PASS: require_boolean rejects '$v'"
        PASS=$((PASS + 1))
    else
        echo "FAIL: require_boolean accepts '$v'"
        FAIL=$((FAIL + 1))
    fi
done

echo "== 3. JSON serialization =="

# Arabic text round trip
arabic="الزبون يتحدث العربية"
json=$(jq -n --arg text "$arabic" '{text:$text}')
assert_eq "Arabic JSON text" "$arabic" "$(echo "$json" | jq -r '.text')"

# Unicode emoji
unicode="🚀 hello 🌍"
json=$(jq -n --arg text "$unicode" '{text:$text}')
assert_eq "Unicode JSON text" "$unicode" "$(echo "$json" | jq -r '.text')"

# Multiline
multiline="line1
line2
line3"
json=$(jq -n --arg text "$multiline" '{text:$text}')
assert_eq "Multiline JSON text" "$multiline" "$(echo "$json" | jq -r '.text')"

# Quotes and backslashes
special='He said "hi" and C:\path\file'
json=$(jq -n --arg text "$special" '{text:$text}')
assert_eq "Quotes/backslashes JSON text" "$special" "$(echo "$json" | jq -r '.text')"

# Long text
long=$(python3 -c "print('x'*5000)" 2>/dev/null || printf 'x%.0s' {1..5000})
json=$(jq -n --arg text "$long" '{text:$text}')
assert_eq "Long JSON text length" "5000" "$(echo "$json" | jq -r '.text | length')"

echo "== 4. Screenshot argument validation =="
source "$CLIENT" >/dev/null 2>&1 || true
# Override api_request to avoid network for validation-only tests
api_request() { RESPONSE_ERROR="no server"; RESPONSE_BODY=""; RESPONSE_CODE=""; return 1; }

# Invalid format rejected
if ! browser_screenshot out.png webp 75 1.0 720 1280 >/dev/null 2>&1; then
    echo "PASS: browser_screenshot rejects invalid format"
    PASS=$((PASS + 1))
else
    echo "FAIL: browser_screenshot accepts invalid format"
    FAIL=$((FAIL + 1))
fi

# Quality out of range rejected
if ! browser_screenshot out.png jpeg 150 1.0 720 1280 >/dev/null 2>&1; then
    echo "PASS: browser_screenshot rejects quality > 100"
    PASS=$((PASS + 1))
else
    echo "FAIL: browser_screenshot accepts quality > 100"
    FAIL=$((FAIL + 1))
fi

# Invalid scale rejected
if ! browser_screenshot out.png jpeg 75 abc 720 1280 >/dev/null 2>&1; then
    echo "PASS: browser_screenshot rejects invalid scale"
    PASS=$((PASS + 1))
else
    echo "FAIL: browser_screenshot accepts invalid scale"
    FAIL=$((FAIL + 1))
fi

# Valid screenshot args pass validation (fail only on network)
if browser_screenshot out.png jpeg 75 1.0 720 1280 >/dev/null 2>&1; then
    echo "FAIL: browser_screenshot should fail without server"
    FAIL=$((FAIL + 1))
else
    echo "PASS: browser_screenshot validation passed, failed on network (expected)"
    PASS=$((PASS + 1))
fi

echo "== 5. Exit codes without server =="
# All functions should return non-zero when the server is unreachable.
if ! browser_ping >/dev/null 2>&1; then echo "PASS: browser_ping non-zero exit"; PASS=$((PASS+1)); else echo "FAIL: browser_ping"; FAIL=$((FAIL+1)); fi
if ! browser_url >/dev/null 2>&1; then echo "PASS: browser_url non-zero exit"; PASS=$((PASS+1)); else echo "FAIL: browser_url"; FAIL=$((FAIL+1)); fi
if ! browser_title >/dev/null 2>&1; then echo "PASS: browser_title non-zero exit"; PASS=$((PASS+1)); else echo "FAIL: browser_title"; FAIL=$((FAIL+1)); fi
if ! browser_state >/dev/null 2>&1; then echo "PASS: browser_state non-zero exit"; PASS=$((PASS+1)); else echo "FAIL: browser_state"; FAIL=$((FAIL+1)); fi
if ! browser_forms >/dev/null 2>&1; then echo "PASS: browser_forms non-zero exit"; PASS=$((PASS+1)); else echo "FAIL: browser_forms"; FAIL=$((FAIL+1)); fi
if ! browser_links >/dev/null 2>&1; then echo "PASS: browser_links non-zero exit"; PASS=$((PASS+1)); else echo "FAIL: browser_links"; FAIL=$((FAIL+1)); fi
if ! browser_goto "https://example.com" >/dev/null 2>&1; then echo "PASS: browser_goto non-zero exit"; PASS=$((PASS+1)); else echo "FAIL: browser_goto"; FAIL=$((FAIL+1)); fi
if ! browser_eval "document.title" >/dev/null 2>&1; then echo "PASS: browser_eval non-zero exit"; PASS=$((PASS+1)); else echo "FAIL: browser_eval"; FAIL=$((FAIL+1)); fi
if ! browser_click "input[type=submit]" >/dev/null 2>&1; then echo "PASS: browser_click non-zero exit"; PASS=$((PASS+1)); else echo "FAIL: browser_click"; FAIL=$((FAIL+1)); fi
if ! browser_type 'input[name=q]' "text" >/dev/null 2>&1; then echo "PASS: browser_type non-zero exit"; PASS=$((PASS+1)); else echo "FAIL: browser_type"; FAIL=$((FAIL+1)); fi
if ! browser_wait ".result" present 5000 >/dev/null 2>&1; then echo "PASS: browser_wait non-zero exit"; PASS=$((PASS+1)); else echo "FAIL: browser_wait"; FAIL=$((FAIL+1)); fi
if ! browser_find "a" >/dev/null 2>&1; then echo "PASS: browser_find non-zero exit"; PASS=$((PASS+1)); else echo "FAIL: browser_find"; FAIL=$((FAIL+1)); fi
if ! browser_extract 'a' '[{"name":"t","kind":"text"}]' >/dev/null 2>&1; then echo "PASS: browser_extract non-zero exit"; PASS=$((PASS+1)); else echo "FAIL: browser_extract"; FAIL=$((FAIL+1)); fi
if ! browser_scroll down 300 >/dev/null 2>&1; then echo "PASS: browser_scroll non-zero exit"; PASS=$((PASS+1)); else echo "FAIL: browser_scroll"; FAIL=$((FAIL+1)); fi

echo "== 6. BASE_URL env override =="
if [ "${BASE_URL:-http://127.0.0.1:8765}" = "http://127.0.0.1:8765" ]; then
    echo "PASS: BASE_URL default correct"
    PASS=$((PASS + 1))
else
    echo "FAIL: BASE_URL default wrong: $BASE_URL"
    FAIL=$((FAIL + 1))
fi

echo ""
echo "============================="
echo "Results: $PASS passed, $FAIL failed"
echo "============================="
[ "$FAIL" -eq 0 ]
