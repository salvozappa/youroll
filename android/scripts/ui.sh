#!/bin/bash
# Inspect and drive the UI through uiautomator.
#   ui.sh dump                 -> list visible nodes: text | content-desc | resource-id | bounds
#   ui.sh tap "<text or desc>" -> tap the centre of the first node whose text/content-desc matches (regex)
#   ui.sh tapxy X Y            -> tap device pixel coordinates (1440x3120)
#   ui.sh swipe X1 Y1 X2 Y2 [ms]
#   ui.sh type "text"          -> type into the focused field
#   ui.sh back | home
#   ui.sh log [pattern]        -> recent logcat for the app (errors and crashes by default)
source "$(dirname "$0")/env.sh"
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb exec-out cat /sdcard/ui.xml; }
case "$1" in
  dump) dump | python3 -I -c '
import sys, re
x = sys.stdin.read()
for n in re.finditer(r"<node ([^>]*)>|<node ([^>]*)/>", x):
    a = n.group(1) or n.group(2)
    g = lambda k: (re.search(k + r"=\"([^\"]*)\"", a) or [None, ""])[1]
    t, d, r, b = g("text"), g("content-desc"), g("resource-id"), g("bounds")
    if t or d or r: print(f"{t} | {d} | {r} | {b}")' ;;
  tap) dump | python3 -I -c '
import sys, re
want = re.compile(sys.argv[1])
for n in re.finditer(r"<node ([^>]*?)/?>", sys.stdin.read()):
    a = n.group(1)
    t = re.search(r"text=\"([^\"]*)\"", a); d = re.search(r"content-desc=\"([^\"]*)\"", a)
    if (t and want.search(t[1])) or (d and want.search(d[1])):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", re.search(r"bounds=\"([^\"]*)\"", a)[1]))
        print((x1 + x2) // 2, (y1 + y2) // 2); break
else: sys.exit("no node matches " + sys.argv[1])' "$2" | { read X Y && adb shell input tap "$X" "$Y" && echo "tapped $X $Y"; } ;;
  tapxy) adb shell input tap "$2" "$3" ;;
  swipe) adb shell input swipe "$2" "$3" "$4" "$5" "${6:-300}" ;;
  type) adb shell input text "$(printf '%s' "$2" | sed 's/ /%s/g')" ;;
  back) adb shell input keyevent KEYCODE_BACK ;;
  home) adb shell input keyevent KEYCODE_HOME ;;
  log) PID=$(adb shell pidof "$YOUROLL_PKG" | tr -d '\r'); 
       if [ -n "$2" ]; then adb logcat -d -t 400 | grep -E "$2"; 
       else adb logcat -d -t 400 '*:E' | grep -E "${PID:-AndroidRuntime}|AndroidRuntime|FATAL" ; fi ;;
  *) sed -n 2,11p "$0" ;;
esac
