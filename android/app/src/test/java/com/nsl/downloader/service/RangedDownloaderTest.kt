package com.nsl.downloader.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.Random
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Exercises [RangedDownloader] against a local HTTP/1.1 server so the chunk
 * maths, the concurrent seek-writes and the resume-after-failure path are
 * checked byte for byte. Offline and deterministic — no CDN involved.
 */
class RangedDownloaderTest {

    private lateinit var server: FakeRangeServer
    private lateinit var output: File

    @Before
    fun setUp() {
        output = File.createTempFile("ranged", ".bin").also { it.delete() }
    }

    @After
    fun tearDown() {
        if (this::server.isInitialized) server.close()
        output.delete()
    }

    private fun downloader() = RangedDownloader(OkHttpClient())

    private fun download(): Boolean = runBlocking {
        downloader().download(server.url, output, emptyMap()) { _, _ -> }
    }

    /** Multi-chunk, multi-connection: the file must come back byte-identical. */
    @Test
    fun `parallel ranges reassemble the file exactly`() {
        // 21 MB spans several 4 MB chunks and ends on a partial one.
        server = FakeRangeServer(payloadOf(21 * 1024 * 1024 + 12345))
        assertTrue(download())
        assertEquals(server.payload.size.toLong(), output.length())
        assertArrayEquals(server.payload, output.readBytes())
        assertTrue("expected several range requests", server.rangeRequests.get() > 1)
    }

    /** A response cut short mid-chunk resumes from where it stopped. */
    @Test
    fun `truncated responses are resumed not restarted`() {
        server = FakeRangeServer(payloadOf(12 * 1024 * 1024), truncateFirst = 3)
        assertTrue(download())
        assertArrayEquals(server.payload, output.readBytes())
    }

    /** A server that ignores Range still works, via the single-stream path. */
    @Test
    fun `falls back to one stream when ranges are refused`() {
        server = FakeRangeServer(payloadOf(9 * 1024 * 1024), honourRanges = false)
        assertTrue(download())
        assertArrayEquals(server.payload, output.readBytes())
    }

    /** Small files skip the split entirely but must still land intact. */
    @Test
    fun `small file takes the single stream path`() {
        server = FakeRangeServer(payloadOf(64 * 1024))
        assertTrue(download())
        assertArrayEquals(server.payload, output.readBytes())
    }

    /** A short 200 response must resume/retry instead of accepting a partial file. */
    @Test
    fun `single stream retries a premature end near completion`() {
        server = FakeRangeServer(
            payloadOf(512 * 1024),
            honourRanges = false,
            truncateWholeFirst = 2
        )
        assertTrue(download())
        assertArrayEquals(server.payload, output.readBytes())
        assertTrue("expected the whole stream to be retried", server.wholeRequests.get() > 1)
    }

    /**
     * The transfer loop blocks in a socket read, which coroutine cancellation
     * cannot interrupt on its own — the download has to stop anyway, and fast,
     * or the Library's delete leaves the service running.
     */
    @Test
    fun `cancelling stops a transfer already in flight`() {
        server = FakeRangeServer(payloadOf(80 * 1024 * 1024), trickle = true)
        val seen = java.util.concurrent.CountDownLatch(1)
        var outcome: Throwable? = null

        runBlocking {
            val job = launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    downloader().download(server.url, output, emptyMap()) { _, _ -> seen.countDown() }
                } catch (e: Throwable) {
                    outcome = e
                    throw e
                }
            }
            assertTrue("transfer never started", seen.await(10, TimeUnit.SECONDS))
            job.cancel()
            // Well inside the 30 s read timeout: proof the socket is torn down
            // rather than the loop merely being left to finish.
            withTimeout(5_000) { job.join() }
        }

        assertTrue("expected cancellation, got $outcome", outcome is CancellationException)
    }

    private fun payloadOf(size: Int) = ByteArray(size).also { Random(42).nextBytes(it) }

    /**
     * Minimal HTTP/1.1 origin. Answers `Range` with 206 + `Content-Range`, or
     * ignores it when [honourRanges] is false. The first [truncateFirst] ranged
     * responses are cut in half and the socket dropped, which reads to the
     * client as a premature EOF.
     */
    private class FakeRangeServer(
        val payload: ByteArray,
        private val honourRanges: Boolean = true,
        truncateFirst: Int = 0,
        truncateWholeFirst: Int = 0,
        /** Drip the body out so a transfer stays in flight long enough to cancel. */
        private val trickle: Boolean = false
    ) : AutoCloseable {

        private val socket = ServerSocket(0)
        private val truncationsLeft = AtomicInteger(truncateFirst)
        private val wholeTruncationsLeft = AtomicInteger(truncateWholeFirst)
        val rangeRequests = AtomicInteger(0)
        val wholeRequests = AtomicInteger(0)

        val url: String get() = "http://127.0.0.1:${socket.localPort}/video.mp4"

        init {
            Thread {
                while (!socket.isClosed) {
                    val client = try { socket.accept() } catch (e: Exception) { break }
                    Thread { runCatching { serve(client) }; runCatching { client.close() } }
                        .apply { isDaemon = true }.start()
                }
            }.apply { isDaemon = true }.start()
        }

        private fun serve(client: Socket) {
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            var range: String? = null
            while (true) {
                val line = reader.readLine() ?: return
                if (line.isEmpty()) break
                if (line.startsWith("Range:", ignoreCase = true)) {
                    range = line.substringAfter(':').trim()
                }
            }

            val out = client.getOutputStream()
            val spec = range?.takeIf { honourRanges }?.removePrefix("bytes=")
            if (spec == null) {
                wholeRequests.incrementAndGet()
                out.write(
                    header(
                        "200 OK",
                        "Content-Length: ${payload.size}",
                        "Accept-Ranges: bytes"
                    )
                )
                val send = if (wholeTruncationsLeft.getAndDecrement() > 0) {
                    payload.size / 2
                } else {
                    payload.size
                }
                out.write(payload, 0, send)
                out.flush()
                return
            }

            rangeRequests.incrementAndGet()
            val start = spec.substringBefore('-').toInt()
            val end = spec.substringAfter('-').toIntOrNull() ?: (payload.size - 1)
            val length = end - start + 1
            out.write(
                header(
                    "206 Partial Content",
                    "Content-Length: $length",
                    "Content-Range: bytes $start-$end/${payload.size}",
                    "Accept-Ranges: bytes"
                )
            )
            // Cut some responses short so the client has to resume mid-chunk.
            val send = if (truncationsLeft.getAndDecrement() > 0) length / 2 else length
            if (trickle) {
                var written = 0
                while (written < send) {
                    val n = minOf(16 * 1024, send - written)
                    out.write(payload, start + written, n)
                    out.flush()
                    written += n
                    Thread.sleep(20)
                }
            } else {
                out.write(payload, start, send)
                out.flush()
            }
        }

        private fun header(status: String, vararg lines: String): ByteArray =
            (listOf("HTTP/1.1 $status") + lines + listOf("Connection: close", "", ""))
                .joinToString("\r\n")
                .toByteArray()

        override fun close() {
            runCatching { socket.close() }
        }
    }
}
