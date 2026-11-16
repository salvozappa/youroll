#!/bin/bash
# Run the SPEC acceptance flows end to end on a device (app/src/androidTest/java/com/salvozappa/youroll/acceptance).
#
# Usage: acceptance.sh [--device SERIAL] [--keep-data] [--only Class[,Class…]] [--import-via picker|store] [--no-build] [--yes]
#   --device SERIAL   adb serial (default: $ANDROID_SERIAL if set, else emulator-$YOUROLL_EMU_PORT)
#   --keep-data       for a personal phone: no `pm clear`. Every flow works in its own project titled
#                     "YouRoll acceptance · …" and deletes only that project; recorder/editor settings are
#                     backed up first and restored at the end.
#   --only Class      run only these test classes (simple names, comma separated or repeated)
#   --import-via store  seed the import flow through ProjectStore instead of driving the Photo Picker
#   --no-build        reuse the APKs already built
#   --yes             do not ask for confirmation before clearing app data on a physical device
#
# WITHOUT --keep-data THIS RUNS `pm clear com.salvozappa.youroll`: EVERY YOUROLL PROJECT ON THE DEVICE IS DELETED.
#
# Each test method runs in its own `am instrument` process and the app is force-stopped between
# methods, so `stepN` methods see the app after a real process death. Results, logcat, recorded
# segments and exports go to build/acceptance/<timestamp>/ (junit.xml, results.txt, media/, logcat/).
set -uo pipefail
PRESET_SERIAL="${ANDROID_SERIAL:-}"
source "$(dirname "$0")/env.sh"
cd "$YOUROLL_ROOT"

SERIAL="${PRESET_SERIAL:-$ANDROID_SERIAL}"
KEEP=false; BUILD=true; YES=false; IMPORT_VIA=picker; ONLY=()
ALL_CLASSES=(MicrophoneAcceptanceTest RecordingLifecycleAcceptanceTest CameraSwitchAcceptanceTest ZoomAcceptanceTest
             EditHistoryAcceptanceTest ExportAcceptanceTest ImportAcceptanceTest)
while [ $# -gt 0 ]; do
  case "$1" in
    --device) SERIAL="$2"; shift 2 ;;
    --keep-data) KEEP=true; shift ;;
    --only) IFS=, read -ra parts <<< "$2"; ONLY+=("${parts[@]}"); shift 2 ;;
    --import-via) IMPORT_VIA="$2"; shift 2 ;;
    --no-build) BUILD=false; shift ;;
    --yes) YES=true; shift ;;
    -h|--help) sed -n 2,19p "$0"; exit 0 ;;
    *) echo "unknown option $1 (see --help)" >&2; exit 2 ;;
  esac
