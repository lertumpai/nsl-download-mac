# NSL Download — macOS Video Downloader
### Product Specification v1.0

---

## 1. Overview

**NSL Download** is a native macOS application that lets users paste any video URL and instantly download the video — including streaming content from platforms like YouTube, Facebook, VK, Twitch, TikTok, and hundreds more. The app handles adaptive streaming formats (HLS, DASH, RTMP) and automatically merges split audio/video streams into a single output file.

---

## 2. Goals

| Goal | Description |
|------|-------------|
| **Simplicity** | Paste a link → pick quality → download. Zero technical knowledge required. |
| **Breadth** | Support 1,000+ sites via a proven extraction backend (yt-dlp). |
| **Streaming** | Handle HLS (`.m3u8`), MPEG-DASH (`.mpd`), and RTMP streams natively. |
| **Muxing** | Auto-merge separate audio + video tracks into one `.mp4` / `.mkv` file. |
| **Native feel** | Looks and behaves like a real macOS app (menu bar, notifications, dark mode). |

---

## 3. Target Platforms

- **OS**: macOS 13 Ventura and later (Apple Silicon + Intel)
- **Architecture**: Universal Binary (arm64 + x86_64)

---

## 4. Supported Video Sources

### Tier 1 (fully tested & prioritised)
| Platform | URL Examples | Notes |
|----------|-------------|-------|
| YouTube | `youtube.com/watch`, `youtu.be` | Adaptive streams (DASH), age-gated content |
| Facebook | `fb.com/video`, `fb.watch` | Public + logged-in content |
| VK | `vk.com/video` | Requires session cookie for private videos |
| Instagram | Reels, Posts, Stories | |
| TikTok | Standard & live | |
| Twitter / X | Video tweets | |
| Twitch | VODs + Clips | |

### Tier 2 (community-tested via yt-dlp)
- Vimeo, Dailymotion, Bilibili, Reddit, Streamable, Rumble, Odysee, and 1,000+ others.

### Streaming Formats Supported
| Format | Protocol | Notes |
|--------|----------|-------|
| HLS | `m3u8` | Multi-bitrate, segmented |
| MPEG-DASH | `mpd` | Separate audio/video streams |
| RTMP / RTMPS | `rtmp://` | Live and archived |
| Progressive MP4 | `https://` | Direct file |
| WebM / VP9 | `https://` | YouTube fallback |

---

## 5. Core Features

### 5.1 URL Input & Detection
- **Paste & Go**: User pastes a URL; app immediately fetches video metadata (title, thumbnail, duration, available formats).
- **Clipboard Watch** *(optional)*: Auto-detects a valid video URL copied to the clipboard and shows a mini prompt.
- **Batch Input**: Accept multiple URLs at once (newline-separated or drag-and-drop a `.txt` file).

### 5.2 Quality & Format Selection
- Display all available streams grouped by resolution: `4K`, `1080p`, `720p`, `480p`, `360p`, `Audio Only`.
- Show codec info (H.264, H.265/HEVC, AV1, VP9, AAC, Opus).
- Let the user set a **default quality** in preferences so single-click download works instantly.

### 5.3 Stream Merging (Muxing)
> **IMPORTANT**: Many platforms (especially YouTube) serve audio and video as **separate adaptive streams**. The app must automatically detect this and merge them using FFmpeg before presenting the final file to the user.

- **Automatic mux**: After segments are downloaded, `ffmpeg` merges audio + video losslessly (`-c copy`).
- **Progress**: Show combined progress (download segments → mux → done).
- **Output format**: User can choose `.mp4` (default), `.mkv`, or `.webm`.

### 5.4 Download Manager
- Queue with live progress bar per item (speed, ETA, downloaded / total size).
- Pause / Resume individual downloads.
- Retry failed downloads with one click.
- Concurrent downloads: configurable (default 3).

### 5.5 Output & File Management
- Default save folder: `~/Movies/NSL Downloads/` (configurable).
- File naming template: `{title} [{resolution}].{ext}` (customisable).
- Avoid duplicate filenames by auto-incrementing.
- Open in Finder / Quick Look after completion.

