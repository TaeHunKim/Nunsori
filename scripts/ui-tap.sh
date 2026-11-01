#!/usr/bin/env bash
# usage: ui-tap.sh "visible text" [index]   -- taps the center of the index-th (default 0) node whose text/content-desc equals the text
# (substring match as fallback). Retries for $RETRY seconds (default 1) while the text is not on screen yet.
source "$(dirname "$0")/env.sh"
for attempt in $(seq 1 "${RETRY:-1}"); do
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml /tmp/lsa_ui.xml >/dev/null 2>&1
  if python3 - "$1" "${2:-0}" <<'PY'
import re, sys, subprocess, xml.etree.ElementTree as ET
want, idx = sys.argv[1], int(sys.argv[2])
nodes = [n for n in ET.parse('/tmp/lsa_ui.xml').iter('node')]
def center(n):
    m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', n.get('bounds'))
    x1, y1, x2, y2 = map(int, m.groups()); return (x1 + x2) // 2, (y1 + y2) // 2
def find(pred): return [n for n in nodes if pred(n)]
hits = find(lambda n: want in (n.get('text'), n.get('content-desc'))) or find(lambda n: want in (n.get('text') or '') or want in (n.get('content-desc') or ''))
if len(hits) <= idx: sys.exit(1)
x, y = center(hits[idx]); subprocess.run(['adb', 'shell', 'input', 'tap', str(x), str(y)])
print(f"tapped '{want}' at {x},{y}")
PY
  then exit 0; fi
  sleep 1
done
echo "not found: $1"; exit 1
