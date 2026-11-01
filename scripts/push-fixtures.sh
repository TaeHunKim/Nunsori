#!/usr/bin/env bash
# Pushes test-fixtures/images -> /sdcard/Pictures/fixtures and registers them in MediaStore.
source "$(dirname "$0")/env.sh"
DST=/sdcard/Pictures/fixtures
adb shell mkdir -p $DST
adb push "$ROOT/test-fixtures/images/." $DST/ >/dev/null
for f in "$ROOT"/test-fixtures/images/*; do
  adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file://$DST/$(basename "$f")" >/dev/null
done
sleep 2
adb shell ls $DST
