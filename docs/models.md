# Models

Catalog: `models/ModelCatalog.kt`. Models are never committed or bundled (except Silero VAD and ML Kit); the user downloads them in Settings. Licenses: [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md).

| Id | Role | Size | Source | License |
|---|---|---|---|---|
| `eg2-440m` | Embedding: text + image | 387,710,976 B | Hugging Face `litert-community/embeddinggemma-2-text-vision-440m-litert-lm` | Apache-2.0 |
| `eg2-740m` | Embedding: text + image + **audio** | ~485 MB | Hugging Face `litert-community/embeddinggemma-2-740m-litert-lm` | Apache-2.0 |
| `mobileclip2-s0` | Planned, not implemented | n/a | n/a | Apple ASCL |
| `chat-gemma4-e2b` | Chat (text + images), default | 2,588,147,712 B | Hugging Face `litert-community/gemma-4-E2B-it-litert-lm` (`gemma-4-E2B-it.litertlm`) | Apache-2.0 (model card), not gated |
| `chat-gemma4-e4b` | Chat (text + images), larger | 3,659,530,240 B | Hugging Face `litert-community/gemma-4-E4B-it-litert-lm` (`gemma-4-E4B-it.litertlm`) | Apache-2.0, not gated |
| `chat-qwen25-1.5b` | Chat (text only), smaller | 1,597,922,856 B | Hugging Face `litert-community/Qwen2.5-1.5B-Instruct` (`..._multi-prefill-seq_q8_ekv4096.litertlm`) | Apache-2.0, not gated |
| `mlkit-ko` | OCR (Korean + Latin), bundled via Gradle | n/a | Google ML Kit | ML Kit Terms |
| `asr-sensevoice` | Speech recognition (ko, en, zh, ja, yue) | 163,002,883 B | GitHub `k2-fsa/sherpa-onnx` release `asr-models` (tar.bz2, unpacked by `ArchiveExtractor`) | FunASR Model License |
| Silero VAD | Voice activity detection | 0.6 MB | same release; fetched by `scripts/fetch-sherpa-onnx.sh`, bundled in the APK, not committed | MIT |

Downloads are resumable (`ModelDownloader`, OkHttp, `.part` files) and run in `ModelDownloadWorker`. A Hugging Face token (optional) is entered in Settings and only sent to Hugging Face.

## Measured behaviour (Phase 0, emulator)

- Only the **740M** model embeds audio. It needs `EmbeddingEngineConfig(audioBackend=...)`, and input is **WAV bytes, 16 kHz mono**. One input holds **1024 tokens, about 40 s**, hence 30 s windows with 5 s overlap.
- `InputData.Image` with several images returns **one vector**; it is close to the mean of per-frame vectors (cosine 0.90, order independent). At most 8 images per clip; `visionTokensPerImage=70`.
- sherpa-onnx (SenseVoice int8 + Silero VAD) runs at about 2% of real time on the emulator; Korean and English recognition is good.
- The emulator uses CPU. Real devices try GPU first and fall back to CPU. No real-device benchmarks yet ([roadmap](roadmap.md)).

## OCR and speech: built-in options

Android has no platform OCR; ML Kit (bundled, offline) is the default and needs no download. The platform `SpeechRecognizer` was considered for speech: its on-device mode needs API 31+, feeding files needs API 33+, it is meant for the microphone at real-time speed, needs installed language packs, gives no timestamps and needs `RECORD_AUDIO`, so SenseVoice (a one-time 163 MB download offered in the first-run wizard) stays the speech engine.

## Chat models

Used by the Chat screen ([chat.md](chat.md)); `ModelKind.LLM` in the catalog, loaded by `LiteRtChatModel` (LiteRT-LM `Engine`, GPU first on real devices, CPU on the emulator) and kept in memory by `ModelResidency.chat()` like the other models. Gemma 4 E2B and E4B accept images (`ModelSpec.vision`); Qwen2.5 does not. The context window (2K/4K/8K, default 4K) is passed to the engine as `maxNumTokens`; changing it or the model reloads the model. File names, sizes and licenses were read from the Hugging Face API; re-check them before a release.

## Adding a model

1. Add a `ModelSpec` to `ModelCatalog.all` (id, kind, size, license, `ModelSource`).
2. Embedding: implement `Embedder` (`ml/embedding`) and register the id in `ModelResidency`'s loader (`when (id)`). OCR: implement `OcrEngine`. ASR: extend `SpeechTools`.
3. Add description strings in both `values/strings.xml` and `values-ko/strings.xml`.
4. Changing the embedding model changes `pipelineKey`, so affected kinds are re-indexed.
5. Update this file and THIRD_PARTY_NOTICES.md.
