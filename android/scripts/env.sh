# Source this: shared environment for YouRoll scripts.
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
# Two emulators can run side by side for parallel work:
#   default        -> AVD YouRoll_API37   on port 5554 (emulator-5554)
#   YOUROLL_EMU_PORT=5556 -> AVD YouRoll_API37_B on port 5556 (emulator-5556)
#   YOUROLL_EMU_PORT=5558 -> AVD YouRoll_API37_C on port 5558 (emulator-5558)
export YOUROLL_EMU_PORT="${YOUROLL_EMU_PORT:-5554}"
case "$YOUROLL_EMU_PORT" in
  5554) export YOUROLL_AVD="YouRoll_API37" ;;
  5556) export YOUROLL_AVD="YouRoll_API37_B" ;;
  *)    export YOUROLL_AVD="YouRoll_API37_C" ;;
esac
# YOUROLL_DEVICE=<serial> targets a physical device instead of the emulator.
export ANDROID_SERIAL="${YOUROLL_DEVICE:-emulator-$YOUROLL_EMU_PORT}"
export YOUROLL_PKG="com.salvozappa.youroll"
export YOUROLL_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export YOUROLL_SHOTS="$YOUROLL_ROOT/build/shots"
mkdir -p "$YOUROLL_SHOTS"
