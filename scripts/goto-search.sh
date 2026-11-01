#!/usr/bin/env bash
# Launches the app and opens the Search tab, focusing the query field (coordinates for 1080x2400 Pixel 8 AVD).
source "$(dirname "$0")/env.sh"
adb shell am start -n "$APP_ID/.MainActivity" >/dev/null; sleep 2
adb shell input tap 74 216; sleep 1      # open drawer
adb shell input tap 234 552; sleep 1     # Search
adb shell input tap 400 373; sleep 1     # focus field
