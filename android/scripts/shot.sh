#!/bin/bash
# Screenshot the emulator. Prints the path of an 800px-high PNG suitable for viewing.
# Usage: shot.sh [name]
source "$(dirname "$0")/env.sh"
NAME="${1:-shot-$(date +%H%M%S)}"
OUT="$YOUROLL_SHOTS/$NAME.png"
adb exec-out screencap -p > "$OUT.full.png"
sips -Z 1000 "$OUT.full.png" --out "$OUT" >/dev/null && rm "$OUT.full.png"
echo "$OUT"
