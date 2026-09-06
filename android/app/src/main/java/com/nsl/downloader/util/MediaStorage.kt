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
import java.util.Collections

/**
 * Publishes finished downloads into a **real, user-visible** directory tree:
 *
 *     Download/<root>/[<library folder>]/<file>   (root defaults to "NSL Downloader")
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
 *
 * Every directory the app writes into also gets a `.nomedia` marker ([ensureHidden]),
 * which makes the platform scanner classify everything inside it as non-media.
 * The files stay where the user can find them in a file manager, but Gallery
 * apps (Samsung Gallery, Google Photos, …) no longer list them.
 */
object MediaStorage {

    const val DEFAULT_ROOT = "NSL Downloader"

    private const val NO_MEDIA = ".nomedia"

    /**
     * Top-level folder under `Download/`. Settings can change it; already
     * published files keep the location stored on their library row, so only
     * new downloads follow the new setting.
     */
    @Volatile
    var root: String = DEFAULT_ROOT
        set(value) {
            field = value.takeIf { it.isNotBlank() } ?: DEFAULT_ROOT
            hidden.clear()
        }

    /** Relative paths already known to carry a `.nomedia`; see [ensureHidden]. */
    private val hidden = Collections.synchronizedSet(mutableSetOf<String>())

    private val useMediaStore: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** Strips path separators and other characters MediaStore rejects. */
    fun sanitizeName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_").trim().take(80)
            .ifBlank { "video_${System.currentTimeMillis()}" }

    /** The length limit must not remove .mp4/.mp3 from a long movie title. */
    fun sanitizeFileName(name: String): String {
        val extension = name.substringAfterLast('.', "")
            .takeIf { it.length in 2..5 && it.all(Char::isLetterOrDigit) }
            ?: return sanitizeName(name)
        val suffix = ".$extension"
        return sanitizeName(name.substringBeforeLast('.')).take(80 - suffix.length) + suffix
    }

    /** e.g. `Download/NSL Downloader/Music videos` */
    fun relativeDir(folder: String?): String {
        val base = "${Environment.DIRECTORY_DOWNLOADS}/$root"
        return if (folder.isNullOrBlank()) base else "$base/${sanitizeName(folder)}"
    }

