#!/usr/bin/env bash
# Fresh-install E2E setup on the emulator, driven through the real UI:
# install -> permissions -> push fixtures -> onboarding (manual) -> enable kinds -> add folder -> download model.
# usage: e2e-setup.sh [model: 440m|740m]  (default 440m)  [kinds...: text docs]  e.g. e2e-setup.sh 440m text docs
source "$(dirname "$0")/env.sh"; cd "$ROOT"
MODEL=${1:-440m}; shift || true
KINDS=("$@")
tap() { RETRY=10 "$ROOT/scripts/ui-tap.sh" "$@" >/dev/null || echo "  (could not tap: $1)"; sleep "${WAIT:-1.5}"; }
nav() { adb shell input tap 74 216; sleep 1.5; adb shell input tap "$1" "$2"; sleep 2; }

./gradlew :app:installDebug -q 2>&1 | grep -E "^e:|FAILED"
for p in READ_MEDIA_IMAGES POST_NOTIFICATIONS; do adb shell pm grant $APP_ID android.permission.$p; done
adb shell appops set $APP_ID MANAGE_EXTERNAL_STORAGE allow
adb shell mkdir -p /sdcard/Documents/fixtures
adb push test-fixtures/docs/. /sdcard/Documents/fixtures/ >/dev/null 2>&1
adb logcat -c; adb shell am start -n $APP_ID/$APP_ACTIVITY >/dev/null; sleep 3
# The first-run dialog is a separate window that uiautomator does not always report: tap by coordinates.
sleep 4; adb shell input tap 360 1452; sleep 1; adb shell input tap 792 1716; sleep 2
nav 234 700                                    # Settings
for k in "${KINDS[@]}"; do
  case $k in
    text) tap "Text files (txt, md, html, csv, …)";;
    docs) tap "Documents (PDF, Word, PowerPoint, Excel)";;
    audio) tap "Audio";;
    video) tap "Video";;
  esac
done
tap "Add folder"; tap "Documents"; tap "fixtures"; tap "Add this folder"; tap "Close"
if [ "$MODEL" = "740m" ]; then tap "Download" 1; else tap "Download" 0; fi
ID=eg2-${MODEL}; FILE=$([ "$MODEL" = "740m" ] && echo embeddinggemma-2-740m.litertlm || echo embeddinggemma-2-text-vision-440m.litertlm)
for i in $(seq 1 60); do
  sleep 3; S=$(adb shell run-as $APP_ID ls -l files/models/$ID/$FILE 2>/dev/null | awk '{print $5}')
  [ -n "$S" ] && [ "$S" -gt 380000000 ] && { echo "model ready ($S bytes)"; break; }
done
echo "setup done"
