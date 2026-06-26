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
        const val CHANNEL_ID = "download_channel"
        const val NOTIF_ID = 1001

        fun start(context: Context, url: String, title: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_DOWNLOAD
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TITLE, title)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = OkHttpClient.Builder().build()
    private val hlsDownloader by lazy { HlsDownloader(client) }
    private val queue = ArrayDeque<Pair<String, String>>()
    private var isProcessing = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DOWNLOAD) {
            val url = intent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
            val title = intent.getStringExtra(EXTRA_TITLE) ?: "Video"
            queue.addLast(url to title)
            if (!isProcessing) processQueue()
        }
        return START_NOT_STICKY
    }

    private fun processQueue() {
        if (queue.isEmpty()) {
            stopSelf()
            return
        }
        isProcessing = true
        val (url, title) = queue.removeFirst()
        scope.launch {
            startForeground(NOTIF_ID, buildNotification("Downloading: $title", 0))
            downloadVideo(url, title)
            processQueue()
        }
    }

    private suspend fun downloadVideo(url: String, title: String) {
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

        try {
            val videoType = detectVideoType(url)
            val outputFile = when (videoType) {
                VideoType.HLS -> File(dir, "$sanitized-$id.ts")
                VideoType.DASH -> File(dir, "$sanitized-$id.mp4")
                VideoType.DIRECT -> File(dir, "$sanitized-$id.${url.substringAfterLast('.').substringBefore('?').ifBlank { "mp4" }}")
            }

            val success = when (videoType) {
                VideoType.HLS -> hlsDownloader.download(url, outputFile) { progress ->
                    updateNotification("Downloading: $title", progress)
                }
                // DASH and direct are both fetched progressively; ExoPlayer plays
                // most progressive MP4/WebM. (Multi-track DASH muxing isn't supported.)
                VideoType.DASH, VideoType.DIRECT -> downloadDirect(url, outputFile) { progress ->
                    updateNotification("Downloading: $title", progress)
                }
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
        }
    }

    private suspend fun downloadDirect(url: String, output: File, onProgress: (Int) -> Unit): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).build()
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
                            if (total > 0) {
                                onProgress((downloaded * 100 / total).toInt())
                            }
                        }
                    }
                }
                true
            } catch (e: Exception) {
                false
            }
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

    private fun buildNotification(text: String, progress: Int): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("NSL Downloader")
            .setContentText(text)
            .setProgress(100, progress, progress == 0)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String, progress: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text, progress))
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
