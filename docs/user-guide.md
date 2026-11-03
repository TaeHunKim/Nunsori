# User guide

![Index](images/index.png) ![Search](images/search.png) ![Settings](images/settings.png)

## First run

A three-step wizard appears on the first launch. Everything in it can be changed later in Settings.

![Step 1](images/onboarding-1.png) ![Step 2](images/onboarding-2.png) ![Step 3](images/onboarding-3.png)

1. **What to index:** the kinds of files and the folders (the permissions they need are requested here).
2. **AI models:** shows whether the companion app OndolSaem is installed and which model it uses for search, text in images, speech and chat, with buttons to install or open it. Models are downloaded and chosen in OndolSaem (embedding 440M recommended, 740M also understands audio). You can skip this; Search, Index and Chat tell you when a model is missing.
3. **Automatic indexing:** immediately, at a set time, when idle and charging, or manually.

## Index

Shows progress ("n / total files"), the file being processed, **Start / Pause**, and a thumbnail grid of indexed files. Indexing continues in the background with a notification, and resumes after the app is killed.

- **Failed files:** a "Failed (n)" chip filters the grid to files that could not be indexed (tap one to see the error); **Retry failed** runs only those files, without resuming the rest of a paused run. Failed files are also retried by every normal run.

## Search

Type a description ("launch schedule and design approval"), exact words, a place ("Dubai", "두바이", "중동") or a file name. Each result card shows a thumbnail, the file name with the best location (`p.3` for a page, `mm:ss` for audio/video), a snippet, and badges for what matched: Image, Text, Audio, Keyword. Tap a result to open it in another app; share one or several from the result actions. Exact keyword matches come first. Ranking details: [search.md](search.md).

![Filters](images/search-filters.png)

**Filters:** the chips under the search box limit results to Documents, Images, Audio and/or Video, and to a date range (any date, today, last 7/30 days, this year, or pick dates; photos use the date taken). With a filter but no query you get a list of that kind of file, newest first. While a model switch is re-indexing, a banner says how many files are not searchable yet.

Photos with GPS data are found by place name. Results that match both the meaning and the place rank higher.

Tips: describe content in natural language for photos and scenes; use distinctive words for names, codes and numbers; Korean and English both work.

Automatic indexing shows when it last ran and what it did (or why it did nothing). Immediate mode also checks hourly, in case the system delayed the instant trigger; for dependable timing exclude the app from battery optimization (the same card appears under the options).

## People

Faces are found when photos are indexed (Settings › Index › *Group photos by person*, on by default; needs OndolSaem 0.3.0 or newer with its *Faces* model downloaded). The **People** menu lists the groups: one per person, with a face crop and the number of photos. Groups with a single photo are hidden until you switch *Show people with one photo* on.

- Tap a group to see its photos (tap a photo to open it), give it a name and save. Giving a name that another group already has offers to **merge** the two; *Merge* also lets you pick the other group from a list.
- Tap a face in the row of faces to use it as the cover or to say **Not this person**: the face leaves the group and is never put back into it.
- In the chat, use the names: "show me photos of Mina from last summer", "move all photos of Mina to Pictures/Mina" (a card lists the files; nothing moves before you approve it).
- Turning face grouping off deletes every face, group and name. Turning it on again scans only the faces of the photos already indexed; the search index is kept.

## Chat

![Chat](images/chat.png)

Ask questions about your files. The model (Gemma 4 by default, downloaded in Settings) can search your index and read files by itself; each use appears as a small card you can open. Chats are kept in a list: pin (pinned chats stay on top), rename or delete them from the menu on each row; answers are shown with Markdown formatting (lists, code, tables, links); the buttons under a message copy it (answers can also be shared) as Markdown text; files an answer mentions appear as thumbnails under it (tap to open the file, long-press for details); the context sent to the model is limited, so very long chats forget their oldest messages (a note says so).

