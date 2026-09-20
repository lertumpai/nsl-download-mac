package com.nsl.downloader.service

import com.nsl.downloader.util.RateLimiter
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis

class HlsSegmentFetcherTest {
    private fun output() = File.createTempFile("hls-segment", ".bin")
    private fun fetcher(policy: HlsHostPolicy = HlsHostPolicy(), window: Long = 250L) =
        HlsSegmentFetcher(OkHttpClient(), policy, slowWindowMs = window,
            minBytesPerSecond = 64 * 1024, retryDelayMs = 10)

    @Test fun stalledConnectionResumesItsPrefixAndFinishesFaster() = runBlocking {
        val file = output()
        try {
            val baseline = Server(Mode.STALL).use { server ->
                measureTimeMillis {
                    OkHttpClient().newCall(Request.Builder().url(server.url).build()).execute().use {
                        assertArrayEquals(server.payload, it.body!!.bytes())
                    }
                }
            }
            Server(Mode.STALL).use { server ->
                val policy = HlsHostPolicy()
                val improved = measureTimeMillis {
                    assertTrue(fetcher(policy).fetch(server.url, emptyMap(), file) { })
                }
                assertArrayEquals(server.payload, file.readBytes())
                assertTrue(server.offsets.any { it > 0 })
                assertTrue(policy.preferHttp1("127.0.0.1"))
                assertTrue("recovery took $improved ms vs $baseline ms", improved < baseline / 2)
                println("Controlled 3-second stall; watchdog scaled to 250 ms: baseline=$baseline ms, recovery=$improved ms")
            }
        } finally { file.delete() }
    }

    @Test fun truncatedSegmentResumesWithoutDownloadingItsPrefixTwice() = runBlocking {
        val file = output()
        try {
            Server(Mode.TRUNCATE).use { server ->
                var bytes = 0L
                assertTrue(fetcher().fetch(server.url,
                    mapOf("Referer" to "https://example.test/watch", "Cookie" to "session=fixture",
                        "Accept-Encoding" to "gzip", "Range" to "bytes=7-9"), file) { bytes += it })
                assertArrayEquals(server.payload, file.readBytes())
                assertEquals(server.payload.size.toLong(), bytes)
                assertTrue(server.encodings.all { it == "identity" })
                assertTrue(server.referrers.all { it == "https://example.test/watch" })
                assertTrue(server.cookies.all { it == "session=fixture" })
            }
        } finally { file.delete() }
    }

    @Test fun ignoredRangesAndWrongOffsetsCannotCorruptTheSegment() = runBlocking {
        for (mode in listOf(Mode.IGNORE_RANGE, Mode.WRONG_RANGE, Mode.CHANGED_ETAG)) {
            val file = output()
            try {
                Server(mode).use { server ->
                    assertTrue(fetcher().fetch(server.url, emptyMap(), file) { })
                    assertArrayEquals(if (mode == Mode.CHANGED_ETAG) server.changed else server.payload, file.readBytes())
                    assertTrue(server.offsets.any { it > 0 })
                }
            } finally { file.delete() }
        }
    }

    @Test fun retryAfterIsHonored() = runBlocking {
        val file = output()
        try {
            Server(Mode.BUSY).use { server ->
                val elapsed = measureTimeMillis { assertTrue(fetcher().fetch(server.url, emptyMap(), file) { }) }
                assertTrue("Retry-After was ignored ($elapsed ms)", elapsed >= 950L)
                assertEquals(2, server.calls.get())
                assertArrayEquals(server.payload, file.readBytes())
            }
        } finally { file.delete() }
    }

    @Test fun manyDownloadsShareTheHostConnectionBudget() = runBlocking {
        val files = (0 until 30).map { output() }
        try {
            Server(Mode.HEALTHY_DELAY).use { server ->
                val engine = fetcher(HlsHostPolicy(12), window = 10_000L)
                withTimeout(10000) {
                    files.map { file -> async(Dispatchers.IO) {
                        engine.fetch(server.url, emptyMap(), file) { }
                    } }.awaitAll().forEach { assertTrue(it) }
                }
                assertTrue("host overloaded with ${server.peak.get()} calls", server.peak.get() <= 12)
                assertTrue(server.peak.get() > 1)
                files.forEach { assertArrayEquals(server.payload, it.readBytes()) }
            }
        } finally { files.forEach { it.delete() } }
    }

    @Test fun deliberateSpeedCapDoesNotTriggerSlowConnectionRecovery() = runBlocking {
        val file = output()
        try {
            Server(Mode.HEALTHY).use { server ->
                RateLimiter.bytesPerSecond = 1024 * 1024
                assertTrue(fetcher(window = 20L).fetch(server.url, emptyMap(), file) { })
                assertEquals(1, server.calls.get())
                assertArrayEquals(server.payload, file.readBytes())
            }
        } finally { RateLimiter.bytesPerSecond = 0; file.delete() }
    }

