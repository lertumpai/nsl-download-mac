const { contextBridge, ipcRenderer } = require('electron')

// Exposed to every page loaded in the BrowserView.
// Injected overlay/context-menu code calls window.__nsl.requestDownload(...)
contextBridge.exposeInMainWorld('__nsl', {
  requestDownload: (videoUrl, pageUrl, pageTitle) =>
    ipcRenderer.send('browser:download-request', { videoUrl, pageUrl, pageTitle })
})
