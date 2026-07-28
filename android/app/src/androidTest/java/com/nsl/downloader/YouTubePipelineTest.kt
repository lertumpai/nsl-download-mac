package com.nsl.downloader

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nsl.downloader.service.Mp3Transcoder
import com.nsl.downloader.service.Mp4Muxer
import com.nsl.downloader.util.MediaStorage
import com.nsl.downloader.youtube.YouTubeResolver
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * End-to-end checks for the parts that can only fail on a real device: YouTube
 * extraction (Rhino must run the player JS), `MediaCodec`/LAME transcoding,
 * `MediaMuxer` remuxing, and the MediaStore hand-off.
 *
 * These hit the live network on purpose — the point is to prove the extractor
 * still works against today's YouTube, which no offline fixture can show.
 */
@RunWith(AndroidJUnit4::class)
class YouTubePipelineTest {

    companion object {
        /** "Me at the zoo": 19 seconds and the most stable URL on the site. */
        private const val VIDEO_URL = "https://www.youtube.com/watch?v=jNQXAC9IVRw"
        private const val PLAYLIST_URL =
            "https://www.youtube.com/playlist?list=PL590L5WQmH8dpP0RyH5pCfIaDEdt9nk7r"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Mobile Safari/537.36"

        private val http = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        @JvmStatic
        @BeforeClass
        fun setUp() {
            YouTubeResolver.ensureInitialised(USER_AGENT)
        }
    }

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val scratch: File
        get() = File(context.cacheDir, "test").also { it.mkdirs() }

    @Test
    fun resolvesVideoStreams() {
        val resolved = YouTubeResolver.resolveVideo(VIDEO_URL)

        assertEquals("Me at the zoo", resolved.title)
        assertTrue("no video options", resolved.videoOptions.isNotEmpty())
        assertTrue("no audio options", resolved.audioOptions.isNotEmpty())

        // Options must be unique per height and sorted best-first.
        val heights = resolved.videoOptions.map { it.height }
        assertEquals(heights.distinct(), heights)
        assertEquals(heights.sortedDescending(), heights)

        // Anything above 360p is a split rendition that has to be remuxed.
        resolved.videoOptions.filter { it.height > 360 }.forEach {
            assertTrue("${it.height}p should need muxing", it.needsMux)
            assertNotNull(it.audioUrl)
        }
    }

    @Test
    fun classifiesUrls() {
        assertEquals(YouTubeResolver.Kind.STREAM, YouTubeResolver.classify(VIDEO_URL))
        assertEquals(YouTubeResolver.Kind.PLAYLIST, YouTubeResolver.classify(PLAYLIST_URL))
        // YouTube redirects the WebView to the mobile host, which is the URL the
        // browser actually classifies when deciding to offer the download button.
        assertEquals(
            YouTubeResolver.Kind.STREAM,
            YouTubeResolver.classify("https://m.youtube.com/watch?v=jNQXAC9IVRw")
        )
        assertEquals(
            YouTubeResolver.Kind.STREAM,
            YouTubeResolver.classify("https://youtu.be/jNQXAC9IVRw")
        )
        assertEquals(
            YouTubeResolver.Kind.OTHER, YouTubeResolver.classify("https://example.com/a.mp4")
        )
        assertTrue(YouTubeResolver.isYouTubeUrl("https://youtu.be/jNQXAC9IVRw"))
        assertTrue(YouTubeResolver.isYouTubeUrl("https://m.youtube.com/watch?v=x"))
        assertEquals(
            "ABC", YouTubeResolver.playlistIdOf("https://www.youtube.com/watch?v=x&list=ABC")
        )
    }

    @Test
    fun resolvesPlaylist() {
        val playlist = YouTubeResolver.resolvePlaylist(PLAYLIST_URL, maxItems = 30)

        assertTrue("playlist title missing", playlist.title.isNotBlank())
        assertTrue("playlist had no items", playlist.items.size > 1)
        playlist.items.forEach {
            assertTrue("bad item url ${it.url}", YouTubeResolver.isYouTubeUrl(it.url))
            assertTrue("blank item title", it.title.isNotBlank())
        }
    }

