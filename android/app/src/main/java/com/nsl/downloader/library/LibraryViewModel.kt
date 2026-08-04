package com.nsl.downloader.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nsl.downloader.data.AppDatabase
import com.nsl.downloader.data.DownloadProgressBus
import com.nsl.downloader.data.DownloadStatus
import com.nsl.downloader.data.FolderEntity
import com.nsl.downloader.data.VideoEntity
import com.nsl.downloader.service.DownloadService
import com.nsl.downloader.util.MediaStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/** A library entry plus its live download progress (null when not downloading). */
data class VideoRow(val video: VideoEntity, val progress: DownloadProgressBus.Info?)

class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = AppDatabase.getInstance(app).videoDao()
    private val folderDao = AppDatabase.getInstance(app).folderDao()

    /** Currently browsed folder; null means "All". */
    val selectedFolderId = MutableStateFlow<Long?>(null)

    val folders = folderDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * The list merged with live per-item progress from the download service and
     * narrowed to the selected folder.
     */
    val rows = combine(
        dao.observeAll(), DownloadProgressBus.state, selectedFolderId
    ) { list, progress, folderId ->
        list.inLibraryFolder(folderId)
            .map { VideoRow(it, progress[it.id]) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun selectFolder(id: Long?) {
        selectedFolderId.value = id
    }

    fun folderName(id: Long?): String? =
        id?.let { folders.value.firstOrNull { f -> f.id == it }?.name }

    /**
     * Creates a library folder and its real directory. Returns false when the
     * name is already taken.
     */
    fun createFolder(name: String, onResult: (Boolean) -> Unit) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            onResult(false)
            return
        }
        viewModelScope.launch {
            val existing = folderDao.getByName(trimmed)
            if (existing != null) {
                onResult(false)
                return@launch
            }
            val id = folderDao.insert(FolderEntity(name = trimmed))
            withIo { MediaStorage.ensureFolder(getApplication(), trimmed) }
            selectedFolderId.value = id
            onResult(true)
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
    fun moveVideo(video: VideoEntity, targetFolderId: Long?) {
        viewModelScope.launch {
            val targetName = targetFolderId?.let { folderDao.getById(it)?.name }
            val moved = withIo {
                if (targetFolderId != null) MediaStorage.ensureFolder(getApplication(), targetName)
                MediaStorage.move(
                    getApplication(), video.localPath, displayNameOf(video),
                    video.mimeType, targetName
                )
            }
            dao.update(video.copy(localPath = moved, folderId = targetFolderId))
        }
    }

    /** Remove a single video and ALL its files (video + thumbnail). */
    fun removeVideo(video: VideoEntity) {
        stopIfDownloading(video)
        viewModelScope.launch(Dispatchers.IO) {
            deleteFiles(video)
            dao.deleteById(video.id)
        }
    }

    /** Remove every video in the current root/folder view and wipe their files. */
    fun removeAll() {
        val folderId = selectedFolderId.value
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
