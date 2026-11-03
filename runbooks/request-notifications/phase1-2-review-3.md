# WeaselPlex Phase 1–2: implementation review, round 3 (final)

Reviewer: Claude Opus 5.5 (high), read-only. Date: 2026-10-29. Host: srv1160496.
Worktree: `t3-abd0cfd3`, branch `t3/in-app-request-status-notifications`, uncommitted diff on top of `599dfca4`.

**What I read:**
- the round-2 findings in `/tmp/weaselplex-p12-review-implementation.md`;
- the full current text of `RequestStatusRepository.kt`, `RequestStage.kt`, `RequestChipHost.kt` and `RequestUiState.kt`;
- the diffs for `SeerrServerRepository`, `MainActivity`, `MainContent`, `NavDrawer`, `SeerrRequestsPage`, `ExpandableDiscoverButtons`, the movie and series detail screens and VMs, and `Extensions.tryRequestFocus`;
- the user-switch path in `UserSwitchListener`;
- `docs/request-stages.md` and `docs/request-stages.fixture.json`.

**Not done:** builds, tests, network, server or device calls, edits to the worktree, and subdelegation.

Paths below are relative to `app/src/main/java/com/github/damontecres/wholphin/`.

## Verdict: **AGREE**

All 7 P1 and 13 P2 findings from round 2 are resolved in the code. I found no new P1 or P2. The items under "Non-blocking" below are optional hardening and doc touch-ups. None of them blocks the Alpha handoff.

## P1 resolution

| # | Status | Where / evidence |
|---|---|---|
| P1-1 probe after resume | Fixed | `listedCurrent` is set after a full page loop (`RequestStatusRepository.kt:153`) and is never reset by `runForeground`. The probe condition at `:224` is `401 \|\| 403 && listedCurrent === s.current`. Only a new `CurrentSeerr` (a new sign-in) re-arms "first read". |
| P1-2 chip Back | Fixed | `RequestChipHost.kt:123-126` consumes Back on both KeyDown and KeyUp and closes on KeyUp. `BackHandler` stays as the fallback (`:106`). |
| P1-3 loss on disconnect/switch | Fixed (new semantics) | Nothing is delivered at show time. Only `close()` delivers (`:60-65`), and only Dismiss, activation and the visible 15 s timeout call it. The `!safe` branch only hides (`:67-73`). `RequestEvent.scopeKey` plus `changeStore(requestedKey)` (`:313`, `:321-332`) write to the right store even when the session is null, and publish to `_state` only when it is the same live key. `remember(state.scope)` resets the local chip on an account switch, and the old store keeps the events pending. `docs/request-stages.md` matches ("acknowledged on dismissal, activation or a visible timeout"). |
| P1-4 series request while Pending/Processing | Fixed | `ExpandableDiscoverButtons.kt` shows `request_partial` for TV when the show is PENDING, PROCESSING or has an own non-terminal row. `onClick = requestOnClick`, `enabled = canRequest` (REQUEST permission). The series VM matches an existing request by `is4k`, so a pending set can be edited. |
| P1-5 cancelled row lingers | Fixed | `forgetLocked` (`:334-345`) drops the row, `missing`, baselined, seeded, awaitingBaseline, unread and pending. Repository `cancel()` calls it under the mutex after the DELETE. Both detail VMs call `forget(id, deletingScope)`, where `deletingScope` is captured before the DELETE. Ordering against an in-flight sync is safe, because `forget` waits on the mutex and runs after that sync. |
| P1-6 manager Cancel on Ready | Fixed | Repository `canCancel` checks own + `status == 1` (`:359-362`). Detail `canUserCancelRequest` checks own + PENDING, with REQUEST or MANAGE_REQUESTS. The any-status manager branch is gone. |
| P1-7 badge on collapsed rail | Fixed | The badge is drawn inside the leading-icon `Box` with `align(TopEnd)` (`NavDrawer.kt` leadingContent), so it shows in both states. It has no trailing-content branch and is not focusable. |

## P2 resolution