- **Tools:** the *Tools* button next to the message box opens a checklist (*Search files*, *Read files*, *Schedule*, *Calendar & contacts*, *Move & delete files*, *Auto search*) for that chat.
- **Scheduled tasks:** ask in plain words, for example "every day at 9 summarize yesterday's photos", "every Monday 8:30 …", "in 30 minutes …" or "whenever a new document about the budget is added, summarize it". The assistant proposes a task card; press **Confirm** to start it. When it runs, the result is added to that chat and you get a notification; tapping it opens the chat. The clock button above the conversation lists the chat's tasks (pause, resume, delete). To change one, just ask ("move it to 6:30 pm", "make it weekly"); the change applies at once. Chats with tasks show a clock icon in the chat list. Tasks end when you delete the chat. **Tasks about new files run after indexing finishes, not the moment a file appears**, and they only post when a new file really matches your condition (the assistant checks each candidate first; a task needs a condition such as "receipt"; vague conditions like "anything with a schedule" work best with *Settings › Background tasks › Let the assistant judge new files*, which is on by default) (with manual indexing, when you start it). Use *Settings › Background tasks* (also an optional last step of the first-run wizard) to allow notifications, exact alarms and to exclude the app from battery optimization; on Samsung phones also remove it from *Sleeping apps* / *Deep sleeping apps*. Without these, tasks run late or not at all.
- **Attach:** the paperclip adds a photo (Gemma 4 can look at it). In Search, long-press results and choose **Ask in chat** to attach those files to a new or existing chat; they are sent with your next message.
- **Stop** ends an answer early. The first answer after starting the app is slower because the model has to load.
- Everything runs on your phone; chats are stored in the app's private storage and deleted with the chat.

See [chat.md](chat.md) for how the context is built.

## Settings

Five tabs: **Index** (what to index, folders, automatic indexing), **Models** (connection to OndolSaem and the model of each category), **Chat** (your instructions, background tasks), **App** (storage, resource indicator, language) and **About** (app name, version, GitHub link, open-source licenses).

- **What to index:** Images, Text files, Documents, Audio, Video.
- **Folders:** quick-add chips for Camera, Screenshots and Downloads, or pick any folder; choose whether to include subfolders. (Without "All files access" only photos, audio and video in a folder are indexed.)
- **AI models (OndolSaem):** the models run in the companion app OndolSaem: choosing and downloading them, the memory policy, the Hugging Face token and the chat context size are all there (**Open OndolSaem**). Nunsori shows the connection and the model of each category; **Check again** after changing something there. When the search model changes in OndolSaem, the files indexed with the old model are marked for re-indexing (Index shows how many; automatic indexing picks them up).
- **Chat instructions:** your own text added to the chat's system prompt for every conversation (up to 2,000 characters).
- **Resource indicator:** a top-bar readout of the connection to OndolSaem, this app's memory and CPU use. The models' memory is on OndolSaem's dashboard.
- **Calendar and contacts:** with *Calendar & contacts* checked, ask the assistant to put an event or a person into your calendar or contacts (for example after it read an invitation photo). It shows a card with the details; **Open in Calendar/Contacts** opens the system app with the fields filled in and you press Save there. Nothing is added without you, and the app needs no calendar or contacts permission. Chats created before this version have the chip off; switch it on above the conversation.
- **Moving and deleting files:** with *Move & delete files* checked, ask for example "move last year's receipts to Documents/Receipts" or "delete the blurry photos from yesterday". The assistant finds the files, then shows a card with their names; nothing happens until you press the button. Deleting goes to the trash (gallery trash for photos and videos, the hidden `.LSA_Trash` folder for documents); *Delete permanently* is a separate button. Needs "All files access". In scheduled tasks the assistant can only propose; the cards wait in the chat until you approve.
- **Automatic indexing:** Manual; Immediately (new files are picked up in the background); Scheduled (a time, optionally only while charging); When idle (charging and idle).
- **Model memory:** keep loaded, unload after 2 minutes idle (default), or unload immediately. Lower memory use costs a reload on the next use.
- Dark mode follows the system.

## Why the permissions

See [privacy.md](privacy.md#permissions-androidmanifestxml).

## Troubleshooting

| Symptom | Check |
|---|---|
| Nothing found | Is the file `DONE` on the Index screen? Is the folder added and the kind enabled? Text/documents need All files access. |
| Documents or text never appear | Grant "All files access" in system settings. |
| "No search model available" | Install OndolSaem, download a model there, then **Check again** in Settings › Models. |
| "OndolSaem is too old" / "no longer supports this version" | Update the app named in the message (Settings › Models has a button). |
| "OndolSaem refused the connection" | Nunsori and OndolSaem must be signed with the same key (or an allowed one), e.g. both debug builds from the same machine. |
| Slow indexing | Emulators use CPU. On phones, a hot device pauses automatic runs. |
| Audio never matches by sound | Audio embedding needs the 740M model; transcripts need the speech model (both chosen in OndolSaem). |
| Old .doc/.ppt/.xls or hwp | Unsupported; convert to the modern format or PDF. |
