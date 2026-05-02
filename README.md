# lertumpai-downloader

A desktop browser that automatically detects and downloads videos from any website. Browse to YouTube, Facebook, VK, TikTok, or any site — the app intercepts video streams in the background and shows a **Download** button in the sidebar. No URL copying needed. Login to private content works normally inside the embedded browser.

Built with Electron + yt-dlp.

---

## Requirements

| Tool | Version | Install |
|------|---------|---------|
| Node.js | 18 or later | [nodejs.org](https://nodejs.org) or `brew install node` |
| yt-dlp | latest | `brew install yt-dlp` |
| FFmpeg | latest | `brew install ffmpeg` |

Verify all three are available:

```bash
node --version      # v18+
yt-dlp --version
ffmpeg -version
```

---

## Run in development

```bash
# 1. Clone
git clone <repo-url>
cd nsl-download-mac

# 2. Install dependencies
brew install aria2 yt-dlp ffmpeg
npm install

# 3. Launch
npm start
```

The app opens a browser window. Navigate to any video site, and detected videos appear automatically in the right sidebar under **DETECTED**.

---

## How to use

1. **Browse** — type a URL or search term in the address bar (works like a normal browser)
2. **Watch the sidebar** — when a video stream is detected, a card appears in the **DETECTED** tab
3. **Click ↓ Download** — choose quality from the picker that appears inline
4. **Track progress** in the **QUEUE** tab; open the file from the **DONE** tab when finished

> **Login-required videos**: just log in to the site normally inside the app. The browser session persists between launches, so you only need to log in once.

---

## Build a distributable DMG

### Prerequisites

You need an Apple Developer account to sign and notarize the app. Without signing, the app will show a Gatekeeper warning on other machines (fine for personal use).

```bash
npm install          # if not already done
```

### Bundle yt-dlp and FFmpeg (required for distribution)

Download self-contained universal binaries so users don't need to install anything:

```bash
mkdir -p bin

# yt-dlp universal binary
curl -L https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_macos \
     -o bin/yt-dlp
chmod +x bin/yt-dlp

# FFmpeg static universal binary — download from evermeet.cx
# (replace the URL with the latest release from https://evermeet.cx/ffmpeg/)
curl -L https://evermeet.cx/ffmpeg/ffmpeg-7.1.zip -o /tmp/ffmpeg.zip
unzip /tmp/ffmpeg.zip -d bin/
chmod +x bin/ffmpeg
```

Verify the binaries work:

```bash
bin/yt-dlp --version
bin/ffmpeg -version
```

The `electron-builder` config in `package.json` copies everything in `bin/` into `Resources/bin/` inside the app bundle automatically.

### Build unsigned (personal use)

```bash
npm run build
```

Output: `dist/NSL Download-1.0.0.dmg` (and a `.zip` for auto-update).

### Build signed + notarized (distribution to others)

Set your Apple credentials as environment variables first:

```bash
export APPLE_ID="your@apple.id"
export APPLE_APP_SPECIFIC_PASSWORD="xxxx-xxxx-xxxx-xxxx"  # app-specific password from appleid.apple.com
export APPLE_TEAM_ID="XXXXXXXXXX"                          # 10-char team ID from developer.apple.com
```

Open `package.json` and add your team ID to the `mac` section:

```json
"mac": {
  "identity": "Developer ID Application: Your Name (XXXXXXXXXX)",
  ...
}
```

Then build:

```bash
npm run build:universal
```

`electron-builder` will sign, notarize, and staple the app automatically. This takes a few minutes due to Apple's notarization service.

---

## Project structure

```
nsl-download-mac/
├── main.js           Electron main process
│                     — BrowserView, request interception, yt-dlp spawning
├── preload.js        contextBridge IPC between main and renderer
├── src/
│   ├── index.html    App shell (toolbar + sidebar)
│   ├── renderer.js   UI logic (detection cards, quality picker, queue)
│   └── styles.css    Light/dark mode styling
├── bin/              Put yt-dlp and ffmpeg here for bundled builds
├── entitlements.mac.plist  Hardened runtime entitlements
└── package.json      Electron + electron-builder config
```

---

## Settings

Click **⚙** in the toolbar or open the **SETTINGS** tab in the sidebar.

| Setting | Default | Notes |
|---------|---------|-------|
| Default quality | 1080p | Pre-selected in quality picker |
| Default format | MP4 | Output container (MP4 / MKV / WebM) |
| Save folder | `~/Movies/NSL Downloads` | Created automatically on first download |
| Filename template | `%(title)s [%(height)sp].%(ext)s` | yt-dlp output template syntax |
| Concurrent downloads | 3 | Max parallel downloads |
| Homepage | `https://www.youtube.com` | Browser start page |

---

## Troubleshooting

**Sidebar shows nothing after visiting a video page**

Some sites load video through JavaScript after the initial page load. Wait a few seconds for the video to start playing — the detection fires when the browser makes the actual stream request.

**"yt-dlp not found" error on download**

Either install yt-dlp (`brew install yt-dlp`) or place the binary at `bin/yt-dlp` next to `main.js`.

**Video downloads at low quality**

Click **Analyse** (instead of Download) on a detected card. This forces a full `yt-dlp --dump-json` pass and shows all available formats including ones not visible in network traffic.

**YouTube shows age-restricted or login page**

Log in to YouTube inside the app. The session is stored in a separate browser partition (`persist:browser`) and persists across launches.

**App is slow or uses too much memory**

Electron bundles a full Chromium engine — ~300 MB on disk and ~200 MB RAM idle is normal. This is the trade-off for having a real embedded browser.
