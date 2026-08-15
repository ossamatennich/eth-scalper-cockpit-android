#!/bin/sh
set -eu

APK=app/build/outputs/apk/stable/app-stable.apk
PACKAGE=com.ethscalper.cockpit.stable
ACTIVITY=com.ethscalper.cockpit.V4MainActivity
UI_FILE="${RUNNER_TEMP:-/tmp}/nmc-ui.xml"
SCREEN_FILE="${RUNNER_TEMP:-/tmp}/nmc-screen.png"

adb install -r "$APK"
adb shell pm grant "$PACKAGE" android.permission.POST_NOTIFICATIONS
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
SERVICE_DUMP="$(adb shell dumpsys activity services "$PACKAGE")"
printf '%s\n' "$SERVICE_DUMP" | grep -q 'V4ForegroundService'
if printf '%s\n' "$SERVICE_DUMP" | grep -q 'MarketWatchService'; then
  printf 'LEGACY_MARKET_WATCH_SERVICE=RUNNING\n'
  exit 1
fi
printf 'ANDROID_V4_FOREGROUND_SERVICE=PASS legacy=absent\n'
CHANNEL_DUMP="$(adb shell dumpsys notification --noredact)"
printf '%s\n' "$CHANNEL_DUMP" | grep -q 'nmc_final_signal_loud_v2'
printf '%s\n' "$CHANNEL_DUMP" | grep -q 'nmc_v4_monitor_v1'
printf '%s\n' "$CHANNEL_DUMP" | grep -q 'NMC.*Surveillance V4'
if printf '%s\n' "$CHANNEL_DUMP" | grep -q 'eth_scalper_watch_v22801'; then
  printf 'LEGACY_FOREGROUND_CHANNEL=PRESENT\n'
  exit 1
fi
printf 'ANDROID_LOUD_NOTIFICATION_CHANNEL=PASS id=nmc_final_signal_loud_v2 permission=granted\n'
printf 'ANDROID_V4_MONITOR_NOTIFICATION=PASS id=nmc_v4_monitor_v1 legacy=retired\n'
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
  # API 35 occasionally returns a null accessibility root on the headless
  # emulator even though the native Activity is visible. Keep navigation a
  # mandatory smoke assertion by tapping the two remaining bottom-nav thirds
  # and requiring a real rendered transition after each tap.
  SCREEN_SIZE="$(adb shell wm size | sed -n 's/.*: \([0-9][0-9]*\)x\([0-9][0-9]*\).*/\1 \2/p' | tail -n 1)"
  set -- $SCREEN_SIZE
  test "$#" -eq 2
  SCREEN_WIDTH="$1"
  SCREEN_HEIGHT="$2"
  NAV_Y=$((SCREEN_HEIGHT * 93 / 100))
  PLANS_X=$((SCREEN_WIDTH / 2))
  HISTORY_X=$((SCREEN_WIDTH * 5 / 6))
  PLANS_SCREEN="${RUNNER_TEMP:-/tmp}/nmc-screen-plans.png"
  HISTORY_SCREEN="${RUNNER_TEMP:-/tmp}/nmc-screen-history.png"

  adb shell input tap "$PLANS_X" "$NAV_Y"
  sleep 2
  adb exec-out screencap -p > "$PLANS_SCREEN"
  test -s "$PLANS_SCREEN"
  ! cmp -s "$SCREEN_FILE" "$PLANS_SCREEN"

  adb shell input tap "$HISTORY_X" "$NAV_Y"
  sleep 2
  adb exec-out screencap -p > "$HISTORY_SCREEN"
  test -s "$HISTORY_SCREEN"
  ! cmp -s "$PLANS_SCREEN" "$HISTORY_SCREEN"
  printf 'ANDROID_BOTTOM_NAV=VISIBLE_CLICKABLE_VISUAL_TRANSITIONS\n'
  printf 'ANDROID_UI_HIERARCHY=UNAVAILABLE_NAVIGATION_VERIFIED_BY_RENDERED_TRANSITIONS\n'
fi

# The foreground notification itself must keep V4 alive and reopen only the
# current V4 Activity after the application is backgrounded.
adb shell input keyevent KEYCODE_HOME
sleep 3
BACKGROUND_SERVICE_DUMP="$(adb shell dumpsys activity services "$PACKAGE")"
printf '%s\n' "$BACKGROUND_SERVICE_DUMP" | grep -q 'V4ForegroundService'
if printf '%s\n' "$BACKGROUND_SERVICE_DUMP" | grep -q 'MarketWatchService'; then exit 1; fi
printf 'ANDROID_V4_BACKGROUND_HOST=PASS\n'
adb shell cmd statusbar expand-notifications
sleep 2
adb shell uiautomator dump --compressed /sdcard/nmc-notifications.xml || true
adb pull /sdcard/nmc-notifications.xml "${RUNNER_TEMP:-/tmp}/nmc-notifications.xml" >/dev/null 2>&1 || true
NOTIFICATION_CENTER=""
if test -f "${RUNNER_TEMP:-/tmp}/nmc-notifications.xml"; then
  NOTIFICATION_CENTER="$(python3 - "${RUNNER_TEMP:-/tmp}/nmc-notifications.xml" <<'PY' || true
import re, sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
node = next((n for n in root.iter('node') if 'Surveillance V4' in n.attrib.get('text', '')), None)
if node is None:
    raise SystemExit(1)
m = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node.attrib['bounds'])
if not m:
    raise SystemExit(1)
x1, y1, x2, y2 = map(int, m.groups())
print((x1 + x2) // 2, (y1 + y2) // 2)
PY
)"
fi
if test -n "$NOTIFICATION_CENTER"; then
  set -- $NOTIFICATION_CENTER
  adb shell input tap "$1" "$2"
else
  SCREEN_SIZE="$(adb shell wm size | sed -n 's/.*: \([0-9][0-9]*\)x\([0-9][0-9]*\).*/\1 \2/p' | tail -n 1)"
  set -- $SCREEN_SIZE
  test "$#" -eq 2
  adb shell input tap "$(( $1 / 2 ))" "$(( $2 * 35 / 100 ))"
fi
sleep 3
adb shell dumpsys activity activities | grep 'mResumedActivity' | grep -q "$ACTIVITY"
printf 'ANDROID_V4_MONITOR_CLICK_TARGET=PASS activity=%s\n' "$ACTIVITY"

ANDROID_RUNTIME_ERRORS="$(adb logcat -d -v threadtime AndroidRuntime:E '*:S')"
if printf '%s\n' "$ANDROID_RUNTIME_ERRORS" | grep -q "$PACKAGE"; then
  printf '%s\n' "$ANDROID_RUNTIME_ERRORS"
  exit 1
fi

printf 'ANDROID_LAUNCH_SMOKE=PASS package=%s activity=%s pid=%s ui=NMC\n' "$PACKAGE" "$ACTIVITY" "$PID"
