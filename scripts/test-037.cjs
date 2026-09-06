// Live integration test: real app detection, metadata, download IPC and player.
// Run: electron scripts/test-037.cjs 
const { app, BrowserWindow, webContents } = require('electron')
const fs = require('fs')
const path = require('path')
const cp = require('child_process')
const root = path.resolve(__dirname, '../test-results/037')
fs.mkdirSync(root, { recursive: true })
app.setPath('userData', fs.mkdtempSync('/tmp/nsl-037-test-'))
fs.writeFileSync(path.join(app.getPath('userData'), 'config.json'), JSON.stringify({ homepage: 'about:blank', saveFolder: root, fragmentConcurrency: 8 }))
app.commandLine.appendSwitch('autoplay-policy', 'no-user-gesture-required')
app.on('browser-window-created', (_, window) => {
 window.hide()
 window.on('closed', () => console.log('TEST WINDOW CLOSED'))
})
require('../main')
const wait = ms => new Promise(r => setTimeout(r, ms))
async function until(fn, timeout = 120000) {
 const end = Date.now() + timeout
 while (Date.now() < end) { const result = await fn(); if (result) return result; await wait(500) }
 throw new Error('Timed out waiting for app state')
}
const pages = ['almost-us-2026', 'second-sister-2025', 'gandhari-2026-%e0%b9%81%e0%b8%a1%e0%b9%88%e0%b8%9c%e0%b8%b9%e0%b9%89%e0%b8%a5%e0%b9%88%e0%b8%b2%e0%b9%83%e0%b8%99%e0%b9%80%e0%b8%87%e0%b8%b2%e0%b8%a1%e0%b8%b7%e0%b8%94'].map(slug => `https://www.037hddmovies.com/2026/09/05/${slug}/`)
const report = { date: new Date().toISOString(), fullMovieDownloads: true, results: [] }
app.whenReady().then(async () => {
 const shell = await until(() => BrowserWindow.getAllWindows()[0]?.webContents)
 await until(() => shell.executeJavaScript('typeof window.api === "object"'))
 await shell.executeJavaScript(`window.testEvents=[]; for(const channel of ['video:detected','download:done','download:failed','download:progress','download:log']) window.api.on(channel,data=>window.testEvents.push({channel,data})); window.api.setSetting('saveFolder',${JSON.stringify(root)});`)
 for (let index = 0; index < pages.length; index++) {
  const pageURL = pages[index]
  const result = { pageURL }
  report.results.push(result)
  try {
   console.log('PAGE', index + 1, pageURL)
   const tabId = await shell.executeJavaScript(`window.api.createTab(${JSON.stringify(pageURL)})`)
   await shell.executeJavaScript(`window.api.switchTab(${JSON.stringify(tabId)})`)
   const browser = await until(() => webContents.getAllWebContents().find(w => w.getURL() === pageURL))
   browser.setWindowOpenHandler(() => ({ action: 'deny' }))
   await until(async () => {
    for (const frame of browser.mainFrame.framesInSubtree) {
     try {
      await frame.executeJavaScript(`(() => {
       document.querySelectorAll('iframe').forEach(f=>{f.loading='eager';f.scrollIntoView()});
       document.querySelectorAll('video').forEach(v=>{v.muted=true;v.play().catch(()=>{})});
       [...document.querySelectorAll('button, .skip-ad, .skip, [class*="skip"]')].filter(e=>/skip|ข้าม/i.test(e.textContent)).forEach(e=>e.click());
      })()`)
     } catch {}
    }
    return shell.executeJavaScript(`window.testEvents.some(e=>e.channel==='video:detected' && e.data.pageURL===${JSON.stringify(pageURL)} && e.data.types.includes('HLS'))`)
   }, 180000)
   result.detected = true
   const meta = await shell.executeJavaScript(`window.api.fetchMetadata(${JSON.stringify(pageURL)})`)
   result.title = meta.title
   result.selector = await shell.executeJavaScript(`buildFormatSelector(buildDisplayFormats(${JSON.stringify(meta.formats)})[0])`)
   result.formats = meta.formats.map(f => ({id:f.format_id,height:f.height,vcodec:f.vcodec,acodec:f.acodec}))
   console.log('DETECTED', result.title, result.formats)
   // Stop browser streaming while the actual downloader runs.
   for (const frame of browser.mainFrame.framesInSubtree) { try { await frame.executeJavaScript(`document.querySelectorAll('video').forEach(v=>v.pause())`) } catch {} }
   const id = await shell.executeJavaScript(`window.api.startDownload(${JSON.stringify({pageURL,formatSelector:result.selector,outputFormat:'mp4',title:`037-test-${index+1}`,customTitle:`037-test-${index+1}`})})`)
   await browser.loadURL('about:blank')
   let lastProgress = 0
   const event = await until(async () => {
    const events = await shell.executeJavaScript(`window.testEvents.filter(e=>e.data.id===${JSON.stringify(id)})`)
    if (Date.now()-lastProgress>15000) { console.log('PROGRESS',index+1,events.filter(e=>e.channel==='download:progress').at(-1)?.data);lastProgress=Date.now() }
    return events.find(e=>e.channel==='download:done'||e.channel==='download:failed')
   }, 1800000)
   if (event.channel === 'download:failed') {
    console.error(await shell.executeJavaScript(`window.testEvents.filter(e=>e.channel==='download:log' && e.data.id===${JSON.stringify(id)}).slice(-8)`))
    throw new Error(event.data.error)
   }
   result.filePath = event.data.filePath
   result.bytes = fs.statSync(result.filePath).size
   const probe = cp.spawnSync('/opt/homebrew/bin/ffprobe',['-v','error','-show_format','-show_streams','-of','json',result.filePath],{encoding:'utf8'})
   if (probe.status !== 0) throw new Error(probe.stderr)
   const info = JSON.parse(probe.stdout)
   result.duration = Number(info.format.duration)
   result.codecs = info.streams.map(s=>({type:s.codec_type,codec:s.codec_name,width:s.width,height:s.height}))
   if (!info.streams.some(s=>s.codec_type==='video') || !info.streams.some(s=>s.codec_type==='audio')) throw new Error('Missing video or audio')
   console.log('VERIFY', index+1, result.bytes, result.duration)
   await new Promise((resolve,reject) => {
    const proc=cp.spawn('/opt/homebrew/bin/ffmpeg',['-v','error','-xerror','-i',result.filePath,'-map','0:v:0','-map','0:a:0','-f','null','-'])
    let error='';proc.stderr.on('data',chunk=>error+=chunk)
    proc.on('error',reject);proc.on('close',code=>code===0?resolve():reject(new Error(error)))
   })
   result.fullDecode = true
   shell.setBackgroundThrottling(false)
   BrowserWindow.fromWebContents(shell).showInactive()
   await shell.executeJavaScript(`openPlayer(${JSON.stringify(result.filePath)},${JSON.stringify(result.title)});document.getElementById('player-video').muted=true`)
   result.playback = await until(() => shell.executeJavaScript(`(() => {const v=document.getElementById('player-video');if(v.error)throw new Error(v.error.message);return v.currentTime>1 && {time:v.currentTime,width:v.videoWidth,height:v.videoHeight,paused:v.paused,readyState:v.readyState}})()`), 30000)
   await shell.executeJavaScript('closePlayer()')
   BrowserWindow.fromWebContents(shell).hide()
   result.passed = true
   console.log('PASS', JSON.stringify(result))
   await shell.executeJavaScript(`window.api.closeTab(${JSON.stringify(tabId)})`)
  } catch (err) { result.passed=false;result.error=err.message;console.error('FAIL',err) }
  fs.writeFileSync(path.join(root,'report.json'),JSON.stringify(report,null,2))
 }
 app.exit(report.results.every(r=>r.passed)?0:1)
}).catch(err=>{console.error(err);app.exit(1)})
