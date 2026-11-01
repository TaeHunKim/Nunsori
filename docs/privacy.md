# Privacy

Local Search Anything has no server, no account, no analytics. Indexing and search run on the device.

## What is stored, and where

All in the app's private storage (removed on uninstall or "Clear data"):

- Room database `lsa.db`: file names, paths, metadata (EXIF date and location of photos), extracted text (OCR, document text, transcripts) and embedding vectors.
- Thumbnails (`ThumbStore`, 360 px) of images, document pages and text cards.
- Downloaded models under `files/models/`.
- Settings (DataStore), including the optional Hugging Face token.

Your original files are only read, never modified.

## Network

The app contacts the network only to download models you ask for:

- `https://huggingface.co/litert-community/...` (EmbeddingGemma 2; the token, if any, is sent only here)
- `https://github.com/k2-fsa/sherpa-onnx/releases/...` (SenseVoice)

Search queries and file contents never leave the device. `INTERNET` is used for nothing else.

Place names for photo locations come from a small dataset bundled in the app (GeoNames, offline); coordinates are never sent anywhere.

## Permissions (`AndroidManifest.xml`)

| Permission | Why |
|---|---|
| `READ_MEDIA_IMAGES/AUDIO/VIDEO` (`READ_EXTERNAL_STORAGE` up to API 32) | Index photos, audio, video |
| `ACCESS_MEDIA_LOCATION` | Read photo location from EXIF |
| `MANAGE_EXTERNAL_STORAGE` (All files access) | Read text files and documents, which MediaStore does not list reliably; optional, only needed for those kinds |
| `INTERNET` | Model download |
| `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | Progress notification and long indexing runs |

## Backup

Android Auto Backup is disabled, so the index, thumbnails and settings are not copied to Google Drive or restored on a new phone.

## Deleting your data

Remove one model in Settings, remove a folder to drop its items, or clear app data / uninstall to delete everything.
