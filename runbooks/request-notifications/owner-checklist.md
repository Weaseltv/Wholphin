# Request notifications: owner checklist for phases 0–4

Prepared 2026-10-31. Check items only after observing them on the relevant device.
The original implementation reviews and automated checks do not replace these
owner checks. This checklist does not authorize deployment or backend changes.

## What is available

| Phase | What it covers | Where to test |
| --- | --- | --- |
| 0 | Discovery of account, status/progress and webhook behavior | Foundation checks below; genuine webhook substitutions remain a rollout check |
| 1–2 | TV My Requests, status, chips and badge | WeaselPlex Alpha on the Shield |
| 3 | Phone My requests and foreground banners | PR #172 build after Check TestFlight / an appropriate Android test build; not included in the Shield APK |
| 4 | Backend webhook/device registry and push worker | PR #243 is undeployed; both process flags default off. Agent-assisted checks after separately authorized rollout |

Phone background/system push notifications require phase 5. Discord cutover is
phase 6. Neither is something to expect from this Shield Alpha or the phase 3
phone changes.

## Phase 0 foundations — observe during ordinary app use

- [ ] Sign in/pair through the normal website account. Requests should connect
  silently, without a separate Jellyfin or Seerr account/password prompt.
- [ ] Use an ordinary member account as well as the owner account, if available.
  My Requests contains only that person's requests. Switching people must not
  expose the previous person's list, badge or notification. Kid profiles keep
  Requests unavailable.
- [ ] Observe a genuine active download if one occurs: progress appears only
  when actual byte counts exist. No bar/ETA is also valid. An empty queue must
  not become a false "not found on an index" or failure message.

Phase 0 discovery is complete. A temporary webhook Test was already checked;
real ready/failed/4K payload substitutions were not proven by that Test.

## Phases 1–2 — Shield Alpha

- [ ] **Default selection:** open Requests → Search from the side menu. Voice
  to search is selected first when voice search is enabled/available.
- [ ] **Submit Search:** press Search on the on-screen keyboard. The keyboard
  closes and the first result is selected when results arrive. An empty result
  or error leaves Search usable; typing alone must not move focus away.
- [ ] **Layout and navigation:** open My Requests; focus Watch now, Details and
  Refresh; move through Ready / In progress / Needs attention and back to the
  first card. Each focused card and its section heading should remain readable,
  including long/wrapped titles. Try returning from Search and a title page.
- [ ] **New movie request:** request a title missing from the library. Success
  opens My Requests with that exact request focused. Reopen the page and check
  its title/poster/year, date and status. A failed submission must not appear as
  a successful request.
- [ ] **TV request:** choose specific seasons. Only those requested seasons
  determine readiness; another season already in the library is insufficient.
  Partial availability shows Some episodes ready; all requested seasons must be
  available before Ready to watch. Add/edit seasons while pending if permitted.
- [ ] **Stages and refresh:** Refresh updates the timestamp/status. Watch a real
  request progress through the stages the server actually supplies: waiting,
  downloading, adding, ready. Approved requests with no queue data can correctly
  remain Request accepted, with an explanation that download progress is not
  available yet. A future movie may show Not out yet.
- [ ] **Watch now / quality:** a ready request opens the correct library title
  and it can play. If you use both HD and 4K, check both independently; the app
  must not substitute the other quality. Details is the fallback when no exact
  library ID is known.
- [ ] **Cancellation:** if an owned request is still pending, Cancel request
  removes that exact request immediately. Approved/completed requests must not
  offer Cancel. An auto-approved account may have no pending request to test.
- [ ] **Ready notification and badge:** leave My Requests and browse Home while
  a new request becomes ready. A chip should appear with Watch now or Details;
  the ready count should also show on the collapsed Requests icon. Opening
  My Requests clears its unread count.
- [ ] **Chip controls:** test its primary action, Dismiss, Back and one minute
  with no input. The countdown bar drains and resets when using the remote.
  Dismiss/Back returns focus to the page. Holding OK as
  the chip appears must not accidentally activate it. Several updates should
  batch rather than stack separate popups.
- [ ] **Safe timing:** while an update is expected, try playback, a dialog,
  typing in Search, the screensaver or the tour. Chips wait for safe browsing;
  interrupting a visible chip must not silently lose that update.
- [ ] **Restart and wake:** dismiss an update, restart Alpha and confirm it
  does not replay. Let another tracked request finish while Alpha is closed;
  reopening should catch up. Sleep/wake and temporary internet loss should
  recover, preserving the last known status instead of inventing failure.
- [ ] **Notifications setting:** Settings → More → Request notifications off
  suppresses chips while My Requests still works. Turn it back on: updates
  suppressed while off should not all flood the screen. The setting is per
  request account.
- [ ] **Failure/decline, when naturally available:** a genuinely failed or
  declined request belongs in Needs attention and can generate an update. Do
  not deliberately break downloads or remove library titles just for this check.

Updates are polled, not instant: while Alpha is open, requests are checked about
once a minute on every page, including when the known list is idle. Library indexing has its
own delay. Old terminal cards are hidden after 30 days unless opened as a
specific request. Existing completed requests should not flood the screen on
initial setup.

## Phase 3 — phones, once the branch build is available

