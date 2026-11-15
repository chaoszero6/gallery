# Gallery

An Android gallery modelled on the Samsung Gallery layout and gestures (floating pill bars, Pictures / Albums / Collections / Menu, pinch-to-zoom grid, filmstrip viewer with swipe-up details), with one addition: **Nextcloud Memories** as its only cloud.

## What it does

- **Merged timeline:** phone photos plus the Memories timeline from your Nextcloud.
  - A phone copy and a server copy of the same photo show once.
  - Server-only items carry a cloud badge.
- **Memories features:**
  - map with photo clusters
  - albums, including shared ones
  - favourites and archive
  - people (Recognize / Face Recognition), places and tags
  - "On this day" stories
  - date and location editing (exiftool on the server)
  - HLS video transcoding, live photos
- **Backup to Nextcloud only:**
  - **Auto-upload:** new photos in the folders you pick are uploaded as they appear.
  - **Sync:** compares every photo in those folders with the server and uploads what's missing. It shows a report first, never deletes or overwrites anything, and can run daily.
  - Large files use resumable chunked upload.
- **Smart search on the phone:** CLIP sentence search, ML Kit labels and text recognition, dates and file names, plus Memories' people, places, tags and albums.
  - Nothing leaves the phone except requests to your own Nextcloud.
  - The telemetry built into ONNX Runtime and ML Kit is removed in `AndroidManifest.xml`.

## Build

The build needs JDK 17 and the Android SDK (platform 37, build-tools 37):

```bash
export JAVA_HOME=~/.local/jdk-17 ANDROID_HOME=~/Android/Sdk
python tools/export_clip.py --images <some photos>  # once: writes app/src/main/assets/clip/* (~200 MB, git-ignored)
./gradlew assembleRelease                           # app/build/outputs/apk/release/app-arm64-v8a-release.apk
adb install -r -g app/build/outputs/apk/release/app-arm64-v8a-release.apk
```

Without the model files the app still works, but search falls back to labels, text, dates and names.

`tools/export_clip.py` needs `torch` (CPU), `transformers`, `onnx`, `onnxruntime` and `pillow`. It:
1. exports OpenAI CLIP ViT-B/32 (MIT licence);
2. quantises the weights to 8 bits (`MatMulNBits`);
3. checks the result against PyTorch: cosine ≥ 0.9998.

## Tests

```bash
./gradlew testDebugUnitTest            # backup planner, matching, dates, tokenizer, Memories/WebDAV/login/upload (MockWebServer)
./gradlew connectedDebugAndroidTest    # on a device: CLIP model vs PyTorch reference embeddings
```

**Live server test.** Add these lines to `local.properties` (it is git-ignored):

```
nc.url=https://cloud.example.com
nc.user=alice
nc.appPassword=xxxxx-xxxxx-xxxxx-xxxxx-xxxxx
```

Then run `./gradlew testDebugUnitTest --tests '*LiveServerTest*' -i`. It makes read-only calls only.

## Server requirements

- Nextcloud 34 or newer, with **Memories 10 or newer**.
- Optional apps:
  - Photos: albums
  - Recognize or Face Recognition: people
  - Memories places setup: places
  - Collaborative tags: tags
- Features the server lacks are hidden in the app.

## Layout

```
app/src/main/java/app/gallery/
  data/local       MediaStore reader, system recycle bin
  data/nextcloud   Memories API, WebDAV, Login Flow v2, account (Keystore-encrypted)
  data/repo        merged media (MediaRepository + Matcher), actions with offline queue, clusters
  backup           BackupPlanner (compare), UploadEngine (PUT / chunked v2), workers
  search           CLIP (ONNX), tokenizer, indexer (ML Kit), search engine
  ui/...           Compose screens (theme, grid, viewer, albums, collections, map, search, settings, nextcloud)
tools/export_clip.py
docs/reference/    Samsung Gallery screenshots used as the UI reference (local only, git-ignored)
```
