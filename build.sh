#!/bin/bash
# Canonical build entry for CLOCK IN Pre-Flight.
# Mirrors are pinned in settings.gradle.kts (Maven Central is throttled on this network).
set -euo pipefail
cd "$(dirname "$0")"
export JAVA_HOME="$PWD/toolchain/jdk/Contents/Home"
export ANDROID_HOME="$PWD/toolchain/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
exec ./toolchain/gradle/bin/gradle --no-daemon --console=plain "$@"
