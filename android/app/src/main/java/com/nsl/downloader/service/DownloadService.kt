package com.nsl.downloader.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.nsl.downloader.MainActivity
import com.nsl.downloader.R
import com.nsl.downloader.data.AppDatabase
import com.nsl.downloader.data.DownloadProgressBus
import com.nsl.downloader.data.DownloadStatus
import com.nsl.downloader.data.VideoEntity
import com.nsl.downloader.util.VideoType
import com.nsl.downloader.util.detectVideoType
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

class DownloadService : Service() {

    companion object {
        const val ACTION_DOWNLOAD = "com.nsl.downloader.DOWNLOAD"
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_HEADERS = "headers"
        const val CHANNEL_ID = "download_channel"
        const val NOTIF_ID = 1001

        fun start(
            context: Context,
            url: String,
            title: String,
            headers: HashMap<String, String> = HashMap()
        ) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_DOWNLOAD
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_HEADERS, headers)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private data class Job(val url: String, val title: String, val headers: Map<String, String>)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = OkHttpClient.Builder().build()
    private val hlsDownloader by lazy { HlsDownloader(client) }
    private val queue = ArrayDeque<Job>()
    private var isProcessing = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DOWNLOAD) {
            val url = intent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
            val title = intent.getStringExtra(EXTRA_TITLE) ?: "Video"
            @Suppress("UNCHECKED_CAST")
            val headers = (intent.getSerializableExtra(EXTRA_HEADERS) as? HashMap<String, String>)
                ?: HashMap()
            queue.addLast(Job(url, title, headers))
            if (!isProcessing) processQueue()
        }
        return START_NOT_STICKY
    }

    private fun processQueue() {
        if (queue.isEmpty()) {
            isProcessing = false
            stopSelf()
            return
        }
        isProcessing = true
        val job = queue.removeFirst()
        scope.launch {
            startForeground(NOTIF_ID, buildNotification(job.title, 0, 0))
            downloadVideo(job.url, job.title, job.headers)
            processQueue()
        }
    }

    private suspend fun downloadVideo(url: String, title: String, headers: Map<String, String>) {
        val db = AppDatabase.getInstance(this)
        val dao = db.videoDao()
        val sanitized = title.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(60)
        val dir = File(filesDir, "downloads").also { it.mkdirs() }

        val entity = VideoEntity(
            title = title,
            sourceUrl = url,
            localPath = "",
            status = DownloadStatus.DOWNLOADING
        )
        val id = dao.insert(entity)

        // Computes a smoothed download speed and fans progress out to the
        // Library (via the bus) and the notification.
        val meter = SpeedMeter()
        val onProgress: (Int, Long) -> Unit = { percent, bytes ->
            val speed = meter.sample(bytes)
            DownloadProgressBus.update(id, percent, speed)
            updateNotification(title, percent, speed)
        }

        try {
            val videoType = detectVideoType(url)
            val outputFile = when (videoType) {
                VideoType.HLS -> File(dir, "$sanitized-$id.ts")
                VideoType.DASH -> File(dir, "$sanitized-$id.mp4")
                VideoType.DIRECT -> File(dir, "$sanitized-$id.${url.substringAfterLast('.').substringBefore('?').ifBlank { "mp4" }}")
            }

            val success = when (videoType) {
                VideoType.HLS -> hlsDownloader.download(url, outputFile, headers, onProgress)
                // DASH and direct are both fetched progressively; ExoPlayer plays
                // most progressive MP4/WebM. (Multi-track DASH muxing isn't supported.)
                VideoType.DASH, VideoType.DIRECT -> downloadDirect(url, outputFile, headers, onProgress)
            }

            if (success && outputFile.exists()) {
                dao.update(entity.copy(
                    id = id,
                    localPath = outputFile.absolutePath,
                    fileSizeBytes = outputFile.length(),
                    status = DownloadStatus.COMPLETED
                ))
                showCompleteNotification(title)
            } else {
                dao.update(entity.copy(id = id, status = DownloadStatus.FAILED))
                showErrorNotification(title)
            }
        } catch (e: Exception) {
            dao.update(entity.copy(id = id, status = DownloadStatus.FAILED))
            showErrorNotification(title)
        } finally {
            DownloadProgressBus.clear(id)
        }
    }

    private suspend fun downloadDirect(
        url: String,
        output: File,
        headers: Map<String, String>,
        onProgress: (Int, Long) -> Unit
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).apply {
                    headers.forEach { (k, v) -> if (v.isNotBlank()) header(k, v) }
                }.build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) return@withContext false
                val body = response.body ?: return@withContext false
                val total = body.contentLength()
                var downloaded = 0L
                FileOutputStream(output).use { fos ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            fos.write(buffer, 0, read)
                            downloaded += read
                            // Unknown total (chunked) → indeterminate percent (-1).
                            val percent = if (total > 0) (downloaded * 100 / total).toInt() else -1
                            onProgress(percent, downloaded)
                        }
                    }
                }
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    /** Smoothed bytes/sec, recomputed at most every ~0.5s. */
    private class SpeedMeter {
        private var lastTime = 0L
        private var lastBytes = 0L
        private var speed = 0L
        fun sample(bytes: Long): Long {
            val now = System.currentTimeMillis()
            if (lastTime == 0L) {
                lastTime = now
                lastBytes = bytes
                return 0L
            }
            val dt = now - lastTime
            if (dt >= 500) {
                speed = (bytes - lastBytes) * 1000 / dt
                lastTime = now
                lastBytes = bytes
            }
            return speed
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Video download progress" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, progress: Int, speedBps: Long): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val indeterminate = progress < 0
        val pct = progress.coerceIn(0, 100)
        val line = buildString {
            if (!indeterminate) append("$pct%")
            if (speedBps > 0) {
                if (isNotEmpty()) append(" • ")
                append(formatSpeed(speedBps))
            }
            if (isEmpty()) append("Starting…")
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(line)
            .setProgress(100, pct, indeterminate)
            .setContentIntent(pi)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(title: String, progress: Int, speedBps: Long) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(title, progress, speedBps))
    }

    private fun formatSpeed(bps: Long): String {
        val kb = bps / 1024.0
        val mb = kb / 1024.0
        return when {
            mb >= 1 -> String.format(java.util.Locale.US, "%.1f MB/s", mb)
            else -> String.format(java.util.Locale.US, "%.0f KB/s", kb)
        }
    }

    private fun showCompleteNotification(title: String) {
        val nm = getSystemService(NotificationManager::class.java)
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Download complete")
            .setContentText(title)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_ID + 1, n)
    }

    private fun showErrorNotification(title: String) {
        val nm = getSystemService(NotificationManager::class.java)
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Download failed")
            .setContentText(title)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_ID + 2, n)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
