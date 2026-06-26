package com.nsl.downloader.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * In-memory, app-wide live download progress keyed by video row id.
 * The download service publishes here every ~0.5s; the Library observes it.
 * Kept out of Room so we don't write to disk on every chunk.
 */
object DownloadProgressBus {

    data class Info(val percent: Int, val bytesPerSec: Long)

    private val _state = MutableStateFlow<Map<Long, Info>>(emptyMap())
    val state: StateFlow<Map<Long, Info>> = _state

    fun update(id: Long, percent: Int, bytesPerSec: Long) {
        _state.value = _state.value.toMutableMap().apply {
            put(id, Info(percent.coerceIn(0, 100), bytesPerSec))
        }
    }

    fun clear(id: Long) {
        if (_state.value.containsKey(id)) {
            _state.value = _state.value.toMutableMap().apply { remove(id) }
        }
    }
}
