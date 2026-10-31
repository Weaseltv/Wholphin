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
  visible one-minute timeout without input. Multiple events form one chip.
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

While the activity is started, requests refresh every 60 seconds on every page,
including when the known list is idle, with bounded 1/2/5/10-minute failure
backoff. Refresh, submit, cancellation and connection changes wake the same
single-flight coordinator. Background stops polling. Each cycle re-captures a
superseded client/session rather than leaving the worker stopped until another
connection-flow emission.

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
- Initial direct VPS → Shield installs were blocked by **device unauthorized**,
  including retries after the first reported approval.
- Authorization resolved on 2026-10-29: an owner-authorized helper used its
  existing ThinkCentre connection solely to approve the Shield system dialog
  matching this VPS's independently verified public-key fingerprint. No private
  keys were copied or regenerated. Build and installation stayed on srv1160496.
- Final direct in-place install of the already-built arm64 Alpha APK:
  `adb -s 100.105.135.30:5555 install -r` returned **Success**, exit 0.
  The authorized VPS connection was retained without reconnecting or restarting
  ADB. No uninstall or data reset was used. The installed APK is
  `app/build/outputs/apk/weaselfin/alpha/WeaselPlex-alpha-arm64-v8a.apk`.
- No app launch, screenshots, playback, D-pad testing or installed-package
  inspection performed. All active Alpha build/Gradle/Kotlin caches retained.
- Disk after build: **60 GB free** on srv1160496.

## Startup compatibility correction — 2026-10-30

The owner reported "Can't reach WeaselPlex right now" when opening the installed
Alpha. The request branch was still based on `599dfca4` (2026-10-12), with Room
schema 36, while the newer installed Alpha work had already moved to schema 37
for kid profiles. `PinnedServerViewModel.connect()` reads the saved servers
before making a network request and labels every exception, including database
downgrade errors, as a connection failure. This source mismatch is the likely
cause; no device logs or app inspection were performed. The public media-server
info endpoint returned HTTP 200 with a valid server ID from the VPS.

Merged current `origin/weaselfin` (`ce17145a`, v1.2.43) into the request branch at
`79018aca`, preserving its exact schema-37 entities/migrations and the existing
profile protections, wake/network recovery and guided tour. Request chips now
wait while the tour is active, and dynamic request-stage labels work with the
current uppercase button renderer. No destructive database fallback, data reset
or authentication retry was added.

- Arm64 Alpha: **BUILD SUCCESSFUL in 6m 14s** using `scripts/build-alpha.sh`.
- Initial install was paused as **device unauthorized**. The helper found no
  running ADB daemon and started it normally; the existing VPS public-key
  fingerprint was verified unchanged. The owner approved the Shield prompt.
- Final direct in-place installation from the VPS of the already-built APK:
  `adb -s 100.105.135.30:5555 install -r` returned **Success**, exit 0.
  The saved Alpha accounts/settings were retained. No ADB server restart,
  key replacement, other-machine fallback, uninstall or data reset was used.
- The installed APK and build record remain at the normal active Alpha path.
- No optional tests, lint, review or device navigation/visual checks. Owner
  verification remains pending. Alpha incremental files and signing key retained;
  **58 GB free** after the build. No stable release or deployment.

## My Requests heading visibility — 2026-10-31

The owner's screenshot showed Ready clipped beneath Refresh while Watch now
was focused. My Requests now uses keep-visible scrolling with the existing
complete-row focus bounds, so a section heading and its card form one scroll
target instead of only the inner action button. Initial focus on the first card
retains the top inset; the list explicitly occupies the remaining height below
the toolbar.

Arm64 Alpha **BUILD SUCCESSFUL in 2m 4s** and direct VPS → Shield in-place install
returned **Success**, exit 0. No rebuild of other variants, optional tests/lint,
extra review, app navigation or device/visual checks. Owner verification remains
pending. Alpha incremental files and signing key retained, Gradle daemon stopped,
and **58 GB free** after the build. No stable release or backend deployment.

The requested Phase 0–4 owner checklist is in `owner-checklist.md` and the
existing Notion feature. Phone checks require the branch test build; backend
integration remains gated, and phone system pushes require Phase 5.

## Owner feedback: accepted status and notification delay — 2026-10-31

The owner passed the basic startup, readable layout, movie/TV submission,
Watch now/playback, notification setting and restart checks. Status clarity was
not accepted: Waiting for your library did not explain what was happening.
Notification actions and playback/typing suppression are untested; account
switching was not reported. Phase 5 has not been approved.

The owner stayed on Home for two minutes after a title finished, refreshed Home,
and saw no notification until returning to Requests. The old five-minute
waiting-request poll plus page-entry refresh explain that timing. Pending and
partly available requests now poll every minute throughout foreground browsing,
including Home. Idle polling and failure backoff are unchanged; Home Refresh
continues to refresh Home content.

