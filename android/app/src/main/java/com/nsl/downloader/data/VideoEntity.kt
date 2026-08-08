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
) {
    /**
     * A failed download can be picked back up as long as we still know where it
     * came from — whatever the attempt fetched is still on disk, so resuming
     * only transfers the part that is missing. Rows written before downloads
     * recorded how they were made carry a blank [DownloadRequest.kind]; the
     * service reads that back off [sourceUrl] instead of refusing them.
     */
    val canResume: Boolean
        get() = status == DownloadStatus.FAILED && sourceUrl.isNotBlank()
}

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
)

enum class DownloadStatus { PENDING, DOWNLOADING, COMPLETED, FAILED }
