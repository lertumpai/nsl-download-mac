# 037 movie downloading

Test date: 2026-09-05 (Asia/Bangkok).

Open a movie detail page at https://www.037hddmovies.com/ in NSL, tap **Download**, and select **Thai Player** or **Soundtrack Player** when offered. The download resolves the public LeoPlayer configuration and tries its alternate servers. Playback does not need to start first. When the HLS master supplies separate audio, NSL saves an MP4 containing both video and audio.

## Fixes

- Site-specific Download action avoids confusing ad clips with the movie.
- Public LeoPlayer API traversal follows the language player and its backup servers. The primary stream1689 server returned an error during testing; the streamhls backup worked.
- The actual embedded player Referer, Origin, and mobile User-Agent accompany media requests.
- PNG headers prepended to MPEG-TS segments served as `.jpg` are removed after checking the PNG boundary and transport-stream sync bytes.
- HLS audio groups are retained and the separate tracks are merged with Android MediaMuxer.
- Transient segment failures are retried. Existing partial-download resume remains active.
- Unsupported encryption, missing AES keys, fMP4 initialization maps, and byte-range playlists fail instead of being published as complete TS files.

## Full host transfer results

The production `MovieResolver` and `HlsDownloader` fetched every video and audio segment from three distinct movie pages. All manifests included `EXT-X-ENDLIST`; downloaded durations matched their declared durations to within one second. FFprobe found H.264 1280×720 video and AAC audio. FFmpeg decoded five-second samples at the beginning, middle, and end of each video. Seeking into raw TS can emit initial reference-frame warnings until the next keyframe.

| Movie title listed by the site | Duration | Video bytes | Audio bytes |
| --- | ---: | ---: | ---: |
| Mobile Suit Gundam Hathaway The Sorcery of Nymph Circe (2026) | 1:47:37 | 709,568,212 | 111,615,036 |
| The Runner (2026) | 1:25:51 | 985,923,700 | 88,240,244 |
| The Whisper Man (2026) | 1:53:48 | 513,255,980 | 117,295,456 |

Exact movie page URLs are in `037-movie-cases.txt`; machine-readable transfer evidence is in `037-host-results.json`. Local raw media and detailed playlist/probe/decode logs are under `/tmp/nsl-movie-downloads/` and are not committed.

## Full Android results

All three complete movies also passed `MoviePipelineTest` on Android 15 (API 35, arm64). Each used the production resolver, HLS downloader, and `HlsMovieDownloader`/`Mp4Muxer`. The instrumentation runner completed successfully in 1,497.216 seconds. Every output had video and audio tracks, the expected full duration, and an Android-decoded frame. The saved MP4s were pulled back to the host and five-second audio/video samples decoded cleanly at the beginning, middle, and end.

| Movie | Android MP4 bytes | Duration |
| --- | ---: | ---: |
| Gundam Hathaway | 782,635,113 | 6457.259 seconds |
| The Runner | 1,033,964,156 | 5150.741 seconds |
| The Whisper Man | 594,268,930 | 6828.715 seconds |

Machine-readable evidence: `037-android-results.json`. Complete playable MP4 copies are at `/tmp/nsl-movie-downloads/movie-1/android.mp4`, `movie-2/android.mp4`, and `movie-3/android.mp4`.

## Reproduce

From `android/`, offline tests:

```sh
./gradlew :app:testDebugUnitTest
```

Opt-in complete real-network transfers (about 2.5 GB total):

```sh
NSL_MOVIE_CASES="$PWD/../docs/testing/037-movie-cases.txt" \
NSL_MOVIE_OUTPUT=/tmp/nsl-movie-downloads \
./gradlew :app:testDebugUnitTest --tests 'com.nsl.downloader.movies.MovieLiveDownloadTest' --rerun-tasks
```

Full Android downloads, mux, audio/video track inspection, duration check, and decoded-frame check:

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb push ../docs/testing/037-movie-cases.txt /data/local/tmp/nsl-037-cases.txt
adb shell am instrument -w -r \
  -e class com.nsl.downloader.MoviePipelineTest \
  -e movieCases /data/local/tmp/nsl-037-cases.txt -e movieFull true \
  com.nsl.downloader.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Omit `movieFull` for a three-segment Android smoke test. Real-network tests are skipped by default. Full Android output files and per-movie verification notes are in the test app's external files directory, under `movie-full-1` through `movie-full-3`.

## Checks and limitations

- Offline unit suite: 19 passed; the live transfer test is skipped unless opted in.
- Host live transfer test: passed for all three complete movies.
- Manual Android browser check: the Download button appears before playback, and opens a Thai/Soundtrack picker.
- Android lint: 17 existing errors in untouched MediaStorage, theme resources, and PlayerActivity. No errors reported in the added movie code.
- External server availability can change. These results establish the three tested pages and player format; they do not guarantee every catalog entry or future availability.
- DRM is not supported. Separate-audio masters currently download their highest variant automatically. HLS layouts outside the supported TS/ADTS path are rejected.
