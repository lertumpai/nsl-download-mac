package com.nsl.downloader.service

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.OutputStream
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Downloads an HLS (.m3u8) stream without ffmpeg:
 *  - resolves a master playlist to its highest-bandwidth variant
 *  - parses the media playlist for segment URIs
 *  - supports AES-128 segment decryption (#EXT-X-KEY)
 *  - concatenates decrypted TS segments into a single playable .ts file
 *
 * This covers the large majority of unencrypted and AES-128 HLS streams.
 * It does not handle SAMPLE-AES (FairPlay/Widevine) DRM.
 */
class HlsDownloader(private val client: OkHttpClient) {

    data class Segment(val url: String, val key: ByteArray?, val iv: ByteArray?, val seq: Int)

    /** @return true on success. onProgress is 0..100. */
    fun download(playlistUrl: String, output: File, onProgress: (Int) -> Unit): Boolean {
        val mediaUrl = resolveVariant(playlistUrl) ?: playlistUrl
        val segments = parseMediaPlaylist(mediaUrl) ?: return false
        if (segments.isEmpty()) return false

        output.outputStream().use { out ->
            segments.forEachIndexed { index, seg ->
                val data = fetchBytes(seg.url) ?: return false
                val decoded = if (seg.key != null) decryptAes128(data, seg.key, seg.iv, seg.seq) else data
                out.write(decoded)
                onProgress(((index + 1) * 100) / segments.size)
            }
        }
        return output.length() > 0
    }

    private fun fetchText(url: String): String? = runCatching {
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) null else r.body?.string()
        }
    }.getOrNull()

    private fun fetchBytes(url: String): ByteArray? = runCatching {
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) null else r.body?.bytes()
        }
    }.getOrNull()

    /** If the playlist is a master, return the highest-bandwidth variant URL. */
    private fun resolveVariant(masterUrl: String): String? {
        val text = fetchText(masterUrl) ?: return null
        if (!text.contains("#EXT-X-STREAM-INF")) return masterUrl // already a media playlist
        val lines = text.lines()
        var bestBandwidth = -1L
        var bestUri: String? = null
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val bw = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val uri = lines.getOrNull(i + 1)?.trim()
                if (uri != null && uri.isNotEmpty() && !uri.startsWith("#") && bw > bestBandwidth) {
                    bestBandwidth = bw
                    bestUri = uri
                }
                i += 2
            } else i++
        }
        return bestUri?.let { resolveUrl(masterUrl, it) }
    }

    private fun parseMediaPlaylist(mediaUrl: String): List<Segment>? {
        val text = fetchText(mediaUrl) ?: return null
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
                        currentKey = keyUri?.let { fetchBytes(resolveUrl(mediaUrl, it)) }
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
