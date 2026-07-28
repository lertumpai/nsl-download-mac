package com.nsl.downloader

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nsl.downloader.youtube.YouTubeResolver
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The browser classifies every page it loads to decide whether to offer the
 * YouTube download button, and that happens long before anything calls
 * [YouTubeResolver.ensureInitialised]. This class deliberately never
 * initialises the extractor, so it must be run on its own.
 */
@RunWith(AndroidJUnit4::class)
class YouTubeClassifyWithoutInitTest {

    @Test
    fun classifiesBeforeExtractorIsInitialised() {
        assertEquals(
            YouTubeResolver.Kind.STREAM,
            YouTubeResolver.classify("https://m.youtube.com/watch?v=jNQXAC9IVRw")
        )
        assertEquals(
            YouTubeResolver.Kind.STREAM,
            YouTubeResolver.classify(
                "https://m.youtube.com/watch?v=jNQXAC9IVRw&list=RDjNQXAC9IVRw"
            )
        )
        assertEquals(
            YouTubeResolver.Kind.PLAYLIST,
            YouTubeResolver.classify(
                "https://www.youtube.com/playlist?list=PL590L5WQmH8dpP0RyH5pCfIaDEdt9nk7r"
            )
        )
    }
}
