# 037 MP4 playback fix — 2026-09-06

The user reported that completed 037 downloads would not play. Reproduction in the actual NSL `PlayerActivity` showed ExoPlayer stuck at 0 ms after rendering one frame: `STATE_BUFFERING`, `playWhenReady=true`, approximately 70 seconds buffered, no suppression or explicit decoder exception. This also occurred through the published Downloads content URI.

## Cause

Android's MPEG-TS extractor returned a bundle of ADTS-framed AAC packets as one sample. The old muxer copied that whole bundle directly into one MP4 AAC sample. For example, the first Gundam audio sample contained 17 ADTS frames, started with `ff f1`, and covered 362.667 ms instead of producing individual AAC access units at 21.333 ms intervals. Metadata extraction, thumbnails, and FFmpeg decoding tolerated the malformed packaging; they were insufficient playback validation.

Android distinguishes ADTS-framed audio using [MediaFormat.KEY_IS_ADTS](https://developer.android.com/reference/android/media/MediaFormat#KEY_IS_ADTS). MP4 output now receives individual AAC access units with ADTS headers removed and timestamps derived from their sample rate. Existing raw AAC tracks, including M4A inputs, remain unchanged.

## Changes

- Split bundled ADTS packets, remove 7-byte headers or 9-byte CRC headers, and assign per-frame timestamps.
- Reject truncated/malformed ADTS instead of saving broken output.
- Treat MP4 finalization failure as a download failure.
- On opening an affected Library download, detect the old defect and show **Repairing video…**. Save a corrected `(repaired).mp4` copy, then update the Library entry. Keep the original file. No network or movie re-download is required.
- Normal files pass through without repair. Failed platform probing does not block formats supported by ExoPlayer.
- Keep `.mp4`/`.mp3` extensions when shortening long filenames, and accept existing `file://` URIs correctly.
- Show playback errors instead of silently leaving a blank player.

## Verification

- 22 offline unit tests passed (one opt-in live test skipped).
- All three complete previously downloaded movies were remuxed with the corrected production muxer and played in NSL's actual player screen and Android MediaPlayer: Gundam Hathaway, The Runner, and The Whisper Man.
- Actual NSL playback tests require more than ten rendered video frames, more than ten rendered audio buffers, advancing playback, and successful seeking to the middle and near the end.
- A published Downloads copy passes the same playback and seek checks plus Android MediaPlayer playback.
- Fresh TS video/audio fixtures mux correctly and play in both engines.
- A fixture created using the old defective muxing behavior is detected and automatically repaired when opened from the Library. The original remains readable; the repaired copy plays through its content URI; reopening it does not create another repair.
- Long filenames retain their media extension; file URIs remain valid.
- Final instrumentation run: **OK (4 tests)**, 61.394 seconds on Android 15/API 35 arm64. See `037-playback-test.txt`.

## Use

Install the updated Desktop `nsl_browser.apk`, then open an existing affected movie from the Library. Allow the repair to finish. To use another player, open the new `(repaired).mp4` copy in Downloads or share the updated Library item. Repair writes a new copy and therefore requires free storage; the original is preserved. New downloads use the corrected format directly.

## Reproduction

`DownloadedMoviePlaybackTest` accepts `savedMovies=true` to use `movie-full-1` through `movie-full-3` in the test app's external files directory. Set `remuxMovies=true` only when those fixtures still contain the old defect. Set `tsFixtures` to a device directory containing short `video.ts` and `audio.ts` movie fixtures for fresh-mux and automatic-repair tests.

```sh
adb shell am instrument -w -r \
  -e class com.nsl.downloader.DownloadedMoviePlaybackTest \
  -e savedMovies true -e tsFixtures /data/local/tmp/nsl-playback-fixtures \
  com.nsl.downloader.debug.test/androidx.test.runner.AndroidJUnitRunner
```
