# Chat

An on-device assistant that answers questions from your files. Code: `chat/`, `ml/llm/`, `ui/chat/`.

![Chat](images/chat.png)

## Sessions

Every conversation is a session (`chat_sessions`, `chat_messages`; schema v6 (v4 added the tables, v5 `pinned`, v6 scheduled tasks)). Sessions are listed pinned first, then newest first (`chat_sessions.pinned`, schema v5); they can be renamed and deleted (the messages and picked photos go with them). The title is the first 40 characters of the first question until you rename it. Only one answer is generated at a time (one model in memory).

## What the model sees

For each question `ChatController.runTurn` builds the context:

1. **System prompt**: role, today's date with weekday and time zone, tool rules, "name the files and pages you used", "reply in the user's language", then the user's own instructions from Settings (`chatCustomPrompt`, up to 2,000 characters, labelled "the user's own instructions"). They go into the system prompt, not the question: the model follows them more reliably, they are not repeated in every turn and they do not appear in the transcript. Their size is deducted from the history budget and a hash of them is part of the conversation key, so editing them starts the model's state afresh. The date is in the key too, so a chat left open past midnight does not keep yesterday's "today". Every user turn is prefixed with `[Current time: 2026-11-02 (Monday) 14:05 UTC+09:00, Asia/Seoul]` (not stored in the transcript), because the clock changes faster than the cached system prompt; the search tool's `date_from`/`date_to` are interpreted in the device time zone.
2. **History**: the stored user and assistant messages before this question, newest first up to **60 %** of the context window minus the question and the system prompt (`ContextBudget`, tokens estimated as characters / 3). It always starts with a user turn. When turns are left out the chat says so. Tool calls of earlier turns are not replayed.
3. **The question**, preceded by attachments: for an attached indexed file the first 3,000 characters of its indexed text and its `item_id`; with **Auto search** on, the top 3 search results (800 characters each).
4. **Photos** picked with the attach button are passed as images to vision models (Gemma 4) and ignored by text-only models.

The context window (2K, 4K default, 8K) is set in Settings. LiteRT-LM keeps the model's own state between turns of a session; it starts over from the stored history when the tools change, an answer was cancelled, or the state reaches 70 % of the window.

## Rendering

Messages are shown as Markdown (`ui/common/Markdown.kt`): CommonMark with GFM tables, parsed by the commonmark library that indexing already uses, and drawn with Compose: headings, emphasis, inline code, code blocks, lists, quotes, links and simple tables. Your own messages use the same renderer. Under each message a **Copy** button (answers also **Share**) puts the Markdown source on the clipboard / in the share sheet, not the rendered text.

