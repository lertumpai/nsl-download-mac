package com.nsl.downloader.util

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Test

class RateLimiterTest {
    @After fun reset() { RateLimiter.bytesPerSecond = 0 }

    @Test fun bufferLargerThanBurstAllowanceStillCompletes() = runBlocking {
        RateLimiter.bytesPerSecond = 1024
        withTimeout(3000) { RateLimiter.acquire(512) }
    }

    @Test fun bandwidthWaitIsCancellable() = runBlocking {
        RateLimiter.bytesPerSecond = 1
        assertNull(withTimeoutOrNull(100) { RateLimiter.acquire(65536); true })
    }
}
