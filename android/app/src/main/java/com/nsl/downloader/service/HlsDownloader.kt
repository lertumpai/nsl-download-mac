package com.nsl.downloader.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Downloads an HLS (.m3u8) stream without ffmpeg:
 *  - lists the resolution variants of a master playlist (for quality selection)
 *  - parses the media playlist for segment URIs
 *  - supports AES-128 segment decryption (#EXT-X-KEY)
 *  - concatenates decrypted TS segments into a single playable .ts file
 *
 * All requests forward caller-supplied headers (User-Agent / Referer / Cookie)
 * so CDN-protected streams (e.g. missav) don't 403.
 *
 * Does not handle SAMPLE-AES (FairPlay/Widevine) DRM.
 */
class HlsDownloader(private val client: OkHttpClient) {

    private val segmentFetcher = HlsSegmentFetcher(client)

    companion object {
        /** Max segments fetched concurrently. */
        private const val PARALLELISM = 6
        private const val LOOKAHEAD = PARALLELISM * 2
        private const val BUFFER_SIZE = 1 shl 16 // 64 KB
    }

    data class Segment(val url: String, val key: ByteArray?, val iv: ByteArray?, val seq: Int)

    /** A selectable quality from a master playlist. */
    data class Variant(
        val url: String,
        val width: Int,
        val height: Int,
        val bandwidth: Long,
        val audioUrl: String? = null
    )

    /**
     * Returns the selectable qualities for [playlistUrl].
     * If it is a master playlist, one [Variant] per #EXT-X-STREAM-INF (sorted by
     * height desc). If it is already a media playlist (single quality), returns a
     * single variant pointing at it with height 0 (unknown).
     */
    fun listVariants(playlistUrl: String, headers: Map<String, String>): List<Variant> {
        val text = fetchText(playlistUrl, headers)
            ?: return listOf(Variant(playlistUrl, 0, 0, 0))
        if (!text.contains("#EXT-X-STREAM-INF")) {
            // Already a media playlist — single quality.
            return listOf(Variant(playlistUrl, 0, 0, 0))
        }
        val lines = text.lines()
        val audioGroups = lines.filter { it.startsWith("#EXT-X-MEDIA:") && it.contains("TYPE=AUDIO") }
        val variants = mutableListOf<Variant>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val bw = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val res = Regex("RESOLUTION=(\\d+)x(\\d+)").find(line)
                val w = res?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val h = res?.groupValues?.get(2)?.toIntOrNull() ?: 0
                val uri = lines.getOrNull(i + 1)?.trim()
                if (!uri.isNullOrEmpty() && !uri.startsWith("#")) {
                    val group = attribute(line, "AUDIO")
                    val audio = audioGroups.filter { attribute(it, "GROUP-ID") == group }
                        .sortedByDescending { it.contains("DEFAULT=YES") }
                        .firstNotNullOfOrNull { attribute(it, "URI") }
                    variants.add(Variant(resolveUrl(playlistUrl, uri), w, h, bw,
                        audio?.let { resolveUrl(playlistUrl, it) }))
                }
                i += 2
            } else i++
        }
        if (variants.isEmpty()) return listOf(Variant(playlistUrl, 0, 0, 0))
        return variants.sortedByDescending { if (it.height > 0) it.height.toLong() else it.bandwidth }
    }

    /**
     * Downloads the media playlist at [mediaUrl] (already a chosen variant) into [output].
     *
     * Segments are fetched **concurrently** (up to [PARALLELISM] at a time) to
     * hide per-segment latency, then written to disk **in playlist order** so the result
     * is a valid contiguous .ts. A rolling window keeps workers busy past a slow
     * segment, with at most [LOOKAHEAD] fetched/in-flight segments on disk.
     * Transfer and decryption use fixed buffers so ten movies do not retain
     * dozens of complete segments in the Android heap.
     *
     * A failed run leaves its segments in place and a [ResumeLog] record beside
     * them, so retrying continues from the next segment rather than re-fetching
     * hundreds of them. The record is discarded by the caller once the bytes are
     * no longer wanted; see [DownloadPartials].
     *
     * @return true on success. Progress reports committed segment percent and
     * received bytes (including retry traffic) for a live network speed meter.
     */
    suspend fun download(
        mediaUrl: String,
        output: File,
        headers: Map<String, String>,
        onProgress: (Int, Long) -> Unit
    ): Boolean = coroutineScope {
        // mediaUrl may still be a master (if caller passed the raw URL); resolve it.
        val resolved = if (isMaster(mediaUrl, headers)) {
            listVariants(mediaUrl, headers).firstOrNull()?.url ?: mediaUrl
        } else mediaUrl

        val segments = parseMediaPlaylist(resolved, headers)
            ?: return@coroutineScope false
        if (segments.isEmpty()) return@coroutineScope false

        val resumed = resume(output, segments.size)
        var written = resumed.bytes
        var done = resumed.segments
        val staging = File(output.parentFile, "${output.name}.segments")
        // An interrupted process may have left speculative segments behind.
        staging.deleteRecursively()
        if (done >= segments.size) return@coroutineScope output.length() > 0
        if (!staging.mkdirs()) return@coroutineScope false

        val reportLock = Any()
        var received = written
        var committed = done
        val reportBytes: (Int) -> Unit = { count ->
            synchronized(reportLock) {
                received += count
                onProgress(committed * 100 / segments.size, received)
            }
        }
        reportBytes(0)
        val permits = Semaphore(PARALLELISM)
        val pending = ArrayDeque<Deferred<File?>>()
        var next = done
        fun fillWindow() {
            while (next < segments.size && pending.size < LOOKAHEAD) {
                val index = next++
                val seg = segments[index]
                pending.addLast(async(Dispatchers.IO) {
                    permits.withPermit {
                        val file = File(staging, "$index")
                        if (segmentFetcher.fetch(seg.url, headers, file, reportBytes)) file else null
                    }
                })
            }
        }
        try {
            fillWindow()
            FileOutputStream(output, done > 0).buffered(BUFFER_SIZE).use { out ->
                while (pending.isNotEmpty()) {
                    val file = pending.first().await() ?: return@coroutineScope false
                    pending.removeFirst()
                    written += appendSegment(file, segments[done], out)
                    file.delete()
                    done++
                    // Record only a flushed, contiguous prefix. Out-of-order
                    // results never become part of a resume checkpoint.
                    out.flush()
                    ResumeLog.write(output, ResumeState.Segments(done, written, segments.size))
                    synchronized(reportLock) {
                        committed = done
                        onProgress(committed * 100 / segments.size, received)
                    }
                    fillWindow()
                }
            }
        } finally {
            pending.forEach { it.cancel() }
            withContext(NonCancellable) {
                pending.toList().joinAll()
                staging.deleteRecursively()
            }
        }
        output.length() > 0
    }

    /** Segments a previous attempt already appended, and where they end. */
    private data class Resumed(val segments: Int, val bytes: Long)

    /**
     * How much of [output] survives from an earlier attempt at a playlist of
     * [playlistSize] segments.
     *
     * A playlist that changed length is a different stream (or a live one), so
     * nothing can be assumed about the bytes already written. Otherwise the file
     * is cut back to the last recorded segment boundary — anything past it was
     * flushed after the record and cannot be placed in the sequence.
     */
    private fun resume(output: File, playlistSize: Int): Resumed {
        val state = ResumeLog.read(output) as? ResumeState.Segments
        val usable = state != null &&
            state.playlistSize == playlistSize &&
            state.segments in 1..playlistSize &&
            output.length() >= state.bytes
        if (!usable) {
            output.delete()
            ResumeLog.clear(output)
            return Resumed(0, 0L)
        }
        if (output.length() > state!!.bytes) {
            runCatching { RandomAccessFile(output, "rw").use { it.setLength(state.bytes) } }
                .onFailure {
                    output.delete()
                    ResumeLog.clear(output)
                    return Resumed(0, 0L)
                }
        }
        return Resumed(state.segments, state.bytes)
    }

    private fun isMaster(url: String, headers: Map<String, String>): Boolean {
        val text = fetchText(url, headers) ?: return false
        return text.contains("#EXT-X-STREAM-INF")
    }

    private fun buildRequest(url: String, headers: Map<String, String>): Request {
        val b = Request.Builder().url(url)
        headers.forEach { (k, v) -> if (v.isNotBlank()) b.header(k, v) }
        return b.build()
    }

    private fun fetchText(url: String, headers: Map<String, String>): String? = runCatching {
        client.newCall(buildRequest(url, headers)).execute().use { r ->
            if (!r.isSuccessful) null else r.body?.string()
        }
    }.getOrNull()

    private fun fetchBytes(url: String, headers: Map<String, String>): ByteArray? = runCatching {
        client.newCall(buildRequest(url, headers)).execute().use { r ->
            if (!r.isSuccessful) null else r.body?.bytes()
        }
    }.getOrNull()

    private fun parseMediaPlaylist(mediaUrl: String, headers: Map<String, String>): List<Segment>? {
        val text = fetchText(mediaUrl, headers) ?: return null
        if (!text.trimStart().startsWith("#EXTM3U")) return null
        // This downloader writes TS/ADTS. Refuse unsupported layouts rather than
        // publishing initialization-free fMP4 or encrypted bytes as a finished movie.
        if (text.contains("#EXT-X-MAP") || text.contains("#EXT-X-BYTERANGE")) return null
        val lines = text.lines()
        val segments = mutableListOf<Segment>()

        var currentKey: ByteArray? = null
        var currentIv: ByteArray? = null
        var seq = 0

        // Media sequence start (used as IV when none specified)
        Regex("#EXT-X-MEDIA-SEQUENCE:(\\d+)").find(text)?.let {
            seq = it.groupValues[1].toIntOrNull() ?: 0
        }

        for (raw in lines) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-KEY") -> {
                    val method = Regex("METHOD=([A-Z0-9-]+)").find(line)?.groupValues?.get(1)
                    if (method == "NONE") {
                        currentKey = null
                        currentIv = null
                    } else if (method == "AES-128") {
                        val keyUri = Regex("URI=\"([^\"]+)\"").find(line)?.groupValues?.get(1)
                        currentKey = keyUri?.let { fetchBytes(resolveUrl(mediaUrl, it), headers) }
                        if (currentKey?.size != 16) return null
                        val ivHex = Regex("IV=0x([0-9A-Fa-f]+)").find(line)?.groupValues?.get(1)
                        currentIv = ivHex?.let { hexToBytes(it) }
                    }
                    else return null
                }
                line.isNotEmpty() && !line.startsWith("#") -> {
                    segments.add(
                        Segment(
                            url = resolveUrl(mediaUrl, line),
                            key = currentKey,
                            iv = currentIv,
                            seq = seq
                        )
                    )
                    seq++
                }
            }
        }
        return segments
    }

    private suspend fun appendSegment(file: File, segment: Segment, out: OutputStream): Long {
        val decoded = if (segment.key != null) {
            File(file.parentFile, "${file.name}.decoded").also {
                decryptAes128(file, it, segment.key, segment.iv, segment.seq)
            }
        } else file
        try {
            return decoded.inputStream().buffered(BUFFER_SIZE).use { input ->
                // unwrapSegment only inspects PNG chunks starting below 64 KB,
                // each at most 64 KB, followed by three TS sync bytes.
                val prefix = ByteArray(minOf(decoded.length(), 128L * 1024 + 400).toInt())
                var size = 0
                while (size < prefix.size) {
                    val count = input.read(prefix, size, prefix.size - size)
                    if (count < 0) throw IOException("Incomplete staged segment")
                    size += count
                }
                val unwrapped = unwrapSegment(prefix)
                out.write(unwrapped)
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    out.write(buffer, 0, count)
                }
                decoded.length() - (prefix.size - unwrapped.size)
            }
        } finally {
            if (decoded != file) decoded.delete()
        }
    }

    private suspend fun decryptAes128(source: File, target: File, key: ByteArray, iv: ByteArray?, seq: Int) {
        val ivBytes = iv ?: ByteArray(16).also { b ->
            // Default IV = segment sequence number, big-endian in the low 4 bytes
            b[12] = (seq ushr 24).toByte()
            b[13] = (seq ushr 16).toByte()
            b[14] = (seq ushr 8).toByte()
            b[15] = seq.toByte()
        }
        suspend fun decode(padding: String) {
            val cipher = Cipher.getInstance("AES/CBC/$padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ivBytes))
            source.inputStream().use { input ->
                target.outputStream().use { out ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        cipher.update(buffer, 0, count)?.let { out.write(it) }
                    }
                    out.write(cipher.doFinal())
                }
            }
        }
        try {
            decode("PKCS5Padding")
        } catch (_: javax.crypto.BadPaddingException) {
            // Some streams omit padding. Rewrite the staging file before use.
            decode("NoPadding")
        }
    }

    private fun resolveUrl(base: String, ref: String): String {
        if (ref.startsWith("http://") || ref.startsWith("https://")) return ref
        return runCatching { URI(base).resolve(ref).toString() }.getOrDefault(ref)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = if (hex.length % 2 != 0) "0$hex" else hex
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun attribute(line: String, name: String): String? =
        Regex("(?:^|[:,])$name=\"([^\"]*)\"").find(line)?.groupValues?.get(1)

    /** Some CDNs prepend a small PNG to TS segments served under .jpg URLs. */
    internal fun unwrapSegment(bytes: ByteArray): ByteArray {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 13, 10, 26, 10)
        if (!bytes.take(8).toByteArray().contentEquals(png)) return bytes
        // Validate the PNG chunk boundary, then require several MPEG-TS sync bytes.
        var offset = 8
        while (offset + 12 <= bytes.size && offset < 64 * 1024) {
            val length = (0..3).fold(0L) { n, i -> (n shl 8) or (bytes[offset + i].toLong() and 255) }
            if (length > 64 * 1024 || offset + 12L + length > bytes.size) break
            val end = offset + 12 + length.toInt()
            if (String(bytes, offset + 4, 4, Charsets.US_ASCII) == "IEND") {
                if (end + 188 * 2 < bytes.size && (0..2).all { bytes[end + it * 188] == 0x47.toByte() }) {
                    return bytes.copyOfRange(end, bytes.size)
                }
                break
            }
            offset = end
        }
        throw IOException("Image response did not contain a transport stream")
    }
}
