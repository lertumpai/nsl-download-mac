package com.nsl.downloader.service

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * What of a partly-downloaded file is already on disk.
 *
 * The bytes alone are not enough to resume from: a ranged transfer fills the
 * file out of order, so its length says nothing about which parts are real, and
 * a segment transfer has to restart on a segment boundary. Each downloader
 * therefore keeps a small sidecar next to its output describing what it has,
 * and consults it before fetching anything.
 */
internal sealed interface ResumeState {

    /** Byte ranges present, for a file filled out of order. */
    data class Ranged(
        val total: Long,
        val chunkSize: Long,
        /** One bit per chunk, LSB first; set means that chunk is complete. */
        val done: ByteArray
    ) : ResumeState {
        // Data-class equality on a ByteArray compares identity, which would make
        // two equal states look different. Nothing relies on it, but a silently
        // wrong equals() is worse than none.
        override fun equals(other: Any?): Boolean =
            other is Ranged && total == other.total && chunkSize == other.chunkSize &&
                done.contentEquals(other.done)

        override fun hashCode(): Int =
            (total.hashCode() * 31 + chunkSize.hashCode()) * 31 + done.contentHashCode()
    }

    /** Bytes present, for a file filled front to back. */
    data class Sequential(val total: Long, val bytes: Long) : ResumeState

    /** Whole playlist segments appended, for an HLS transfer. */
    data class Segments(val segments: Int, val bytes: Long, val playlistSize: Int) : ResumeState
}

/**
 * Reads and writes the [ResumeState] sidecar for an output file.
 *
 * The sidecar is written far more often than it is read, so it is kept tiny and
 * rewritten whole — for a 4 GB file split into 512 KB chunks the bitmap is 1 KB.
 * Writes go through a temporary file and a rename so a process death mid-write
 * cannot leave a half-written record that reads back as plausible.
 */
internal object ResumeLog {

    private const val MAGIC = 0x4E534C52 // "NSLR"
    private const val VERSION = 1

    private const val TAG_RANGED: Byte = 0
    private const val TAG_SEQUENTIAL: Byte = 1
    private const val TAG_SEGMENTS: Byte = 2

    fun sidecarOf(output: File): File = File(output.parentFile, "${output.name}.part")

    /** The recorded state, or null if there is none or it cannot be trusted. */
    fun read(output: File): ResumeState? = runCatching {
        val file = sidecarOf(output)
        if (!file.exists()) return null
        DataInputStream(file.inputStream().buffered()).use { input ->
            if (input.readInt() != MAGIC || input.readInt() != VERSION) return null
            when (input.readByte()) {
                TAG_RANGED -> {
                    val total = input.readLong()
                    val chunkSize = input.readLong()
                    val bits = ByteArray(input.readInt())
                    input.readFully(bits)
                    ResumeState.Ranged(total, chunkSize, bits)
                }
                TAG_SEQUENTIAL -> ResumeState.Sequential(input.readLong(), input.readLong())
                TAG_SEGMENTS ->
                    ResumeState.Segments(input.readInt(), input.readLong(), input.readInt())
                else -> null
            }
        }
    }.getOrNull()

    fun write(output: File, state: ResumeState) {
        runCatching {
            val target = sidecarOf(output)
            val temp = File(target.parentFile, "${target.name}.tmp")
            DataOutputStream(temp.outputStream().buffered()).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                when (state) {
                    is ResumeState.Ranged -> {
                        out.writeByte(TAG_RANGED.toInt())
                        out.writeLong(state.total)
                        out.writeLong(state.chunkSize)
                        out.writeInt(state.done.size)
                        out.write(state.done)
                    }
                    is ResumeState.Sequential -> {
                        out.writeByte(TAG_SEQUENTIAL.toInt())
                        out.writeLong(state.total)
                        out.writeLong(state.bytes)
                    }
                    is ResumeState.Segments -> {
                        out.writeByte(TAG_SEGMENTS.toInt())
                        out.writeInt(state.segments)
                        out.writeLong(state.bytes)
                        out.writeInt(state.playlistSize)
                    }
                }
            }
            if (!temp.renameTo(target)) {
                target.delete()
                temp.renameTo(target)
            }
        }
    }

    fun clear(output: File) {
        runCatching { sidecarOf(output).delete() }
    }
}
