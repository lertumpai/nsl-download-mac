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
import com.nsl.downloader.util.RateLimiter
import okhttp3.HttpUrl.Companion.toHttpUrl
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

        /**
         * Kept small on purpose. Google's CDN serves the head of every response
         * at full speed and then shapes the rest towards playback rate, so the
         * useful trick is to keep asking for fresh, short ranges rather than to
         * ride one long response down to a trickle.
         */
        private const val CHUNK_SIZE = 2L shl 20   // 2 MB per range request
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

    /**
     * How a byte range is asked for.
     *
     * Google's video CDN honours a `range=start-end` **query parameter** as well
     * as the HTTP header, and only the query form reliably escapes the shaping
     * it applies to a long-running response — the same reason yt-dlp asks for
     * its chunks that way.
     */
    private enum class RangeMode { HEADER, QUERY }

    private data class Probe(
        val length: Long,
        val acceptsRanges: Boolean,
        val mode: RangeMode = RangeMode.HEADER
    )

    suspend fun download(
        url: String,
        output: File,
        headers: Map<String, String>,
        onProgress: (Int, Long) -> Unit
    ): Boolean {
        val probe = probe(url, headers)
        if (probe != null && probe.acceptsRanges && probe.length >= MIN_PARALLEL_BYTES) {
            if (downloadRanged(url, output, headers, probe.length, probe.mode, onProgress)) {
                return true
            }
            // A CDN can advertise ranges and still fall over under concurrent
            // reads; one plain pass is the safety net. It is genuinely slower —
            // a single shaped response — so the ranged path retries hard before
            // giving up on it (see [downloadRanged]).
            currentCoroutineContext().ensureActive()
        }
        return downloadWhole(url, output, headers, onProgress)
    }

    /** A one-byte GET: reveals the total size and how ranges may be asked for. */
    private suspend fun probe(url: String, headers: Map<String, String>): Probe? =
        withContext(Dispatchers.IO) {
            try {
                val viaHeader = useCall(
                    client.newCall(buildRequest(url, headers, 0, 0, RangeMode.HEADER))
                ) { response ->
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
                } ?: return@withContext null

                if (!supportsRangeParam(url)) return@withContext viaHeader
                // Confirm the query form really slices before trusting it with
                // real chunks: a server that ignores it answers with the whole
                // file, which would be written at the wrong offset.
                val sliced = useCall(
                    client.newCall(buildRequest(url, headers, 0, 0, RangeMode.QUERY))
                ) { response ->
                    response.isSuccessful && response.body?.contentLength() == 1L
                }
                if (sliced == true) viaHeader.copy(acceptsRanges = true, mode = RangeMode.QUERY)
                else viaHeader
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }

    private fun supportsRangeParam(url: String): Boolean =
        runCatching { url.toHttpUrl().host.endsWith("googlevideo.com") }.getOrDefault(false)

    private class Chunk(val start: Long, val end: Long) {
        var attempts = 0
    }

    /**
     * Splits [total] into chunks handed out to [CONNECTIONS] workers. Each
     * worker owns its own file handle and seeks to the offset it is filling,
     * so no write ordering or buffering between them is needed.
     *
     * A chunk that fails goes back in the queue instead of sinking the whole
     * pass: giving up here means restarting as one plain, CDN-shaped response,
     * which is what a download collapsing to a few KB/s after a fast start
     * actually looks like.
     */
    private suspend fun downloadRanged(
        url: String,
        output: File,
        headers: Map<String, String>,
        total: Long,
        mode: RangeMode,
        onProgress: (Int, Long) -> Unit
    ): Boolean = coroutineScope {
        try {
            RandomAccessFile(output, "rw").use { it.setLength(total) }
        } catch (e: IOException) {
            return@coroutineScope false
        }

        val queue = java.util.concurrent.ConcurrentLinkedQueue<Chunk>()
        var offset = 0L
        while (offset < total) {
            queue.add(Chunk(offset, minOf(offset + CHUNK_SIZE, total) - 1))
            offset += CHUNK_SIZE
        }

        val done = AtomicLong(0)
        val exhausted = java.util.concurrent.atomic.AtomicBoolean(false)
        val reportLock = Any()
        // Workers report from several threads and the callback downstream keeps
        // running state (speed meter, notification), so serialise it here.
        val report: () -> Unit = {
            val soFar = done.get()
            synchronized(reportLock) { onProgress((soFar * 100 / total).toInt(), soFar) }
        }

        (0 until CONNECTIONS).map {
            async(Dispatchers.IO) {
                RandomAccessFile(output, "rw").use { raf ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (!exhausted.get()) {
                        val chunk = queue.poll() ?: break
                        var written = 0L
                        val ok = fetchRange(url, headers, chunk, mode, raf, buffer) { read ->
                            written += read
                            done.addAndGet(read.toLong())
                            report()
                        }
                        if (ok) continue

                        // Roll the partial back out of the progress before the
                        // retry re-reads the same bytes.
                        done.addAndGet(-written)
                        report()
                        if (++chunk.attempts >= MAX_ATTEMPTS) {
                            exhausted.set(true)
                            break
                        }
                        delay(RETRY_DELAY_MS * chunk.attempts)
                        queue.add(chunk)
                    }
                }
            }
        }.awaitAll()

        !exhausted.get() && queue.isEmpty() && done.get() >= total
    }

    /**
     * Pulls one [chunk] into [raf]. A failure part way through is retried from
     * wherever it stopped rather than from the head of the chunk.
     */
    private suspend fun fetchRange(
        url: String,
        headers: Map<String, String>,
        chunk: Chunk,
        mode: RangeMode,
        raf: RandomAccessFile,
        buffer: ByteArray,
        onBytes: (Int) -> Unit
    ): Boolean {
        var pos = chunk.start
        val end = chunk.end
        var attempts = 0
        while (pos <= end) {
            currentCoroutineContext().ensureActive()
            try {
                useCall(client.newCall(buildRequest(url, headers, pos, end, mode))) { response ->
                    val expected = end - pos + 1
                    when (mode) {
                        RangeMode.HEADER ->
                            if (response.code != 206) {
                                throw IOException("range rejected: ${response.code}")
                            }
                        // A server that ignored the parameter answers with the
                        // whole file; writing that at [pos] would corrupt the
                        // output, so the length has to match exactly.
                        RangeMode.QUERY ->
                            if (!response.isSuccessful ||
                                response.body?.contentLength() != expected
                            ) {
                                throw IOException("range param ignored: ${response.code}")
                            }
                    }
                    val stream = response.body?.byteStream() ?: throw IOException("empty body")
                    var remaining = expected
                    while (remaining > 0) {
                        val read = stream.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (read <= 0) break
                        RateLimiter.acquire(read)
                        raf.seek(pos)
                        raf.write(buffer, 0, read)
                        pos += read
                        remaining -= read
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
                            RateLimiter.acquire(read)
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
        end: Long?,
        mode: RangeMode = RangeMode.HEADER
    ): Request {
        val useParam = mode == RangeMode.QUERY && start != null && end != null
        val target = if (!useParam) url else runCatching {
            url.toHttpUrl().newBuilder().setQueryParameter("range", "$start-$end").build().toString()
        }.getOrDefault(url)

        return Request.Builder().url(target).apply {
            headers.forEach { (k, v) -> if (v.isNotBlank()) header(k, v) }
            if (start != null && !useParam) header("Range", "bytes=$start-${end ?: ""}")
        }.build()
    }
}
