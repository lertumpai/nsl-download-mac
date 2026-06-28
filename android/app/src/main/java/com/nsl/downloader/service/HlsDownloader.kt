package com.nsl.downloader.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
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

    companion object {
        /** Max segments fetched concurrently. */
        private const val PARALLELISM = 6
        private const val BUFFER_SIZE = 1 shl 16 // 64 KB
    }

    data class Segment(val url: String, val key: ByteArray?, val iv: ByteArray?, val seq: Int)

    /** A selectable quality from a master playlist. */
    data class Variant(
        val url: String,
        val width: Int,
        val height: Int,
        val bandwidth: Long
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
                    variants.add(Variant(resolveUrl(playlistUrl, uri), w, h, bw))
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
     * Segments are fetched+decrypted **concurrently** (up to [PARALLELISM] at a time) to
     * hide per-segment latency, then written to disk **in playlist order** so the result
     * is a valid contiguous .ts. Memory is bounded to one batch of segments.
     *
     * @return true on success. onProgress reports (percent 0..100, total bytes written).
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

        var written = 0L
        var done = 0
        output.outputStream().buffered(BUFFER_SIZE).use { out ->
            // Process in batches so at most PARALLELISM segments are in flight / in memory.
            for (batch in segments.chunked(PARALLELISM)) {
                val fetched = batch.map { seg ->
                    async(Dispatchers.IO) {
                        val data = fetchBytes(seg.url, headers) ?: return@async null
                        if (seg.key != null) decryptAes128(data, seg.key, seg.iv, seg.seq) else data
                    }
                }.awaitAll()

                for (bytes in fetched) {
                    bytes ?: return@coroutineScope false  // a segment failed
                    out.write(bytes)
                    written += bytes.size
                    done++
                    onProgress(done * 100 / segments.size, written)
                }
            }
        }
        output.length() > 0
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
                        val ivHex = Regex("IV=0x([0-9A-Fa-f]+)").find(line)?.groupValues?.get(1)
                        currentIv = ivHex?.let { hexToBytes(it) }
                    }
                    // SAMPLE-AES and others: unsupported, leave key null (segment kept raw)
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

    private fun decryptAes128(data: ByteArray, key: ByteArray, iv: ByteArray?, seq: Int): ByteArray {
        val ivBytes = iv ?: ByteArray(16).also { b ->
            // Default IV = segment sequence number, big-endian in the low 4 bytes
            b[12] = (seq ushr 24).toByte()
            b[13] = (seq ushr 16).toByte()
            b[14] = (seq ushr 8).toByte()
            b[15] = seq.toByte()
        }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ivBytes))
        return runCatching { cipher.doFinal(data) }.getOrElse {
            // Some streams use NoPadding; retry
            val c2 = Cipher.getInstance("AES/CBC/NoPadding")
            c2.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ivBytes))
            c2.doFinal(data)
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
}
