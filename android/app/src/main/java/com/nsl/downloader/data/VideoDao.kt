package com.nsl.downloader.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoDao {
    @Query("SELECT * FROM videos ORDER BY downloadedAt DESC")
    fun observeAll(): Flow<List<VideoEntity>>

    @Query("SELECT * FROM videos ORDER BY downloadedAt DESC")
    suspend fun observeAllOnce(): List<VideoEntity>

    @Query("SELECT * FROM videos WHERE id = :id")
    suspend fun getById(id: Long): VideoEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(video: VideoEntity): Long

    @Update
    suspend fun update(video: VideoEntity)

    @Delete
    suspend fun delete(video: VideoEntity)

    @Query("DELETE FROM videos WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM videos")
    suspend fun deleteAll()

    @Query("SELECT * FROM videos WHERE status = :status")
    suspend fun getByStatus(status: DownloadStatus): List<VideoEntity>

    /**
     * Marks downloads that were still running when the app last went away as
     * paused, so they show up as something the user can resume. [running] holds
     * the ids the download service owns, including its queue — an app process
     * that survived its service must not have its live rows retired.
     */
    @Query(
        "UPDATE videos SET status = 'PAUSED' " +
            "WHERE status IN ('DOWNLOADING', 'PENDING') AND id NOT IN (:running)"
    )
    suspend fun pauseInterrupted(running: List<Long>)

    /** Conditional updates cannot resurrect a deleted row or undo completion. */
    @Query("UPDATE videos SET status = 'PAUSED' WHERE id = :id AND status IN ('DOWNLOADING', 'PENDING')")
    suspend fun pause(id: Long): Int

    @Query("UPDATE videos SET status = 'PENDING' WHERE id = :id AND status IN ('PAUSED', 'FAILED')")
    suspend fun queueResume(id: Long): Int

    @Query("UPDATE videos SET status = 'DOWNLOADING' WHERE id = :id AND status = 'PENDING'")
    suspend fun startDownload(id: Long): Int

    @Query("UPDATE videos SET status = 'FAILED' WHERE id = :id AND status = 'DOWNLOADING'")
    suspend fun failActive(id: Long): Int

    /** Used when a folder is deleted: its videos fall back to the library root. */
    @Query("UPDATE videos SET folderId = NULL WHERE folderId = :folderId")
    suspend fun clearFolder(folderId: Long)

    @Query("SELECT * FROM videos WHERE folderId = :folderId")
    suspend fun getInFolder(folderId: Long): List<VideoEntity>
}
