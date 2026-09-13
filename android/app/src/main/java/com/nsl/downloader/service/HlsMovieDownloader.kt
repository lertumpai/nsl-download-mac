package com.nsl.downloader.service

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/** Keeps an HLS master's audio rendition attached to its video through download and mux. */
class HlsMovieDownloader(private val hls: HlsDownloader) {
    suspend fun download(
        url: String, directory: File, headers: Map<String, String>,
        report: (Int, Long) -> Unit
    ): File? {
        val variant = hls.listVariants(url, headers).firstOrNull() ?: return null
        val video = File(directory, "media.ts")
        val audioUrl = variant.audioUrl
        // Every stream ends in a mux, whether or not its audio arrived
        // separately: a concatenated transport stream carries ADTS-framed AAC,
        // which an MP4 audio track cannot hold. Doing it here is what makes a
        // finished download playable without a trip through Library repair, so
        // the transfers never own the last stretch of the progress bar.
        val videoShare = if (audioUrl == null) 85 else 80
        val muxFrom = if (audioUrl == null) 85 else 95
        var videoBytes = 0L
        if (!hls.download(variant.url, video, headers) { percent, bytes ->
            videoBytes = bytes
            report(percent * videoShare / 100, bytes)
        }) return null

        var totalBytes = videoBytes
        var audio: File? = null
        if (audioUrl != null) {
            val file = File(directory, "audio.ts")
            if (!hls.download(audioUrl, file, headers) { percent, bytes ->
                totalBytes = videoBytes + bytes
                report(80 + percent * 15 / 100, totalBytes)
            }) return null
            audio = file
        }

        val output = File(directory, "media.mp4")
        val context = currentCoroutineContext()
        val onProgress: (Int) -> Unit =
            { report(muxFrom + it * (100 - muxFrom) / 100, totalBytes) }
        val checkCancelled = { context.ensureActive() }
        val muxed = if (audio != null) {
            Mp4Muxer.mux(video, audio, output, onProgress, checkCancelled)
        } else {
            Mp4Muxer.remux(video, output, onProgress, checkCancelled)
        }
        if (muxed) return output
        // Split renditions are useless apart, but a single stream that will not
        // remux — an unusual codec, a container MediaMuxer refuses — is still
        // the movie that was asked for. Publish the transport stream and leave
        // Library repair as the fallback rather than failing the download.
        return if (audio == null) video else null
    }
}
