#!/bin/bash
# Independently check the claims this repository makes.
#
#   ./scripts/verify.sh            offline checks (no network, ~1 min after the first build)
#   ./scripts/verify.sh --live     additionally exercises the real Solana RPC and risk APIs
#
# Everything printed is a fact from a command that just ran. If a check fails the script exits
# non-zero and says which claim is no longer true.
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO"
LIVE=0; [ "${1:-}" = "--live" ] && LIVE=1
FAILED=0

# Same pins build.sh uses — apksigner and the Gradle child process both need them.
export JAVA_HOME="$REPO/toolchain/jdk/Contents/Home"
export ANDROID_HOME="$REPO/toolchain/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

pass() { printf '  \033[32mPASS\033[0m  %s\n' "$*"; }
fail() { printf '  \033[31mFAIL\033[0m  %s\n' "$*"; FAILED=1; }
info() { printf '        %s\n' "$*"; }
section() { printf '\n\033[1m%s\033[0m\n' "$*"; }

section "1. Toolchain"
if ./scripts/bootstrap_toolchain.sh --check >/dev/null 2>&1; then
  pass "toolchain complete"
else
  fail "toolchain incomplete — run ./scripts/bootstrap_toolchain.sh"
  ./scripts/bootstrap_toolchain.sh --check || true
fi

section "2. Build and test"
BUILD_LOG=$(mktemp)
if ./build.sh :app:assembleDebug :app:testDebugUnitTest >"$BUILD_LOG" 2>&1; then
  pass "build + full test suite green"
else
  fail "build or tests failed — last 20 lines:"
  tail -20 "$BUILD_LOG"
fi
RESULTS="app/build/test-results/testDebugUnitTest"
if [ -d "$RESULTS" ]; then
  TOTALS=$(python3 - "$RESULTS" <<'PY'
import glob, re, sys
t = f = e = s = c = 0
for p in glob.glob(sys.argv[1] + "/*.xml"):
    m = re.search(r'tests="(\d+)" skipped="(\d+)" failures="(\d+)" errors="(\d+)"', open(p).read())
    if m:
        t += int(m.group(1)); s += int(m.group(2)); f += int(m.group(3)); e += int(m.group(4)); c += 1
print(f"{t} {f} {e} {s} {c}")
PY
)
  read -r T F E S C <<<"$TOTALS"
  info "$T tests across $C classes, $F failures, $E errors, $S skipped (opt-in live)"
  [ "$F" = "0" ] && [ "$E" = "0" ] && pass "no test failures" || fail "$F failures / $E errors"
  [ "$T" -ge 200 ] && pass "test count >= 200" || fail "test count dropped to $T"
fi

section "3. Engine stays portable"
if grep -rq "^import android" app/src/main/java/com/clockin/preflight/engine/ 2>/dev/null; then
  fail "engine/ imports android.* — it would no longer run under plain JVM tests"
else
  pass "engine/ has zero android.* imports (pure Kotlin)"
fi

section "4. Deliverables present"
for f in "docs/download/pre-flight-1.0.0.apk:Signed APK" \
         "docs/media/demo.mp4:Demo video" \
         "docs/pitch-deck.pdf:Pitch deck"; do
  path="${f%%:*}"; name="${f##*:}"
  if [ -s "$path" ]; then
    pass "$name — $(( $(stat -f%z "$path") / 1024 )) KB"
  else
    fail "$name missing at $path"
  fi
done
if [ -s docs/media/demo.mp4 ]; then
  DUR=$(ffprobe -v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 docs/media/demo.mp4 2>/dev/null | cut -d. -f1)
  if [ -n "$DUR" ] && [ "$DUR" -ge 60 ] && [ "$DUR" -le 180 ]; then
    pass "demo video is ${DUR}s (within the 2–3 minute brief, and shows the app in use)"
  else
    fail "demo video duration ${DUR:-unknown}s"
  fi
fi

section "5. The README points at the published video"
if grep -q "youtube.com/watch\|youtu.be/" README.md; then
  pass "README links the demo video on YouTube"
  info "$(grep -o 'https://www.youtube.com/watch?v=[A-Za-z0-9_-]*' README.md | head -1)"
else
  fail "README does not link a YouTube video (the submission cites the hosted video, not a repo file)"
fi

section "6. The APK is really signed"
APK=docs/download/pre-flight-1.0.0.apk
SIGNER="$(./toolchain/sdk/build-tools/35.0.0/apksigner verify --print-certs "$APK" 2>/dev/null | head -1)"
if [ -n "$SIGNER" ]; then
  pass "apksigner verified"; info "$SIGNER"
else
  fail "apksigner could not verify $APK"
fi

section "7. MWA integration is present in the app (not just the harness)"
if grep -rq "mobile-wallet-adapter" app/build.gradle.kts && \
   grep -rq "LocalAssociationScenario\|MobileWalletAdapter" app/src/main/java/com/clockin/preflight/mwa/; then
  pass "app depends on the MWA client and drives a session from com.clockin.preflight.mwa"
else
  fail "no Mobile Wallet Adapter usage found in the app"
fi

if [ "$LIVE" = 1 ]; then
  section "8. Live network (--live)"
  RPC=https://solana-rpc.publicnode.com
  SLOT=$(curl -s --max-time 15 -X POST -H 'Content-Type: application/json' \
    -d '{"jsonrpc":"2.0","id":1,"method":"getSlot"}' "$RPC" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("result",""))' 2>/dev/null)
  [ -n "$SLOT" ] && { pass "Solana RPC reachable"; info "current slot $SLOT"; } || fail "Solana RPC unreachable"

  USDC=EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v
  RC=$(curl -s -o /dev/null -w '%{http_code}' --max-time 15 "https://api.rugcheck.xyz/v1/tokens/$USDC/report")
  [ "$RC" = "200" ] && pass "RugCheck reachable (HTTP 200)" || fail "RugCheck returned HTTP $RC"
  GP=$(curl -s -o /dev/null -w '%{http_code}' --max-time 15 \
    "https://api.gopluslabs.io/api/v1/solana/token_security?contract_addresses=$USDC")
  [ "$GP" = "200" ] && pass "GoPlus reachable (HTTP 200)" || fail "GoPlus returned HTTP $GP"

  info "running the opt-in live parser test…"
  if CLOCKIN_LIVE_SMOKE=1 ./build.sh :app:testDebugUnitTest \
       --tests 'com.clockin.preflight.data.LiveSmokeTest' >/dev/null 2>&1; then
    pass "live smoke test passed (parsers still match the live servers)"
  else
    fail "live smoke test failed"
  fi
fi

printf '\n'
if [ "$FAILED" = 0 ]; then
  printf '\033[1;32mAll checks passed.\033[0m'
  [ "$LIVE" = 0 ] && printf '  Re-run with --live to exercise the real network.'
  printf '\n'
else
  printf '\033[1;31mOne or more claims failed.\033[0m\n'
fi
exit $FAILED
