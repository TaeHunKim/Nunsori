#!/usr/bin/env bash
# Speech fixtures (macOS `say` + afconvert/ffmpeg) -> test-fixtures/media/ (not committed).
set -e
OUT="$(cd "$(dirname "$0")/.." && pwd)/test-fixtures/media"; mkdir -p "$OUT"; cd "$OUT"
KO="내일 오전 열 시에 은하수프로젝트 출시 일정 회의가 있습니다. 디자인 리뷰는 다음 주 화요일로 정해졌고, 담당자는 김민수 대리입니다. 마케팅 예산은 삼천만 원으로 승인되었습니다."
EN1="Today we will bake sourdough bread with rye flour. First mix the flour and water, then add the starter and let the dough rest overnight."
EN2="The quarterly budget meeting is scheduled for Monday morning. Please bring the sales report and the marketing forecast."
say -v Yuna "$KO" -o ko.aiff; say -v Samantha "$EN1" -o en1.aiff; say -v Samantha "$EN2" -o en2.aiff
afconvert -f m4af -d aac ko.aiff meeting_ko.m4a
ffmpeg -loglevel error -y -i en1.aiff -ar 44100 -ac 2 -b:a 128k cooking_en.mp3
ffmpeg -loglevel error -y -i en2.aiff -ar 16000 -ac 1 budget_en.wav
# ~80 s recording: Korean, English, pause, English, Korean
ffmpeg -loglevel error -y -f lavfi -i anullsrc=r=22050:cl=mono -t 3 gap.wav
for f in ko en1 en2; do ffmpeg -loglevel error -y -i $f.aiff -ar 22050 -ac 1 $f.wav; done
printf "file 'ko.wav'\nfile 'en1.wav'\nfile 'gap.wav'\nfile 'en2.wav'\nfile 'ko.wav'\nfile 'en1.wav'\nfile 'gap.wav'\nfile 'en2.wav'\n" > list.txt
ffmpeg -loglevel error -y -f concat -safe 0 -i list.txt -c copy long_talk.wav
ffmpeg -loglevel error -y -f lavfi -i "sine=frequency=440:duration=6" -ar 22050 -ac 1 tone.wav
rm -f *.aiff ko.wav en1.wav en2.wav gap.wav list.txt
for f in *; do printf "%-18s %5.1fs\n" "$f" "$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$f")"; done
