package com.nsl.downloader.service

import android.content.Context
import java.io.File

/**
 * Where a download's half-finished files live between attempts.
 *
 * Each library row gets its own directory, named after the row id, and the
 * files inside it are named after the part they hold rather than the video —
 * so a retry of row 42 finds exactly what the failed attempt left behind and
 * carries on from there.
 *
 * This is app-private storage rather than the cache: the system is free to
 * evict a cache directory whenever it wants space, and a half-downloaded film
 * is precisely the sort of large, cold file it would pick first.
 */
internal object DownloadPartials {

    private const val DIR = "downloading"

    fun root(context: Context): File =
        File(context.filesDir, DIR).also { it.mkdirs() }

    fun forVideo(context: Context, videoId: Long): File =
        File(root(context), videoId.toString()).also { it.mkdirs() }

    /** Throws away everything held for [videoId]; nothing can be resumed after. */
    fun discard(context: Context, videoId: Long) {
        runCatching { File(root(context), videoId.toString()).deleteRecursively() }
    }

    /**
     * Drops partials whose library row is gone. A row can disappear without the
     * service ever hearing about it — the process is killed mid-transfer, the
     * database is cleared — and the bytes would otherwise sit there for good.
     */
    fun sweep(context: Context, keep: Set<Long>) {
        runCatching {
            root(context).listFiles()?.forEach { entry ->
                val id = entry.name.toLongOrNull()
                if (id == null || id !in keep) entry.deleteRecursively()
            }
        }
    }
}
