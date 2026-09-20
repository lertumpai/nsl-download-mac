package com.nsl.downloader.data

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class DownloadQueueBusTest {
    @After fun cleanup() { DownloadQueueBus.clear() }

    @Test fun pausingAPlaylistItemDoesNotCountItAsFailedOrCompleted() {
        DownloadQueueBus.start(1, "Playlist", 3)
        DownloadQueueBus.finished(1)
        DownloadQueueBus.withdraw(1)
        val batch = DownloadQueueBus.state.value.single()
        assertEquals(2, batch.total)
        assertEquals(1, batch.finished)
        assertEquals(0, batch.failed)
        assertEquals(1, batch.remaining)
        DownloadQueueBus.withdraw(1)
        assertTrue(DownloadQueueBus.state.value.isEmpty())
    }
}
