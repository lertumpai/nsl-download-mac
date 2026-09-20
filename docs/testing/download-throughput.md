# Download throughput changes

## Findings and implementation

- Settings previously accepted only 1–6 concurrent downloads. It now accepts 1–10,
  defaults to 10 for fresh settings, and preserves an existing selected value.
- HLS waited for every segment in a batch of six before scheduling another batch.
  A slow response left successful workers idle. A rolling window now schedules up
  to 12 segments with six network workers per download. Files are appended in order
  and every committed segment receives a resume checkpoint.
- HLS previously retained complete segment bodies in memory. Lookahead now uses
  temporary files; transfer, decryption, and assembly use fixed-size buffers. The
  staging directory is removed after workers finish on success, failure, or cancel.
  This trades extra temporary disk I/O for bounded heap use with ten movies.
- HLS speed previously advanced only when a whole batch completed. It now counts
  received bytes during transfers (including retry traffic), while percentage tracks
  segments actually committed to the output.
- Direct range requests now have a 20-second whole-call budget when bandwidth is
  unlimited. This recovers from both silence and drip-fed responses that never hit
  the read timeout. Retries retain the in-process partial offset, and useful progress
  renews the retry budget. Completed chunks remain resumable across process restarts.
- Range replies validate offsets and lengths before writing. Requests use identity
  encoding and replace stale browser Range headers to keep byte positions valid.
- The shared connection pool retains up to 60 idle connections for reuse between
  chunks. Network concurrency remains bounded by the workers; OkHttp dispatcher
  limits do not constrain these synchronous calls.
- The optional shared bandwidth cap now covers HLS too. Budget waits suspend and
  observe cancellation; buffers larger than the burst allowance can still complete.

## Validation

Run `./gradlew :app:testDebugUnitTest` from `android/`.

The local regression fixtures cover ten simultaneous byte-identical downloads,
connection rotation after a stalled partial range, wrong range offsets, inherited
compression/range headers, slow HLS segment lookahead with no more than six workers,
ordered assembly, prefix resume after a failed segment, streaming AES decryption
with and without padding, large PNG-wrapped TS segments, and cancellation during
bandwidth waits. Existing direct-transfer truncation and resume tests remain enabled.

These are controlled local-server and interceptor tests. They do not measure a
specific CDN or Android device's sustained throughput. The opt-in live movie test
requires explicit source URLs and is skipped when none are supplied.

Android debug lint reports 21 errors in unchanged MediaStorage, PlayerActivity, and
theme files (API-level and Media3 opt-in declarations). It is not a clean lint run.

## Device check

1. In Settings, choose 10 downloads and Unlimited speed; an upgraded install keeps
   its previous concurrency and speed preferences until changed.
2. Queue a mixture of direct and HLS videos, including one known slow source.
3. Check progress and speed while switching apps, then cancel one active item.
4. Interrupt connectivity, restore it, and verify completed files play normally.

Ten files share the available link; each cannot individually use its full bandwidth
at the same time. Server throttling, Wi-Fi/cellular conditions, storage speed, and
post-download muxing/transcoding can still limit completion speed. These changes
remove app-side bottlenecks, but do not guarantee maximum network speed at all times.

References: [OkHttp connection pooling](https://github.com/square/okhttp) and
[Kotlin IO dispatcher behavior](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-dispatchers/-i-o.html).
