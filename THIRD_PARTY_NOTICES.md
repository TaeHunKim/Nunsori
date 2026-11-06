# Third-party notices

Nunsori (Local Search Anything) is licensed under the [Apache License 2.0](LICENSE), Copyright 2026 Taehun Kim.
It uses the components below. Versions come from [`gradle/libs.versions.toml`](gradle/libs.versions.toml).

## Libraries (linked into the app)

| Component | Version | License |
|---|---|---|
| AndroidX (Compose BoM, Material 3, Activity, Navigation, Lifecycle, Room, WorkManager, DataStore, AppCompat, ExifInterface) | see catalog (Compose BoM 2026.09.00, Room 2.8.5, WorkManager 2.12.0) | Apache-2.0 |
| Kotlin, kotlinx.coroutines, kotlinx.serialization | 2.4.21 / 1.11.0 / 1.12.0 | Apache-2.0 |
| OndolSaem client SDK (`com.github.taehunkim.ondolsaem:sdk`) | 0.1.0 | Apache-2.0 |
| Coil (incl. coil-svg) | 3.6.3 | Apache-2.0 |
| AndroidSVG | 1.4 | Apache-2.0 |
| pdfbox-android | 2.0.27.0 | Apache-2.0 |
| commonmark-java (+ GFM tables) | 0.32.0 | BSD-2-Clause |
| jsoup | 1.23.2 | MIT |

## Data bundled in the app

| Data | License |
|---|---|
| Place names for photo locations: `app/src/main/assets/places.bin`, built by `scripts/make-places.py` from [GeoNames](https://www.geonames.org) `cities15000`, alternate names and country info (about 34,000 cities, English and Korean names) | CC BY 4.0, attribution shown in Settings > About |

## Models

No model is stored in this repository or bundled in the app. Since 0.8.0 the models are downloaded and run by the
companion app OndolSaem (repository `sonanjip/ondolsaem`), whose THIRD_PARTY_NOTICES.md lists them with their
licenses (EmbeddingGemma 2, Gemma 4, Qwen2.5, SenseVoice, Silero VAD, ML Kit text recognition) together with the
runtimes (LiteRT-LM, sherpa-onnx, ML Kit).

## Test data

Test fixtures (photos, documents, audio, video) are generated or downloaded by scripts in `scripts/` and are **not**
committed. Sample photos come from Wikimedia Commons under their individual licenses.
