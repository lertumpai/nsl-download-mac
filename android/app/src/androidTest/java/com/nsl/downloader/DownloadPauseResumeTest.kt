package com.nsl.downloader

import android.Manifest
import android.app.NotificationManager
import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.nsl.downloader.data.*
import com.nsl.downloader.service.DownloadService
import com.nsl.downloader.service.DownloadPartials
import com.nsl.downloader.service.ResumeLog
import com.nsl.downloader.service.ResumeState
import com.nsl.downloader.util.MediaStorage
import com.nsl.downloader.util.Prefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

class DownloadPauseResumeTest {
    @get:Rule val notifications: GrantPermissionRule = GrantPermissionRule.grant(
        *if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()
    )
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun downloadWakeLockHeld(): Boolean {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("dumpsys power")
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { reader ->
            reader.lineSequence().any { it.contains("PARTIAL_WAKE_LOCK") && it.contains("NSL:downloads") }
        }
    }

    @Test fun pausedRequestsSurviveReopeningAndTransitionsAreConditional() = runBlocking {
        val name = "pause-test-${System.nanoTime()}.db"
        fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        var db = open()
        try {
            val request = DownloadRequest(headers = mapOf("Referer" to "https://example.test"), ytHeight = 1080)
            val id = db.videoDao().insert(VideoEntity(title = "Saved", sourceUrl = "https://example.test/video",
                localPath = "", request = request))
            assertEquals(1, db.videoDao().pause(id))
            db.close()
            db = open()
            val saved = db.videoDao().getById(id)!!
            assertEquals(DownloadStatus.PAUSED, saved.status)
            assertEquals(request, saved.request)
            assertTrue(saved.canResume)
            assertEquals(0, db.videoDao().startDownload(id))
            assertEquals(1, db.videoDao().queueResume(id))
            assertEquals(0, db.videoDao().queueResume(id))
            assertEquals(1, db.videoDao().startDownload(id))
            db.videoDao().pauseInterrupted(listOf(id))
            assertEquals(DownloadStatus.DOWNLOADING, db.videoDao().getById(id)!!.status)
            db.videoDao().pauseInterrupted(emptyList())
            assertEquals(DownloadStatus.PAUSED, db.videoDao().getById(id)!!.status)
            db.videoDao().update(saved.copy(status = DownloadStatus.COMPLETED))
            assertEquals(0, db.videoDao().pause(id))
            assertEquals(0, db.videoDao().queueResume(id))
            db.videoDao().deleteById(id)
            assertEquals(0, db.videoDao().queueResume(id))
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun activeAndQueuedDownloadsPauseAndResumeWithoutLosingCompletedChunks() = runBlocking {
        val prefs = Prefs(context)
        val oldLimit = prefs.maxConcurrentDownloads
        val oldSpeed = prefs.speedLimitBytesPerSecond
        prefs.maxConcurrentDownloads = 1
        prefs.speedLimitBytesPerSecond = 0
        val dao = AppDatabase.getInstance(context).videoDao()
        val ids = mutableListOf<Long>()
        val tag = "Pause test ${System.nanoTime()}"
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val server = RangeServer()
        suspend fun row(title: String): VideoEntity = withTimeout(15000) {
            var found = dao.observeAllOnce().firstOrNull { it.title == title }
            while (found == null) {
                delay(20)
                found = dao.observeAllOnce().firstOrNull { it.title == title }
            }
            found
        }
        suspend fun waitFor(check: suspend () -> Boolean) = withTimeout(30000) {
            while (!check()) delay(20)
        }
        try {
            DownloadService.start(context, server.url, "$tag active")
            val first = row("$tag active").also { ids.add(it.id) }
            val partial = java.io.File(DownloadPartials.forVideo(context, first.id), "media.mp4")
            waitFor {
                (ResumeLog.read(partial) as? ResumeState.Ranged)?.done?.any { it.toInt() != 0 } == true
            }
            assertTrue("active download should hold its CPU wake lock", downloadWakeLockHeld())
            val nm = context.getSystemService(NotificationManager::class.java)
            assertTrue(nm.activeNotifications.any { it.notification.actions?.any { a -> a.title.toString() == "Pause" } == true })
            DownloadService.start(context, server.url, "$tag queued")
            val second = row("$tag queued").also { ids.add(it.id) }
            assertEquals(DownloadStatus.PENDING, second.status)
            DownloadService.pause(context, second.id)
            waitFor { dao.getById(second.id)?.status == DownloadStatus.PAUSED }
            DownloadService.pause(context, first.id)
            waitFor { dao.getById(first.id)?.status == DownloadStatus.PAUSED && first.id !in DownloadService.runningIds() }
            waitFor { !downloadWakeLockHeld() }
            val state = ResumeLog.read(partial) as ResumeState.Ranged
            val completed = (0 until state.done.size * 8).filter {
                state.done[it / 8].toInt() and (1 shl (it % 8)) != 0
            }.toSet()
            assertTrue(completed.isNotEmpty())
            assertTrue(nm.activeNotifications.any { it.notification.actions?.any { a -> a.title.toString() == "Resume" } == true })
            // A separate database connection sees the durable pause and original request.
            Room.databaseBuilder(context, AppDatabase::class.java, "nsl_downloader.db").build().let { reopened ->
                try { assertEquals(DownloadStatus.PAUSED, reopened.videoDao().getById(first.id)!!.status) }
                finally { reopened.close() }
            }
            server.starts.clear()
            server.slow.set(false)
            DownloadService.retry(context, first.id)
            DownloadService.retry(context, first.id)
            waitFor { dao.getById(first.id)?.status == DownloadStatus.COMPLETED }
            val done = dao.getById(first.id)!!
            context.contentResolver.openInputStream(MediaStorage.toUri(done.localPath))!!.use {
                assertArrayEquals(server.payload, it.readBytes())
            }
            assertTrue("completed chunks fetched again", server.starts.none { (start, length) ->
                length > 1 && (start / state.chunkSize).toInt() in completed
            })
            assertEquals("paused queue must not auto-start", DownloadStatus.PAUSED, dao.getById(second.id)!!.status)
            // Race a quick Resume against teardown of the same writer.
            server.slow.set(true)
            DownloadService.retry(context, second.id)
            waitFor { dao.getById(second.id)?.status == DownloadStatus.DOWNLOADING }
            DownloadService.pause(context, second.id)
            waitFor { dao.getById(second.id)?.status == DownloadStatus.PAUSED }
            DownloadService.retry(context, second.id)
            DownloadService.retry(context, second.id)
            waitFor { dao.getById(second.id)?.status == DownloadStatus.DOWNLOADING }
            context.stopService(android.content.Intent(context, DownloadService::class.java))
            waitFor { dao.getById(second.id)?.status == DownloadStatus.PAUSED && second.id !in DownloadService.runningIds() }
            server.slow.set(false)
            DownloadService.retry(context, second.id)
            waitFor { dao.getById(second.id)?.status == DownloadStatus.COMPLETED }
            context.contentResolver.openInputStream(MediaStorage.toUri(dao.getById(second.id)!!.localPath))!!.use {
                assertArrayEquals(server.payload, it.readBytes())
            }
        } finally {
            for (id in ids) {
                if (id in DownloadService.runningIds()) DownloadService.cancel(context, id)
                waitFor { id !in DownloadService.runningIds() }
                dao.getById(id)?.localPath?.takeIf { it.isNotBlank() }?.let { MediaStorage.delete(context, it) }
                dao.deleteById(id)
                DownloadPartials.discard(context, id)
                context.getSystemService(NotificationManager::class.java).cancel(DownloadService.DONE_NOTIF_BASE + id.toInt())
            }
            server.close()
            scenario.close()
            prefs.maxConcurrentDownloads = oldLimit
            prefs.speedLimitBytesPerSecond = oldSpeed
        }
    }

    private class RangeServer : AutoCloseable {
        val payload = ByteArray(32 * 1024 * 1024) { (it % 251).toByte() }
        val slow = AtomicBoolean(true)
        val starts = ConcurrentLinkedQueue<Pair<Long, Int>>()
        private val server = ServerSocket(0)
        val url = "http://127.0.0.1:${server.localPort}/video.mp4"
        init {
            Thread {
                while (!server.isClosed) {
                    val socket = try { server.accept() } catch (_: Exception) { break }
                    Thread { socket.use { runCatching { serve(it) } } }.apply { isDaemon = true }.start()
                }
            }.apply { isDaemon = true }.start()
        }
        private fun serve(socket: Socket) {
            val reader = socket.getInputStream().bufferedReader()
            reader.readLine() ?: return
            var range = "0-${payload.lastIndex}"
            while (true) {
                val line = reader.readLine() ?: return
                if (line.isEmpty()) break
                if (line.startsWith("Range:", true)) range = line.substringAfter("bytes=")
            }
            val start = range.substringBefore('-').toInt()
            val end = range.substringAfter('-').toIntOrNull() ?: payload.lastIndex
            val length = end - start + 1
            starts.add(start.toLong() to length)
            val out = socket.getOutputStream()
            out.write(("HTTP/1.1 206 Partial Content\r\nContent-Length: $length\r\n" +
                "Content-Range: bytes $start-$end/${payload.size}\r\nConnection: close\r\n\r\n").toByteArray())
            var pos = start
            while (pos <= end) {
                val n = minOf(16384, end - pos + 1)
                out.write(payload, pos, n)
                out.flush()
                pos += n
                if (slow.get() && length > 1) Thread.sleep(10)
            }
        }
        override fun close() { server.close() }
    }
}
