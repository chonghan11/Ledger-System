#!/usr/bin/env bash
# End-to-end smoke test against a RUNNING ledger (default http://localhost:8080).
# Exercises the real HTTP API and the UI assets; exits non-zero on the first failed check.
# Usage: scripts/smoke-test.sh [base-url]
set -euo pipefail

BASE="${1:-http://localhost:8080}"
RUN="smoke-$(date +%s)-$RANDOM"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
PASSED=0

fail() { echo "FAIL: $*" >&2; echo "--- last response ($STATUS) ---" >&2; cat "$TMP/body" >&2 || true; echo >&2; exit 1; }
pass() { PASSED=$((PASSED + 1)); echo "  ok  $*"; }

# req METHOD PATH [JSON_BODY] [IDEMPOTENCY_KEY] -> sets STATUS, BODY; headers in $TMP/headers
req() {
  local method="$1" path="$2" body="${3:-}" key="${4:-}"
  local args=(-s -o "$TMP/body" -D "$TMP/headers" -w '%{http_code}' -X "$method" "$BASE$path")
  [[ -n "$body" ]] && args+=(-H 'Content-Type: application/json' --data "$body")
  [[ -n "$key" ]] && args+=(-H "Idempotency-Key: $key")
  STATUS="$(curl "${args[@]}")"
  BODY="$(cat "$TMP/body")"
}

# json FIELD -> first value of "FIELD" in BODY (string or number). Good enough for flat checks.
json() { printf '%s' "$BODY" | grep -o "\"$1\":\(\"[^\"]*\"\|[^,}]*\)" | head -1 | sed -E "s/\"$1\"://; s/^\"//; s/\"$//"; }

expect_status() { [[ "$STATUS" == "$1" ]] || fail "$2: expected HTTP $1, got $STATUS"; }
expect_field()  { local v; v="$(json "$1")"; [[ "$v" == "$2" ]] || fail "$3: expected $1=$2, got '$v'"; }

echo "Smoke testing $BASE"

# ---- UI is served ----
req GET /;          expect_status 200 "UI index";  grep -q '<title>Ledger</title>' "$TMP/body" || fail "UI index has no title"; pass "UI page served"
req GET /app.js;    expect_status 200 "UI script"; pass "UI script served"
req GET /app.css;   expect_status 200 "UI styles"; pass "UI styles served"

# ---- reference data ----
req GET /currencies;            expect_status 200 "currencies";  [[ "$BODY" == *'"JPY"'* ]] || fail "JPY missing"; pass "currencies listed"
req GET /fx-rates/USD/SGD;      expect_status 200 "fx rate";     expect_field rate "1.34500000" "USD/SGD rate"; pass "FX rate readable"

# ---- accounts ----
req POST /accounts '{"name":"Smoke A","currency":"USD"}'; expect_status 201 "create A"; A="$(json id)"
req POST /accounts '{"name":"Smoke B","currency":"SGD"}'; expect_status 201 "create B"; B="$(json id)"
req POST /accounts '{"name":"Smoke C","currency":"USD"}'; expect_status 201 "create C"; C="$(json id)"
pass "accounts created"

# ---- deposit + idempotent replay ----
req POST "/accounts/$A/deposits" '{"amount":10000,"currency":"USD"}' "$RUN-dep"
expect_status 201 "deposit"; DEP="$(json id)"
req POST "/accounts/$A/deposits" '{"amount":10000,"currency":"USD"}' "$RUN-dep"
expect_status 201 "deposit replay"
grep -qi '^Idempotent-Replayed: true' "$TMP/headers" || fail "replay header missing"
[[ "$(json id)" == "$DEP" ]] || fail "replay returned a different transaction"
req GET "/accounts/$A"; expect_field balance 10000 "balance after replay"
pass "idempotent replay moved money once"

req POST "/accounts/$A/deposits" '{"amount":999,"currency":"USD"}' "$RUN-dep"
expect_status 422 "same key, different body"; expect_field code IDEMPOTENCY_KEY_REUSED "key reuse"
pass "key reuse with different body rejected"

# ---- transfer + hold ----
req POST /transactions "{\"fromAccountId\":\"$A\",\"toAccountId\":\"$C\",\"amount\":2500,\"currency\":\"USD\"}" "$RUN-t1"
expect_status 201 "transfer"; T1="$(json id)"
req GET "/accounts/$C"
expect_field balance 2500 "C balance"; expect_field heldBalance 2500 "C held"; expect_field availableBalance 0 "C available"
pass "transfer posted and held for recipient"

req POST /transactions "{\"fromAccountId\":\"$C\",\"toAccountId\":\"$A\",\"amount\":1,\"currency\":\"USD\"}" "$RUN-t2"
expect_status 422 "spend held money"; expect_field code INSUFFICIENT_FUNDS "held money"
pass "held money cannot be spent"

# ---- FX ----
req POST /transactions "{\"fromAccountId\":\"$A\",\"toAccountId\":\"$B\",\"amount\":1000,\"currency\":\"USD\"}" "$RUN-fx"
expect_status 201 "fx transfer"; expect_field destinationAmount 1345 "converted amount"; expect_field destinationCurrency SGD "dest ccy"
pass "cross-currency transfer converted with integer maths"

# ---- validation ----
req POST "/accounts/$A/deposits" '{"amount":12.5,"currency":"USD"}' "$RUN-float"
expect_status 400 "fractional amount"; pass "fractional amount rejected"
req POST /transactions "{\"fromAccountId\":\"$A\",\"toAccountId\":\"$C\",\"amount\":1,\"currency\":\"USD\"}"
expect_status 400 "missing key"; expect_field code MISSING_HEADER "missing key"; pass "missing Idempotency-Key rejected"
req GET /nope; expect_status 404 "unknown route"; expect_field code NOT_FOUND "unknown route"; pass "unknown route is problem+json"

# ---- reversal ----
req POST "/transactions/$T1/reversal" '{"reason":"smoke"}'
expect_status 201 "reversal"
req GET "/accounts/$A"; expect_field balance 9000 "A after reversal (10000 - 1000 FX)"
req POST "/transactions/$T1/reversal" '{}'
expect_status 409 "double reversal"; expect_field code ALREADY_REVERSED "double reversal"
pass "reversal restored funds and cannot repeat"

# ---- history ----
req GET "/accounts/$A/transactions"; expect_status 200 "history"
[[ "$(printf '%s' "$BODY" | grep -o '"entryId"' | wc -l)" -eq 4 ]] || fail "expected 4 history rows for A"
pass "history lists every posting"

# ---- ledger-wide integrity ----
req GET /admin/integrity-check; expect_status 200 "integrity"; expect_field healthy true "integrity"
pass "integrity check healthy"

echo "All $PASSED smoke checks passed."
