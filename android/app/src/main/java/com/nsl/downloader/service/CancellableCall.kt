package com.nsl.downloader.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Response

/**
 * Runs [block] over the response of [call], tearing the socket down if the
 * coroutine is cancelled.
 *
 * A thread blocked in a socket read never observes cancellation on its own, and
 * a completion handler on the job cannot help — the job only completes once
 * that read returns, which is the very thing we are trying to stop. So the
 * guard is a child coroutine parked in [awaitCancellation]: cancelling the
 * parent resumes it at once and its `finally` kills the call out from under the
 * reader, which surfaces there as an ordinary IO failure.
 *
 * Without this, cancelling a download would not take effect until the current
 * transfer ran to completion or hit the read timeout.
 */
internal suspend fun <T> useCall(call: Call, block: suspend (Response) -> T): T =
    coroutineScope {
        // Unconfined starts eagerly, so the guard is in place before the call.
        val watchdog = launch(Dispatchers.Unconfined) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            call.execute().use { block(it) }
        } finally {
            watchdog.cancel()
        }
    }
