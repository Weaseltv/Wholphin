# WeaselPlex Phase 1–2 (TV request status + ready chip): architecture review, round 1

Reviewer: Claude Opus 5.5 (high), read-only. Date: 2026-10-29. Host: srv1160496.
Inputs: `AGENTS.md`, `plan-final.md` (§4–5, §8), `runbooks/request-notifications/phase0-findings.md`, `plan-phase0-addendum.md`. I also read the current integration points in worktree `t3-abd0cfd3` at `599dfca4`, plus the parent's uncommitted `seerr-api.yml` diff.
Not done: edits, builds, tests, live server calls, device checks, subdelegation.

Phase 0 overrides v4 in these places, so build to Phase 0:
- Rules 9–10 become a neutral **"Waiting for your library"** stage.
- Done = `completed`; Active = `queued` / `downloading`.
- TV season state comes from `/request/{id}` detail. The list does not include `media.seasons`.
- `filter=all` omits blocklisted rows. Handle rows that disappear as described below.

Severity: **[M]** = must handle or it will be a bug. **[S]** = strong recommendation.

---

## 1. Session, account isolation and expiry (`services/SeerrServerRepository.kt`)

**[M1] `SeerrApi.update()` creates a new `SeerrApiClient` with an empty cookie jar on every connect** (`SeerrApi.kt:24-29`).
- A sync that reads `seerrApi.api` lazily can read page 1 from the old client and page 2 from the new one.
- A late 403 from a stale client could then trigger a probe or `Expired` against the fresh session.
- **Fix:** capture `val client = seerrApi.api` and the `Success` instance (`conn`) once per sync and use them for every list, detail and probe call in that sync.
- Before committing results, check `seerrApi.api === client && connection.value === conn`. If either changed, discard the results.
- That pair is the generation token.

**[M2] `current`, `currentUserId` and `active` map anything other than `Success` to null/false** (`:55-67`). If you add `Expired` naively, two things break:
- (a) `active` turns false, so `NavDrawerService` (`:71`) drops the Requests item, and with it the badge, during every reconnect.
- (b) The repository sees `currentUserId == null` and treats it as an account switch: it wipes state and chips and may re-baseline.
- **Fix:** key the repository on an explicit `AccountKey(serverUrl, seerrUserId, jellyfinUserRowId)`, taken only from `Success`.
- Treat `Expired`, `Error` and `NotConfigured` as **paused**: keep the in-memory state, hide chips, persist nothing new.
- Only a `Success` with a *different* key counts as an account switch. A `Success` with the same key after a re-login is not a switch: no re-baseline.
- Either include `Expired` in `active`, or hold the drawer item steady some other way.

**[M3] Go to `Expired` with a compare-and-set against the exact `Success` that produced the 403**, e.g. `_connection.compareAndSet(conn, Expired(conn.current.server, conn.current.user))`.
- Do **not** call `seerrApi.update("", null)`; `error()` does that today.
- `connectForUser` (`:185`) then passes its `Success` short-circuit. Since the last successful attempt set `lastAttemptAt = null`, `mayRetry` allows exactly one attempt.
- A concurrent `onStart` → `reconnectIfNeeded` call serializes on `connectMutex`, so there is no double strike. Verify that it stays that way.
- Add `Expired` to the exhaustive `when`s: `removeServerForCurrentUser` (`:339`) and `PreferencesContent.kt:506-537`.

**[M4] No stale saved credentials.**
- In pinned builds, `connectForUser` re-mints the session through the stored row's `authMethod`. Provisioned rows are `QUICK_CONNECT`, which reuses the Jellyfin session the website SSO created. That is fine.
- A legacy row stored as `JELLYFIN` or `LOCAL` with a saved password would POST `/auth/jellyfin` with stale credentials, which is a fail2ban strike.
- **For the expiry-driven reconnect only:** auto-reconnect when `authMethod` is `QUICK_CONNECT` (or `API_KEY`). Otherwise mark the session Requests-unavailable and leave it to the normal `onStart` path.
- Never add a login prompt to My Requests. NotConfigured/Error → "Requests are unavailable right now", with no sign-in UI.

**[M5] Probe rules.**
- Probe on 401 from anything, or on 403 from the **list** endpoint only after a successful list read **on this same client instance**. Hold that flag per client, not per process.
- Use `client.usersApi.authMeGet()`.
- Keep the probe's own `lastProbeAt` (15 minutes) in the repository. Never call `recordAttempt`.
- Status codes come from `ClientException.statusCode` (see `SeerrReconnectPolicy.isRefusal`).
- Count only 401/403 as a dead session. Timeouts, 5xx and `ServerException` keep the session and go to backoff.
- Never probe on 403 from detail, cancel or metadata calls.

