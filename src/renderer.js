// ── State ────────────────────────────────────────────────────────
const detectedPages = new Map()  // pageURL → { pageURL, pageTitle, favicon, types[] }
const downloads     = new Map()  // id → download object
const completed     = []

let settings = {}
let activeQualityPicker = null   // pageURL currently showing quality picker

// Playlist state
let currentPlaylist = null        // { url, listId, title, entries[] }
let playlistView    = false       // true = showing playlist panel

// Browser Tabs state
let browserTabsList = []          // [{ id, url, title, loading }]
let activeBrowserTabId = null

// ── Boot ─────────────────────────────────────────────────────────
;(async () => {
  settings = await window.api.getSettings()
  applySettingsToForm()
  bindEvents()
  bindIPCListeners()
  
  // Create initial tab
  const id = await window.api.createTab(settings.homepage || 'https://www.google.com')
  browserTabsList.push({ id, url: '', title: 'New Tab', loading: false })
  activeBrowserTabId = id
  window.api.switchTab(id)
  renderBrowserTabs()
})()

// ── Toolbar / navigation ─────────────────────────────────────────
function bindEvents() {
  const addr    = $('address-bar')
  const btnBack = $('btn-back')
  const btnFwd  = $('btn-forward')
  const btnRel  = $('btn-reload')

  addr.addEventListener('keydown', e => {
    if (e.key === 'Enter') { window.api.go(addr.value); addr.blur() }
  })
  addr.addEventListener('focus', () => addr.select())

  btnBack.addEventListener('click',    () => window.api.back())
  btnFwd.addEventListener('click',     () => window.api.forward())
  btnRel.addEventListener('click',     () => window.api.reload())
  $('btn-settings').addEventListener('click', () => switchTab('settings'))

  $('btn-new-tab').addEventListener('click', async () => {
    const id = await window.api.createTab(settings.homepage || 'https://www.google.com')
    browserTabsList.push({ id, url: '', title: 'New Tab', loading: false })
    activeBrowserTabId = id
    window.api.switchTab(id)
    renderBrowserTabs()
  })

  // Sidebar tabs
  document.querySelectorAll('.tab-btn').forEach(btn => {
    btn.addEventListener('click', () => switchTab(btn.dataset.tab))
  })

  // Settings form
  bindSettingsForm()
}

function bindIPCListeners() {
  window.api.on('browser:navigate', ({ tabId, url, title }) => {
    const tab = browserTabsList.find(t => t.id === tabId)
    if (tab) {
      tab.url = url
      tab.title = title || tab.title
    }
    if (tabId === activeBrowserTabId) {
      $('address-bar').value = url
      $('btn-back').disabled    = false
      $('btn-forward').disabled = false
    }
    renderBrowserTabs()
  })

  window.api.on('browser:loading', ({ tabId, loading }) => {
    const tab = browserTabsList.find(t => t.id === tabId)
    if (tab) tab.loading = loading
    
    if (tabId === activeBrowserTabId) {
      $('loading-indicator').classList.toggle('active', loading)
      $('btn-reload').textContent = loading ? '✕' : '↺'
      $('btn-reload').title = loading ? 'Stop' : 'Reload'
    }
  })

  window.api.on('browser:title', ({ tabId, title }) => {
    const tab = browserTabsList.find(t => t.id === tabId)
    if (tab) {
      tab.title = title
      renderBrowserTabs()
    }
  })

  window.api.on('tab:new-requested', async (url) => {
    const id = await window.api.createTab(url)
    browserTabsList.push({ id, url: url, title: 'Loading...', loading: true })
    activeBrowserTabId = id
    window.api.switchTab(id)
    renderBrowserTabs()
  })

  window.api.on('video:detected', ({ pageURL, pageTitle, types }) => {
    upsertDetectedVideo(pageURL, pageTitle, types)
    if (!document.querySelector('.tab-btn[data-tab="detected"]').classList.contains('active')) {
      flashTab('detected')
    }
  })

  window.api.on('playlist:detected', ({ url, listId }) => {
    upsertPlaylistCard(url, listId)
    if (!document.querySelector('.tab-btn[data-tab="detected"]').classList.contains('active')) {
      flashTab('detected')
    }
  })

  window.api.on('playlist:entry', (entry) => {
    if (!currentPlaylist) return
    currentPlaylist.entries.push(entry)
    appendPlaylistEntry(entry)
  })

  window.api.on('playlist:done', ({ count }) => {
    const hdr = $('playlist-loading')
    if (hdr) hdr.textContent = `${count} video${count !== 1 ? 's' : ''} found`
  })

  window.api.on('download:progress', ({ id, status, percent, speed, eta, total }) => {
    const dl = downloads.get(id)
    if (!dl) return
    dl.status  = status
    dl.percent = percent
    dl.speed   = speed
    dl.eta     = eta
    dl.total   = total
    renderDownloadItem(id)
  })

  window.api.on('download:done', ({ id, isAudio, filePath }) => {
    const dl = downloads.get(id)
    if (!dl) return
    completed.unshift({ id, title: dl.title, filePath: filePath || dl.filePath || '', isAudio: isAudio || dl.isAudio })
    downloads.delete(id)
    renderQueued()
    renderCompleted()
    if (!document.querySelector('.tab-btn[data-tab="done"]').classList.contains('active')) {
      flashTab('done')
    }
  })

  window.api.on('download:failed', ({ id, code, error }) => {
    const dl = downloads.get(id)
    if (!dl) return
    dl.status = 'failed'
    dl.error  = error || `Exit code ${code}`
    renderDownloadItem(id)
  })
}

