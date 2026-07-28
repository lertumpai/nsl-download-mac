package com.nsl.downloader.library

import com.nsl.downloader.data.VideoEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryFiltersTest {

    private val root = video(1, null)
    private val firstFolder = video(2, 10)
    private val secondFolder = video(3, 20)

    @Test
    fun `all chip contains only unfiled root entries`() {
        assertEquals(listOf(root), listOf(root, firstFolder, secondFolder).inLibraryFolder(null))
    }

    @Test
    fun `folder chip contains only its own entries`() {
        assertEquals(
            listOf(firstFolder),
            listOf(root, firstFolder, secondFolder).inLibraryFolder(10)
        )
    }

    private fun video(id: Long, folderId: Long?) = VideoEntity(
        id = id,
        title = "Video $id",
        sourceUrl = "https://example.test/$id",
        localPath = "/downloads/$id.mp4",
        folderId = folderId
    )
}