**File previews:** under an answer (also a scheduled run's), thumbnails of the files from the search/read calls of that turn whose file name the answer mentions (`chat/ChatPreviews.kt`; if it names none, up to 4 images of the results). Thumbnails are the ones made at indexing time, so documents, videos and audio show their page/frame/icon too. Tap opens the file in another app, long-press shows the details sheet.

## Tools

The model calls tools through LiteRT-LM's tool calling (`automaticToolCalling`). Each session can switch them on and off with the checklist behind the *Tools* button next to the message box (it opens upwards); changing them starts a fresh model state from the stored history. At most 8 tool calls per question. `search_index` takes an optional query: with only `kinds` and/or `date_from`/`date_to` it lists the matching files newest first (up to 100, paged with `offset`), so "everything I shot yesterday" works; with neither a query nor a filter it returns an error. Reading many files is bounded by the context window (2K/4K/8K tokens), not by the tool.

| Tool | Arguments | Result |
|---|---|---|
| `search_index` | `query` (required), `kinds` (`document,image,audio,video`), `date_from`, `date_to` (`YYYY-MM-DD`), `limit` (1 to 10, default 5) | JSON list of `item_id, name, kind, page or time, date, place, snippet`. Same engine as the Search screen: [search.md](search.md) |
| `read_file` | `item_id` (required), `page`, `from_sec`, `to_sec`, `offset`, `max_chars` (up to 4000, default 2000) | JSON with name, kind, date, place, pages or duration, `total_chars`, `next_offset` and the `text` slice |

`read_file` reads the **indexed text**, not the original file: document and text chunks (with `[p.N]` markers), transcripts of audio and video (with `[mm:ss]` markers, selectable by time) and OCR text of images. Files without indexed text say so.

Calendar and contact tools are described in [Action cards](#action-cards-calendar-and-contacts). Tool calls appear in the conversation as small cards ("Searched files for … · 3 results", "Read plan.docx"); tap a card to see the found files and open them.

**Auto search** is for models that handle tool calling poorly: before each question the app searches and puts the best excerpts in the context. It can be combined with the tools.

## Action cards (calendar and contacts)

The *Calendar & contacts* checkbox switches two tools (file tools are below): `add_calendar_event` (`title`, `start` as `YYYY-MM-DD HH:mm` local time or `YYYY-MM-DD` for all day, optional `end`, `all_day`, `location`, `description`) and `add_contact` (`name`, `phone`, `email`, `organization`, `note`). Like `schedule_task` they only **propose**: the app validates the fields (`ActionPayloads`), stores a proposal (`pending_actions`, status `PROPOSED`) and shows an **action card** in the chat with what would be added. The model supplies fields, never URLs or intents. **Open in Calendar / Contacts** hands an `ACTION_INSERT` intent with the fields filled in to the system app (no calendar or contacts permission is needed; you press Save there); the card then shows as done. **Dismiss** drops it. A handled card cannot be approved again. If no app can take the intent the card says so.

### File actions

The *Move & delete files* checkbox switches `move_files` (`item_ids`, `destination`) and `delete_files` (`item_ids`, optional `permanent`). The model first finds files with `search_index` and passes their `item_id`s (up to 50; unknown ids are refused), never paths. The card lists the file names and what will happen; nothing changes before you approve.

- **Delete** goes to the **trash** by default (button *Move to trash*). *Delete permanently* is a separate button on the card (the primary button only when the user asked for it explicitly). Photos, audio and video in the media store use the system trash (`IS_TRASHED`, restorable from the gallery, kept about 30 days); text and documents are moved to the hidden folder `.LSA_Trash` in the storage root (with `.nomedia`, skipped by indexing; restore by moving them back with a file manager).
- **Move** puts files into a folder relative to the phone's storage (`Pictures/Wedding`, created if missing; not absolute, hidden or inside `Android/`). A name clash gets `name (1).ext`. Media store files move by changing `RELATIVE_PATH` (their id and index data stay; the system only allows folders that fit the file type, otherwise that file is reported as failed); plain files are renamed and their index row follows without re-indexing. A file that ends up outside the indexed folders leaves the index.
- **Index:** moved or deleted files are updated in the index at once, so search does not show them. Every file is checked again at approval time (gone files are dropped and reported); partial failures are listed on the card.
- **Needs "All files access"** (Android 11+, the same permission documents need). Without it, approving shows a hint and a button to the setting and the card stays open.
- Not covered yet: restoring from `.LSA_Trash` inside the app, and moving files that live outside the primary storage.

The card is generic (`ActionManager`, `ActionKind`, `chat_messages.actionId`): propose, show a card, act only after approval.

## Scheduled tasks

Ask for something to happen later, for example "every day at 9 summarize yesterday's photos", "every Monday 8:30 list the new documents" or "when a photo of a receipt is added, read the total". The model calls `schedule_task` (with `list_scheduled_tasks`, `update_scheduled_task` and `cancel_scheduled_task`); the tool only **proposes**: a card in the chat shows what, when and under which condition, and nothing runs until you press **Confirm**. The *Schedule* chip switches the tools per chat; the clock button in the chat header lists the chat's tasks (pause, resume, delete, next and last run).

- **Changing a task:** ask the assistant ("move it to 18:30", "make it weekly on Mondays", "only images"). `update_scheduled_task` changes just the given fields of a task of the same chat and applies at once (the alarm is re-armed; a file task keeps seeing only files indexed after it was confirmed); the card in the chat shows the new schedule. Pause, resume and delete stay in the task list.
- **Lifetime:** a task belongs to the chat it was asked in (`scheduled_tasks.sessionId`, cascade delete). Chats with an active task show a clock icon in the list, next to (not instead of) the pin.
- **Time tasks:** once (date and time, or "in N minutes"), every N minutes (at least 5), daily, or weekly on chosen weekdays. `Schedule.nextRun` computes the next local time. `TaskAlarms` sets one exact alarm (`setExactAndAllowWhileIdle`; without the exact-alarm permission it falls back to an inexact one) and `TaskAlarmReceiver` queues `TaskRunWorker`. Boot, time change, time zone change and the exact-alarm permission change re-arm all alarms (`TaskBootReceiver`, also at app start). A run missed while the phone was off still happens once if it is less than 12 hours late, otherwise it is skipped.
- **File tasks:** after every indexing pass, `TaskManager.onIndexed` takes the files that finished in that pass (and after the task was confirmed), keeps the kinds the task names and decides **per file** whether it matches the task's query (`SearchEngine.matchItems`, see [search.md](search.md#matching-new-files-for-scheduled-tasks)). A file task must have a query (key words of what the file is about); only if the user explicitly wants every new file does the model set `any_file`. The query may list alternatives separated by `|` (the model writes the user's own words first, then 2 to 5 concrete words or synonyms such a file would contain, e.g. `schedule|wedding invitation|invitation`); any one can match. With the setting *Let the assistant judge new files* on (default; Settings › Background tasks) and at most 20 new files in the pass, every new file that has indexed text becomes a candidate even if the similarity match missed it, so abstract conditions like "anything with a schedule" still work (more chat-model use); files without text still need their own match. With the setting off, only matched files are candidates. Candidates are queued on the task (`pendingItemIds`). When the task runs, the chat model first **checks the candidates** against the condition (a short yes/no call with a text excerpt of each file, outside the chat); if none really matches, nothing is posted, no notification is sent and the queue is simply cleared. Files with no indexed text (for example photos without recognized text) cannot be checked by words and pass on their semantic match alone. One run per task per pass then handles all confirmed files (up to 10 are listed to the model, the rest are mentioned). **These run after indexing, not at the moment a file appears**; with manual auto-index mode that means when you start indexing. The confirmation card, the task list and Settings say so.
- **A run:** `ChatController.runScheduled` waits for any running answer (one lock for user and scheduled turns), then adds the instruction and the answer to the chat (both marked with a clock), using the chat's earlier messages as context and the search and read tools, plus the calendar/contact and file tools if the chat has those chips on (a task cannot schedule more tasks). **A run can only propose actions**: cards for events, contacts, moves or deletions appear in the chat and wait for you; the notification starts with `[N to approve]` when a run left cards. Nothing is carried out without a tap, because nobody is present and file changes are not undoable by the app. The embedder and the chat model are loaded for the run and released by the normal memory policy. The result is also shown as a notification (channel *Scheduled tasks*); tapping it opens that chat. Failures (no model, load error) are stored on the task and notified too. A run is skipped with a note when the battery is under 15 % and not charging; a one-time task retries in 30 minutes.
- **Limits:** 20 tasks in total, 5 per chat. The phone must allow background work: see *Background tasks* in the user guide (notifications, exact alarms, battery-optimization exemption).

## Ask in chat

In Search, long-press a result to select files, then **Ask in chat** and pick a session or a new one. The files arrive as attachments in the message box; nothing is sent until you press send.

## Models and limits

Models are chosen and downloaded in Settings ([models.md](models.md#chat-models)); the first answer after loading a model takes longer (about a minute for Gemma 4 E2B on the emulator CPU). The chat model and the embedding model are in memory together while you chat (the search tool needs the embedder), and both follow the model memory policy.

- Small models can pick the wrong tool or arguments; Gemma 4 E2B called `search_index` and `read_file` correctly in the emulator test.
- Answers are only as good as the index: files that are not indexed, or indexed without text, cannot be read.
- Web search and fetching web pages are not available yet ([roadmap.md](roadmap.md)).