// ── Detected videos ───────────────────────────────────────────────
function upsertDetectedVideo(pageURL, pageTitle, types) {
  const existing = detectedPages.get(pageURL)
  if (existing) {
    types.forEach(t => existing.types.add(t))
    // Re-render that card
    const card = document.querySelector(`[data-page-url="${CSS.escape(pageURL)}"]`)
    if (card) renderBadges(card.querySelector('.type-badges'), existing.types)
    return
  }

  const entry = { pageURL, pageTitle, favicon: null, types: new Set(types) }
  detectedPages.set(pageURL, entry)
  prependDetectedCard(entry)
  $('detected-empty').style.display = 'none'
}

function prependDetectedCard(entry) {
  const list = $('detected-list')
  const card = document.createElement('div')
  card.className = 'video-card'
  card.dataset.pageUrl = entry.pageURL

  const host = safeHost(entry.pageURL)
  const faviconSrc = `https://www.google.com/s2/favicons?domain=${host}&sz=32`

  card.innerHTML = `
    <div class="video-card-header">
      <img class="video-favicon" src="${faviconSrc}" alt="">
      <div class="video-info">
        <div class="video-title" title="${esc(entry.pageTitle)}">${esc(entry.pageTitle || host)}</div>
        <div class="video-url">${esc(host)}</div>
      </div>
      <button class="icon-btn btn-dismiss" title="Dismiss">✕</button>
    </div>
    <div class="type-badges"></div>
    <div class="video-card-actions">
      <button class="btn btn-primary btn-download">↓ Download</button>
      <button class="btn btn-secondary btn-analyse">Analyse</button>
    </div>
  `

  renderBadges(card.querySelector('.type-badges'), entry.types)

  card.querySelector('.btn-dismiss').addEventListener('click', () => {
    detectedPages.delete(entry.pageURL)
    card.remove()
    if (!$('detected-list').hasChildNodes()) {
      $('detected-empty').style.display = ''
    }
  })

  card.querySelector('.btn-download').addEventListener('click', () =>
    toggleQualityPicker(card, entry.pageURL, entry.pageTitle))
  card.querySelector('.btn-analyse').addEventListener('click', () =>
    toggleQualityPicker(card, entry.pageURL, entry.pageTitle, true))

  list.prepend(card)
}

