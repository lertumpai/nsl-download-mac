# HLS slow-connection investigation

The supplied Library screenshot shows several concurrent transfers at 1–50 KB/s.
It does not identify the segment CDN, response status, network conditions, or the
configured speed cap. A metadata-only HEAD request to the supplied site on
2026-09-13 returned HTTP 403 with `cf-mitigated: challenge`. No live video-CDN
throughput measurement was possible from that request.

Code inspection found these remaining app-side gaps:

- HLS reads had no throughput monitor. A trickling response could remain active
  indefinitely without hitting a per-read timeout, blocking ordered assembly once
  the bounded lookahead window filled.
- Failed segments were restarted from byte zero, discarding their partial bytes.
- Six workers per file could produce 60 requests to one host with ten downloads.
  OkHttp's asynchronous dispatcher settings do not limit these blocking calls.
- The download foreground service did not hold its own CPU/Wi-Fi wake locks.

## Changes

`HlsSegmentFetcher` monitors response progress in eight-second windows. With no
intentional bandwidth cap, idle responses are cancelled; responses below 8 KiB/s
can be rotated twice per segment. Limiting slow-response rotations avoids
restarting a consistently slow but working link indefinitely. Cancellation still
interrupts the socket immediately.

Transport failures temporarily select an HTTP/1.1 client with a separate connection
pool for that host, for five minutes. Healthy requests continue to use the normal
client's negotiated protocol. This is a recovery fallback, not a claim that HTTP/1.1
is faster on every network.

Partial segment retries use Range/If-Range with identity encoding. Content-Range,
length, and available validators are checked before appending. A 200 response to a
resume request replaces the file. A changed validator or invalid offset discards
the prefix and retries cleanly. Cookies and Referer are retained. Completed encoded
segments are decrypted and assembled using the existing ordered pipeline.

A coroutine semaphore permits at most 12 segment requests per host across the
downloader's jobs. Waiters suspend without occupying IO threads. HTTP 429 and 503
responses establish a shared cooldown; Retry-After supports seconds and HTTP dates.
Ten downloads remain supported, sharing this per-host request budget.

The service holds a ten-minute CPU wake-lock lease while jobs are active, renewing
it every five minutes, plus a Wi-Fi lock. Both are released at idle or destruction.
Android can still restrict background networking. Holding these locks during
transfers uses more battery than allowing the device to sleep.

## Validation

The local-server tests cover stalled responses, partial-byte resume, ignored and
invalid ranges, changed ETags, header forwarding, Retry-After, 30 concurrent fetches
sharing a 12-request host limit, intentional speed caps, and cancellation releasing
a host permit. Output is compared byte for byte.

In the controlled three-second-stall fixture, a plain transfer took 3008 ms and
recovery took 276 ms. The test scales the watchdog to 250 ms to keep the suite fast;
production uses eight seconds. This proves the recovery path works, and is **not**
a measured speedup on the supplied site or the user's network.

The Android pause/resume tests also check that the CPU wake lock exists during an
active transfer and is released after all transfers pause.

Technical references:

- [OkHttp Call cancellation and timeout contract](https://raw.githubusercontent.com/square/okhttp/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/Call.kt)
- [OkHttp connection-pool behavior](https://raw.githubusercontent.com/square/okhttp/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/ConnectionPool.kt)
- [Android wake-lock guidance](https://developer.android.com/develop/background-work/background-tasks/awake/wakelock)
