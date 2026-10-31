# TV foreground live request progress

The TV consumes the agreed Unraid progress-service v0.3 contract (wire version 1)
through the public HTTPS requests host. Source collection and production routing
belong to the Unraid/NC1 operators; this implementation runs on the thread's
Hostinger T3 worker, srv1160496. NC1 routing was reported live by the owner during
implementation, with external unauthenticated 401 and streaming support verified
by the infrastructure agent. No production routing was changed from this thread.

## Transport and lifecycle

- One shared, account-captured SSE connection while My Requests is visible,
  the window is resumed/focused, and playback, screensaver/dimming, editing,
  dialogs and onboarding are inactive. Backgrounding or leaving the page cancels
  the connection and its HTTP work. Account/client changes invalidate all late
  callbacks. Overlapping Activity foreground jobs serialize through one owner.
- Requests use `/progress/v1/stream` and `/progress/v1/snapshot` on the pinned
  HTTPS host and reuse that exact Seerr client's in-memory `connect.sid` cookie
  jar. API-key configurations and other Seerr servers keep the existing path;
  the progress clients strip API-key/impersonation headers and refuse redirects.
- Stream read timeout is 40 seconds, allowing the 15-second heartbeat while
  detecting a dead connection. OkHttp SSE reconnect is implemented explicitly.
  Snapshot calls have a 15-second limit and cancellation closes the HTTP call.
- A connection begins with a full personal snapshot. Version, boot, revision,
  record identity, observation time, bounded payload and numeric values are
  checked. Updates replace full records; revisions may have gaps. Unexpected
  versions and malformed/unavailable routes back off instead of clearing history.
- A stream delivering events needs no snapshot polling. If it goes quiet,
  audit the service's shared JSON cache after 1 second, regardless of stage.
  Heartbeats alone do not prove request updates arrive.
  An audit merges newer data without closing the stream. Its cursor advances so
  older queued events cannot undo that snapshot. A failed backup read backs off
  independently and leaves the stream open; an authentication denial still
  invokes the guarded recovery path. These are cached progress reads, not direct
  calls to Seerr or the downloaders. If streaming fails but JSON
  works, poll the cached endpoint every second while My Requests is visible, with
  separate bounded stream retries. Failures use 2/4/8/.../60-second backoff,
  jitter and Retry-After. Unsupported routes are retried slowly so the same
  Alpha can discover a newly enabled route.
- Revalidating freezes progress with stale status; ok requires a fresh snapshot.
  A genuine auth denial stops automatic progress retries for that client and
  invokes the existing separately throttled SSO probe/recovery. A server outage
  never signs anyone out. Manual Refresh offers an explicit retry.

## Canonical data and presentation

The progress map is ephemeral and supplements already-owned canonical request
rows. Its snapshot, update, remove and 24-hour retention never replace Seerr
history, metadata, notification baselines/events or unread counts. Row identity
must match current account/client, request ID, TMDB ID, movie/TV type, requested
seasons and quality before the overlay is displayed.

The list uses existing canonical rows and stable per-request focus requesters;
percent updates do not change list order, poster URLs or initial selection.
Display supports pending approval, accepted, waiting/release, searching, queued,
downloading, checking, unpacking, importing, adding, Finishing up and paused.
Partial availability remains separate from processing progress. Imported episode
counts are labelled imported, not playable. Unknown progress is not invented;
stale data retains its last value and is labelled Last known status.

Source availability/status changes wake a coalesced canonical Seerr refresh.
Only that verified canonical path feeds the existing ready/partial/failed chip
journal, library IDs and actions. A service Ready hint is shown as Finishing up
until canonical confirmation. Home and other pages retain the existing
foreground minute checker. Live progress does not enable phone background push
or change the held Phase 5/deployment gates.

Every successful submission/season edit forces a live resync, coalesced with
page entry through the shared connection owner. Successful cancellation removes
only the matching local progress entry before resynchronizing. Error paths do
not report a successful submission or navigate as if it succeeded.

## Owner verification

After the Alpha installation:

1. Open My Requests with the normal account. The header should reach Live updates
   (or Updating automatically for a healthy snapshot fallback), with no extra login.
2. Request a title normally. Keep My Requests open through downloading, unpacking,
   importing, Finishing up and Ready; do not press Refresh. The Unraid operator's
   stage logs can measure source-to-screen behavior alongside this observation.
3. Confirm the selected row/scroll position stays usable while percentages change.
   Leave for Home and confirm the existing ready popup still works.