**[S6] Account switch.** `UserSwitchListener` calls `clear()` (→ NotConfigured) and then `connectForUser`.
- On NotConfigured: cancel in-flight work by bumping the generation, hide the visible chip (it stays *pending* in that scope's store), and clear the in-memory list.
- The persistent store is per scope, so switching back restores that account's pending items. That is intended.

## 2. Sync (`RequestStatusRepository`, `@Singleton`)

**[M7] Paging order.** `sort=modified` (`updatedAt` desc) is not stable while you page.
- If a row on page 2+ is modified mid-sync, it jumps to page 1, which you have already read. It is **missed** for that sync, and the row at the boundary is read twice.
- Either use `sort=added&sortDirection=desc`, which only produces duplicates and new rows that are picked up next sync, or treat "absent from a complete sync" as *unverified*, never as removed (see M9). I'd do both.
- Paging loop:
  - Stop when `page >= pages`, or when a page returns fewer than `take` rows (guards a missing `pageInfo`).
  - Dedupe by `id`.
  - Hard cap at 1,000 rows, then set `syncComplete=false`.
- Owner filter: drop a row only if `requestedBy?.id` is **non-null and ≠ me**, so a seeded POST response with a null `requestedBy` survives.

**[M8] TV season hydration** (Phase 0 §2).
- For every tracked TV row, displayed or not, call `GET /request/{id}` each sync. Concurrency is at most 4 with a `Semaphore`; dedupe by media id.
- Hydration uses the same client and generation guard as the list.
- If hydration fails, keep the last snapshot and mark the row `stale`: its stage becomes Checking, and **no new event key may be generated from it**.
- Read `media.seasons[].status` or `status4k` by `request.is4k`. **Never** read `request.seasons[].status`.
- R = `request.seasons[].seasonNumber`. The spec diff correctly retypes it to `SeasonRequest`.
- A small optimization: skip detail for TV requests whose stage is already terminal (Ready delivered, or declined/failed) and whose `updatedAt` hasn't changed.

**[M9] Rows that disappear.** A row that was tracked and is absent from a *complete* list sync:
- Call its `/request/{id}` once to tell blocklisted from deleted.
- Remove it only after a second 404 on a later successful sync.
- Never create a `failed` event because a row disappeared.
- A row you cancelled yourself (§4) is removed immediately and skips this check.

**[M10] Generated number types.** In the generated `DownloadingItem`, `size` → **`propertySize: BigDecimal?`** and `sizeLeft: BigDecimal?` (both `@Contextual`).
- Convert with `toDouble()` and require `isFinite() && size > 0 && 0 <= sizeLeft <= size`.
- Choose `downloadStatus` or `downloadStatus4k` by `is4k`.
- TV Q = rows whose `episode?.seasonNumber ∈ R`, plus rows with no episode.
- Unknown status strings → Checking. Compare case-insensitively.
- `estimatedCompletionTime`: show it only if it parses and is in the future.

**[S11] Stage engine.** Make it a pure `deriveRequestStage(request, media, now)`.
- Rule order: 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8 → "Waiting for your library" → 11.
- An empty or unknown R skips rules 2–3 but still lets 1 and 4 match.
- Partial: the secondary stage is computed over the seasons not yet available.
- Put it in the `docs/request-stages.md` + JSON fixture that the plan requires. Tests only if the owner asks.

**[S12] Metadata** (title, poster, year, movie release dates).
- Cache by `(mediaType, tmdbId)` and fetch lazily for visible rows *and* for pending chip identities, which need the title.
- A failure affects only that row.
- The current History VM fetches `/movie|/tv` for every row before it shows anything (`SeerrRequestsPage.kt:68-117`). Replace it, don't extend it.
- Its `connection.onEach { user -> if (user != null) … }` always calls `requestGet()`, because `connection` is never null. With no `requestedBy`, a MANAGE_REQUESTS account gets everyone's requests.

**[S13] Writes.** `KeyValueService.save` overwrites the value. The store's `get().first()` → `save()` isn't atomic, so the poller and a chip dismissal can race.
- Add a read-modify-write helper inside a single `dataStore.updateData {}`, or serialize all store writes behind one `Mutex`.
- Write only when something changed. A 60 s poll would otherwise rewrite the shared Preferences file every minute.
- Wrap decoding in `runCatching`. A corrupt value must not crash the flow.
- Bound `delivered`, e.g. by dropping keys whose request id hasn't been seen for 60 days.

## 3. Lifecycle polling

**[S14] Location and loop.** Put it in `MainActivity` next to `startPeriodicUpdateCheck` (`:357`), using `repeatOnLifecycle(STARTED)`.
- Use `connection.collectLatest { if (it is Success) loop() }` so the loop restarts after re-login.
- Use one single-flight `Mutex` for sync.
- "Refresh now" requests (submit, resume, cancel) go through a conflated `Channel` that the loop waits on with `select { delay(interval); channel.onReceive }`.
- Intervals:
  - 60 s while My Requests is visible or anything is in Downloading/Adding.
  - 5 min while anything is non-terminal.
  - 30 min otherwise.
  - On errors, back off 1 → 2 → 5 → 10 min.
- Track "My Requests visible" with a **counter**, not a boolean, through a `DisposableEffect` gated on the page lifecycle. During the NavDisplay transition, the incoming and outgoing entries overlap.

## 4. My Requests, navigation and detail page

**[M15] `Destination.Discover` changes from `data object` to `data class(initialTab: Int? = null, focusRequestId: Int? = null)`.** Things that break:
- `NavDrawer.kt:197/213` uses `asDestinations.indexOf(key)` equality. `Discover(1, 42)` will never highlight Requests. Map with `is Destination.Discover`.
- `NavDrawer.kt:163` and `DestinationContent.kt:387` use `Destination.Discover ->`, which must become `is Destination.Discover ->`. Pass both arguments into `DiscoverPage`.
- `ApplicationContent.kt:100` uses `contentKey = "${key}_…"` from `toString()`. **Two equal `Discover(1, 42)` entries in the back stack collide.** Example: My Requests → detail → edit the same pending TV request (PUT returns the same id) → navigate again. NavDisplay/SaveableStateHolder then throws on the duplicate key.
- **Fix:** navigate after a submit with a stack reset: `navigateToFromDrawer(Discover(1, id))`, or pop back to an existing `Discover` entry and replace it. Then the stack is always `[Home, Discover(…)]` and the drawer highlight matches.

**[M16] `initialTab` must be one-shot and must not lose the race with `TabViewModel.init`.**
- `init` sets the remembered tab asynchronously on IO (`TabbedPage.kt:66-83`). If `initialTab` is set from the composable, init can overwrite it.
- **Fix:** pass `initialTab` through `TabViewModel.Factory.create(itemId, tabCount, initialTab)` and use `initialTab ?: remembered` inside `init`. Don't persist it as the remembered tab.
- The VM is scoped to the nav entry (`rememberViewModelStoreNavEntryDecorator`, with a distinct contentKey per arguments), so an ordinary drawer open still uses the remembered tab.
- Consume `focusRequestId` once, with a `rememberSaveable` "consumed" flag in that entry. Coming back from a detail page must not re-scroll.

**[S17] Focusing the new request.** This is why seeding matters: the seeded row exists before any sync.
- Sections are Ready, In progress and Needs attention. Phase 0 removed "Still looking", so Needs attention holds only Couldn't be added. "Waiting for your library" goes under In progress. Compute the card's section and index.
- `scrollToItem` / `bringIntoView` first, then request focus *after* the item is composed: `LaunchedEffect(target, layoutReady)` + `withFrameNanos` + `tryRequestFocus()`. Fall back to the first card.
- Empty state is the neutral message, with focus on the tab (`focusRequesterOnEmpty`).

**[M18] Submit flows.**
- `requestPost` returns `MediaRequest` (201) and `requestRequestIdPut` returns `MediaRequest` (200). Capture them.
- Navigate and seed **only on success**. Today both VMs toast "An error occurred" and carry on (`DiscoverMovieViewModel.kt:194-214`, `DiscoverSeriesViewModel.kt:343-385`).
- Seed into the repository **and** add the id to the store's `seededSinceLastSync` (never baselined), then trigger an immediate refresh.
- Do the navigation on Main, after the request dialog's state has closed.
- **Series trap** (`DiscoverSeriesViewModel.kt:335-339`): the PUT-or-POST lookup matches any own PENDING request without `is4k`. A 4K request could therefore rewrite the pending HD request into 4K. Match `is4k == request.is4k` as well.

**[M19] Cancel the exact own pending request.**
- Both VMs' `cancelRequest(id)` ignore `id` and delete `mediaInfo.requests.firstOrNull()` (`Movie…:221-235`, `Series…:395-410`). That can be another user's request: 403 for ordinary users, or a wrong delete for MANAGE_REQUESTS.
- `DiscoverMovieDetails.kt:127` even passes the **TMDB movie id**.
- **Fix:** pick `requests.first { it.requestedBy?.id == me && it.status == PENDING && it.is4k == <shown quality> }` and delete that `id`.
- `canUserCancelRequest` should be computed for that same request. Drop the MANAGE_REQUESTS-any branch for this button.
- In My Requests, offer cancel only when the row is mine and PENDING. A 403 from that call never probes.

**[M20] Watch is quality-specific.** `MediaInfo.jellyfinId` prefers 4K (`SeerrService.kt` at the end of the file). It's used by `createImageUrl`, every `createDiscoverItem` and `goToButtonDiscover`.
- **Don't change that extension's semantics globally**, or browse and images regress.
- Add `fun MediaInfo.jellyfinId(is4k: Boolean): UUID? = (if (is4k) jellyfinMediaId4k else jellyfinMediaId)?.toUUIDOrNull()`. Use it for Watch now, the chip and the detail page's request stage.
- If it's null, the primary action is **Details** (My Requests focused on the request), never a toast.

**[S21] Live detail stage.**
- `ExpandableDiscoverButtons` labels by overall HD `mediaInfo.status`. Drive the label from the repository's own requests that match `(type, tmdbId)`. Prefer a non-terminal request; if there are several (HD + 4K, or several TV requests), take the most recent.
- When the repository isn't synced, fall back to the current label.
- Movie `pendingOnClick` is a no-op. On series, `DiscoverSeriesDetails.kt:283` maps `pendingOnClick = requestOnClick`, which reopens the season dialog.
  - If Pending now opens My Requests, an ordinary user can only edit a pending season set through some other control. Keep an explicit "Request"/edit button while it's pending, or accept and note the loss.
- On a partly-available series with a new pending season request, the first button is "Go to". Show the stage on a secondary line, or it never appears.

**[S22] Failure events.**
- Emit `failed` only for request DECLINED or FAILED, or for media BLOCKLISTED on a request that isn't completed.
- A previously delivered `ready` followed by media **DELETED** (removed from the library later) should not produce "We couldn't add X". Show it in the list; don't notify.

## 5. Event identity, store and baseline

**[M23] The baseline is once per scope, and it is persisted.** Store `baselinedAt` in the scope record.
- If you re-baseline on every process start, every event that happened while the TV was off is swallowed, and those are the main thing a chip is for.
- A row present at baseline whose TV hydration failed is "deferred-baseline": its keys go to delivered on its first complete hydration, not to pending.
- After the baseline, a request first seen later (for example made on the phone or website) is **not** baselined. Its new keys go to pending.
- Seeded own submits are never baselined.

**[S24] Event keys.**
- Format: `v1|<id>|hd|4k|ready|partial|failed|<sig>`.
- Partial `sig`: numerically sorted, comma-joined `n:A` / `n:P` over R.
- Generate keys only from fresh, complete, non-stale state.
- Scope: `weaselplex|<seerrUserId>`. In unpinned upstream flavors, either gate the feature off or add the server URL to the scope.

**[S25] Toggle and badge.**
- **Proto3 default trap:** `AppPreferences` is proto3. A new `bool request_notifications = N` reads **false** on every existing install, which turns the feature off for everyone.
  - Store the inverse (`request_notifications_disabled`), or default it through `AppPreferencesSerializer` + `AppUpgradeHandler`.
  - Put it in the `R.string.more` group (`AppPreference.kt:1311-1324`).
- **When the toggle is off:** mark new identities delivered silently, so turning it back on doesn't release a burst. The badge keeps counting.
- **Badge:** a persistent per-scope `seenReady` set.
  - Count = current Ready/Partly-ready keys not in that set.
  - On opening My Requests, add all of them.
  - At baseline, everything is seen.
  - `NavItem` is shared and generic, so add an optional `badgeCount`. It must be visible on the collapsed rail icon.

## 6. Root chip host (`MainContent.kt`, inside the `AppContent` entry next to `UpdatePrompt` at `:131`)

**[M26] Detecting an open dialog.** There's no dialog registry, and `Dialog(` is called directly in six files (Dialogs.kt, VoiceSearchButton, ImageFilterSliders, LocaleChoiceDialog, PlaybackControls, PlaybackDialog).
- Use **`LocalWindowInfo.current.isWindowFocused`** read in MainContent. Any Compose `Dialog` or focusable `Popup` takes window focus from the activity window.
- That also covers `UpdatePromptDialog` and the voice-search UI. Also require `updateChecker.prompt.value == null`.

**[M27] Text input.**
- Hook `UiInputState` centrally in `EditTextBox` / `SearchEditTextBox` (`ui/components/EditTextBox.kt:72,214,252,358`) with `onFocusChanged`. Use a counter plus a `DisposableEffect` decrement on dispose, so a field removed while focused can't leave the flag stuck on.
- Don't hook it per page. Twelve files use these fields.
- Note: Requests opens on the Search tab with the box focused, so the chip waits there. That's correct.

**[M28] Safe moments.** Show the chip only when all of these hold:
- the app content is shown;
- the top of the back stack is not `isPlayback` and not `UpdateApp`;
- `!screensaver.show && !screensaver.showDim`;
- the window has focus;
- no text input is active;
- the toggle is on;
- connected `Success`.
- If any of these becomes false **while the chip is up**, pull it back to *pending* (not delivered) and restore focus.
- Otherwise the screensaver can cover a focused chip (`MainActivity.dispatchKeyEvent` swallows keys while it shows). The chip then times out unseen and gets marked delivered.
- Mark delivered on Watch/Details/View/Dismiss/Back, or on a timeout that ran while the chip was actually visible.

**[M29] The 700 ms fresh-press guard.**
- Record `shownAt = SystemClock.uptimeMillis()` after focus actually lands on the primary button.
- In the chip's `onPreviewKeyEvent`, consume DPAD_CENTER/ENTER/NUMPAD_ENTER (down, up and repeats) when `nativeKeyEvent.downTime < shownAt + 700`.
- `downTime` is in the same uptime base. It rejects both early presses and a press, or a held key, that began on the page before the chip appeared, whose key-up would otherwise land on the chip.
- Back is not guarded.

**[M30] Focus save and restore.**
- `saveFocusedChild` and `restoreFocusedChild` need a `FocusRequester` attached to a focus-target ancestor of the page.
- Wrap `ApplicationContent` in `Modifier.focusRequester(appFocus).focusGroup()` and make the chip a **sibling** outside that group. Nothing in the app uses these APIs yet (they're experimental, so opt in).
- Order: `appFocus.saveFocusedChild()` → show → focus the primary button (after composition and the enter animation; use the existing `tryRequestFocus`).
- On close: `if (!appFocus.restoreFocusedChild()) appFocus.requestFocus()`. If the destination changed (Watch/View navigated), skip the restore and let the new page take focus.
- Consume Back in the chip's `onPreviewKeyEvent` only while the chip has focus. On the Shield (API 30 key path), that runs before NavDisplay's `onBack` and the drawer's `BackHandler` (`NavDrawer.kt:322`).
- The 15 s timeout restarts on any key the chip handles.
- An event that arrives while the chip is visible does **not** replace or re-focus it. It waits for the next chip.

**[S31] Batching.**
- Collect every pending key in scope at show time.
- One item: title + Watch now (id guard) / Details + Dismiss.
- Two or more: "N requests are ready" if they're all ready/partial, otherwise "N request updates", with **View** → `navigateToFromDrawer(Discover(1, null))`.
- Mark the whole batch delivered together.
- Customer copy says "Requests" / "WeaselPlex", never Seerr/Jellyfin. Use US spelling.

## 7. Spec diff (in progress) and build notes

- The `seerr-api.yml` diff looks right:
  - adds `SeasonRequest` and `DownloadingItem`;
  - adds `MediaInfo.status4k`, `downloadStatus`, `downloadStatus4k`;
  - fixes the enum description (6 BLOCKLISTED, 7 DELETED).
- `Season.status4k` already exists. Don't duplicate it.
- Retyping `MediaRequest.seasons` to `SeasonRequest` is source-compatible with `DiscoverSeriesViewModel.kt:298-310`, which uses only `seasonNumber` / `status`.
- Generated JSON is `ignoreUnknownKeys` / `isLenient` / `explicitNulls=false`, and non-required fields are nullable, so missing `media.seasons` in list rows is safe.
- Per AGENTS.md fast Alpha iteration: build only with `./scripts/build-alpha.sh --install 100.105.135.30:5555` from this VPS. No tests, lint, screenshots or device navigation. Keep the Alpha caches. If ADB fails, stop and report; don't fall back to ThinkCentre. Report `df -h ~` if under 30 GB.

## 8. Priority order for the implementer

1. M1–M5 (generation token, AccountKey, CAS `Expired`, no stale password reconnect, probe rules).
2. M7–M10 (paging, hydration, rows that disappear, BigDecimal).
3. M15–M20 (Destination/contentKey, one-shot tab, submit seeding, exact cancel, `is4k` PUT match, quality-specific id).
4. M23, S25 (persistent baseline, proto default).
5. M26–M30 (window-focus dialog gate, central text-input flag, withdraw on unsafe, `downTime` guard, focus group and restore).
