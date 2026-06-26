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
    val status: DownloadStatus = DownloadStatus.PENDING
)

enum class DownloadStatus { PENDING, DOWNLOADING, COMPLETED, FAILED }