function renderBadges(container, types) {
  container.innerHTML = ''
  types.forEach(t => {
    const span = document.createElement('span')
    span.className = `badge ${typeToCSSClass(t)}`
    span.textContent = t
    container.appendChild(span)
  })
}

function typeToCSSClass(t) {
  if (t === 'HLS')     return 'hls'
  if (t === 'DASH')    return 'dash'
  if (t === 'YouTube') return 'yt'
  return 'video'
}

// ── Quality picker ────────────────────────────────────────────────
async function toggleQualityPicker(card, pageURL, pageTitle, forceAnalyse = false) {
  // If picker already open for this card, close it
  const existing = card.querySelector('.quality-picker')
  if (existing) { existing.remove(); activeQualityPicker = null; return }

  // Close any other open picker
  document.querySelectorAll('.quality-picker').forEach(el => el.remove())
  activeQualityPicker = pageURL

  const picker = document.createElement('div')
  picker.className = 'quality-picker'
  picker.innerHTML = `
    <div class="quality-picker-header">
      <span>Select Quality</span>
      <button title="Close">✕</button>
    </div>
    <div class="quality-loading"><span class="spinner"></span> Fetching formats…</div>
  `
  picker.querySelector('button').addEventListener('click', () => {
    picker.remove(); activeQualityPicker = null
  })
  card.appendChild(picker)

  let formats
  try {
    const meta = await window.api.fetchMetadata(pageURL)
    formats = buildDisplayFormats(meta.formats || [])
    if (!formats.length) throw new Error('No downloadable formats found')

    const title = meta.title || pageTitle

    // Find default selection
    const defQuality = settings.defaultQuality || '1080p'
    const defIdx = formats.findIndex(f => f.displayRes.toLowerCase() === defQuality.toLowerCase())
    let selectedIdx = defIdx >= 0 ? defIdx : 0

    renderFormatList(picker, formats, selectedIdx, title, pageURL)
  } catch (err) {
    picker.querySelector('.quality-loading').innerHTML =
      `<span style="color:var(--red)">⚠ ${esc(err.message)}</span>`
  }
}

function renderFormatList(picker, formats, selectedIdx, title, pageURL) {
  const defFormat = settings.defaultFormat || 'mp4'
  const defAudioFormat = settings.defaultAudioFormat || 'mp3'

  picker.innerHTML = `
    <div class="quality-picker-header">
      <span>Select Quality</span>
      <button title="Close">✕</button>
    </div>
    <div class="format-list"></div>
    <div class="quality-picker-footer">
      <select class="format-select" id="out-format">
        <option value="mp4" ${defFormat==='mp4'?'selected':''}>MP4</option>
        <option value="mkv" ${defFormat==='mkv'?'selected':''}>MKV</option>
        <option value="webm" ${defFormat==='webm'?'selected':''}>WebM</option>
      </select>
      <button class="btn btn-primary" style="flex:1" id="btn-start-dl">↓ Download</button>
    </div>
    <div class="quality-picker-divider"></div>
    <div class="quality-picker-footer">
      <select class="format-select" id="out-audio-format">
        <option value="mp3" ${defAudioFormat==='mp3'?'selected':''}>MP3</option>
        <option value="aac" ${defAudioFormat==='aac'?'selected':''}>AAC</option>
        <option value="flac" ${defAudioFormat==='flac'?'selected':''}>FLAC</option>
      </select>
      <button class="btn btn-audio" style="flex:1" id="btn-extract-audio">♪ Extract</button>
    </div>
  `

  picker.querySelector('.quality-picker-header button').addEventListener('click', () => {
    picker.remove(); activeQualityPicker = null
  })

  const list = picker.querySelector('.format-list')
  formats.forEach((fmt, i) => {
    const row = document.createElement('div')
    row.className = 'format-row' + (i === selectedIdx ? ' selected' : '')
    row.innerHTML = `
      <div class="format-dot"></div>
      <span class="format-res">${esc(fmt.displayRes)}</span>
      <span class="format-codec">${esc(fmt.displayCodec)}</span>
      <span class="format-size">${esc(fmt.displaySize)}</span>
    `
    row.addEventListener('click', () => {
      selectedIdx = i
      list.querySelectorAll('.format-row').forEach((r, j) =>
        r.classList.toggle('selected', j === i))
    })
    list.appendChild(row)
  })

  picker.querySelector('#btn-start-dl').addEventListener('click', async () => {
    const fmt = formats[selectedIdx]
    const outFmt = picker.querySelector('#out-format').value
    const selector = buildFormatSelector(fmt)
    picker.remove()
    activeQualityPicker = null
    await startDownload({ pageURL, title, formatSelector: selector, outputFormat: outFmt })
    switchTab('queue')
  })

  picker.querySelector('#btn-extract-audio').addEventListener('click', async () => {
    const audioFmt = picker.querySelector('#out-audio-format').value
    picker.remove()
    activeQualityPicker = null
    await extractAudioFromPage({ pageURL, title, audioFormat: audioFmt })
    switchTab('queue')
  })
}

