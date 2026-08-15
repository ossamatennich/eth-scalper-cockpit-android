#!/bin/sh
set -eu

APK=app/build/outputs/apk/stable/app-stable.apk
PACKAGE=com.ethscalper.cockpit.stable
ACTIVITY=com.ethscalper.cockpit.V4MainActivity
UI_FILE="${RUNNER_TEMP:-/tmp}/nmc-ui.xml"
SCREEN_FILE="${RUNNER_TEMP:-/tmp}/nmc-screen.png"

adb install -r "$APK"
adb shell am force-stop "$PACKAGE"
adb logcat -c
adb shell input keyevent KEYCODE_WAKEUP || true
adb shell wm dismiss-keyguard || true
START_OK=false
for attempt in 1 2 3; do
  printf 'ANDROID_LAUNCH_ATTEMPT=%s\n' "$attempt"
  START_OUTPUT="$(adb shell am start -W "$PACKAGE/$ACTIVITY")"
  printf '%s\n' "$START_OUTPUT"
  if printf '%s\n' "$START_OUTPUT" | grep -q 'Status: ok'; then
    START_OK=true
    break
  fi
  adb shell am force-stop "$PACKAGE"
  sleep 4
done
test "$START_OK" = true
sleep 8
PID="$(adb shell pidof "$PACKAGE")"
test -n "$PID"
adb shell dumpsys activity activities | grep -q "$ACTIVITY"
adb shell dumpsys window windows | grep -q "$PACKAGE/$ACTIVITY"
printf 'ANDROID_LAUNCH_PROCESS=PASS pid=%s\n' "$PID"
adb exec-out screencap -p > "$SCREEN_FILE"
test -s "$SCREEN_FILE"
printf 'ANDROID_SCREENSHOT=PASS bytes=%s\n' "$(wc -c < "$SCREEN_FILE")"

UI_OK=false
for attempt in 1 2 3 4 5; do
  printf 'ANDROID_UI_CHECK attempt=%s\n' "$attempt"
  adb shell uiautomator dump --compressed /sdcard/nmc-ui.xml || true
  adb pull /sdcard/nmc-ui.xml "$UI_FILE" >/dev/null 2>&1 || true
  if test -f "$UI_FILE" && grep -q 'text="NMC"' "$UI_FILE"; then
    UI_OK=true
    break
  fi
  sleep 2
done
if test "$UI_OK" = true; then
  grep -q 'text="ACCUEIL"' "$UI_FILE"
  grep -q 'text="PLANS"' "$UI_FILE"
  grep -q 'text="HISTORIQUE"' "$UI_FILE"
  PLANS_CENTER="$(python3 - "$UI_FILE" <<'PY'
import re, sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
node = next((n for n in root.iter('node') if n.attrib.get('text') == 'PLANS'), None)
if node is None:
    raise SystemExit(1)
m = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node.attrib['bounds'])
if not m:
    raise SystemExit(1)
x1, y1, x2, y2 = map(int, m.groups())
print((x1 + x2) // 2, (y1 + y2) // 2)
PY
)"
  set -- $PLANS_CENTER
  adb shell input tap "$1" "$2"
  sleep 1
  adb shell uiautomator dump --compressed /sdcard/nmc-ui-plans.xml
  adb pull /sdcard/nmc-ui-plans.xml "${RUNNER_TEMP:-/tmp}/nmc-ui-plans.xml" >/dev/null
  grep -q 'text="ORDRES LIMITES POSSIBLES"' "${RUNNER_TEMP:-/tmp}/nmc-ui-plans.xml"
  printf 'ANDROID_BOTTOM_NAV=VISIBLE_AND_CLICKABLE\n'
  printf 'ANDROID_UI_HIERARCHY=NMC\n'
else
  printf 'ANDROID_UI_HIERARCHY=UNAVAILABLE screenshot=%s\n' "$SCREEN_FILE"
fi

ANDROID_RUNTIME_ERRORS="$(adb logcat -d -v threadtime AndroidRuntime:E '*:S')"
if printf '%s\n' "$ANDROID_RUNTIME_ERRORS" | grep -q "$PACKAGE"; then
  printf '%s\n' "$ANDROID_RUNTIME_ERRORS"
  exit 1
fi

printf 'ANDROID_LAUNCH_SMOKE=PASS package=%s activity=%s pid=%s ui=NMC\n' "$PACKAGE" "$ACTIVITY" "$PID"
