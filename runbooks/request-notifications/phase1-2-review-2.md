# WeaselPlex Phase 1–2: implementation review, round 2

Reviewer: Claude Opus 5.5 (high), read-only. Date: 2026-10-29. Host: srv1160496.
Worktree: `t3-abd0cfd3`, branch `t3/in-app-request-status-notifications`, uncommitted diff on top of `599dfca4`.

**Inputs:**
- `AGENTS.md`
- `plan-final.md` §4–5
- `runbooks/request-notifications/phase0-findings.md`
- round-1 `/tmp/weaselplex-p12-review-scope.md`
- every tracked diff, plus the four new Kotlin files

**Not done:** builds, tests, network or server calls, device checks, edits, subdelegation. I didn't look for compile errors. The parent is handling those.

Paths below are relative to `app/src/main/java/com/github/damontecres/wholphin/`. **P1** means it must be fixed before the Alpha is handed over, or the feature misbehaves in an ordinary situation. **P2** means fix it in this batch, but it is not a release-day breakage.

## Round-1 items confirmed fixed

- Captured client/session generation.
- `Expired` keeps the identity, with a compare-and-set against the exact `Success`.
- Only `QUICK_CONNECT` / `API_KEY` reconnect.
- Rows are filtered by owner.
- A missing row gets a detail check, and is dropped only after a second 404.
- The probe has its own clock and doesn't call `recordAttempt`.
- One-shot `initialTab` goes through the assisted factory.
- The `Discover` data-class highlight is handled, and `navigateToFromDrawer` prevents duplicate content keys.
- The series PUT matches `is4k`.
- The browse `jellyfinId` helper is untouched, and there is a separate `jellyfinId(is4k)`.
- The baseline is persisted per scope, with `awaitingBaseline` and `seeded`.
- The toggle defaults to on through the KV default (not proto3).
- Store writes go through a single mutex.
- The central text-field tracker, the `BasicDialog` count and the window-focus gate are in place.
- The key-press guard uses `downTime`.
- Stage order and the Done/Active sets follow Phase 0. "Waiting for your library" is neutral.

**`sort=modified` and an explicit `desc`:** not necessary. Seerr 3.5's default direction is already descending (Phase 0 §3). A row that moves between pages mid-sync is missed only for that sync. If it was already tracked, the missing-row detail check (`RequestStatusRepository.kt:178-193`) picks it up. If it is new, the next sync finds it. Leave it as it is.

---

## P1: blocking

### P1-1. The probe flag is reset on every activity start, so an expired session after resume is never detected
`services/RequestStatusRepository.kt:109`, `:149`, `:205`.

**Problem:**
- `runForeground()` runs on every `STARTED`. Each run creates a new `Session` and sets `successfulList = false`.
- On resume the TV does **not** sign in again: `reconnectIfNeeded` returns early on `Success`.
- So when a session dies while the TV sits in the background (the usual case on an always-on Shield), the first list read after resume returns 403 and is treated as "first read, not expiry".
- Every later read in that `Session` also returns 403, so `successfulList` never becomes true. The probe never runs, and Requests stays at "Requests unavailable" until the process is killed.

**Fix:** tie the flag to the sign-in instance, not to the poll session.
```kotlin
private var listedCurrent: CurrentSeerr? = null      // replaces successfulList
// runForeground: delete `successfulList = false`
// sync, after the page loop:   listedCurrent = s.current
// catch:  if (code == 401 || code == 403 && listedCurrent === s.current) probeSession(s)
```
A new sign-in creates a new `CurrentSeerr` in `set()`, so "first read after login" still doesn't probe. That keeps the plan's TV rule.

### P1-2. Chip Back loses to page `BackHandler`s registered later
`ui/components/RequestChipHost.kt:100`.

**Problem:**
- `OnBackPressedDispatcher` gives priority to the most recently *added* callback.
- `NavDrawer` (and its Home `BackHandler` at `ui/nav/NavDrawer.kt:322`) is composed per `NavEntry`, so it is added after the root chip host on every navigation.
- On Home with the chip focused, Back opens the drawer and moves focus there. The chip stays up, unfocused, until it times out.
- On other pages, Back pops the page instead of dismissing the chip.

