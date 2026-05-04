const { contextBridge, ipcRenderer } = require('electron')

// Exposed to every page loaded in the BrowserView.
contextBridge.exposeInMainWorld('__nsl', {
  requestDownload: (videoUrl, pageUrl, pageTitle) =>
    ipcRenderer.send('browser:download-request', { videoUrl, pageUrl, pageTitle }),
  sendSegment: (captureId, type, buf) =>
    ipcRenderer.send('stream:captureChunk', captureId, type, buf),
  endCapture: (captureId) =>
    ipcRenderer.send('stream:captureEnd', captureId),
})
