#!/usr/bin/env bash
# usage: emu-start.sh [--window]
set -e; source "$(dirname "$0")/env.sh"
if adb devices | grep -q "^emulator-.*device$"; then echo "emulator already running"; exit 0; fi
FLAGS="-no-window -no-audio -no-snapshot-save"
[ "$1" = "--window" ] && FLAGS="-no-snapshot-save"
nohup emulator -avd "$AVD_NAME" $FLAGS -gpu host >"$ROOT/build/emulator.log" 2>&1 &
adb wait-for-device
for i in $(seq 1 120); do
  [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && { echo "booted"; exit 0; }
  sleep 2
done
echo "boot timeout; see build/emulator.log" >&2; exit 1
