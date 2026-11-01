#!/usr/bin/env bash
# usage: ui-search.sh "query" -- replaces the text in the focused Search field, submits, prints ranked file names
# (resolved through the app database). ASCII only; use mobile-mcp type_keys for Korean.
source "$(dirname "$0")/env.sh"
adb logcat -c
adb shell input keycombination 113 29   # CTRL+A
adb shell input keyevent 67             # DEL
adb shell input text "$(printf '%s' "$1" | sed 's/ /%s/g')"
adb shell input keyevent 66             # ENTER
sleep 2.5
"$(dirname "$0")/search-result.sh"
