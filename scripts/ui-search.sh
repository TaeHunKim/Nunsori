#!/usr/bin/env bash
# usage: ui-search.sh "query" -- replaces the text in the focused Search field and submits; prints the ranked file names.
source "$(dirname "$0")/env.sh"
adb logcat -c
adb shell input keycombination 113 29   # CTRL+A
adb shell input keyevent 67             # DEL
adb shell input text "$(printf '%s' "$1" | sed 's/ /%s/g')"
adb shell input keyevent 66             # ENTER
sleep 2.5
adb logcat -d -s LSA.Search | grep RESULT | tail -1 | sed -E 's/.*q=/q=/; s/\b21\b/dog/g; s/\b22\b/sign/g; s/\b23\b/receipt/g; s/\b24\b/invoice/g; s/\b25\b/cat/g; s/\b26\b/mountain/g; s/\b27\b/meeting/g; s/\b28\b/pizza/g'
