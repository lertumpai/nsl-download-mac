# NSL Downloader (Android)

A native Android app that lets you **browse any website**, **detect and download videos**
of any content/streaming type, and **play them back** with a gesture-driven player.

## Features

- **Browser tab** — a full WebView browser (address bar, back/forward/reload). JavaScript,
  DOM storage and inline media enabled so streaming sites work.
- **Universal video detection** — every network request the page makes is sniffed
  (`shouldInterceptRequest`). Direct files (`.mp4/.webm/.mkv/.mov/.ts/...`), **HLS**
  (`.m3u8`), **DASH** (`.mpd`) and `videoplayback`/`manifest` patterns are recognised.
  A floating **Download (N)** button lists every detected stream to pick from.
- **Quality selection** — when an HLS stream is a master playlist, a **Select quality**
  dialog lists every resolution variant (e.g. **1080p / 720p / 480p**, with bitrate) so you
  pick the size *before* downloading. Single-quality streams skip the dialog.
- **Forwarded headers/cookies** — downloads reuse the WebView's **User-Agent**, the page
  **Referer/Origin**, the request headers the WebView itself sent, and the current
  **cookies**. This is what makes CDN-protected sites (e.g. **missav**) work instead of
  returning `403 Forbidden`.
- **Download engine** (`DownloadService`, a foreground service with a progress notification):
  - Direct files → streamed to disk with OkHttp.
  - **HLS** → native downloader (`HlsDownloader`): downloads the chosen variant,
    parses the media playlist, **decrypts AES-128 segments**, and concatenates to a
    playable `.ts`. No ffmpeg dependency. Segments are fetched **6-at-a-time
    concurrently** (then written in playlist order) to hide per-segment latency;
    OkHttp is tuned for parallel same-host requests.
  - DASH/progressive → fetched directly (64 KB buffered I/O).
- **Concurrent downloads** — up to **3 videos download at once** (`MAX_CONCURRENT`), the rest
  queue. Each shows its own progress; a foreground summary notification counts the active ones.
- **Live progress & speed** — each downloading item shows a progress bar with **percent and
  download speed** (e.g. `40% • 721 KB/s`) in the Library, and a per-download notification.
  Progress is published through an in-memory `DownloadProgressBus` (not Room, to avoid disk
  churn) and merged with the list via `combine()`.
- **Rich list rows** — each entry shows **duration • size • date** (e.g. `0:09 • 2.5 MB • Jun 26`)
  plus a real video thumbnail. Duration is probed with `MediaMetadataRetriever` after download.
- **YouTube downloads** (`youtube/YouTubeResolver.kt`, NewPipeExtractor) — on any YouTube
  watch or playlist page the Download button offers:
  - **Video (MP4)** with a real quality picker. Everything above 360p is served by YouTube as
    separate audio-less renditions, so those are downloaded as two streams and **remuxed**
    into one MP4 by `MediaMuxer` (`service/Mp4Muxer.kt`) — a container operation, no
    re-encoding. The picker labels them *"video + audio merged"*. Only AVC-in-MP4 + AAC/M4A
    are offered, because those are what `MediaMuxer` accepts.
  - **Audio (MP3)** at **128 / 192 / 320 kbps** — a genuine MP3, not a renamed `.m4a`.
    Android has no MP3 encoder and ffmpeg-kit was pulled from Maven Central, so the pipeline
    is `MediaCodec` decode → 16-bit PCM → **jump3r** (pure-Java LAME) in
    `service/Mp3Transcoder.kt`. Being pure Java, this is the slow step: expect a few times
    realtime, with live progress in the Library.
  - **Whole playlist as MP4 / MP3** — the listing is read up front, then **one job per video**
    is queued. Jobs carry only the watch URL; the service resolves stream URLs just before
    each transfer, because YouTube's expire long before a long queue drains.
- **Downloads land in a real folder** — everything is published to
  **`Download/NSL Downloader/…`** via MediaStore (API 29+) or a direct file write below that,
  so the files are visible in the device's Files app and to every other app.
- **Jump-to-Library** — starting a download switches to the Library tab so it's immediately visible.
- **Library tab** — Room-backed list with auto-generated video thumbnails (Glide), title,
  size and date.
  - **Folders** — a chip row switches between *All* and each folder; **＋ New folder** creates
    one. A library folder *is* a real subdirectory of `Download/NSL Downloader/`, and moving an
    item (long-press → **Move to folder…**) relocates the actual file.
  - **Open folder** — opens the current folder in the device's file manager.
  - **Long-press an item** for Play / Move / Share / Remove.
  - **Delete one** — trash icon → confirm → removes the DB row **and the file(s)**.
  - **Remove all** — wipes every entry in the current folder and its files.
