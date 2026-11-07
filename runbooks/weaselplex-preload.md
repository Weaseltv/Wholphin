# WeaselPlex TV pre-load (Alpha)

App implementation of AGENT-09 Part C and the owner's approved C-Q answers (2026-11-06).
Server implementation, Jellyfin and nginx are outside this change.

## Availability and fallback

Settings → Playback → **Pre-load video when the server has spare bandwidth** is on
by default, including existing installs. The stock backend is **ExoPlayer**.
“Prefer MPV” is an optional choice: ordinary SDR 1080p then selects MPV, while HDR
(or 4K with MPV hardware decoding off) selects ExoPlayer. Backend defaults are unchanged.
MPV and external players get no capability request, pacer headers, or heartbeat.

One authenticated hello request per playback enables the ExoPlayer session only on
v1 success. A 404/error/three-second timeout uses the original video data source and
retry policy with no pacer video headers, heartbeat, or video cache. Unknown-length
files, live/infinite items, DASH and cross-origin remote sources use normal playback.
The enabled path supports finite direct files with size/runtime metadata and HLS.

## Implementation

- `PreloadManager` owns the playback, setting gate, capability check, player listeners,
  startup cleanup and Android low-storage receiver.
- `PacerClient` captures this playback's Jellyfin Authorization header. Control calls
  have a three-second deadline; video calls have no total deadline, a 120-second read
  timeout, and no automatic connection retry or redirect.
- `PreloadSession` owns exactly one video worker. Both player cache misses and
  speculative downloading go through it. Direct requests are sequential 8 MiB
  ranges; HLS segments and their init/key dependencies stay in order. Bytes are
  committed to small cache fragments as they arrive so startup doesn't wait for an
  entire paced 8 MiB response. A 60-second byte-progress watchdog detects stalls.
  Retry the same unit after five seconds, once; no independent Media3 retries in
  enabled mode. Normal Jellyfin playback reports remain in place.
- `PreloadStore` uses app-private `files/preload/` and Media3 SimpleCache; player
  CacheDataSource is read-only, since the single worker is the only cache writer.
  The cache index uses Media3's SQLite provider, avoiding whole-index rewrites per
  socket fragment. Cache size is bounded by the Part C headroom formula.
- Direct stored-seconds estimation is `floor(0.9 * contiguousBytesAhead /
  (fileSize / runtimeSeconds))`, bounded by remaining runtime. The byte position is
  anchored to the extractor’s actual byte offset/time on seeks and advanced using
  playback time and average byte rate; actual player byte-range
  requests take priority over speculative reads. Header/report bitrate both use
  `floor(fileSize * 8 / runtimeSeconds)`. HLS uses stored segment durations and the
  MediaSource bitrate.
- An uncached explicit HLS seek cancels the old response, drops all stored HLS
  output, and advances the cache generation before fetching at the selected time.
  A background fetch never guesses a later segment URL or bypasses a failed segment.
- Storage pressure first evicts data over five minutes behind. At capacity,
  speculative downloading stops; urgent player reads may replace retained ranges
  to keep playback going within the budget. Android low storage or crossing the
  reserve closes/deletes the store and resumes open readers through normal network
  playback. Pausing alone continues pre-loading.

## Diagnostic log

Open Settings → About, long-press **Installed version**, then scroll to **Logcat**. The `Preload` tag
uses INFO-level messages, so verbose logging is not needed. Settings → Advanced →
**Send app logs** sends the same diagnostic log to the server.

Look for:

- `capability=unavailable; normal playback`: hello failed (404 is expected until the
  pacer is live); this playback creates no video store.
- `storedBytes=… bufferedAheadSec=… complete=… limitBytes=…`: stored payload size,
  continuous ahead estimate/duration and the session's limit.
- `delete reason=… bytes=… storedBytes=0`: whole-item cleanup. Reasons include
  `app-start`, `player-left`, `another-item-started`, `stream-replaced`,
  `playback-ended`, `android-low-storage`, and `hls-uncached-seek`.
- `delete reason=more-than-five-minutes-behind` or `capacity-player-demand`: bounded
  individual-range eviction, with remaining size.

The startup cleanup also removes force-stop/crash leftovers before another player
can open a cache. Before a new item gets a store, the old worker closes and deletion
finishes. No next-episode data is stored.

## Validation and rollout

Run `./scripts/test-preload.sh` for focused JVM/Android unit tests. The helper
limits discovery to the pre-load tests (avoiding unrelated legacy sample-class
loading failures on Java 17), keeps scratch files on disk, and saves XML results
under `build/preload-tests/`. Tests use a local fake pacer, never the media server:
fallback tests first, then range ordering, shared stored reads, fixed headers,
sequential HLS retry/seek behavior, paced byte progress, stall retries, capacity,
startup leftovers, and deletion. Test timeout thresholds may be shortened through
internal constructor arguments; production thresholds remain 60 seconds/five seconds.

Validation on the ThinkCentre: 13 focused checks passed (4 fallback and 9 enabled),
including the real manager/factory with a fake 200 hello, ownership isolation,
Android low-storage deletion, and an already-open reader resuming on the original
network source. This is local fake-server validation, not real-pacer/device acceptance.

The arm64 Alpha was built and installed on the owner's Shield from the thread's
ThinkCentre on 2026-11-07 (build successful in 4m 53s; ADB streamed install: Success). The owner performs device playback/visual verification.
Real pacer behavior, two-TV priority, network-disconnect playback and actual server
transcode cleanup await the server's test-mode signal. Two remote beta users and
one week of stall/storage observation precede any stable release; stable publication
requires explicit owner authorization.