// ── Audio extraction ──────────────────────────────────────────────
async function extractAudioFromPage({ pageURL, title, audioFormat, filePrefix }) {
  const id = await window.api.extractAudio({
    pageURL, audioFormat: audioFormat || settings.defaultAudioFormat || 'mp3', title, filePrefix
  })
  downloads.set(id, {
    id, pageURL, title,
    status: 'downloading', percent: 0,
    speed: '', eta: '', total: '', error: '',
    isAudio: true
  })
  $('queue-empty').style.display = 'none'
  renderDownloadItem(id)
}

// ── Playlist ──────────────────────────────────────────────────────
function upsertPlaylistCard(url, listId) {
  const existing = document.querySelector(`[data-playlist-url="${CSS.escape(url)}"]`)
  if (existing) return

  const list = $('detected-list')
  if (!list) return
  const card = document.createElement('div')
  card.className = 'video-card playlist-card'
  card.dataset.playlistUrl = url

  const host = safeHost(url)
  card.innerHTML = `
    <div class="video-card-header">
      <span class="playlist-icon">📋</span>
      <div class="video-info">
        <div class="video-title">YouTube Playlist</div>
        <div class="video-url">${esc(host)}</div>
      </div>
      <button class="icon-btn btn-dismiss" title="Dismiss">✕</button>
    </div>
    <div class="video-card-actions">
      <button class="btn btn-primary btn-browse-playlist">▸ Browse Playlist</button>
    </div>
  `

  card.querySelector('.btn-dismiss').addEventListener('click', () => {
    card.remove()
    if (!$('detected-list').hasChildNodes()) $('detected-empty').style.display = ''
  })

  card.querySelector('.btn-browse-playlist').addEventListener('click', () =>
    showPlaylistPanel(url))

  list.prepend(card)
  $('detected-empty').style.display = 'none'
}

