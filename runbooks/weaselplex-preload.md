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
- `storage limit reached; background pre-load stopped storedBytes=… limitBytes=…
  freeBytes=… reserveBytes=…`: capacity reached. Stored video stays available;
  the physical-space check includes 1 MiB of cache/filesystem bookkeeping headroom.

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

## Real-pacer Shield test — 2026-11-07

Owner explicitly authorized device playback/control for this test. All work stayed
on ThinkCentre. The live pacer supports Justin M only; no server/Jellyfin/nginx
configuration was changed. The installed test Alpha includes DEBUG-only successful
report acknowledgements (dispatch/response timing) and player state/seek logging.
Headers, endpoints, JSON fields and failure handling are unchanged.

Tested **Justin M → SHIELD → WeaselPlex Alpha → ExoPlayer → DirectPlay**, using
The Boys / King of Hell. Current saved user and the normal Jellyfin playback session
both confirmed Justin M. Capability logged `capability=v1; backend=ExoPlayer`.
The public `/status` endpoint returned 404 from this worker; acknowledgement logs
were used instead of claiming access to the server's LAN-only scheduler view.

Measured results:

- Successful `/report` HTTP 200 responses. In a separate uninterrupted sample,
  six periodic client-dispatch intervals were **5.000, 5.001, 4.999, 5.026, 5.285,
  4.688 seconds** (startup's immediate reports excluded). All ten reports in that
  sample, including startup and stop, were acknowledged. Response latency was
  **228–362 ms**. Network acknowledgement timestamps alone had appeared irregular
  in the preceding run, which is why dispatch timestamps were added.
- Controlled seek/stop run: `bytesStored` reached **132,854,706 bytes**, with
  **629 seconds** reported continuously ahead. While paused, position held near
  235.66 s and stored bytes advanced from 80,688,050 to 91,960,242.
- A **120-second forward seek**, beyond the roughly 52-second in-memory buffer
  and inside the stored range, logged seek at **03:09:00.719 EST** and READY at
  **03:09:01.167 EST**: **448 ms**. A brief BUFFERING state occurred; this is a
  measured sub-second seek, not a claim of a literally zero-delay transition.
  Reported rebuffer-event count remained zero in this controlled run.
- Stop: **03:09:10.416 EST** logged
  `delete reason=player-left bytes=132854706 storedBytes=0`. The `preload/`
  directory was absent within **2,231 ms** of sending the stop command, including
  remote-command latency and polling. A second normal stop deleted 76,232,968 bytes
  and the directory was absent within **1,227 ms**. Both are inside the five-second
  acceptance window. Stop reports were acknowledged with HTTP 200.

### C6 defect found in the first test (resolved below)

The longer first run calculated a **430,670,080-byte** payload budget. At
**429,798,546 bytes**, **02:59:36.337 EST**, it logged:

```text
delete reason=low-storage-reserve bytes=429798546 storedBytes=0
```

That deletion happened before the requested stop test, so it was not counted as a
successful normal-stop deletion. The later shorter runs above isolated that case.

The original `PreloadSession.ensureRoom()` checked `freeBytes < reserve + incomingFragment`
**before** capacity eviction/pause logic. Cache/index/filesystem overhead or other
storage use can therefore reach this check before the payload budget. It treats
an incoming write that would breach the reserve as a reason to delete the entire
item and fall back to normal playback. **This differs from C6's normal capacity
behavior: evict over-five-minute-old data, then retain the item and pause speculative
pre-loading if still full.** This was left open by the initial test-only change;
the correction and Shield retest follow.

### C6 capacity correction and Shield retest — 2026-11-07

`ensureRoom()` now distinguishes an already-breached reserve from insufficient
room for the next write. The latter uses the normal capacity path: evict data over
five minutes behind, recheck both payload and physical headroom, then stop background
pre-loading while retaining the item. A 1 MiB write allowance accounts for cache/index
and filesystem metadata without increasing the payload budget. Urgent player writes
also recheck physical headroom after eviction. Actual free space below the reserve,
and Android low-storage notifications, still close and delete the store.

All **15 focused checks passed**: four fallback checks followed by eleven enabled
checks. Two new regressions exercise physical capacity below the payload budget,
retained cached reads, and eviction of data over five minutes behind before pausing.
The manager fixture warms its fake server connection before the three-second hello
check, matching the app's preceding Jellyfin metadata traffic. The arm64 Alpha built
successfully in **1m 22s** and installed in place on the owner's Shield (`Success`).

Real-pacer retest: **Justin M / SHIELD / ExoPlayer / DirectPlay**, same episode.
Hello returned HTTP 200 and the app logged `capability=v1; backend=ExoPlayer`.

- At **04:09:40.960 EST**, background pre-loading stopped with **512,898,194 bytes**
  stored, a **514,736,384-byte** payload limit, and **1,501,167,616 bytes** free
  against the **1,500,000,000-byte** reserve. No low-storage purge occurred.
- The cache stayed at **512,898,194 bytes** during the following 15-second hold,
  with about **2,908 seconds** reported ahead, and remained present after the seek.
- A 120-second forward seek from approximately 204.45 s to 324.45 s was beyond
  the 258.27 s in-memory buffer endpoint and inside the stored range. Seek logged
  **04:09:58.204 EST**, READY **04:09:58.431 EST**: **227 ms**.
- Normal stop logged **04:10:01.699 EST**:
  `delete reason=player-left bytes=512898194 storedBytes=0`. The cache directory
  disappeared within **1,382 ms** of the stop command. The stopped report was
  acknowledged with HTTP 200.
- There were 45 successful report acknowledgements. Most periodic dispatches were
  approximately five seconds apart; one pair of successful acknowledgements had a
  ten-second dispatch gap. Only successful responses are logged, so this does not
  prove every scheduled report was delivered. Reporting remains advisory with the
  existing three-second control timeout; this capacity fix does not change it.

Live HLS/transcode ordering, two-TV scheduler priority and a five-minute network
outage were not exercised in this direct-play test. Earlier fake-pacer coverage is
separate evidence, not a substitute for those live acceptance cases.
