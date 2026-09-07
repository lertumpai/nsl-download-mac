package com.nsl.downloader

import android.app.Application
import android.Manifest
import android.os.Build
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.SystemClock
import android.net.Uri
import android.view.View
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.google.android.material.button.MaterialButton
import com.nsl.downloader.data.AppDatabase
import com.nsl.downloader.data.DownloadStatus
import com.nsl.downloader.data.VideoEntity
import com.nsl.downloader.data.FolderEntity
import com.nsl.downloader.library.LibraryViewModel
import com.nsl.downloader.library.LibraryFragment
import androidx.lifecycle.ViewModelProvider
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
import org.junit.Rule
import java.io.File

class LibraryMultiSelectRepairTest {
    @get:Rule val notifications: GrantPermissionRule = GrantPermissionRule.grant(
        *if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()
    )

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun longPressAndSelectAllOfferBatchRepair(): Unit = runBlocking {
        val dao = AppDatabase.getInstance(context).videoDao()
        val folderDao = AppDatabase.getInstance(context).folderDao()
        val folderId = folderDao.insert(FolderEntity(name = "Repair button test ${System.nanoTime()}"))
        val ids = mutableListOf<Long>()
        repeat(3) { index ->
            ids += dao.insert(
                VideoEntity(
                    title = "Repair test ${index + 1}",
                    sourceUrl = "https://example.invalid/${index + 1}",
                    localPath = "/sdcard/Download/repair-test-${index + 1}.mp4",
                    status = DownloadStatus.COMPLETED,
                    folderId = folderId,
                    mimeType = listOf("video/mp2t", "application/octet-stream", "")[index]
                )
            )
        }

        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                scenario.onActivity {
                    it.showLibrary()
                    it.supportFragmentManager.executePendingTransactions()
                    val fragment = it.supportFragmentManager.fragments.filterIsInstance<LibraryFragment>().single()
                    ViewModelProvider(fragment)[LibraryViewModel::class.java].selectFolder(folderId)
                }
                waitUntil {
                    var count = 0
                    scenario.onActivity {
                        count = it.findViewById<RecyclerView>(R.id.recyclerView)?.adapter?.itemCount ?: 0
                    }
                    count == 3
                }

                // Each legacy MIME used to leave Repair disabled on its own.
                for (index in 0..2) {
                    scenario.onActivity { activity ->
                        val list = activity.findViewById<RecyclerView>(R.id.recyclerView)
                        list.findViewHolderForAdapterPosition(index)!!.itemView.performLongClick()
                    }
                    waitUntil {
                        var visible = false
                        scenario.onActivity {
                            visible = it.findViewById<View>(R.id.selectionBar).visibility == View.VISIBLE
                        }
                        visible
                    }
                    tapRepairAndCheckDialog(scenario, 1)
                    scenario.onActivity { it.findViewById<View>(R.id.btnCloseSelection).performClick() }
                    waitUntil {
                        var hidden = false
                        scenario.onActivity { hidden = it.findViewById<View>(R.id.selectionBar).visibility == View.GONE }
                        hidden
                    }
                }

                scenario.onActivity {
                    it.findViewById<RecyclerView>(R.id.recyclerView)
                        .findViewHolderForAdapterPosition(0)!!.itemView.performLongClick()
                }
                waitUntil {
                    var visible = false
                    scenario.onActivity { visible = it.findViewById<View>(R.id.selectionBar).visibility == View.VISIBLE }
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
                tapRepairAndCheckDialog(scenario, 3)
            }
        } finally {
            ids.forEach { dao.deleteById(it) }
            folderDao.deleteById(folderId)
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
                mimeType = listOf("video/mp2t", "application/octet-stream", "")[index - 1]
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

    private fun tapRepairAndCheckDialog(scenario: ActivityScenario<MainActivity>, count: Int) {
        scenario.onActivity {
            assertTrue("Repair must be enabled for $count selected videos",
                it.findViewById<MaterialButton>(R.id.btnRepairSelected).isEnabled)
        }
        // Click through accessibility, so a clipped/covered button also fails.
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        waitUntil {
            val button = instrumentation.uiAutomation.rootInActiveWindow
                ?.findAccessibilityNodeInfosByViewId(context.resources.getResourceName(R.id.btnRepairSelected))
                ?.firstOrNull { it.isVisibleToUser && it.isEnabled }
            button?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }
        val title = if (count == 1) "Repair 1 video?" else "Repair $count videos?"
        waitUntil {
            instrumentation.uiAutomation.rootInActiveWindow
                ?.findAccessibilityNodeInfosByText(title)?.any { it.text?.toString() == title } == true
        }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        instrumentation.waitForIdleSync()
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
