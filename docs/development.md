# Development

Setup: [getting-started.md](getting-started.md). Design: [architecture.md](architecture.md).

## Layout

`app/` (single module): `src/main` (code under `com/lsa/app`, see the package map in architecture.md), `src/test` (JVM unit tests), `src/androidTest` (instrumented tests). Strings: `values/strings.xml` (en) and `values-ko/strings.xml` (ko) must have identical keys (`StringsParityTest`).

## Testing

| Level | Command | Notes |
|---|---|---|
| Unit | `./gradlew assembleDebug testDebugUnitTest` | Chunkers, fusion, keyword matching, DB logic |
| Instrumented | `./gradlew connectedDebugAndroidTest` | Needs a device. `ChatTest` runs the chat controller and tools against a fake `ChatModel` (`ModelHost.chatOverride`). `ModelHostTest` talks to the real OndolSaem when it is installed on the device (connection, model-id mirroring, a real embedding; skipped otherwise). It uninstalls Nunsori at the end; models live in OndolSaem and stay. Write tests that do not depend on the app's settings. |
| Real-model E2E | manual, emulator | Install OndolSaem and download the models there, index fixtures, query. Drive the UI with mobile-mcp; `adb uiautomator` is blocked while mobile-mcp is attached, so `scripts/e2e-setup.sh` may not work. |

## Release build

The release APK is signed with a release keystore that is **not in the repo**. Without it `./gradlew assembleRelease` still works but produces an unsigned APK that Android will not install.

1. Create the key once (keep it outside the repo and **back it up with its passwords**: if it is lost, installed users can no longer be updated):
   `keytool -genkeypair -keystore ~/.nunsori-keys/nunsori-release.jks -alias nunsori -keyalg RSA -keysize 4096 -validity 36500`
2. Put the settings in `~/.gradle/gradle.properties` (or environment variables of the same names): `NUNSORI_STORE_FILE`, `NUNSORI_STORE_PASSWORD`, `NUNSORI_KEY_ALIAS`, `NUNSORI_KEY_PASSWORD`.
3. Bump `appVersionName` in `app/build.gradle.kts`, then `./gradlew assembleRelease`. Output: `app/build/outputs/apk/release/app-release.apk` (arm64-v8a only). R8/minify is off on purpose (JNI libraries, reflection in pdfbox).
4. Check: `$ANDROID_HOME/build-tools/<version>/apksigner verify --print-certs app-release.apk`.
5. Publish: `git tag -a vX.Y.Z -m "Nunsori X.Y.Z"`, `git push origin vX.Y.Z`, `gh release create vX.Y.Z app-release.apk#Nunsori-X.Y.Z.apk --title ... --notes ...` (put the SHA-256 in the notes).

A release APK cannot be installed over a debug build (different signing key): uninstall first, which deletes the index and thumbnails. `adb run-as` does not work on release builds, so app data cannot be copied into one.

## OndolSaem SDK

Model hosting is moving to the separate **OndolSaem** app (roadmap item 2), which lives in its own repository, `taehunkim/ondolsaem`. Nunsori reaches it through that repository's client SDK, `com.github.taehunkim.ondolsaem:sdk` (published on JitPack from git tags).

To change the SDK and Nunsori together, check OndolSaem out next to this repository and add `ondolsaem.dir=../ondolsaem` to `local.properties`. `settings.gradle.kts` then adds it as a Gradle composite build, so the SDK is built from that source and edits there show up in the next Nunsori build. Without the line the published artifact is used.

## Scripts

| Script | Purpose |
|---|---|
| `env.sh` | JAVA_HOME / ANDROID_HOME / PATH |
| `emu-start.sh [--window]`, `emu-stop.sh` | Start or stop the AVD |
| `run.sh` | installDebug + launch |
| `logcat.sh [N]` | Logs of the app process (error log if it died) |
| `screenshot.sh` | Screenshot to `build/screens/` |
| `push-fixtures.sh` | `test-fixtures/images` to `/sdcard/Pictures/fixtures` and MediaStore scan |
| `goto-search.sh`, `ui-search.sh`, `ui-tap.sh`, `search-result.sh`, `e2e-search.sh`, `e2e-setup.sh` | UI automation helpers for E2E |
| `make-places.py` | Rebuild `app/src/main/assets/places.bin` from GeoNames (downloads about 210 MB to `build/places-src`; the result is committed) |
| `fetch-photo-fixtures.sh`, `make-text-fixtures.py`, `make-doc-fixtures.py`, `make-office-fixtures.py`, `make-audio-fixtures.sh`, `make-card-fixture.py` (fictional business card), `make-demo-docs.py` (README demo documents) | Generate fixtures |

## Fixtures

Never committed. Generate:

```bash
scripts/fetch-photo-fixtures.sh
uv run --with pillow scripts/make-text-fixtures.py
python3 scripts/make-doc-fixtures.py
uv run --with reportlab --with pillow --with python-docx --with python-pptx --with openpyxl scripts/make-office-fixtures.py
scripts/make-audio-fixtures.sh      # videos: three-scene mp4 via ffmpeg
```

Push documents, audio and video to `/sdcard/Documents|Recordings|Movies/fixtures`.

## Debugging

- Log tags: `LSA.ModelHost`, `LSA.Auto`, `LSA.Search`; OndolSaem logs under `OndolSaem.*` (`OndolSaem.Residency`, `OndolSaem.Notify`, ...).
- Inspect the DB: `adb shell "run-as io.github.taehunkim.nunsori sqlite3 databases/lsa.db 'select status,count(*) from items group by status'"`.
- mobile-mcp (`.mcp.json`) lists screen elements and taps; system permission dialogs are separate windows and may need coordinate taps.

## Schema and version changes

Version: `appVersionName` in `app/build.gradle.kts` (`major.minor.patch`); `versionCode = major*10000 + minor*100 + patch`. Settings show the app and database version.

Any change to an `@Entity` must, in the same commit:
1. bump `AppDatabase.VERSION`,
2. add `MIGRATION_n_n+1` in `data/db/Migrations.kt` and append it to `ALL_MIGRATIONS`,
3. commit the generated `app/schemas/.../n+1.json`,
4. add a migration test in `MigrationTest` (the chain test `everyExportedSchemaMigratesToTheLatestVersion` fails if one is missing).

Never use `fallbackToDestructiveMigration` for upgrades. Changes that only affect derived output go into `PipelineKeys` instead (reprocesses just that kind). Settings or file-layout changes go into a step in `AppUpgrade`. To test an upgrade by hand, index with the previous build, then `adb install -r` the new one.

## Conventions

Commit only code, scripts and docs: no models, test media, databases or build output (see `.gitignore`). Update docs with code ([CONTRIBUTING.md](../CONTRIBUTING.md)).

The current schema is v6 (`MIGRATION_5_6` adds `scheduled_tasks` and `chat_messages.taskId`; covered by `MigrationTest.v5ToV6AddsScheduledTasksThatCascadeWithTheirChat`). Scheduled-task logic is tested in `ChatTest` (fake alarms, clock, battery and model) and `ScheduleTest` (unit, next-run math).
