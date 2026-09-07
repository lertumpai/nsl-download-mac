package com.nsl.downloader.library

import com.nsl.downloader.data.DownloadStatus
import com.nsl.downloader.data.VideoEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepairEligibilityTest {
    private val downloaded = VideoEntity(
        title = "Legacy movie", sourceUrl = "https://example.invalid/movie",
        localPath = "content://media/external/downloads/12", status = DownloadStatus.COMPLETED
    )

    @Test fun `legacy HLS and unknown labels allow the media probe to decide`() {
        listOf("video/mp4", "video/mp2t", "video/MP2T", "video/mp4; codecs=avc1",
            "application/octet-stream", "application/vnd.apple.mpegurl", "").forEach {
            assertTrue("Repair disabled for $it", downloaded.copy(mimeType = it).canRepair)
        }
    }

    @Test fun `audio and incomplete downloads are excluded`() {
        assertFalse(downloaded.copy(mimeType = " AUDIO/MPEG ").canRepair)
        assertFalse(downloaded.copy(localPath = "").canRepair)
        listOf(DownloadStatus.PENDING, DownloadStatus.DOWNLOADING, DownloadStatus.FAILED).forEach {
            assertFalse(downloaded.copy(status = it).canRepair)
        }
    }
}
