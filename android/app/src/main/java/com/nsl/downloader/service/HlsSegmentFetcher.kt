package com.nsl.downloader.service

import com.nsl.downloader.util.RateLimiter
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.*
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Shared across all HLS downloads owned by one downloader/service. */
internal class HlsHostPolicy(private val maxRequestsPerHost: Int = 12) {
    private class Host(limit: Int) {
        val permits = Semaphore(limit)
        val retryAt = AtomicLong()
        val http1Until = AtomicLong()
    }
    private val hosts = ConcurrentHashMap<String, Host>()
    private fun host(name: String) = hosts.getOrPut(name) { Host(maxRequestsPerHost) }

    suspend fun <T> transfer(name: String, block: suspend () -> T): T = host(name).permits.withPermit {
        // Recheck after acquiring: another response may have extended the wait.
        while (true) {
            val wait = host(name).retryAt.get() - System.nanoTime()
            if (wait <= 0L) break
            delay((wait / 1_000_000L).coerceAtLeast(1L))
        }
        block()
    }

    fun backoff(name: String, millis: Long) {
        val deadline = System.nanoTime() + millis * 1_000_000L
        host(name).retryAt.updateAndGet { maxOf(it, deadline) }
    }

    fun preferHttp1(name: String) = host(name).http1Until.get() > System.nanoTime()
    fun recoverConnection(name: String) {
        host(name).http1Until.set(System.nanoTime() + TimeUnit.MINUTES.toNanos(5))
    }
}

