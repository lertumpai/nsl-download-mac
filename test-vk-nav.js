function isVideoPage(url) {
  if (url.match(/^https?:\/\/vk\.com\/(video[-\d_]+|video_ext\.php)/)) return true;
  if (url.match(/^https?:\/\/(www\.)?vkvideo\.ru\/video[-\d_]+/)) return true;
  if (url.includes('youtube.com/watch')) return true;
  if (url.match(/^https?:\/\/(www\.)?twitter\.com\/.*\/status\/\d+/)) return true;
  if (url.match(/^https?:\/\/(www\.)?x\.com\/.*\/status\/\d+/)) return true;
  return false;
}
console.log(isVideoPage('https://vkvideo.ru/video-229278647_456239022'));
