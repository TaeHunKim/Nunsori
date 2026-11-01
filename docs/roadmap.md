# Roadmap and known limits

## Next

- Done in 0.4.0: failed-file list and retry, embedding-length guard, pinned chats, Markdown, chat instructions, low-power indexing, folder presets, resource indicator.

- **Web tools for chat:** web search and fetching a page for the chat model. Deferred on purpose: the app only uses the network to download models today, so this needs its own opt-in setting, a disclosure of what leaves the device, and a search API choice.
- Chat: attach arbitrary files that are not indexed (text extraction on the spot), NPU/GPU choice, summaries for long chats instead of dropping old messages.
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
