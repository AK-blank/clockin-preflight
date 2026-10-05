#!/bin/bash
# Prove a full Mobile Wallet Adapter session on the emulator, end to end.
#
#   mockwallet (walletlib 2.0.3) <-- localhost websocket / MWA v1 --> mwadriver (clientlib-ktx 2.0.3)
#
# The driver calls exactly the API the CLOCK IN app will call:
#     MobileWalletAdapter(ConnectionIdentity(...)).transact(sender) { signTransactions(...) }
#
# Produces: tools/shots/mwa-driver-pass.png, tools/mwa-result.json, tools/logs/mwa-*.log
# Exits non-zero unless the driver logs "VERDICT PASS".
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/emulator_env.sh"

HARNESS="$REPO_ROOT/tools/mwa-harness"
WALLET_APK="$HARNESS/mockwallet/build/outputs/apk/debug/mockwallet-debug.apk"
DRIVER_APK="$HARNESS/mwadriver/build/outputs/apk/debug/mwadriver-debug.apk"
mkdir -p "$REPO_ROOT/tools/logs" "$REPO_ROOT/tools/shots"

for apk in "$WALLET_APK" "$DRIVER_APK"; do
  [ -f "$apk" ] || { echo "missing $apk -- build the harness first"; exit 2; }
done

echo "== installing =="
adb install -r "$WALLET_APK" | tail -1
adb install -r "$DRIVER_APK" | tail -1

adb shell am force-stop com.clockin.mwadriver
adb shell am force-stop com.clockin.mockwallet
adb logcat -c

echo "== launching driver =="
T0=$(date +%s)
adb shell am start -n com.clockin.mwadriver/.DriverActivity | tail -1

VERDICT=""
for i in $(seq 1 30); do
  sleep 2
  if adb logcat -d -s CLOCKIN-MWA-DRIVER:* 2>/dev/null | grep -q "VERDICT"; then
    VERDICT=$(adb logcat -d -s CLOCKIN-MWA-DRIVER:* 2>/dev/null | grep -o "VERDICT [A-Z]*" | tail -1)
    break
  fi
done
T1=$(date +%s)

adb logcat -d -s CLOCKIN-MWA-DRIVER:* > "$REPO_ROOT/tools/logs/mwa-driver.log" 2>/dev/null
adb logcat -d -s CLOCKIN-MWA-WALLET:* > "$REPO_ROOT/tools/logs/mwa-wallet.log" 2>/dev/null
adb exec-out screencap -p > "$REPO_ROOT/tools/shots/mwa-driver-pass.png"
adb pull /storage/emulated/0/Android/data/com.clockin.mwadriver/files/mwa-result.json \
    "$REPO_ROOT/tools/mwa-result.json" >/dev/null 2>&1

echo ""
echo "===== driver log ====="
cat "$REPO_ROOT/tools/logs/mwa-driver.log"
echo "===== wallet log ====="
cat "$REPO_ROOT/tools/logs/mwa-wallet.log"
echo ""
echo "wall clock: $((T1-T0))s   verdict: ${VERDICT:-<none>}"
echo "artifacts: tools/shots/mwa-driver-pass.png  tools/mwa-result.json  tools/logs/mwa-*.log"
[ "$VERDICT" = "VERDICT PASS" ] || exit 1
