package com.nsl.downloader.movies

import org.junit.Assert.*
import org.junit.Test
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

class MovieResolverTest {
    @Test fun onlyMoviePagesShowResolver() {
        assertTrue(MovieResolver.isMoviePage("https://www.037hddmovies.com/2026/09/02/movie/"))
        assertFalse(MovieResolver.isMoviePage("https://www.037hddmovies.com/"))
        assertFalse(MovieResolver.isMoviePage("https://www.037hddmovies.com.evil.test/2026/09/02/movie/"))
    }

    @Test fun extractsLanguagePlayersWithoutAds() {
        val embeds = MovieResolver.embeds("""
            <iframe src="https://www.leoplayer7.com/watch?v=thai" title="Thai Player"></iframe>
            <iframe src='https://www.leoplayer7.com/watch?v=sub' title='Soundtrack Player'></iframe>
            <iframe src="https://ads.test/ad.mp4"></iframe>
        """)
        assertEquals(listOf("Thai Player", "Soundtrack Player"), embeds.map { it.second })
    }

    @Test fun extractsMobileMasterAndDeduplicatesDeviceBranches() {
        val html = """
            window.hls='https://cdn.test/master.m3u8';
            window.hls='https://cdn.test/master.m3u8';
            window.hls='https://cdn.test/master';
        """
        assertEquals(listOf("https://cdn.test/master.m3u8"), MovieResolver.playlists(html))
        assertEquals("https://cdn.test/x", MovieResolver.decode("https:\\/\\/cdn.test\\/x"))
    }

    @Test fun fallsBackFromOfflinePrimaryAndRetainsPlayerReferer() {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            var code = 200
            val text = when (request.url.host) {
                "www.037hddmovies.com" -> """<iframe src="https://www.leoplayer7.com/watch?v=x" title="Thai Player">"""
                "www.leoplayer7.com" -> when (request.url.encodedPath) {
                    "/watch" -> """atomic({"playlist":[{"api":"https://www.leoplayer7.com/api/list","token":"entry"}]}).${'$'}mount('#app')"""
                    "/api/list" -> {
                        assertEquals("entry", request.header("X-Auth-Token"))
                        """{"data":[{"api":"https://www.leoplayer7.com/api/primary","token":"server"},{"api":"https://www.leoplayer7.com/api/backup","token":"server"}]}"""
                    }
                    "/api/primary" -> """{"data":{"source":{"type":"embed","url":"https://offline.test/player"}}}"""
                    else -> """{"data":{"source":{"type":"embed","url":"https://backup.test/player"}}}"""
                }
                "offline.test" -> { code = 521; "Offline" }
                "backup.test" -> "window.hls='https://cdn.test/master.m3u8';"
                else -> {
                    assertEquals("https://backup.test/player", request.header("Referer"))
                    "#EXTM3U\n#EXTINF:1,\nsegment\n#EXT-X-ENDLIST"
                }
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Test")
                .body(text.toResponseBody()).build()
        }.build()
        val source = MovieResolver(client).resolve("https://www.037hddmovies.com/2026/09/02/movie/", "Mobile agent").single()
        assertEquals("https://cdn.test/master.m3u8", source.url)
        assertEquals("https://backup.test/player", source.headers["Referer"])
        assertEquals("https://backup.test", source.headers["Origin"])
    }
}
