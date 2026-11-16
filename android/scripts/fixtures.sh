#!/bin/bash
# Generate test media with ffmpeg (same recipes as upstream scripts/test-core.sh) and
# optionally push them into the emulator's Movies folder so the app's importer can see them.
# Usage: fixtures.sh [--push]
set -euo pipefail
source "$(dirname "$0")/env.sh"
F="$YOUROLL_ROOT/build/fixtures"; mkdir -p "$F"
ffmpeg -y -hide_banner -loglevel error -f lavfi -i 'testsrc2=size=1920x1080:rate=30:duration=8' -f lavfi -i 'sine=frequency=1000:sample_rate=48000:duration=8' -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest "$F/landscape-1080p.mp4"
ffmpeg -y -hide_banner -loglevel error -f lavfi -i 'testsrc2=size=1080x1920:rate=30:duration=6' -f lavfi -i 'sine=frequency=500:sample_rate=48000:duration=6' -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest "$F/portrait-1080p.mp4"
ffmpeg -y -hide_banner -loglevel error -f lavfi -i 'testsrc2=size=640x360:rate=30:duration=4' -f lavfi -i 'sine=frequency=1000:sample_rate=48000:duration=4' -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest "$F/source.mp4"
cp "$YOUROLL_ROOT/../ThirdParty/YouRec/Fixtures/editor-fixture.mp4" "$F/editor-fixture.mp4"
echo "fixtures in $F"
if [[ "${1:-}" == "--push" ]]; then
  for f in "$F"/*.mp4; do adb push "$f" /sdcard/Movies/ >/dev/null; done
  adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Movies >/dev/null 2>&1 || true
  for f in "$F"/*.mp4; do adb shell content call --uri content://media/external/file --method scan_file --arg "/sdcard/Movies/$(basename "$f")" >/dev/null 2>&1 || true; done
  echo "pushed to /sdcard/Movies"
fi
