package com.nsl.downloader.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.nsl.downloader.MainActivity
import com.nsl.downloader.R
import com.nsl.downloader.data.AppDatabase
import com.nsl.downloader.data.DownloadProgressBus
import com.nsl.downloader.data.DownloadQueueBus
import com.nsl.downloader.data.DownloadStatus
import com.nsl.downloader.data.VideoEntity
import com.nsl.downloader.util.MediaStorage
import com.nsl.downloader.util.applyDownloadSettings
import com.nsl.downloader.util.VideoType
import com.nsl.downloader.util.detectVideoType
import com.nsl.downloader.youtube.YouTubeResolver
import kotlinx.coroutines.*
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class DownloadService : Service() {

    companion object {
        const val ACTION_DOWNLOAD = "com.nsl.downloader.DOWNLOAD"
        const val ACTION_CANCEL = "com.nsl.downloader.CANCEL"
        const val ACTION_CANCEL_BATCH = "com.nsl.downloader.CANCEL_BATCH"
        const val EXTRA_VIDEO_ID = "video_id"
        const val EXTRA_BATCH_ID = "batch_id"
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_HEADERS = "headers"
        const val EXTRA_KIND = "kind"
        const val EXTRA_YT_FORMAT = "yt_format"
        const val EXTRA_YT_HEIGHT = "yt_height"
        const val EXTRA_MP3_BITRATE = "mp3_bitrate"
        const val EXTRA_YT_URLS = "yt_urls"
        const val EXTRA_YT_TITLES = "yt_titles"
        const val EXTRA_FOLDER_ID = "folder_id"
        const val EXTRA_FOLDER_NAME = "folder_name"
        const val EXTRA_USER_AGENT = "user_agent"

        const val CHANNEL_ID = "download_channel"
        const val NOTIF_ID = 1001            // foreground summary
        const val PROGRESS_NOTIF_BASE = 20000 // per-download progress
        const val DONE_NOTIF_BASE = 30000     // per-download complete/failed
        const val BATCH_DONE_NOTIF_BASE = 40000 // per-playlist complete
        const val MAX_CONCURRENT = 3          // default; Settings can change it

        /** Keeps batch PendingIntent request codes clear of the video-id ones. */
        private const val BATCH_REQUEST_OFFSET = 100_000L

        /** Progress is posted at most this often; see the note in [runJob]. */
        private const val PROGRESS_INTERVAL_MS = 300L

        /**
         * In-flight transfers keyed by the library row they are filling. The
         * service owns the coroutines, but cancelling has to work from the
         * Library too, so the registry lives here rather than on the instance.
         */
        private val running = ConcurrentHashMap<Long, kotlinx.coroutines.Job>()

        /** Ids cancelled before their coroutine had a chance to register. */
        private val cancelledIds: MutableSet<Long> =
            java.util.Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())

        /** Which batch (playlist) an in-flight row belongs to; see [cancelBatch]. */
        private val batchOfVideo = ConcurrentHashMap<Long, Long>()

        private val batchSeq = java.util.concurrent.atomic.AtomicLong(1L)

        /** Ties the items of one playlist together so they can be cancelled as one. */
        fun newBatchId(): Long = batchSeq.getAndIncrement()

        /**
         * Stops the download filling library row [videoId] and discards its
         * partial file. A no-op for rows that already finished, so callers do
         * not have to check first.
         */
        fun cancel(videoId: Long) {
            cancelledIds.add(videoId)
            running.remove(videoId)?.cancel(CancellationException("cancelled by user"))
        }

        /**
         * Calls off a whole playlist: items still queued never start, and the
         * ones already transferring stop like a single cancel. The queue lives
         * on the service instance, so this has to go through an Intent.
         */
        fun cancelBatch(context: Context, batchId: Long) {
            // Drop it here as well as in the service: the banner should go away
            // on tap even if the service is already tearing itself down.
            DownloadQueueBus.remove(batchId)
            runCatching {
                context.startService(
                    Intent(context, DownloadService::class.java).apply {
                        action = ACTION_CANCEL_BATCH
                        putExtra(EXTRA_BATCH_ID, batchId)
                    }
                )
            }
        }

        /** A plain URL download: HLS, DASH or a direct progressive file. */
        fun start(
            context: Context,
            url: String,
            title: String,
            headers: HashMap<String, String> = HashMap(),
            folderId: Long? = null,
            folderName: String? = null
        ) {
            context.startDownload(
                Intent(context, DownloadService::class.java).apply {
                    putExtra(EXTRA_KIND, Kind.GENERIC.name)
                    putExtra(EXTRA_URL, url)
                    putExtra(EXTRA_TITLE, title)
                    putExtra(EXTRA_HEADERS, headers)
                    putFolder(folderId, folderName)
                }
            )
        }

        /**
         * A YouTube download. Only the watch URL is queued — the actual stream
         * URLs are resolved inside the service right before transfer, because
         * they are short-lived and a queued playlist can sit for a long time.
         */
        fun startYouTube(
            context: Context,
            pageUrl: String,
            title: String,
            format: YtFormat,
            targetHeight: Int = 0,
            mp3Bitrate: Int = 192,
            userAgent: String,
            folderId: Long? = null,
            folderName: String? = null
        ) {
            context.startDownload(
                Intent(context, DownloadService::class.java).apply {
                    putExtra(EXTRA_KIND, Kind.YOUTUBE.name)
                    putExtra(EXTRA_URL, pageUrl)
                    putExtra(EXTRA_TITLE, title)
                    putExtra(EXTRA_YT_FORMAT, format.name)
                    putExtra(EXTRA_YT_HEIGHT, targetHeight)
                    putExtra(EXTRA_MP3_BITRATE, mp3Bitrate)
                    putExtra(EXTRA_USER_AGENT, userAgent)
                    putFolder(folderId, folderName)
                }
            )
        }

        /**
         * A whole playlist in one go. Sending 200 separate Intents works, but
         * each one restarts the service and re-posts its notification, which
         * the system throttles ("enqueue rate … shedding") — so the items ride
         * in together and are queued in order.
         */
        fun startYouTubePlaylist(
            context: Context,
            items: List<Pair<String, String>>,
            format: YtFormat,
            mp3Bitrate: Int = 192,
            userAgent: String,
            batchId: Long,
            folderId: Long? = null,
            folderName: String? = null
        ) {
            if (items.isEmpty()) return
            context.startDownload(
                Intent(context, DownloadService::class.java).apply {
                    putExtra(EXTRA_KIND, Kind.YOUTUBE.name)
                    putStringArrayListExtra(EXTRA_YT_URLS, ArrayList(items.map { it.first }))
                    putStringArrayListExtra(EXTRA_YT_TITLES, ArrayList(items.map { it.second }))
                    putExtra(EXTRA_YT_FORMAT, format.name)
                    putExtra(EXTRA_YT_HEIGHT, 0)
                    putExtra(EXTRA_MP3_BITRATE, mp3Bitrate)
                    putExtra(EXTRA_USER_AGENT, userAgent)
                    putExtra(EXTRA_BATCH_ID, batchId)
                    putFolder(folderId, folderName)
                }
            )
        }

        private fun Intent.putFolder(folderId: Long?, folderName: String?) {
            putExtra(EXTRA_FOLDER_ID, folderId ?: -1L)
            putExtra(EXTRA_FOLDER_NAME, folderName)
        }

        private fun Context.startDownload(intent: Intent) {
            intent.action = ACTION_DOWNLOAD
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
        }
    }

    enum class Kind { GENERIC, YOUTUBE }
    enum class YtFormat { MP4, MP3 }

    /** A queued unit of work. (Named to stay clear of [kotlinx.coroutines.Job].) */
    private data class Task(
        val kind: Kind,
        val url: String,
        val title: String,
        val headers: Map<String, String>,
        val folderId: Long?,
        val folderName: String?,
        val ytFormat: YtFormat = YtFormat.MP4,
        val ytHeight: Int = 0,
        val mp3Bitrate: Int = 192,
        val userAgent: String = "",
        /** 0 when the download stands alone; see [DownloadService.cancelBatch]. */
        val batchId: Long = 0L
    )

    private val prefs by lazy { com.nsl.downloader.util.Prefs(this) }

    /** Re-read per pump so a change in Settings takes hold without a restart. */
    private val maxConcurrent: Int get() = prefs.maxConcurrentDownloads

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = OkHttpClient.Builder()
        // Allow many concurrent segment requests across simultaneous downloads.
        .dispatcher(Dispatcher().apply {
            maxRequests = 128
            maxRequestsPerHost = 32
        })
        .connectionPool(ConnectionPool(32, 5, TimeUnit.MINUTES))
        .retryOnConnectionFailure(true)
        // Generous per-read window: a genuinely stalled range is retried from
        // where it stopped, but a slow mobile link should not trip this.
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val hlsDownloader by lazy { HlsDownloader(client) }
    private val rangedDownloader by lazy { RangedDownloader(client) }

    /** Scratch space; finished files are published to shared storage. */
    private val workDir by lazy { File(cacheDir, "downloading").also { it.mkdirs() } }

    // Concurrency: as many downloads as Settings allows run at once; the rest wait.
    private val lock = Any()
    private val pending = ArrayDeque<Task>()
    private var active = 0

    override fun onCreate() {
        super.onCreate()
        // The service can be the first thing the system starts, so the speed
        // cap and the destination folder are applied here as well as in the UI.
        prefs.applyDownloadSettings()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            intent.getLongExtra(EXTRA_VIDEO_ID, -1L).takeIf { it >= 0 }?.let { cancel(it) }
            // Normally the cancelled job's own teardown stops us via pump();
            // this covers an id that was already gone when the action arrived.
            stopIfIdle()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_CANCEL_BATCH) {
            intent.getLongExtra(EXTRA_BATCH_ID, 0L).takeIf { it != 0L }?.let { dropBatch(it) }
            stopIfIdle()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_DOWNLOAD) {
            val playlistUrls = intent.getStringArrayListExtra(EXTRA_YT_URLS)
            val playlistTitles = intent.getStringArrayListExtra(EXTRA_YT_TITLES)
            val url = intent.getStringExtra(EXTRA_URL)
                ?: playlistUrls?.firstOrNull()
                ?: return START_NOT_STICKY
            @Suppress("UNCHECKED_CAST")
            val headers = (intent.getSerializableExtra(EXTRA_HEADERS) as? HashMap<String, String>)
                ?: HashMap()
            val folderId = intent.getLongExtra(EXTRA_FOLDER_ID, -1L).takeIf { it >= 0 }
            val task = Task(
                kind = runCatching { Kind.valueOf(intent.getStringExtra(EXTRA_KIND).orEmpty()) }
                    .getOrDefault(Kind.GENERIC),
                url = url,
                title = intent.getStringExtra(EXTRA_TITLE) ?: "Video",
                headers = headers,
                folderId = folderId,
                folderName = intent.getStringExtra(EXTRA_FOLDER_NAME),
                ytFormat = runCatching {
                    YtFormat.valueOf(intent.getStringExtra(EXTRA_YT_FORMAT).orEmpty())
                }.getOrDefault(YtFormat.MP4),
                ytHeight = intent.getIntExtra(EXTRA_YT_HEIGHT, 0),
                mp3Bitrate = intent.getIntExtra(EXTRA_MP3_BITRATE, 192),
                userAgent = intent.getStringExtra(EXTRA_USER_AGENT).orEmpty(),
                batchId = intent.getLongExtra(EXTRA_BATCH_ID, 0L)
            )
            // One Intent can carry a whole playlist; [task] is the template the
            // entries differ from only by URL and title.
            val tasks = if (playlistUrls.isNullOrEmpty()) listOf(task)
            else playlistUrls.mapIndexed { index, itemUrl ->
                task.copy(
                    url = itemUrl,
                    title = playlistTitles?.getOrNull(index)?.takeIf { it.isNotBlank() } ?: "Video"
                )
            }

            // Must enter foreground promptly after startForegroundService() —
            // that holds even for items we are about to throw away.
            startForeground(NOTIF_ID, summaryNotification())

            // A batch cancelled while its items were still being handed over:
            // its queue entry is gone, so the stragglers are dropped.
            if (task.batchId != 0L &&
                DownloadQueueBus.state.value.none { it.id == task.batchId }
            ) {
                stopIfIdle()
                return START_NOT_STICKY
            }
            synchronized(lock) { tasks.forEach { pending.addLast(it) } }
            pump()
        }
        return START_NOT_STICKY
    }

    /** Launch as many queued downloads as the concurrency limit allows. */
    private fun pump() {
        val toStart = mutableListOf<Task>()
        var idle: Boolean
        synchronized(lock) {
            while (active < maxConcurrent && pending.isNotEmpty()) {
                toStart.add(pending.removeFirst())
                active++
            }
            idle = active == 0 && pending.isEmpty()
        }

        toStart.forEach { task ->
            scope.launch {
                try {
                    runJob(task)
                } finally {
                    synchronized(lock) { active-- }
                    pump()
                }
            }
        }

        if (idle) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, summaryNotification())
        }
    }

    /**
     * Calls off every item of one playlist. Queued entries are simply dropped;
     * the two or three already transferring go through the normal per-row
     * cancel, which discards their partial files and library rows.
     */
    private fun dropBatch(batchId: Long) {
        synchronized(lock) { pending.removeAll { it.batchId == batchId } }
        DownloadQueueBus.remove(batchId)
        batchOfVideo.entries
            .filter { it.value == batchId }
            .map { it.key }
            .forEach { cancel(it) }
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, summaryNotification())
    }

    /** Tears the service down once nothing is left to transfer. */
    private fun stopIfIdle() {
        val idle = synchronized(lock) { active == 0 && pending.isEmpty() }
        if (idle) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // ---------------------------------------------------------------- jobs

    private suspend fun runJob(task: Task) {
        val dao = AppDatabase.getInstance(this).videoDao()
        val entity = VideoEntity(
            title = task.title,
            sourceUrl = task.url,
            localPath = "",
            status = DownloadStatus.DOWNLOADING,
            folderId = task.folderId,
            mimeType = if (task.kind == Kind.YOUTUBE && task.ytFormat == YtFormat.MP3)
                "audio/mpeg" else "video/mp4"
        )
        val id = dao.insert(entity)
        val nm = getSystemService(NotificationManager::class.java)
        val progressNotifId = PROGRESS_NOTIF_BASE + id.toInt()

        // Publish this coroutine so the Library and the notification action can
        // stop it, then honour a cancel that raced the insert.
        currentCoroutineContext()[kotlinx.coroutines.Job]?.let { running[id] = it }
        if (task.batchId != 0L) batchOfVideo[id] = task.batchId
        // The batch cancel only sees rows already in [batchOfVideo], so a job
        // that started while it ran has to check for itself.
        val batchGone = task.batchId != 0L &&
            DownloadQueueBus.state.value.none { it.id == task.batchId }
        if (cancelledIds.remove(id) || batchGone) {
            running.remove(id)
            batchOfVideo.remove(id)
            dao.deleteById(id)
            return
        }

        // Computes a smoothed download speed and fans progress out to the
        // Library (via the bus) and this download's own notification. Both are
        // throttled: neither can use more than a few updates a second, and
        // posting one per 64 KB read measurably slows the transfer down.
        val meter = SpeedMeter()
        var lastReport = 0L
        val report: (Int, Long) -> Unit = { percent, bytes ->
            val now = SystemClock.elapsedRealtime()
            if (now - lastReport >= PROGRESS_INTERVAL_MS) {
                lastReport = now
                val speed = meter.sample(bytes)
                DownloadProgressBus.update(id, percent, speed)
                nm.notify(progressNotifId, buildProgressNotification(id, task.title, percent, speed))
            }
        }
        // Post-download stages (mux, transcode) have no byte rate to report.
        val stage: (Int) -> Unit = { percent ->
            DownloadProgressBus.update(id, percent, 0L)
            nm.notify(progressNotifId, buildProgressNotification(id, task.title, percent, 0L))
        }

        val scratch = mutableListOf<File>()
        var downloaded = false
        try {
            val produced = when (task.kind) {
                Kind.GENERIC -> runGeneric(task, id, scratch, report)
                Kind.YOUTUBE -> runYouTube(task, id, scratch, report, stage)
            }
            // The transfer and transcode helpers report failure by returning
            // rather than throwing, so a cancel can reach here looking like an
            // error. Turn it back into one before anything is written.
            currentCoroutineContext().ensureActive()

            // Publishing and the row update are one unit: a cancel landing
            // between them would leave a file in shared storage that no library
            // entry points at.
            withContext(NonCancellable) {
                val published = if (produced == null) null else {
                    stage(99)
                    MediaStorage.publish(
                        context = this@DownloadService,
                        source = produced.file,
                        displayName = produced.displayName,
                        mimeType = produced.mimeType,
                        folder = task.folderName
                    ) ?: keepPrivately(produced)
                }

                if (produced != null && published != null) {
                    dao.update(
                        entity.copy(
                            id = id,
                            localPath = published,
                            fileSizeBytes = produced.sizeBytes,
                            durationMs = probeDurationMs(published),
                            mimeType = produced.mimeType,
                            status = DownloadStatus.COMPLETED
                        )
                    )
                    downloaded = true
                    notifyItemDone(task, id, ok = true)
                } else {
                    dao.update(entity.copy(id = id, status = DownloadStatus.FAILED))
                    notifyItemDone(task, id, ok = false)
                }
            }
        } catch (e: CancellationException) {
            // Deliberate stop. Nothing was produced, so the row goes away —
            // whether the Library already removed it or the cancel came from
            // the notification action, which leaves it behind.
            withContext(NonCancellable) { dao.deleteById(id) }
            throw e
        } catch (e: Exception) {
            // A cancel does not always arrive as a CancellationException: the
            // HTTP layer turns a cancelled call into a plain IOException. What
            // matters is whether this job was stopped, not what was thrown.
            if (!currentCoroutineContext().isActive) {
                withContext(NonCancellable) { dao.deleteById(id) }
            } else {
                withContext(NonCancellable) {
                    dao.update(entity.copy(id = id, status = DownloadStatus.FAILED))
                }
                notifyItemDone(task, id, ok = false)
            }
        } finally {
            running.remove(id)
            cancelledIds.remove(id)
            batchOfVideo.remove(id)
            // Counts however it ended: a batch that finished with failures must
            // still stop showing as in progress. The last item to land is the
            // one that reports the playlist as a whole.
            if (task.batchId != 0L) {
                DownloadQueueBus.finished(task.batchId, ok = downloaded)
                    ?.takeIf { it.remaining == 0 }
                    ?.let { showBatchDoneNotification(it) }
            }
            scratch.forEach { runCatching { it.delete() } }
            DownloadProgressBus.clear(id)
            nm.cancel(progressNotifId)
        }
    }

    /**
     * Last resort when shared storage rejects the file — most plausibly a
     * pre-Android-10 device where the user declined the storage permission.
     * The download stays playable from app-private storage instead of being
     * thrown away; it just will not show up in the Files app.
     */
    private fun keepPrivately(produced: Produced): String? = runCatching {
        val dir = File(filesDir, "downloads").also { it.mkdirs() }
        val target = File(dir, produced.file.name)
        produced.file.copyTo(target, overwrite = true)
        produced.file.delete()
        target.absolutePath
    }.getOrNull()

    /** A finished file in [workDir], ready to be published. */
    private data class Produced(
        val file: File,
        val displayName: String,
        val mimeType: String,
        val sizeBytes: Long
    )

    private suspend fun runGeneric(
        task: Task,
        id: Long,
        scratch: MutableList<File>,
        report: (Int, Long) -> Unit
    ): Produced? {
        val sanitized = MediaStorage.sanitizeName(task.title)
        val videoType = detectVideoType(task.url)
        val extension = when (videoType) {
            // The native HLS downloader concatenates transport-stream segments.
            VideoType.HLS -> "ts"
            VideoType.DASH -> "mp4"
            VideoType.DIRECT ->
                task.url.substringAfterLast('.').substringBefore('?')
                    .takeIf { it.length in 2..4 } ?: "mp4"
        }
        val output = File(workDir, "$sanitized-$id.$extension").also { scratch.add(it) }

        val ok = when (videoType) {
            VideoType.HLS -> hlsDownloader.download(task.url, output, task.headers, report)
            // DASH and direct are both fetched progressively; ExoPlayer plays
            // most progressive MP4/WebM. (Multi-track DASH muxing isn't supported.)
            VideoType.DASH, VideoType.DIRECT ->
                downloadDirect(task.url, output, task.headers, report)
        }
        if (!ok || !output.exists()) return null
        return Produced(
            file = output,
            displayName = "$sanitized.$extension",
            mimeType = mimeForExtension(extension),
            sizeBytes = output.length()
        )
    }

    private suspend fun runYouTube(
        task: Task,
        id: Long,
        scratch: MutableList<File>,
        report: (Int, Long) -> Unit,
        stage: (Int) -> Unit
    ): Produced? {
        YouTubeResolver.ensureInitialised(task.userAgent.ifBlank { DEFAULT_USER_AGENT })
        val resolved = withContext(Dispatchers.IO) {
            runCatching { YouTubeResolver.resolveVideo(task.url) }.getOrNull()
        } ?: return null
        currentCoroutineContext().ensureActive()

        val sanitized = MediaStorage.sanitizeName(
            task.title.ifBlank { resolved.title }
        )

        return if (task.ytFormat == YtFormat.MP3) {
            runYouTubeMp3(task, id, resolved, sanitized, scratch, report, stage)
        } else {
            runYouTubeMp4(task, id, resolved, sanitized, scratch, report, stage)
        }
    }

    private suspend fun runYouTubeMp4(
        task: Task,
        id: Long,
        resolved: YouTubeResolver.Resolved,
        sanitized: String,
        scratch: MutableList<File>,
        report: (Int, Long) -> Unit,
        stage: (Int) -> Unit
    ): Produced? {
        val option = pickVideoOption(resolved.videoOptions, task.ytHeight) ?: return null

        if (!option.needsMux) {
            val output = File(workDir, "$sanitized-$id.mp4").also { scratch.add(it) }
            if (!downloadDirect(option.videoUrl, output, emptyMap(), report)) return null
            return Produced(output, "$sanitized.mp4", "video/mp4", output.length())
        }

        // Split renditions: fetch both tracks, then remux. The byte-level
        // progress covers the two transfers; muxing tops it up to 95.
        val videoPart = File(workDir, "$sanitized-$id.v.mp4").also { scratch.add(it) }
        val audioPart = File(workDir, "$sanitized-$id.a.${option.audioSuffix}")
            .also { scratch.add(it) }

        if (!downloadDirect(option.videoUrl, videoPart, emptyMap()) { p, b ->
                report(scalePercent(p, 0, 75), b)
            }
        ) return null
        if (!downloadDirect(option.audioUrl!!, audioPart, emptyMap()) { p, b ->
                report(scalePercent(p, 75, 90), b)
            }
        ) return null

        stage(92)
        val output = File(workDir, "$sanitized-$id.mp4").also { scratch.add(it) }
        if (!Mp4Muxer.mux(videoPart, audioPart, output)) return null
        return Produced(output, "$sanitized.mp4", "video/mp4", output.length())
    }

    private suspend fun runYouTubeMp3(
        task: Task,
        id: Long,
        resolved: YouTubeResolver.Resolved,
        sanitized: String,
        scratch: MutableList<File>,
        report: (Int, Long) -> Unit,
        stage: (Int) -> Unit
    ): Produced? {
        val source = resolved.audioOptions.firstOrNull() ?: return null
        val audioPart = File(workDir, "$sanitized-$id.src.${source.suffix}")
            .also { scratch.add(it) }

        if (!downloadDirect(source.url, audioPart, emptyMap()) { p, b ->
                report(scalePercent(p, 0, 45), b)
            }
        ) return null

        // Pure-Java LAME: slow enough that the user needs to see it moving.
        val output = File(workDir, "$sanitized-$id.mp3").also { scratch.add(it) }
        val ok = withContext(Dispatchers.Default) {
            val ctx = coroutineContext
            Mp3Transcoder.transcode(
                input = audioPart,
                output = output,
                bitrateKbps = task.mp3Bitrate,
                durationUsHint = resolved.durationSeconds * 1_000_000
            ) { p ->
                // The encode loop never suspends, so this callback is the only
                // place a cancel can break into it.
                ctx.ensureActive()
                stage(scalePercent(p, 45, 95))
            }
        }
        if (!ok) return null
        return Produced(output, "$sanitized.mp3", "audio/mpeg", output.length())
    }

    /** Closest available rendition at or below the target; best if none fits. */
    private fun pickVideoOption(
        options: List<YouTubeResolver.VideoOption>,
        targetHeight: Int
    ): YouTubeResolver.VideoOption? {
        if (options.isEmpty()) return null
        if (targetHeight <= 0) return options.first()
        return options.firstOrNull { it.height <= targetHeight } ?: options.last()
    }

    /** Maps a 0..100 sub-task percent onto the [from, to] slice of the whole. */
    private fun scalePercent(percent: Int, from: Int, to: Int): Int {
        if (percent < 0) return -1
        return from + (percent.coerceIn(0, 100) * (to - from) / 100)
    }

    private fun mimeForExtension(extension: String): String = when (extension.lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "ts" -> "video/mp2t"
        else -> "video/mp4"
    }

    /** Reads the media duration (ms) from a published item; 0 if unknown. */
    private fun probeDurationMs(location: String): Long {
        val mmr = android.media.MediaMetadataRetriever()
        return try {
            MediaStorage.applyDataSource(mmr, this, location)
            mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            runCatching { mmr.release() }
        }
    }

    /**
     * A single file over HTTP. Split into concurrent byte ranges where the
     * server allows it — that is what keeps YouTube's per-response throttle
     * from pinning a long video to playback speed.
     */
    private suspend fun downloadDirect(
        url: String,
        output: File,
        headers: Map<String, String>,
        onProgress: (Int, Long) -> Unit
    ): Boolean = rangedDownloader.download(url, output, headers, onProgress)

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

    // -------------------------------------------------------- notifications

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Video download progress" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE
    )

    /** The ongoing foreground notification summarising how many downloads run. */
    private fun summaryNotification(): Notification {
        val count = synchronized(lock) { active + pending.size }.coerceAtLeast(1)
        val batches = DownloadQueueBus.state.value
        val single = batches.singleOrNull()
        val text = when {
            single != null ->
                "${single.label} • ${single.finished}/${single.total}"
            count == 1 -> "Downloading 1 video…"
            else -> "Downloading $count videos…"
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("NSL Downloader")
            .setContentText(text)
            .setContentIntent(contentIntent())
            .setOnlyAlertOnce(true)
            .setOngoing(true)
        // With one playlist running, the shade can call the whole thing off;
        // with several there is no single obvious target, so the Library does it.
        if (single != null) {
            builder.setProgress(single.total, single.finished, false)
                .addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    getString(R.string.cancel_playlist),
                    cancelBatchIntent(single.id)
                )
        }
        return builder.build()
    }

    /** Stops the download filling row [videoId] straight from the shade. */
    private fun cancelIntent(videoId: Long): PendingIntent = PendingIntent.getService(
        this,
        videoId.toInt(),
        Intent(this, DownloadService::class.java).apply {
            action = ACTION_CANCEL
            putExtra(EXTRA_VIDEO_ID, videoId)
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** Calls off the rest of a playlist straight from the shade. */
    private fun cancelBatchIntent(batchId: Long): PendingIntent = PendingIntent.getService(
        this,
        // Batch ids share the request-code space with video ids; keep them apart.
        (batchId + BATCH_REQUEST_OFFSET).toInt(),
        Intent(this, DownloadService::class.java).apply {
            action = ACTION_CANCEL_BATCH
            putExtra(EXTRA_BATCH_ID, batchId)
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** Per-download progress notification with percent + speed. */
    private fun buildProgressNotification(
        videoId: Long,
        title: String,
        progress: Int,
        speedBps: Long
    ): Notification {
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
            .setContentIntent(contentIntent())
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.cancel_download),
                cancelIntent(videoId)
            )
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun formatSpeed(bps: Long): String {
        val kb = bps / 1024.0
        val mb = kb / 1024.0
        return when {
            mb >= 1 -> String.format(java.util.Locale.US, "%.1f MB/s", mb)
            else -> String.format(java.util.Locale.US, "%.0f KB/s", kb)
        }
    }

    /**
     * A playlist item does not get its own "complete" notification: 200 of them
     * would bury the shade — and the ongoing summary, which is where the whole
     * batch can be cancelled. [showBatchDoneNotification] reports the lot once.
     */
    private fun notifyItemDone(task: Task, id: Long, ok: Boolean) {
        if (task.batchId == 0L) showDoneNotification(id, task.title, ok)
    }

    /** One line for a finished playlist, saying how many of it actually landed. */
    private fun showBatchDoneNotification(batch: DownloadQueueBus.Batch) {
        val text = if (batch.failed > 0) {
            getString(
                R.string.notif_playlist_done_failed, batch.label, batch.downloaded, batch.failed
            )
        } else {
            getString(R.string.notif_playlist_done_ok, batch.label, batch.downloaded)
        }
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(R.string.notif_playlist_done))
            .setContentText(text)
            .setContentIntent(contentIntent())
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(BATCH_DONE_NOTIF_BASE + batch.id.toInt(), n)
    }

    private fun showDoneNotification(id: Long, title: String, ok: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(
                if (ok) android.R.drawable.stat_sys_download_done
                else android.R.drawable.stat_notify_error
            )
            .setContentTitle(if (ok) "Download complete" else "Download failed")
            .setContentText(title)
            .setContentIntent(contentIntent())
            .setAutoCancel(true)
            .build()
        nm.notify(DONE_NOTIF_BASE + id.toInt(), n)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        running.clear()
        cancelledIds.clear()
        batchOfVideo.clear()
        // The queue died with the service, so nothing is left to cancel.
        DownloadQueueBus.clear()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

private const val DEFAULT_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0.0.0 Mobile Safari/537.36"
