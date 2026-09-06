package com.nsl.downloader.service

import java.io.IOException
import java.nio.ByteBuffer

/** Converts a TS extractor's bundle of ADTS frames into individual raw AAC samples. */
internal object AdtsSamples {
    data class Frame(val offset: Int, val size: Int, val timeOffsetUs: Long)
    private val rates = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000,
        24000, 22050, 16000, 12000, 11025, 8000, 7350)

    /** Null means ordinary raw AAC (e.g. a YouTube M4A track), which is already MP4-ready. */
    fun split(buffer: ByteBuffer, size: Int): List<Frame>? {
        fun byte(i: Int) = buffer.get(i).toInt() and 255
        fun sync(i: Int) = i + 1 < size && byte(i) == 255 && byte(i + 1) and 0xf6 == 0xf0
        if (size < 2 || !sync(0)) return null
        val frames = mutableListOf<Frame>()
        var offset = 0
        var sampleRate = 0
        while (offset < size) {
            if (offset + 7 > size || !sync(offset)) throw IOException("Truncated ADTS frame")
            val rate = rates.getOrNull((byte(offset + 2) shr 2) and 15)
                ?: throw IOException("Unsupported ADTS sample rate")
            if (sampleRate != 0 && sampleRate != rate) throw IOException("ADTS sample rate changed")
            sampleRate = rate
            if (byte(offset + 6) and 3 != 0) throw IOException("Multiple AAC blocks per ADTS frame are unsupported")
            val header = if (byte(offset + 1) and 1 != 0) 7 else 9
            val length = ((byte(offset + 3) and 3) shl 11) or
                (byte(offset + 4) shl 3) or (byte(offset + 5) shr 5)
            if (length <= header || offset + length > size) throw IOException("Invalid ADTS frame length")
            frames += Frame(offset + header, length - header, frames.size.toLong() * 1024 * 1_000_000 / rate)
            offset += length
        }
        return frames
    }
}
