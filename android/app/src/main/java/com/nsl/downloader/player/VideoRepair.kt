package com.nsl.downloader.player

import android.content.Context
import com.nsl.downloader.data.AppDatabase
import com.nsl.downloader.service.Mp4Muxer
import com.nsl.downloader.util.MediaStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Repairs old ADTS-in-MP4 downloads into a new copy; the original is never overwritten. */
internal object VideoRepair {
    /** Strict probe used by batch repair, where an unreadable MP4 is a failure. */
    suspend fun needsRepair(context: Context, location: String): Boolean =
        withContext(Dispatchers.IO) {
            Mp4Muxer.needsAudioRepair(context, MediaStorage.toUri(location))
        }

    suspend fun prepare(context: Context, location: String, videoId: Long,
        onRepairProgress: (Int) -> Unit): String = withContext(Dispatchers.IO) {
        val uri = MediaStorage.toUri(location)
        // ExoPlayer supports formats that the platform extractor does not.
        // Only intervene when this specific old MP4 defect is positively found.
        if (!runCatching { Mp4Muxer.needsAudioRepair(context, uri) }.getOrDefault(false)) {
            return@withContext location
        }
        onRepairProgress(0)
        val scratch = File.createTempFile("audio-repair-", ".mp4", context.cacheDir)
        val coroutine = currentCoroutineContext()
        try {
            if (!Mp4Muxer.repair(context, uri, scratch,
                { onRepairProgress(it * 95 / 100) }, { coroutine.ensureActive() })) {
                throw IOException("Could not repair the movie audio")
            }
            coroutine.ensureActive()
            val dao = AppDatabase.getInstance(context).videoDao()
            val row = if (videoId >= 0) dao.getById(videoId) else null
            if (row == null) {
                // A caller opening a file without a Library row can play this
                // private repaired copy. It can be discarded with the app cache.
                return@withContext scratch.absolutePath
            }
            val bytes = scratch.length()
            onRepairProgress(96)
            withContext(NonCancellable) {
                val repaired = MediaStorage.publish(context, scratch,
                    "${MediaStorage.sanitizeName(row.title).take(60)} (repaired).mp4",
                    "video/mp4", row.request.folderName) ?: throw IOException("Could not save repaired video")
                try {
                    dao.update(row.copy(localPath = repaired, fileSizeBytes = bytes, mimeType = "video/mp4"))
                } catch (e: Exception) {
                    MediaStorage.delete(context, repaired)
                    throw e
                }
                onRepairProgress(100)
                repaired
            }
        } catch (e: Exception) {
            scratch.delete()
            throw e
        }
    }
}