    /** Downloads the real audio track and encodes it with the pure-Java LAME. */
    @Test
    fun transcodesAudioToPlayableMp3() {
        val resolved = YouTubeResolver.resolveVideo(VIDEO_URL)
        val audio = resolved.audioOptions.first()
        val source = File(scratch, "audio.${audio.suffix}")
        download(audio.url, source)
        assertTrue("audio download empty", source.length() > 0)

        val containerDuration = run {
            val probe = MediaExtractor()
            try {
                probe.setDataSource(source.absolutePath)
                runCatching { probe.getTrackFormat(0).getLong(MediaFormat.KEY_DURATION) }
                    .getOrDefault(-1L)
            } finally {
                probe.release()
            }
        }

        val mp3 = File(scratch, "audio.mp3")
        val reported = mutableListOf<Int>()
        assertTrue(
            "transcode failed",
            Mp3Transcoder.transcode(
                source, mp3, 128,
                durationUsHint = resolved.durationSeconds * 1_000_000
            ) { reported.add(it) }
        )

        assertTrue("mp3 is empty", mp3.length() > 1000)
        val diagnostics = "container duration=$containerDuration us, " +
            "extractor duration=${resolved.durationSeconds}s, samples=$reported"
        assertTrue("no progress reported ($diagnostics)", reported.size > 1)
        assertTrue("progress must be monotonic ($diagnostics)", reported == reported.sorted())
        assertEquals("progress must finish at 100 ($diagnostics)", 100, reported.last())

        // An MP3 frame header starts with 11 set bits.
        val head = mp3.readBytes().take(4)
        val sync = (head[0].toInt() and 0xFF) == 0xFF &&
            (head[1].toInt() and 0xE0) == 0xE0
        val id3 = String(mp3.readBytes(), 0, 3) == "ID3"
        assertTrue("not an MP3 bitstream", sync || id3)

        // The platform must agree it is decodable MPEG audio.
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(mp3.absolutePath)
            assertEquals(1, extractor.trackCount)
            val format = extractor.getTrackFormat(0)
            assertEquals("audio/mpeg", format.getString(MediaFormat.KEY_MIME))
            assertTrue(format.getInteger(MediaFormat.KEY_SAMPLE_RATE) > 0)
        } finally {
            extractor.release()
        }
    }

    /** Downloads a split rendition and proves the remux yields a real MP4. */
    @Test
    fun muxesSplitStreamsIntoOneMp4() {
        val resolved = YouTubeResolver.resolveVideo(VIDEO_URL)
        val option = resolved.videoOptions.firstOrNull { it.needsMux }
            ?: return // No split rendition offered for this video; nothing to prove.

        val videoPart = File(scratch, "v.mp4")
        val audioPart = File(scratch, "a.${option.audioSuffix}")
        download(option.videoUrl, videoPart)
        download(option.audioUrl!!, audioPart)

        val output = File(scratch, "muxed.mp4")
        assertTrue("mux failed", Mp4Muxer.mux(videoPart, audioPart, output))
        assertTrue("muxed file empty", output.length() > 0)

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(output.absolutePath)
            val mimes = (0 until extractor.trackCount).map {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty()
            }
            assertTrue("no video track in $mimes", mimes.any { it.startsWith("video/") })
            assertTrue("no audio track in $mimes", mimes.any { it.startsWith("audio/") })
        } finally {
            extractor.release()
        }
    }

    /** The MediaStore hand-off that every finished download goes through. */
    @Test
    fun publishesIntoSharedStorage() {
        val payload = ByteArray(64 * 1024) { (it % 251).toByte() }
        val source = File(scratch, "publish-me.mp4").apply { writeBytes(payload) }

        val folder = "NSLTest-${System.currentTimeMillis()}"
        val location = MediaStorage.publish(context, source, "clip.mp4", "video/mp4", folder)
        assertNotNull("publish returned null", location)
        location!!

        try {
            assertTrue("source not consumed", !source.exists())
            assertTrue("published item missing", MediaStorage.exists(context, location))

            val readBack = context.contentResolver
                .openInputStream(MediaStorage.toUri(location))!!
                .use { it.readBytes() }
            assertTrue("payload changed", payload.contentEquals(readBack))
        } finally {
            MediaStorage.delete(context, location)
            MediaStorage.removeFolderIfEmpty(folder)
        }
    }

    @Test
    fun sanitizesNamesForMediaStore() {
        assertEquals("a_b_c", MediaStorage.sanitizeName("a/b\\c"))
        assertEquals("no_colon", MediaStorage.sanitizeName("no:colon"))
        assertTrue(MediaStorage.sanitizeName("   ").isNotBlank())
        assertTrue(MediaStorage.relativeDir(null).endsWith("NSL Downloader"))
        assertTrue(MediaStorage.relativeDir("Clips").endsWith("NSL Downloader/Clips"))
    }

    private fun download(url: String, target: File) {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        http.newCall(request).execute().use { response ->
            assertTrue("HTTP ${response.code} for $url", response.isSuccessful)
            target.outputStream().use { out -> response.body!!.byteStream().copyTo(out) }
        }
    }
}