### 5.6 Authentication (Cookies)
- Support importing browser cookies (Chrome, Firefox, Safari) via cookie file for gated content (Facebook private videos, VK login-required).
- Store cookies securely in macOS Keychain.

### 5.7 Notifications & Menu Bar
- macOS native notifications when a download completes or fails.
- Optional **menu bar icon** showing active downloads count + quick-add URL field.

---

## 6. UI / UX Design

### 6.1 Main Window Layout

```
┌──────────────────────────────────────────────────────────┐
│  NSL Download                          ⬛ 🔴 🟡 🟢      │
├──────────────────────────────────────────────────────────┤
│  ┌──────────────────────────────────────┐  [Paste & Go] │
│  │  Paste video URL here...             │               │
│  └──────────────────────────────────────┘               │
├──────────────────────────────────────────────────────────┤
│  QUEUE                                                    │
│  ┌────────────────────────────────────────────────────┐  │
│  │ 🎬 [Thumbnail] Title of Video         1080p MP4    │  │
│  │              ████████████░░░░░░  62%  12.4 MB/s    │  │
│  │              ETA: 0:42  |  [Pause]  [Cancel]       │  │
│  ├────────────────────────────────────────────────────┤  │
│  │ 🎬 [Thumbnail] Another Video          720p MP4     │  │
│  │              Queued...                              │  │
│  └────────────────────────────────────────────────────┘  │
├──────────────────────────────────────────────────────────┤
│  COMPLETED                                                │
│  ✅ Video Name.mp4   1.2 GB   [Open File] [Show Finder]  │
└──────────────────────────────────────────────────────────┘
```

### 6.2 Quality Picker Sheet (appears after URL analysis)
```
┌─────────────────────────────────────┐
│  📺 "Video Title Here"              │
│  ⏱ 12:34  •  youtube.com           │
│  [Thumbnail]                        │
│                                     │
│  Select Format:                     │
│  ○ 4K   H.265  ~800 MB              │
│  ● 1080p H.264  ~350 MB  ← default  │
│  ○ 720p  H.264  ~180 MB             │
│  ○ Audio Only  AAC ~28 MB           │
│                                     │
│  Output: .mp4  ▾    [Download]      │
└─────────────────────────────────────┘
```

### 6.3 Design Language
- Follow macOS **Human Interface Guidelines**.
- Support **Light and Dark Mode** automatically.
- Use SF Pro typography with SF Symbols icons.
- Accent color: configurable (defaults to system accent).

---

## 7. Technical Architecture

### 7.1 Tech Stack

| Layer | Technology | Reason |
|-------|-----------|--------|
| UI | **SwiftUI** (macOS) | Native, modern, Dark Mode auto |
| Download Engine | **yt-dlp** (Python CLI, bundled) | Supports 1,000+ sites |
| Stream Muxer | **FFmpeg** (bundled static binary) | Industry-standard A/V merge |
| Process Mgmt | Swift `Process` / `AsyncStream` | Non-blocking, cancellable |
| Storage | **Core Data** / SQLite | Queue persistence |
| Keychain | **Security.framework** | Cookie / credential storage |
| Notifications | **UserNotifications** | Native macOS alerts |

### 7.2 Bundled Binaries
Both `yt-dlp` and `ffmpeg` will be **shipped inside the app bundle** (`Contents/MacOS/bin/`) so users need no separate installation.

- `yt-dlp`: universal Python-compiled binary (no Python runtime needed).
- `ffmpeg`: static universal binary from `evermeet.cx/ffmpeg` or compiled from source.

### 7.3 Download Flow

```
User → Paste URL
  → App calls: yt-dlp --dump-json <URL>
  → yt-dlp returns: JSON metadata (formats, title, thumbnail)
  → App shows: Quality Picker to User
  → User selects quality & confirms
  → App calls: yt-dlp -f <format> -o <path> <URL>
  → yt-dlp streams: Progress via stdout
  → yt-dlp calls: FFmpeg internally for muxing (--merge-output-format)
  → FFmpeg completes mux
  → App sends: macOS Notification + file is ready
```

