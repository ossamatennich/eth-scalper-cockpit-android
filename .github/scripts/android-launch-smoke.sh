#!/bin/sh
set -eu

APK=app/build/outputs/apk/stable/app-stable.apk
PACKAGE=com.ethscalper.cockpit.stable
ACTIVITY=com.ethscalper.cockpit.V4MainActivity
UI_FILE="${RUNNER_TEMP:-/tmp}/nmc-ui.xml"

adb install -r "$APK"
adb shell am force-stop "$PACKAGE"
adb logcat -c
START_OUTPUT="$(adb shell am start -W "$PACKAGE/$ACTIVITY")"
printf '%s\n' "$START_OUTPUT"
printf '%s\n' "$START_OUTPUT" | grep -q 'Status: ok'
sleep 8
PID="$(adb shell pidof "$PACKAGE")"
test -n "$PID"
adb shell dumpsys activity activities | grep -q "$ACTIVITY"

UI_OK=false
for attempt in 1 2 3; do
  adb shell uiautomator dump --compressed /sdcard/nmc-ui.xml >/dev/null 2>&1 || true
  adb pull /sdcard/nmc-ui.xml "$UI_FILE" >/dev/null 2>&1 || true
  if test -f "$UI_FILE" && grep -q 'text="NMC"' "$UI_FILE"; then
    UI_OK=true
    break
  fi
  sleep 2
done
test "$UI_OK" = true

ANDROID_RUNTIME_ERRORS="$(adb logcat -d -v threadtime AndroidRuntime:E '*:S')"
if printf '%s\n' "$ANDROID_RUNTIME_ERRORS" | grep -q "$PACKAGE"; then
  printf '%s\n' "$ANDROID_RUNTIME_ERRORS"
  exit 1
fi

printf 'ANDROID_LAUNCH_SMOKE=PASS package=%s activity=%s pid=%s ui=NMC\n' "$PACKAGE" "$ACTIVITY" "$PID"