**Fix:** consume Back in the chip's own key handler. On the Shield (API 30), key events reach Compose before the dispatcher. Keep the `BackHandler` as a fallback.
```kotlin
.onPreviewKeyEvent { event ->
    lastInput = SystemClock.uptimeMillis()
    if (event.key == Key.Back) { if (event.type == KeyEventType.KeyUp) close(); return@onPreviewKeyEvent true }
    ...
```
This handler only receives events while the chip has focus, so "Back consumed only while focused" still holds.

### P1-3. A chip that is marked delivered when shown is lost if the connection drops or the account switches
`RequestChipHost.kt:83` (delivered at show), `:68-73` (requeue); `RequestStatusRepository.kt:283-293` (`changeStore`).

**Problem:**
- `changeStore` captures `session` and silently drops the write if that session is null or no longer current.
- On `Expired` (after the probe) or `Error`, the `init` collector sets `session = null` *before* it publishes `connected = false`. The host's `requeue(events)` is then dropped, and events already written as delivered are never shown.
- On an account switch, `remember(state.scope)` resets `events` with no requeue at all. The old scope keeps them as delivered. Plan §5.3/S6 says they stay pending.
- Smaller case: a Dismiss pressed just after a reconnect that produced a new `Success` is dropped, so the chip comes back.

**Fix (simplest):** don't mark delivered at show time.
1. Delete `repository.delivered(events)` at `:83`.
2. In the `!safe` branch, just hide (`events = emptyList(); displayed = false`, restore only if `chipFocused`). There is nothing to requeue, so `requeue()` can be deleted.
3. `close()` keeps `repository.delivered(events)`. Only user actions and a visible timeout call it, and those can only happen while the chip is safe, so connected.
4. Also make `changeStore` address the store by key instead of by session identity:
```kotlin
private fun changeStore(update: (RequestNotificationStore) -> RequestNotificationStore): Job {
    val key = session?.key ?: _state.value.scope ?: return Job().apply { complete() }
    return scope.launch { mutex.withLock {
        val live = session?.takeIf { it.key == key && current(it) }
        val base = if (live != null) _state.value.notifications else keyValue.get(key, RequestNotificationStore()).first()
        val next = update(base)
        if (next != base) keyValue.save(key, next)
        if (live != null && current(live)) _state.update { it.copy(notifications = next) }
    } }
}
```
This also stops the Settings toggle, `markOpened` and Dismiss being silently ignored while the connection is reconnecting.

### P1-4. Series regression: no way to request more seasons, or edit a pending season set, while the show is PENDING or PROCESSING
`ui/detail/discover/ExpandableDiscoverButtons.kt:121-124`; `DiscoverSeriesDetails.kt:285` still passes `pendingOnClick = requestOnClick`, but nothing calls it now.

**Problem:**
- Before this change, Pending on a series reopened the season dialog.
- Now it always opens My Requests.
- The only other Request button appears for `PARTIALLY_AVAILABLE`. So a user whose season 1 is processing can't ask for season 2, and can't change their own pending season set. Phase 0 §4 confirms ordinary users may edit a pending set.

**Fix:** keep the new Pending → My Requests behaviour, and add an explicit request button for TV in those states:
```kotlin
if (availability == SeerrAvailability.PARTIALLY_AVAILABLE ||
    requestMediaType == "tv" && (availability == SeerrAvailability.PENDING || availability == SeerrAvailability.PROCESSING)) {
    item("request_partial") { ExpandableFaButton(title = R.string.request, ..., onClick = {
        if (availability == SeerrAvailability.PARTIALLY_AVAILABLE) requestOnClick() else pendingOnClick() }, enabled = true, ...) }
}
```
Also remove the old `enabled = availability == PARTIALLY_AVAILABLE`.

