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

Waiting-stage copy follows detail rather than hiding partial availability:
no_release_found_yet on a partial show says The rest isn't available to download
yet. It'll be added automatically when it is. Nonpartial requests use generic
no-download wording. not_released_yet keeps Waiting for release for nonpartial
titles; partial shows retain Some episodes ready with an explanation that the
rest has not been released. No episode number or playable-episode count is
inferred from episodes.imported: it stays labelled N of M episodes imported.

The Unraid operator's 2026-10-31 TV correction retains the v1 fields and numeric
types. For TV, stageProgress now measures episode-weighted download completion
over the whole request, counting finished episodes as complete and active ones
fractionally, rather than weighting only the current active download set. The
service reports a nondecreasing percentage. As of the owner's 2026-11-01 policy,
TV requests always send etaSeconds null and have no time estimate. The earlier
TV ETA experiments are superseded. Retry detail is
reported only when nothing else is coming for the request. These are operator-
reported source changes (52/52 checks, including six failures against the old
behavior), not checks independently run by the app agent. The existing Alpha
displays the supplied progress values and replaces the entire record on update,
so null ETA and removed retry detail clear previous values. The TV client also
ignores any older TV estimate still in a cached record and never derives a TV
ETA from bytesLeft. Canonical Seerr fallback does not collect TV completion-time
estimates, and the card explicitly permits estimate Text only for movies in both
paths. No Text or fixed spacer is emitted for a TV estimate; the normal column
spacing applies only to remaining children. Movie estimates retain their existing
behavior. The bar, percentage and imported-episode counter remain independent of
ETA. Download completion and imported counts still do not establish Seerr
availability.

Source availability/status changes coalesce into captured-session, per-request
Seerr detail reads for known rows. They replace only that verified request while
keeping all other rows and metadata. Unknown requests and confirmed-deletion
checks still use the existing history sync, as do manual/periodic refreshes.
Only the verified canonical path feeds the existing ready/partial/failed chip
journal. A matching personal TV record with availability partial can show Some
episodes ready and Watch now immediately, independently of its processing stage.
It uses a matching-quality canonical library ID; if that link is missing, Watch
now performs one captured-account Seerr title lookup, verifies its TMDB identity
and quality-specific library ID, and checks the account again before navigation.
It never substitutes the other quality or a title-name match. A service Ready
hint is shown as Finishing up until canonical confirmation. Home and other pages retain the existing
foreground minute checker. Live progress does not enable phone background push
or change the held Phase 5/deployment gates.

The owner reported a repeated partial popup for Schitt's Creek, request 58. The
operator correlated the first alert at 23:45:37 UTC and the second at 23:49:17 UTC
with the helper-triggered Seerr check making seasons 5 and 6 available; overall
availability remained partial. It was not the five-minute sweep. Alpha 7f10b6fa
already groups all season signatures into one partial presentation per account,
request and quality, honors legacy dismissed keys across restart/reconnect, and
preserves the separate full-ready alert. Source records continue updating the
card silently within partial availability. The outstanding S03E10 torrent lacked
seeders and is handled by the Unraid cleanup/replacement path, not an app failure.

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

## Stable presentation while a request becomes Ready — 2026-10-31

The owner identified two cosmetic defects missed in the first Blair Witch
recording review: the confirming explanation disappeared briefly before Ready,
and Happy Death Day's poster appeared blank as the reordered list revealed it.
Functional progress remained successful; the earlier review was not exhaustive
visual acceptance.

Cards were already keyed by request ID. They now use stable, fixed-size image
requests and matching Coil memory-cache placeholders. A page/account-scoped cache
retains up to 24 successful painters across lazy-row disposal/reordering. At most
eight leading/viewport-neighbour rows are warmed, with image prefetch concurrency
limited to two; this does not prefetch all request history or run each second.
The confirming explanation remains for both confirming_availability and the
live ready hint, until canonical Ready replaces it.

For a known request, a live Ready/availability hint now fetches only that personal
request's detail and publishes the row and notification journal together, without
setting the list-wide loading state. The stream's complete progress record lacks
the canonical ownership/library-link fields, so it does not replace that check.
Row metadata, quality, session/account guards and TV season validation remain.
Manual/periodic history reads still preserve the currently displayed rows.

Arm64 Alpha built in **3m 53s** and installed directly from srv1160496 on the
Shield: **ADB Success, exit 0**. No optional tests, device navigation, screenshots
or server changes. The owner verifies the next Ready transition visually; build
and installation do not establish that the one-frame/one-second flicker is gone.

## Delivery record — 2026-10-31

Arm64 Alpha built successfully in **4m 24s** and installed directly from
Hostinger worker srv1160496 on the Shield: **ADB Success, exit 0**. An earlier
Kotlin symbol-generation attempt failed; the final frozen-source retry passed.
No clean/cache deletion was performed. Build daemons stopped; **57 GB free**.
No optional tests/lint/reviews or agent device/authentication checks were run.
The owner reported the public NC1 route live during the build; authenticated
streaming and the real download timeline remain owner/operator verification.
