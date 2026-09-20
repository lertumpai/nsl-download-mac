package com.nsl.downloader.service

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.Collections

class HlsDownloaderTest {
    @Test fun pauseThenNewDownloaderResumesOnlyMissingSegments() = runBlocking {
        val out = File.createTempFile("hls-pause", ".ts")
        var resumeFrom = 0
        val http = client { path ->
            if (path == "/master") playlist(20) else {
                val index = path.removePrefix("/segment").toInt()
                assertTrue("completed segment fetched again", index >= resumeFrom)
                ByteArray(65536) { index.toByte() }
            }
        }
        try {
            launch(kotlinx.coroutines.Dispatchers.IO) {
                val jobContext = currentCoroutineContext()
                HlsDownloader(http).download("https://cdn.test/master", out, emptyMap()) { percent, _ ->
                    if (percent >= 10) jobContext.cancel()
                }
            }.join()
            resumeFrom = (ResumeLog.read(out) as ResumeState.Segments).segments
            assertTrue(resumeFrom in 2 until 20)
            assertTrue(HlsDownloader(http).download("https://cdn.test/master", out, emptyMap()) { _, _ -> })
            assertArrayEquals(ByteArray(20 * 65536) { (it / 65536).toByte() }, out.readBytes())
        } finally { out.delete(); ResumeLog.clear(out) }
    }

    private fun playlist(count: Int) = (listOf("#EXTM3U") +
        (0 until count).flatMap { listOf("#EXTINF:1,", "segment$it") } +
        "#EXT-X-ENDLIST").joinToString("\n").toByteArray()

    @Test fun slowFirstSegmentDoesNotBlockTheNextBatchAndOutputStaysOrdered() = runBlocking {
        val seventhStarted = CountDownLatch(1)
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val hls = HlsDownloader(client { path ->
            if (path == "/master") playlist(20) else {
                val index = path.removePrefix("/segment").toInt()
                val count = active.incrementAndGet()
                peak.updateAndGet { maxOf(it, count) }
                try {
                    if (index == 0) assertTrue("idle workers never started segment 7",
                        seventhStarted.await(5, TimeUnit.SECONDS))
                    if (index == 6) seventhStarted.countDown()
                    ByteArray(1024) { index.toByte() }
                } finally { active.decrementAndGet() }
            }
        })
        val out = File.createTempFile("hls-pipeline", ".ts")
        var receivedBeforeCommit = false
        try {
            assertTrue(hls.download("https://cdn.test/master", out, emptyMap()) { percent, bytes ->
                if (percent == 0 && bytes > 0) receivedBeforeCommit = true
            })
            assertTrue("speed must update before ordered segments are committed", receivedBeforeCommit)
            assertTrue("more than six network workers", peak.get() <= 6)
            assertArrayEquals(ByteArray(20 * 1024) { (it / 1024).toByte() }, out.readBytes())
            assertFalse(File(out.parentFile, "${out.name}.segments").exists())
        } finally { out.delete(); ResumeLog.clear(out) }
    }

    @Test fun failureKeepsOnlyTheContiguousPrefixAndRetryResumesIt() = runBlocking {
        var fail = true
        val requested = Collections.synchronizedList(mutableListOf<Int>())
        val hls = HlsDownloader(client { path ->
            if (path == "/master") playlist(15) else {
                val index = path.removePrefix("/segment").toInt()
                requested.add(index)
                if (fail && index == 4) throw IOException("temporary segment failure")
                ByteArray(1024) { index.toByte() }
            }
        })
        val out = File.createTempFile("hls-resume", ".ts")
        try {
            assertFalse(hls.download("https://cdn.test/master", out, emptyMap()) { _, _ -> })
            assertEquals(4, (ResumeLog.read(out) as ResumeState.Segments).segments)
            fail = false
            requested.clear()
            assertTrue(hls.download("https://cdn.test/master", out, emptyMap()) { _, _ -> })
            assertTrue("committed segments were fetched again", requested.none { it < 4 })
            assertArrayEquals(ByteArray(15 * 1024) { (it / 1024).toByte() }, out.readBytes())
        } finally { out.delete(); ResumeLog.clear(out) }
    }

