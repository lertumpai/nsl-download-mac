// Some embedded players serve HLS as text/plain at URLs without an extension.
function isStreamHlsMaster(value) {
  try {
    const u = new URL(value)
    return /(^|\.)streamhls\.com$/i.test(u.hostname) && /^\/hls\/[a-f\d]{32}\/master\/?$/i.test(u.pathname)
  } catch { return false }
}

function is037Page(value) {
  try { return /(^|\.)037hddmovies\.com$/i.test(new URL(value).hostname) } catch { return false }
}

function isKnownVideoAd(value) {
  try {
    const u = new URL(value)
    return /(^|\.)(googles\.video|cdend\.com)$/i.test(u.hostname)
  } catch { return false }
}

function streamRank(value, type) {
  if (!/^https?:\/\//i.test(value || '')) return 0
  if (/^https?:\/\/vk\.com\/(video[-\d_]+|video_ext\.php)/.test(value)) return 60
  if (type === 'YouTube' && /^https?:\/\/(www\.)?youtube\.com\/watch\?/.test(value)) return 60
  if (type === 'Twitter' && /^https?:\/\/(www\.)?(twitter|x)\.com\/[^/]+\/status\/\d+/.test(value)) return 60
  if (isStreamHlsMaster(value)) return 50
  if (type === 'HLS' || type === 'DASH') return 30
  if (type === 'Video') return 20
  return 10
}

function selectStream(existing, candidate) {
  if (!candidate?.url) return existing
  if (!existing?.url || streamRank(candidate.url, candidate.type) > streamRank(existing.url, existing.type)) return candidate
  // Refresh headers for the same stream; don't replace a master with a rendition.
  if (candidate.url === existing.url) return { ...existing, ...candidate, headers: { ...existing.headers, ...candidate.headers } }
  return existing
}

function requestHeaderArgs(pageURL, targetURL, headers = {}, userAgent) {
  const normalized = Object.fromEntries(Object.entries(headers).map(([k, v]) => [k.toLowerCase(), v]))
  const args = ['--user-agent', normalized['user-agent'] || userAgent]
  if (targetURL !== pageURL) {
    const referer = normalized.referer || pageURL
    if (/^https?:\/\//i.test(referer) && !/[\r\n]/.test(referer)) args.push('--referer', referer)
    const origin = normalized.origin
    if (origin && !/[\r\n]/.test(origin)) args.push('--add-header', `Origin: ${origin}`)
  }
  return args
}

// This provider prefixes MPEG-TS fragments with PNG bytes. Force the merger's
// input demuxer only; probing the assembled tracks would incorrectly select PNG.
function streamDownloadArgs(url) {
  return isStreamHlsMaster(url)
    ? ['--postprocessor-args', 'Merger+ffmpeg_i:-f mpegts']
    : []
}

// stdout chunks need not end on line boundaries (including Unicode filenames).
function lineReader(onLine) {
  let pending = ''
  return {
    push(chunk) {
      pending += chunk
      const lines = pending.split(/\r?\n/)
      pending = lines.pop()
      lines.forEach(onLine)
    },
    end() { if (pending) onLine(pending); pending = '' }
  }
}

module.exports = { isStreamHlsMaster, is037Page, isKnownVideoAd, selectStream, requestHeaderArgs, streamDownloadArgs, lineReader }
