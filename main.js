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
      windowBounds: { width: 1300, height: 820 },
      defaultAudioFormat: 'mp3',
      audioQuality: '320'
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
    windowBounds: { width: 1300, height: 820 },
    defaultAudioFormat: 'mp3',
    audioQuality: '320'
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
const TOOLBAR_HEIGHT = 80 // Increased to fit tab bar
const SIDEBAR_WIDTH = 330

let mainWindow = null
const browserTabs = new Map() // tabId -> BrowserView
let activeTabId = null
let tabIdCounter = 0

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

  mainWindow.on('resize', () => {
    repositionBrowserView()
    const [w, h] = mainWindow.getSize()
    store.set('windowBounds', { width: w, height: h })
  })

  mainWindow.on('closed', () => { mainWindow = null })
}

function createBrowserTab(url) {
  const tabId = String(++tabIdCounter)
  const view = new BrowserView({
    webPreferences: {
      nodeIntegration: false,
      contextIsolation: true,
      partition: 'persist:browser',
      spellcheck: false
    }
  })
  
  browserTabs.set(tabId, view)
  
  view.webContents.setWindowOpenHandler(({ url: newUrl }) => {
    // Force popups/ads to open in a new tab instead of a new window
    mainWindow?.webContents.send('tab:new-requested', newUrl)
    return { action: 'deny' }
  })

  // Forward browser events to renderer, tagged with tabId
  view.webContents.on('did-navigate', (_, navUrl) => {
    mainWindow?.webContents.send('browser:navigate', { tabId, url: navUrl, title: view.webContents.getTitle() })
    if (tabId === activeTabId) {
      scanDOMForVideos()
      checkForPlaylist(navUrl)
    }
  })
  view.webContents.on('did-navigate-in-page', (_, navUrl) => {
    mainWindow?.webContents.send('browser:navigate', { tabId, url: navUrl, title: view.webContents.getTitle() })
    if (tabId === activeTabId) checkForPlaylist(navUrl)
  })
  view.webContents.on('page-title-updated', (_, title) => {
    mainWindow?.webContents.send('browser:title', { tabId, title })
    const navUrl = view.webContents.getURL()
    if (detectedByPage.has(navUrl)) detectedByPage.get(navUrl).pageTitle = title
  })
  view.webContents.on('did-start-loading', () => {
    mainWindow?.webContents.send('browser:loading', { tabId, loading: true })
  })
  view.webContents.on('did-stop-loading', () => {
    mainWindow?.webContents.send('browser:loading', { tabId, loading: false })
    if (tabId === activeTabId) scanDOMForVideos()
  })
  view.webContents.on('page-favicon-updated', (_, favicons) => {
    const navUrl = view.webContents.getURL()
    if (favicons.length && detectedByPage.has(navUrl)) {
      detectedByPage.get(navUrl).favicon = favicons[0]
    }
  })

  if (url) view.webContents.loadURL(url)
  return tabId
}

function switchBrowserTab(tabId) {
  if (!browserTabs.has(tabId) || !mainWindow) return
  activeTabId = tabId
  const view = browserTabs.get(tabId)
  mainWindow.setBrowserView(view)
  repositionBrowserView()
  
  // Inform renderer of the state of the newly active tab
  mainWindow.webContents.send('browser:navigate', { 
    tabId, 
    url: view.webContents.getURL(), 
    title: view.webContents.getTitle() 
  })
  mainWindow.webContents.send('browser:loading', { 
    tabId, 
    loading: view.webContents.isLoading() 
  })
}

function closeBrowserTab(tabId) {
  if (!browserTabs.has(tabId)) return
  const view = browserTabs.get(tabId)
  if (mainWindow && activeTabId === tabId) {
    mainWindow.removeBrowserView(view)
    activeTabId = null
  }
  // Destroy webContents to free memory
  view.webContents.destroy()
  browserTabs.delete(tabId)
}

function getActiveView() {
  return activeTabId ? browserTabs.get(activeTabId) : null
}