done
export ANDROID_SERIAL="$SERIAL"
CLASSES=("${ALL_CLASSES[@]}")
if [ ${#ONLY[@]} -gt 0 ]; then
  CLASSES=()
  for c in "${ONLY[@]}"; do
    [[ " ${ALL_CLASSES[*]} " == *" $c "* ]] || { echo "unknown class $c; one of: ${ALL_CLASSES[*]}" >&2; exit 2; }
    CLASSES+=("$c")
  done
fi

PKG=com.salvozappa.youroll
RUNNER=com.salvozappa.youroll.test/androidx.test.runner.AndroidJUnitRunner
OUT="$YOUROLL_ROOT/build/acceptance/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT/raw" "$OUT/logcat"
RESULTS="$OUT/results.tsv"; : > "$RESULTS"

[ "$(adb -s "$SERIAL" get-state 2>/dev/null)" = device ] || { echo "device $SERIAL is not connected (adb devices)" >&2; exit 1; }
MODEL="$(adb shell getprop ro.product.model | tr -d '\r')"
echo "Device: $SERIAL ($MODEL, Android $(adb shell getprop ro.build.version.release | tr -d '\r'))"

if ! $KEEP; then
  echo
  echo "################################################################################"
  echo "#  WARNING: this runs \`pm clear $PKG\` on $SERIAL."
  echo "#  ALL YouRoll projects, recordings, edits and exports on that device are DELETED."
  echo "#  On a personal phone use --keep-data instead."
  echo "################################################################################"
  echo
  if [[ "$SERIAL" != emulator-* ]] && ! $YES; then
    read -r -p "Type CLEAR to erase YouRoll's data on $MODEL: " answer
    [ "$answer" = CLEAR ] || { echo "aborted"; exit 1; }
  fi
fi

if $BUILD; then
  echo "Building app and test APKs…"
  ./gradlew --quiet assembleDebug assembleDebugAndroidTest || exit 1
fi
echo "Installing (data is kept by the install)…"
adb install -r -g app/build/outputs/apk/debug/app-debug.apk >/dev/null || { echo "install failed: a differently signed YouRoll is installed? Not uninstalling it." >&2; exit 1; }
adb install -r -t -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null || exit 1

# Device state for the run: awake, unlocked, portrait. Restored on exit.
ROT_AUTO="$(adb shell settings get system accelerometer_rotation | tr -d '\r')"
ROT_USER="$(adb shell settings get system user_rotation | tr -d '\r')"
STAY_ON="$(adb shell settings get global stay_on_while_plugged_in | tr -d '\r')"
restore_device() {
  adb shell settings put system accelerometer_rotation "${ROT_AUTO:-1}" >/dev/null 2>&1
  [ "$ROT_USER" != null ] && adb shell settings put system user_rotation "${ROT_USER:-0}" >/dev/null 2>&1
  adb shell settings put global stay_on_while_plugged_in "${STAY_ON:-0}" >/dev/null 2>&1
}
trap restore_device EXIT
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 0
adb shell settings put global stay_on_while_plugged_in 7
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard >/dev/null 2>&1

if ! $KEEP; then
  echo "Clearing YouRoll's app data (pm clear $PKG)…"
  adb shell pm clear "$PKG" >/dev/null
fi
adb shell rm -rf "/sdcard/Android/data/$PKG/files/acceptance" >/dev/null 2>&1

ARGS=(-e acceptance true -e keepData "$KEEP" -e importVia "$IMPORT_VIA")

# Runs one test method in a fresh process; appends "class<TAB>method<TAB>status<TAB>seconds<TAB>message" to $RESULTS.
run_test() {
  local class="$1" method="$2" extra=("${@:3}")
  local raw="$OUT/raw/$class.$method.txt"
  adb shell am force-stop "$PKG"
  adb logcat -c 2>/dev/null
  printf '%-34s %-46s ' "$class" "$method"
  local started=$SECONDS
  adb shell am instrument -w -r "${ARGS[@]}" ${extra[@]+"${extra[@]}"} -e class "com.salvozappa.youroll.acceptance.$class#$method" "$RUNNER" 2>&1 \
    | tr -d '\r' | tee "$raw" | while IFS= read -r line; do
        case "$line" in
          "INSTRUMENTATION_STATUS: operator="*) printf '\n\n>>> OPERATOR: %s\n\n' "${line#*operator=}" ;;
        esac
      done
  local elapsed=$((SECONDS - started))
  adb logcat -d > "$OUT/logcat/$class.$method.txt" 2>/dev/null
  python3 -I - "$raw" "$class" "$method" "$elapsed" >> "$RESULTS" <<'PY'
import sys, re
raw, cls, method, elapsed = sys.argv[1:]
text = open(raw, encoding="utf-8", errors="replace").read()
codes = [int(c) for c in re.findall(r"^INSTRUMENTATION_STATUS_CODE: (-?\d+)", text, re.M)]
stack = re.search(r"^INSTRUMENTATION_STATUS: stack=(.*?)(?=^INSTRUMENTATION_STATUS)", text, re.M | re.S)
message = (stack.group(1).strip() if stack else "")
final = [c for c in codes if c in (0, -2, -3, -4)]
if "Process crashed" in text or "INSTRUMENTATION_FAILED" in text:
    status = "ERROR"; message = message or re.sub(r"\s+", " ", text[-600:])
elif not final:
    status = "ERROR"; message = "no result: " + re.sub(r"\s+", " ", text[-400:])
else:
    status = {0: "PASS", -2: "FAIL", -3: "SKIP", -4: "SKIP"}[final[-1]]
# Keep the first line of the reason; the full stack stays in raw/.
first = message.splitlines()[0] if message else ""
first = re.sub(r"^(org\.junit\.AssumptionViolatedException|java\.lang\.AssertionError|org\.junit\.internal\.AssumptionViolatedException): ", "", first)
print("\t".join([cls, method, status, elapsed, first.replace("\t", " ")]))
PY
  tail -1 "$RESULTS" | awk -F'\t' '{ printf "%-5s %3ss  %s\n", $3, $4, substr($5, 1, 110) }'
}

list_methods() {
  adb shell am instrument -w -r -e log true "${ARGS[@]}" -e class "com.salvozappa.youroll.acceptance.$1" "$RUNNER" 2>/dev/null \
    | tr -d '\r' | sed -n 's/^INSTRUMENTATION_STATUS: test=//p' | awk '!seen[$0]++'
}

echo
echo "Results → $OUT"
run_test SuiteHousekeeping begin -e housekeeping true
for class in "${CLASSES[@]}"; do
  methods="$(list_methods "$class")"
  [ -n "$methods" ] || { printf '%s\t-\tERROR\t0\tno test methods found\n' "$class" >> "$RESULTS"; echo "$class: no test methods found"; continue; }
  for method in $methods; do run_test "$class" "$method"; done
done
run_test SuiteHousekeeping end -e housekeeping true
adb shell am force-stop "$PKG"

echo
echo "Pulling recorded segments, exports and reports…"
adb pull "/sdcard/Android/data/$PKG/files/acceptance" "$OUT/media" >/dev/null 2>&1 && \
  adb shell rm -rf "/sdcard/Android/data/$PKG/files/acceptance" >/dev/null 2>&1

python3 -I - "$RESULTS" "$OUT/junit.xml" "$SERIAL" "$MODEL" <<'PY'
import sys, collections
from xml.sax.saxutils import escape, quoteattr
results, out, serial, model = sys.argv[1:]
rows = [l.rstrip("\n").split("\t") for l in open(results, encoding="utf-8") if l.strip()]
suites = collections.OrderedDict()
for cls, method, status, secs, msg in rows: suites.setdefault(cls, []).append((method, status, secs, msg))
xml = ['<?xml version="1.0" encoding="UTF-8"?>', '<testsuites name=%s>' % quoteattr("YouRoll acceptance on %s (%s)" % (model, serial))]
for cls, tests in suites.items():
    fails = sum(1 for t in tests if t[1] == "FAIL"); errs = sum(1 for t in tests if t[1] == "ERROR"); skips = sum(1 for t in tests if t[1] == "SKIP")
    xml.append('  <testsuite name=%s tests="%d" failures="%d" errors="%d" skipped="%d">' % (quoteattr("com.salvozappa.youroll.acceptance." + cls), len(tests), fails, errs, skips))
    for method, status, secs, msg in tests:
        xml.append('    <testcase classname=%s name=%s time="%s">' % (quoteattr("com.salvozappa.youroll.acceptance." + cls), quoteattr(method), secs))
        if status == "FAIL": xml.append('      <failure message=%s/>' % quoteattr(msg))
        elif status == "ERROR": xml.append('      <error message=%s/>' % quoteattr(msg))
        elif status == "SKIP": xml.append('      <skipped message=%s/>' % quoteattr(msg))
        xml.append('    </testcase>')
    xml.append('  </testsuite>')
xml.append('</testsuites>')
open(out, "w", encoding="utf-8").write("\n".join(xml) + "\n")
PY

echo
echo "================================ Summary ================================"
awk -F'\t' '{ printf "%-5s %-34s %s\n", $3, $1, $2; if ($3 != "PASS" && $5 != "") printf "      %s\n", substr($5, 1, 160) }' "$RESULTS" | tee "$OUT/results.txt"
PASS=$(awk -F'\t' '$3=="PASS"' "$RESULTS" | wc -l | tr -d ' ')
FAIL=$(awk -F'\t' '$3=="FAIL" || $3=="ERROR"' "$RESULTS" | wc -l | tr -d ' ')
SKIP=$(awk -F'\t' '$3=="SKIP"' "$RESULTS" | wc -l | tr -d ' ')
echo "-------------------------------------------------------------------------"
echo "$PASS passed, $FAIL failed, $SKIP skipped  ·  JUnit XML: $OUT/junit.xml  ·  media: $OUT/media"

cat <<'EOF'

Manual checks (not automated; see TODO.md, "Needs a real device"):
  1. Lip sync: record a hand clap facing the camera, export, and check the clap's sound and
     picture land on the same frame in the export (and across a Pause/Resume join).
  2. Front camera mirroring: the selfie preview is mirrored, the saved file reads correctly
     (text not reversed) unless "Save mirrored selfies" is on; check visually on the phone.
  3. Outdoor readability: recorder controls, timer and mic chip are readable in direct sunlight,
     one-handed.
EOF
[ "$FAIL" = 0 ]
