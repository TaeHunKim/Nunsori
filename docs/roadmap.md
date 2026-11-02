# Roadmap and known limits

## Next

- Done in 0.6.1: chat usability: composer follows the keyboard without a gap, copy/share of messages as Markdown, thumbnails of mentioned files under answers (tap opens the file), own status-bar icons for indexing and task notifications; Settings is split into tabs; a tools checklist menu replaces the chips (and adds the missing move/delete switch); keyboard no longer pans the whole screen (`adjustResize`).
- Done in 0.6.0: action cards for the chat: calendar-event and contact proposals (system app opens prefilled) and file move / trash / delete (approval first, trash by default, needs All files access). Scheduled runs can propose actions too (cards wait in the chat, the notification counts them). Later ideas: restoring from `.LSA_Trash` in the app, opt-in auto-approval per task.
- Done in 0.5.0: scheduled chat tasks (time-based with exact alarms, and after-indexing file triggers), confirmation cards, notifications that open the chat, background-access checks (notifications, exact alarms, battery optimization).
- Done in 0.4.0: failed-file list and retry, embedding-length guard, pinned chats, Markdown, chat instructions, low-power indexing, folder presets, resource indicator.

### Planned, in priority order

1. **People in photos (face grouping).** Detect faces when indexing images, compute a face embedding per face, and group the faces of the same person across photos (clustering on-device). Features on top: a People view, naming a person, searching and filtering by person ("photos with Mina"), and chat access to it. Models that run on a phone exist: face detection with ML Kit Face Detection (already the Google on-device family the app uses for OCR) or YuNet (small ONNX), and a compact recognition model such as SFace or MobileFaceNet (about 5 to 40 MB, 128-d to 512-d vectors, fast enough for an indexing pass). Open points to settle before building: **licenses** of the exact weights (the OpenCV Zoo models YuNet and SFace are reported as MIT and Apache-2.0, to be verified; popular InsightFace weights are research or non-commercial only and must not be used), a **runtime** (the app has LiteRT-LM and ML Kit but no general ONNX or TFLite interpreter, so either convert to LiteRT or add ONNX Runtime), the vector store (a face is not a segment of an item, so it needs its own table and a schema migration), the clustering quality on side views, masks and children, and **privacy**: face vectors are biometric data. They would stay on the device, be switchable off (off by default or an explicit opt-in step), be deletable per person and all at once, and be described in [privacy.md](privacy.md).
2. **Direct integration with other apps' content for search.** Index more than files: text messages (SMS/MMS), notes and similar sources, so one search and the chat cover them. Needs per-source feasibility work: SMS/MMS means the sensitive `READ_SMS` permission and a content provider reader (fine for a sideloaded app, restricted on Google Play); notes apps mostly have no public provider (Samsung Notes, Google Keep), so the realistic routes are exports or shared text into the app, notification capture, or plain-file notes (Markdown/text folders are already indexed). Each source also needs its own opt-in, a source-aware result card (open the message or note in its app) and a privacy review.
3. **Web tools for chat (lowest priority).** Web search and fetching a page for the chat model. They go against the app's on-device principle (queries and page requests would leave the phone), so they are pushed behind the work above. If built: an opt-in setting that is off by default, a clear disclosure of what leaves the device, and a search API choice; the app only uses the network to download models today.

### Other ideas

- **Sub-agent for images:** the chat model cannot look at an image that a tool returns (tool results are text only), so a search hit that is a photo stays a file name and OCR text. Idea: a tool such as `describe_image(item_id, question?)` that starts a separate, short model call (a sub-agent with its own small prompt and no tools) that does see the image, and hands back a summary or description (optionally answering a specific question about it) as the tool result. The same pattern could serve other jobs that would clutter the main context (summarize a long document, check one file against a condition). Open points: which model does it (the chat model when it has vision, otherwise a vision-capable one) and the memory cost of loading it next to the embedder (see `ml/ModelResidency`), latency on a phone (one call per image, so a cap per turn), caching descriptions per file so they are not recomputed (possibly reusing them as search text, which touches the indexing pipeline and `PipelineKeys`), a prompt that keeps descriptions short for the 4K window, and whether scheduled runs may use it (battery).
- **Agent skills for the chat:** reusable instruction bundles (a name, a one-line description and a Markdown body, in the spirit of agent skills) that the user can add, edit and switch on per chat or for scheduled tasks. Sketch: the system prompt lists only each active skill's name and description; a small tool (for example `use_skill`) returns the full body when the model decides it applies, so the context cost stays low on the 4K default window. Open points: where skills live (app storage, imported from a folder or a shared file), a few built-in examples (receipt summary, weekly digest), how a skill may refer to the existing tools, whether scheduled runs can use them, and a size limit per skill. Skills stay plain text; they never run code.
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

- Automatic indexing is a WorkManager job: the system may delay it (Doze, standby buckets, Samsung *Sleeping apps*) unless the app is excluded from battery optimization; Immediate mode has an hourly safety net.
- Scheduled tasks run one at a time and need the chat model, so a run can be late when another answer is being generated or the phone is in Doze/an OEM sleep list (Samsung *Sleeping apps*); file-triggered tasks run after indexing, never at file creation; a new file counts only if it clearly stands out from the typical similarity of the index (or contains all the query words) and the chat model confirms it; photos without text are not checked by the model (they need their own match), and the similarity thresholds still need tuning with real files. Only files indexed after confirming count.