    @Test fun cancellingAStalledTransferReleasesItsHostPermit() = runBlocking {
        val file = output()
        val second = output()
        try {
            Server(Mode.STALL).use { server ->
                val engine = fetcher(HlsHostPolicy(1), window = 10_000L)
                val started = CompletableDeferred<Unit>()
                val job = launch(Dispatchers.IO) { engine.fetch(server.url, emptyMap(), file) { started.complete(Unit) } }
                withTimeout(3000) { started.await() }
                withTimeout(1000) { job.cancelAndJoin() }
                withTimeout(3000) { assertTrue(engine.fetch(server.url, emptyMap(), second) { }) }
                assertArrayEquals(server.payload, second.readBytes())
            }
        } finally { file.delete(); second.delete() }
    }

    private enum class Mode { HEALTHY, HEALTHY_DELAY, STALL, TRUNCATE, IGNORE_RANGE, WRONG_RANGE, CHANGED_ETAG, BUSY }

    private class Server(val mode: Mode) : AutoCloseable {
        val payload = ByteArray(512 * 1024) { (it % 251).toByte() }
        val changed = ByteArray(payload.size) { ((it + 71) % 251).toByte() }
        val calls = AtomicInteger()
        val peak = AtomicInteger()
        private val active = AtomicInteger()
        val offsets = ConcurrentLinkedQueue<Int>()
        val encodings = ConcurrentLinkedQueue<String>()
        val referrers = ConcurrentLinkedQueue<String>()
        val cookies = ConcurrentLinkedQueue<String>()
        private val listener = ServerSocket(0)
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()
        val url = "http://127.0.0.1:${listener.localPort}/segment.ts"
        init {
            Thread {
                while (!listener.isClosed) {
                    val socket = try { listener.accept() } catch (_: Exception) { break }
                    sockets.add(socket)
                    Thread {
                        socket.use { runCatching { serve(it) } }
                        sockets.remove(socket)
                    }.apply { isDaemon = true }.start()
                }
            }.apply { isDaemon = true }.start()
        }
        private fun serve(socket: Socket) {
            val reader = socket.getInputStream().bufferedReader()
            reader.readLine() ?: return
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: return
                if (line.isEmpty()) break
                headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
            }
            val index = calls.incrementAndGet()
            val offset = headers["range"]?.substringAfter("bytes=")?.substringBefore('-')?.toInt() ?: 0
            offsets.add(offset)
            encodings.add(headers["accept-encoding"].orEmpty())
            referrers.add(headers["referer"].orEmpty())
            cookies.add(headers["cookie"].orEmpty())
            val out = socket.getOutputStream()
            val count = active.incrementAndGet()
            peak.updateAndGet { maxOf(it, count) }
            try {
                if (mode == Mode.BUSY && index == 1) {
                    out.write("HTTP/1.1 429 Too Many Requests\r\nRetry-After: 1\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush()
                    return
                }
                if (mode == Mode.HEALTHY_DELAY) Thread.sleep(100)
                val start = if (mode == Mode.IGNORE_RANGE) 0 else offset
                val data = if (mode == Mode.CHANGED_ETAG && index > 1) changed else payload
                val end = data.lastIndex
                val rangeStart = start + if (mode == Mode.WRONG_RANGE && index == 2) 1 else 0
                val length = data.size - start
                val tag = if (mode == Mode.CHANGED_ETAG && index > 1) "v2" else "v1"
                val extra = if (start > 0) "Content-Range: bytes $rangeStart-$end/${data.size}\r\n" else ""
                out.write(("HTTP/1.1 ${if (start > 0) "206 Partial Content" else "200 OK"}\r\n" +
                    "Content-Length: $length\r\nETag: \"$tag\"\r\n$extra" +
                    "Connection: close\r\n\r\n").toByteArray())
                if (index == 1 && mode == Mode.STALL) {
                    out.write(data, 0, 1024)
                    out.flush()
                    Thread.sleep(3000)
                    out.write(data, 1024, data.size - 1024)
                } else if (index == 1 && mode in listOf(Mode.TRUNCATE, Mode.IGNORE_RANGE, Mode.WRONG_RANGE, Mode.CHANGED_ETAG)) {
                    out.write(data, 0, data.size / 2)
                } else out.write(data, start, length)
                out.flush()
            } finally { active.decrementAndGet() }
        }
        override fun close() {
            listener.close()
            sockets.forEach { runCatching { it.close() } }
        }
    }
}
