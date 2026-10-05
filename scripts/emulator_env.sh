#!/bin/bash
# Shared environment for the CLOCK IN emulator toolchain.
# Source this:  source scripts/emulator_env.sh
#
# Everything lives under the repo so it is reproducible and disposable:
#   toolchain/sdk   -> browser-tmp/sdk (SDK: platform-tools, emulator, system-image)
#   tools/avd       -> ANDROID_AVD_HOME (the clockin35 AVD)
#   tools/dl        -> downloaded package zips
#   tools/mwa       -> unpacked Mobile Wallet Adapter AARs (API inspection)
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export REPO_ROOT
export JAVA_HOME="$REPO_ROOT/toolchain/jdk/Contents/Home"
export ANDROID_HOME="$REPO_ROOT/toolchain/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_AVD_HOME="$REPO_ROOT/tools/avd"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

export AVD_NAME="${AVD_NAME:-clockin35}"
# Debug APK is built with applicationIdSuffix ".debug" (see app/build.gradle.kts).
export APP_PKG="${APP_PKG:-com.clockin.probe.debug}"
export APP_ACTIVITY="${APP_ACTIVITY:-com.clockin.probe.MainActivity}"
export APK="$REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk"
