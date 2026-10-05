#!/bin/bash
# Record the CLOCK IN Pre-Flight demo video.
#
# Produces docs/media/demo.mp4: a title card, three real screen-recorded flows from the release APK
# on the emulator, an end card, and synthesized narration.
#
# Coordinate note (this cost us an hour): `adb shell input` uses DEVICE pixels. The screenshots this
# repo shows are 1080x2400, but if you read coordinates off a downscaled preview (e.g. 536 px wide)
# you must multiply by 1080/536 ≈ 2.015. Tapping the wrong y silently does nothing.
#
# Prereqs: emulator running (scripts/boot_avd.sh), release APK built, mock wallet installed
# (tools/mwa-harness), ffmpeg, and `say` (macOS) for narration.
set -uo pipefail
cd "$(dirname "$0")/.."
REPO="$PWD"
ADB="$REPO/toolchain/sdk/platform-tools/adb"
mkdir -p tools/video/out docs/media

PKG=com.clockin.probe
ACTIVITY="$PKG/$PKG.MainActivity"

# Coordinates, in device pixels (1080x2400)
TAB_PREFLIGHT="163 318"; TAB_AUTHORITY="409 318"; TAB_WALLET="919 318"
CHIP_DRAINER="242 836"; CHIP_REAL_SWAP="530 836"
BTN_CHECK_AUTHORITIES="540 1061"; CHIP_USDC="154 1173"
BTN_CONNECT_WALLET="540 1284"

record() { # record <seconds> <outfile> <commands...>
  local limit="$1" out="$2"; shift 2
  "$ADB" shell screenrecord --size 720x1600 --bit-rate 12M --time-limit "$limit" /sdcard/rec.mp4 &
  local rec=$!
  sleep 2
  "$@"
  wait $rec 2>/dev/null
  "$ADB" pull /sdcard/rec.mp4 "$out" >/dev/null 2>&1
  echo "recorded $out"
}

echo "== segment 1: drainer + real mainnet swap =="
"$ADB" shell pm clear "$PKG" >/dev/null 2>&1
"$ADB" shell am start -n "$ACTIVITY" >/dev/null 2>&1; sleep 7
record 62 tools/video/seg1.mp4 bash -c "
  '$ADB' shell input tap $CHIP_DRAINER; sleep 6
  '$ADB' shell input swipe 540 1600 540 900 500; sleep 4
  '$ADB' shell input swipe 540 900 540 1600 500; sleep 2
  '$ADB' shell input tap $CHIP_REAL_SWAP; sleep 14
  '$ADB' shell input swipe 540 1600 540 900 500; sleep 4"

echo "== segment 2: Mobile Wallet Adapter =="
"$ADB" shell pm clear "$PKG" >/dev/null 2>&1
"$ADB" shell am start -n "$ACTIVITY" >/dev/null 2>&1; sleep 7
record 40 tools/video/seg3.mp4 bash -c "
  '$ADB' shell input tap $TAB_WALLET; sleep 4
  '$ADB' shell input tap $BTN_CONNECT_WALLET; sleep 20
  '$ADB' shell input swipe 540 2000 540 700 400; sleep 2
  '$ADB' shell input swipe 540 2000 540 1000 400; sleep 4"

echo "== narration =="
say -v Samantha -r 168 -f tools/video/narration.txt -o tools/video/narration.aiff

echo "== assemble =="
ffmpeg -v error -y -i tools/video/seg1.mp4 -t 62 -vf "fps=24,scale=720:1600" \
  -c:v libx264 -preset veryfast -crf 22 -pix_fmt yuv420p -an tools/video/out/p1.mp4
ffmpeg -v error -y -i tools/video/seg3.mp4 -t 33 -vf "fps=24,scale=720:1600" \
  -c:v libx264 -preset veryfast -crf 22 -pix_fmt yuv420p -an tools/video/out/p3.mp4
echo "Cards are rendered from HTML with headless Chrome (see this script's git history for the markup);"
echo "if tools/video/cards/*.png are missing, re-render them before concat."
printf "file 'card-title.mp4'\nfile 'p1.mp4'\nfile 'p3.mp4'\nfile 'card-end.mp4'\n" > tools/video/out/list.txt
ffmpeg -v error -y -f concat -safe 0 -i tools/video/out/list.txt -c copy tools/video/out/silent.mp4
ffmpeg -v error -y -i tools/video/out/silent.mp4 -i tools/video/narration.aiff \
  -filter_complex "[1:a]apad[a]" -map 0:v -map "[a]" -c:v copy -c:a aac -b:a 128k -shortest \
  docs/media/demo.mp4
echo "done: docs/media/demo.mp4 ($(ffprobe -v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 docs/media/demo.mp4)s)"
