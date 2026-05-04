const { contextBridge, ipcRenderer } = require('electron')

// Exposed to every page loaded in the BrowserView.
contextBridge.exposeInMainWorld('__nsl', {
  requestDownload: (videoUrl, pageUrl, pageTitle) =>
    ipcRenderer.send('browser:download-request', { videoUrl, pageUrl, pageTitle }),
})
