// ── State ────────────────────────────────────────────────────────
const detectedPages = new Map()  // pageURL → { pageURL, pageTitle, favicon, types[] }
const downloads     = new Map()  // id → download object
const completed     = []

let settings = {}
let activeQualityPicker = null   // pageURL currently showing quality picker

// ── Boot ─────────────────────────────────────────────────────────
;(async () => {
  settings = await window.api.getSettings()
  applySettingsToForm()
  bindEvents()
  bindIPCListeners()
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

  // Sidebar tabs
  document.querySelectorAll('.tab-btn').forEach(btn => {
    btn.addEventListener('click', () => switchTab(btn.dataset.tab))
  })

  // Settings form
  bindSettingsForm()
}

function bindIPCListeners() {
  window.api.on('browser:navigate', (url, title) => {
    $('address-bar').value = url
    $('btn-back').disabled    = false
    $('btn-forward').disabled = false
  })

  window.api.on('browser:loading', loading => {
    $('loading-indicator').classList.toggle('active', loading)
    $('btn-reload').textContent = loading ? '✕' : '↺'
    $('btn-reload').title = loading ? 'Stop' : 'Reload'
  })

  window.api.on('video:detected', ({ pageURL, pageTitle, types }) => {
    upsertDetectedVideo(pageURL, pageTitle, types)
    // Auto-switch to Detected tab if something new arrives
    if (!document.querySelector('.tab-btn[data-tab="detected"]').classList.contains('active')) {
      flashTab('detected')
    }
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

  window.api.on('download:done', ({ id }) => {
    const dl = downloads.get(id)
    if (!dl) return
    completed.unshift({ id, title: dl.title, filePath: dl.filePath })
    downloads.delete(id)
    renderQueued()
    renderCompleted()
    switchTab('done')
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
    </div>
    <div class="type-badges"></div>
    <div class="video-card-actions">
      <button class="btn btn-primary btn-download">↓ Download</button>
      <button class="btn btn-secondary btn-analyse">Analyse</button>
    </div>
  `

  renderBadges(card.querySelector('.type-badges'), entry.types)

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
}

// ── Download management ───────────────────────────────────────────
async function startDownload({ pageURL, title, formatSelector, outputFormat }) {
  const id = await window.api.startDownload({
    pageURL, formatSelector, title,
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

  const isMuxing = dl.status === 'muxing'
  const isFailed = dl.status === 'failed'
  const pct = dl.percent || 0

  item.innerHTML = `
    <div class="download-title" title="${esc(dl.title)}">${esc(dl.title)}</div>
    <div class="progress-bar-track">
      <div class="progress-bar-fill ${isMuxing ? 'muxing' : ''}"
           style="width:${pct}%"></div>
    </div>
    <div class="download-meta">
      <span>${isFailed ? '⚠ ' + esc(dl.error) : isMuxing ? 'Merging…' : pct.toFixed(1) + '%' + (dl.total ? ' of ' + dl.total : '')}</span>
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

  // Prepend new item (completed[0] is newest)
  const newest = completed[0]
  if (!list.querySelector(`[data-done-id="${newest.id}"]`)) {
    const item = document.createElement('div')
    item.className = 'completed-item'
    item.dataset.doneId = newest.id
    item.innerHTML = `
      <span class="completed-icon">✅</span>
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
  setVal('s-quality',     settings.defaultQuality || '1080p')
  setVal('s-format',      settings.defaultFormat  || 'mp4')
  setVal('s-folder',      settings.saveFolder     || '')
  setVal('s-template',    settings.filenameTemplate || '%(title)s [%(height)sp].%(ext)s')
  setVal('s-concurrency', String(settings.maxConcurrentDownloads || 3))
  setVal('s-homepage',    settings.homepage || 'https://www.youtube.com')
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

  $('btn-choose-folder')?.addEventListener('click', async () => {
    const folder = await window.api.chooseFolder()
    if (folder) { settings.saveFolder = folder; setVal('s-folder', folder) }
  })
}

// ── Tab switching ─────────────────────────────────────────────────
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
    const isVideoOnly = f.acodec === 'none'

    if (isAudioOnly) {
      if (!audioOnly || (f.tbr || 0) > (audioOnly.tbr || 0)) audioOnly = f
      continue
    }

    const h = f.height
    if (!h) continue
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
  const h = fmt.height
  if (h) return `bestvideo[height<=${h}][ext=mp4]+bestaudio[ext=m4a]/bestvideo[height<=${h}]+bestaudio/best[height<=${h}]`
  return 'bestvideo+bestaudio/best'
}

// ── Utils ─────────────────────────────────────────────────────────
const $ = id => document.getElementById(id)
const esc = s => String(s || '').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;')
const setVal = (id, val) => { const el = $(id); if (el) el.value = val }
const safeHost = url => { try { return new URL(url).hostname } catch { return url } }
