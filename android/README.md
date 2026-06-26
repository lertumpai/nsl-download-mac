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
    playable `.ts`. No ffmpeg dependency.
  - DASH/progressive → fetched directly.
- **Live progress & speed** — each downloading item shows a progress bar with **percent and
  download speed** (e.g. `40% • 721 KB/s`) in the Library, and the same in the notification.
  Progress is published through an in-memory `DownloadProgressBus` (not Room, to avoid disk
  churn) and merged with the list via `combine()`.
- **Library tab** — Room-backed list with auto-generated video thumbnails (Glide), title,
  size and date.
  - **Delete one** — trash icon → confirm → removes the DB row **and the file(s)**.
  - **Remove all** — wipes every entry and the whole `downloads/` directory.
- **Chrome-less UI** — no action bar; the browser address bar / library header sit directly
  under the status bar (insets handled via `fitsSystemWindows`).
- **Player** (`PlayerActivity`, ExoPlayer/Media3, landscape):
  - **Swipe left / right** = **−10s / +10s**.
  - On-screen **−10s / +10s** buttons.
  - Double-tap left/right half also seeks ∓10s. Visual "+10s ⏩ / ⏪ −10s" feedback flash.

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

## Notes & limitations

- **SAMPLE-AES / Widevine / FairPlay DRM** streams cannot be downloaded (by design).
- **DASH with separate audio+video tracks** isn't muxed; single-file/progressive media works.
- `minSdk 24`, `targetSdk 35`.

## Module map

| Area | File |
|---|---|
| Entry + bottom nav | `MainActivity.kt` |
| Browser + sniffing | `browser/BrowserFragment.kt`, `browser/VideoSniffer.kt` |
| Download service | `service/DownloadService.kt`, `service/HlsDownloader.kt` |
| Player + gestures | `player/PlayerActivity.kt` |
| Library + delete | `library/LibraryFragment.kt`, `library/LibraryViewModel.kt`, `library/VideoAdapter.kt` |
| Persistence (Room) | `data/*` |
| Type detection | `util/VideoType.kt` |
