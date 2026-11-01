#!/usr/bin/env bash
# usage: logcat.sh [lines=200]  -- dumps app-process logs and exits
source "$(dirname "$0")/env.sh"
PID=$(adb shell pidof "$APP_ID" | tr -d '\r')
if [ -n "$PID" ]; then adb logcat -d -t "${1:-200}" --pid="$PID"; else echo "app not running; crash/fatal log:"; adb logcat -d -t "${1:-200}" '*:E'; fi
