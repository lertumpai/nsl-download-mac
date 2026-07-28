package com.nsl.downloader.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File

/**
 * Publishes finished downloads into a **real, user-visible** directory tree:
 *
 *     Download/NSL Downloader/[<library folder>]/<file>
 *
 * The `Download` collection is used (rather than `Movies`/`Music`) because it is
 * the only MediaStore collection that accepts arbitrary MIME types, so MP4 and
 * MP3 from the same playlist land side by side in one folder the user can open
 * in their Files app.
 *
 * Downloaders keep writing to private scratch files; [publish] is the single
 * hand-off point into shared storage. On API 29+ that means a MediaStore insert
 * plus a stream copy; below that, a plain file copy and a media-scanner ping.
 *
 * Every stored location is round-tripped as a *string*: a `content://` URI on
 * API 29+, an absolute path below that. Use [toUri], [openStream], [delete] and
 * [applyDataSource] instead of assuming either shape.
 */
object MediaStorage {

    const val ROOT = "NSL Downloader"

    private val useMediaStore: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** Strips path separators and other characters MediaStore rejects. */
    fun sanitizeName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_").trim().take(80)
            .ifBlank { "video_${System.currentTimeMillis()}" }

    /** e.g. `Download/NSL Downloader/Music videos` */
    fun relativeDir(folder: String?): String {
        val base = "${Environment.DIRECTORY_DOWNLOADS}/$ROOT"
        return if (folder.isNullOrBlank()) base else "$base/${sanitizeName(folder)}"
    }

