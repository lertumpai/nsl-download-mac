package com.nsl.downloader.service

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class HlsDownloaderTest {
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
        val ts = ByteArray(188 * 5) { if (it % 188 == 0) 0x47 else 1 }
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
