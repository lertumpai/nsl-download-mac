# NSL Download — Feature Additions
### Product Specification v2.1

---

## Overview

This document defines two new features added on top of the v2.0 Electron browser spec:

1. **Audio extraction** — convert any downloaded video to a music file (MP3 / AAC / FLAC)
2. **Playlist downloader** — browse to a YouTube playlist, browse all videos in the sidebar, select individual ones, and batch-download

These features extend the existing architecture without replacing it.

---

## Feature 1 — Audio Extraction (Convert to Music)

### 1.1 User Flow

1. User detects a video normally (or opens the quality picker)
2. In the quality picker, a new **"Audio only"** section appears at the bottom with a dedicated **Extract Audio** button
3. User picks the output audio format (MP3 / AAC / FLAC) from a dropdown
4. Click **Extract Audio** — yt-dlp downloads the best audio stream and FFmpeg re-encodes it to the chosen format
5. The completed file appears in the DONE tab with a music note icon instead of a video icon

### 1.2 UI Changes

**Quality picker footer — add audio extract row:**

```
┌─────────────────────────────────────┐
│  Quality picker                  [×]│
│  ─────────────────────────────────  │
│  ○ 1080p  H.264    ~450 MB          │
│  ○  720p  H.264    ~250 MB          │
│  ○  480p  H.264    ~120 MB          │
│  ─────────────────────────────────  │
│  [Format: MP4 ▾]  [ ↓ Download ]   │
│  ─────────────────────────────────  │
│  [Audio: MP3 ▾]   [ ♪ Extract  ]   │
└─────────────────────────────────────┘
```

A thin divider separates video download from audio extract. The audio format selector has three options: MP3, AAC, FLAC. No quality row needs to be selected for audio extraction — it always pulls the best available audio stream.

### 1.3 yt-dlp Command

```
yt-dlp \
  -f bestaudio \
  -x \
  --audio-format mp3 \          # or aac / flac
  --audio-quality 0 \            # best quality for the chosen codec
  --ffmpeg-location <ffmpeg> \
  -o "%(title)s.%(ext)s" \
  --newline \
  <pageURL>
```

`-x` tells yt-dlp to extract audio and call FFmpeg to re-encode. Progress output is identical to a video download so the existing `parseYTDLPLine` parser works unchanged.

### 1.4 Progress & Completion

- Progress bar appears in the QUEUE tab exactly like a video download
- Status text shows "Extracting audio…" instead of "Merging…" during the FFmpeg step
- DONE tab entry shows `♪` icon and filename ending in `.mp3` / `.aac` / `.flac`

### 1.5 Settings Addition

| Setting | Default | Description |
|---------|---------|-------------|
| Default audio format | MP3 | Pre-selected in the audio format dropdown |
| Audio quality (MP3) | 320k | Bitrate for MP3 output (`--audio-quality 0` = VBR best, or specify `128`/`192`/`320`) |

---

## Feature 2 — Playlist Downloader

### 2.1 User Flow

1. User browses to a YouTube playlist URL (`/playlist?list=...`) inside the embedded browser
2. The app detects it is a playlist page (URL pattern) and shows a **"Playlist detected"** banner at the top of the DETECTED panel
3. User clicks **Browse Playlist** — the sidebar switches to a Playlist view that lists all videos in the playlist (fetched via `yt-dlp --flat-playlist --dump-json`)
4. Each playlist entry has a checkbox. A **Select All / Deselect All** toggle is at the top
5. User selects the videos they want, chooses a quality preset (applies to all selected), and clicks **Download Selected**
6. All selected videos are queued into the QUEUE tab as individual download items, subject to the existing concurrency limit

### 2.2 UI — Playlist Panel

```
┌──────────────────────────────────────────────────────────────────┐
│  ← → ↺  [ https://youtube.com/playlist?list=PL...  ]  [⚙]       │
├──────────────────────────────────────────────────┬───────────────┤
│                                                  │ DETECTED  (1) │
│                                                  │ ┌───────────┐ │
│         YouTube Playlist page                    │ │ 📋 Playlist│ │
│                                                  │ │ Lo-fi Hits │ │
│                                                  │ │ 42 videos  │ │
│                                                  │ │[Browse ▸] │ │
│                                                  │ └───────────┘ │
│                                                  └───────────────┘
│
│  (user clicks Browse ▸ — sidebar switches to Playlist view)
│
│                                                  ┌──────────────────┐
│                                                  │ ← Lo-fi Hits     │
│                                                  │ ☑ Select All     │
│                                                  │ ─────────────    │
│                                                  │ ☑ 1. Rainy Cafe… │
│                                                  │ ☑ 2. Night Drive  │
│                                                  │ ☐ 3. Sleepy Town  │
│                                                  │ ☑ 4. Lo-fi Jazz   │
│                                                  │    …42 total      │
│                                                  │ ─────────────    │
│                                                  │ Quality: [1080p▾]│
│                                                  │ Format:  [MP4  ▾]│
│                                                  │[↓ Download 3 sel]│
│                                                  └──────────────────┘
```

