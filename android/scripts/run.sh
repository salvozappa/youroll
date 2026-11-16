#!/bin/bash
# Build the debug APK, install it on the emulator (or $YOUROLL_DEVICE) and launch it (grants camera/mic).
# Usage: run.sh [--no-build] [--clear]   (--clear wipes app data first)
set -euo pipefail
source "$(dirname "$0")/env.sh"
cd "$YOUROLL_ROOT"
[[ " $* " == *" --no-build "* ]] || ./gradlew --quiet assembleDebug
[[ -n "${YOUROLL_DEVICE:-}" ]] || "$YOUROLL_ROOT/scripts/emu.sh" >/dev/null
[[ " $* " == *" --clear "* ]] && adb shell pm clear "$YOUROLL_PKG" >/dev/null || true
adb install -r -g app/build/outputs/apk/debug/app-debug.apk >/dev/null
adb shell am force-stop "$YOUROLL_PKG"
adb logcat -c
adb shell am start -W -n "$YOUROLL_PKG/.MainActivity" | grep -E "Status|TotalTime"
