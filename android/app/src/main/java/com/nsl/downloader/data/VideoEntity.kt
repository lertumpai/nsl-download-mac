package com.nsl.downloader.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "videos")
data class VideoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val sourceUrl: String,
    val localPath: String,
    val thumbnailPath: String? = null,
    val durationMs: Long = 0,
    val fileSizeBytes: Long = 0,
    val downloadedAt: Long = System.currentTimeMillis(),
    val status: DownloadStatus = DownloadStatus.PENDING,
    val lastPositionMs: Long = 0,
    /** Library folder this entry lives in; null = the library root. */
    val folderId: Long? = null,
    /** MIME type of the produced file, e.g. video/mp4 or audio/mpeg. */
    val mimeType: String = "video/mp4"
)

enum class DownloadStatus { PENDING, DOWNLOADING, COMPLETED, FAILED }
