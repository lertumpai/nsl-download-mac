package com.nsl.downloader.util

enum class VideoType { DIRECT, HLS, DASH }

/**
 * Detects the streaming/container type from a URL. Falls back to DIRECT.
 */
fun detectVideoType(url: String): VideoType {
    val lower = url.substringBefore('?').lowercase()
    return when {
        lower.contains(".m3u8") -> VideoType.HLS
        lower.contains(".mpd") -> VideoType.DASH
        else -> VideoType.DIRECT
    }
}

/** File extensions and patterns we treat as downloadable video. */
private val VIDEO_EXTENSIONS = listOf(
    ".mp4", ".m3u8", ".mpd", ".webm", ".mkv", ".mov", ".avi",
    ".flv", ".ts", ".m4v", ".3gp", ".ogv", ".f4v", ".wmv"
)

private val VIDEO_MIME_HINTS = listOf(
    "video/", "application/vnd.apple.mpegurl", "application/x-mpegurl",
    "application/dash+xml", "application/octet-stream"
)

/** Heuristic: is this URL likely a video resource? */
fun isLikelyVideoUrl(url: String, mimeType: String? = null): Boolean {
    val lower = url.substringBefore('?').lowercase()
    if (VIDEO_EXTENSIONS.any { lower.contains(it) }) return true
    if (mimeType != null && VIDEO_MIME_HINTS.any { mimeType.lowercase().startsWith(it) }) {
        // octet-stream only counts if URL looks media-ish
        if (mimeType.lowercase().startsWith("application/octet-stream")) {
            return VIDEO_EXTENSIONS.any { lower.contains(it) }
        }
        return true
    }
    // Common streaming query patterns
    if (lower.contains("/manifest") || lower.contains("videoplayback")) return true
    return false
}

fun guessTitleFromUrl(url: String): String {
    val name = url.substringBefore('?').substringAfterLast('/')
    return name.substringBeforeLast('.').ifBlank { "video_${System.currentTimeMillis()}" }
}
