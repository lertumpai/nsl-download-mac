package com.nsl.downloader.library

import com.nsl.downloader.data.VideoEntity

/**
 * Returns only the entries physically assigned to the selected library level.
 *
 * A null selection is the root/"All" chip, not an aggregate across folders.
 * Moving an entry into a folder therefore removes it from the root list.
 */
internal fun List<VideoEntity>.inLibraryFolder(folderId: Long?): List<VideoEntity> =
    filter { it.folderId == folderId }
