package com.nsl.downloader.vk

import android.net.Uri
import org.json.JSONObject

/**
 * URL classification and payload parsing for VK video.
 *
 * Unlike [com.nsl.downloader.youtube.YouTubeResolver] nothing here touches the
 * network: VK's stream URLs are signed and tied to the visitor's session, so
 * they are read out of the page the user is already on (see [VkScripts]) rather
 * than re-fetched from outside the WebView.
 */
object VkResolver {

    /** One downloadable rendition of a VK video. */
    data class Source(
        val label: String,
        /** Vertical resolution; 0 for an adaptive manifest of unknown height. */
        val height: Int,
        val url: String,
        val type: Type
    ) {
        enum class Type { MP4, HLS, DASH }
    }

    data class Resolved(val title: String, val sources: List<Source>)

    private val VK_HOSTS = setOf(
        "vk.com", "vk.ru", "vkvideo.ru", "vkontakte.ru", "vk.video", "userapi.com"
    )

    /** `video-12345_67890`, `clip-12345_67890`, with or without the leading `-`. */
    private val VIDEO_SEGMENT = Regex("""^(video|clip)-?\d+_\d+""")

    fun isVkUrl(url: String): Boolean {
        val host = hostOf(url) ?: return false
        return VK_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    /**
     * True for a page that shows a single video. Deliberately conservative —
     * the caller also offers the download whenever sources were actually found,
     * so an unrecognised route still works.
     */
    fun isVideoPageUrl(url: String): Boolean = runCatching {
        if (!isVkUrl(url)) return@runCatching false
        val uri = Uri.parse(url)
        // The embeddable player, used by every site that hosts a VK video.
        if (uri.path.orEmpty().trimStart('/').startsWith("video_ext.php")) {
            return@runCatching !uri.getQueryParameter("oid").isNullOrBlank() &&
                !uri.getQueryParameter("id").isNullOrBlank()
        }
        // The id is not always the first segment — opening a video from a
        // playlist gives `/playlist/-123_45/video-123_456`.
        if (uri.pathSegments.any { VIDEO_SEGMENT.containsMatchIn(it) }) return@runCatching true
        // Feed and profile deep links carry the video in `z=` instead.
        val z = uri.getQueryParameter("z").orEmpty()
        VIDEO_SEGMENT.containsMatchIn(z)
    }.getOrDefault(false)

    /**
     * Turns the JSON produced by [VkScripts.COLLECT] into ranked sources.
     *
     * Progressive MP4 comes first and highest-quality-first: those are plain
     * files the downloader can range-request and resume. The adaptive manifests
     * are kept as a fallback for videos VK serves no progressive copy of.
     */
    fun parse(json: String, fallbackTitle: String): Resolved {
        val root = runCatching { JSONObject(json) }.getOrNull()
            ?: return Resolved(cleanTitle(fallbackTitle), emptyList())

        val mp4 = LinkedHashMap<Int, String>()
        val mp4Cache = LinkedHashMap<Int, String>()
        val adaptive = LinkedHashMap<Source.Type, String>()

        val array = root.optJSONArray("sources")
        for (i in 0 until (array?.length() ?: 0)) {
            val item = array?.optJSONObject(i) ?: continue
            val url = item.optString("url").takeIf { it.startsWith("http") } ?: continue
            val type = item.optString("type")
            when (type) {
                "hls" -> adaptive.putIfAbsent(Source.Type.HLS, url)
                "dash" -> adaptive.putIfAbsent(Source.Type.DASH, url)
                else -> {
                    val height = item.optInt("height")
                    // A `<video>` fallback with no known height still beats
                    // offering nothing, so it is filed under 0.
                    if (height != 0 && height !in 100..4320) continue
                    val bucket = if (type == "mp4cache") mp4Cache else mp4
                    bucket.putIfAbsent(height, url)
                }
            }
        }
        // Only where VK offered no primary copy of that height.
        mp4Cache.forEach { (height, url) -> mp4.putIfAbsent(height, url) }

        val sources = buildList {
            mp4.entries
                .sortedByDescending { it.key }
                .forEach { (height, url) ->
                    add(
                        Source(
                            label = if (height > 0) "${height}p" else "Original",
                            height = height,
                            url = url,
                            type = Source.Type.MP4
                        )
                    )
                }
            adaptive[Source.Type.HLS]?.let {
                add(Source("Adaptive (HLS)", 0, it, Source.Type.HLS))
            }
            adaptive[Source.Type.DASH]?.let {
                add(Source("Adaptive (DASH)", 0, it, Source.Type.DASH))
            }
        }

        val title = root.optString("title").ifBlank { fallbackTitle }
        return Resolved(cleanTitle(title), sources)
    }

    /** Strips the site suffix VK appends to every document title. */
    fun cleanTitle(raw: String): String {
        var title = raw.trim()
        listOf(" | VK", " | ВКонтакте", " | VK Видео", " | VK Video", " – VK").forEach {
            if (title.endsWith(it, ignoreCase = true)) {
                title = title.dropLast(it.length).trim()
            }
        }
        return title.ifBlank { "vk_video_${System.currentTimeMillis()}" }
    }

    private fun hostOf(url: String): String? =
        runCatching { Uri.parse(url).host }.getOrNull()
            ?.removePrefix("www.")
            ?.removePrefix("m.")
            ?.lowercase()
}
