const {
  app, BrowserWindow, BrowserView, ipcMain,
  session, shell, dialog, Notification, nativeTheme
} = require('electron')
const path = require('path')
const { spawn } = require('child_process')
const fs = require('fs')
const os = require('os')

// ---------------------------------------------------------------------------
// Settings store (falls back to simple JSON if electron-store not installed)
// ---------------------------------------------------------------------------
let store
try {
  const Store = require('electron-store')
  store = new Store({
    defaults: {
      defaultQuality: '1080p',
      defaultFormat: 'mp4',
      saveFolder: path.join(os.homedir(), 'Movies', 'NSL Downloads'),
      filenameTemplate: '%(title)s [%(height)sp].%(ext)s',
      maxConcurrentDownloads: 3,
      homepage: 'https://www.youtube.com',
      windowBounds: { width: 1300, height: 820 }
    }
  })
} catch {
  // Fallback: plain JSON file in userData
  const settingsPath = path.join(app.getPath('userData'), 'settings.json')
  const defaults = {
    defaultQuality: '1080p', defaultFormat: 'mp4',
    saveFolder: path.join(os.homedir(), 'Movies', 'NSL Downloads'),
    filenameTemplate: '%(title)s [%(height)sp].%(ext)s',
    maxConcurrentDownloads: 3,
    homepage: 'https://www.youtube.com',
    windowBounds: { width: 1300, height: 820 }
  }
  let data = { ...defaults }
  try { Object.assign(data, JSON.parse(fs.readFileSync(settingsPath, 'utf8'))) } catch {}
  store = {
    get: (k) => data[k],
    set: (k, v) => { data[k] = v; fs.writeFileSync(settingsPath, JSON.stringify(data, null, 2)) },
    store: data
  }
}

// ---------------------------------------------------------------------------
// Globals
// ---------------------------------------------------------------------------
const SIDEBAR_WIDTH = 330
const TOOLBAR_HEIGHT = 50

let mainWindow = null
let browserView = null

// pageURL → { pageURL, pageTitle, favicon, types: Set, timestamp }
const detectedByPage = new Map()

// id → { proc, pageURL, title }
const activeDownloads = new Map()

let downloadIdCounter = 0

// ---------------------------------------------------------------------------
// yt-dlp / ffmpeg binary resolution
// ---------------------------------------------------------------------------
function findBinary(name) {
  // 1. Bundled inside app (production)
  const inResources = path.join(process.resourcesPath || '', 'bin', name)
  if (fs.existsSync(inResources)) return inResources

  // 2. Alongside this file (development: put yt-dlp/ffmpeg in ./bin/)
  const localBin = path.join(__dirname, 'bin', name)
  if (fs.existsSync(localBin)) return localBin

  // 3. Homebrew
  for (const p of [`/opt/homebrew/bin/${name}`, `/usr/local/bin/${name}`, `/usr/bin/${name}`]) {
    if (fs.existsSync(p)) return p
  }

  return name // hope it's in PATH
}

// ---------------------------------------------------------------------------
// Window setup
// ---------------------------------------------------------------------------
function createWindow() {
  const bounds = store.get('windowBounds')

  mainWindow = new BrowserWindow({
    width: bounds.width,
    height: bounds.height,
    minWidth: 960,
    minHeight: 600,
    titleBarStyle: 'hiddenInset',
    trafficLightPosition: { x: 14, y: 16 },
    backgroundColor: nativeTheme.shouldUseDarkColors ? '#1e1e1e' : '#f5f5f5',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      spellcheck: false
    }
  })

  mainWindow.loadFile(path.join(__dirname, 'src', 'index.html'))

  // Embedded browser (separate session so cookies persist independently)
  browserView = new BrowserView({
    webPreferences: {
      nodeIntegration: false,
      contextIsolation: true,
      partition: 'persist:browser',
      spellcheck: false
    }
  })

  mainWindow.setBrowserView(browserView)
  repositionBrowserView()

  browserView.webContents.loadURL(store.get('homepage') || 'https://www.youtube.com')
  browserView.webContents.setWindowOpenHandler(({ url }) => {
    // Open in same BrowserView rather than a new Electron window
    browserView.webContents.loadURL(url)
    return { action: 'deny' }
  })

  // Forward browser events to renderer
  browserView.webContents.on('did-navigate', (_, url) => {
    mainWindow.webContents.send('browser:navigate', url, browserView.webContents.getTitle())
    scanDOMForVideos()
  })
  browserView.webContents.on('did-navigate-in-page', (_, url) => {
    mainWindow.webContents.send('browser:navigate', url, browserView.webContents.getTitle())
  })
  browserView.webContents.on('page-title-updated', (_, title) => {
    mainWindow.webContents.send('browser:title', title)
    // Update cached title for this page
    const url = browserView.webContents.getURL()
    if (detectedByPage.has(url)) detectedByPage.get(url).pageTitle = title
  })
  browserView.webContents.on('did-start-loading', () =>
    mainWindow.webContents.send('browser:loading', true))
  browserView.webContents.on('did-stop-loading', () => {
    mainWindow.webContents.send('browser:loading', false)
    scanDOMForVideos()
  })
  browserView.webContents.on('page-favicon-updated', (_, favicons) => {
    const url = browserView.webContents.getURL()
    if (favicons.length && detectedByPage.has(url)) {
      detectedByPage.get(url).favicon = favicons[0]
    }
  })

  mainWindow.on('resize', () => {
    repositionBrowserView()
    const [w, h] = mainWindow.getSize()
    store.set('windowBounds', { width: w, height: h })
  })

  mainWindow.on('closed', () => { mainWindow = null })
}

