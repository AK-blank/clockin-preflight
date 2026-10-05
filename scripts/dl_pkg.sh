#!/bin/bash
# Robust resumable downloader for Android SDK packages from a fast mirror.
# usage: dl_pkg.sh <relative-repo-path> <dest-file> [mirror-base]
# Measured 2026-10-05: mirrors.cloud.tencent.com/AndroidSDK = ~5.3 MB/s;
# dl.google.com = ~2.2 MB/s fresh then throttled to ~60 B/s (do not rely on it).
set -uo pipefail
REL="$1"; DEST="$2"; BASE="${3:-https://mirrors.cloud.tencent.com/AndroidSDK}"
mkdir -p "$(dirname "$DEST")"
ATTEMPT=0
while [ "$ATTEMPT" -lt 200 ]; do
  ATTEMPT=$((ATTEMPT+1))
  SZ=$(stat -f%z "$DEST" 2>/dev/null || echo 0)
  echo "[$(date +%H:%M:%S)] attempt $ATTEMPT, have $SZ bytes, fetching $REL"
  /usr/bin/curl -sS -L --fail --connect-timeout 15 --max-time 1800 \
      -C - -o "$DEST" -w "  -> code=%{http_code} got=%{size_download}B speed=%{speed_download}B/s\n" \
      "$BASE/$REL" && { echo "[$(date +%H:%M:%S)] DONE $DEST"; exit 0; }
  RC=$?
  NEW=$(stat -f%z "$DEST" 2>/dev/null || echo 0)
  echo "  curl rc=$RC, now $NEW bytes"
  if [ "$RC" -eq 22 ] || [ "$RC" -eq 33 ]; then
    echo "  (416/range-not-satisfiable => already complete)"; exit 0
  fi
  sleep 3
done
echo "FAILED after $ATTEMPT attempts"; exit 1
