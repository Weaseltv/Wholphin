# WeaselPlex TV request status and notifications — phases 1–2

Implemented on 2026-10-29 in branch `t3/in-app-request-status-notifications`, on
srv1160496. The Phase 0 findings and the agreed plan remain the design record.
This work implements TV status and in-app notifications only. Phone parity,
phone push, backend deployment and Discord cutover remain phases 3–6.

## Customer behavior

- Requests has Search and **My Requests**, replacing History. My Requests groups
  Ready, In progress and Needs attention, with title/year/poster, stage, real
  download progress and future ETA when present, requested date, update age and
  Refresh. Old terminal rows are hidden after 30 days; tracking is not truncated
  by that display rule.
- After a successful movie/TV request or pending-season edit, the returned
  request is seeded and My Requests opens with that exact card focused. The
  title page's request action shows the live own-request stage and opens its
  status. TV retains a separate Request action for adding/editing seasons.
- Watch now opens the matching-quality library title. If that ID isn't known,
  Details remains available. Cancel acts only on the exact own pending request
  and removes it from local state/journal immediately after the server deletes it.
- Ready, partly ready and genuine failed/declined transitions queue a TV chip.
  Existing terminal rows are baselined once and don't flood the screen on first
  use. The Requests icon has a ready count even while the sidebar is collapsed.
- The nonmodal chip waits for resumed browsing, a focused main window, no
  playback, screensaver/dimming, dialog or focused text input. It saves page
  focus, selects its primary action, rejects OK presses begun before its 700 ms
  guard, handles Back while focused, and restores focus on dismissal or a
  visible 15-second timeout without input. Multiple events form one chip.
- Settings → More → Request notifications defaults on and persists per request
  account. Turning chips off does not remove My Requests or its unread badge.

## Data and session behavior

`RequestStatusRepository` is the sole foreground poller. It captures one client,
account and generation for a sync, drops other owners' rows and rejects late
responses after switches/reconnects. It enumerates 50-row pages up to the 1,000
safety cap, with an explicit incomplete-history message. Listing uses stable
request-ID order (`added`); display order remains newest modified first. TV
season availability is hydrated with at most four calls concurrently and shared
per media. Unchanged fresh fully-ready snapshots are reused. Missing persisted
request IDs are checked individually, including after process restart; only a
second 404 confirms removal.

Poll intervals are 60 seconds while My Requests is visible or downloads/imports
are active, five minutes while work remains, 30 minutes otherwise, with bounded
1/2/5/10-minute failure backoff. Refresh, submit and cancellation wake the same
single-flight coordinator. Background stops polling.

A list 401, or a list 403 after that same sign-in previously listed successfully,
permits one `/auth/me` probe per 15 minutes. A dead-session response permits one
silent Quick Connect/API-key reconnect through the existing single-flight
policy. The successful-list marker survives an activity stop/resume. Expired
sessions preserve account identity and pause chips. Legacy password credentials
are not automatically retried on expiry. Website SSO and existing provisioned
backend identities are used; this feature requires no separate backend sign-in.

## Review decisions and limits

Claude Opus 5.5 high reviewed architecture and implementation over three rounds.
Round 3 returned **AGREE**, with all seven P1 and thirteen P2 findings resolved.
The review ledger is retained beside this file. Optional follow-ups also added
a local dismissed-key guard, honest initial loading copy, focus retry backoff,
manager cancellation parity and the corrected stage handoff documentation.

Two practical refinements supersede the older plan:

- Request-ID paging avoids moving modified-time pages; the UI still sorts by
  update time and a missing tracked row gets an owned detail read.
- A chip remains durably pending until a visible timeout, dismissal or action,
  rather than being consumed merely on appearance. Hiding during connection,
  account or window changes cannot lose it. Scope-keyed writes remain valid
  while reconnecting and cannot publish to another account. Killing the process
  while a chip is still up can present that unacknowledged chip again later.

Library removal after completion shows Not available without inventing a
couldn't-add notification. Failed TV hydration retains last-known stages/badges
but cannot generate a new readiness event. Empty/incomplete queue data never
claims a failed search. Only valid finite byte counts produce progress, and only
real future movie release dates produce Not out yet (digital/physical preferred).

A read-only public status check on 2026-10-29 returned Seerr 3.5.1,
`564bfb08b54caa374bddaf820abfd226e5e69151`. Its source has no changes from the
Phase 0 version in the request routes, media/season subscribers, download
tracker, auth middleware, auth routes or MediaRequest entity used here. No
credential/login probes, real requests or notification webhooks were sent.

The owner performs Alpha visual, playback and D-pad verification. No optional
tests, lint, formatting suite, screenshots, app launch/navigation or device
metadata checks are part of this delivery. Build/install outcome is recorded
below after the final review and installation.

## Delivery record

- Final arm64 Alpha build: **BUILD SUCCESSFUL**, 36 seconds with incremental
  outputs retained. Helper package/native-dependency safeguards passed.
- Direct VPS → Shield install attempted through the approved helper.
  ADB returned **device unauthorized**; the Shield must approve this VPS's
  existing debugging key. No replacement key, other host, fallback route,
  uninstall or data reset was used.
- The owner reported approving the Shield prompt. A direct install retry,
  disconnect/reconnect and restart of the VPS ADB daemon still returned
  **device unauthorized**. The existing September 17 host key is unchanged;
  installation remains blocked on the Shield accepting it. A new prompt/status
  confirmation was requested. The built APK is
  `app/build/outputs/apk/weaselfin/alpha/WeaselPlex-alpha-arm64-v8a.apk`.
- No app launch, screenshots, playback, D-pad testing or installed-package
  inspection performed. All active Alpha build/Gradle/Kotlin caches retained.
- Disk after build: **60 GB free** on srv1160496.
