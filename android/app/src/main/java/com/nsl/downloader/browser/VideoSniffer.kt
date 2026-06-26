package com.nsl.downloader.browser

import com.nsl.downloader.util.isLikelyVideoUrl
import java.util.concurrent.ConcurrentHashMap

/**
 * Collects candidate video URLs seen during WebView resource loading.
 * Deduplicates and exposes the most recent set per page.
 */
class VideoSniffer {

    data class Candidate(
        val url: String,
        val mimeType: String?,
        val headers: Map<String, String>,
        val seenAt: Long
    )

    private val found = ConcurrentHashMap<String, Candidate>()
    var onNewVideo: ((Candidate) -> Unit)? = null

    fun consider(url: String, mimeType: String?, headers: Map<String, String> = emptyMap()) {
        if (url.isBlank()) return
        if (!isLikelyVideoUrl(url, mimeType)) return
        val key = normalizeKey(url)
        if (found.containsKey(key)) return
        val candidate = Candidate(url, mimeType, headers, System.currentTimeMillis())
        found[key] = candidate
        onNewVideo?.invoke(candidate)
    }

    fun snapshot(): List<Candidate> = found.values.sortedByDescending { it.seenAt }

    fun clear() = found.clear()

    fun count(): Int = found.size

    // Strip volatile query params (tokens/segment indices) so HLS variants dedupe
    private fun normalizeKey(url: String): String {
        val base = url.substringBefore('?')
        return base
    }
}
