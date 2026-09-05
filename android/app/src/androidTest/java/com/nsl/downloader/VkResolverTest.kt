package com.nsl.downloader

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nsl.downloader.util.VideoType
import com.nsl.downloader.util.detectVideoType
import com.nsl.downloader.vk.VkResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs on a device because both halves of [VkResolver] lean on the platform:
 * URL classification on `android.net.Uri`, payload parsing on `org.json`.
 */
@RunWith(AndroidJUnit4::class)
class VkResolverTest {

    @Test
    fun recognisesVkHosts() {
        listOf(
            "https://vk.com/video-1_2",
            "https://m.vk.com/video-1_2",
            "https://vkvideo.ru/video-1_2",
            "https://www.vk.ru/video-1_2"
        ).forEach { assertTrue(it, VkResolver.isVkUrl(it)) }

        listOf(
            "https://youtube.com/watch?v=abc",
            "https://notvk.com/video-1_2",
            "https://vk.com.evil.example/video-1_2"
        ).forEach { assertFalse(it, VkResolver.isVkUrl(it)) }
    }

    @Test
    fun recognisesVideoPages() {
        listOf(
            "https://vkvideo.ru/video-22822305_456241864",
            "https://vk.com/video123456_789",
            "https://vk.com/clip-1_2",
            "https://vkvideo.ru/playlist/-22822305_5/video-22822305_456241864",
            "https://vk.com/video_ext.php?oid=-22822305&id=456241864&hash=abc",
            "https://vk.com/feed?z=video-1_2%2Fpl_wall_-1"
        ).forEach { assertTrue(it, VkResolver.isVideoPageUrl(it)) }

        listOf(
            "https://vk.com/feed",
            "https://vk.com/im",
            "https://vk.com/video_ext.php?hash=abc",
            "https://youtube.com/watch?v=abc"
        ).forEach { assertFalse(it, VkResolver.isVideoPageUrl(it)) }
    }

    /**
     * Progressive renditions come out highest-first and ahead of the adaptive
     * manifests, and the primary `urlNNN` copy wins over the `cacheNNN` one at
     * the same height.
     */
    @Test
    fun ranksSourcesBestProgressiveFirst() {
        val json = """
        {"title":"Clip | VK","sources":[
          {"url":"https://cdn.example/c480.mp4","height":480,"type":"mp4cache"},
          {"url":"https://cdn.example/v240.mp4","height":240,"type":"mp4"},
          {"url":"https://cdn.example/v480.mp4","height":480,"type":"mp4"},
          {"url":"https://cdn.example/v1080.mp4","height":1080,"type":"mp4"},
          {"url":"https://vk.com/video_hls.php?extra=d","height":0,"type":"hls"},
          {"url":"https://cdn.example/index.mpd","height":0,"type":"dash"}
        ]}
        """.trimIndent()

        val resolved = VkResolver.parse(json, "ignored")

        assertEquals("Clip", resolved.title)
        assertEquals(
            listOf("1080p", "480p", "240p", "Adaptive (HLS)", "Adaptive (DASH)"),
            resolved.sources.map { it.label }
        )
        // The cache copy arrived first but must not displace the primary URL.
        assertEquals("https://cdn.example/v480.mp4", resolved.sources[1].url)
    }

    @Test
    fun ignoresJunkAndSurvivesGarbage() {
        val json = """
        {"title":"","sources":[
          {"url":"blob:https://vk.com/abc","height":0,"type":"mp4"},
          {"url":"https://cdn.example/v720.mp4","height":720,"type":"mp4"},
          {"url":"https://cdn.example/nope.mp4","height":99999,"type":"mp4"}
        ]}
        """.trimIndent()

        val resolved = VkResolver.parse(json, "Fallback | ВКонтакте")
        assertEquals(listOf("720p"), resolved.sources.map { it.label })
        assertEquals("Fallback", resolved.title)

        assertTrue(VkResolver.parse("not json at all", "T").sources.isEmpty())
    }

    /**
     * The downloader picks its strategy from the URL alone, and VK's master
     * playlist carries no `.m3u8` in its path — without this it would be saved
     * as a one-line text file.
     */
    @Test
    fun classifiesVkManifests() {
        assertEquals(
            VideoType.HLS,
            detectVideoType("https://vk.com/video_hls.php?extra=abc")
        )
        assertEquals(
            VideoType.DASH,
            detectVideoType("https://cdn.example/index.mpd?extra=abc")
        )
        assertEquals(
            VideoType.DIRECT,
            detectVideoType("https://cdn.example/videos/v720.mp4?extra=abc")
        )
    }
}
