# Persistent download pause and resume

The Library's transfer button shows Pause for running or queued items and Resume
for paused or failed items. Download notifications offer Pause; paused notifications
offer Resume. The button resumes directly, without a confirmation dialog.

All queued requests now receive persistent Room rows before being scheduled,
including playlist items that have not started. PAUSED is stored using the existing
string status column, so this requires no database migration or data reset. On app
startup, unfinished rows that are not owned by the live service become PAUSED.

Pause persists the state before cancelling the worker and retains its files under
app-private storage. Direct files resume completed byte-range chunks; HLS resumes
the last committed segment; sequential downloads use their saved byte checkpoint.
An unfinished chunk or segment may be fetched again. Servers that do not support
ranges may require a sequential transfer to restart. YouTube URLs are resolved again
from the saved source URL. Muxing/transcoding can restart from saved source tracks.

Resume waits for an old writer to exit and conditionally claims the saved row, so
quick or duplicate taps cannot schedule two writers for the same file. Pausing a
playlist item detaches it from that batch without counting it as failed; it resumes
individually. Cancel/Remove still deletes the row and partial files.

Validation:

- `DownloadPauseResumeTest` on an Android 35 emulator: durable requests across
  database reopen, conditional state transitions, recovery of interrupted rows,
  pause of active and queued items, Pause/Resume notification actions, preservation
  of completed chunks, byte-identical output, quick repeated Resume taps, and
  recovery after stopping the service.
- `HlsDownloaderTest`: cancel at a committed segment, create a new downloader,
  resume without fetching committed segments, and verify byte-identical output.
- `DownloadQueueBusTest`: pausing playlist items does not count them as failures
  or completions and removes an empty active batch summary.

The existing optional live-source test remains skipped without supplied URLs.