function repositionBrowserView() {
  if (!mainWindow || !browserView) return
  const { width, height } = mainWindow.getContentBounds()
  browserView.setBounds({
    x: 0,
    y: TOOLBAR_HEIGHT,
    width: Math.max(0, width - SIDEBAR_WIDTH),
    height: Math.max(0, height - TOOLBAR_HEIGHT)
  })
}

// ---------------------------------------------------------------------------
// Video detection — network interception
// ---------------------------------------------------------------------------
function setupVideoInterception() {
  const browserSession = session.fromPartition('persist:browser')

  browserSession.webRequest.onCompleted({ urls: ['*://*/*'] }, (details) => {
    if (!browserView) return

    const url = details.url
    const contentType = (details.responseHeaders?.['content-type']?.[0] || '').toLowerCase()

    const type = classifyRequest(url, contentType)
    if (!type) return

    const pageURL = browserView.webContents.getURL()
    const pageTitle = browserView.webContents.getTitle()
    recordDetection(pageURL, pageTitle, type)
  })
}

function classifyRequest(url, contentType) {
  // Skip tiny segments and tracking pixels
  if (url.includes('.ts?') || url.match(/\/seg\d+\./)) return null
  if (url.includes('doubleclick') || url.includes('analytics')) return null

  if (url.match(/\.m3u8(\?|$)/i) || contentType.includes('mpegurl')) return 'HLS'
  if (url.match(/\.mpd(\?|$)/i) || contentType.includes('dash+xml')) return 'DASH'
  if (url.includes('googlevideo.com') && url.includes('videoplayback')) return 'YouTube'
  if (url.includes('video.twimg.com') && url.includes('.mp4')) return 'Twitter'
  if (contentType.startsWith('video/') && !url.includes('/seg') && !url.endsWith('.ts')) return 'Video'

  return null
}

function recordDetection(pageURL, pageTitle, type) {
  if (!pageURL || pageURL === 'about:blank' || pageURL.startsWith('devtools://')) return

  const existing = detectedByPage.get(pageURL)
  if (existing) {
    if (existing.types.has(type)) return // already know this type for this page
    existing.types.add(type)
  } else {
    detectedByPage.set(pageURL, {
      pageURL, pageTitle,
      favicon: null,
      types: new Set([type]),
      timestamp: Date.now()
    })
  }

  mainWindow?.webContents.send('video:detected', {
    pageURL,
    pageTitle,
    types: [...(detectedByPage.get(pageURL)?.types || [type])]
  })
}

async function scanDOMForVideos() {
  if (!browserView) return
  try {
    const found = await browserView.webContents.executeJavaScript(`
      (function() {
        return Array.from(document.querySelectorAll('video'))
          .map(v => v.src || v.currentSrc)
          .filter(s => s && s.startsWith('http'))
      })()
    `)
    const pageURL = browserView.webContents.getURL()
    const pageTitle = browserView.webContents.getTitle()
    for (const src of found) {
      const type = classifyRequest(src, '')
      recordDetection(pageURL, pageTitle, type || 'Video')
    }
  } catch { /* cross-origin or CSP blocked */ }
}

// ---------------------------------------------------------------------------
// IPC — browser navigation
// ---------------------------------------------------------------------------
ipcMain.on('browser:go', (_, input) => {
  if (!browserView) return
  let url = input.trim()
  if (!url) return
  if (!url.startsWith('http://') && !url.startsWith('https://')) {
    // Looks like a real domain?
    if (/^[\w-]+(\.[a-z]{2,})+/.test(url) && !url.includes(' ')) {
      url = 'https://' + url
    } else {
      url = 'https://www.google.com/search?q=' + encodeURIComponent(url)
    }
  }
  // Clear old detections when navigating away
  browserView.webContents.loadURL(url)
})

ipcMain.on('browser:back',    () => browserView?.webContents.canGoBack()    && browserView.webContents.goBack())
ipcMain.on('browser:forward', () => browserView?.webContents.canGoForward() && browserView.webContents.goForward())
ipcMain.on('browser:reload',  () => browserView?.webContents.reload())

// ---------------------------------------------------------------------------
// IPC — metadata fetch
// ---------------------------------------------------------------------------
ipcMain.handle('ytdlp:metadata', async (_, pageURL) => {
  return new Promise((resolve, reject) => {
    const bin = findBinary('yt-dlp')
    const proc = spawn(bin, ['--dump-json', '--no-playlist', pageURL])
    let out = '', err = ''
    proc.stdout.on('data', d => { out += d })
    proc.stderr.on('data', d => { err += d })
    proc.on('close', code => {
      const line = out.split('\n').find(l => l.trim().startsWith('{'))
      if (!line) {
        reject(new Error(err.trim() || `yt-dlp exited ${code}`))
        return
      }
      try { resolve(JSON.parse(line)) }
      catch (e) { reject(new Error('Failed to parse yt-dlp JSON')) }
    })
    proc.on('error', reject)
  })
})

