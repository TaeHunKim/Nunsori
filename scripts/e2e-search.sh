#!/usr/bin/env bash
# Real-model search E2E on the emulator. Prereq: fixtures pushed (push-fixtures.sh), app installed, folder
# Pictures/fixtures selected, EmbeddingGemma 2 downloaded and indexing finished (see AGENTS.md).
# Checks that the top hit of each (ASCII) query is the expected fixture. Korean queries: see AGENTS.md.
source "$(dirname "$0")/env.sh"
fail=0
name_of() { adb shell "content query --uri content://media/external/images/media/$1 --projection _display_name" | sed -E 's/.*_display_name=//; s/^copy[0-9]+_//' | tr -d '\r'; }
"$(dirname "$0")/goto-search.sh"
check() {
  local q="$1" want="$2" out id got
  out=$("$(dirname "$0")/ui-search.sh" "$q")
  id=$(echo "$out" | sed -E 's/.*top=\[([0-9]+).*/\1/')
  got=$(name_of "$id")
  if [ "$got" = "$want" ]; then echo "PASS  '$q' -> $got"; else echo "FAIL  '$q' -> $got (want $want)  [$out]"; fail=1; fi
}
check "a cat"                       cat.jpg
check "snowy mountain"              mountain.jpg
check "pizza"                       pizza.jpg
check "a dog on grass"              dog.jpg
check "receipt from a coffee shop"  receipt_ko.jpg
check "invoice"                     invoice_en.jpg
check "Seoul Station"               sign_en.jpg
exit $fail
