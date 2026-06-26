package com.nsl.downloader.data

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun fromStatus(value: DownloadStatus): String = value.name

    @TypeConverter
    fun toStatus(value: String): DownloadStatus = DownloadStatus.valueOf(value)
}
