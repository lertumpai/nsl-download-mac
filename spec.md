# NSL Download — Video Downloader Browser
### Product Specification v2.0

---

## 1. Overview

**NSL Download** is a desktop application that embeds a full web browser. The user browses to any website normally — YouTube, Facebook, VK, TikTok, etc. — and the app automatically detects video streams playing on the page. A sidebar shows each detected video with a one-click **Download** button. No URL copying or pasting required.

Login to private content works naturally because the user is already authenticated inside the embedded browser.

---

## 2. Goals

| Goal | Description |
|------|-------------|
| **Browse naturally** | Full Chromium browser embedded in the app — user just watches the page load |
| **Auto-detect** | Intercept HLS / DASH / direct video URLs from network traffic as they stream |
| **One-click download** | Click Download on detected video → pick quality → done |
| **Login solved** | No cookie import needed — user logs into sites inside the app |
| **Breadth** | yt-dlp handles 1,000+ sites; network interception catches everything else |

---

## 3. Target Platforms

- **OS**: macOS 13 Ventura and later (Apple Silicon + Intel)
- **Runtime**: Electron (Chromium + Node.js, universal binary)

---

## 4. Supported Video Sources

Detection works at two levels:

### Level 1 — Network interception (catches everything)
The app monitors every network request the embedded browser makes. Any URL matching these patterns triggers a detected-video entry:

| Pattern | Type | Example |
|---------|------|---------|
| `*.m3u8` in URL | HLS stream | Twitch, live streams |
| `*.mpd` in URL | MPEG-DASH | YouTube adaptive |
| `content-type: video/*` | Direct video | MP4, WebM files |
| `content-type: application/x-mpegurl` | HLS | HLS servers |
| `videoplayback` + `googlevideo.com` | YouTube | YouTube internal |

### Level 2 — DOM scan (catches `<video>` elements)
After each page load, the app scans `document.querySelectorAll('video')` for `src` / `currentSrc` attributes.

### Level 3 — yt-dlp fallback
For any detected page, the user can always trigger a manual yt-dlp analysis from the sidebar to get the full format list including formats not visible in network traffic.

---

## 5. Core Features

### 5.1 Embedded Browser
- Full Chromium engine via Electron `BrowserView` / `WebContentsView`
- Navigation toolbar: Back, Forward, Reload, address bar with search fallback (Google)
- Cookies and session persist across app launches (`persist:browser` partition)
- New tab support (Cmd+T)

### 5.2 Video Detection Sidebar
- Fixed right panel (320 px) always visible alongside the browser
- One entry per page — deduplicated by page URL
- Shows: page title, favicon, detected stream type badge (HLS / DASH / MP4 / YouTube)
- **Download** button on each entry opens the quality picker
- **Analyse with yt-dlp** button for manual metadata fetch

### 5.3 Quality Picker
- Appears inline in sidebar after clicking Download
- Calls `yt-dlp --dump-json <pageURL>` to retrieve all formats
- Formats grouped by resolution: 4K, 1080p, 720p, 480p, 360p, Audio Only
- Shows codec (H.264, H.265, AV1, VP9) and estimated file size
- Default quality preference applied automatically
- Output format selector: MP4 (default), MKV, WebM

### 5.4 Download Manager
- Queue section in sidebar below detected videos
- Per-item progress bar: percentage, speed (MB/s), ETA
- Pause / Resume / Cancel per item
- Retry failed downloads
- Concurrent downloads: configurable (default 3)

### 5.5 Stream Merging (Muxing)
- yt-dlp calls FFmpeg internally via `--merge-output-format`
- Progress shows "Merging…" stage after download segments complete
- Both yt-dlp and FFmpeg bundled at `Resources/bin/`

### 5.6 Output & File Management
- Default save folder: `~/Movies/NSL Downloads/` (configurable)
- Filename template: `%(title)s [%(height)sp].%(ext)s` (yt-dlp syntax)
- Open file / Show in Finder buttons on completed items

### 5.7 Notifications
- macOS native notification when download completes or fails

---

## 6. UI Layout

```
┌──────────────────────────────────────────────────────────────────┐
│  ← → ↺  [ https://www.youtube.com/watch?v=...    ] [⚙ Settings] │
├──────────────────────────────────────────────────┬───────────────┤
│                                                  │ DETECTED  (2) │
│                                                  │ ┌───────────┐ │
│                                                  │ │▶ YT Video │ │
│         Chromium BrowserView                     │ │  YouTube  │ │
│                                                  │ │[↓Download]│ │
│         (full web browsing)                      │ └───────────┘ │
│                                                  │ ┌───────────┐ │
│                                                  │ │▶ FB Video │ │
│                                                  │ │  HLS      │ │
│                                                  │ │[↓Download]│ │
│                                                  │ └───────────┘ │
│                                                  ├───────────────┤
│                                                  │ DOWNLOADING   │
│                                                  │ ┌───────────┐ │
│                                                  │ │ Title     │ │
│                                                  │ │ ████░ 62% │ │
│                                                  │ │ 12MB/s    │ │
│                                                  │ └───────────┘ │
│                                                  ├───────────────┤
│                                                  │ COMPLETED     │
│                                                  │ ✅ video.mp4  │
│                                                  │ [Open][Finder]│
└──────────────────────────────────────────────────┴───────────────┘
```

