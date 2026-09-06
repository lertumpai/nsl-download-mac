package com.nsl.downloader

import android.app.Application
import android.content.Intent
import android.os.SystemClock
import android.net.Uri
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.button.MaterialButton
import com.nsl.downloader.data.AppDatabase
import com.nsl.downloader.data.DownloadStatus
import com.nsl.downloader.data.VideoEntity
import com.nsl.downloader.library.LibraryViewModel
import com.nsl.downloader.util.MediaStorage
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LibraryMultiSelectRepairTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun longPressAndSelectAllOfferBatchRepair(): Unit = runBlocking {
        val dao = AppDatabase.getInstance(context).videoDao()
        val previous = dao.observeAllOnce()
        dao.deleteAll()
        repeat(3) { index ->
            dao.insert(
                VideoEntity(
                    title = "Repair test ${index + 1}",
                    sourceUrl = "https://example.invalid/${index + 1}",
                    localPath = "/sdcard/Download/repair-test-${index + 1}.mp4",
                    status = DownloadStatus.COMPLETED,
                    mimeType = "video/mp4"
                )
            )
        }

        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                scenario.onActivity { it.showLibrary() }
                waitUntil {
                    var count = 0
                    scenario.onActivity {
                        count = it.findViewById<RecyclerView>(R.id.recyclerView)?.adapter?.itemCount ?: 0
                    }
                    count == 3
                }

                scenario.onActivity { activity ->
                    val list = activity.findViewById<RecyclerView>(R.id.recyclerView)
                    list.findViewHolderForAdapterPosition(0)!!.itemView.performLongClick()
                }
                waitUntil {
                    var visible = false
                    scenario.onActivity {
                        visible = it.findViewById<View>(R.id.selectionBar).visibility == View.VISIBLE
                    }
                    visible
                }

                scenario.onActivity {
                    it.findViewById<MaterialButton>(R.id.btnSelectAll).performClick()
                }
                waitUntil {
                    var label = ""
                    scenario.onActivity {
                        label = it.findViewById<TextView>(R.id.selectionCount).text.toString()
                    }
                    label == "3 of 3 selected"
                }
                scenario.onActivity {
                    assertTrue(it.findViewById<MaterialButton>(R.id.btnRepairSelected).isEnabled)
                    assertEquals("Repair", it.findViewById<MaterialButton>(R.id.btnRepairSelected).text)
                }
            }
        } finally {
            dao.deleteAll()
            previous.forEach { dao.insert(it) }
        }
    }

    @Test fun batchContinuesAfterIndividualRepairFailures(): Unit = runBlocking {
        val videos = (1..3).map { index ->
            VideoEntity(
                id = index.toLong(),
                title = "Unavailable repair test $index",
                sourceUrl = "https://example.invalid/$index",
                localPath = "/sdcard/Download/missing-repair-test-$index.mp4",
                status = DownloadStatus.COMPLETED,
                mimeType = "video/mp4"
            )
        }
        val viewModel = LibraryViewModel(context.applicationContext as Application)
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(10_000) { viewModel.repairResults.first() }
        }
        viewModel.repairVideos(videos)

        val finished = result.await()
        assertEquals(0, finished.repaired)
        assertEquals(0, finished.alreadyPlayable)
        assertEquals(3, finished.failed)
        assertFalse(finished.cancelled)
    }

    @Test fun fileUriDownloadsAreRecognizedAsExisting() {
        val file = File(context.cacheDir, "batch-repair-file-uri.mp4")
        file.writeBytes(byteArrayOf(0))
        try {
            assertTrue(MediaStorage.exists(context, Uri.fromFile(file).toString()))
        } finally {
            file.delete()
        }
    }

    private fun waitUntil(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        assertTrue("Condition was not met within ${timeoutMs}ms", condition())
    }
}
