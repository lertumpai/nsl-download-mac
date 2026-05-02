# NSL Download

A native macOS video downloader. Paste any video URL → pick quality → download. Supports YouTube, Facebook, VK, TikTok, Twitch, and 1,000+ other sites via yt-dlp.

**Requires macOS 13 Ventura or later.**

---

## Prerequisites

### 1. Xcode

Install Xcode 15 or later from the Mac App Store. Command-line tools alone are not enough — you need the full Xcode.app.

```
xcode-select --install    # installs CLI tools (not enough on its own)
```

Open Xcode at least once and accept the license agreement before building.

### 2. yt-dlp and FFmpeg

The app looks for bundled binaries inside the app bundle first, then falls back to system-installed ones. For development, the easiest path is Homebrew:

```bash
brew install yt-dlp ffmpeg
```

After installing, verify both are accessible:

```bash
yt-dlp --version
ffmpeg -version
```

---

## Build & Run

### Option A — Xcode GUI (recommended)

1. Clone this repo and open the project:

   ```bash
   git clone <repo-url>
   open NSLDownload.xcodeproj
   ```

2. In Xcode, select the **NSLDownload** scheme and your Mac as the destination.

3. Set your **Development Team**:
   - Open project settings → *Signing & Capabilities*
   - Choose your Apple ID team (a free account works for local development)

4. Press **⌘R** to build and run.

### Option B — Command line

```bash
xcodebuild -project NSLDownload.xcodeproj \
           -scheme NSLDownload \
           -configuration Debug \
           -destination 'platform=macOS' \
           build
```

The built app lands in `~/Library/Developer/Xcode/DerivedData/NSLDownload-*/Build/Products/Debug/NSLDownload.app`.

---

## Bundling yt-dlp and FFmpeg (for distribution)

For a self-contained app that works without Homebrew:

1. Download the universal yt-dlp binary:

   ```bash
   curl -L https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_macos \
        -o yt-dlp
   chmod +x yt-dlp
   ```

2. Download a static FFmpeg universal binary from [evermeet.cx/ffmpeg](https://evermeet.cx/ffmpeg/) or build from source.

3. Place both binaries in the app bundle after building:

   ```bash
   APP=~/Library/Developer/Xcode/DerivedData/NSLDownload-*/Build/Products/Debug/NSLDownload.app
   mkdir -p "$APP/Contents/MacOS/bin"
   cp yt-dlp   "$APP/Contents/MacOS/bin/"
   cp ffmpeg   "$APP/Contents/MacOS/bin/"
   chmod +x    "$APP/Contents/MacOS/bin/"*
   ```

The app checks `Contents/MacOS/bin/yt-dlp` first; if absent it falls back to `/opt/homebrew/bin/yt-dlp` and `/usr/local/bin/yt-dlp`.

---

## Project Structure

```
NSLDownload.xcodeproj/
NSLDownload/
├── NSLDownloadApp.swift        Entry point (@main), Window + MenuBarExtra + Settings
├── ContentView.swift           Main window: URL input, queue, completed list
├── Models/
│   ├── DownloadItem.swift      Observable download state machine
│   ├── VideoMetadata.swift     Decoded from yt-dlp --dump-json output
│   └── AppSettings.swift       UserDefaults-backed preferences singleton
├── Services/
│   ├── YTDLPService.swift      Process management, progress line parsing
│   ├── DownloadManager.swift   Queue, concurrency, pause/resume/cancel/retry
│   └── NotificationService.swift  macOS notifications
└── Views/
    ├── URLInputView.swift
    ├── QualityPickerSheet.swift
    ├── DownloadItemRow.swift
    ├── CompletedItemRow.swift
    ├── PreferencesView.swift
    └── MenuBarView.swift
```

---

## Settings

Open **NSL Download → Settings** (⌘,) to configure:

| Setting | Default | Notes |
|---|---|---|
| Default quality | 1080p | Auto-selected in quality picker |
| Default format | MP4 | Container for merged output |
| Save folder | `~/Movies/NSL Downloads` | Created automatically |
| Filename template | `%(title)s [%(height)sp].%(ext)s` | yt-dlp output template syntax |
| Concurrent downloads | 3 | Max parallel downloads |
| Embed thumbnail | Off | Writes cover art into MP4 |
| Embed subtitles | Off | Downloads and embeds .srt/.vtt |
| Clipboard monitoring | Off | Auto-detects copied video URLs |
| Cookie browser | None | Chrome / Firefox / Safari for gated content |

---

## Cookies (private videos)

For Facebook private videos, VK login-required content, etc.:

1. Open **Settings → Advanced**
2. Select the browser you use for that site under *Import cookies from*
3. yt-dlp reads the cookies directly from the browser's on-disk store — no export step needed

Alternatively, export a Netscape-format `cookies.txt` from a browser extension and pass it to yt-dlp manually via the command line.

---

## Troubleshooting

**"yt-dlp binary not found"** — Install via `brew install yt-dlp` or bundle the binary as described above.

**"This URL is not supported"** — The site may not be in yt-dlp's extractor list. Check [supported sites](https://github.com/yt-dlp/yt-dlp/blob/master/supportedsites.md).

**"This video requires login"** — Configure the cookie browser in Settings → Advanced.

**Download stuck at muxing** — FFmpeg is required for merging adaptive streams. Install via `brew install ffmpeg`.

**Update yt-dlp** — Settings → Advanced → *Update yt-dlp* runs `yt-dlp -U` to pull the latest release.

---

## Distribution

For notarized DMG distribution:

1. Set a valid Development Team with an Apple Developer account.
2. Ensure `ENABLE_HARDENED_RUNTIME = YES` (already set in the project).
3. Archive via **Product → Archive**, then distribute through Xcode Organizer.
4. The app is not sandboxed (`com.apple.security.app-sandbox = false`) because sandboxing blocks subprocess execution of bundled binaries — this means Mac App Store distribution is not possible without significant rework.
