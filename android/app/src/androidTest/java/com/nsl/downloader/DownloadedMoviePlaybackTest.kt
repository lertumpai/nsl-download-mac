package com.nsl.downloader

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.media3.ui.PlayerView
import com.nsl.downloader.player.PlayerActivity
import android.media.MediaPlayer
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import android.view.SurfaceView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import com.nsl.downloader.util.MediaStorage
import com.nsl.downloader.service.Mp4Muxer
import com.nsl.downloader.data.AppDatabase
import com.nsl.downloader.data.VideoEntity
import com.nsl.downloader.data.DownloadStatus
import kotlinx.coroutines.runBlocking
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Exercises real playback engines, not just metadata/thumbnail extraction. */
class DownloadedMoviePlaybackTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    @Test fun downloadedMoviesPlayInExoPlayerAndPlatformPlayer() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("savedMovies") == "true")
        for (index in 1..3) {
            val file = File(context.getExternalFilesDir(null), "movie-full-$index/media.mp4")
            assertTrue("Missing fixture $file", file.isFile)
            val repaired = File(file.parentFile, "fixed.mp4")
            val remux = InstrumentationRegistry.getArguments().getString("remuxMovies") == "true"
            if (remux) assertTrue("Remux failed", Mp4Muxer.mux(file, file, repaired))
            val target = if (remux) repaired else file
            checkPlatform(Uri.fromFile(target))
            checkExo(Uri.fromFile(target))
            if (remux) {
                assertTrue(file.delete())
                assertTrue(repaired.renameTo(file))
            }
        }
    }

    @Test fun publishedMoviePlaysThroughItsContentUri() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("savedMovies") == "true")
        val original = File(context.getExternalFilesDir(null), "movie-full-3/media.mp4")
        val scratch = File(context.cacheDir, "publish-playback.mp4")
        original.copyTo(scratch, overwrite = true)
        val location = MediaStorage.publish(context, scratch, "playback-test.mp4", "video/mp4", "Playback test")
        assertNotNull(location)
        val uri = Uri.parse(location)
        try {
            checkExo(uri)
            checkPlatform(uri)
        } finally {
            context.contentResolver.delete(uri, null, null)
            scratch.delete()
        }
    }

    @Test fun freshHlsMuxAndAutomaticRepairBothPlay() = runBlocking {
        val fixtures = InstrumentationRegistry.getArguments().getString("tsFixtures")
        assumeTrue("TS fixtures are opt-in", fixtures != null)
        val video = File(fixtures!!, "video.ts")
        val audio = File(fixtures, "audio.ts")
        val fresh = File(context.cacheDir, "fresh-hls.mp4")
        val broken = File(context.cacheDir, "old-hls.mp4")
        assertTrue(Mp4Muxer.mux(video, audio, fresh))
        assertFalse(Mp4Muxer.needsAudioRepair(context, Uri.fromFile(fresh)))
        checkExo(Uri.fromFile(fresh))
        checkPlatform(Uri.fromFile(fresh))
        fresh.delete()

        // Reproduce the previous app's TS-to-MP4 writer exactly: bundled ADTS
        // bytes were passed to the MP4 muxer as though they were raw AAC.
        writeOldMux(video, audio, broken)
        assertTrue(Mp4Muxer.needsAudioRepair(context, Uri.fromFile(broken)))
        val original = MediaStorage.publish(context, broken, "old-audio-fixture.mp4", "video/mp4", "Playback test")!!
        val dao = AppDatabase.getInstance(context).videoDao()
        val id = dao.insert(VideoEntity(title = "Playback repair fixture", sourceUrl = "https://example.invalid/movie.m3u8",
            localPath = original, status = DownloadStatus.COMPLETED))
        try {
            checkExo(Uri.parse(original), id)
            val updated = dao.getById(id)!!
            assertNotEquals(original, updated.localPath)
            assertTrue("Repair must preserve the original", MediaStorage.exists(context, original))
            assertFalse(Mp4Muxer.needsAudioRepair(context, Uri.parse(updated.localPath)))
            checkPlatform(Uri.parse(updated.localPath))
            checkExo(Uri.parse(updated.localPath), id)
            assertEquals("Repair must run only once", updated.localPath, dao.getById(id)!!.localPath)
        } finally {
            dao.getById(id)?.let { MediaStorage.delete(context, it.localPath) }
            MediaStorage.delete(context, original)
            dao.deleteById(id)
        }
    }

    @Test fun longFileNamesKeepTheirExtensionAndFileUrisRemainValid() {
        assertTrue(MediaStorage.sanitizeFileName("Movie ".repeat(30) + ".mp4").endsWith(".mp4"))
        assertEquals(80, MediaStorage.sanitizeFileName("x".repeat(100) + ".mp4").length)
        assertEquals(Uri.parse("file:///sdcard/movie.mp4"), MediaStorage.toUri("file:///sdcard/movie.mp4"))
    }

    private fun writeOldMux(video: File, audio: File, output: File) {
        val v = MediaExtractor()
        val a = MediaExtractor()
        val mux = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            v.setDataSource(video.absolutePath)
            a.setDataSource(audio.absolutePath)
            val tracks = listOf(v to "video/", a to "audio/").map { (e, prefix) ->
                val track = (0 until e.trackCount).first { e.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith(prefix) }
                e.selectTrack(track)
                mux.addTrack(e.getTrackFormat(track))
            }
            mux.start()
            val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
            listOf(v, a).forEachIndexed { index, e ->
                while (true) {
                    buffer.clear()
                    val size = e.readSampleData(buffer, 0)
                    if (size < 0) break
                    val info = MediaCodec.BufferInfo()
                    info.set(0, size, e.sampleTime, if (e.sampleFlags and 1 != 0) 1 else 0)
                    mux.writeSampleData(tracks[index], buffer, info)
                    if (!e.advance()) break
                }
            }
            mux.stop()
        } finally { mux.release(); v.release(); a.release() }
    }

    private fun checkExo(uri: Uri, videoId: Long = -1) {
        val intent = Intent(context, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_PATH, if (uri.scheme == "file") uri.path else uri.toString())
            .putExtra(PlayerActivity.EXTRA_ID, videoId)
        ActivityScenario.launch<PlayerActivity>(intent).use { scenario ->
            val end = SystemClock.elapsedRealtime() + 30_000
            var position = 0L
            var rendered = 0
            var audioRendered = 0
            var diagnostics = ""
            var error: PlaybackException? = null
            while (SystemClock.elapsedRealtime() < end) {
                scenario.onActivity { activity ->
                    val player = activity.findViewById<PlayerView>(R.id.playerView).player as? ExoPlayer ?: return@onActivity
                    position = player.currentPosition
                    diagnostics = "state=${player.playbackState} ready=${player.playWhenReady} loading=${player.isLoading} buffered=${player.bufferedPosition} suppressed=${player.playbackSuppressionReason}"
                    error = player.playerError
                    player.videoDecoderCounters?.ensureUpdated()
                    rendered = player.videoDecoderCounters?.renderedOutputBufferCount ?: 0
                    player.audioDecoderCounters?.ensureUpdated()
                    audioRendered = player.audioDecoderCounters?.renderedOutputBufferCount ?: 0
                }
                if (error != null || (rendered > 10 && audioRendered > 10 && position > 1500)) break
                SystemClock.sleep(100)
            }
            error?.let { throw AssertionError("ExoPlayer could not play $uri: ${it.errorCodeName}", it) }
            assertTrue("ExoPlayer did not render/advance $uri ($position ms, $rendered video frames, $audioRendered audio buffers, $diagnostics)", rendered > 10 && audioRendered > 10 && position > 1500)
            var duration = 0L
            scenario.onActivity { duration = it.findViewById<PlayerView>(R.id.playerView).player!!.duration }
            for (target in listOf(duration / 2, (duration - 10_000).coerceAtLeast(0))) {
                var before = 0
                scenario.onActivity {
                    val player = it.findViewById<PlayerView>(R.id.playerView).player as ExoPlayer
                    before = player.videoDecoderCounters!!.renderedOutputBufferCount
                    player.seekTo(target)
                }
                val deadline = SystemClock.elapsedRealtime() + 20_000
                var passed = false
                while (!passed && SystemClock.elapsedRealtime() < deadline) {
                    scenario.onActivity {
                        val player = it.findViewById<PlayerView>(R.id.playerView).player as ExoPlayer
                        player.playerError?.let { throw AssertionError("Seek failed", it) }
                        passed = player.currentPosition >= target + 1000 &&
                            player.videoDecoderCounters!!.renderedOutputBufferCount > before + 10
                    }
                    SystemClock.sleep(100)
                }
                assertTrue("Playback did not resume after seeking to $target", passed)
            }
        }
    }

    private fun checkPlatform(uri: Uri) {
        val error = AtomicReference<String?>()
        val rendered = AtomicBoolean(false)
        lateinit var player: MediaPlayer
        lateinit var surfaceView: SurfaceView
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            scenario.onActivity { activity ->
                surfaceView = SurfaceView(activity)
                activity.setContentView(surfaceView)
            }
            val readyDeadline = SystemClock.elapsedRealtime() + 10_000
            var ready = false
            while (!ready && SystemClock.elapsedRealtime() < readyDeadline) {
                main { ready = surfaceView.holder.surface.isValid }
                SystemClock.sleep(100)
            }
            assertTrue("Playback surface unavailable", ready)
            main {
                player = MediaPlayer()
                player.setDisplay(surfaceView.holder)
                player.setOnErrorListener { _, what, extra -> error.set("$what/$extra"); true }
                player.setOnInfoListener { _, what, _ ->
                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) rendered.set(true)
                    false
                }
                player.setOnPreparedListener { it.start() }
                player.setDataSource(context, uri)
                player.prepareAsync()
            }
            try {
                val end = SystemClock.elapsedRealtime() + 30_000
                var position = 0
                while (SystemClock.elapsedRealtime() < end && error.get() == null) {
                    main { position = runCatching { player.currentPosition }.getOrDefault(0) }
                    if (rendered.get() && position > 1500) break
                    SystemClock.sleep(100)
                }
                assertNull("Platform player failed for $uri", error.get())
                assertTrue("Platform player did not render/advance $uri ($position ms)", rendered.get() && position > 1500)
            } finally { main { player.release() } }
        }
    }
}
