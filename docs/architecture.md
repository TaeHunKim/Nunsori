# Architecture

Code package and Gradle `namespace` `com.lsa.app` (Kotlin, Jetpack Compose); the installed app id (`applicationId`) is `io.github.taehunkim.nunsori`. Source root: `app/src/main/java/com/lsa/app`.

## 1. Goals and principles

- **On-device only.** Embedding, OCR, speech recognition and chat run on the phone. Nunsori has no network permission ([privacy](privacy.md)).
- **Models live in the companion app OndolSaem.** It downloads them, keeps them in memory and serves them over a bound service (SDK `com.github.TaeHunKim:ondolsaem` on JitPack); `ml/ModelHost` is the only place that talks to it ([models](models.md)).
- **One data model for every file type.** A file is an `item`; what is searched is a `segment`. New file types only add extractors and segment types.
- **Resumable.** Work is committed per file and checkpointed per segment batch, so a killed process resumes where it stopped.

## 2. System overview

```mermaid
flowchart TD
    UI[Compose UI: Index / Search / Settings] --> VM[ViewModels]
    VM --> CTRL[IndexingController]
    VM --> SE[SearchEngine]
    SCHED[AutoIndexScheduler] --> WM[WorkManager]
    CTRL --> WM
    WM --> W[IndexingWorker]
    W --> RUN[IndexRunner: sync + process]
    RUN --> SRC[CompositeSource: MediaStore + FileSource]
    RUN --> SESS[ItemSession]
    SESS --> X[Extractors: image, text, pdf, office, audio, video]
    X --> EMB[Embedder: EmbeddingGemma 2 in OndolSaem]
    X --> OCR[OcrEngine: ML Kit in OndolSaem]
    X --> ASR[SpeechTools: VAD + SenseVoice in OndolSaem]
    X --> DB[(Room: items, segments)]
    X --> TH[ThumbStore: thumbnails]
    SE --> DB
    SE --> EMB
    CHAT[ChatController] --> SE
    CHAT --> LLM[ChatModel: LLM in OndolSaem]
    CHAT --> DB
    RES[ModelHost] -.leases.-> EMB
    RES -.leases.-> OCR
    RES -.leases.-> ASR
    RES -.leases.-> LLM
```

## 3. Package map

| Package | Responsibility |
|---|---|
| `data/db` | Room entities, `ItemDao`, `Migrations.kt` |
| `data/media` | `MediaStoreRepository` (images, audio, video), `FileSource` (text and documents, needs All files access), `CompositeSource` |
| `data/prefs` | `SettingsStore` (DataStore): kinds, folders, mirrored model ids, auto-index mode |
| `indexing` | `IndexRunner`, `ItemSession`, `IndexingController`, `IndexingWorker`, `AutoIndexScheduler`, `PipelineKeys`, `ImageExtractor` |
| `indexing/text` | `TextExtractor`, `TextChunker`, `MarkdownChunker`, `TextFormats`, `TextDecoder`, `TextRouting` |
| `indexing/docs` | `PdfExtractor`, `PdfText`, `OfficeExtractor`, `OfficeParsers`, `TextCard`, `ThumbStore`, `ZipSource` |
| `indexing/places` | `PlaceFinder`: offline GPS to place name (GeoNames cities, English and Korean) |
| `indexing/audio` | `AudioExtractor`, `AudioDecoder`, `Pcm` (window planning), `SpeechTools` |
| `indexing/video` | `VideoExtractor`, `VideoClips` |
| `indexing/faces` | `FaceIndexer` (face pass after indexing, crops, off/on), `FaceClusterer` (grouping, merging) |
| `chat/actions` | `ActionManager` (proposals, approve/dismiss, intents), `ActionPayloads` (validation), `ActionTools` (`add_calendar_event`, `add_contact`, `move_files`, `delete_files`), `FileOps` (move, trash, delete + index update) |
| `chat/schedule` | `TaskManager` (propose, confirm, run, file matching), `Schedule` (next run), `TaskAlarms` + receivers + `TaskRunWorker` |
| `ml`, `ml/embedding`, `ml/ocr`, `ml/llm` | `ModelHost` (OndolSaem client: leases, remote engines, model-id mirroring), interfaces `Embedder`, `OcrEngine`, `ChatModel` (`SpeechTools` is in `indexing/audio`) |
| `chat` | `ChatController` (turns, context, attachments), `ChatTools` (`search_index`, `read_file`), `PeopleDirectory` (names for the `person` arguments), `ContextBudget`: [chat.md](chat.md) |
| `search` | `SearchEngine`, `Fusion`, `KeywordMatcher` |
| `ui` | `AppScaffold` (shows `OnboardingScreen` until `onboarding_done`; drawer menu on the lists, an up arrow to the list on detail routes such as `chat/{id}` and `people/{id}`), screens (`index`, `search`, `people`, `settings`, `onboarding`), `theme`; `settings/SettingsSections.kt` holds the kinds, folders and embedding-model sections shared by Settings and the wizard |