| # | Status | Notes |
|---|---|---|
| P2-1 hydration failure | Fixed | Prior seasons and stage are kept with `fresh = false` (`:184-187`). Unread keeps non-fresh rows (`:266`). Events are gated on `fresh`. |
| P2-2 deleted/completed failure chip | Fixed | `notify = request.status in 3,4 \|\| request.status != 5 && status == 6` (`RequestStage.kt`). `eventKey()` returns null when `!notify`. Media status 7, and a completed request later blocklisted, never chip. Explicit declined or failed still chips. |
| P2-3 restart missing-row checks | Fixed | `trackedIds = old ∪ baselined ∪ pending ids` (`:190`). A row is confirmed gone only on the second 404, and that drops it from the journal and pending (`:207-211`, `:265-266`). An incomplete sync skips the checks and keeps the prior rows. |
| P2-4 visibility counter | Fixed | `AtomicInteger`, with a floor of 0 (`:75-76`, `:135`). |
| P2-5 empty copy | Fixed (see N2) | "You haven't requested anything yet" only shows after a complete, connected, error-free sync. The empty list focuses the tab first and Refresh second. |
| P2-6 focus restore | Fixed | `restore()` → `restoreFocusedChild()`, else `pageFocus` (a focus group). It runs only when `chipFocused`. `tryRequestFocus` now returns the real Boolean from `requestFocus()`, so the 3-frame retry is meaningful. |
| P2-7 stale non-Success | Fixed | The identity is checked both before and under the mutex (`:81-83`). |
| P2-8 held OK | Fixed | Repeats are consumed without touching `validPress` (`:129`). |
| P2-9 Watch clears all | Fixed | `markOpened(single?.request?.id)`. My Requests clears everything through `setVisible`. |
| P2-10 quality edge cases | Fixed | Both detail screens cancel with HD preferred, then 4K, using the same own+pending predicate. The stage label prefers a non-terminal HD row. |
| P2-11 corrupt store | Fixed | `readStore` catches decode errors and rethrows Cancellation. It is the only read path. |
| P2-12 docs/fixture | Fixed (see N5) | The table, fixture cases and `eventExamples` sequences (failed→ready, partial transitions) are present. |
| P2-13 hydration cost | Fixed | The detail GET is skipped only when the row is fresh and READY, and its request `updatedAt`, media `updatedAt`, `status` and `status4k` are unchanged and its seasons are known. |

I also checked that a user switch doesn't leak the previous profile's list or badge. `UserSwitchListener` calls `seerrServerRepository.clear()`, which emits NotConfigured, and the collector resets to `RequestsState()`. If that emission is conflated away, a different scope key still resets in `runForeground`. The chip is also gated on `ownerRowId`.

## Non-blocking (optional; none required for AGREE)

- **N1. A just-dismissed chip can come back if Dismiss lands during a sync.**
  - **How it happens.** `close()` clears `events` at once, but the store write waits for the mutex. `sync()` holds the mutex across network I/O. Meanwhile the `LaunchedEffect` restarts with the stale `candidates` and re-shows the same events after 500 ms. Once the write lands, the third branch (`RequestChipHost.kt:83`) only compares item event keys, not pending, so the re-shown chip stays up until it is dismissed again or times out. Nothing is lost.
  - **Most likely trigger.** A chip that was hidden by an app stop re-shows on resume while that resume's first sync is still running.
  - **Fix.** Keep a local `val acked = remember(state.scope) { mutableStateListOf<String>() }`. Add the keys in `close()` and filter them out of `candidates`. Alternatively, update `_state.notifications` before taking the lock.
- **N2.** Before the first sync starts (`lastUpdated == null`, `!loading`, `error == null`), the empty list says "Your requests couldn't be loaded right now." "Loading your requests…" would be more accurate in that state. Keep the "couldn't be loaded" text for `error != null` or `!connected`.
- **N3.** Repository `canCancel` requires `REQUEST`. The detail page allows `REQUEST || MANAGE_REQUESTS`. A manager without the REQUEST bit would see Cancel on the detail page but not on My Requests. This is cosmetic: admins pass both checks through ADMIN. Align it to the detail predicate.
- **N4.** When `firstFocus` fails three frames in a row (`RequestChipHost.kt:93-96`), the events are cleared without delivery, and the first effect immediately re-arms after 500 ms. If focus ever can't be taken, that would flicker in a loop. I found no focus trap in the app today (`grep` shows only directional `Cancel`s). A small backoff, or a retry limit per key set, would close it.
- **N5. Doc drift.**
  - `request-stages.md` still says "A failed season refresh shows Checking". It now keeps the last known stage, marked "Last known status".
  - It doesn't state the `notify = false` rule for removals after completion or deletions. That rule matters for the Phase 3 portable keys.
  - Consider adding a fixture case for completed + media status 7 that produces no event.
- **N6.** The `fallbackFocus` parameter of `RequestChipHost` is now unused. It is harmless, so either remove it or leave it.

## Carry-forward notes (unchanged from round 2)

- An Expired session that doesn't use QC/API key never reconnects automatically. Mention that in the PR.
- Live TV, 4K and queue-byte paths are still unobserved against real data (Phase 0 limits). The owner's Alpha check covers them.
- Seerr 3.5.1 versus 3.5.0 has no changes in the request, media, subscriber or auth paths, per the parent's public read-only check. I did not repeat that check.
