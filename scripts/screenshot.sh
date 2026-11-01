#!/usr/bin/env bash
source "$(dirname "$0")/env.sh"
OUT="${1:-$ROOT/build/screens/$(date +%H%M%S).png}"; mkdir -p "$(dirname "$OUT")"
adb exec-out screencap -p > "$OUT" && echo "$OUT"
