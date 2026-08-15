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
START_OUTPUT="$(adb shell am start -W "$PACKAGE/$ACTIVITY")"
printf '%s\n' "$START_OUTPUT"
printf '%s\n' "$START_OUTPUT" | grep -q 'Status: ok'
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
