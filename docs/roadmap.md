# Roadmap and known limits

## Next

- **Local LLM RAG:** answer questions from retrieved segments with an on-device LLM.
- Additional models: MobileCLIP2-S0 (listed, not implemented), PP-OCRv5.
- Real-device performance measurements (GPU/NPU paths, thermal behaviour).
- Multiple embedding models at the same time.
- Embed place names as text segments (`PLACE_TEXT`) for fuzzy place queries; skipped for now because name matching plus aliases covers the common cases.
- Geotags for videos.

## Possible scaling work

- FTS5 trigram index if `LIKE` keyword search gets slow.
- 256-d vectors and/or an ANN index beyond about 300k segments.

## Known limits

- Office files are not rendered: text plus a generated text card only. Old .doc/.ppt/.xls and hwp are unsupported.
- Vectors are scanned in memory (int8); target about 300k segments.
- Video is embedded per scene/length clip, so moments shorter than a few seconds can be missed; precision is configurable.
- Only the 740M model embeds audio; input is limited to about 40 s per clip.
- Emulator inference is CPU only and slow.