### P1-5. A cancelled request stays on My Requests as "Last known status" for another cycle
`RequestStatusRepository.kt:297-303`; `DiscoverMovieViewModel.kt` / `DiscoverSeriesViewModel.kt` `cancelRequest` → `requestStatusRepository.refresh()`.

**Problem:**
- After a successful DELETE, the next sync doesn't see the row. The missing-row path gets a 404, sets `missing = 1` and keeps the row (`fresh = false`).
- The row only disappears after a *second* sync, which can be up to 5 minutes away when My Requests isn't visible.
- Round-1 M9 said a row you cancelled yourself is removed immediately.

**Fix:**
- Add `suspend fun forget(id: Int)` under the mutex:
  - drop the row from `_state.items` and from `missing`;
  - remove its `pending` and `unread` entries, `seeded` and `awaitingBaseline` included;
  - save the store.
- Call it after a successful delete in `cancel()`, and in both detail VMs (instead of just `refresh()`), then `refresh()`.

### P1-6. Admin accounts get "Cancel request" on Ready and completed rows
`RequestStatusRepository.kt:304-307`.

**Problem:**
- `canCancel` keeps the `MANAGE_REQUESTS` any-status branch that this diff removed from the detail page's `canUserCancelRequest`.
- The owner's own account (a manager) sees Cancel on every row, including Ready ones. Pressing it deletes a completed request.

**Fix:**
```kotlin
return request.requestedBy?.id == user.id && request.status == RequestStatus.PENDING.status &&
    (user.hasPermission(SeerrPermission.REQUEST) || user.hasPermission(SeerrPermission.MANAGE_REQUESTS))
```

### P1-7. The Requests badge doesn't show on the collapsed rail
`ui/nav/NavDrawer.kt:884-885`.

**Problem:**
- The count is in `trailingContent`. tv-material3 `NavigationDrawerItem` only draws trailing content while the drawer is open.
- On the collapsed rail, which is where the customer is most of the time, the badge is invisible. It is supposed to be the fallback for every missed chip.

**Fix:** draw it as an overlay in the leading icon `Box` (`:842-871`), visible in both states. For example:
```kotlin
if (library is NavDrawerItem.Discover && requestState.badgeCount > 0)
    Text(requestState.badgeCount.coerceAtMost(9).toString(), fontSize = 10.sp, color = Color.Black,
         modifier = Modifier.align(Alignment.TopEnd).background(Color(0xFF00F2D6), CircleShape).padding(horizontal = 4.dp))
```
Then remove the trailing-content branch. It is decoration only, not focusable, so it adds no behaviour the owner didn't ask for.

---

## P2: fix in this batch

### P2-1. A failed TV hydration throws away the last good snapshot and clears the badge
`RequestStatusRepository.kt:171-176`, `:246`.

**Problem:**
- When a detail read fails, the row is rebuilt from the list row, which has no `media.seasons`, with stage `CHECKING`.
- The `unread` filter at `:246` (`stage.ready`) then drops it, so one transient error lowers the badge. A Ready card shows "Checking".
- Phase 0 §2 says to keep the last successful snapshot.

**Fix:**
```kotlin
if (seasons == null) { detailFailures.add(row.id)
    val keep = prior?.request?.media?.seasons
    row.copy(media = row.media?.copy(seasons = keep)) } ...
val stage = if (fresh) deriveRequestStage(hydrated, ...) else prior?.stage ?: RequestStage(CHECKING, "Checking", detail = ...)
```
- Events stay gated on `fresh`, which is already done.
- In `trackEvents`, filter `unread` by `byId[it]?.let { !it.fresh || it.stage.ready } == true`.

### P2-2. Media DELETED on a completed request sends a "couldn't add" notification
`services/RequestStage.kt:42-47`, `:86-93`.

**Problem:**
- Rule 1 maps media status 7 to `FAILED`, which becomes a `failed` event and a chip.
- A title watched months ago and later removed from the library ("Not available") then produces a failure notification.
- It also breaks the portable-key contract. The server only sends `failed` for MEDIA_FAILED / MEDIA_DECLINED (Phase 0 §4), so TV and phones would disagree on the same key.