    @Test fun cancellationWhileBandwidthLimitedStopsPromptly() = runBlocking {
        val out = File.createTempFile("hls-cancel", ".ts")
        val hls = HlsDownloader(client { path ->
            if (path == "/master") playlist(15) else ByteArray(64 * 1024)
        })
        com.nsl.downloader.util.RateLimiter.bytesPerSecond = 1
        try {
            val result = kotlinx.coroutines.withTimeoutOrNull(250) {
                hls.download("https://cdn.test/master", out, emptyMap()) { _, _ -> }
            }
            assertNull(result)
            assertFalse(File(out.parentFile, "${out.name}.segments").exists())
        } finally {
            com.nsl.downloader.util.RateLimiter.bytesPerSecond = 0
            out.delete(); ResumeLog.clear(out)
        }
    }

    @Test fun streamsAesSegmentsWithDefaultSequenceIvAndEitherPadding() = runBlocking {
        val key = ByteArray(16) { it.toByte() }
        val iv = ByteArray(16).also { it[15] = 7 }
        val plain = ByteArray(2_000_000) { (it % 251).toByte() }
        for (padding in listOf("PKCS5Padding", "NoPadding")) {
            val cipher = javax.crypto.Cipher.getInstance("AES/CBC/$padding")
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,
                javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.IvParameterSpec(iv))
            val encrypted = cipher.doFinal(plain)
            val hls = HlsDownloader(client { path ->
                when (path) {
                    "/master" -> ("#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:7\n" +
                        "#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n" +
                        "#EXTINF:1,\nsegment0\n#EXT-X-ENDLIST").toByteArray()
                    "/key" -> key
                    else -> encrypted
                }
            })
            val out = File.createTempFile("hls-aes", ".ts")
            try {
                assertTrue(hls.download("https://cdn.test/master", out, emptyMap()) { _, _ -> })
                assertArrayEquals(plain, out.readBytes())
            } finally { out.delete(); ResumeLog.clear(out) }
        }
    }

    private fun client(body: (String) -> ByteArray) = OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(body(chain.request().url.encodedPath).toResponseBody()).build()
    }.build()

    @Test fun preservesDefaultAudioGroupWithExtensionlessPlaylists() {
        val text = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",NAME="Thai",DEFAULT=YES,URI="audio/main"
            #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720,AUDIO="a"
            video/index
        """.trimIndent()
        val variant = HlsDownloader(client { text.toByteArray() }).listVariants("https://cdn.test/master", emptyMap()).single()
        assertEquals("https://cdn.test/audio/main", variant.audioUrl)
        assertEquals("https://cdn.test/video/index", variant.url)
    }

    @Test fun removesPngWrapperAndKeepsAllTsPackets() = runBlocking {
        val ts = ByteArray(188 * 2000) { if (it % 188 == 0) 0x47 else 1 }
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 13, 10, 26, 10,
            0, 0, 0, 0, 73, 69, 78, 68, 0, 0, 0, 0)
        val hls = HlsDownloader(client { path ->
            if (path == "/master") "#EXTM3U\n#EXTINF:1,\n//cdn.test/segment.jpg\n#EXT-X-ENDLIST".toByteArray()
            else png + ts
        })
        val out = File.createTempFile("hls", ".ts")
        try {
            assertTrue(hls.download("https://cdn.test/master", out, emptyMap()) { _, _ -> })
            assertArrayEquals(ts, out.readBytes())
        } finally { out.delete(); ResumeLog.clear(out) }
    }

    @Test fun refusesDrmMissingKeysAndUnsupportedContainers() = runBlocking {
        for (tag in listOf("#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"key\"",
            "#EXT-X-KEY:METHOD=AES-128,URI=\"key\"", "#EXT-X-MAP:URI=\"init\"", "#EXT-X-BYTERANGE:100@0")) {
            val hls = HlsDownloader(client { "#EXTM3U\n$tag\n#EXTINF:1,\nsegment\n#EXT-X-ENDLIST".toByteArray() })
            val out = File.createTempFile("hls", ".ts")
            try { assertFalse(hls.download("https://cdn.test/master", out, emptyMap()) { _, _ -> }) }
            finally { out.delete(); ResumeLog.clear(out) }
        }
    }
}