function showPlaylistPanel(url) {
  currentPlaylist = { url, entries: [] }
  playlistView = true

  const panel = $('panel-detected')
  panel.innerHTML = `
    <div class="playlist-panel">
      <div class="playlist-panel-header">
        <button class="icon-btn" id="btn-playlist-back">← Back</button>
        <span class="playlist-panel-title" id="playlist-title">Loading…</span>
      </div>
      <div class="playlist-select-all-row">
        <label><input type="checkbox" id="chk-select-all" checked> Select All</label>
        <span class="playlist-count" id="playlist-loading">Fetching…</span>
      </div>
      <div id="playlist-entry-list" class="playlist-entry-list"></div>
      <div class="playlist-panel-footer">
        <div class="playlist-footer-row">
          <label class="setting-label">Quality</label>
          <select class="format-select" id="pl-quality">
            <option value="2160">4K</option>
            <option value="1080" selected>1080p</option>
            <option value="720">720p</option>
            <option value="480">480p</option>
            <option value="360">360p</option>
          </select>
        </div>
        <div class="playlist-footer-row">
          <label class="setting-label">Format</label>
          <select class="format-select" id="pl-format">
            <option value="mp4" selected>MP4</option>
            <option value="mkv">MKV</option>
            <option value="webm">WebM</option>
            <option value="mp3">MP3</option>
            <option value="aac">AAC</option>
            <option value="flac">FLAC</option>
          </select>
        </div>
        <button class="btn btn-primary" id="btn-dl-selected" style="margin-top:6px">↓ Download Selected</button>
      </div>
    </div>
  `

  $('btn-playlist-back').addEventListener('click', () => {
    playlistView = false
    currentPlaylist = null
    panel.innerHTML = `
      <div class="empty-state" id="detected-empty" style="display:${detectedPages.size ? 'none' : ''}">
        <svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
          <circle cx="12" cy="12" r="10"/><polygon points="10,8 16,12 10,16"/>
        </svg>
        <p>Browse to any page.<br>Detected videos appear here.</p>
      </div>
      <div id="detected-list"></div>
    `
    for (const [, entry] of detectedPages) prependDetectedCard(entry)
  })

  $('chk-select-all').addEventListener('change', e => {
    document.querySelectorAll('.pl-entry-chk').forEach(chk => { chk.checked = e.target.checked })
    updateDownloadSelectedLabel()
  })

  $('btn-dl-selected').addEventListener('click', () => downloadSelectedPlaylistItems())

  window.api.fetchPlaylist(url).then(({ count }) => {
    const el = $('playlist-title')
    if (el) el.textContent = `${count} video${count !== 1 ? 's' : ''}`
  }).catch(() => {
    const el = $('playlist-loading')
    if (el) el.textContent = '⚠ Failed to fetch'
  })
}

function appendPlaylistEntry(entry) {
  const list = $('playlist-entry-list')
  if (!list) return
  const idx = currentPlaylist ? currentPlaylist.entries.length : 0
  const row = document.createElement('label')
  row.className = 'pl-entry-row'
  row.dataset.entryUrl = entry.url
  const dur = entry.duration ? formatDuration(entry.duration) : ''
  row.innerHTML = `
    <input type="checkbox" class="pl-entry-chk" checked>
    <span class="pl-entry-index">${idx}.</span>
    <span class="pl-entry-title" title="${esc(entry.title)}">${esc(entry.title)}</span>
    ${dur ? `<span class="pl-entry-dur">${esc(dur)}</span>` : ''}
  `
  row.querySelector('.pl-entry-chk').addEventListener('change', updateDownloadSelectedLabel)
  list.appendChild(row)
  updateDownloadSelectedLabel()
}

function updateDownloadSelectedLabel() {
  const total   = document.querySelectorAll('.pl-entry-chk').length
  const checked = document.querySelectorAll('.pl-entry-chk:checked').length
  const btn = $('btn-dl-selected')
  if (btn) btn.textContent = checked > 0 ? `↓ Download ${checked} selected` : '↓ Download Selected'
  const allChk = $('chk-select-all')
  if (allChk) allChk.indeterminate = checked > 0 && checked < total
}

async function downloadSelectedPlaylistItems() {
  if (!currentPlaylist) return
  const quality = $('pl-quality')?.value || '1080'
  const format  = $('pl-format')?.value  || 'mp4'
  
  const isAudio = ['mp3', 'aac', 'flac'].includes(format)
  const formatSelector = isAudio 
    ? undefined 
    : `bestvideo[height<=${quality}][ext=mp4]+bestaudio[ext=m4a]/bestvideo[height<=${quality}]+bestaudio/best[height<=${quality}]`

  const rows = document.querySelectorAll('.pl-entry-row')
  let count = 0
  for (const row of rows) {
    const chk = row.querySelector('.pl-entry-chk')
    if (!chk?.checked) continue
    const url   = row.dataset.entryUrl
    const rawIdx = row.querySelector('.pl-entry-index')?.textContent || ''
    const indexPadded = rawIdx.replace('.', '').padStart(2, '0') // e.g. "01"
    
    const rawTitle = row.querySelector('.pl-entry-title')?.textContent || 'Video'
    const title = indexPadded ? `${indexPadded} - ${rawTitle}` : rawTitle
    
    if (isAudio) {
      await extractAudioFromPage({ pageURL: url, title, audioFormat: format, filePrefix: indexPadded })
    } else {
      await startDownload({ pageURL: url, title, formatSelector, outputFormat: format, filePrefix: indexPadded })
    }
    count++
  }
  if (count > 0) switchTab('queue')
}

