# User guide

![Index](images/index.png) ![Search](images/search.png) ![Settings](images/settings.png)

## Index

Shows progress ("n / total files"), the file being processed, **Start / Pause**, and a thumbnail grid of indexed files. Indexing continues in the background with a notification, and resumes after the app is killed.

## Search

Type a description ("launch schedule and design approval"), exact words, a place ("Dubai", "두바이", "중동") or a file name. Each result card shows a thumbnail, the file name with the best location (`p.3` for a page, `mm:ss` for audio/video), a snippet, and badges for what matched: Image, Text, Audio, Keyword. Tap a result to open it in another app; share one or several from the result actions. Exact keyword matches come first. Ranking details: [search.md](search.md).

**Filters:** the chips under the search box limit results to Documents, Images, Audio and/or Video, and to a date range (any date, today, last 7/30 days, this year, or pick dates; photos use the date taken). With a filter but no query you get a list of that kind of file, newest first. While a model switch is re-indexing, a banner says how many files are not searchable yet.

Photos with GPS data are found by place name. Results that match both the meaning and the place rank higher.

Tips: describe content in natural language for photos and scenes; use distinctive words for names, codes and numbers; Korean and English both work.

## Settings

- **What to index:** Images, Text files, Documents, Audio, Video.
- **Folders:** add folders, choose whether to include subfolders.
- **Embedding model:** download or delete 440M (text+image) or 740M (adds audio). Changing it re-indexes.
- **Speech recognition:** needed for transcripts of audio and video.
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
| Model download stalls | Retry; downloads resume. Hugging Face may need a token (Settings). |
| Slow indexing | Emulators use CPU. On phones, a hot device pauses automatic runs. |
| Audio never matches by sound | Audio embedding needs the 740M model; transcripts need the speech model. |
| Old .doc/.ppt/.xls or hwp | Unsupported; convert to the modern format or PDF. |
