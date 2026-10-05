#!/bin/bash
# Boot the clockin35 AVD headless and wait until Android is really up.
# Prints measured boot time. Idempotent: reuses an already-running emulator.
#
# Measured on 2026-10-05 (Apple Silicon, macOS 27, emulator 37.3.2, cold boot):
#   see tools/boot-timing.txt
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/emulator_env.sh"

mkdir -p "$REPO_ROOT/tools/logs"
EMU_LOG="$REPO_ROOT/tools/logs/emulator.log"

if adb devices | grep -q "^emulator-"; then
  echo "emulator already running:"
  adb devices
else
  echo "launching $AVD_NAME headless ..."
  T0=$(date +%s)
  nohup "$ANDROID_HOME/emulator/emulator" -avd "$AVD_NAME" \
      -no-window -no-audio -no-snapshot -no-boot-anim \
      -gpu swiftshader_indirect \
      -netdelay none -netspeed full \
      > "$EMU_LOG" 2>&1 &
  echo "emulator pid $!  (log: $EMU_LOG)"
fi

echo "waiting for adb device ..."
adb wait-for-device

echo "waiting for sys.boot_completed ..."
BOOTED=0
for i in $(seq 1 180); do
  if [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
    BOOTED=1; break
  fi
  sleep 2
done

T1=$(date +%s)
if [ "$BOOTED" = "1" ]; then
  # boot_completed flips slightly before the launcher is usable; settle a moment.
  adb shell input keyevent 82 >/dev/null 2>&1
  echo "BOOT OK in $((T1-T0))s"
  adb devices -l
  adb shell getprop ro.build.version.sdk
  adb shell getprop ro.product.cpu.abi
  echo "boot_seconds=$((T1-T0))" | tee "$REPO_ROOT/tools/boot-timing.txt"
else
  echo "BOOT FAILED after $((T1-T0))s -- tail of $EMU_LOG:"
  tail -30 "$EMU_LOG"
  exit 1
fi