    /** The same location as a [File], for pre-Q and for folder bookkeeping. */
    fun legacyDir(folder: String?): File {
        val base = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), ROOT
        )
        return if (folder.isNullOrBlank()) base else File(base, sanitizeName(folder))
    }

    /**
     * Best-effort creation of the real directory. On API 29+ an empty directory
     * has no MediaStore row, so this only matters for file managers; the
     * directory is created for real by the first [publish] into it either way.
     */
    fun ensureFolder(folder: String?) {
        runCatching { legacyDir(folder).mkdirs() }
    }

    /** Removes the real directory if the app emptied it. Never recurses. */
    fun removeFolderIfEmpty(folder: String) {
        runCatching {
            val dir = legacyDir(folder)
            if (dir.isDirectory && dir.list()?.isEmpty() != false) dir.delete()
        }
    }

    /**
     * Moves [source] into shared storage and returns the stored location string,
     * or null if publishing failed (the caller should keep [source] in that case).
     */
    fun publish(
        context: Context,
        source: File,
        displayName: String,
        mimeType: String,
        folder: String?
    ): String? {
        if (!source.exists() || source.length() == 0L) return null
        return if (useMediaStore) {
            publishViaMediaStore(context, source, displayName, mimeType, folder)
        } else {
            publishViaFile(context, source, displayName, mimeType, folder)
        }
    }

    private fun publishViaMediaStore(
        context: Context,
        source: File,
        displayName: String,
        mimeType: String,
        folder: String?
    ): String? = runCatching {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, sanitizeName(displayName))
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir(folder))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: return@runCatching null
        resolver.openOutputStream(uri)?.use { out ->
            source.inputStream().use { it.copyTo(out, 1 shl 16) }
        } ?: run {
            resolver.delete(uri, null, null)
            return@runCatching null
        }
        resolver.update(
            uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null
        )
        source.delete()
        uri.toString()
    }.getOrNull()

    private fun publishViaFile(
        context: Context,
        source: File,
        displayName: String,
        mimeType: String,
        folder: String?
    ): String? = runCatching {
        val dir = legacyDir(folder).also { it.mkdirs() }
        val target = uniqueFile(dir, sanitizeName(displayName))
        source.inputStream().use { input ->
            target.outputStream().use { input.copyTo(it, 1 shl 16) }
        }
        source.delete()
        MediaScannerConnection.scanFile(
            context, arrayOf(target.absolutePath), arrayOf(mimeType), null
        )
        target.absolutePath
    }.getOrNull()

    private fun uniqueFile(dir: File, name: String): File {
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var candidate = File(dir, name)
        var n = 1
        while (candidate.exists()) {
            val suffixed = if (ext.isEmpty()) "$base ($n)" else "$base ($n).$ext"
            candidate = File(dir, suffixed)
            n++
        }
        return candidate
    }

    /**
     * Relocates an already-published item into [toFolder]. Returns the new
     * location string, or the original one if the move could not be performed.
     */
    fun move(
        context: Context,
        location: String,
        displayName: String,
        mimeType: String,
        toFolder: String?
    ): String {
        if (!useMediaStore || !isContentUri(location)) return moveLegacy(context, location, toFolder)
        val resolver = context.contentResolver
        val uri = Uri.parse(location)
        val relocated = runCatching {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir(toFolder))
            }
            resolver.update(uri, values, null, null) > 0
        }.getOrDefault(false)
        if (relocated) return location

        // Some OEM providers reject in-place RELATIVE_PATH updates; fall back to
        // a copy through a scratch file and drop the original.
        return runCatching {
            val scratch = File.createTempFile("move", null, context.cacheDir)
            resolver.openInputStream(uri)?.use { input ->
                scratch.outputStream().use { input.copyTo(it, 1 shl 16) }
            } ?: return location
            val published = publish(context, scratch, displayName, mimeType, toFolder)
            if (published != null) {
                resolver.delete(uri, null, null)
                published
            } else {
                scratch.delete()
                location
            }
        }.getOrDefault(location)
    }

    private fun moveLegacy(context: Context, location: String, toFolder: String?): String {
        val src = File(location)
        if (!src.exists()) return location
        return runCatching {
            val dir = legacyDir(toFolder).also { it.mkdirs() }
            val target = uniqueFile(dir, src.name)
            if (src.renameTo(target)) {
                MediaScannerConnection.scanFile(
                    context, arrayOf(src.absolutePath, target.absolutePath), null, null
                )
                target.absolutePath
            } else {
                location
            }
        }.getOrDefault(location)
    }

    fun isContentUri(location: String): Boolean = location.startsWith("content://")

    fun toUri(location: String): Uri =
        if (isContentUri(location)) Uri.parse(location) else Uri.fromFile(File(location))

    fun exists(context: Context, location: String): Boolean = when {
        location.isBlank() -> false
        isContentUri(location) -> runCatching {
            context.contentResolver.openAssetFileDescriptor(Uri.parse(location), "r")
                ?.use { true } ?: false
        }.getOrDefault(false)
        else -> File(location).exists()
    }

    fun delete(context: Context, location: String): Boolean = runCatching {
        if (location.isBlank()) return false
        if (isContentUri(location)) {
            context.contentResolver.delete(Uri.parse(location), null, null) > 0
        } else {
            val file = File(location)
            val deleted = file.delete()
            if (deleted) {
                MediaScannerConnection.scanFile(context, arrayOf(location), null, null)
            }
            deleted
        }
    }.getOrDefault(false)

    /** [MediaMetadataRetriever.setDataSource] for either location shape. */
    fun applyDataSource(mmr: MediaMetadataRetriever, context: Context, location: String) {
        if (isContentUri(location)) mmr.setDataSource(context, Uri.parse(location))
        else mmr.setDataSource(location)
    }

    /**
     * Intents that open the real folder in the device's file manager, most
     * likely to succeed first. Callers should try them in order.
     */
    fun openFolderIntents(folder: String?): List<Intent> {
        val relative = relativeDir(folder)
        val docId = "primary:$relative"
        val intents = mutableListOf<Intent>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Opens DocumentsUI (or the OEM Files app) directly at the folder.
            val treeUri = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, docId)
            intents += Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(treeUri, DocumentsContract.Document.MIME_TYPE_DIR)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }

        // A file:// directory URI is what most OEM managers want, but handing
        // one to another app throws FileUriExposedException on API 24+, so it is
        // deliberately not offered.

        // Last resort: the system Downloads screen.
        intents += Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)

        return intents
    }

    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
}
