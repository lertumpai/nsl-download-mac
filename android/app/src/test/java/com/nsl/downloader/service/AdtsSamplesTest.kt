package com.nsl.downloader.service

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer

class AdtsSamplesTest {
    private fun frame(payload: ByteArray, crc: Boolean = false): ByteArray {
        val length = payload.size + if (crc) 9 else 7
        val header = byteArrayOf(0xff.toByte(), if (crc) 0xf0.toByte() else 0xf1.toByte(), 0x4c,
            (0x80 or (length shr 11)).toByte(), (length shr 3).toByte(),
            (((length and 7) shl 5) or 31).toByte(), 0xfc.toByte())
        return header + (if (crc) byteArrayOf(0, 0) else byteArrayOf()) + payload
    }

    @Test fun splitsBundledAudioAndRemovesHeadersWithCorrectTimestamps() {
        val payload = byteArrayOf(1, 2, 3, 4)
        val bytes = (1..17).flatMap { frame(payload).toList() }.toByteArray()
        val buffer = ByteBuffer.wrap(bytes)
        val frames = AdtsSamples.split(buffer, bytes.size)!!
        assertEquals(17, frames.size)
        frames.forEachIndexed { index, f ->
            assertEquals(index.toLong() * 1024 * 1_000_000 / 48000, f.timeOffsetUs)
            assertArrayEquals(payload, bytes.copyOfRange(f.offset, f.offset + f.size))
        }
    }

    @Test fun stripsOptionalCrcAndLeavesRawAacUntouched() {
        val bytes = frame(byteArrayOf(21, 22, 23), crc = true)
        assertEquals(9, AdtsSamples.split(ByteBuffer.wrap(bytes), bytes.size)!!.single().offset)
        assertNull(AdtsSamples.split(ByteBuffer.wrap(byteArrayOf(0x21, 0x10, 4, 0x60)), 4))
    }

    @Test(expected = IOException::class) fun refusesTruncatedAudioInsteadOfPublishingIt() {
        val bytes = frame(byteArrayOf(1, 2, 3)).dropLast(1).toByteArray()
        AdtsSamples.split(ByteBuffer.wrap(bytes), bytes.size)
    }
}
