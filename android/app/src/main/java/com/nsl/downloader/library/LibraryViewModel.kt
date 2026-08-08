package com.nsl.downloader.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nsl.downloader.data.AppDatabase
import com.nsl.downloader.data.DownloadProgressBus
import com.nsl.downloader.data.DownloadStatus
import com.nsl.downloader.data.FolderEntity
import com.nsl.downloader.data.VideoEntity
import com.nsl.downloader.service.DownloadPartials
import com.nsl.downloader.service.DownloadService
import com.nsl.downloader.util.MediaStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/**
 * A library entry plus its live download progress (null when not downloading)
 * and its part in the current multi-select, if one is running.
 */
data class VideoRow(
    val video: VideoEntity,
    val progress: DownloadProgressBus.Info?,
    val selected: Boolean = false,
    val selectionActive: Boolean = false
)

class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = AppDatabase.getInstance(app).videoDao()
    private val folderDao = AppDatabase.getInstance(app).folderDao()

    /** Currently browsed folder; null means "All". */
    val selectedFolderId = MutableStateFlow<Long?>(null)

    /**
     * Rows ticked for a bulk action. Empty means no multi-select is running —
     * the state is the mode, so there is nothing else to keep in step with it.
     */
    val selectedIds = MutableStateFlow<Set<Long>>(emptySet())

    val folders = folderDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * The list merged with live per-item progress from the download service and
     * narrowed to the selected folder.
     */
    val rows = combine(
        dao.observeAll(), DownloadProgressBus.state, selectedFolderId, selectedIds
    ) { list, progress, folderId, picked ->
        list.inLibraryFolder(folderId)
            .map { VideoRow(it, progress[it.id], it.id in picked, picked.isNotEmpty()) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun selectFolder(id: Long?) {
        // A pick made in one folder would act on rows the user can no longer
        // see, so switching folders ends it.
        clearSelection()
        selectedFolderId.value = id
    }

    // ------------------------------------------------------- multi-select

    /** The picked rows, in the order they appear in the list. */
    val selectedVideos: List<VideoEntity>
        get() = rows.value.filter { it.selected }.map { it.video }

    /** Ticks or unticks [video]; the last untick ends the multi-select. */
    fun toggleSelection(video: VideoEntity) {
        if (!video.canMove) return
        val current = selectedIds.value
        selectedIds.value =
            if (video.id in current) current - video.id else current + video.id
    }

    fun selectAll() {
        selectedIds.value = rows.value.filter { it.video.canMove }.map { it.video.id }.toSet()
    }

    fun clearSelection() {
        if (selectedIds.value.isNotEmpty()) selectedIds.value = emptySet()
    }

    /** How many rows in the current view a multi-select could take. */
    fun selectableCount(): Int = rows.value.count { it.video.canMove }

    fun folderName(id: Long?): String? =
        id?.let { folders.value.firstOrNull { f -> f.id == it }?.name }

    /**
     * Creates a library folder and its real directory, reporting its id — or
     * null when the name is blank or already taken. [select] browses to the new
     * folder; a caller that only needed somewhere to put videos (a move, say)
     * passes false so the list it was working on stays where it is.
     */
    fun createFolder(name: String, select: Boolean = true, onResult: (Long?) -> Unit) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            onResult(null)
            return
        }
        viewModelScope.launch {
            val existing = folderDao.getByName(trimmed)
            if (existing != null) {
                onResult(null)
                return@launch
            }
            val id = folderDao.insert(FolderEntity(name = trimmed))
            withIo { MediaStorage.ensureFolder(getApplication(), trimmed) }
            if (select) selectFolder(id)
            onResult(id)
        }
    }

    /** Drops the folder; its videos fall back to the library root, files intact. */
    fun deleteFolder(folder: FolderEntity) {
        viewModelScope.launch {
            val members = dao.getInFolder(folder.id)
            withIo {
                members.forEach { video ->
                    val moved = MediaStorage.move(
                        getApplication(), video.localPath, displayNameOf(video),
                        video.mimeType, null
                    )
                    if (moved != video.localPath) {
                        dao.update(video.copy(localPath = moved, folderId = null))
                    }
                }
            }
            dao.clearFolder(folder.id)
            folderDao.deleteById(folder.id)
            MediaStorage.removeFolderIfEmpty(folder.name)
            if (selectedFolderId.value == folder.id) selectedFolderId.value = null
        }
    }

    /** Moves both the database row and the file on disk. */
    fun moveVideo(video: VideoEntity, targetFolderId: Long?) =
        moveVideos(listOf(video), targetFolderId) {}

    /**
     * Moves a whole selection in one pass: the target folder is created once,
     * and a file that throws on the way (an OEM provider refusing the
     * relocation, say) leaves its own path alone instead of stopping the rest.
     * [onDone] runs on the main thread once the last row is written.
     */
    fun moveVideos(
        videos: List<VideoEntity>,
        targetFolderId: Long?,
        onDone: (moved: Int) -> Unit
    ) {
        if (videos.isEmpty()) {
            onDone(0)
            return
        }
        viewModelScope.launch {
            val targetName = targetFolderId?.let { folderDao.getById(it)?.name }
            val relocated = withIo {
                if (targetFolderId != null) MediaStorage.ensureFolder(getApplication(), targetName)
                videos.map { video ->
                    // On API 29+ a successful move keeps the same content URI,
                    // so an unchanged path is not a failure to report.
                    val path = runCatching {
                        MediaStorage.move(
                            getApplication(), video.localPath, displayNameOf(video),
                            video.mimeType, targetName
                        )
                    }.getOrDefault(video.localPath)
                    video.copy(localPath = path, folderId = targetFolderId)
                }
            }
            relocated.forEach { dao.update(it) }
            clearSelection()
            onDone(relocated.size)
        }
    }

    /**
     * Picks a failed download back up. The service still has what the failed
     * attempt fetched, so this continues it instead of starting over.
     */
    fun resumeVideo(video: VideoEntity) {
        if (!video.canResume) return
        DownloadService.retry(getApplication(), video.id)
    }

    /** Remove a single video and ALL its files (video + thumbnail). */
    fun removeVideo(video: VideoEntity) {
        stopIfDownloading(video)
        selectedIds.value = selectedIds.value - video.id
        viewModelScope.launch(Dispatchers.IO) {
            deleteFiles(video)
            dao.deleteById(video.id)
        }
    }

    /** Remove every video in the current root/folder view and wipe their files. */
    fun removeAll() {
        val folderId = selectedFolderId.value
        clearSelection()
        viewModelScope.launch(Dispatchers.IO) {
            val all = dao.observeAllOnce().inLibraryFolder(folderId)
            all.forEach {
                stopIfDownloading(it)
                deleteFiles(it)
                dao.deleteById(it.id)
            }
            if (folderId != null) {
                folderName(folderId)?.let { MediaStorage.removeFolderIfEmpty(it) }
            }
        }
    }

    /**
     * Dropping the row is not enough for an in-flight download: the service
     * would keep transferring, keep its notification up and still publish the
     * finished file into a library that no longer expects it.
     */
    private fun stopIfDownloading(video: VideoEntity) {
        if (video.status == DownloadStatus.DOWNLOADING || video.status == DownloadStatus.PENDING) {
            DownloadService.cancel(getApplication(), video.id)
        }
    }

    private fun deleteFiles(video: VideoEntity) {
        runCatching {
            if (video.localPath.isNotBlank()) {
                MediaStorage.delete(getApplication(), video.localPath)
            }
            video.thumbnailPath?.let { File(it).delete() }
        }
        // Removing the row is also the user saying they no longer want what a
        // failed attempt left half-finished.
        DownloadPartials.discard(getApplication(), video.id)
    }

    private fun displayNameOf(video: VideoEntity): String {
        val fromPath = video.localPath.substringAfterLast('/').substringBefore('?')
        return if (fromPath.contains('.')) fromPath
        else "${MediaStorage.sanitizeName(video.title)}.${extensionFor(video.mimeType)}"
    }

    private fun extensionFor(mimeType: String): String = when (mimeType) {
        "audio/mpeg" -> "mp3"
        "audio/mp4" -> "m4a"
        "video/webm" -> "webm"
        "video/mp2t" -> "ts"
        else -> "mp4"
    }

    private suspend fun <T> withIo(block: suspend () -> T): T =
        kotlinx.coroutines.withContext(Dispatchers.IO) { block() }
}