[Draft phone PR #172](https://github.com/Weaseltv/streamyfin/pull/172) still needs
Check TestFlight and owner iPhone approval before merge. Repeat on Android when
its test build is available.

- [ ] Repeat movie/season submission, exact-card navigation/highlight, stages,
  quality-specific Watch now, refresh and owned pending cancellation. Phone
  cancellation includes confirmation.
- [ ] A new update shows one in-app banner during browsing. View requests opens
  My requests; Dismiss closes it. A banner must never change tabs by itself.
- [ ] Playback, a dialog/request sheet, keyboard, sign-in or backgrounding hides
  the banner. Resume catches up; reopening does not replay consumed updates.
- [ ] Switch people and notification preferences: no previous person's request
  data/banner, and no flood after re-enabling notifications. Sign-in continues
  using the website-linked session.
- [ ] The existing Recent requests slide still works; the owner/admin can still
  see its server-wide recent activity and download information there. This is
  separate from the personal My requests list.

These are foreground in-app banners. Lock-screen/background pushes are not
implemented by phase 3.

## Phase 4 — agent-assisted integration after authorized deployment

[Draft backend PR #243](https://github.com/Weaseltv/weaseltv-platform/pull/243)
has not been deployed. Follow its phase4.md rollout order; keep the worker off
until phase 5 client registration/recipient guards and device checks are done.

- [ ] Enable ingestion only in the approved rollout. Seerr Test is accepted,
  stored as ignored and produces no customer notification. Use the frozen
  template, exact Bearer header and event mask 88.
- [ ] Observe authorized genuine movie-ready, requested-TV-season-ready and
  failed/declined payloads, plus 4K when available. Request ID, recipient UUID,
  media and quality must match; unsafe overall-series approval events must not
  announce requested seasons as ready.
- [ ] Verify duplicate events do not create duplicate deliveries, other users
  cannot register/delete someone else's devices, and token rotation/account
  switching prevents delivery to the old binding.
- [ ] Verify delayed events older than 24 hours are not sent, queue outages
  recover without a backlog flood or duplicate unrelated emails, and ambiguous
  sends are not blindly retried.
- [ ] After phase 5 is complete and delivery is explicitly enabled, check real
  owner-device delivery, Expo receipts and removal/disable of invalid tokens.
  Until then, absence of a phone system push is expected.

Leave Discord notifications unchanged through these checks; switching them off
is a separately approved phase 6 action.

For a useful bug report, note the device, request title and quality, selected TV
seasons, action, approximate time and actual result. Screenshots help with
layout issues. Do not include passwords, tokens or webhook secrets.

## TV status labels in plain English

| Status | What makes the app show it |
| --- | --- |
| Waiting for approval | The request service says the request still needs approval. |
| Request accepted | The request is accepted, but there is no confirmed download progress or watchable copy yet. Previously labelled Waiting for your library. |
| Downloading …% | The server reports a queued/active download with enough information to calculate progress. This measures downloading, not the whole request. |
| Adding to your library | The server reports the download finished, but the title is not yet available to watch. |
| Ready to watch | The server confirms the movie, or every requested TV season, is available in the requested quality. |
| Some episodes ready | The server confirms part of the requested TV seasons is available, but not all. |
| Not out yet | The movie has a known release date in the future. |
| Checking | The available information is insufficient to identify a more specific stage. It does not prove that an active search is running. |
| Checking your library | The app previously saw a download, but progress is no longer reported. It is waiting for library availability to be confirmed. |
| Request declined | The request service says approval was refused. |
| Couldn't be added | The request service reports the request failed. |
| Not available | The server marks the title blocked or removed. |

Last known status means a refresh failed and the app kept its previous result.
These are status labels; Ready / In progress / Needs attention are the list's
groups. Ready can include a partly available show, labelled Some episodes ready.

## Owner's first basic-checklist results

Reported 2026-10-31 against the installed heading-fix Alpha:

- Passed: normal startup/account retention, readable headings/titles, movie
  submission, TV-season submission, Watch now/playback, notification setting,
  and closing/reopening without replay.
- Not accepted: status clarity. Beyond the Gates (2019) showed Waiting for your
  library; the owner could not tell what it meant. New TV copy is Request
  accepted with the explanation: "No download progress is available yet. We'll
  let you know when it's ready to watch."
- Notification timing: the owner stayed on Home for two minutes after a title
  finished, refreshed Home and saw no chip. Returning to Requests produced it.
  The old five-minute waiting-request interval and page-entry refresh explain
  that timing. The replacement Alpha checks pending requests every minute while
  browsing any page. Owner retest remains required.
- Not yet checked: notification actions/dismissal and suppression during
  playback/typing. Account switching was not reported.
- No approval to start Phase 5 has been given.

## Follow-up reports after the popup redesign

The owner reported that submitting Search left the keyboard open, notifications
still appeared only after returning to My Requests, and Trick 'r Treat (2007)
showed Downloading 28% at 00:05, Request accepted at 00:07, then Ready to watch
and a popup at 00:15 after manually returning. Those supplied screenshots are
the observations; no agent device inspection was performed. The earlier
one-minute cadence change alone did not resolve notification delivery.

Replacement Alpha changes Search submission to close the keyboard and select
the first result when ready. Read-only fields stop blocking notifications.
The foreground checker re-captures replaced clients/accounts every cycle and
does not park permanently on a superseded session. It checks the idle list
every minute too. Observed download evidence persists across refresh/restart;
if progress disappears before availability is confirmed, the status becomes
Checking your library without an obsolete percentage or a claim of completion.

Retest these three behaviors on the replacement Alpha. Notification delivery
away from Requests is still pending owner confirmation; Phase 5 remains on hold.