### 7.4 Progress Parsing
Parse yt-dlp stdout lines matching:
```
[download]  62.5% of  350.00MiB at   12.40MiB/s ETA 00:42
[ffmpeg] Merging formats into "output.mp4"
```
Update SwiftUI `@Published` progress model in real-time via `AsyncStream`.

### 7.5 Cookie Import
- User selects browser → app reads cookie store (using `--cookies-from-browser chrome` yt-dlp flag).
- Alternatively, import a Netscape-format `cookies.txt` file.

---

## 8. Preferences / Settings

| Setting | Default | Description |
|---------|---------|-------------|
| Default quality | 1080p | Quality auto-selected on download |
| Default format | MP4 | Container format |
| Save folder | `~/Movies/NSL Downloads` | Output directory |
| Filename template | `{title} [{height}p].{ext}` | Rename pattern |
| Max concurrent downloads | 3 | Parallelism |
| Embed thumbnail | Off | Write thumbnail into MP4 metadata |
| Embed subtitles | Off | Download & embed `.srt` / `.vtt` |
| Clipboard monitoring | Off | Auto-detect copied video URLs |
| Menu bar icon | On | Show downloads in menu bar |
| Cookie browser | None | Browser for cookie extraction |

---

## 9. Error Handling

| Error | User-facing Message | Action |
|-------|---------------------|--------|
| Unsupported URL | "This URL is not supported." | Link to supported sites list |
| Private / login required | "This video requires login. Please import cookies." | Open cookie import dialog |
| Network timeout | "Download failed. Check your connection." | Retry button |
| Disk full | "Not enough disk space." | Open storage settings |
| Mux failed | "Could not merge audio/video." | Offer raw streams as fallback |
| yt-dlp outdated | "Extractor needs update." | Auto-update yt-dlp binary |

---

## 10. Distribution & Updates

| Item | Detail |
|------|--------|
| Distribution | Direct download (DMG) + Mac App Store (if sandboxing allows) |
| Notarization | Apple notarized (required for Gatekeeper) |
| Auto-update | **Sparkle** framework for direct DMG distribution |
| yt-dlp update | In-app "Update Extractor" button (downloads latest `yt-dlp` release from GitHub) |
| License | To be decided (proprietary / MIT) |

---

## 11. Development Milestones

| Milestone | Deliverables | Est. Duration |
|-----------|-------------|---------------|
| **M1 — Core Engine** | Bundle yt-dlp + ffmpeg, run download from CLI wrapper, parse progress | 1 week |
| **M2 — Basic UI** | SwiftUI main window, URL input, quality picker, download queue | 1.5 weeks |
| **M3 — Streaming & Mux** | HLS/DASH/RTMP support, auto-mux, combined progress tracking | 1 week |
| **M4 — Polish** | Preferences, notifications, menu bar, Dark Mode, error handling | 1 week |
| **M5 — Distribution** | Signing, notarization, DMG packaging, Sparkle updates | 0.5 week |

**Total estimate: ~5 weeks for v1.0**

---

## 12. Out of Scope (v1.0)

- iOS / iPadOS version
- Browser extension integration
- Cloud sync / remote queue
- Scheduled downloads
- Post-processing (trim, compress, convert)
- Downloading DRM-protected content (Netflix, Disney+, etc.)

---

## 13. Open Questions

Please review and answer these before development begins:

1. **Distribution**: App Store or direct DMG download only? (App Store sandbox may restrict bundling yt-dlp/ffmpeg.)
2. **Monetisation**: Free, one-time purchase, or freemium (e.g., limit concurrent downloads on free tier)?
3. **App Name / Branding**: Is "NSL Download" the final name? Any icon/brand guidelines?
4. **Cookie / Login support priority**: Is Facebook private video support a must-have for v1.0?
5. **Language localisation**: Thai + English from day one, or English first?

---

*Spec written: 2026-05-02 | Author: Antigravity (AI) | Version: 1.0*