/** Resumes a failed HLS segment's encoded bytes before decryption/assembly. */
internal class HlsSegmentFetcher(
    private val client: OkHttpClient,
    private val hosts: HlsHostPolicy = HlsHostPolicy(),
    private val slowWindowMs: Long = 8_000L,
    private val minBytesPerSecond: Long = 8L * 1024,
    private val retryDelayMs: Long = 300L
) {
    private val recoveryClient = client.newBuilder()
        .protocols(listOf(Protocol.HTTP_1_1))
        // Recovery should not return to the same stalled multiplexed connection.
        .connectionPool(ConnectionPool(12, 2, TimeUnit.MINUTES))
        .readTimeout(minOf(client.readTimeoutMillis.toLong().takeIf { it > 0 } ?: 15_000L, 15_000L), TimeUnit.MILLISECONDS)
        .build()

    private class InvalidRange : IOException()
    private class Busy : IOException()
    private val contentRange = Regex("bytes (\\d+)-(\\d+)/(\\d+)", RegexOption.IGNORE_CASE)

    suspend fun fetch(url: String, headers: Map<String, String>, target: File, onBytes: (Int) -> Unit): Boolean {
        val base = Request.Builder().url(url).apply {
            headers.forEach { (key, value) -> if (value.isNotBlank()) header(key, value) }
            removeHeader("Range")
            removeHeader("If-Range")
            header("Accept-Encoding", "identity")
        }.build()
        var total = -1L
        var validator: String? = null
        var slowRecoveries = 0
        target.delete()
        repeat(8) { attempt ->
            currentCoroutineContext().ensureActive()
            val offset = target.length()
            val request = base.newBuilder().apply {
                if (offset > 0L) {
                    header("Range", "bytes=$offset-")
                    validator?.let { header("If-Range", it) }
                }
            }.build()
            var fatal = false
            try {
                val ok = hosts.transfer(request.url.host) {
                    val selected = if (hosts.preferHttp1(request.url.host)) recoveryClient else client
                    val call = selected.newCall(request)
                    val received = AtomicLong()
                    val wasSlow = AtomicBoolean()
                    try {
                        monitor(call, received, wasSlow, slowRecoveries < 2) {
                            useCall(call) { response ->
                                if (response.code == 429 || response.code == 503) {
                                    hosts.backoff(request.url.host, retryAfterMillis(response.header("Retry-After"))
                                        ?: (500L shl attempt.coerceAtMost(4)))
                                    throw Busy()
                                }
                                if (!response.isSuccessful) {
                                    fatal = response.code in listOf(401, 403, 404, 410)
                                    return@useCall false
                                }
                                val body = response.body ?: return@useCall false
                                val responseValidator = response.header("ETag")?.takeUnless { it.startsWith("W/") }
                                    ?: response.header("Last-Modified")
                                var append = false
                                val expectedEnd: Long
                                if (response.code == 206) {
                                    val parts = response.header("Content-Range")?.trim()
                                        ?.let { contentRange.matchEntire(it) }?.groupValues ?: throw InvalidRange()
                                    val start = parts[1].toLongOrNull() ?: throw InvalidRange()
                                    val end = parts[2].toLongOrNull() ?: throw InvalidRange()
                                    val size = parts[3].toLongOrNull() ?: throw InvalidRange()
                                    if (start != offset || end < start || end >= size ||
                                        (total > 0L && total != size) ||
                                        (validator != null && responseValidator != null && validator != responseValidator) ||
                                        (body.contentLength() >= 0L && body.contentLength() != end - start + 1)) {
                                        throw InvalidRange()
                                    }
                                    total = size
                                    expectedEnd = end + 1
                                    append = offset > 0L
                                } else if (response.code == 200) {
                                    // Range ignored or If-Range changed: replace, never append.
                                    total = body.contentLength()
                                    expectedEnd = total
                                } else return@useCall false
                                validator = responseValidator ?: validator.takeIf { append }
                                val buffer = ByteArray(64 * 1024)
                                var position = if (append) offset else 0L
                                target.outputStream(append).use { out ->
                                    val input = body.byteStream()
                                    while (true) {
                                        currentCoroutineContext().ensureActive()
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        if (expectedEnd >= 0L && position + count > expectedEnd) throw InvalidRange()
                                        received.addAndGet(count.toLong())
                                        RateLimiter.acquire(count)
                                        out.write(buffer, 0, count)
                                        position += count
                                        onBytes(count)
                                    }
                                }
                                if (expectedEnd >= 0L && position != expectedEnd) throw IOException("Incomplete segment")
                                position > 0L && (total < 0L || position == total)
                            }
                        }
                    } finally {
                        if (wasSlow.get()) slowRecoveries++
                    }
                }
                if (ok) return true
                if (fatal) return false
            } catch (e: CancellationException) {
                throw e
            } catch (_: InvalidRange) {
                // An untrusted range must not be joined to the retained prefix.
                target.delete()
                total = -1L
                validator = null
            } catch (_: Busy) {
                // The shared host gate handles Retry-After for every movie.
            } catch (_: IOException) {
                hosts.recoverConnection(request.url.host)
            }
            currentCoroutineContext().ensureActive()
            if (total > 0L && target.length() == total) return true
            if (attempt < 7) delay(retryDelayMs * (attempt + 1).coerceAtMost(3))
        }
        return false
    }

    private suspend fun <T> monitor(
        call: Call, received: AtomicLong, wasSlow: AtomicBoolean,
        recoverSlow: Boolean, block: suspend () -> T
    ): T = coroutineScope {
        val watchdog = launch(Dispatchers.Default) {
            var previous = 0L
            while (isActive) {
                delay(slowWindowMs)
                val now = received.get()
                val delta = now - previous
                previous = now
                // An intentional app-wide cap must never trigger recovery.
                if (RateLimiter.bytesPerSecond > 0L) continue
                if (delta == 0L || (recoverSlow && delta * 1000 / slowWindowMs < minBytesPerSecond)) {
                    wasSlow.set(true)
                    call.cancel()
                    break
                }
            }
        }
        try { block() } finally { watchdog.cancel() }
    }

    private fun retryAfterMillis(value: String?): Long? {
        if (value == null) return null
        value.trim().toLongOrNull()?.let { return it.coerceIn(0L, 86_400L) * 1000 }
        return runCatching {
            val parser = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            parser.timeZone = TimeZone.getTimeZone("GMT")
            (parser.parse(value)!!.time - System.currentTimeMillis()).coerceIn(0L, 86_400_000L)
        }.getOrNull()
    }

    private fun File.outputStream(append: Boolean) = java.io.FileOutputStream(this, append)
}