**Fix:**
- Keep the red "Not available" stage in the list.
- Make `eventKey()` return null when the request was already completed (`request.status == 5`), or when the failure comes only from media status 7.
- Simplest is a flag on `RequestStage`, `notify: Boolean = true`, set to false in that branch.

### P2-3. After a restart, rows that disappear are never checked, and their pending events are dropped
`RequestStatusRepository.kt:178`, `:245`.

**Problem:**
- The missing-row check only iterates the in-memory `old`, which is empty after process death.
- A request blocklisted while the app was killed (hidden by `filter=all`) silently disappears: no "Not available" stage and no failure chip.
- `pending.removeAll { it.requestId !in byId }` also discards any pending chip for it.
- `store.baselined` is persisted but never read.

**Fix:**
- After a complete list sync, take the ids in `store.baselined ∪ old.keys` that are not in `rows` as candidates for the same single detail check, adding the detail item when it is owned.
- Keep the two-404 removal: track the counts in `missing`, and drop the id from `baselined` on the second 404.
- Only remove pending events for ids that have been confirmed gone.

### P2-4. `myRequestsVisible` is a boolean, so overlapping pages end it at false
`ui/discover/SeerrRequestsPage.kt:64`; `RequestStatusRepository.kt:73`, `:131`.

**Problem:**
- Chip **Details/View** while already on My Requests composes the new `Discover(1, id)` entry first (true), then disposes the old one (false).
- Polling then falls to 5 minutes, and new Ready items are counted as unread while the user is looking at them.

**Fix:** use an `AtomicInteger` counter. `setVisible(true)` increments, refreshes and calls `markOpened()`. `false` decrements, with a floor of 0. Use `visibleCount > 0` where the boolean is read now.

### P2-5. "You haven't requested anything yet" shows before the first sync and on errors
`SeerrRequestsPage.kt:89`.

**Problem:** it is shown whenever `items.isEmpty() && !state.loading`. That includes before the first sync, while disconnected, and after a failed sync. A request that failed to load is reported as "you have none".

**Fix:** show that text only when `state.lastUpdated != null && state.error == null && state.connected`. Otherwise show neutral copy: "Your requests couldn't be loaded right now." or "Loading your requests…".
- Related, `:79`: when the list is empty, focus `focusRequesterOnEmpty` (the tab), as the old page did. Grabbing the Refresh button pulls focus off the tab row as soon as the tab is selected.

### P2-6. Chip focus restore details
`RequestChipHost.kt:57`, `:72`, `:85`.

- **Fallback target.** `fallbackFocus` is attached to `ApplicationContent`'s root modifier, which has no focus target. Its `requestFocus()` throws, `tryRequestFocus` swallows that, and nothing gets focus. Fall back to `pageFocus.tryRequestFocus()` instead: a `focusGroup` hands focus to its first child.
- **Only restore when the chip had focus.** In the `!safe` branch (`:72`) and the stale-event branch (`:85`), call `restore()` only when `chipFocused`. If the user has D-padded out of the chip into the page, hiding it must not move their focus.

### P2-7. A stale non-`Success` emission can null the new session
`RequestStatusRepository.kt:77-85`.

**Problem:**
- The `init` collector (Default dispatcher) and `runForeground` (IO) both react to `connection`.
- If the collector processes an old `Expired`/`Error` value after `runForeground` has already installed the session for the new `Success`, it sets `session = null`. The loop then exits and doesn't restart until the next status change.

**Fix:** at the start of the collector body, add `if (connections.connection.value is SeerrConnectionStatus.Success) return@collect`. Do the `generation++` and `session = null` under `mutex.withLock`.

### P2-8. A held OK key cancels a valid click
`RequestChipHost.kt:119`.

**Problem:** every KeyDown recomputes `validPress`. Repeats have `repeatCount > 0`, so they set it false, and the KeyUp is consumed. A slightly long press does nothing.

**Fix:** only assign `validPress` when `repeatCount == 0`. Always consume repeats (`return true`) without resetting it.

### P2-9. Watch now clears every unread badge
`RequestChipHost.kt:131`.