---

## 7. Technical Architecture

### 7.1 Tech Stack

| Layer | Technology | Reason |
|-------|-----------|--------|
| App shell | **Electron** (macOS) | Embeds Chromium, exposes Node.js for subprocess management |
| Browser engine | **Chromium** (via BrowserView) | Full web browsing with request interception |
| Video detection | `session.webRequest` + DOM scan | Catches HLS/DASH/direct video without any user action |
| Download engine | **yt-dlp** (bundled binary) | 1,000+ site support, quality selection, muxing |
| Stream muxer | **FFmpeg** (bundled binary) | Called internally by yt-dlp |
| UI | Vanilla HTML/CSS/JS | Lightweight, no framework overhead |
| Settings | **electron-store** | JSON persisted to app userData |
| Notifications | Electron `Notification` API | Native macOS alerts |

### 7.2 Bundled Binaries

Both `yt-dlp` and `ffmpeg` shipped inside the app at `Resources/bin/` (via `electron-builder` `extraResources`). Fall back to Homebrew paths (`/opt/homebrew/bin/`) for development.

### 7.3 Detection Flow

```
User browses → page loads in BrowserView
  → session.webRequest.onCompleted fires for every network request
  → main.js checks URL/Content-Type for video patterns
  → match found → deduplicate by pageURL → send IPC to renderer
  → sidebar shows new entry with [↓ Download] button
  (simultaneously)
  → page finish loading → executeJavaScript scans <video> elements
  → any new src found → same dedup + IPC flow
```

### 7.4 Download Flow

```
User clicks Download on sidebar entry
  → renderer sends IPC: fetch-metadata(pageURL)
  → main.js: spawn yt-dlp --dump-json <pageURL>
  → formats returned → renderer shows quality picker inline
  → user picks format → renderer sends IPC: start-download(opts)
  → main.js: spawn yt-dlp -f <format> -o <template> <pageURL>
  → stdout lines parsed for progress → IPC to renderer → progress bar
  → yt-dlp calls FFmpeg internally for muxing
  → process exits 0 → IPC download-complete → notification
```

### 7.5 Progress Parsing

Same yt-dlp stdout format as v1:
```
[download]  62.5% of  350.00MiB at   12.40MiB/s ETA 00:42
[ffmpeg] Merging formats into "output.mp4"
```

---

## 8. Settings

| Setting | Default | Description |
|---------|---------|-------------|
| Default quality | 1080p | Pre-selected in quality picker |
| Default format | mp4 | Merge output format |
| Save folder | `~/Movies/NSL Downloads` | Output directory |
| Filename template | `%(title)s [%(height)sp].%(ext)s` | yt-dlp output template |
| Max concurrent downloads | 3 | Parallelism |
| Homepage | `https://www.youtube.com` | Browser start page |

---

## 9. Error Handling

| Error | Message | Action |
|-------|---------|--------|
| yt-dlp not found | "yt-dlp not installed" | Link to install instructions |
| Unsupported URL | "Site not supported" | Suggest manual yt-dlp |
| Login required | *(shouldn't happen — user is already logged in)* | Prompt user to log in inside the browser |
| Network error | "Download failed. Check connection." | Retry button |
| Disk full | "Not enough disk space." | Open save folder settings |
| Mux failed | "Could not merge streams." | Offer raw video file |

---

## 10. Distribution

| Item | Detail |
|------|--------|
| Distribution | DMG via `electron-builder` |
| Auto-update | `electron-updater` |
| Notarization | `electron-builder` + Apple notarize |
| yt-dlp update | In-settings "Update yt-dlp" button |

---

## 11. Development Milestones

| Milestone | Deliverables | Est. Duration |
|-----------|-------------|---------------|
| **M1 — Browser shell** | Electron window, BrowserView, toolbar navigation | 3 days |
| **M2 — Detection** | webRequest interceptor, DOM scan, sidebar entries | 3 days |
| **M3 — Download** | yt-dlp integration, quality picker, progress | 4 days |
| **M4 — Polish** | Settings, notifications, dark mode, error handling | 3 days |
| **M5 — Distribution** | electron-builder, signing, notarization, DMG | 2 days |

**Total estimate: ~3 weeks for v1.0**

---

## 12. Out of Scope (v1.0)

- Windows / Linux builds (architecture supports it, not prioritised)
- Browser extensions
- Scheduled downloads
- Post-processing (trim, convert)
- DRM-protected content (Netflix, Disney+)

---

*Spec updated: 2026-05-02 | Version: 2.0 — Electron browser approach*
