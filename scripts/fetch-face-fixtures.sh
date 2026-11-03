#!/usr/bin/env bash
# Downloads public photos of a few public figures (Wikimedia Commons) into test-fixtures/faces/ for the face-grouping
# tests: several different photos per person, a family photo and a photo with two of them. Not committed.
# Push to the device: adb push test-fixtures/faces /sdcard/Pictures/faces (then a media scan, see push-fixtures.sh).
set -e
DIR="$(cd "$(dirname "$0")/.." && pwd)/test-fixtures/faces"
mkdir -p "$DIR"; cd "$DIR"
get() { curl -sL -A "Mozilla/5.0 (nunsori face fixture fetch)" -o "$1" "https://commons.wikimedia.org/wiki/Special:FilePath/$2?width=1024"; file "$1" | cut -c1-70; }
get obama_1.jpg   "President_Barack_Obama.jpg"
get obama_2.jpg   "Official_portrait_of_Barack_Obama.jpg"
get obama_family.jpg "Barack_Obama_family_portrait_2011.jpg"
get merkel_1.jpg  "Angela_Merkel_(2019-12-09).jpg"
get merkel_2.jpg  "Angela_Merkel_2019.jpg"
get macron_1.jpg  "Emmanuel_Macron_par_Claude_Truong-Ngoc_avril_2015.jpg"
get macron_2.jpg  "Emmanuel_Macron_in_July_2017.jpg"
get macron_3.jpg  "Emmanuel_Macron_2021.jpg"
get moon_1.jpg    "Moon_Jae-in_May_2017.jpg"
get moon_2.jpg    "Moon_Jae-in_2017-10-01.jpg"
get moon_3.jpg    "President_Moon_Jae-in_portrait_2017.jpg"
get swift_1.jpg   "Taylor_Swift_2_-_2019_by_Glenn_Francis.jpg"
get swift_2.png   "191125_Taylor_Swift_at_the_2019_American_Music_Awards.png"
get merkel_macron.jpg "Angela_Merkel_and_Emmanuel_Macron_(2019-10-09).jpg"
