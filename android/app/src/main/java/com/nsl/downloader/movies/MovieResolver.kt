package com.nsl.downloader.movies

import com.google.gson.JsonParser
import com.google.gson.JsonParseException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException

/** Follows the public player configuration, including LeoPlayer's backup servers. */
class MovieResolver(private val client: OkHttpClient) {
    data class Source(val url: String, val label: String, val headers: Map<String, String>)

    companion object {
        fun isMoviePage(url: String): Boolean {
            val u = url.toHttpUrlOrNull() ?: return false
            return (u.host == "037hddmovies.com" || u.host == "www.037hddmovies.com") &&
                Regex("^/\\d{4}/\\d{2}/\\d{2}/.+").matches(u.encodedPath)
        }

        internal fun decode(value: String) = value.replace("\\/", "/").replace("&amp;", "&")
        internal fun embeds(html: String): List<Pair<String, String>> =
            Regex("<iframe\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html).mapNotNull { tag ->
                fun attr(name: String) = Regex("\\b$name\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)
                    .find(tag.value)?.groupValues?.get(1)
                val url = attr("src")?.let(::decode) ?: return@mapNotNull null
                val host = url.toHttpUrlOrNull()?.host ?: return@mapNotNull null
                if (host != "www.leoplayer7.com" && host != "leoplayer7.com") return@mapNotNull null
                url to (attr("title") ?: "Movie")
            }.distinct().toList()

        internal fun playlists(html: String): List<String> =
            Regex("(?:window\\.)?hls\\s*=\\s*[\"']([^\"']+)[\"']")
                .findAll(html).map { decode(it.groupValues[1]) }
                .filter { it.toHttpUrlOrNull() != null && it.contains(".m3u8") }.distinct().toList()
    }

    fun resolve(pageUrl: String, userAgent: String): List<Source> {
        require(isMoviePage(pageUrl)) { "Unsupported movie page" }
        val sources = mutableListOf<Source>()
        for ((embed, label) in embeds(fetch(pageUrl, userAgent, pageUrl)).take(6)) {
            // A broken language/server must not prevent the remaining ones from working.
            try {
                val html = fetch(embed, userAgent, pageUrl)
                val config = Regex("atomic\\((\\{.*?\\})\\)\\.\\${'$'}mount", RegexOption.DOT_MATCHES_ALL)
                    .find(html)?.groupValues?.get(1) ?: continue
                val entries = JsonParser.parseString(config).asJsonObject.getAsJsonArray("playlist") ?: continue
                for (entry in entries.take(4)) {
                    val e = entry.asJsonObject
                    val api = decode(e.get("api")?.asString ?: continue)
                    if (!sameHost(api, embed)) continue
                    val groups = JsonParser.parseString(fetch(api, userAgent, embed, e.get("token")?.asString))
                        .asJsonObject.getAsJsonArray("data") ?: continue
                    for (group in groups.take(6)) {
                        try {
                            val g = group.asJsonObject
                            val groupApi = decode(g.get("api")?.asString ?: continue)
                            if (!sameHost(groupApi, embed)) continue
                            val media = JsonParser.parseString(fetch(groupApi, userAgent, embed, g.get("token")?.asString))
                                .asJsonObject.getAsJsonObject("data")?.getAsJsonObject("source") ?: continue
                            val player = decode(media.get("url")?.asString ?: continue)
                            val playerUrl = player.toHttpUrlOrNull() ?: continue
                            if (playerUrl.scheme != "https") continue
                            val urls = if (media.get("type")?.asString == "embed") {
                                playlists(fetch(player, userAgent, embed))
                            } else listOf(player).filter { it.contains(".m3u8") }
                            for (url in urls) {
                                val headers = mapOf("User-Agent" to userAgent, "Referer" to player,
                                    "Origin" to "${playerUrl.scheme}://${playerUrl.host}")
                                val manifest = fetch(url, userAgent, player)
                                if (manifest.trimStart().startsWith("#EXTM3U")) {
                                    sources.add(Source(url, label, headers))
                                    break
                                }
                            }
                            if (sources.any { it.label == label }) break
                        } catch (_: IOException) { } catch (_: IllegalStateException) { } catch (_: JsonParseException) { }
                    }
                }
            } catch (_: IOException) { } catch (_: IllegalStateException) { } catch (_: JsonParseException) { }
        }
        return sources.distinctBy { it.url }
    }

    private fun sameHost(a: String, b: String) =
        a.toHttpUrlOrNull()?.let { it.scheme == "https" && it.host == b.toHttpUrlOrNull()?.host } == true

    private fun fetch(url: String, userAgent: String, referer: String, token: String? = null): String {
        val request = Request.Builder().url(url).header("User-Agent", userAgent).header("Referer", referer)
        token?.let { request.header("X-Auth-Token", it) }
        return client.newCall(request.build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Player HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty player response")
            val source = body.source()
            source.request(2_000_001)
            val text = source.readUtf8(minOf(source.buffer.size, 2_000_001))
            if (text.length > 2_000_000) throw IOException("Player response too large")
            text
        }
    }
}
