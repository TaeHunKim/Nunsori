#!/usr/bin/env bash
# Downloads the public photo fixtures (Wikimedia Commons) into test-fixtures/images/. They are not committed.
# Text fixtures: uv run --with pillow scripts/make-text-fixtures.py
set -e
cd "$(dirname "$0")/../test-fixtures/images" 2>/dev/null || { mkdir -p "$(dirname "$0")/../test-fixtures/images"; cd "$(dirname "$0")/../test-fixtures/images"; }
get() { curl -sL -A "Mozilla/5.0 (local-search-anything fixture fetch)" -o "$1" "https://commons.wikimedia.org/wiki/Special:FilePath/$2?width=1024"; file "$1" | cut -c1-80; }
get cat.jpg      "Cat_November_2010-1a.jpg"
get pizza.jpg    "Eq_it-na_pizza-margherita_sep2005_sml.jpg"
get mountain.jpg "Mount_Everest_as_seen_from_Drukair2_PLW_edit.jpg"
get dog.jpg      "YellowLabradorLooking_new.jpg"
