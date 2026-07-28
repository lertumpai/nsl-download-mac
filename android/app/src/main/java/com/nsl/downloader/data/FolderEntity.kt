package com.nsl.downloader.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A user-created library folder. Each one is backed by a real subdirectory of
 * `Movies/NSL Downloader/` on shared storage, so it also shows up in the
 * device's Files app and gallery.
 */
@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)
