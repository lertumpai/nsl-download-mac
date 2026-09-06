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
        var videoBytes = 0L
        if (!hls.download(variant.url, video, headers) { percent, bytes ->
            videoBytes = bytes
            report(if (audioUrl == null) percent else percent * 80 / 100, bytes)
        }) return null
        if (audioUrl == null) return video
        val audio = File(directory, "audio.ts")
        var totalBytes = videoBytes
        if (!hls.download(audioUrl, audio, headers) { percent, bytes ->
            totalBytes = videoBytes + bytes
            report(80 + percent * 15 / 100, totalBytes)
        }) return null
        val output = File(directory, "media.mp4")
        val context = currentCoroutineContext()
        return if (Mp4Muxer.mux(video, audio, output,
            onProgress = { report(95 + it * 5 / 100, totalBytes) },
            checkCancelled = { context.ensureActive() })) output else null
    }
}
