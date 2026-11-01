# Third-party notices

Local Search Anything is licensed under the [Apache License 2.0](LICENSE), Copyright 2026 Taehun Kim.
It uses the components below. Versions come from [`gradle/libs.versions.toml`](gradle/libs.versions.toml).

## Libraries (linked into the app)

| Component | Version | License |
|---|---|---|
| AndroidX (Compose BoM, Material 3, Activity, Navigation, Lifecycle, Room, WorkManager, DataStore, AppCompat, ExifInterface) | see catalog (Compose BoM 2026.09.00, Room 2.8.5, WorkManager 2.12.0) | Apache-2.0 |
| Kotlin, kotlinx.coroutines, kotlinx.serialization | 2.4.21 / 1.11.0 / 1.12.0 | Apache-2.0 |
| OkHttp | 5.5.1 | Apache-2.0 |
| Coil | 3.6.3 | Apache-2.0 |
| LiteRT-LM (`litertlm-android`) | 0.19.0 | Apache-2.0 |
| sherpa-onnx (AAR, downloaded by `scripts/fetch-sherpa-onnx.sh`, not committed) | 2.0.1 | Apache-2.0 |
| pdfbox-android | 2.0.27.0 | Apache-2.0 |
| Apache Commons Compress | 1.28.0 | Apache-2.0 |
| commonmark-java (+ GFM tables) | 0.32.0 | BSD-2-Clause |
| jsoup | 1.23.2 | MIT |
| ML Kit Text Recognition (Korean) | 16.0.1 | [ML Kit Terms of Service](https://developers.google.com/ml-kit/terms) (proprietary; fetched through Gradle, not redistributed in this repository) |

## Data bundled in the app

| Data | License |
|---|---|
| Place names for photo locations: `app/src/main/assets/places.tsv.gz`, built by `scripts/make-places.py` from [GeoNames](https://www.geonames.org) `cities15000`, alternate names and country info (about 34,000 cities, English and Korean names) | CC BY 4.0, attribution shown in Settings > About |

## Models

No model is stored in this repository. The app downloads them at the user's request (Settings), except Silero VAD, which
is fetched at build time and bundled in the APK. Each model keeps its own license; check it before redistributing.

| Model | Size | Source | License as stated by the source |
|---|---|---|---|
| EmbeddingGemma 2 text+vision 440M | 388 MB | Hugging Face `litert-community/embeddinggemma-2-text-vision-440m-litert-lm` | Apache-2.0 (model card) |
| EmbeddingGemma 2 740M (+audio) | ~485 MB | Hugging Face `litert-community/embeddinggemma-2-740m-litert-lm` | Apache-2.0 (model card) |
| SenseVoice small, int8 (via sherpa-onnx) | 163 MB archive | GitHub `k2-fsa/sherpa-onnx` release `asr-models` | FunASR Model License |
| Silero VAD | 0.6 MB | GitHub `k2-fsa/sherpa-onnx` release `asr-models` | MIT |
| ML Kit Korean text recognition | n/a | Google (Gradle dependency) | ML Kit Terms |

The EmbeddingGemma model cards say Apache-2.0 but also link to the Gemma terms of use; the catalog in
[`ModelCatalog.kt`](app/src/main/java/com/lsa/app/models/ModelCatalog.kt) follows the card. Re-check before shipping
a release build.

## Test data

Test fixtures (photos, documents, audio, video) are generated or downloaded by scripts in `scripts/` and are **not**
committed. Sample photos come from Wikimedia Commons under their individual licenses.
