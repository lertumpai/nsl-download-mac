package com.nsl.downloader

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import androidx.test.platform.app.InstrumentationRegistry
import com.nsl.downloader.movies.MovieResolver
import com.nsl.downloader.service.HlsDownloader
import com.nsl.downloader.service.HlsMovieDownloader
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** Opt-in live Android download/mux test. movieFull=true transfers complete movies. */
class MoviePipelineTest {
    @Test fun threeMoviesProducePlayableMp4WithSound() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val casePath = args.getString("movieCases")
        val full = args.getString("movieFull") == "true"
        assumeTrue("Live tests are opt-in", casePath != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pages = File(casePath!!).readLines().filter { it.isNotBlank() }
        assertEquals(3, pages.size)
        val client = OkHttpClient.Builder().readTimeout(45, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val response = chain.proceed(chain.request())
                val url = chain.request().url
                if (!full && (url.encodedPath.endsWith("/index") || url.encodedPath.endsWith("/audio_0"))) {
                    val body = response.body!!
                    val type = body.contentType()
                    val text = body.string()
                    if (text.startsWith("#EXTM3U")) {
                        var count = 0
                        val lines = text.lines().takeWhile { line ->
                            if (line.isNotBlank() && !line.startsWith("#")) count++
                            count <= 3
                        }.dropLastWhile { it.startsWith("#EXTINF") }
                        response.newBuilder().body((lines.joinToString("\n") + "\n#EXT-X-ENDLIST\n").toResponseBody(type)).build()
                    } else response.newBuilder().body(text.toResponseBody(type)).build()
                } else response
            }.build()
        val agent = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/130.0.0.0 Mobile Safari/537.36"
        pages.forEachIndexed { index, page ->
            val source = MovieResolver(client).resolve(page, agent).first()
            val dir = File(context.getExternalFilesDir(null), "movie-${if (full) "full" else "test"}-${index + 1}").also { it.mkdirs() }
            var last = -1
            val output = HlsMovieDownloader(HlsDownloader(client)).download(source.url, dir, source.headers) { percent, bytes ->
                if (percent != last) {
                    last = percent
                    File(dir, "progress.txt").writeText("$percent% $bytes bytes")
                }
            }
            assertNotNull("Download or mux failed: $page", output)
            assertEquals("mp4", output!!.extension)
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(output.absolutePath)
                val mimes = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty() }
                assertTrue(mimes.any { it.startsWith("video/") })
                assertTrue(mimes.any { it.startsWith("audio/") })
            } finally { extractor.release() }
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(output.absolutePath)
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                assertTrue(duration > if (full) 3_600_000 else 5000)
                val frame = retriever.getFrameAtTime(2_000_000)
                assertNotNull("Cannot decode movie frame", frame)
                frame?.recycle()
                File(dir, "verified.txt").writeText("$page\n${output.length()} bytes\n$duration ms\nvideo + audio; frame decoded\n")
            } finally { retriever.release() }
            // Keep the playable result, release scratch tracks so three complete
            // movie outputs fit on a small emulator data partition.
            dir.listFiles()?.filter { it.name.endsWith(".ts") || it.name.endsWith(".part") }?.forEach { it.delete() }
        }
    }
}
