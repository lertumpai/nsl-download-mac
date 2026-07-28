package com.nsl.downloader.service

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.BufferedOutputStream
import java.io.File
import java.nio.ByteOrder

/**
 * Turns any Android-decodable audio file (YouTube hands us AAC in M4A, or Opus
 * in WebM) into a real CBR MP3.
 *
 * There is no MP3 encoder in `MediaCodec` and ffmpeg-kit was pulled from Maven
 * Central, so the pipeline is: `MediaCodec` decode → 16-bit PCM → jump3r/LAME.
 * The LAME half is pure Java and is the slow part — expect several times
 * realtime rather than instant.
 */
object Mp3Transcoder {

    private const val TIMEOUT_US = 10_000L
    private const val FRAMES_PER_CHUNK = 4096

    /**
     * @param durationUsHint used when the container declares no duration, which
     *   is common for the fragmented MP4 that YouTube serves. Pass 0 if unknown.
     * @param onProgress percent 0..100, called as decoding advances.
     * @return true when [output] holds a complete MP3.
     */
    fun transcode(
        input: File,
        output: File,
        bitrateKbps: Int,
        durationUsHint: Long = 0L,
        onProgress: (Int) -> Unit = {}
    ): Boolean {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null

        try {
            extractor.setDataSource(input.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return false

            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return false
            val durationUs = runCatching { inputFormat.getLong(MediaFormat.KEY_DURATION) }
                .getOrDefault(0L)
                .takeIf { it > 0 } ?: durationUsHint

            decoder = MediaCodec.createDecoderByType(mime).apply {
                configure(inputFormat, null, null, 0)
                start()
            }

            BufferedOutputStream(output.outputStream(), 1 shl 16).use { out ->
                drainInto(out, extractor, decoder, bitrateKbps, durationUs, onProgress)
                    ?.finish(out)
            }
            return output.length() > 0
        } catch (e: Exception) {
            output.delete()
            return false
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
        }
    }

    /**
     * Runs the decode loop, creating the LAME encoder once the decoder reports
     * its real output format (sample rate and channel count can differ from the
     * container's, notably for Opus which always decodes at 48 kHz).
     *
     * Returns the encoder so the caller can flush it while [out] is still open.
     */
    private fun drainInto(
        out: java.io.OutputStream,
        extractor: MediaExtractor,
        decoder: MediaCodec,
        bitrateKbps: Int,
        durationUs: Long,
        onProgress: (Int) -> Unit
    ): Mp3Encoder? {
        val bufferInfo = MediaCodec.BufferInfo()
        var encoder: Mp3Encoder? = null
        var channels = 2
        var left = IntArray(FRAMES_PER_CHUNK)
        var right = IntArray(FRAMES_PER_CHUNK)
        var inputDone = false
        var outputDone = false
        var lastPercent = -1

        while (!outputDone) {
            if (!inputDone) {
                val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    val buffer = decoder.getInputBuffer(inIndex)!!
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(
                            inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            when (val outIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = decoder.outputFormat
                    channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 2)
                    encoder = Mp3Encoder(
                        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                        channels = channels,
                        bitrateKbps = bitrateKbps
                    )
                }
                MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                else -> {
                    if (outIndex >= 0) {
                        if (bufferInfo.size > 0 && encoder != null) {
                            val pcm = decoder.getOutputBuffer(outIndex)!!
                            pcm.position(bufferInfo.offset)
                            pcm.limit(bufferInfo.offset + bufferInfo.size)
                            val shorts = pcm.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()

                            val frames = shorts.remaining() / channels
                            if (left.size < frames) {
                                left = IntArray(frames)
                                right = IntArray(frames)
                            }
                            fill(shorts, channels, frames, left, right)
                            encoder.encode(left, right, frames, out)

                            // Only real sample buffers carry a timestamp; the
                            // end-of-stream marker arrives with pts 0 and would
                            // otherwise send the progress bar back to the start.
                            if (durationUs > 0) {
                                val percent =
                                    (bufferInfo.presentationTimeUs * 100 / durationUs)
                                        .toInt().coerceIn(0, 100)
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    onProgress(percent)
                                }
                            }
                        }
                        decoder.releaseOutputBuffer(outIndex, false)

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                            if (lastPercent < 100) onProgress(100)
                        }
                    }
                }
            }
        }
        return encoder
    }

    /**
     * De-interleaves 16-bit PCM into LAME's per-channel int arrays. LAME reads
     * an int sample as a 16-bit value in the high half, so each short is shifted
     * left by 16 rather than sign-extended.
     */
    private fun fill(
        shorts: java.nio.ShortBuffer,
        channels: Int,
        frames: Int,
        left: IntArray,
        right: IntArray
    ) {
        if (channels == 1) {
            for (i in 0 until frames) left[i] = shorts.get().toInt() shl 16
        } else {
            for (i in 0 until frames) {
                left[i] = shorts.get().toInt() shl 16
                right[i] = shorts.get().toInt() shl 16
            }
        }
    }
}
