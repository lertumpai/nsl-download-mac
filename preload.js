const { contextBridge, ipcRenderer } = require('electron')

contextBridge.exposeInMainWorld('api', {
  // Browser navigation & tabs
  go:      (url)  => ipcRenderer.send('browser:go', url),
  back:    ()     => ipcRenderer.send('browser:back'),
  forward: ()     => ipcRenderer.send('browser:forward'),
  reload:  ()     => ipcRenderer.send('browser:reload'),
  
  createTab: (url)   => ipcRenderer.invoke('tab:create', url),
  switchTab: (tabId) => ipcRenderer.send('tab:switch', tabId),
  closeTab:  (tabId) => ipcRenderer.send('tab:close', tabId),

  // yt-dlp
  fetchMetadata: (url)  => ipcRenderer.invoke('ytdlp:metadata', url),
  startDownload: (opts) => ipcRenderer.invoke('ytdlp:download', opts),
  extractAudio:  (opts) => ipcRenderer.invoke('ytdlp:extractAudio', opts),
  cancelDownload: (id)  => ipcRenderer.send('ytdlp:cancel', id),
  pauseDownload:  (id)  => ipcRenderer.send('ytdlp:pause', id),
  resumeDownload: (id)  => ipcRenderer.send('ytdlp:resume', id),

  // Playlist
  fetchPlaylist: (url) => ipcRenderer.invoke('playlist:fetch', url),

  // File system
  openFile:    (p) => ipcRenderer.send('shell:openFile', p),
  showFolder:  (p) => ipcRenderer.send('shell:showFolder', p),
  chooseFolder: () => ipcRenderer.invoke('dialog:chooseFolder'),

  // Settings
  getSettings: ()       => ipcRenderer.invoke('settings:get'),
  setSetting:  (k, v)   => ipcRenderer.send('settings:set', k, v),

  // Video player
  showPlayer: () => ipcRenderer.send('player:show'),
  hidePlayer: () => ipcRenderer.send('player:hide'),

  // Clear cache
  clearCache: () => ipcRenderer.invoke('clear-cache'),

  // Event listeners (return cleanup fn)
  on: (channel, cb) => {
    const wrapped = (_, ...args) => cb(...args)
    ipcRenderer.on(channel, wrapped)
    return () => ipcRenderer.removeListener(channel, wrapped)
  }
})
