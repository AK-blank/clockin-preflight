#!/bin/bash
# One-time toolchain bootstrap for CLOCK IN Pre-Flight.
#
# Why this exists: `build.sh` expects ./toolchain/{jdk,sdk,gradle}, which is gitignored because it is
# ~950 MB of third-party binaries. A fresh clone therefore CANNOT build until you run this.
#
# Every URL was chosen by measurement on a network where dl.google.com throttles to ~60 B/s and
# Maven Central is unreachable:
#   - Tencent Android SDK mirror   ~4.4 MB/s  (vs dl.google.com at ~60 B/s)
#   - Tencent Gradle mirror        ~4.8 MB/s
#   - Amazon Corretto JDK 17       ~6.7 MB/s  (vs GitHub release assets at ~40 KB/s)
#
# The Google SDK zips do NOT extract to the paths the SDK expects — this was verified, not assumed:
#   platform-35_r02.zip       -> android-35/           (needs renaming to platforms/android-35)
#   build-tools_r35_macosx.zip-> android-15/           (internal dir says 15, source.properties says
#                                                       Pkg.Revision=35.0.0; it IS build-tools 35)
#   platform-tools-*.zip      -> platform-tools/       (correct as-is)
#   commandlinetools-*.zip    -> cmdline-tools/        (needs renaming to cmdline-tools/latest)
#
# Usage:  ./scripts/bootstrap_toolchain.sh          # install into ./toolchain
#         ./scripts/bootstrap_toolchain.sh --check  # verify only, change nothing
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TC="$REPO/toolchain"
SDK_MIRROR="https://mirrors.cloud.tencent.com/AndroidSDK"
GRADLE_VERSION="8.11.1"
GRADLE_URL="https://mirrors.cloud.tencent.com/gradle/gradle-${GRADLE_VERSION}-bin.zip"
JDK_URL="https://corretto.aws/downloads/latest/amazon-corretto-17-aarch64-macos-jdk.tar.gz"
CLT_URL="https://dl.google.com/android/repository/commandlinetools-mac-11076708_latest.zip"

say() { printf '\033[1m==>\033[0m %s\n' "$*"; }

check() {
  local ok=1 p
  for p in "$TC/jdk/Contents/Home/bin/java" "$TC/sdk/platform-tools/adb" \
           "$TC/sdk/platforms/android-35/android.jar" "$TC/sdk/build-tools/35.0.0/aapt2" \
           "$TC/sdk/build-tools/35.0.0/apksigner" "$TC/sdk/cmdline-tools/latest/bin/sdkmanager" \
           "$TC/gradle/bin/gradle"; do
    if [ -e "$p" ]; then printf '  ok      %s\n' "${p#$REPO/}"
    else printf '  MISSING %s\n' "${p#$REPO/}"; ok=0; fi
  done
  [ "$ok" = 1 ] && { say "toolchain complete"; return 0; }
  say "toolchain incomplete — run without --check"
  return 1
}

if [ "${1:-}" = "--check" ]; then check; exit $?; fi

mkdir -p "$TC"
STAGE="$TC/.stage"
rm -rf "$STAGE"; mkdir -p "$STAGE"

download() { # download <url> <outfile>
  say "downloading $(basename "$1")"
  curl -fL --retry 3 --retry-delay 2 -o "$2" "$1"
}

# ---------------------------------------------------------------- JDK 17
if [ ! -e "$TC/jdk/Contents/Home/bin/java" ]; then
  download "$JDK_URL" "$TC/corretto17.tar.gz"
  mkdir -p "$TC/jdk"
  tar xzf "$TC/corretto17.tar.gz" -C "$TC/jdk" --strip-components=1
  rm -f "$TC/corretto17.tar.gz"
fi
"$TC/jdk/Contents/Home/bin/java" -version 2>&1 | head -1

# ------------------------------------------------------------ Android SDK
# Extract to a staging dir, then move the *inner* directory to where the SDK expects it.
install_pkg() { # install_pkg <mirror-name> <inner-dir-in-zip> <dest-relative-to-sdk>
  local name="$1" inner="$2" dest="$TC/sdk/$3"
  [ -e "$dest" ] && { say "already present: $3"; return 0; }
  download "$SDK_MIRROR/$name" "$STAGE/pkg.zip"
  rm -rf "$STAGE/x"; mkdir -p "$STAGE/x"
  unzip -q -o "$STAGE/pkg.zip" -d "$STAGE/x"
  if [ ! -d "$STAGE/x/$inner" ]; then
    say "ERROR: $name did not contain $inner (layout changed upstream?)"
    ls "$STAGE/x"; return 1
  fi
  mkdir -p "$(dirname "$dest")"
  mv "$STAGE/x/$inner" "$dest"
  rm -rf "$STAGE/x" "$STAGE/pkg.zip"
  say "installed $3"
}

if [ ! -e "$TC/sdk/cmdline-tools/latest/bin/sdkmanager" ]; then
  download "$CLT_URL" "$STAGE/clt.zip"
  rm -rf "$STAGE/clt"; mkdir -p "$STAGE/clt"
  unzip -q -o "$STAGE/clt.zip" -d "$STAGE/clt"
  mkdir -p "$TC/sdk/cmdline-tools"
  rm -rf "$TC/sdk/cmdline-tools/latest"
  mv "$STAGE/clt/cmdline-tools" "$TC/sdk/cmdline-tools/latest"
  rm -rf "$STAGE/clt" "$STAGE/clt.zip"
  say "installed cmdline-tools/latest"
fi

install_pkg "platform-tools-latest-darwin.zip" "platform-tools"          "platform-tools"
install_pkg "platform-35_r02.zip"              "android-35"              "platforms/android-35"
install_pkg "build-tools_r35_macosx.zip"       "android-15"              "build-tools/35.0.0"

mkdir -p "$TC/sdk/licenses"
# Accept the SDK licence so AGP does not stop for input (same hash sdkmanager writes).
printf '\n24333f8a63b6825ea9c5514f83c2829b004d1fee' > "$TC/sdk/licenses/android-sdk-license"

# ---------------------------------------------------------------- Gradle
if [ ! -e "$TC/gradle/bin/gradle" ]; then
  download "$GRADLE_URL" "$STAGE/gradle.zip"
  unzip -q -o "$STAGE/gradle.zip" -d "$STAGE"
  rm -rf "$TC/gradle"
  mv "$STAGE/gradle-$GRADLE_VERSION" "$TC/gradle"
  rm -f "$STAGE/gradle.zip"
fi

rm -rf "$STAGE"
check
say "now build with: ./build.sh :app:assembleDebug :app:testDebugUnitTest"