Waiting for your library is now **Request accepted**, with the explanation
**No download progress is available yet. We'll let you know when it's ready to
watch.** Stage evidence, quality handling and portable event identities are
unchanged. The plain-English status glossary and exact owner results are saved
in `owner-checklist.md` and the existing Notion feature. Phone copy/cadence is
unchanged by this TV iteration.

The first build was interrupted to include the owner's copy feedback in the
same replacement APK. The final arm64 Alpha **BUILD SUCCESSFUL in 3m** and direct
VPS → Shield in-place installation returned **Success**, exit 0. Saved Alpha
accounts/settings retained. No optional tests/lint/review or device app
navigation/visual checks. Owner notification timing/copy retest remains pending.
Gradle daemon stopped, active Alpha caches/key retained, **58 GB free**. No stable
release, phone/backend deployment or Phase 5 implementation.

## Owner's poster popup redesign — 2026-10-31

Implemented the supplied mockup as a top-center, rounded dark card with the
Requests teal border/glow, left poster, checked status pill, uppercase Orbitron
title, smaller teal year, ready instruction, filled teal Watch now with a white
focus ring and a quieter outlined Dismiss button. Orbitron ExtraBold and its
existing OFL attribution were restored from repository commit `4ae1d95d` for the
popup only, with the name-based font shrinker keep rule. Other app typography
is unchanged. Missing artwork has a title-initial fallback; metadata starts
loading when a pending update can safely be shown.

The owner's follow-up sets the timeout to **one minute**. The thin bottom bar
shows the actual remaining idle time, updates during drawing and resets on
remote input. The prior 700 ms held-OK guard, safe-browsing gates, interrupted
event persistence, focus restoration and acknowledgement on action/dismissal/
visible timeout remain. Existing matching-quality title navigation is retained.
Partial/failed updates use their real labels; grouped updates use a count and
View requests rather than falsely claiming a single title is ready.

Final arm64 Alpha **BUILD SUCCESSFUL in 2m 45s**; direct VPS → Shield in-place
installation returned **Success**, exit 0. Saved Alpha accounts/settings and
active incremental caches/key retained. Gradle daemon stopped, **58 GB free**.
No optional tests/lint/review, app launch, remote navigation, screenshots or
device visual checks. Owner design/timer verification remains pending. No stable
release, backend deployment or Phase 5 work.

## Search submission, checker recovery and download history — 2026-10-31

Owner follow-up: Search submitted from the keyboard left the keyboard open;
notifications still appeared only when opening My Requests; a supplied sequence
showed Trick 'r Treat (2007) Downloading 28% at 00:05, Request accepted at 00:07,
then Ready/popup at 00:15 after returning manually. The interval change alone
was insufficient. These are owner observations, not agent device verification.

- Both shared Search input variants explicitly hide the IME on submission.
  Requests Search exits editing, clears text focus, waits for the submitted
  result set and focuses a freshly reset grid's first item. Typing alone does
  not move focus; changed queries cancel pending handoffs; empty/error results
  return to the usable read-only search field without reopening the keyboard.
  Read-only/disabled text fields no longer count as active editing for chips.
- The foreground worker owns a persistent loop rather than a per-connection
  collector body that can end on a stale client and wait indefinitely for a
  new StateFlow value. Every cycle revalidates/re-captures connection, client,
  account and generation. Connection changes wake it; same-account reconnects
  preserve journal/baseline state. It now checks every minute even with an idle
  known list, to discover requests created elsewhere. Failure backoff and
  throttled SSO probes/recovery are retained; background stops the worker.
- The journal saves observed download evidence per owned request. A queue that
  disappears after observed progress shows **Checking your library**, with no
  stale percentage and no claim that it completed, failed or was not found.
  Genuine availability/failure wins; ready/failed/gone/cancelled requests clear
  the marker. Metadata refresh respects this history, which survives restart.
  Partial TV stages keep their available-season signature; event keys do not
  change. No Room schema change or new backend credentials are involved.
- Sanitized sync diagnostics contain only row/update counts and exception class,
  without titles, IDs, credentials or request/response bodies. No device logs,
  network probes or real requests were collected/sent by the agent.

Arm64 Alpha **BUILD SUCCESSFUL in 2m 56s** and direct VPS → Shield in-place install
returned **Success**, exit 0. Owner must retest Search handoff and notification
delivery from Home; neither is claimed verified by installation. No optional
tests/lint/review, screenshots, app launch or remote navigation. Gradle daemon
stopped, active Alpha caches/key retained, **58 GB free**. No stable release,
phone/backend deployment or Phase 5 work.
