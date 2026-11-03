# Getting started

## Requirements

- macOS or Linux, Android Studio (its bundled JBR is the JDK used by `scripts/env.sh`).
- Android SDK with platform 36 and 37, NDK 28.2, CMake 3.31.6 (`~/Library/Android/sdk` by default).
- An emulator (AVD `lsa_api36`, 8 GB RAM, 32 GB data) or a phone running Android 10+ (minSdk 29).
- Internet access for the first build.
- The companion app **OndolSaem** (repository `taehunkim/ondolsaem`), which downloads and runs the models. Check it out next to this repository, build and install it (`cd ../ondolsaem && scripts/fetch-sherpa-onnx.sh && scripts/run.sh`) and download at least the embedding model in its Models tab.

## Build and run

```bash
git clone <this repo> && cd local_search_anything
source scripts/env.sh              # JAVA_HOME, ANDROID_HOME, PATH (adb, emulator)
./gradlew assembleDebug
scripts/emu-start.sh               # add --window for a visible emulator; skip when using a phone
scripts/run.sh                     # installDebug + launch
```

The OndolSaem client SDK comes from JitPack; to build it from a local OndolSaem checkout instead, add `ondolsaem.dir=../ondolsaem` to `local.properties` ([development.md](development.md#ondolsaem-sdk)).

For a signed release APK see [development.md](development.md#release-build).

## First run

1. Go through the **first-run wizard**: choose the kinds of files and folders (grant the media permissions when asked; text files and documents also need **All files access**), check the connection to OndolSaem (it lists the model each category uses; download them in OndolSaem: the embedding model 440M is the default, 740M adds audio embedding, SenseVoice gives audio and video transcripts), and choose automatic indexing (Manual, Immediately, Scheduled, or When idle and charging). Everything can be changed later in Settings.
2. Go to **Index** and press **Start indexing**.
3. Go to **Search** and type a query.
4. To chat about your files, install the companion app **Malbotdari** and download a chat model in OndolSaem (Gemma 4 E2B is the default, 2.6 GB); **Chat** in the menu opens it.

## Try it with test files

Test files are not in the repo. Generate them (see [development.md](development.md#fixtures)) and push with `scripts/push-fixtures.sh` (images) or `adb push` into `/sdcard/Documents|Recordings|Movies/fixtures`.
