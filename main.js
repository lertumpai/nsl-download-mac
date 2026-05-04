const {
  app, BrowserWindow, BrowserView, ipcMain, Menu,
  session, shell, dialog, Notification, nativeTheme
} = require('electron')
const path = require('path')
const { spawn, spawnSync } = require('child_process')
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
      homepage: 'https://www.google.com',
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
    homepage: 'https://www.google.com',
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
let playerVisible = false

// ---------------------------------------------------------------------------
// yt-dlp / ffmpeg binary resolution
// ---------------------------------------------------------------------------
function findBinary(name) {
  // 0. Explicit override from environment
  if (process.env.YTDLP_PATH && fs.existsSync(process.env.YTDLP_PATH)) return process.env.YTDLP_PATH

  // 1. Prefer a PATH-installed binary if available
  try {
    const whichResult = spawnSync('which', [name], { encoding: 'utf8' }).stdout.trim()
    if (whichResult && fs.existsSync(whichResult)) return whichResult
  } catch {}

  // 2. Bundled inside app (production)
  const inResources = path.join(process.resourcesPath || '', 'bin', name)
  if (fs.existsSync(inResources)) return inResources

  // 3. Alongside this file (development: put yt-dlp/ffmpeg in ./bin/)
  const localBin = path.join(__dirname, 'bin', name)
  if (fs.existsSync(localBin)) return localBin

  // 4. Homebrew / system paths
  for (const p of [`/opt/homebrew/bin/${name}`, `/usr/local/bin/${name}`, `/usr/bin/${name}`]) {
    if (fs.existsSync(p)) return p
  }

  return name // hope it's in PATH
}

// ---------------------------------------------------------------------------
// Download speed helpers
// ---------------------------------------------------------------------------
let _aria2cPath = undefined
function findAria2c() {
  if (_aria2cPath !== undefined) return _aria2cPath
  try {
    const r = spawnSync('which', ['aria2c'], { encoding: 'utf8' })
    if (r.status === 0 && r.stdout.trim()) { _aria2cPath = r.stdout.trim(); return _aria2cPath }
  } catch {}
  for (const p of ['/opt/homebrew/bin/aria2c', '/usr/local/bin/aria2c', '/usr/bin/aria2c']) {
    if (fs.existsSync(p)) { _aria2cPath = p; return _aria2cPath }
  }
  _aria2cPath = ''
  return _aria2cPath
}

function getDownloadSpeedArgs() {
  const args = [
    '--concurrent-fragments', '16',  // parallel HLS/DASH segment downloads (was 4)
    '--retries', '10',
    '--fragment-retries', '10',
    '--buffer-size', '16M',           // larger write buffer reduces I/O stalls
    '--socket-timeout', '15',         // don't hang on unresponsive connections
  ]
  if (findAria2c()) {
    // aria2c for plain http/https only — m3u8/dash segment fetches stay on yt-dlp
    // native downloader which respects --concurrent-fragments above.
    args.push(
      '--downloader', 'aria2c:http,https',
      '--downloader-args', 'aria2c:-x 16 -s 16 -k 1M --file-allocation=none --optimize-concurrent-downloads=true'
    )
  } else {
    // Native downloader fallback: split large HTTP files into 10 MB chunks
    // so yt-dlp can download them in parallel ranges.
    args.push('--http-chunk-size', '10M')
  }
  return args
}

function normalizeVKUrl(url) {
  if (!url) return url
  try {
    const u = new URL(url)
    if (u.hostname.includes('vk.com') || u.hostname.includes('vkvideo.')) {
      const zParam = u.searchParams.get('z')
      if (zParam && zParam.startsWith('video')) {
        const videoId = zParam.split('/')[0]
        return `https://vk.com/${videoId}`
      }
      const pathMatch = u.pathname.match(/video[-\d_]+/)
      if (pathMatch) return `https://vk.com/${pathMatch[0]}`
      const queryMatch = u.search.match(/video[-\d_]+/)
      if (queryMatch) return `https://vk.com/${queryMatch[0]}`
      const hashMatch = u.hash.match(/video[-\d_]+/)
      if (hashMatch) return `https://vk.com/${hashMatch[0]}`
    }
  } catch {}
  return url
}

