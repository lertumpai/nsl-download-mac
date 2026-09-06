package com.nsl.downloader.service

import android.content.Context
import android.net.Uri
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException

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

    fun mux(
        videoFile: File,
        audioFile: File,
        output: File,
        onProgress: (Int) -> Unit = {},
        checkCancelled: () -> Unit = {}
    ): Boolean = muxSources(
        { it.setDataSource(videoFile.absolutePath) },
        { it.setDataSource(audioFile.absolutePath) }, output, onProgress, checkCancelled)

    fun repair(context: Context, source: Uri, output: File,
        onProgress: (Int) -> Unit = {}, checkCancelled: () -> Unit = {}): Boolean = muxSources(
        { it.setDataSource(context, source, null) },
        { it.setDataSource(context, source, null) }, output, onProgress, checkCancelled)

    fun needsAudioRepair(context: Context, source: Uri): Boolean {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, source, null)
            val track = selectTrack(extractor, "audio/") ?: return false
            val format = extractor.getTrackFormat(track)
            if (format.getString(MediaFormat.KEY_MIME) != "audio/mp4a-latm") return false
            val buffer = ByteBuffer.allocate(maxOf(maxInputSize(format), DEFAULT_BUFFER_BYTES))
            val size = extractor.readSampleData(buffer, 0)
            size > 0 && AdtsSamples.split(buffer, size) != null
        } finally { extractor.release() }
    }

    private fun muxSources(
        setVideoSource: (MediaExtractor) -> Unit,
        setAudioSource: (MediaExtractor) -> Unit,
        output: File, onProgress: (Int) -> Unit, checkCancelled: () -> Unit
    ): Boolean {
        var muxer: MediaMuxer? = null
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var complete = false

        try {
            setVideoSource(videoExtractor)
            setAudioSource(audioExtractor)

            val videoTrack = selectTrack(videoExtractor, "video/") ?: return false
            val audioTrack = selectTrack(audioExtractor, "audio/") ?: return false
            val videoFormat = videoExtractor.getTrackFormat(videoTrack)
            val audioFormat = audioExtractor.getTrackFormat(audioTrack)
            // TS extractors can return several ADTS frames in one sample. The
            // MP4 track must contain individual AAC access units without ADTS.
            audioFormat.setInteger(MediaFormat.KEY_IS_ADTS, 0)

            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outVideo = muxer.addTrack(videoFormat)
            val outAudio = muxer.addTrack(audioFormat)
            muxer.start()

            val bufferSize = maxOf(
                maxInputSize(videoFormat), maxInputSize(audioFormat), DEFAULT_BUFFER_BYTES
            )
            val buffer = ByteBuffer.allocate(bufferSize)
            copyTrack(videoExtractor, videoFormat, muxer, outVideo, buffer, 0, 70,
                onProgress, checkCancelled)
            copyTrack(audioExtractor, audioFormat, muxer, outAudio, buffer, 70, 100,
                onProgress, checkCancelled)
            checkCancelled()
            // stop() writes the MP4 sample tables. A failure here must not be
            // reported as a completed download with an unplayable container.
            muxer.stop()
            complete = output.length() > 0
            return complete
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return false
        } finally {
            runCatching { muxer?.release() }
            runCatching { videoExtractor.release() }
            runCatching { audioExtractor.release() }
            if (!complete) output.delete()
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
        format: MediaFormat,
        muxer: MediaMuxer,
        outputTrack: Int,
        buffer: ByteBuffer,
        progressFrom: Int,
        progressTo: Int,
        onProgress: (Int) -> Unit,
        checkCancelled: () -> Unit
    ) {
        val info = MediaCodec.BufferInfo()
        val durationUs = runCatching {
            format.getLong(MediaFormat.KEY_DURATION)
        }.getOrDefault(0L)
        var lastProgress = -1
        fun report(sampleTimeUs: Long, complete: Boolean = false) {
            val progress = if (complete || durationUs <= 0L) {
                if (complete) progressTo else progressFrom
            } else {
                progressFrom + (
                    sampleTimeUs.coerceIn(0L, durationUs) *
                        (progressTo - progressFrom) / durationUs
                    ).toInt()
            }
            if (progress != lastProgress) {
                lastProgress = progress
                onProgress(progress)
            }
        }

        report(0L)
        while (true) {
            checkCancelled()
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            val aacFrames = if (format.getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm") {
                AdtsSamples.split(buffer, size)
            } else null
            if (aacFrames != null) {
                for (frame in aacFrames) {
                    info.offset = frame.offset
                    info.size = frame.size
                    info.presentationTimeUs = extractor.sampleTime + frame.timeOffsetUs
                    info.flags = MediaCodec.BUFFER_FLAG_KEY_FRAME
                    muxer.writeSampleData(outputTrack, buffer, info)
                }
            } else {
                info.offset = 0
                info.size = size
                info.presentationTimeUs = extractor.sampleTime
                info.flags = sampleFlagsToBufferFlags(extractor.sampleFlags)
                muxer.writeSampleData(outputTrack, buffer, info)
            }
            report(info.presentationTimeUs)
            // Some device extractors keep exposing the final sample after
            // advance() returns false. Respecting its result prevents an
            // endless final-sample write that leaves the UI parked at 92%.
            if (!extractor.advance()) break
        }
        checkCancelled()
        report(durationUs, complete = true)
    }

    /** `MediaExtractor.SAMPLE_FLAG_*` and `MediaCodec.BUFFER_FLAG_*` differ. */
    private fun sampleFlagsToBufferFlags(sampleFlags: Int): Int =
        if (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
            MediaCodec.BUFFER_FLAG_KEY_FRAME
        } else {
            0
        }
}
