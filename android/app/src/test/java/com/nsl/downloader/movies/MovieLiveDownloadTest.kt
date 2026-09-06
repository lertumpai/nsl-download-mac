package com.nsl.downloader.movies

import com.google.gson.GsonBuilder
import com.nsl.downloader.service.HlsDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** Opt-in full transfers: NSL_MOVIE_CASES supplies one 037 movie page per line. */
class MovieLiveDownloadTest {
    @Test fun downloadThreeCompleteMovies() = runBlocking {
        val cases = System.getenv("NSL_MOVIE_CASES")
        assumeTrue("Live movie tests are opt-in", !cases.isNullOrBlank())
        val root = File(System.getenv("NSL_MOVIE_OUTPUT") ?: "/tmp/nsl-movie-downloads").also { it.mkdirs() }
        val pages = File(cases!!).readLines().filter { it.isNotBlank() }
        assertEquals(3, pages.size)
        pages.mapIndexed { index, page -> async(Dispatchers.IO) {
            val dir = File(root, "movie-${index + 1}").also { it.mkdirs() }
            val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS).build()
            val agent = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/130.0.0.0 Mobile Safari/537.36"
            val sources = MovieResolver(client).resolve(page, agent)
            assertTrue("No movie sources for $page", sources.isNotEmpty())
            val source = sources.first()
            val hls = HlsDownloader(client)
            val variant = hls.listVariants(source.url, source.headers).first()
            val details = linkedMapOf<String, Any>("page" to page, "source" to source,
                "variant" to variant, "status" to "downloading")
            val gson = GsonBuilder().setPrettyPrinting().create()
            File(dir, "result.json").writeText(gson.toJson(details))
            for ((name, url) in listOf("video" to variant.url, "audio" to variant.audioUrl)) {
                if (url == null) continue
                val req = Request.Builder().url(url).apply { source.headers.forEach { (k, v) -> header(k, v) } }.build()
                val manifest = client.newCall(req).execute().use { assertTrue(it.isSuccessful); it.body!!.string() }
                assertTrue("Must be a complete VOD", manifest.contains("#EXT-X-ENDLIST"))
                File(dir, "$name.m3u8").writeText(manifest)
                val duration = Regex("#EXTINF:([0-9.]+)").findAll(manifest).sumOf { it.groupValues[1].toDouble() }
                details["${name}DurationSeconds"] = duration
                var last = -1
                val ok = hls.download(url, File(dir, "$name.ts"), source.headers) { percent, bytes ->
                    if (percent != last) {
                        last = percent
                        File(dir, "$name-progress.txt").writeText("$percent% $bytes bytes")
                    }
                }
                assertTrue("$name download failed for $page", ok)
                assertTrue(File(dir, "$name.ts").length() > 1_000_000)
            }
            details["status"] = "complete"
            File(dir, "result.json").writeText(gson.toJson(details))
        } }.awaitAll()
        Unit
    }
}
