package com.nsl.downloader.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Live state of the batches (playlists) the download service is working
 * through, keyed by batch id.
 *
 * A library row only appears once its transfer actually starts, so the tail of
 * a 40-item playlist would otherwise be invisible — and impossible to call off
 * — until the service reached it. The Library observes this to show what is
 * still queued and to cancel the whole batch.
 *
 * In-memory like [DownloadProgressBus]: the service queue does not survive the
 * process either, so there is nothing to persist.
 */
object DownloadQueueBus {

    data class Batch(
        val id: Long,
        val label: String,
        val total: Int,
        val finished: Int,
        val failed: Int = 0
    ) {
        /** Items neither downloaded nor failed yet — queued plus in flight. */
        val remaining: Int get() = (total - finished).coerceAtLeast(0)

        val downloaded: Int get() = (finished - failed).coerceAtLeast(0)
    }

    private val _state = MutableStateFlow<List<Batch>>(emptyList())
    val state: StateFlow<List<Batch>> = _state

    /** Registers a batch before its items are handed to the service. */
    @Synchronized
    fun start(id: Long, label: String, total: Int) {
        if (total <= 0) return
        _state.value = _state.value.filterNot { it.id == id } + Batch(id, label, total, 0)
    }

    /**
     * One item reached a terminal state, whatever it was: a batch that finished
     * with failures should still stop showing as in progress. Returns the batch
     * as it now stands — `remaining == 0` means that was the last item — or null
     * if the batch is already gone (cancelled).
     */
    @Synchronized
    fun finished(id: Long, ok: Boolean = true): Batch? {
        val batch = _state.value.firstOrNull { it.id == id } ?: return null
        val updated = batch.copy(
            finished = batch.finished + 1,
            failed = batch.failed + if (ok) 0 else 1
        )
        _state.value = if (updated.remaining == 0) _state.value.filterNot { it.id == id }
        else _state.value.map { if (it.id == id) updated else it }
        return updated
    }

    @Synchronized
    fun remove(id: Long) {
        if (_state.value.any { it.id == id }) {
            _state.value = _state.value.filterNot { it.id == id }
        }
    }

    @Synchronized
    fun clear() {
        if (_state.value.isNotEmpty()) _state.value = emptyList()
    }
}
