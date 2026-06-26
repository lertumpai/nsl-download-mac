package com.nsl.downloader.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nsl.downloader.data.AppDatabase
import com.nsl.downloader.data.VideoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = AppDatabase.getInstance(app).videoDao()

    val videos = dao.observeAll().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
    )

    /** Remove a single video and ALL its files (video + thumbnail). */
    fun removeVideo(video: VideoEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            deleteFiles(video)
            dao.deleteById(video.id)
        }
    }

    /** Remove every video and wipe the downloads directory entirely. */
    fun removeAll() {
        viewModelScope.launch(Dispatchers.IO) {
            val all = dao.observeAllOnce()
            all.forEach { deleteFiles(it) }
            dao.deleteAll()
            // Nuke the downloads dir in case of orphans
            File(getApplication<Application>().filesDir, "downloads").deleteRecursively()
        }
    }

    private fun deleteFiles(video: VideoEntity) {
        runCatching {
            if (video.localPath.isNotBlank()) File(video.localPath).delete()
            video.thumbnailPath?.let { File(it).delete() }
        }
    }
}
