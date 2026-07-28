package com.nsl.downloader.util

/**
 * App-wide download speed cap, shared by every transfer in flight.
 *
 * A token bucket rather than a per-connection cap: the setting the user picks is
 * a budget for the whole app, and the ranged downloader alone opens half a dozen
 * sockets. [acquire] is called from the transfer loops on IO threads and simply
 * sleeps when the budget is spent — a few milliseconds at a time, so a cancel is
 * never left waiting for long.
 */
object RateLimiter {

    /** Bytes per second; 0 means no limit. */
    @Volatile
    var bytesPerSecond: Long = 0
        set(value) {
            field = value.coerceAtLeast(0)
            synchronized(lock) {
                available = 0.0
                lastRefill = System.nanoTime()
            }
        }

    private val lock = Any()
    private var available = 0.0
    private var lastRefill = 0L

    /** Burst allowance, so a fast link is not chopped into stuttering reads. */
    private const val BURST_SECONDS = 0.25
    private const val MAX_SLEEP_MS = 50L

    /**
     * Blocks until [bytes] fit in the budget. Returns immediately when no limit
     * is set, which is the default and the hot path.
     */
    fun acquire(bytes: Int) {
        if (bytes <= 0) return
        while (true) {
            val limit = bytesPerSecond
            if (limit <= 0) return
            val waitMs = synchronized(lock) {
                val now = System.nanoTime()
                if (lastRefill == 0L) lastRefill = now
                val elapsed = (now - lastRefill) / 1_000_000_000.0
                lastRefill = now
                available = (available + elapsed * limit).coerceAtMost(limit * BURST_SECONDS)
                if (available >= bytes) {
                    available -= bytes
                    0L
                } else {
                    // Time to earn the shortfall, capped so cancels stay responsive.
                    (((bytes - available) / limit) * 1000).toLong().coerceIn(1L, MAX_SLEEP_MS)
                }
            }
            if (waitMs == 0L) return
            try {
                Thread.sleep(waitMs)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }
}
