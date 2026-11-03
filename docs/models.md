# Models

Since 0.8.0 Nunsori **does not download or run models itself**. The companion app **OndolSaem / 온돌샘** (separate repository `taehunkim/ondolsaem`) downloads the models, keeps them in memory by its own per-category policy, and serves them to Nunsori over a bound service. The model catalog, downloads, residency, memory dashboard and model licenses are documented there. Nunsori's side is `ml/ModelHost.kt`.

## What Nunsori uses

| OndolSaem category | Used for | Default model (in OndolSaem) |
|---|---|---|
| `embedding` | Image, text, audio and video vectors; search queries | EmbeddingGemma 2 440M (`eg2-440m`); 740M (`eg2-740m`) also embeds audio |
| `ocr` | Text in photos and scanned PDF pages | ML Kit Korean + Latin (`mlkit-ko`, built into OndolSaem) |
| `asr` | Speech detection (VAD, always available) and transcripts of audio and video | SenseVoice (`asr-sensevoice`) |
| `llm` | Chat and scheduled tasks | Gemma 4 E2B (`chat-gemma4-e2b`) |

## How it fits

- `ModelHost` hands out `Lease`s on remote implementations of the existing interfaces (`Embedder`, `OcrEngine`, `SpeechTools`, `ChatModel`), so indexing, search and chat code is unchanged. Each lease also holds a host lease, which keeps the model loaded in OndolSaem while Nunsori uses it (an indexing pass, a chat turn).
- Indexing calls are sent with background priority, search queries and chat with interactive priority; OndolSaem serves interactive requests first.
- Chat tools (`search_index`, `read_file`, ...) still run in Nunsori: OndolSaem's chat model calls them back through the SDK.
- Large inputs (images, WAV, PCM files) are passed as file descriptors.
- The ids of OndolSaem's active models are mirrored into Nunsori's settings (`embedding_model_id`, `ocr_model_id`, `asr_model_id`, `chat_model_id`) by `ModelHost.refresh()`: at app start, before every indexing pass, on OndolSaem's model-change callback and on its `MODEL_CHANGED` broadcast (which also reaches Nunsori when it is not running). The ids are part of `pipelineKey`, so a model change in OndolSaem re-indexes exactly the affected items. Ids are the same as before 0.8.0, so upgrading does not re-index.
- Every embedding result carries the model id; a mismatch (model changed mid-run) fails the call and triggers a refresh.
- The chat context size is set in OndolSaem and reported to Nunsori (`ModelInfo.contextTokens`), which trims prompts to it.

## Measured behaviour (Phase 0, emulator)

These still hold, now inside OndolSaem:

- Only the **740M** model embeds audio; input is **WAV bytes, 16 kHz mono**, at most **1024 tokens, about 40 s**, hence 30 s windows with 5 s overlap.
- `InputData.Image` with several images returns **one vector** (close to the mean of per-frame vectors, cosine 0.90). At most 8 images per clip; `visionTokensPerImage=70`.
- sherpa-onnx (SenseVoice int8 + Silero VAD) runs at about 2% of real time on the emulator.
- The emulator uses CPU. Real devices try GPU first and fall back to CPU.

## Adding a model or a category

Models and categories are added in OndolSaem (its `docs/architecture.md`). A new category that Nunsori should use also needs a client call in `ModelHost` and, if it produces stored data, a part in `PipelineKeys`.