A back arrow (←) at the top of the playlist view returns to the normal DETECTED panel.

### 2.3 Playlist Detection

Detect playlist URL in two places:

**A. Address bar navigation** — in `main.js`, when the BrowserView navigates to a URL, check:
```js
url.includes('youtube.com/playlist?list=') ||
url.includes('youtube.com/watch') && url.includes('list=')
```
If matched, send IPC `playlist:detected` with the URL.

**B. Network interception** — some playlist pages also emit playlist API responses. Detection via A is sufficient for YouTube.

### 2.4 Fetching Playlist Entries

On `playlist:detected`, or when user clicks **Browse Playlist**, the main process runs:

```
yt-dlp \
  --flat-playlist \
  --dump-json \
  --no-warnings \
  <playlistURL>
```

Each stdout line is a JSON object with fields:
```json
{
  "id": "dQw4w9WgXcQ",
  "title": "Rainy Café Ambience",
  "url": "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
  "duration": 3612,
  "thumbnail": "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
  "uploader": "Chilled Cow"
}
```

The renderer receives these entries as `playlist:entry` IPC events streamed one-by-one (so the list appears progressively), followed by `playlist:done`.

### 2.5 Download Selected

When the user clicks **Download Selected**:
1. Collect the `url` values of all checked entries
2. For each checked entry, call `startDownload({ pageURL: entry.url, title: entry.title, formatSelector, outputFormat })` — reusing the exact same download path as single-video downloads
3. Items enter the QUEUE immediately; concurrency limit applies

The playlist view stays visible (user can add more items while downloads are running).

### 2.6 Progress & Completion

No changes needed to the QUEUE / DONE panels — playlist items appear as individual entries identical to normal downloads. The title column shows the individual video title, not the playlist name.

### 2.7 Edge Cases

| Case | Handling |
|------|---------|
| Playlist is private | yt-dlp will error per entry; entry shows "Failed" with retry button |
| Age-restricted video in playlist | User is already logged in inside the browser; yt-dlp uses the same cookies |
| Very large playlist (500+ videos) | Entries streamed progressively; UI renders list lazily (virtual scroll or paginate at 100) |
| `list=` param on a watch URL | App detects both the single video AND the playlist; both appear in sidebar (video card + playlist card) |
| Non-YouTube playlist (e.g., SoundCloud) | Not supported in this version; detection is YouTube-only |

---

## Implementation Plan

### Phase 1 — Audio Extraction (est. 2 days)

| Task | File(s) |
|------|---------|
| Add `Extract Audio` button + audio format dropdown to quality picker HTML | `src/renderer.js` |
| Add `extractAudio` IPC handler in main process — spawn yt-dlp with `-x --audio-format` | `main.js` |
| Add `extractAudio` to preload contextBridge | `preload.js` |
| Wire button click → IPC → queue item in renderer | `src/renderer.js` |
| Show `♪` icon for audio-type completed items in DONE tab | `src/renderer.js`, `src/styles.css` |
| Add `s-audio-format` setting (MP3/AAC/FLAC) to SETTINGS panel | `src/index.html`, `src/renderer.js` |

### Phase 2 — Playlist Downloader (est. 4 days)

| Task | File(s) |
|------|---------|
| Detect playlist URL on navigate and DOM-ready in main process | `main.js` |
| Add `playlist:detected` IPC event + `fetchPlaylist` IPC handler | `main.js`, `preload.js` |
| Playlist card component in DETECTED panel | `src/renderer.js`, `src/styles.css` |
| Playlist panel view (checklist, Select All, quality/format selectors, Download button) | `src/renderer.js`, `src/styles.css` |
| Stream `playlist:entry` events to renderer progressively | `main.js`, `src/renderer.js` |
| "Download Selected" wires into existing `startDownload` path | `src/renderer.js` |
| Back button returns to normal DETECTED panel | `src/renderer.js` |

---

## Settings Additions Summary

| Setting | ID | Default | Panel location |
|---------|----|---------|----------------|
| Default audio format | `s-audio-format` | MP3 | SETTINGS → new "AUDIO" section |
| Audio quality (MP3 kbps) | `s-audio-quality` | 320 | SETTINGS → AUDIO section |

---

## Out of Scope (v2.1)

- Non-YouTube playlists (SoundCloud, Spotify, Apple Music)
- Playlist download scheduling
- Batch audio extraction from a playlist (can be done manually per-video after playlist download completes)
- Audio waveform preview

---

*Spec updated: 2026-05-02 | Version: 2.1 — Audio extraction + Playlist downloader*