function formatDuration(secs) {
  if (!secs) return ''
  const h = Math.floor(secs / 3600)
  const m = Math.floor((secs % 3600) / 60)
  const s = Math.floor(secs % 60)
  if (h > 0) return `${h}:${String(m).padStart(2,'0')}:${String(s).padStart(2,'0')}`
  return `${m}:${String(s).padStart(2,'0')}`
}

// ── Download management ───────────────────────────────────────────
async function startDownload({ pageURL, title, formatSelector, outputFormat, filePrefix }) {
  const id = await window.api.startDownload({
    pageURL, formatSelector, title, filePrefix,
    outputFormat: outputFormat || settings.defaultFormat || 'mp4'
  })

  downloads.set(id, {
    id, pageURL, title,
    status: 'downloading', percent: 0,
    speed: '', eta: '', total: '', error: ''
  })

  $('queue-empty').style.display = 'none'
  renderDownloadItem(id)
}

function renderDownloadItem(id) {
  const dl = downloads.get(id)
  if (!dl) return

  const list = $('queue-list')
  let item = list.querySelector(`[data-dl-id="${id}"]`)
  if (!item) {
    item = document.createElement('div')
    item.className = 'download-item'
    item.dataset.dlId = id
    list.appendChild(item)
  }

  const isMuxing     = dl.status === 'muxing'
  const isExtracting = dl.status === 'extracting'
  const isFailed = dl.status === 'failed'
  const pct = dl.percent || 0

  item.innerHTML = `
    <div class="download-title" title="${esc(dl.title)}">${esc(dl.title)}</div>
    <div class="progress-bar-track">
      <div class="progress-bar-fill ${isMuxing ? 'muxing' : ''}"
           style="width:${pct}%"></div>
    </div>
    <div class="download-meta">
      <span>${isFailed ? '⚠ ' + esc(dl.error) : isExtracting ? 'Extracting audio…' : isMuxing ? 'Merging…' : pct.toFixed(1) + '%' + (dl.total ? ' of ' + dl.total : '')}</span>
      <span>${dl.speed ? dl.speed + (dl.eta ? ' · ETA ' + dl.eta : '') : ''}</span>
    </div>
    <div class="download-actions">
      ${isFailed
        ? `<button class="icon-btn" data-action="retry" title="Retry">↺ Retry</button>`
        : dl.status === 'paused'
        ? `<button class="icon-btn" data-action="resume" title="Resume">▶ Resume</button>`
        : `<button class="icon-btn" data-action="pause"  title="Pause">⏸</button>`}
      <button class="icon-btn danger" data-action="cancel" title="Cancel">✕</button>
    </div>
  `

  item.querySelectorAll('[data-action]').forEach(btn => {
    btn.addEventListener('click', () => {
      const a = btn.dataset.action
      if (a === 'pause')  { dl.status = 'paused';  window.api.pauseDownload(id);  renderDownloadItem(id) }
      if (a === 'resume') { dl.status = 'downloading'; window.api.resumeDownload(id); renderDownloadItem(id) }
      if (a === 'cancel') { downloads.delete(id);  window.api.cancelDownload(id); item.remove(); updateQueueEmpty() }
      if (a === 'retry')  { startRetry(dl) }
    })
  })
}

function renderQueued() {
  $('queue-list').querySelectorAll('.download-item').forEach(el => {
    const id = el.dataset.dlId
    if (!downloads.has(id)) el.remove()
  })
  updateQueueEmpty()
}