4. Verify a partially ready show labels imported counts accurately and updates
   remaining downloads. Switching accounts must never show another user's rows.

The owner performs device checks. The app agent does not launch/navigate the
Shield, capture screens/logs, extract session credentials or create real requests.
Build/install success alone is not authenticated end-to-end verification.

## Recovery after the first owner trial — 2026-10-31

The owner clarified that **Ernest Scared Stupid (1991)** did show the later TV
stages; only Downloading was missing. The earlier interpretation that all TV
progress stayed frozen was incorrect. The operator traced the missing stage to
a 38-second torrent completing before Radarr's queue exposed its mapping, and
reported deploying a grab-history matching fix (41/41 checks passed). Those are
operator-reported results, not independently reproduced here.

The recovery Alpha accepts finite nonnegative JSON numbers for bytes remaining
and ETA, including fractions that the original Long fields rejected. ETA is
rounded only for display. SSE events and quiet-stream snapshot audits apply
through one serialized owner/cursor so older queued messages cannot undo a fresh
snapshot. This first recovery build switched to one-second automatic fallback
when an audit was newer; the stream-race correction below supersedes that policy.
There is no Refresh requirement. Logs identify rejected event types and recovery
without recording payloads, parser exception text, cookies or account identities.

The owner rejected reusing the spec's original 3s/10s fallback values for this
recovery. Healthy cached fallback and quiet-stream audits now both use one second
while the page is visible. Source collection remains shared on the service;
failed HTTP requests still back off instead of retrying once a second through an
outage. The first build with the old timings was stopped before installation.

The one-second recovery Alpha built successfully in **4m 32s** and installed
directly on the Shield from srv1160496: **ADB Success, exit 0**. No app launch,
device navigation, session extraction or optional tests were performed. The
operator's later log and the owner's clarification identify a missing Downloading
stage, not a complete TV delivery failure. Real-download verification remains
with the owner/operator.

## Keep the stream open during cached recovery — 2026-10-31

The Fly II recording and operator connection log showed roughly one minute of
fallback after the live stream disconnected. The first recovery build treated
a cached snapshot arriving ahead of its live event as a stream failure, closed
the socket and imposed a 60-second retry delay. A normal timing race could
therefore disconnect a healthy stream.

The TV now applies the newer snapshot and advances the shared revision cursor
while keeping the stream open. This removes the snapshot-triggered disconnect
and its one-minute cooldown. Backup-read failures back off independently without
closing the live stream. Real stream failures still use automatic one-second
cached fallback with bounded reconnect/backoff; account/lifecycle cancellation
and genuine authentication denial still end the connection. The Unraid-owned
status regression and Jellyfin ingestion investigation are separate work.

The corrected arm64 Alpha built successfully in **2m 4s** and installed directly
from srv1160496 on the Shield: **ADB Success, exit 0**. No app/device navigation,
optional tests or production changes were performed. The owner verifies live
connection continuity on the next request; installation success is not a claim
of a reproduced end-to-end streaming test.

## Dismiss restores the exact My Requests action — 2026-10-31

The Scooby-Doo (2002) recording showed My Requests selected after Dismiss, and
the owner confirmed it moved there automatically. Saving the enclosing page
alone was insufficient to preserve the exact action through nested tab/group
focus entry.

My Requests now tracks the attached focused control for Refresh, the stable
Details/Watch now button and Cancel. The chip captures that target as well as its
region. Dismiss/Back/timeout re-enters the saved region and then explicitly
requests the original control before removing the chip. Detached targets are
discarded safely; pages without an exact target retain their existing region
restoration. A row's requester survives its Details-to-Watch-now label change.
The primary popup action still navigates without restoring the old page.

Arm64 Alpha built in **2m 38s** and installed directly from srv1160496 on the
Shield: **ADB Success, exit 0**. No optional tests, device navigation, screenshots
or production changes were performed. The owner verifies the next dismissal;
build/install success is not a claim of device focus testing. Server-side stage
correction and Jellyfin/Seerr timing optimizations remain the Unraid operator's
separate work.

## Delivery record — 2026-10-31

Arm64 Alpha built successfully in **4m 24s** and installed directly from
Hostinger worker srv1160496 on the Shield: **ADB Success, exit 0**. An earlier
Kotlin symbol-generation attempt failed; the final frozen-source retry passed.
No clean/cache deletion was performed. Build daemons stopped; **57 GB free**.
No optional tests/lint/reviews or agent device/authentication checks were run.
The owner reported the public NC1 route live during the build; authenticated
streaming and the real download timeline remain owner/operator verification.
