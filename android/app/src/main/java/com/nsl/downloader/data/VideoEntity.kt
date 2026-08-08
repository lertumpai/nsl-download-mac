package com.nsl.downloader.data

import androidx.room.Embedded
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
    val mimeType: String = "video/mp4",
    /** How to fetch this entry again if the first attempt did not finish. */
    @Embedded(prefix = "req_") val request: DownloadRequest = DownloadRequest()
)

/**
 * Everything the download service needs to start the transfer that fills a
 * row — kept on the row itself so a failed download can be resumed later, from
 * a fresh process, without the caller that queued it still being around.
 *
 * The stream URLs are deliberately not here: for YouTube they are signed and
 * short-lived, so a retry resolves them again from [VideoEntity.sourceUrl].
 */
data class DownloadRequest(
    /** Matches DownloadService.Kind. */
    val kind: String = "GENERIC",
    val headers: Map<String, String> = emptyMap(),
    /** Matches DownloadService.YtFormat. */
    val ytFormat: String = "MP4",
    val ytHeight: Int = 0,
    val mp3Bitrate: Int = 192,
    val userAgent: String = "",
    val folderName: String? = null
) {
    /**
     * False for rows written before downloads recorded how they were made.
     * Guessing at those would be worse than not offering to resume them: a
     * YouTube row retried as a plain file download saves the web page.
     */
    val isUsable: Boolean get() = kind.isNotBlank()
}

enum class DownloadStatus { PENDING, DOWNLOADING, COMPLETED, FAILED }