function renderCompleted() {
  const list = $('done-list')
  $('done-empty').style.display = completed.length ? 'none' : ''

  const newest = completed[0]
  if (!list.querySelector(`[data-done-id="${newest.id}"]`)) {
    const item = document.createElement('div')
    item.className = 'completed-item'
    item.dataset.doneId = newest.id
    item.innerHTML = `
      <span class="completed-icon">${newest.isAudio ? '♪' : '✅'}</span>
      <span class="completed-title" title="${esc(newest.title)}">${esc(newest.title)}</span>
      <div class="completed-actions">
        <button class="link-btn" data-action="open">Open</button>
        <button class="link-btn" data-action="finder">Finder</button>
      </div>
    `
    item.querySelector('[data-action="open"]').addEventListener('click', () =>
      window.api.openFile(newest.filePath || ''))
    item.querySelector('[data-action="finder"]').addEventListener('click', () =>
      window.api.showFolder(newest.filePath || ''))
    list.prepend(item)
  }
}

async function startRetry(dl) {
  downloads.delete(dl.id)
  document.querySelector(`[data-dl-id="${dl.id}"]`)?.remove()
  await startDownload({ pageURL: dl.pageURL, title: dl.title, formatSelector: 'bestvideo+bestaudio/best' })
}

function updateQueueEmpty() {
  $('queue-empty').style.display = downloads.size ? 'none' : ''
}

// ── Settings form ─────────────────────────────────────────────────
function applySettingsToForm() {
  setVal('s-quality',       settings.defaultQuality || '1080p')
  setVal('s-format',        settings.defaultFormat  || 'mp4')
  setVal('s-folder',        settings.saveFolder     || '')
  setVal('s-template',      settings.filenameTemplate || '%(title)s [%(height)sp].%(ext)s')
  setVal('s-concurrency',   String(settings.maxConcurrentDownloads || 3))
  setVal('s-homepage',      settings.homepage || 'https://www.google.com')
  setVal('s-audio-format',  settings.defaultAudioFormat || 'mp3')
  setVal('s-audio-quality', settings.audioQuality || '320')
}

function bindSettingsForm() {
  const save = (key, id, transform) => {
    $(id)?.addEventListener('change', e => {
      const val = transform ? transform(e.target.value) : e.target.value
      settings[key] = val
      window.api.setSetting(key, val)
    })
  }

  save('defaultQuality',        's-quality')
  save('defaultFormat',         's-format')
  save('filenameTemplate',      's-template')
  save('maxConcurrentDownloads','s-concurrency', v => parseInt(v, 10))
  save('homepage',              's-homepage')
  save('defaultAudioFormat',    's-audio-format')
  save('audioQuality',          's-audio-quality')

  $('btn-choose-folder')?.addEventListener('click', async () => {
    const folder = await window.api.chooseFolder()
    if (folder) { settings.saveFolder = folder; setVal('s-folder', folder) }
  })
}

// ── Browser Tabs UI ────────────────────────────────────────────────
function renderBrowserTabs() {
  const container = $('browser-tabs-list')
  if (!container) return
  container.innerHTML = ''

  browserTabsList.forEach(tab => {
    const el = document.createElement('div')
    el.className = 'browser-tab' + (tab.id === activeBrowserTabId ? ' active' : '')
    el.title = tab.title
    
    el.innerHTML = `
      <span class="browser-tab-title">${esc(tab.title || 'New Tab')}</span>
      <button class="browser-tab-close" title="Close">✕</button>
    `

    // Switch tab on click
    el.addEventListener('click', (e) => {
      if (e.target.classList.contains('browser-tab-close')) return
      activeBrowserTabId = tab.id
      window.api.switchTab(tab.id)
      renderBrowserTabs()
    })

    // Close tab on click
    el.querySelector('.browser-tab-close').addEventListener('click', () => {
      window.api.closeTab(tab.id)
      const idx = browserTabsList.findIndex(t => t.id === tab.id)
      browserTabsList.splice(idx, 1)
      
      if (browserTabsList.length === 0) {
        // If last tab closed, create a new empty one
        $('btn-new-tab').click()
      } else if (activeBrowserTabId === tab.id) {
        // If active tab closed, switch to the previous one (or next if it was first)
        const newActive = browserTabsList[Math.max(0, idx - 1)]
        activeBrowserTabId = newActive.id
        window.api.switchTab(newActive.id)
      }
      renderBrowserTabs()
    })

    container.appendChild(el)
  })
}