- **Fullscreen video** — pages that call `requestFullscreen()` (YouTube included) are hosted
  above the entire UI via `onShowCustomView`, covering the tab bar and the system bars. Back
  exits.
- **Leaving the app** — one exclusive choice under the ⋮ menu, because background audio and
  Picture-in-Picture both claim the same media pipeline:
  - **Pause playback**
  - **Keep playing in background** (foreground service + notification)
  - **Float video on top (Picture-in-Picture)** — swiping to another app moves the video into
    a floating window. Works for both the browser and the offline player.
- **Collapsible footer** — the chevron above the tab bar hides the bottom navigation and
  shrinks the download button to its icon; the state persists. Also in the ⋮ menu.
- **Long-press a link** for a menu: *Open in new tab / Open in this tab / Copy / Share /
  Download link*.
- **Chrome-less UI** — no action bar; the browser address bar / library header sit directly
  under the status bar (insets handled via `fitsSystemWindows`).
- **Player** (`PlayerActivity`, ExoPlayer/Media3, **portrait or landscape** — `fullUser`,
  rotates without interrupting playback):
  - **Swipe left / right** = **−10s / +10s**.
  - On-screen **−10s / +10s** buttons.
  - Double-tap left/right half also seeks ∓10s. Visual "+10s ⏩ / ⏪ −10s" feedback flash.
  - Enters Picture-in-Picture on leaving the app when that mode is selected.

## Requirements

- JDK 17+ (tested with JDK 21)
- Android SDK with **platform android-35** and **build-tools 36.1.0**
- Set the SDK path in `local.properties` (`sdk.dir=/path/to/Android/sdk`)

## Build & run

```bash
cd android

# Debug APK (installable)
./gradlew :app:assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk

# Install on a running emulator/device
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Release APK (unsigned; sign with your keystore for Play / sideloading)
./gradlew :app:assembleRelease
# -> app/build/outputs/apk/release/app-release-unsigned.apk
```

### Run on an emulator

```bash
$ANDROID_HOME/emulator/emulator -avd <your_avd> &
adb wait-for-device
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.nsl.downloader/.MainActivity   # .debug suffix for the debug build
```

### Tests

`app/src/androidTest` holds on-device tests for the parts that can only fail on a real
device — YouTube extraction (Rhino has to run YouTube's player JS), MP3 transcoding, MP4
remuxing and the MediaStore hand-off. They hit the live network on purpose: the point is to
show the extractor still works against today's YouTube, which no offline fixture can.

```bash
./gradlew :app:connectedDebugAndroidTest
```

Note this **uninstalls the app** afterwards, so reinstall before poking at the UI by hand.

## Notes & limitations

- **SAMPLE-AES / Widevine / FairPlay DRM** streams cannot be downloaded (by design).
- **DASH with separate audio+video tracks** isn't muxed; single-file/progressive media works.
  (YouTube's split renditions *are* muxed — see `Mp4Muxer` — because they are known to be
  AVC + AAC.)
- **MP3 transcoding is CPU-bound and pure Java.** A long video takes a while; the progress
  bar reflects real decoder position.
- YouTube renditions in **VP9 or AV1** are skipped: remuxing them into MP4 is not something
  `MediaMuxer` will do, and re-encoding is out of scope.
- Below Android 10, publishing into `Download/NSL Downloader` needs the storage permission.
  If it is denied the download is **kept in app-private storage** rather than discarded — it
  stays playable but won't appear in the Files app.
- `minSdk 24`, `targetSdk 35`. Because the app targets 35, **predictive back is on by
  default** and `onBackPressed()` is never called — back is handled through
  `OnBackPressedDispatcher` in `MainActivity`.

## Module map

| Area | File |
|---|---|
| Entry, fullscreen host, PiP, footer | `MainActivity.kt` |
| Browser + sniffing | `browser/BrowserFragment.kt`, `browser/VideoSniffer.kt`, `browser/BrowserScripts.kt` |
| YouTube extraction | `youtube/YouTubeResolver.kt`, `youtube/NewPipeDownloaderImpl.kt` |
| Download service | `service/DownloadService.kt`, `service/HlsDownloader.kt` |
| Mux / transcode | `service/Mp4Muxer.kt`, `service/Mp3Transcoder.kt`, `service/Mp3Encoder.kt` |
| Shared storage + folders | `util/MediaStorage.kt` |
| Player + gestures + PiP | `player/PlayerActivity.kt` |
| Library, folders, delete | `library/LibraryFragment.kt`, `library/LibraryViewModel.kt`, `library/VideoAdapter.kt` |
| Persistence (Room) | `data/*` |
| Settings / playback mode | `util/Prefs.kt` |
| Type detection | `util/VideoType.kt` |