function repositionBrowserView() {
  if (!mainWindow || !activeTabId) return
  const view = browserTabs.get(activeTabId)
  if (!view) return
  const { width, height } = mainWindow.getContentBounds()
  view.setBounds({
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
    // Find which tab made the request
    let requestingTabId = null
    let requestingView = null
    for (const [tId, view] of browserTabs.entries()) {
      if (view.webContents.id === details.webContentsId) {
        requestingTabId = tId
        requestingView = view
        break
      }
    }
    
    if (!requestingView) return

    const url = details.url
    const contentType = (details.responseHeaders?.['content-type']?.[0] || '').toLowerCase()

    const type = classifyRequest(url, contentType)
    if (!type) return

    const pageURL = requestingView.webContents.getURL()
    const pageTitle = requestingView.webContents.getTitle()
    
    // Only send IPC if this is the active tab
    recordDetection(pageURL, pageTitle, type, requestingTabId === activeTabId)
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
  const view = getActiveView()
  if (!view) return
  try {
    const found = await view.webContents.executeJavaScript(`
      (function() {
        return Array.from(document.querySelectorAll('video'))
          .map(v => v.src || v.currentSrc)
          .filter(s => s && s.startsWith('http'))
      })()
    `)
    const pageURL = view.webContents.getURL()
    const pageTitle = view.webContents.getTitle()
    for (const src of found) {
      const type = classifyRequest(src, '')
      recordDetection(pageURL, pageTitle, type || 'Video')
    }
  } catch { /* cross-origin or CSP blocked */ }
}

// ---------------------------------------------------------------------------
// Playlist detection
// ---------------------------------------------------------------------------
function checkForPlaylist(url) {
  if (!url || !mainWindow) return
  const isPlaylist = url.includes('youtube.com/playlist?list=') ||
    (url.includes('youtube.com/watch') && url.includes('list='))
  if (!isPlaylist) return

  // Extract playlist ID and basic info
  let listId = ''
  try {
    const u = new URL(url)
    listId = u.searchParams.get('list') || ''
  } catch {}

  mainWindow.webContents.send('playlist:detected', { url, listId })
}

// ---------------------------------------------------------------------------
// IPC — browser navigation
// ---------------------------------------------------------------------------
ipcMain.on('browser:go', (_, input) => {
  const view = getActiveView()
  if (!view) return
  let url = input.trim()
  if (!url) return
  
  // Quick fix: assume domain if no protocol and no spaces
  if (!url.startsWith('http://') && !url.startsWith('https://') && !url.startsWith('file://')) {
    if (url.includes(' ') || !url.includes('.')) {
      url = `https://duckduckgo.com/?q=${encodeURIComponent(url)}`
    } else {
      url = 'https://' + url
    }
  }
  view.webContents.loadURL(url)
})

ipcMain.on('browser:back',    () => getActiveView()?.webContents.canGoBack()    && getActiveView().webContents.goBack())
ipcMain.on('browser:forward', () => getActiveView()?.webContents.canGoForward() && getActiveView().webContents.goForward())
ipcMain.on('browser:reload',  () => getActiveView()?.webContents.reload())

ipcMain.handle('tab:create', (_, url) => {
  const tabId = createBrowserTab(url || store.store?.homepage || 'https://www.youtube.com')
  return tabId
})

ipcMain.on('tab:switch', (_, tabId) => {
  switchBrowserTab(tabId)
})

ipcMain.on('tab:close', (_, tabId) => {
  closeBrowserTab(tabId)
})

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
// IPC — audio extraction
// ---------------------------------------------------------------------------
ipcMain.handle('ytdlp:extractAudio', async (_, { pageURL, audioFormat, title, filePrefix }) => {
  const id = String(++downloadIdCounter)
  const settings = store.store || {}
  const saveFolder = settings.saveFolder || path.join(os.homedir(), 'Movies', 'NSL Downloads')
  const audioQuality = settings.audioQuality || '320'

  fs.mkdirSync(saveFolder, { recursive: true })
  let template = '%(title)s.%(ext)s'
  if (filePrefix) template = `${filePrefix} - ${template}`
  const outputTemplate = path.join(saveFolder, template)

  const bin = findBinary('yt-dlp')
  const args = [
    '-f', 'bestaudio',
    '-x',
    '--audio-format', audioFormat || 'mp3',
    '--audio-quality', audioQuality === '320' ? '0' : audioQuality,
    '--ffmpeg-location', path.dirname(findBinary('ffmpeg')),
    '--output', outputTemplate,
    '--newline',
    pageURL
  ]

  const proc = spawn(bin, args)
  activeDownloads.set(id, { proc, pageURL, title, isAudio: true })
  let capturedFilePath = ''

  proc.stdout.on('data', data => {
    for (const line of data.toString().split('\n')) {
      const fp = parseFilePath(line)
      if (fp) capturedFilePath = fp
      const p = parseYTDLPLine(line, true)
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
      mainWindow?.webContents.send('download:done', { id, isAudio: true, filePath: capturedFilePath })
      try {
        new Notification({
          title: 'Audio Extraction Complete',
          body: title || 'Your audio file is ready.'
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

// ---------------------------------------------------------------------------
// IPC — playlist fetch
// ---------------------------------------------------------------------------
ipcMain.handle('playlist:fetch', async (_, playlistURL) => {
  return new Promise((resolve, reject) => {
    const bin = findBinary('yt-dlp')
    const args = ['--flat-playlist', '--dump-json', '--no-warnings', playlistURL]
    const proc = spawn(bin, args)
    let count = 0

    proc.stdout.on('data', data => {
      const lines = data.toString().split('\n')
      for (const line of lines) {
        const trimmed = line.trim()
        if (!trimmed.startsWith('{')) continue
        try {
          const entry = JSON.parse(trimmed)
          count++
          mainWindow?.webContents.send('playlist:entry', {
            id: entry.id,
            title: entry.title || `Video ${count}`,
            url: entry.url || entry.webpage_url || `https://www.youtube.com/watch?v=${entry.id}`,
            duration: entry.duration,
            thumbnail: entry.thumbnail,
            uploader: entry.uploader || entry.channel
          })
        } catch {}
      }
    })

    proc.stderr.on('data', () => {}) // suppress warnings

    proc.on('close', code => {
      mainWindow?.webContents.send('playlist:done', { count })
      resolve({ count })
    })

    proc.on('error', reject)
  })
})

// ---------------------------------------------------------------------------
// IPC — download management
// ---------------------------------------------------------------------------
ipcMain.handle('ytdlp:download', async (_, { pageURL, formatSelector, title, filePrefix }) => {
  const id = String(++downloadIdCounter)
  const settings = store.store || {}
  const saveFolder = settings.saveFolder || path.join(os.homedir(), 'Movies', 'NSL Downloads')
  let template = settings.filenameTemplate || '%(title)s [%(height)sp].%(ext)s'
  if (filePrefix) template = `${filePrefix} - ${template}`
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
  let capturedFilePath = ''

  proc.stdout.on('data', data => {
    for (const line of data.toString().split('\n')) {
      const fp = parseFilePath(line)
      if (fp) capturedFilePath = fp
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
      mainWindow?.webContents.send('download:done', { id, filePath: capturedFilePath })
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
  homepage: store.get('homepage'),
  defaultAudioFormat: store.get('defaultAudioFormat'),
  audioQuality: store.get('audioQuality')
})
ipcMain.on('settings:set', (_, key, value) => store.set(key, value))

// ---------------------------------------------------------------------------
// Progress line parser
// ---------------------------------------------------------------------------
// ---------------------------------------------------------------------------
// File path extractor
// ---------------------------------------------------------------------------
function parseFilePath(line) {
  // "[download] Destination: /path/to/file.mp4"
  const dest = line.match(/\[download\]\s+Destination:\s+(.+)/)
  if (dest) return dest[1].trim()

  // "[Merger] Merging formats into "/path/to/file.mp4""
  // "[ffmpeg] Destination: /path/to/file.mp3"
  const merger = line.match(/(?:\[Merger\].*into|(?:\[ffmpeg\]|\[ExtractAudio\]).*Destination:)\s+"?(.+?)"?\s*$/)
  if (merger) return merger[1].trim()

  return null
}

function parseYTDLPLine(line, isAudio = false) {
  if (line.includes('[ffmpeg]') || line.includes('[Merger]') || line.includes('Merging formats')) {
    return { status: isAudio ? 'extracting' : 'muxing', percent: 100, speed: '', eta: '' }
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
