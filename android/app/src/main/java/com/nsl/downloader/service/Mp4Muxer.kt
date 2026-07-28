package com.nsl.downloader.service

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Remuxes a video-only track and an audio-only track into one MP4.
 *
 * YouTube serves everything above 360p as separate DASH renditions, so the
 * high-quality path has to recombine them. This is a pure container operation —
 * samples are copied, never re-encoded — which is why [YouTubeResolver] only
 * offers AVC-in-MP4 video and AAC/M4A audio: those are the codecs `MediaMuxer`
 * accepts in an MP4.
 *
 * @see com.nsl.downloader.youtube.YouTubeResolver
 */
object Mp4Muxer {

    private const val DEFAULT_BUFFER_BYTES = 1 shl 20

    fun mux(videoFile: File, audioFile: File, output: File): Boolean {
        var muxer: MediaMuxer? = null
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var started = false

        try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            audioExtractor.setDataSource(audioFile.absolutePath)

            val videoTrack = selectTrack(videoExtractor, "video/") ?: return false
            val audioTrack = selectTrack(audioExtractor, "audio/") ?: return false
            val videoFormat = videoExtractor.getTrackFormat(videoTrack)
            val audioFormat = audioExtractor.getTrackFormat(audioTrack)

            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outVideo = muxer.addTrack(videoFormat)
            val outAudio = muxer.addTrack(audioFormat)
            muxer.start()
            started = true

            val bufferSize = maxOf(
                maxInputSize(videoFormat), maxInputSize(audioFormat), DEFAULT_BUFFER_BYTES
            )
            val buffer = ByteBuffer.allocate(bufferSize)
            copyTrack(videoExtractor, muxer, outVideo, buffer)
            copyTrack(audioExtractor, muxer, outAudio, buffer)
            return true
        } catch (e: Exception) {
            output.delete()
            return false
        } finally {
            if (started) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { videoExtractor.release() }
            runCatching { audioExtractor.release() }
        }
    }

    private fun selectTrack(extractor: MediaExtractor, prefix: String): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(prefix)) {
                extractor.selectTrack(i)
                return i
            }
        }
        return null
    }

    private fun maxInputSize(format: MediaFormat): Int =
        runCatching { format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) }.getOrDefault(0)

    private fun copyTrack(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        outputTrack: Int,
        buffer: ByteBuffer
    ) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            info.offset = 0
            info.size = size
            info.presentationTimeUs = extractor.sampleTime
            info.flags = sampleFlagsToBufferFlags(extractor.sampleFlags)
            muxer.writeSampleData(outputTrack, buffer, info)
            extractor.advance()
        }
    }

    /** `MediaExtractor.SAMPLE_FLAG_*` and `MediaCodec.BUFFER_FLAG_*` differ. */
    private fun sampleFlagsToBufferFlags(sampleFlags: Int): Int =
        if (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
            MediaCodec.BUFFER_FLAG_KEY_FRAME
        } else {
            0
        }
}
