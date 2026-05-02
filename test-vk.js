const { URL } = require('url');

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
  
  if (normalizedPage && normalizedPage.match(/^https?:\/\/vk\.com\/video[-\d_]+/)) {
    targetUrl = normalizedPage
  } else if (streamUrl) {
    targetUrl = streamUrl
  }
  
  return normalizeVKUrl(targetUrl)
}

console.log(getTargetUrl('https://vk.com/video?z=video-22822305_456239018%2Fpl_cat_trends', 'https://vk52-node3.m3u8'))
console.log(getTargetUrl('https://vk.com/video-22822305_456239018', 'https://vk52-node3.m3u8'))
console.log(getTargetUrl('https://vk.com/feed', 'https://vk.com/video-22822305_456239018'))
console.log(getTargetUrl('https://vk.com/feed', 'https://vk52-node3.m3u8'))
