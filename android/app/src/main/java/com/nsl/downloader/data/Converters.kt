package com.nsl.downloader.data

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun fromStatus(value: DownloadStatus): String = value.name

    @TypeConverter
    fun toStatus(value: String): DownloadStatus = DownloadStatus.valueOf(value)

    /**
     * Request headers as one column. Tab and newline are the separators because
     * neither can appear in an HTTP header value — a Cookie or Referer long
     * enough to worry about still cannot contain them.
     */
    @TypeConverter
    fun fromHeaders(value: Map<String, String>): String =
        value.entries.joinToString("\n") { "${it.key}\t${it.value}" }

    @TypeConverter
    fun toHeaders(value: String): Map<String, String> {
        if (value.isBlank()) return emptyMap()
        return value.lineSequence()
            .mapNotNull { line ->
                val name = line.substringBefore('\t', "")
                if (name.isEmpty() || !line.contains('\t')) null
                else name to line.substringAfter('\t')
            }
            .toMap()
    }
}