    /** The same location as a [File], for pre-Q and for folder bookkeeping. */
    fun legacyDir(folder: String?): File {
        val base = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), root
        )
        return if (folder.isNullOrBlank()) base else File(base, sanitizeName(folder))
    }

    /**
     * Best-effort creation of the real directory. On API 29+ an empty directory
     * has no MediaStore row, so this only matters for file managers; the
     * directory is created for real by the first [publish] into it either way.
     */
    fun ensureFolder(context: Context, folder: String?) {
        runCatching { legacyDir(folder).mkdirs() }
        ensureHidden(context, folder)
    }

    /**
     * Drops a `.nomedia` into the folder so Gallery apps skip everything in it.
     *
     * Must run *before* the first file is published there: the platform decides
     * a file's media type while scanning it, and only re-checks on a rescan
     * (see [hideExistingDownloads] for files that predate the marker).
     *
     * Cheap to call repeatedly — the first success per directory is remembered.
     */
    fun ensureHidden(context: Context, folder: String?) {
        ensureHiddenPath(context, relativeDir(folder), legacyDir(folder))
    }

    private fun ensureHiddenPath(context: Context, relativePath: String, dir: File) {
        if (!hidden.add(relativePath)) return
        val ok = if (useMediaStore) createNoMediaRow(context, relativePath, dir)
        else createNoMediaFile(dir)
        // Leave a failure out of the cache so the next download retries.
        if (!ok) hidden.remove(relativePath)
    }

    private fun createNoMediaFile(dir: File): Boolean = runCatching {
        dir.mkdirs()
        val marker = File(dir, NO_MEDIA)
        marker.exists() || marker.createNewFile()
    }.getOrDefault(false)

    /**
     * The API 29+ path: shared storage is only writable through MediaStore, so
     * the marker has to be inserted as a row like any other published file.
     */
    private fun createNoMediaRow(context: Context, relativePath: String, dir: File): Boolean {
        // A marker left by an older (pre-scoped-storage) install still counts.
        if (runCatching { File(dir, NO_MEDIA).exists() }.getOrDefault(false)) return true

        // The Downloads collection is where the app's own files live, but a
        // dot-prefixed, non-media name is exactly the kind of insert some OEM
        // providers refuse; Files accepts the same row on those devices.
        val collections = listOf(
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        )
        return collections.any { insertNoMediaInto(context, it, relativePath) }
    }

    private fun insertNoMediaInto(context: Context, collection: Uri, relativePath: String): Boolean =
        runCatching {
            val resolver = context.contentResolver
            if (noMediaRowExists(context, collection, relativePath)) return true

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, NO_MEDIA)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            }
            val uri = resolver.insert(collection, values) ?: return false

            // MediaProvider is free to rewrite a display name it dislikes — some
            // builds append an extension for the MIME type. A ".nomedia.bin"
            // hides nothing and would just litter the user's folder, so drop it.
            if (displayNameOf(context, uri) != NO_MEDIA) {
                resolver.delete(uri, null, null)
                return false
            }
            // Materialise the (empty) file; the row alone is not what the
            // scanner looks for.
            resolver.openOutputStream(uri)?.close()
            true
        }.getOrDefault(false)

    private fun noMediaRowExists(context: Context, collection: Uri, relativePath: String): Boolean =
        runCatching {
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND " +
                    "${MediaStore.MediaColumns.RELATIVE_PATH} IN (?,?)",
                arrayOf(NO_MEDIA, "$relativePath/", relativePath),
                null
            )?.use { it.moveToFirst() } ?: false
        }.getOrDefault(false)

    private fun displayNameOf(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null
        )?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    /**
     * Retro-fits the marker onto downloads that were published before this app
     * version, then asks the platform to rescan them so their existing
     * "this is a video" classification is replaced by "not media".
     *
     * Safe to call more than once, but it walks every published file, so
     * callers should gate it (see `Prefs.galleryHiddenRoot`). Blocking I/O.
     */
    fun hideExistingDownloads(context: Context) {
        ensureHidden(context, null)
        if (useMediaStore) rescanViaMediaStore(context) else rescanViaFiles(context)
    }

    private fun rescanViaMediaStore(context: Context) {
        runCatching {
            val base = relativeDir(null)
            val paths = mutableListOf<String>()
            val dirs = mutableSetOf<String>()
            context.contentResolver.query(
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                arrayOf(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.DATA
                ),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf("$base/%"),
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val relative = cursor.getString(0)?.trimEnd('/') ?: continue
                    dirs += relative
                    if (cursor.getString(1) == NO_MEDIA) continue
                    cursor.getString(2)?.let { paths += it }
                }
            }
            // Sub-folders first: a file is only reclassified if its own
            // directory is already hidden when the scanner reaches it.
            dirs.forEach { relative ->
                ensureHiddenPath(context, relative, legacyDirOf(relative))
            }
            if (paths.isNotEmpty()) {
                MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null)
            }
        }
    }

    private fun rescanViaFiles(context: Context) {
        runCatching {
            val paths = mutableListOf<String>()
            fun walk(dir: File) {
                createNoMediaFile(dir)
                dir.listFiles()?.forEach { child ->
                    if (child.isDirectory) walk(child)
                    else if (child.name != NO_MEDIA) paths += child.absolutePath
                }
            }
            val base = legacyDir(null)
            if (base.isDirectory) walk(base)
            if (paths.isNotEmpty()) {
                MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null)
            }
        }
    }

    /** `Download/NSL Downloader/x` -> the matching [File] under external storage. */
    private fun legacyDirOf(relativePath: String): File =
        File(Environment.getExternalStorageDirectory(), relativePath)

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
        // Before the write, never after: the scanner decides the file's media
        // type as it lands, and only a hidden directory keeps it out of Gallery.
        ensureHidden(context, folder)
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
            put(MediaStore.MediaColumns.DISPLAY_NAME, sanitizeFileName(displayName))
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
        val target = uniqueFile(dir, sanitizeFileName(displayName))
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
        ensureHidden(context, toFolder)
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
        if (isContentUri(location) || location.startsWith("file:")) Uri.parse(location)
        else Uri.fromFile(File(location))

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
