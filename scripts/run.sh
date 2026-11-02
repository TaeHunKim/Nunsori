#!/usr/bin/env bash
set -e; source "$(dirname "$0")/env.sh"; cd "$ROOT"
./gradlew :app:installDebug
adb shell am start -n "$APP_ID/$APP_ACTIVITY"
