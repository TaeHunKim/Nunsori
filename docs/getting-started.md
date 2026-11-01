# Getting started

## Requirements

- macOS or Linux, Android Studio (its bundled JBR is the JDK used by `scripts/env.sh`).
- Android SDK with platform 36 and 37, NDK 28.2, CMake 3.31.6 (`~/Library/Android/sdk` by default).
- An emulator (AVD `lsa_api36`, 8 GB RAM, 32 GB data) or a phone running Android 10+ (minSdk 29).
- Internet access for the first build and for model downloads.

## Build and run

```bash
git clone <this repo> && cd local_search_anything
source scripts/env.sh              # JAVA_HOME, ANDROID_HOME, PATH (adb, emulator)
scripts/fetch-sherpa-onnx.sh       # sherpa-onnx AAR -> app/libs, Silero VAD -> app/src/main/assets (not committed)
./gradlew assembleDebug
scripts/emu-start.sh               # add --window for a visible emulator; skip when using a phone
scripts/run.sh                     # installDebug + launch
```

`scripts/fetch-sherpa-onnx.sh` must run before the first build; the AAR and VAD file are not in git.

## First run

1. **Choose automatic indexing** on the welcome screen (Manual, Immediately, Scheduled, or When idle and charging). You can change it later in Settings.
2. Open **Settings**: pick the kinds to index, add folders, and press **Download** on an embedding model (440M is the default; 740M adds audio embedding). Speech recognition for audio and video transcripts is a separate download.
3. Grant media permissions when asked. Text files and documents also need **All files access**.
4. Go to **Index** and press **Start indexing**.
5. Go to **Search** and type a query.

## Try it with test files

Test files are not in the repo. Generate them (see [development.md](development.md#fixtures)) and push with `scripts/push-fixtures.sh` (images) or `adb push` into `/sdcard/Documents|Recordings|Movies/fixtures`.