## 4. Data model

Defined in `data/db/Entities.kt`.

**`items`** (one row per file): `sourceKey` (unique id of the source file), `kind` (`IMAGE, TEXT, DOC, AUDIO, VIDEO`), `uri`, `folderKey`
(same form as MediaStore `RELATIVE_PATH`), `displayName`, `mimeType`, `sizeBytes`, `modifiedAt`, `title`, `durationMs`, `pageCount`,
`width/height`, `dateTaken`, `latitude/longitude`, `place` (searchable place names, v3), `status` (`IN_PROGRESS, DONE, FAILED, SKIPPED`), `error`, `embeddingModelId`,
`pipelineKey`, `progress`, `indexedAt`.

**`segments`** (what is searched): `itemId`, `type`, `ordinal`, `startMs/endMs` (audio, video), `page`, `text`, `embedding`, `thumbPath`.

**`indexed_folders`**: `relativePath`, `enabled`, `recursive`.

**`people`** (`name` or null, `coverFaceId`, timestamps) and **`faces`** (`itemId` with cascade delete, box in 0..1, `score`, int8 128-d `vector`, `personId` set to null when its person is deleted, `blockedPersonId` = the group the user took it out of, `thumbPath` of the crop), added in v8 together with `items.faceKey` (face model key a photo was scanned with; null = not scanned). See [indexing.md](indexing.md#faces).

**`chat_sessions`** (`title`, `enabledTools`, `autoSearch`, timestamps) and **`chat_messages`** (`sessionId` with cascade delete, `role` USER/ASSISTANT/TOOL, `text`, `toolName`, `toolArgs`, `attachments`, `createdAt`), added in v4.

| `SegmentType` | Produced by | Signal group |
|---|---|---|
| `IMAGE` | images | VISUAL |
| `OCR_TEXT` | images (ML Kit) | TEXT |
| `TEXT_CHUNK` | text files, document text | TEXT |
| `PAGE_IMAGE` | PDF pages | VISUAL |
| `CARD_IMAGE` | Office text cards | VISUAL |
| `TRANSCRIPT` | audio and video speech | TEXT |
| `AUDIO_CLIP` | audio windows (740M only) | AUDIO |
| `VIDEO_CLIP` | video clips | VISUAL |

**Vector format.** int8, stored as `[scale: float32][int8 × dim]` (`Quant`), 768 dims. Dot products are done in memory over all vectors.

**Migrations.** The schema is at **v8** (`AppDatabase.VERSION`; v3 added `items.place`, v4 the chat tables, v5 pinned chats, v6 scheduled tasks, v7 action cards, v8 faces and people). `Migrations.kt` holds one `Migration` per step and the ordered `ALL_MIGRATIONS` list that `LsaApplication` registers. There is no destructive fallback on upgrade; only a downgrade (older build over a newer one) rebuilds the index, because it is derived data. Derived data that can be recomputed without a schema change is handled by `pipelineKey` instead. Policy: [development.md](development.md#schema-and-version-changes).

**App upgrades.** `AppUpgrade.run` (called from `LsaApplication`) compares the stored `last_version_code` with `BuildConfig.VERSION_CODE` and runs per-release steps for settings or file-layout changes. Auto Backup is disabled (`allowBackup="false"`): the index is derived and a restored database would not match missing thumbnails and models.

## 5. Indexing flow

Details in [indexing.md](indexing.md).

1. `IndexRunner.sync()` needs no model. It lists files from the sources, deletes rows whose file is gone, updates moved files, and computes the to-do set (new, changed, or `pipelineKey` differs). An empty listing never deletes anything (safety against revoked permission or unmounted storage).
2. `IndexRunner.process()` handles the to-do set one file at a time. `ItemSession` picks the extractor by kind; the extractor streams segments to a sink that commits them with the new `progress` value.
3. `pipelineKey` (`PipelineKeys.kt`) encodes everything that changes a kind's output: model id, chunker version, video precision, and so on. When it changes, only that kind is reprocessed.
4. Extractors throw `SkipItem` for unsupported, protected or too-large files; the item becomes `SKIPPED` with a reason.

## 6. Background execution

| Mode (`AutoIndexMode`) | Mechanism |
|---|---|
| MANUAL | User presses Start; work runs as foreground work with a notification |
| IMMEDIATE | WorkManager content-URI trigger on MediaStore (works while the app is dead), 15 s update delay, 2 min max delay |
| SCHEDULED | Periodic work at a chosen time, optional "charging only" |
| IDLE | Periodic work with charging + device idle constraints |

Automatic runs cannot start foreground services from the background on recent Android, so `setForeground` is attempted inside try/catch. `IndexingController` serialises runs (`runLock`), and a thermal gate pauses automatic runs when the device is hot. The mode is chosen on first launch.

## 7. Model lifetime

Models are loaded and unloaded by OndolSaem, with a residency policy per category set there (keep loaded, unload right after use, or after 1 to 15 minutes). `ModelHost.embedder()/ocr()/speech()/chat()` return a `Lease` that also holds a lease in OndolSaem, so a model stays loaded while Nunsori uses it: an indexing pass holds the embedder and OCR leases for the whole pass, and a chat turn holds the embedder (when search or auto search is on) and the chat model from start to finish. When OndolSaem is not installed, refuses the connection or has no model for a category, the lease is null and the screens show a hint (`HostState`). The optional resource indicator (`ui/common/ResourceIndicator.kt`) shows whether OndolSaem is connected plus this process's memory and CPU; the models' memory is on OndolSaem's dashboard. Log tag `LSA.ModelHost`.

## 8. Search (summary)

Segments are scored per signal group (VISUAL, TEXT, AUDIO) with a median-spread gate and relative scaling, combined with SQL `LIKE` keyword matches, grouped per item, and shown with the best location (page or time). See [search.md](search.md).

## 9. Design decisions

| Decision | Why | Alternative rejected |
|---|---|---|
| WorkManager content-URI trigger for IMMEDIATE | Works when the process is dead | `ContentObserver` (only while the app lives) |
| Relative score per signal group | Cosine ranges differ by space (unrelated OCR text still scores about 0.6) | Reciprocal rank fusion: ranks ignore how good the best hit is, so nonsense queries still return results |
| int8 vectors, full in-memory scan | Simple, fast enough to about 300k segments | ANN index (complexity, not needed yet) |
| SQL `LIKE` keywords | No extra index to maintain | FTS5 trigram (revisit if slow) |
| All files access for text and documents | MediaStore does not expose documents reliably | Storage Access Framework per folder |
| Office files become text + generated card image | Phones cannot render Office documents | Server-side rendering (not on-device) |
| Audio in two stages (transcript, then clips) | Speech recognition is cheap, audio embedding needs the 740M model; each stage checkpoints separately | One combined pass |
| Video as scene/length clips, up to 8 frames each | Several frames in one request give one vector (measured) | Per-frame vectors (storage) |

## 10. Limits and extension points

- About 300k segments is the target for the in-memory scan; beyond that add ANN or smaller (256-d) vectors.
- Keyword search is `LIKE`; move to FTS5 trigram if it slows down.
- New embedding or OCR models: see [models.md](models.md#adding-a-model).
- Known limits and plans: [roadmap.md](roadmap.md).

## Scheduled tasks

`scheduled_tasks` (schema v6, cascade with `chat_sessions`) holds the tasks; `chat_messages.taskId` links a confirmation card or a run's messages to a task. Time tasks: `TaskAlarms` (AlarmManager exact alarm per task) → `TaskAlarmReceiver` → unique `TaskRunWorker` per task (expedited, foreground `dataSync`) → `TaskManager.run` → `ChatController.runScheduled`. File tasks: `IndexingWorker` calls `TaskManager.onIndexed(passStart)` after a pass; matches are queued on the task and a `TaskRunWorker` is enqueued. `ChatController` serializes user turns and scheduled runs with one mutex. Notifications deep-link to a chat with the `sessionId` extra handled in `MainActivity`/`AppScaffold`. Details: [chat.md](chat.md#scheduled-tasks).

`pending_actions` (schema v7, cascade with `chat_sessions`) holds action-card proposals (`kind` CALENDAR_EVENT, CONTACT, MOVE_FILES or DELETE_FILES, `status` PROPOSED, DONE, DISMISSED or FAILED, `payload` JSON); `chat_messages.actionId` links the tool message to its card. Approval happens in `ChatViewModel.approveAction`, which starts the intent; nothing is executed by the model itself. Details: [chat.md](chat.md#action-cards-calendar-and-contacts).
