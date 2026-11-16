#!/bin/bash
# Start the emulator headless if it is not running; wait until booted.
# Usage: emu.sh [--window] [--restart]
source "$(dirname "$0")/env.sh"
WINDOW="-no-window"; [[ " $* " == *" --window "* ]] && WINDOW=""
if [[ " $* " == *" --restart "* ]]; then adb emu kill >/dev/null 2>&1; sleep 3; fi
if ! adb devices | grep -q "^$ANDROID_SERIAL"; then
  nohup emulator -avd "$YOUROLL_AVD" -port "$YOUROLL_EMU_PORT" $WINDOW -no-boot-anim -no-snapshot-save -gpu swiftshader_indirect \
    > "$YOUROLL_ROOT/build/emulator-$YOUROLL_EMU_PORT.log" 2>&1 &
fi
adb wait-for-device
for i in $(seq 1 120); do
  [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && { echo "emulator ready ($(adb shell wm size | tr -d '\r'))"; exit 0; }
  sleep 2
done
echo "emulator did not finish booting; see $YOUROLL_ROOT/build/emulator.log" >&2; exit 1