// ── Sidebar Tabs ──────────────────────────────────────────────────
function switchTab(name) {
  document.querySelectorAll('.tab-btn').forEach(b =>
    b.classList.toggle('active', b.dataset.tab === name))
  document.querySelectorAll('.panel').forEach(p =>
    p.classList.toggle('active', p.id === `panel-${name}`))
}

function flashTab(name) {
  const btn = document.querySelector(`.tab-btn[data-tab="${name}"]`)
  if (!btn) return
  btn.style.color = 'var(--accent)'
  setTimeout(() => { btn.style.color = '' }, 1500)
}

// ── Format helpers ────────────────────────────────────────────────
function buildDisplayFormats(formats) {
  const byHeight = new Map()
  let audioOnly = null

  for (const f of formats) {
    if (!f || f.ext === 'mhtml') continue

    const isAudioOnly = f.vcodec === 'none'
    if (isAudioOnly) {
      if (!audioOnly || (f.tbr || 0) > (audioOnly.tbr || 0)) audioOnly = f
      continue
    }

    const h = f.height || 0
    const existing = byHeight.get(h)
    if (!existing || (f.tbr || 0) > (existing.tbr || 0)) byHeight.set(h, f)
  }

  const sorted = [...byHeight.entries()]
    .sort((a, b) => b[0] - a[0])
    .map(([, f]) => enrichFormat(f))

  if (audioOnly) sorted.push(enrichFormat(audioOnly))
  return sorted
}

function enrichFormat(f) {
  const h = f.height
  let displayRes = 'Audio Only'
  if (h) {
    if (h >= 2160) displayRes = '4K'
    else if (h >= 1440) displayRes = '1440p'
    else if (h >= 1080) displayRes = '1080p'
    else if (h >= 720)  displayRes = '720p'
    else if (h >= 480)  displayRes = '480p'
    else if (h >= 360)  displayRes = '360p'
    else displayRes = `${h}p`
  } else if (f.vcodec !== 'none') {
    displayRes = f.format_note || 'Unknown resolution'
  }

  const vShort = f.vcodec && f.vcodec !== 'none' ? f.vcodec.split('.')[0] : null
  const aShort = f.acodec && f.acodec !== 'none' ? f.acodec.split('.')[0] : null
  const codecParts = [vShort, aShort].filter(Boolean)
  const displayCodec = codecParts.length ? codecParts.join(' + ') : ''

  const size = f.filesize || f.filesize_approx
  let displaySize = ''
  if (size) {
    const mb = size / 1_048_576
    displaySize = mb >= 1024 ? `~${(mb/1024).toFixed(1)} GB` : `~${mb.toFixed(0)} MB`
  }

  return { ...f, displayRes, displayCodec, displaySize }
}

function buildFormatSelector(fmt) {
  if (fmt.vcodec === 'none') return 'bestaudio[ext=m4a]/bestaudio'
  const h = fmt.height || 0
  if (h) return `bestvideo[height<=${h}][ext=mp4]+bestaudio[ext=m4a]/bestvideo[height<=${h}]+bestaudio/best[height<=${h}]`
  return 'bestvideo[ext=mp4]+bestaudio[ext=m4a]/bestvideo+bestaudio/best'
}

// ── Utils ─────────────────────────────────────────────────────────
const $ = id => document.getElementById(id)
const esc = s => String(s || '').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;')
const setVal = (id, val) => { const el = $(id); if (el) el.value = val }
const safeHost = url => { try { return new URL(url).hostname } catch { return url } }
