#!/usr/bin/env bash
# Downloads the sherpa-onnx Android AAR (speech recognition + VAD). It is not on Maven Central and is not committed.
set -e
VERSION=2.0.1
DEST="$(cd "$(dirname "$0")/.." && pwd)/app/libs"
FILE="$DEST/sherpa-onnx-$VERSION.aar"
mkdir -p "$DEST"
# Silero VAD (0.6 MB) is bundled as an asset; it is not committed either.
ASSETS="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/assets"
mkdir -p "$ASSETS"
[ -f "$ASSETS/silero_vad.onnx" ] || curl -fL -o "$ASSETS/silero_vad.onnx" "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"
if [ -f "$FILE" ]; then echo "already present: $FILE"; exit 0; fi
curl -fL -o "$FILE.part" "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$VERSION/sherpa-onnx-$VERSION.aar"
mv "$FILE.part" "$FILE"
echo "downloaded $FILE ($(wc -c < "$FILE") bytes)"