function getTargetUrl(pageURL, streamUrl) {
  let targetUrl = pageURL
  const normalizedPage = normalizeVKUrl(pageURL)
  
  if (normalizedPage && normalizedPage.match(/^https?:\/\/vk\.com\/(video[-\d_]+|video_ext\.php)/)) {
    targetUrl = normalizedPage
  } else if (streamUrl) {
    targetUrl = streamUrl
  }
  
  return normalizeVKUrl(targetUrl)
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

// ---------------------------------------------------------------------------
// Video overlay injection script — hover a <video> element to see the button
// ---------------------------------------------------------------------------
// Runs once per page via executeJavaScript — sets up hover buttons for all player types.
const VIDEO_OVERLAY_SCRIPT = `;(function(){
  if(window.__nslInjected)return;window.__nslInjected=true;

  /* ── shared helpers ── */
  var BTN_CSS='position:fixed;z-index:2147483647;background:rgba(0,0,0,.85);color:#fff;border:none;border-radius:18px;padding:5px 14px;font-size:12px;font-weight:600;cursor:pointer;display:none;pointer-events:auto;box-shadow:0 2px 14px rgba(0,0,0,.55);';
  function mkBtn(label){var b=document.createElement('button');b.textContent=label;b.style.cssText=BTN_CSS;(document.body||document.documentElement).appendChild(b);return b;}
  function posAt(b,el,e){var r=el.getBoundingClientRect();b.style.top=Math.max(8,r.top+10)+'px';b.style.right=Math.max(8,window.innerWidth-r.right+10)+'px';}
  function hover(el,b,getUrl){
    var t=null;
    el.addEventListener('mouseenter',function(){clearTimeout(t);posAt(b,el);b.style.display='block';});
    el.addEventListener('mouseleave',function(e){if(e.relatedTarget!==b)t=setTimeout(function(){b.style.display='none';},220);});
    b.addEventListener('mouseenter',function(){clearTimeout(t);});
    b.addEventListener('mouseleave',function(){t=setTimeout(function(){b.style.display='none';},220);});
    b.addEventListener('click',function(e){e.stopPropagation();var u=getUrl();if(u&&window.__nsl)window.__nsl.requestDownload(u,location.href,document.title);b.style.display='none';});
  }

  /* ── URL extractors (bypass blob: by reading player API) ── */
  function jwUrl(){try{var jw=window.jwplayer&&window.jwplayer();if(!jw||!jw.getPlaylistItem)return null;var item=jw.getPlaylistItem();if(!item)return null;var srcs=item.allSources||item.sources||[];var best=srcs.find(function(x){return x.file&&/\\.m3u8/i.test(x.file);})||srcs.find(function(x){return x.file&&x.file.startsWith('http');});return(best&&best.file)||item.file||null;}catch(e){return null;}}
  function artUrl(){try{if(!window.Artplayer||!window.Artplayer.instances||!window.Artplayer.instances.length)return null;var a=window.Artplayer.instances[0];return(a.url)||(a.option&&a.option.url)||null;}catch(e){return null;}}
  function vjsUrl(){try{if(!window.videojs||!window.videojs.players)return null;var p=Object.values(window.videojs.players).find(function(x){return x;});var s=p&&p.currentSrc&&p.currentSrc();return(s&&s.startsWith('http'))?s:null;}catch(e){return null;}}
  function vidElUrl(v){var s=v?(v.currentSrc||v.src||''):'';return(s&&!s.startsWith('blob:')&&s.startsWith('http'))?s:null;}

  /* ── <video> element hover button ── */
  var vBtn=mkBtn('\\u2b07 Download Video');
  var cur=null;
  function attachVideo(v){
    if(v.__nslDone)return;v.__nslDone=true;
    hover(v,vBtn,function(){return jwUrl()||artUrl()||vjsUrl()||vidElUrl(cur);});
    v.addEventListener('mouseenter',function(){cur=v;});
  }
  document.querySelectorAll('video').forEach(attachVideo);

  /* ── JW Player fixed-position button (avoids overflow:hidden clipping) ── */
  function injectJWBtn(){
    try{var jw=window.jwplayer&&window.jwplayer();if(!jw||!jw.getContainer)return;var c=jw.getContainer();if(!c||c.__nslJW)return;c.__nslJW=true;var b=mkBtn('\\u2b07 Download');hover(c,b,jwUrl);}catch(e){}
  }

  /* ── ArtPlayer — native controls API first, fixed-pos fallback ── */
  function injectArtBtn(){
    try{
      if(!window.Artplayer||!window.Artplayer.instances)return;
      window.Artplayer.instances.forEach(function(art){
        if(art.__nslDone)return;art.__nslDone=true;
        try{
          art.controls.add({name:'nsl-dl',position:'right',html:'<span style="font-size:19px;line-height:1;cursor:pointer;opacity:.9;padding:0 4px" title="Download">\\u2b07</span>',
            click:function(){var u=artUrl();if(u&&window.__nsl)window.__nsl.requestDownload(u,location.href,document.title);}});
        }catch(e){
          var c=art.template&&(art.template.player||art.template.wrap);
          if(c){var b=mkBtn('\\u2b07 Download');hover(c,b,artUrl);}
        }
      });
    }catch(e){}
  }

  /* ── setup ── */
  setTimeout(injectJWBtn,500);setTimeout(injectJWBtn,2500);
  setTimeout(injectArtBtn,500);setTimeout(injectArtBtn,2500);

  new MutationObserver(function(ms){
    ms.forEach(function(m){m.addedNodes.forEach(function(n){
      if(n.tagName==='VIDEO')attachVideo(n);
      else if(n.querySelectorAll)n.querySelectorAll('video').forEach(attachVideo);
    });});
    injectJWBtn();injectArtBtn();
  }).observe(document.documentElement,{childList:true,subtree:true});
})();`

// Runs via executeJavaScript multiple times — returns array of video URLs found in any player API.
// Safe to run repeatedly; never modifies the DOM.
const PLAYER_DETECT_SCRIPT = `(function(){
  var urls=[];
  function add(u){if(u&&typeof u==='string'&&u.startsWith('http')&&!u.startsWith('blob:'))urls.push(u);}
  // JW Player
  try{var jw=window.jwplayer&&window.jwplayer();if(jw&&jw.getPlaylist){[jw.getPlaylistItem&&jw.getPlaylistItem()].concat(jw.getPlaylist()||[]).filter(Boolean).forEach(function(item){(item.allSources||item.sources||[]).forEach(function(s){add(s.file);});add(item.file);});}}catch(e){}
  // ArtPlayer
  try{if(window.Artplayer&&window.Artplayer.instances)window.Artplayer.instances.forEach(function(a){add(a.url);add(a.option&&a.option.url);});}catch(e){}
  // Video.js
  try{if(window.videojs&&window.videojs.players)Object.values(window.videojs.players).forEach(function(p){if(!p)return;add(p.currentSrc&&p.currentSrc());(p.currentSources?p.currentSources():[]).forEach(function(s){add(s.src);});});}catch(e){}
  // Common p2p/stream config objects
  ['playerConfig','_playerConfig','p2pConfig','streamConfig','videoConfig','nplayer_config','playerVars','_nativeConfig'].forEach(function(k){try{var c=window[k];if(!c)return;add(c.url||c.src||c.hls||c.stream||c.source||c.videoUrl||c.hlsUrl||c.video_url);}catch(e){}});
  // Direct <video> src (only if not blob:)
  document.querySelectorAll('video').forEach(function(v){add(v.currentSrc);add(v.src);});
  return[...new Set(urls)];
})()`

function handleVideoDownloadRequest(videoUrl, pageUrl, pageTitle) {
  if (!videoUrl || !pageUrl) return
  // Record the video URL as a detected stream on this page
  recordDetection(pageUrl, pageTitle || 'Video', 'Video', videoUrl)
  // Tell renderer to switch to detected tab and auto-open quality picker
  mainWindow?.webContents.send('video:auto-pick', { pageURL: pageUrl, pageTitle: pageTitle || '' })
}

function createBrowserTab(url) {
  const tabId = String(++tabIdCounter)
  const view = new BrowserView({
    webPreferences: {
      nodeIntegration: false,
      contextIsolation: true,
      partition: 'persist:browser',
      preload: path.join(__dirname, 'preload-browser.js'),
      spellcheck: false
    }
  })
  
  browserTabs.set(tabId, view)
  
  view.webContents.on('console-message', (_, _level, message) => {
    if (message.startsWith('[NSL]') || message.startsWith('[NSL capture]')) {
      console.log('[BrowserTab]', message)
    }
  })

  view.webContents.setWindowOpenHandler(({ url: newUrl }) => {
    // Force popups/ads to open in a new tab instead of a new window
    mainWindow?.webContents.send('tab:new-requested', newUrl)
    return { action: 'deny' }
  })

  // Right-click context menu — always show download option; scan player APIs if no direct srcURL
  view.webContents.on('context-menu', (_, params) => {
    const items = []
    items.push({
      label: '↓ Download Video with NSL',
      click: async () => {
        let url = (params.mediaType === 'video' && params.srcURL) ? params.srcURL : null
        if (!url) {
          try {
            const found = await view.webContents.executeJavaScript(PLAYER_DETECT_SCRIPT)
            url = Array.isArray(found) && found[0]
          } catch {}
        }
        if (url) handleVideoDownloadRequest(url, view.webContents.getURL(), view.webContents.getTitle())
      }
    })
    items.push({ type: 'separator' })
    if (params.linkURL) items.push({ label: 'Open Link in New Tab', click: () => mainWindow?.webContents.send('tab:new-requested', params.linkURL) })
    if (params.selectionText) items.push({ label: 'Copy', role: 'copy' })
    items.push({ label: 'Back', click: () => view.webContents.canGoBack() && view.webContents.goBack() })
    items.push({ label: 'Reload', click: () => view.webContents.reload() })
    Menu.buildFromTemplate(items).popup({ window: mainWindow })
  })

  // Inject overlay download button after each page finishes loading + periodic player API scan
  view.webContents.on('did-stop-loading', () => {
    view.webContents.executeJavaScript(VIDEO_OVERLAY_SCRIPT).catch(() => {})
    // Players like JW Player / ArtPlayer may init 1-8s after the page loads
    ;[1000, 3000, 8000].forEach(delay => {
      setTimeout(async () => {
        if (!view.webContents || view.webContents.isDestroyed()) return
        try {
          const found = await view.webContents.executeJavaScript(PLAYER_DETECT_SCRIPT)
          if (!Array.isArray(found) || !found.length) return
          const currentUrl = view.webContents.getURL()
          const currentTitle = view.webContents.getTitle()
          for (const u of found) {
            recordDetection(currentUrl, currentTitle, classifyRequest(u, '') || 'Video', u)
          }
        } catch {}
      }, delay)
    })
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
  if (playerVisible) return
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
      if (view.webContents && view.webContents.id === details.webContentsId) {
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

    if (!requestingView.webContents) return

    const pageURL = requestingView.webContents.getURL()
    const pageTitle = requestingView.webContents.getTitle()
    
    recordDetection(pageURL, pageTitle, type, url)
  })
}

function classifyRequest(url, contentType) {
  // Skip tiny segments and tracking pixels
  if (url.includes('doubleclick') || url.includes('analytics') || url.includes('googletagmanager')) return null
  // Skip TS segments (individual chunks, not the manifest)
  if (url.match(/\/seg[-_]?\d+\.ts(\?|$)/i) || url.match(/[_-]\d{4,}\.ts(\?|$)/i)) return null

  const normType = (contentType || '').toLowerCase()

  if (url.match(/\.m3u8(\?|$)/i) || normType.includes('mpegurl') || normType.includes('x-mpegurl')) return 'HLS'
  if (url.match(/\.mpd(\?|$)/i) || normType.includes('dash+xml')) return 'DASH'
  if (url.includes('googlevideo.com') && url.includes('videoplayback')) return 'YouTube'
  if (url.includes('video.twimg.com') && url.includes('.mp4')) return 'Twitter'
  // JW Player CDN delivery (cdn.jwplayer.com or jwpcdn.com)
  if ((url.includes('jwpcdn.com') || url.includes('cdn.jwplayer.com')) && url.match(/\.(m3u8|mp4)(\?|$)/i)) return 'Video'
  if (url.match(/\.(mp4|webm|mkv|mov|ogg|ogv)(\?|$)/i)) return 'Video'
  if (normType.startsWith('video/') && !url.endsWith('.ts')) return 'Video'
  if (normType.includes('application/octet-stream') && url.match(/\.(mp4|webm|mkv|mov|ogg|ogv|m3u8|mpd)(\?|$)/i)) return 'Video'

  return null
}

function recordDetection(pageURL, pageTitle, type, streamUrl = null) {
  if (!pageURL || pageURL === 'about:blank' || pageURL.startsWith('devtools://')) return

  const existing = detectedByPage.get(pageURL)
  if (existing) {
    if (streamUrl && (type === 'HLS' || type === 'DASH' || type === 'Video')) {
      if (!existing.streamUrl) {
        existing.streamUrl = streamUrl
      } else {
        const newIsVK = normalizeVKUrl(streamUrl).match(/^https?:\/\/vk\.com\/(video[-\d_]+|video_ext\.php)/)
        const oldIsVK = normalizeVKUrl(existing.streamUrl).match(/^https?:\/\/vk\.com\/(video[-\d_]+|video_ext\.php)/)
        if (newIsVK && !oldIsVK) {
          existing.streamUrl = streamUrl
        }
      }
    }
    if (existing.types.has(type)) return // already know this type for this page
    existing.types.add(type)
  } else {
    detectedByPage.set(pageURL, {
      pageURL, pageTitle,
      favicon: null,
      types: new Set([type]),
      timestamp: Date.now(),
      streamUrl: streamUrl || null
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

  const pageURL = view.webContents.getURL()
  const pageTitle = view.webContents.getTitle()

  // Immediately detect if the page URL itself is a canonical video URL
  const isYouTube = pageURL.includes('youtube.com/watch')
  const isTwitter = pageURL.match(/^https?:\/\/(www\.)?(twitter|x)\.com\/.*\/status\/\d+/)
  const isVK = normalizeVKUrl(pageURL).match(/^https?:\/\/vk\.com\/(video[-\d_]+|video_ext\.php)/)

  if (isYouTube) recordDetection(pageURL, pageTitle, 'YouTube', pageURL)
  else if (isTwitter) recordDetection(pageURL, pageTitle, 'Twitter', pageURL)
  else if (isVK) recordDetection(pageURL, pageTitle, 'Video', pageURL)

  try {
    const found = await view.webContents.executeJavaScript(`
      (function() {
        const urls = new Set()

        // Standard HTML elements
        document.querySelectorAll('video, source, iframe, a, [data-src], [data-href]').forEach(el => {
          const src = el.src || el.currentSrc || el.getAttribute('src') || el.getAttribute('href') || el.getAttribute('data-src') || el.getAttribute('data-href')
          if (src && src.startsWith('http')) urls.add(src)
        })

        // JW Player — reads actual source URLs from player API (bypasses blob: delivery)
        if (window.jwplayer) {
          try {
            const jw = window.jwplayer()
            if (jw && jw.getPlaylist) {
              const playlist = jw.getPlaylist() || []
              const current = jw.getPlaylistItem ? jw.getPlaylistItem() : null
              ;[current, ...playlist].filter(Boolean).forEach(item => {
                ;(item.allSources || item.sources || []).forEach(s => {
                  if (s.file && s.file.startsWith('http')) urls.add(s.file)
                })
                if (item.file && item.file.startsWith('http')) urls.add(item.file)
              })
            }
          } catch(e) {}
        }

        // ArtPlayer
        if (window.Artplayer && window.Artplayer.instances) {
          try {
            window.Artplayer.instances.forEach(a => {
              const u = a.url || (a.option && a.option.url)
              if (u && typeof u === 'string' && u.startsWith('http')) urls.add(u)
            })
          } catch(e) {}
        }

        // Video.js
        if (window.videojs && window.videojs.players) {
          try {
            Object.values(window.videojs.players).forEach(p => {
              if (!p) return
              const src = p.currentSrc && p.currentSrc()
              if (src && src.startsWith('http')) urls.add(src)
              ;(p.currentSources ? p.currentSources() : []).forEach(s => {
                if (s.src && s.src.startsWith('http')) urls.add(s.src)
              })
            })
          } catch(e) {}
        }

        // Common player config objects
        ;['playerConfig','_playerConfig','p2pConfig','__streamConfig','streamConfig',
          'videoConfig','nplayer_config','playerVars','_nativeConfig'].forEach(k => {
          try {
            const c = window[k]; if (!c) return
            const u = c.url || c.src || c.hls || c.stream || c.source || c.videoUrl || c.hlsUrl || c.video_url
            if (u && typeof u === 'string' && u.startsWith('http')) urls.add(u)
          } catch(e) {}
        })

        // VK video URLs embedded in script tags
        const vkRegex = /(https?:)?\/\/(?:m\.)?vk\.com\/(?:video[-\d_]+|video_ext\.php\?)[^"'\s]+/g
        document.querySelectorAll('script').forEach(script => {
          const text = script.textContent || ''
          let match
          while ((match = vkRegex.exec(text))) {
            let url = match[0]
            if (url.startsWith('//')) url = 'https:' + url
            urls.add(url)
          }
        })

        const vkVideoPageRegex = /(https?:)?\/\/(?:www\.)?vkvideo\.ru\/video[-\d_]+/g
        document.body.innerHTML.match(vkVideoPageRegex || [])?.forEach(url => {
          if (url.startsWith('//')) url = 'https:' + url
          urls.add(url)
        })

        return Array.from(urls)
      })()
    `)
    for (const src of found) {
      const type = classifyRequest(src, '')
      recordDetection(pageURL, pageTitle, type || 'Video', src)
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
      url = `https://www.google.com/search?q=${encodeURIComponent(url)}`
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
  const tabId = createBrowserTab(url || store.get('homepage') || 'https://www.google.com')
  if (!activeTabId) switchBrowserTab(tabId)
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
  const cookiesPath = await exportCookies()
  return new Promise((resolve, reject) => {
    const bin = findBinary('yt-dlp')
    const pageData = detectedByPage.get(pageURL)
    const targetUrl = getTargetUrl(pageURL, pageData && pageData.streamUrl ? pageData.streamUrl : null)

    const args = ['--dump-json', '--no-playlist', '--user-agent', 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36']
    if (cookiesPath) args.push('--cookies', cookiesPath)
    if (targetUrl !== pageURL) {
      args.push('--add-header', `Referer: ${pageURL}`)
    }
    args.push(targetUrl)

    const proc = spawn(bin, args)
    let out = '', err = ''
    proc.stdout.on('data', d => { out += d })
    proc.stderr.on('data', d => { err += d })
    proc.on('close', code => {
      const line = out.split('\n').find(l => l.trim().startsWith('{'))
      if (line) {
        try { resolve(JSON.parse(line)); return }
        catch {}
      }
      reject(new Error(err.trim() || `yt-dlp exited ${code}`))
    })
    proc.on('error', reject)
  })
})

// ---------------------------------------------------------------------------
// IPC — audio extraction
// ---------------------------------------------------------------------------
ipcMain.handle('ytdlp:extractAudio', async (_, { pageURL, audioFormat, title, filePrefix, customTitle }) => {
  const cookiesPath = await exportCookies()
  const id = String(++downloadIdCounter)
  const settings = store.store || {}
  const saveFolder = settings.saveFolder || path.join(os.homedir(), 'Movies', 'NSL Downloads')
  const audioQuality = settings.audioQuality || '320'

  fs.mkdirSync(saveFolder, { recursive: true })
  let template = '%(title)s.%(ext)s'
  if (filePrefix) template = `${filePrefix} - ${template}`
  template = applyCustomTitle(template, customTitle)
  const outputTemplate = path.join(saveFolder, template)

  const pageData = detectedByPage.get(pageURL)
  const targetUrl = getTargetUrl(pageURL, pageData && pageData.streamUrl ? pageData.streamUrl : null)

  const bin = findBinary('yt-dlp')

  function buildAudioArgs(outTpl) {
    const a = [
      '-f', 'bestaudio',
      '-x',
      '--audio-format', audioFormat || 'mp3',
      '--audio-quality', audioQuality === '320' ? '0' : audioQuality,
      '--ffmpeg-location', path.dirname(findBinary('ffmpeg')),
      '--output', outTpl,
      '--newline', '--no-overwrites',
      '--user-agent', 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36',
      ...getDownloadSpeedArgs()
    ]
    if (cookiesPath) a.push('--cookies', cookiesPath)
    if (targetUrl !== pageURL) {
      a.push('--add-header', `Referer: ${pageURL}`)
      try { a.push('--add-header', `Origin: ${new URL(pageURL).origin}`) } catch {}
    }
    a.push(targetUrl)
    return a
  }

  let capturedFilePath = ''
  let allStderr = ''

  function runAudio(outTpl, isRetry) {
    allStderr = ''
    capturedFilePath = ''
    const proc = spawn(bin, buildAudioArgs(outTpl))
    activeDownloads.set(id, { proc, pageURL, title, isAudio: true })
    let skipped = false

    proc.stdout.on('data', data => {
      for (const line of data.toString().split('\n')) {
        if (line.includes('has already been downloaded')) skipped = true
        const fp = parseFilePath(line)
        if (fp) capturedFilePath = fp
        const p = parseYTDLPLine(line, true)
        if (p) mainWindow?.webContents.send('download:progress', { id, ...p })
      }
    })

    proc.stderr.on('data', data => {
      const msg = data.toString().trim()
      if (msg) {
        allStderr += (allStderr ? '\n' : '') + msg
        mainWindow?.webContents.send('download:log', { id, msg })
      }
    })

    proc.on('close', code => {
      activeDownloads.delete(id)
      if (code === 0 && skipped && !isRetry) {
        const epochTpl = outTpl.includes('.%(ext)s')
          ? outTpl.replace('.%(ext)s', `_${Date.now()}.%(ext)s`)
          : `${outTpl}_${Date.now()}`
        runAudio(epochTpl, true)
        return
      }
      if (code === 0) {
        mainWindow?.webContents.send('download:done', { id, isAudio: true, filePath: capturedFilePath })
        try { new Notification({ title: 'Audio Extraction Complete', body: title || 'Your audio file is ready.' }).show() } catch {}
      } else if (capturedFilePath && fs.existsSync(capturedFilePath) && fs.statSync(capturedFilePath).size > 0) {
        mainWindow?.webContents.send('download:done', { id, isAudio: true, filePath: capturedFilePath })
      } else {
        const errLine = allStderr.split('\n').map(l => l.trim())
          .find(l => l.toLowerCase().startsWith('error')) || allStderr.split('\n').pop()?.trim() || ''
        mainWindow?.webContents.send('download:failed', { id, error: errLine || 'Audio extraction failed' })
      }
    })

    proc.on('error', err => {
      activeDownloads.delete(id)
      mainWindow?.webContents.send('download:failed', { id, error: err.message })
    })
  }

  runAudio(outputTemplate, false)
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
ipcMain.handle('ytdlp:download', async (_, { pageURL, formatSelector, title, filePrefix, customTitle }) => {
  const cookiesPath = await exportCookies()
  const id = String(++downloadIdCounter)
  const settings = store.store || {}
  const saveFolder = settings.saveFolder || path.join(os.homedir(), 'Movies', 'NSL Downloads')
  let template = settings.filenameTemplate || '%(title)s [%(height)sp].%(ext)s'
  if (filePrefix) template = `${filePrefix} - ${template}`
  template = applyCustomTitle(template, customTitle)
  const mergeFormat = settings.defaultFormat || 'mp4'

  fs.mkdirSync(saveFolder, { recursive: true })
  const outputTemplate = path.join(saveFolder, template)

  const pageData = detectedByPage.get(pageURL)
  const targetUrl = getTargetUrl(pageURL, pageData && pageData.streamUrl ? pageData.streamUrl : null)

  const bin = findBinary('yt-dlp')

  function buildArgs(outTpl, mkvFallback) {
    const ffmpegBin = findBinary('ffmpeg')
    const a = [
      '--format', mkvFallback
        ? 'bestvideo[ext=mp4]+bestaudio[ext=m4a]/bestvideo[ext=mp4]+bestaudio/bestvideo+bestaudio/best'
        : formatSelector,
      '--merge-output-format', mkvFallback ? 'mkv' : mergeFormat,
      '--output', outTpl,
      '--newline', '--no-playlist', '--progress', '--no-overwrites',
      '--windows-filenames',
      '--user-agent', 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36',
      ...getDownloadSpeedArgs()
    ]
    if (path.isAbsolute(ffmpegBin)) a.push('--ffmpeg-location', path.dirname(ffmpegBin))
    if (cookiesPath) a.push('--cookies', cookiesPath)
    if (targetUrl !== pageURL) {
      a.push('--add-header', `Referer: ${pageURL}`)
      try { a.push('--add-header', `Origin: ${new URL(pageURL).origin}`) } catch {}
    }
    a.push(targetUrl)
    return a
  }

  let capturedFilePath = ''
  let allStderr = ''

  function notifyDone() {
    mainWindow?.webContents.send('download:done', { id, filePath: capturedFilePath })
    try { new Notification({ title: 'Download Complete', body: title || 'Your video is ready.' }).show() } catch {}
  }

  function notifyFailed() {
    const errLine = allStderr.split('\n').map(l => l.trim())
      .find(l => l.toLowerCase().startsWith('error')) || allStderr.split('\n').pop()?.trim() || ''
    mainWindow?.webContents.send('download:failed', { id, error: errLine || 'Download failed' })
  }

  function run(outTpl, isRetry, mkvFallback) {
    allStderr = ''
    capturedFilePath = ''
    const proc = spawn(bin, buildArgs(outTpl, mkvFallback))
    activeDownloads.set(id, { proc, pageURL, title })
    let skipped = false

    proc.stdout.on('data', data => {
      for (const line of data.toString().split('\n')) {
        if (line.includes('has already been downloaded')) skipped = true
        const fp = parseFilePath(line)
        if (fp) capturedFilePath = fp
        const p = parseYTDLPLine(line)
        if (p) mainWindow?.webContents.send('download:progress', { id, ...p })
      }
    })

    proc.stderr.on('data', data => {
      const msg = data.toString().trim()
      if (msg) {
        allStderr += (allStderr ? '\n' : '') + msg
        mainWindow?.webContents.send('download:log', { id, msg })
      }
    })

    proc.on('close', code => {
      activeDownloads.delete(id)
      if (code === 0 && skipped && !isRetry) {
        const epochTpl = outTpl.includes('.%(ext)s')
          ? outTpl.replace('.%(ext)s', `_${Date.now()}.%(ext)s`)
          : `${outTpl}_${Date.now()}`
        run(epochTpl, true, mkvFallback)
        return
      }
      if (code === 0) {
        notifyDone()
      } else if (capturedFilePath && fs.existsSync(capturedFilePath) && fs.statSync(capturedFilePath).size > 0) {
        notifyDone()
      } else if (!mkvFallback && allStderr.toLowerCase().includes('error opening output files')) {
        mainWindow?.webContents.send('download:log', { id, msg: '[NSL] MP4 merge failed — retrying as MKV…' })
        run(outTpl, true, true)
      } else {
        notifyFailed()
      }
    })

    proc.on('error', err => {
      activeDownloads.delete(id)
      mainWindow?.webContents.send('download:failed', { id, error: err.message })
    })
  }

  run(outputTemplate, false, false)
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

ipcMain.handle('fs:listFiles', async () => {
  const folder = store.get('saveFolder')
  if (!fs.existsSync(folder)) return []
  try {
    const files = fs.readdirSync(folder)
      .map(name => {
        const fullPath = path.join(folder, name)
        const stat = fs.statSync(fullPath)
        if (!stat.isFile()) return null
        const ext = path.extname(name).toLowerCase()
        const isVideo = ['.mp4', '.mkv', '.webm', '.avi', '.mov'].includes(ext)
        const isAudio = ['.mp3', '.m4a', '.flac', '.wav', '.aac'].includes(ext)
        if (!isVideo && !isAudio) return null
        return {
          name,
          path: fullPath,
          size: stat.size,
          mtime: stat.mtime.getTime(),
          isVideo,
          isAudio
        }
      })
      .filter(Boolean)
      .sort((a, b) => b.mtime - a.mtime) // newest first
    return files
  } catch (err) {
    console.error('Error listing files:', err)
    return []
  }
})

ipcMain.on('fs:deleteFile', (_, filePath) => {
  try {
    if (fs.existsSync(filePath)) {
      fs.unlinkSync(filePath)
    }
  } catch (err) {
    console.error('Error deleting file:', err)
  }
})

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
// IPC — video overlay / context-menu download request
// ---------------------------------------------------------------------------
ipcMain.on('browser:download-request', (_, { videoUrl, pageUrl, pageTitle }) => {
  handleVideoDownloadRequest(videoUrl, pageUrl, pageTitle)
})

// ---------------------------------------------------------------------------
// IPC — video player
// ---------------------------------------------------------------------------
ipcMain.on('player:show', () => {
  playerVisible = true
  if (activeTabId) {
    const view = browserTabs.get(activeTabId)
    if (view) view.setBounds({ x: 0, y: 0, width: 0, height: 0 })
  }
})

ipcMain.on('player:hide', () => {
  playerVisible = false
  repositionBrowserView()
})

// ---------------------------------------------------------------------------
// IPC — clear cache
// ---------------------------------------------------------------------------
ipcMain.handle('clear-cache', () => {
  const browserSession = session.fromPartition('persist:browser')
  browserSession.clearCache().catch(console.error)
  return true
})


// ---------------------------------------------------------------------------
// Cookie export — writes Electron session cookies in Netscape format for yt-dlp
// ---------------------------------------------------------------------------
async function exportCookies() {
  try {
    const browserSession = session.fromPartition('persist:browser')
    const cookies = await browserSession.cookies.get({})
    if (!cookies.length) return null
    const lines = ['# Netscape HTTP Cookie File', '']
    for (const c of cookies) {
      const domain = c.domain || ''
      const sub = domain.startsWith('.') ? 'TRUE' : 'FALSE'
      const expires = c.expirationDate ? Math.floor(c.expirationDate) : 0
      lines.push([domain, sub, c.path || '/', c.secure ? 'TRUE' : 'FALSE', expires, c.name, c.value || ''].join('\t'))
    }
    const p = path.join(os.tmpdir(), 'nsl_cookies.txt')
    fs.writeFileSync(p, lines.join('\n'))
    return p
  } catch { return null }
}

// ---------------------------------------------------------------------------
// Filename helpers
// ---------------------------------------------------------------------------
function sanitizeFilename(name) {
  return String(name || '').replace(/[/\\:*?"<>|]/g, '_').trim() || 'video'
}

function applyCustomTitle(template, customTitle) {
  if (!customTitle) return template
  const safe = sanitizeFilename(customTitle)
  return template.replace('%(title)s', safe || `playlist_${Date.now()}`)
}

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
