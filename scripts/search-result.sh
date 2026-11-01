#!/usr/bin/env bash
# Prints the last search ranking from logcat with item ids resolved to file names.
source "$(dirname "$0")/env.sh"
LINE=$(adb logcat -d -s LSA.Search | grep RESULT | tail -1 | sed 's/.*q=/q=/')
[ -z "$LINE" ] && { echo "(no result logged)"; exit 1; }
MAP=$(adb shell "run-as com.lsa.app sqlite3 databases/lsa.db 'select itemId, displayName from items'" 2>/dev/null | tr -d '\r')
python3 - "$LINE" "$MAP" <<'PY'
import re, sys
line, mp = sys.argv[1], sys.argv[2]
names = dict(l.split('|', 1) for l in mp.splitlines() if '|' in l)
m = re.search(r'top=\[(.*?)\]', line)
ids = [i.strip() for i in m.group(1).split(',')] if m and m.group(1) else []
print(line.split(' top=')[0], '->', [names.get(i, i) for i in ids])
PY
