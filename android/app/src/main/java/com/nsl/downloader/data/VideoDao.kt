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

    /** Used when a folder is deleted: its videos fall back to the library root. */
    @Query("UPDATE videos SET folderId = NULL WHERE folderId = :folderId")
    suspend fun clearFolder(folderId: Long)

    @Query("SELECT * FROM videos WHERE folderId = :folderId")
    suspend fun getInFolder(folderId: Long): List<VideoEntity>
}