**Problem:** `markOpened()` empties `unread`, even for a single Watch press.

**Fix:**
- Watch: `changeStore { it.copy(unread = it.unread - single.request.id) }`.
- Details/View navigate to My Requests, which clears the badge itself through `setVisible`.

### P2-10. Detail-page cancel and stage label pick the wrong request in quality edge cases
`DiscoverMovieDetails.kt:127`, `DiscoverSeriesDetails.kt:151`, `ExpandableDiscoverButtons.kt:49`.

- **Cancel.** Cancel is enabled by `canUserCancelRequest` for any own pending request, but the click only looks for `is4k != true`. A user with only a 4K pending request sees Cancel, and it does nothing. Use `firstOrNull { own && pending && is4k != true } ?: firstOrNull { own && pending }`, the same predicate in both places.
- **Stage label.** `ownRequest` takes the first match by `updatedAt`. Prefer a non-terminal HD row: `items.filter { match }.let { l -> l.firstOrNull { !it.stage.terminal && it.request.is4k != true } ?: l.firstOrNull { !it.stage.terminal } ?: l.firstOrNull() }`.

### P2-11. A corrupt store value crashes the activity scope
`RequestStatusRepository.kt:105`.

**Problem:** `keyValue.get(...).first()` decodes without `runCatching`. It runs inside `runForeground`, under `lifecycleScope`. A decode exception there is uncaught.

**Fix:** `runCatching { keyValue.get(k, RequestNotificationStore()).first() }.getOrDefault(RequestNotificationStore())`.

### P2-12. Plan deliverable missing: stage table and fixture
Plan §4 says the TV repo holds the truth table and a JSON fixture of cases, and the phone port in Phase 3 uses the same fixture. Neither exists.

**Fix:** add `docs/request-stages.md`, with the rules in the order the code applies them (including the Phase 0 "Waiting for your library" change), and `docs/request-stages.fixture.json`. The fixture needs these cases:
- empty queue;
- completed row with size > 0;
- 4K season;
- empty R;
- unknown queue status;
- failed → retried → ready;
- season P→A;
- partial → full.

This is documentation and data only. No test run is implied (owner policy).

### P2-13. Hydration cost, optional
`RequestStatusRepository.kt:155`. Every TV request, including long-finished ones, gets a detail GET on every sync. That is every 60 s while My Requests is open.

**Fix:** reuse the prior seasons, without a detail call, for rows that are `fresh`, whose `stage.kind == READY` and whose `updatedAt` is unchanged. Readiness can't regress into a notifiable event (see P2-2).

---

## Notes, not changes

- **Expired with a non-QC/API_KEY method never reconnects, not even on `onStart`** (`SeerrServerRepository.kt:277`). That is stricter than round-1 M4, which left it to the normal start path. It is a safe choice for fail2ban, and pinned builds provision `QUICK_CONNECT`, so customers aren't affected. Keep it, and mention it in the PR.
- **Compile items I noticed in passing** (the parent owns these):
  - `saveFocusedChild` / `restoreFocusedChild` may need `@OptIn(ExperimentalComposeUiApi::class)`, depending on the Compose version.
  - The `it.episode.seasonNumber` smart cast in `RequestStage.kt:59` relies on K2 smart-casting after `it.episode?.seasonNumber == null ||`. If it complains, use `it.episode?.seasonNumber.let { n -> n == null || n in remaining }`.
- **Copy:** customer strings say Requests/WeaselPlex, never Seerr/Jellyfin, and use US spelling. Good. Plan rule 1 wording for FAILED is "Something went wrong adding this"; the code says "Couldn't be added". Either is fine.
- **Phase 0 limits still apply:** no live TV, 4K or queue-byte observation. The progress bar and ETA paths are untested against real data. The owner's Alpha check covers that.

## Priority order
P1-1 → P1-3 → P1-2 → P1-4 → P1-5 → P1-6 → P1-7, then P2-1 → P2-3 → P2-2 → P2-4 → P2-6 → P2-7, then the rest.
