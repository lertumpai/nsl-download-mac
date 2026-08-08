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
 *
 * Progress survives the call. What has already landed is recorded in a
 * [ResumeLog] sidecar as it lands, so a download that failed — or a whole app
 * process that died — picks up from there instead of starting the file again.
 * The record is only ever discarded by the caller, once it no longer wants the
 * bytes; see [DownloadPartials].
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
        private const val CHUNK_SIZE = 2L shl 20   // 2 MB per ordinary range request
        private const val GOOGLEVIDEO_CHUNK_SIZE = 512L shl 10 // 512 KB
        private const val BUFFER_SIZE = 1 shl 16   // 64 KB
        private const val MAX_ATTEMPTS = 3
        private const val MAX_WHOLE_ATTEMPTS = 5
        private const val RETRY_DELAY_MS = 300L

        /**
         * How much a single-stream transfer may re-fetch after an interruption.
         * The ranged path records every chunk as it completes, but a sequential
         * one would have to write the sidecar per buffer to match that, so it
         * settles for checkpoints.
         */
        private const val RESUME_RECORD_BYTES = 4L shl 20

        /** Under this the connection setup costs more than the split saves. */
        private const val MIN_PARALLEL_BYTES = 4L shl 20

        /**
         * YouTube commonly uses a separate 1–3 MB audio track after the video
         * reaches 75%. Those small tracks still need fresh query ranges or the
         * final stage is pinned to Google's per-response playback rate.
         */
        private const val MIN_GOOGLEVIDEO_PARALLEL_BYTES = 256L shl 10
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
        // A retry of a multi-part job (say a YouTube mux that failed after both
        // tracks landed) asks for files that are already whole. Answering that
        // from the sidecar keeps the retry off the network entirely.
        alreadyComplete(output)?.let { size ->
            onProgress(100, size)
            return true
        }

        val probe = probe(url, headers)
        val parallelThreshold = when (probe?.mode) {
            RangeMode.QUERY -> MIN_GOOGLEVIDEO_PARALLEL_BYTES
            else -> MIN_PARALLEL_BYTES
        }
        if (probe != null && probe.acceptsRanges && probe.length >= parallelThreshold) {
            if (downloadRanged(url, output, headers, probe.length, probe.mode, onProgress)) {
                return true
            }
            // A CDN can advertise ranges and still fall over under concurrent
            // reads; one plain pass is the safety net. It is genuinely slower —
            // a single shaped response — so the ranged path retries hard before
            // giving up on it (see [downloadRanged]).
            currentCoroutineContext().ensureActive()

            // The fallback fills the file front to back, so it has to start
            // from nothing — scattered ranges are not a prefix it can append
            // to. Taking it would throw away everything the ranged pass did
            // get. Only worth that when it got nothing: a server that answered
            // real ranges has not refused the method, and the caller's next
            // attempt resumes from those bytes instead of re-fetching them.
            if (rangedBytesOnDisk(output) > 0L) return false
        }
        return downloadWhole(url, output, headers, probe?.length ?: -1L, onProgress)
    }

    /** Bytes a previous ranged pass left in [output] and can still account for. */
    private fun rangedBytesOnDisk(output: File): Long {
        val state = ResumeLog.read(output) as? ResumeState.Ranged ?: return 0L
        val map = ChunkMap(output, state.total, state.chunkSize)
        return if (map.restore(state)) map.bytesDone() else 0L
    }

    /** The file's size if a previous attempt already finished it, else null. */
    private fun alreadyComplete(output: File): Long? {
        if (!output.exists()) return null
        val length = output.length()
        return when (val state = ResumeLog.read(output)) {
            is ResumeState.Ranged -> {
                val map = ChunkMap(output, state.total, state.chunkSize)
                if (state.total == length && map.restore(state) && map.isFull()) length else null
            }
            is ResumeState.Sequential ->
                if (state.total > 0 && state.total == length && state.bytes == length) length
                else null
            else -> null
        }
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

    private class Chunk(val index: Int, val start: Long, val end: Long) {
        var attempts = 0
    }

    /**
     * Which chunks of [output] are already on disk, kept in step with the
     * sidecar so the answer outlives the process.
     *
     * Workers complete chunks on several threads at once, so both the bitmap
     * and the write behind it are serialised. The file is a kilobyte at most
     * and a chunk takes long enough to fetch that rewriting it per completion
     * costs nothing measurable.
     */
    private class ChunkMap(
        private val output: File,
        private val total: Long,
        private val chunkSize: Long
    ) {
        val count: Int =
            if (chunkSize <= 0L) 0 else ((total + chunkSize - 1) / chunkSize).toInt()
        private val bits = ByteArray((count + 7) / 8)

        /**
         * Adopts [state] when it describes this same split of this same file,
         * and reports whether it did. A mismatch — the server now gives a
         * different length, or the last attempt used the other range mode and
         * so a different chunk size — means the bytes on disk cannot be placed,
         * so the transfer starts over.
         */
        fun restore(state: ResumeState.Ranged?): Boolean {
            val usable = state != null &&
                state.total == total &&
                state.chunkSize == chunkSize &&
                state.done.size == bits.size &&
                output.length() == total
            if (usable) state!!.done.copyInto(bits)
            return usable
        }

        @Synchronized
        fun isDone(index: Int): Boolean =
            bits[index / 8].toInt() and (1 shl (index % 8)) != 0

        @Synchronized
        fun markDone(index: Int) {
            bits[index / 8] = (bits[index / 8].toInt() or (1 shl (index % 8))).toByte()
            ResumeLog.write(output, ResumeState.Ranged(total, chunkSize, bits.copyOf()))
        }

        @Synchronized
        fun isFull(): Boolean = (0 until count).all { isDone(it) }

        /** Bytes already on disk — the last chunk is usually a short one. */
        @Synchronized
        fun bytesDone(): Long = (0 until count).sumOf { index ->
            if (!isDone(index)) 0L
            else minOf(chunkSize, total - index * chunkSize)
        }
    }

    /**
     * Splits [total] into chunks handed out to [CONNECTIONS] workers. Each
     * worker owns its own file handle and seeks to the offset it is filling,
     * so no write ordering or buffering between them is needed.
     *
     * A chunk that fails goes back in the queue instead of sinking the whole
     * pass: giving up here means restarting as one plain, CDN-shaped response,
     * which is what a download collapsing to a few KB/s after a fast start
     * actually looks like. Chunks a previous attempt finished are not queued
     * at all.
     */
    private suspend fun downloadRanged(
        url: String,
        output: File,
        headers: Map<String, String>,
        total: Long,
        mode: RangeMode,
        onProgress: (Int, Long) -> Unit
    ): Boolean = coroutineScope {
        val chunkSize = when (mode) {
            RangeMode.QUERY -> GOOGLEVIDEO_CHUNK_SIZE
            RangeMode.HEADER -> CHUNK_SIZE
        }
        // Only meaningful for a file that already holds the right bytes at the
        // right offsets, so read the record before setLength touches anything.
        val map = ChunkMap(output, total, chunkSize)
        map.restore(ResumeLog.read(output) as? ResumeState.Ranged)

        try {
            RandomAccessFile(output, "rw").use { it.setLength(total) }
        } catch (e: IOException) {
            return@coroutineScope false
        }

        val queue = java.util.concurrent.ConcurrentLinkedQueue<Chunk>()
        var offset = 0L
        var index = 0
        while (offset < total) {
            if (!map.isDone(index)) {
                queue.add(Chunk(index, offset, minOf(offset + chunkSize, total) - 1))
            }
            offset += chunkSize
            index++
        }

        val done = AtomicLong(map.bytesDone())
        val exhausted = java.util.concurrent.atomic.AtomicBoolean(false)
        val reportLock = Any()
        // Workers report from several threads and the callback downstream keeps
        // running state (speed meter, notification), so serialise it here.
        val report: () -> Unit = {
            val soFar = done.get()
            synchronized(reportLock) { onProgress((soFar * 100 / total).toInt(), soFar) }
        }
        report()

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
                        if (ok) {
                            map.markDone(chunk.index)
                            continue
                        }

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

        !exhausted.get() && queue.isEmpty() && map.isFull()
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
        knownTotal: Long,
        onProgress: (Int, Long) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        var total = knownTotal
        var downloaded = resumeSequential(output, knownTotal)
        var lastRecorded = downloaded
        var attempt = 0

        if (downloaded > 0L) onProgress((downloaded * 100 / total).toInt(), downloaded)
        // Asking for a range that starts at the end would only earn a 416.
        if (total > 0L && downloaded >= total) return@withContext true

        while (attempt < MAX_WHOLE_ATTEMPTS) {
            currentCoroutineContext().ensureActive()
            val resumeAt = downloaded.takeIf { it > 0L }
            var reachedEof = false
            val accepted = try {
                useCall(client.newCall(buildRequest(url, headers, resumeAt, null))) { response ->
                    if (!response.isSuccessful) return@useCall false
                    val body = response.body ?: return@useCall false

                    // If the origin ignores the resume Range and replies 200,
                    // restart cleanly instead of appending a second copy.
                    if (resumeAt != null && response.code != 206) {
                        downloaded = 0L
                        lastRecorded = 0L
                        output.delete()
                        ResumeLog.clear(output)
                    }

                    val responseTotal = response.header("Content-Range")
                        ?.substringAfterLast('/')
                        ?.trim()
                        ?.toLongOrNull()
                        ?: body.contentLength().takeIf { it >= 0L }?.let { downloaded + it }
                        ?: -1L
                    if (responseTotal > 0L) total = responseTotal

                    try {
                        FileOutputStream(output, downloaded > 0L).buffered(BUFFER_SIZE)
                            .use { out ->
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
                                            if (total > 0L) {
                                                (downloaded * 100 / total).coerceAtMost(100).toInt()
                                            } else {
                                                -1
                                            },
                                            downloaded
                                        )
                                        if (total > 0L &&
                                            downloaded - lastRecorded >= RESUME_RECORD_BYTES
                                        ) {
                                            // Flush first: the record must never
                                            // claim bytes still in the buffer.
                                            out.flush()
                                            ResumeLog.write(
                                                output, ResumeState.Sequential(total, downloaded)
                                            )
                                            lastRecorded = downloaded
                                        }
                                    }
                                }
                            }
                    } finally {
                        // Also on the way out of a failure: a body that stops
                        // early throws rather than reading EOF, and those bytes
                        // are the whole point of being able to resume. The
                        // stream is closed — and so flushed — by now.
                        if (total > 0L && downloaded > lastRecorded) {
                            ResumeLog.write(output, ResumeState.Sequential(total, downloaded))
                            lastRecorded = downloaded
                        }
                    }
                    reachedEof = true
                    true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }

            if (accepted && reachedEof && (total <= 0L || downloaded >= total)) {
                return@withContext true
            }

            currentCoroutineContext().ensureActive()
            attempt++
            if (attempt < MAX_WHOLE_ATTEMPTS) {
                delay(RETRY_DELAY_MS * attempt)
            }
        }
        false
    }

    /**
     * How many bytes of [output] a sequential pass may keep, given the origin
     * now reports [knownTotal]. Anything not covered by the record is dropped:
     * a previous attempt can have flushed past its last checkpoint, and those
     * bytes are unaccounted for, so the append would start at the wrong place.
     */
    private fun resumeSequential(output: File, knownTotal: Long): Long {
        val state = ResumeLog.read(output) as? ResumeState.Sequential
        val bytes = state?.bytes ?: 0L
        val usable = state != null &&
            knownTotal > 0L &&
            state.total == knownTotal &&
            bytes in 1..knownTotal &&
            output.length() >= bytes
        if (!usable) {
            output.delete()
            ResumeLog.clear(output)
            return 0L
        }
        if (output.length() > bytes) {
            runCatching { RandomAccessFile(output, "rw").use { it.setLength(bytes) } }
                .onFailure {
                    output.delete()
                    ResumeLog.clear(output)
                    return 0L
                }
        }
        return bytes
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
