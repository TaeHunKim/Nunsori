# Indexing

Values below are read from the code; the file is given so you can check them.

## Sources and kinds

| Kind | Source | Extensions / notes |
|---|---|---|
| IMAGE, AUDIO, VIDEO | `MediaStoreRepository` | what MediaStore reports; needs media permissions |
| TEXT | `FileSource` | txt/plain, md (`md, markdown, mdown, mkd`), html (`html, htm, xhtml`), xml family (`xml, svg, rss, atom, plist, xsd, xsl`), `csv`, `tsv`, subtitles (`srt, vtt`); full list in `text/TextRouting.kt` |
| DOC | `FileSource` | PDF, docx/pptx/xlsx (OOXML), odt/odp/ods (ODF). Old .doc/.ppt/.xls and hwp are unsupported |

`FileSource` needs "All files access". Folder keys equal MediaStore `RELATIVE_PATH`. You choose kinds and folders (with or without subfolders) in Settings.

## Text (`indexing/text`)

- Reads up to 25 MiB and 20,000 chunks per file (`TextExtractor`). Encoding is detected in `TextDecoder`.
- `TextChunker` (`ChunkerConfig`): target 800 chars, max 1000, min 200, overlap 120. Splits at blank lines, then lines, then sentences, then spaces, and cuts hard only for unbroken runs.
- `MarkdownChunker` follows headings and keeps the heading as the chunk title; it falls back to plain chunking.
- HTML to text with jsoup; XML keeps text nodes only; CSV/TSV rows are grouped; subtitles keep timing (`startMs/endMs`).

## Images (`ImageExtractor`)

One `IMAGE` segment (embedding at max side 1024) and one `OCR_TEXT` segment (OCR at max side 1600, text capped at 6000 chars for storage and keyword search; only the first 1500 chars are embedded, and a failed text embedding keeps the image vector instead of failing the file). EXIF date and location are stored on the item.

**Places.** The EXIF position is turned into searchable names by `PlaceFinder` (`indexing/places`, data `assets/places.bin`, regenerate with `scripts/make-places.py`): the nearest city within 100 km, plus the largest city of 500k+ people within 50 km, each with city and country in English and Korean plus country aliases and regions (e.g. `Dubai 두바이 United Arab Emirates 아랍에미리트 연합 Asia 아시아 UAE Emirates Middle East 중동`; abbreviations, continents and regions come from `ABBREVIATIONS`, `CONTINENTS`, `REGIONS` in `scripts/make-places.py`; ISO codes are avoided because "IN" or "ARE" are ordinary words), stored in `items.place` (`""` = looked up, nothing near). `IndexRunner.sync()` fills `place` for older rows that have a position but no name (`backfillPlaces`, no model needed); when the places data changes, an `AppUpgrade` step calls `ItemDao.resetPlaces()` so every row is looked up again. Videos are not geotagged yet.

## Text length limit

The embedding model accepts 1024 tokens and LiteRT-LM exposes no tokenizer, so length is only known when a call fails ("input sequence length (N) exceeds maximum supported signature length(1024)"). `EmbeddingGemma2Embedder.embedDocument/embedQuery` therefore retry with 60% of the text up to 5 times (`retryShorter`), for every caller (text, PDF, Office, OCR, transcripts, queries). Titles are capped at 200 chars and queries at 1000. Dense Korean or CJK text, long OCR output and tables are the usual causes.

## Failed files

An exception during extraction marks the item `FAILED` with the message in `items.error`. Every run (`sync`) puts failed items back in the queue; the Index screen lists them behind the "Failed" chip with a Retry button. Skipped items (`SKIPPED`) are unsupported or too large files and are not retried.

## Low-power mode

A manual-run option (`indexLowPower`): `ModelResidency.embedder(lowPower = true)` loads the embedder on 2 CPU threads (no GPU; resident under its own key so switching reloads it), sherpa-onnx uses 1 thread, `IndexingWorker` waits 300 ms between files and stops on a thermal status of MODERATE or higher. Automatic runs ignore it.

## PDF (`PdfExtractor`, `PdfText`)

Up to 500 pages. Text comes from pdfbox; a page with fewer than 15 meaningful characters (`PdfText.MIN_CHARS`, punctuation excluded) is treated as scanned and goes through OCR. Each page is rendered (width 1200, max height 2400, embedded at side 1024) as a `PAGE_IMAGE` and its text is chunked as `TEXT_CHUNK` with the page number. Thumbnails are 360 px (`ThumbStore`).

## Office (`OfficeExtractor`, `OfficeParsers`, `TextCard`)

Phones cannot render Office files, so text is extracted (up to 300 chunks per slide/sheet/section) and a generated **text card** image is embedded as `CARD_IMAGE`. Spreadsheets become sheet text with rows kept together.

## Audio (`indexing/audio`)

Decoded to 16 kHz mono PCM (max 4 hours, `AudioDecoder`). Two stages with separate checkpoints:

- **Stage A, transcript:** Silero VAD finds speech spans, SenseVoice transcribes them into `TRANSCRIPT` segments with times.
- **Stage B, audio clips:** only with the 740M model. Windows of about 30 s (`TARGET_MS`) with 5 s overlap, cut at silence within 6 s (`SNAP_MS`), never over 36 s (`MAX_MS`, the model takes about 40 s); a tail shorter than 4 s is merged. Windows that are pure silence (RMS below 0.002) are skipped but progress still advances. Each window is a WAV `AUDIO_CLIP`.

Without an ASR model only stage B runs; without the 740M model only stage A runs; with neither the file is `SKIPPED`.

## Video (`indexing/video`)

Clips are planned from scene changes (threshold 0.10 on a 16×16 grid) with length 4 to 20 s and 20% overlap. Each clip embeds up to 8 frames (side 448) as one `VIDEO_CLIP` vector. If the video has speech, stage "audio" (`AUDIO_STAGE`) transcribes it; transcript segments are about 220 chars or 10 s. Precision is a Settings option and part of `pipelineKey`. Moments shorter than a few seconds can be missed.

## Checkpoints and re-processing

- `items.progress` records where to resume (segment ordinal, or audio stage offsets such as `STAGE_B = 10^12`).
- Commits are per file; killing the app loses at most the current batch.
- `pipelineKey` (`PipelineKeys.kt`) mixes model id, chunker version, video precision, etc. Changing it reprocesses only that kind.

## Sync (`IndexRunner.sync`)

Deleted files are pruned, moved files updated in place, changed files (size or modified time) reprocessed. An empty listing is treated as an error condition and never wipes the index. Sync does not need any model.
