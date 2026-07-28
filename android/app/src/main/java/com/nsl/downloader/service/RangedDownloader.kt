package com.nsl.downloader.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLong

/**
 * Fetches one URL into a file over several concurrent HTTP range requests.
 *
 * YouTube's CDN shapes any single long-lived response down to roughly playback
 * speed, so a plain whole-file GET crawls on a long video. Asking for bounded
 * byte ranges restarts that shaping on every chunk, and keeping a few chunks in
 * flight fills the pipe — the same trick as yt-dlp's `--http-chunk-size` and
 * aria2c's `-x`. Servers that ignore `Range` fall back to a single stream, so
 * this is safe to use for every direct download, not just YouTube.
 *
 * Cancellation is honoured promptly: a blocking socket read cannot be
 * interrupted by coroutine cancellation alone, so each call is tied to the
 * job and the socket is cancelled with it.
 */
class RangedDownloader(base: OkHttpClient) {

    companion object {
        /** Range requests in flight at once, i.e. sockets per download. */
        private const val CONNECTIONS = 6
        private const val CHUNK_SIZE = 4L shl 20   // 4 MB per range request
        private const val BUFFER_SIZE = 1 shl 16   // 64 KB
        private const val MAX_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 300L

        /** Under this the connection setup costs more than the split saves. */
        private const val MIN_PARALLEL_BYTES = 4L shl 20
    }

    /**
     * One TCP connection per range request. HTTP/2 would multiplex them all
     * onto a single connection and hand back exactly the throughput the split
     * is meant to buy.
     */
    private val client = base.newBuilder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    private data class Probe(val length: Long, val acceptsRanges: Boolean)

    suspend fun download(
        url: String,
        output: File,
        headers: Map<String, String>,
        onProgress: (Int, Long) -> Unit
    ): Boolean {
        val probe = probe(url, headers)
        if (probe != null && probe.acceptsRanges && probe.length >= MIN_PARALLEL_BYTES) {
            if (downloadRanged(url, output, headers, probe.length, onProgress)) return true
            // A CDN can advertise ranges and still fall over under concurrent
            // reads; one plain pass is the safety net.
            currentCoroutineContext().ensureActive()
        }
        return downloadWhole(url, output, headers, onProgress)
    }

    /** A one-byte GET: reveals the total size and whether ranges are honoured. */
    private suspend fun probe(url: String, headers: Map<String, String>): Probe? =
        withContext(Dispatchers.IO) {
            try {
                useCall(client.newCall(buildRequest(url, headers, 0, 0))) { response ->
                    when {
                        response.code == 206 -> {
                            // Content-Range: bytes 0-0/123456
                            val total = response.header("Content-Range")
                                ?.substringAfterLast('/')?.trim()?.toLongOrNull() ?: -1L
                            if (total > 0) Probe(total, true) else null
                        }
                        response.isSuccessful -> Probe(response.body?.contentLength() ?: -1L, false)
                        else -> null
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }

    /**
     * Splits [total] into chunks handed out to [CONNECTIONS] workers. Each
     * worker owns its own file handle and seeks to the offset it is filling,
     * so no write ordering or buffering between them is needed.
     */
    private suspend fun downloadRanged(
        url: String,
        output: File,
        headers: Map<String, String>,
        total: Long,
        onProgress: (Int, Long) -> Unit
    ): Boolean = coroutineScope {
        try {
            RandomAccessFile(output, "rw").use { it.setLength(total) }
        } catch (e: IOException) {
            return@coroutineScope false
        }

        val cursor = AtomicLong(0)
        val done = AtomicLong(0)
        val reportLock = Any()
        // Workers report from several threads and the callback downstream keeps
        // running state (speed meter, notification), so serialise it here.
        val onBytes: (Int) -> Unit = { read ->
            val soFar = done.addAndGet(read.toLong())
            synchronized(reportLock) { onProgress((soFar * 100 / total).toInt(), soFar) }
        }

        (0 until CONNECTIONS).map {
            async(Dispatchers.IO) {
                RandomAccessFile(output, "rw").use { raf ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var healthy = true
                    while (healthy) {
                        val start = cursor.getAndAdd(CHUNK_SIZE)
                        if (start >= total) break
                        val end = minOf(start + CHUNK_SIZE, total) - 1
                        healthy = fetchRange(url, headers, start, end, raf, buffer, onBytes)
                    }
                    healthy
                }
            }
        }.awaitAll().all { it }
    }

    /**
     * Pulls [start]..[end] into [raf]. A failure part way through is retried
     * from wherever it stopped rather than from the head of the chunk.
     */
    private suspend fun fetchRange(
        url: String,
        headers: Map<String, String>,
        start: Long,
        end: Long,
        raf: RandomAccessFile,
        buffer: ByteArray,
        onBytes: (Int) -> Unit
    ): Boolean {
        var pos = start
        var attempts = 0
        while (pos <= end) {
            currentCoroutineContext().ensureActive()
            try {
                useCall(client.newCall(buildRequest(url, headers, pos, end))) { response ->
                    if (response.code != 206) throw IOException("range rejected: ${response.code}")
                    val stream = response.body?.byteStream() ?: throw IOException("empty body")
                    while (true) {
                        val read = stream.read(buffer)
                        if (read <= 0) break
                        raf.seek(pos)
                        raf.write(buffer, 0, read)
                        pos += read
                        onBytes(read)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Handled by the retry check below.
            }
            // A cancelled call surfaces as a plain IOException above, so the
            // retry path has to re-check before it decides to try again.
            currentCoroutineContext().ensureActive()
            if (pos > end) return true
            if (++attempts >= MAX_ATTEMPTS) return false
            delay(RETRY_DELAY_MS * attempts)
        }
        return true
    }

    /** Single sequential pass, for servers that will not serve ranges. */
    private suspend fun downloadWhole(
        url: String,
        output: File,
        headers: Map<String, String>,
        onProgress: (Int, Long) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            useCall(client.newCall(buildRequest(url, headers, null, null))) { response ->
                if (!response.isSuccessful) return@useCall false
                val body = response.body ?: return@useCall false
                val total = body.contentLength()
                var downloaded = 0L
                FileOutputStream(output).buffered(BUFFER_SIZE).use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                            downloaded += read
                            // Unknown total (chunked) → indeterminate percent (-1).
                            onProgress(
                                if (total > 0) (downloaded * 100 / total).toInt() else -1,
                                downloaded
                            )
                        }
                    }
                }
                true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    private fun buildRequest(
        url: String,
        headers: Map<String, String>,
        start: Long?,
        end: Long?
    ): Request = Request.Builder().url(url).apply {
        headers.forEach { (k, v) -> if (v.isNotBlank()) header(k, v) }
        if (start != null) header("Range", "bytes=$start-${end ?: ""}")
    }.build()
}
