# Models

Catalog: `models/ModelCatalog.kt`. Models are never committed or bundled (except Silero VAD and ML Kit); the user downloads them in Settings. Licenses: [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md).

| Id | Role | Size | Source | License |
|---|---|---|---|---|
| `eg2-440m` | Embedding: text + image | 387,710,976 B | Hugging Face `litert-community/embeddinggemma-2-text-vision-440m-litert-lm` | Apache-2.0 |
| `eg2-740m` | Embedding: text + image + **audio** | ~485 MB | Hugging Face `litert-community/embeddinggemma-2-740m-litert-lm` | Apache-2.0 |
| `mobileclip2-s0` | Planned, not implemented | n/a | n/a | Apple ASCL |
| `mlkit-ko` | OCR (Korean + Latin), bundled via Gradle | n/a | Google ML Kit | ML Kit Terms |
| `asr-sensevoice` | Speech recognition (ko, en, zh, ja, yue) | 163,002,883 B | GitHub `k2-fsa/sherpa-onnx` release `asr-models` (tar.bz2, unpacked by `ArchiveExtractor`) | FunASR Model License |
| Silero VAD | Voice activity detection | 0.6 MB | same release; fetched by `scripts/fetch-sherpa-onnx.sh`, bundled in the APK, not committed | MIT |

Downloads are resumable (`ModelDownloader`, OkHttp, `.part` files) and run in `ModelDownloadWorker`. A Hugging Face token (optional) is entered in Settings and only sent to Hugging Face.

## Measured behaviour (Phase 0, emulator)

- Only the **740M** model embeds audio. It needs `EmbeddingEngineConfig(audioBackend=...)`, and input is **WAV bytes, 16 kHz mono**. One input holds **1024 tokens, about 40 s**, hence 30 s windows with 5 s overlap.
- `InputData.Image` with several images returns **one vector**; it is close to the mean of per-frame vectors (cosine 0.90, order independent). At most 8 images per clip; `visionTokensPerImage=70`.
- sherpa-onnx (SenseVoice int8 + Silero VAD) runs at about 2% of real time on the emulator; Korean and English recognition is good.
- The emulator uses CPU. Real devices try GPU first and fall back to CPU. No real-device benchmarks yet ([roadmap](roadmap.md)).

## Adding a model

1. Add a `ModelSpec` to `ModelCatalog.all` (id, kind, size, license, `ModelSource`).
2. Embedding: implement `Embedder` (`ml/embedding`) and register the id in `ModelResidency`'s loader (`when (id)`). OCR: implement `OcrEngine`. ASR: extend `SpeechTools`.
3. Add description strings in both `values/strings.xml` and `values-ko/strings.xml`.
4. Changing the embedding model changes `pipelineKey`, so affected kinds are re-indexed.
5. Update this file and THIRD_PARTY_NOTICES.md.