// ---------------------------------------------------------------------------
// IPC — download management
// ---------------------------------------------------------------------------
ipcMain.handle('ytdlp:download', async (_, { pageURL, formatSelector, title }) => {
  const id = String(++downloadIdCounter)
  const settings = store.store || {}
  const saveFolder = settings.saveFolder || path.join(os.homedir(), 'Movies', 'NSL Downloads')
  const template = settings.filenameTemplate || '%(title)s [%(height)sp].%(ext)s'
  const mergeFormat = settings.defaultFormat || 'mp4'

  fs.mkdirSync(saveFolder, { recursive: true })
  const outputTemplate = path.join(saveFolder, template)

  const bin = findBinary('yt-dlp')
  const args = [
    '--format', formatSelector,
    '--merge-output-format', mergeFormat,
    '--output', outputTemplate,
    '--newline', '--no-playlist', '--progress',
    '--ffmpeg-location', path.dirname(findBinary('ffmpeg')),
    pageURL
  ]

  const proc = spawn(bin, args)
  activeDownloads.set(id, { proc, pageURL, title })

  proc.stdout.on('data', data => {
    for (const line of data.toString().split('\n')) {
      const p = parseYTDLPLine(line)
      if (p) mainWindow?.webContents.send('download:progress', { id, ...p })
    }
  })

  proc.stderr.on('data', data => {
    const msg = data.toString().trim()
    if (msg) mainWindow?.webContents.send('download:log', { id, msg })
  })

  proc.on('close', code => {
    activeDownloads.delete(id)
    if (code === 0) {
      mainWindow?.webContents.send('download:done', { id })
      try {
        new Notification({
          title: 'Download Complete',
          body: title || 'Your video is ready.'
        }).show()
      } catch {}
    } else {
      mainWindow?.webContents.send('download:failed', { id, code })
    }
  })

  proc.on('error', err => {
    activeDownloads.delete(id)
    mainWindow?.webContents.send('download:failed', { id, error: err.message })
  })

  return id
})

ipcMain.on('ytdlp:cancel', (_, id) => {
  const d = activeDownloads.get(id)
  if (d) { d.proc.kill(); activeDownloads.delete(id) }
})

ipcMain.on('ytdlp:pause', (_, id) => { activeDownloads.get(id)?.proc.kill('SIGSTOP') })
ipcMain.on('ytdlp:resume', (_, id) => { activeDownloads.get(id)?.proc.kill('SIGCONT') })

// ---------------------------------------------------------------------------
// IPC — file system
// ---------------------------------------------------------------------------
ipcMain.on('shell:openFile',   (_, p) => shell.openPath(p))
ipcMain.on('shell:showFolder', (_, p) => shell.showItemInFolder(p))

ipcMain.handle('dialog:chooseFolder', async () => {
  const r = await dialog.showOpenDialog(mainWindow, {
    properties: ['openDirectory', 'createDirectory']
  })
  if (!r.canceled && r.filePaths[0]) {
    store.set('saveFolder', r.filePaths[0])
    return r.filePaths[0]
  }
  return null
})

// ---------------------------------------------------------------------------
// IPC — settings
// ---------------------------------------------------------------------------
ipcMain.handle('settings:get', () => store.store || {
  defaultQuality: store.get('defaultQuality'),
  defaultFormat: store.get('defaultFormat'),
  saveFolder: store.get('saveFolder'),
  filenameTemplate: store.get('filenameTemplate'),
  maxConcurrentDownloads: store.get('maxConcurrentDownloads'),
  homepage: store.get('homepage')
})
ipcMain.on('settings:set', (_, key, value) => store.set(key, value))

// ---------------------------------------------------------------------------
// Progress line parser
// ---------------------------------------------------------------------------
function parseYTDLPLine(line) {
  if (line.includes('[ffmpeg]') || line.includes('[Merger]') || line.includes('Merging formats')) {
    return { status: 'muxing', percent: 100, speed: '', eta: '' }
  }
  if (!line.includes('[download]') || !line.includes('%')) return null

  const pct   = line.match(/(\d+\.?\d*)%/)?.[1]
  const speed = line.match(/at\s+([\d.]+\s*\w+\/s)/)?.[1] || ''
  const eta   = line.match(/ETA\s+([\d:]+)/)?.[1] || ''
  const total = line.match(/of\s+~?([\d.]+\s*\w+)/)?.[1] || ''

  if (!pct) return null
  return { status: 'downloading', percent: parseFloat(pct), speed, eta, total }
}

// ---------------------------------------------------------------------------
// App lifecycle
// ---------------------------------------------------------------------------
app.whenReady().then(() => {
  createWindow()
  setupVideoInterception()

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow()
  })
})

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit()
})
