# WeaselPlex Kid Profiles — Implementation Plan (FINAL, agreed)

Revised 2026-10-12 after round 3 (Fable: agree; Astra: agree w/ changes; Sol: disagree, one
blocker; Grok: agree w/ changes). Owner decisions in section 1 are final. Section 9 maps round-1
blockers; section 10 round-2; section 11 round-3.

## 1. Owner decisions (final)
1. Real, separate media-server (Jellyfin 12.1) user per kid, created automatically from the
   parent's existing sign-in. Server-enforced parental policy, separate watch history.
2. Instant creation on the TV (no phone approval step).
3. Levels: **Kids** = up to PG / TV-PG; **Older kids** = up to PG-13 / TV-14; **Teens** = up to
   R / TV-MA.
   **Owner ruling 2026-10-12 (after round 2):** Jellyfin scores NC-17 and TV-MA identically
   (17/1, verified in upstream us.json), so no policy can admit TV-MA while excluding NC-17.
   The owner chose: **Teens admits TV-MA and accepts that NC-17 also gets through.** No NC-17
   tagging job, no R-only fallback. Only score 18 (TV-X / TV-AO) and unrated are blocked for
   Teens. This is final; do not reopen.
4. Kids: no content requests, no Live TV, max 5 kid profiles per account.

### 1.1 Level -> policy table (US ratings from upstream us.json; Phase 0 confirms on 12.1)
| Level | MaxParentalRating | MaxParentalSubRating | Admits | Blocks |
|---|---|---|---|---|
| Kids | 10 | null | G, TV-Y, TV-G, Approved, TV-Y7(-FV), PG, TV-PG and TV-PG-x variants (10/1) | PG-13 and up |
| Older kids | 14 | null | + PG-13, TV-14 and TV-14-x variants (14/1) | R, TV-MA, NC-17, 18 |
| Teens | 17 | null | + R, TV-MA(-x), NC-17 (accepted) | TV-X, TV-AO (18) |
All levels also block unrated items (section 3.3). Sub-rating must be null, not 0, or TV-PG-x /
TV-14-x shows disappear.

## 2. Corrected facts (from round 1)
- `ServerRepository.switchServerOrUser()` (ServerRepository.kt:277-286) only clears persisted
  ids; `_current`, `currentUserDto` and the ApiClient token remain. The chooser screen therefore
  still has the previous session in memory; `current != null` is NOT proof of parental
  authorization.
- `changeUser` clears an existing PIN when the same user re-authenticates (ServerRepository.kt:
  206-213) and stores `name = userDto.name` (:111-114).
- `PinEntryDialog` compares per keystroke with no lockout (SwitchUserContent.kt:473-483).
- Workers (SuggestionsWorker.kt:65-75, TvProviderWorker.kt:71-82, LatestNextUpWorker.kt:53-65)
  reuse the singleton ApiClient or restore their scheduled user into global state.
- Seerr: `UserSwitchListener` calls `connectForUser` on every switch (:81-86) and
  `reconnectIfNeeded` on every activity start (:44-49); first sign-in provisions a Seerr user via
  Quick Connect (SeerrServerRepository.kt:200-206, 273-300) with Seerr's default permissions.
- Website: every mutation route requires a trusted browser Origin (`requireTrustedMutationOrigin`,
  business-http.ts:138-148); a TV request would 403. `runIdempotentMutation` hashes the whole
  body (business-http.ts:393-428). Ownership is keyed by portal account:
  `media_access_requests.portal_user_id` -> `media_access_identities` (request_id, cleared_at)
  (packages/db/src/schema/media-access.ts:21-41). Parent Quick Connect requires exactly one live
  identity row (weaselplex-quick-connect.ts:139). Reinstate can replace the parent media user
  (media-access-http.ts:488-499). `setDisabled` fetches the current policy and flips `IsDisabled` (media-server.ts:347-355); v3+ still re-applies the kid builder for kids as a safeguard.
  `revokeSessions` notes DELETE /Devices kills every user's token on a device (media-server.ts:
  415-419); password rotation is user-scoped.
- Existing adapter methods: `updatePolicy` (:279), `setDisabled` (:347), `deleteUser` (:361),
  `renameUser` (:375), `revokeSessions` (:415), `authorizeQuickConnect` with userId (:199-238).
  Member policy overlay `weaselPlexMemberPolicy` (media-server-policy.ts:184-206) already has
  `IsHidden: true` and `EnableSharedDeviceControl: true` (owner choice).
- Jellyfin: policy field is `MaxParentalSubRating` (not SubScore); SDK 1.7.1 lacks it (website
  uses raw JSON, fine). Upstream us.json gives NC-17 and TV-MA the identical 17/1 (R = 17/0);
  `IsParentalAllowed`: lower score allowed, equal score allowed iff sub-rating max is null or
  item sub <= max; ratings load only from embedded resources (no admin override). Hence the
  owner ruling in section 1, item 3. Folders/box sets are never "unrated-blocked";
  series/season/episode resolve to the series rating. Jellyfin treats NR / Not Rated / Unrated /
  missing ratings as unrated; `Approved` is rated (0/0, with G).

## 3. Website design (weaseltv-platform)

### 3.1 Data
New table `media_kid_profiles`: `id`, `portal_user_id` (owner; stable across parent media-user
replacement), `request_id` (FK media_access_requests), `media_user_id` (unique, null until
provisioned), `display_name`, `level`, `state` (`provisioning|active|disabled|deleting`),
`created_at`, `updated_at`, `last_error`. Unique (portal_user_id, lower(display_name)) among
non-deleted rows. **Kids are never inserted into `media_access_identities`.**

### 3.2 One service, two authentication adapters
- **Portal session adapter:** existing actor (`requireWeaselPlexActor`) + existing origin/CSRF
  checks. Used by the dashboard card and the approve-page picker.
- **TV media-token adapter (new route family `/api/v1/weaselplex/tv/kids/*`):** no Origin check;
  per-IP and per-token-hash rate limit applied BEFORE any media-server call; then
  `GET /Users/Me` with the presented token against the configured media server only (never a
  client-supplied URL/user id, never the admin key); map media user -> live identity
  (`cleared_at is null`) -> approved request -> `portal_user_id`. Reject if the caller is a kid
  (`media_kid_profiles.media_user_id`), revoked, or not approved. Tokens never logged.
- Both adapters call the same ownership-checked service; every kid id is checked against the
  caller's `portal_user_id`.

### 3.3 Operations
- **Create** `POST .../kids {displayName, level}` + `Idempotency-Key` (hash covers only
  displayName+level). Under the per-account lock shared with revoke/reinstate/delete/connect/
  level-change (reuse the serialization used by Quick Connect authorize, weaselplex-quick-connect
  .ts:126-150): check state, reserve quota (<=5 non-deleted), insert row `provisioning`, create
  media user with a random password, **commit `media_user_id` to the row immediately** (so a
  crash can always be found and compensated), apply kid policy, **read the user back and verify**
  (`MaxParentalRating`, `MaxParentalSubRating`, `BlockUnratedItems`, `IsAdministrator=false`,
  `EnableLiveTvAccess=false`), provision the Seerr deny (3.5), mark `active`. Any failure before
  `active` -> `deleteUser` compensation. Row locks (`FOR UPDATE` on the request row) are held
  only in short transactions around state checks/transitions, never across media-server or
  Seerr calls (the QC helper's 10 s `statement_timeout`, weaselplex-quick-connect.ts:61-62,
  can't cover them); the `provisioning`/`deleting` row states serialize the long steps (as `provisionMediaUser` does, media-access-http.ts:
  243-327 incl. uncertain-commit handling); rows stuck in `provisioning` are retried/cleaned by
  the next call or an admin repair action.
- **Connect** `POST .../kids/{id}/connect {quickConnectCode}` — authorize the code as the kid (only
  `active` kids). Mirrors the existing QC helper exactly: request-row `FOR UPDATE` held across the
  single `authorizeQuickConnect` call in its 10 s transaction, so revoke cannot interleave.
- **Staging:** create is implemented as separate stages (reserve in a short tx -> provider calls
  outside any tx -> finalize in a short tx). `runIdempotentMutation` (business-http.ts:398,
  449-459) wraps only the short reservation/finalize steps, never the provider calls. Separate from create so an expired code or lost response just reconnects the
  same kid with a fresh code. Rate-limited like existing QC (5 / 10 min).
- **List** `GET .../kids`.
- **Update** (portal only in v1) rename display name / change level: re-applies kid policy under
  the lock. **Delete** (portal only in v1): state `deleting` -> revoke sessions -> delete media
  user -> delete Seerr user -> remove row; retryable, row kept until provider delete succeeds.
- **Adult (member) builder change:** `weaselPlexMemberPolicy` explicitly sets
  `MaxParentalRating` and `MaxParentalSubRating` to null for adults (today it spreads whatever the
  server had), so the TV's kid signal (`maxParentalRating != null`) is unambiguous.
- **Kid policy builder:** start from `weaselPlexMemberPolicy(current, folderIds)` (+ member
  configuration), then set `MaxParentalRating` and `MaxParentalSubRating` from table 1.1 (confirmed in Phase 0),
  `BlockUnratedItems` = [Movie, Series, Trailer, Book, ChannelContent, LiveTvChannel,
  LiveTvProgram, Other], `EnableLiveTvAccess=false`, `EnableLiveTvManagement=false`,
  `SyncPlayAccess=None`, `EnableContentDeletion=false`, `IsAdministrator=false`. Leave
  `EnableSharedDeviceControl` and `IsHidden` as the member values (note: shared device control
  lets a kid session control other sessions on that same device; accepted). `Music` is omitted
  from `BlockUnratedItems` on purpose (music is rarely rated). `setDisabled` for kids must
  re-apply the kid builder (never the member policy) so disable/enable can't drop restrictions.
- **Username:** Jellyfin `Name` = sanitized kid display name made unique with the existing
  `uniqueWeaselPlexUsername` (e.g. "Emma", "Emma 2"). Parent link lives only in
  `media_kid_profiles`.

### 3.4 Lifecycle cascades (all under the per-account lock, retryable, auditable)
- **Revoke parent access** (request -> `revoked`): disable every kid (kid builder + IsDisabled),
  set kid rows `disabled`, and rotate each kid's password (user-scoped; never DELETE /Devices).
- **"Sign out everywhere"** (`revokeWeaselPlexSessionsWithin`, media-access-http.ts:612-619;
  staff-customer-accounts.ts:142-146): rotate each kid's password to end sessions ONLY. Kid
  access state and policy are unchanged (rows stay `active`), so fresh pairing via connect works
  immediately. Tested separately from revoke/reinstate.
- **Reinstate** (`revoked` -> approved): re-enable kids; kids follow `portal_user_id`, unaffected
  by parent media-user replacement.
- Account deletion: delete every kid media user + Seerr user; keep cleanup references until each
  provider delete succeeds.

### 3.5 "No requests" is a server-side launch gate
Phase 0 determines how Seerr treats new Jellyfin users (auto-import, default permissions). Default
design: on create, the website imports the kid into Seerr via the Seerr admin API and sets
permissions to none (new systemd credential, owner-provisioned; never in Git). If Phase 0 finds a
better server-side control (e.g. auto-import off + default permissions none), use that. Kid
creation does not ship until a kid demonstrably cannot request via TV, phone app, or Seerr web.

### 3.6 Approve-page picker (Phase 2)
`/weaselplex/connect?code=` adds "Who is this device for?" (You / each active kid). Lets phones,
the Web Player and other TVs sign a kid in via QR. Portal-session adapter.

## 4. Android TV design (this repo, weaselfin flavor only)

### 4.1 Session and authorization correctness
- **Restriction data published atomically:** `changeUser` reads the fetched `UserDto.policy` and
  writes to Room, BEFORE publishing `_current`, the final columns (defined now, single migration
  36->37 shipped only in Phase 3, with a migration test): `maxParentalRating: Int?`,
  `policyState` (tri-state `unknown|adult|kid`), `displayName: String?`. `restoreSession` always
  refreshes them; on refresh failure keep the cached values; `unknown` is treated as kid with the
  lowest ceiling (fail closed). A profile's **ceiling** = `maxParentalRating` for kids, unlimited
  for adults.
- **Phase 0 Alpha carries no migration:** its Seerr guard is purely in-memory, computed from
  `currentUserDto.policy.maxParentalRating`.
- **Display name:** from the create/connect API response when available; otherwise
  `UserDto.name` (e.g. a kid signed in by QR on a second TV shows "Emma 2" if the server name
  was de-duplicated; accepted). Portal renames update the server display name only.
- **Parental authorization is explicit:** a new in-memory `ParentUnlock(userRowId, at)` set only
  by (a) a successful PIN entry for a non-kid profile, (b) a completed adult Quick Connect sign-in
  (4.2), or (c) `SetPinFlow` creating a first PIN inside that adult's own session **only while the
  device is not protected** (4.2). It authorizes the single switch/action in progress and expires
  when that completes, when the flow is left, or after 5 min. It never changes the persisted
  session ceiling by itself.
- **Explicit chooser teardown:** entering the chooser from a kid session cancels in-flight
  account-bound work and clears `ParentUnlock`; late async results tagged with an older user are
  dropped (Seerr connect, home rows).
- **Workers never touch the foreground session** (covers process death too: with `current` null
  they skip instead of restoring; they also re-check the scheduled user after every suspension
  and drop stale results if the current user changed mid-run): SuggestionsWorker, TvProviderWorker,
  LatestNextUpWorker never call `restoreSession`. If `current.user.id` is not their scheduled
  user they return `Result.retry()`; with no current user (chooser, cold start) they return
  success without work (skip), so they don't spin. (Full task-local clients
  are a later refactor; the singleton is threaded through LatestNextUpService, ImageUrlService,
  SuggestionsCache.) `WholphinDreamService` (screensaver, :95-99) likewise only uses the existing
  `current` session and never restores a user.
- **Cold start:** `MainActivity` (:525-541) reads `isProtected` and the switch gate (4.2) from the
  Room row BEFORE restoring, so a protected or gated profile is never restored into `_current` /
  the shared ApiClient (and no Seerr connect fires) before its PIN is entered.
- **Owner-visible consequence (documented, not changed in v1):** Android TV home-screen rows
  (Watch Next/channels) and the screensaver reflect the last-used profile.

### 4.2 Adult-protection invariant and the single switch gate
- **Durable device protection flag:** a persisted `kidProtection` DataStore flag is set the
  moment the first kid row is admitted to the device (via create OR via Add User/QR yielding a
  kid), together with the session-ceiling key. It is NOT derived from the number of kid rows:
  removing kids (including the last one), restarts, auto-sign-in off, and `switchServerOrUser`
  leave it set. It is cleared only by an explicit "Turn off kid protection on this TV" action in
  an adult's settings, behind `ParentUnlock`, and only when no kid rows remain. Every rule below
  that says "protected device" means this flag is set.
- **Bootstrap (device not yet protected):** the Add-kid flow requires the creating parent to
  hold or set a PIN (trust-on-first-use is allowed ONLY while the device is not protected). When
  the flag is set, every other saved adult without a PIN becomes **locked**.
- **On a protected device:** an adult profile without a PIN is locked. Selecting a
  locked adult does not open their session; it requires either (a) a `ParentUnlock` from another
  adult on this device, or (b) a fresh Quick Connect sign-in for that adult (approved on
  theweasel.tv by the account holder). Only then may that adult create their PIN. Creating a
  first PIN is never by itself proof of parental authority on a protected device.
- Adults newly added via Add User on a protected device must set a PIN in-flow (they just proved
  themselves via Quick Connect approval on theweasel.tv).
- PIN removal (`PreferencesContent.kt:715-723`) is blocked on a protected device.
- **Quick Connect counts as parent authorization:** a completed Quick Connect sign-in (approved
  on theweasel.tv by the account holder, as "You" or via the 3.6 picker) passes the gate for that
  target; for an adult it sets `ParentUnlock`; the session ceiling becomes the target's ceiling.
- `changeUser` no longer clears an existing PIN on re-auth of the same user (weaselfin).
- Long-press delete of a non-kid row requires that profile's PIN; deleting a pinless (locked)
  adult on a protected device requires `ParentUnlock` from another adult or a fresh Quick Connect
  for that adult (kids can't delete a parent to re-add it unprotected).
- **Quick Connect sign-ins on a protected device complete atomically:** for an adult without a
  PIN, the in-flow PIN setup must finish before `AppContent`; until then `_current`, the shared
  ApiClient and the session ceiling are unchanged, and cancelling leaves them unchanged (today
  `SwitchUserViewModel.kt:223-229` publishes immediately; Phase 3 defers publication).
- **Single switch gate (`ProfileSwitchGate`)** applied before any credentials are published, on
  every entry path: chooser selection, cold-start auto restore, `IntentService` (:126-134 and
  its auto-restore branch :139-156, which today calls `restoreSession` before the `isProtected`
  check and gets the same pre-restore Room check as MainActivity),
  remote-control/Play On targets, and the screensaver. Rule: a persisted **session ceiling**
  (new DataStore key, written inside `changeUser` itself so every path persists it by
  construction; initialized to "unlimited" when the protection flag is first set, since the
  parent is unlocked at that moment; NOT cleared by `switchServerOrUser`, process death, or
  removing kid rows; a missing key on a protected device = unknown = gated) is compared with the
  target's ceiling. If
  target ceiling > session ceiling, or either is unknown, an adult PIN (`ParentUnlock`) is
  required for that one switch. **The session ceiling is written only when a switch completes,
  and it is always the destination's ceiling** (10, 14, 17, or unlimited for an adult). A PIN
  entered and then dismissed changes nothing; switching from an adult to a younger kid stores
  that kid's ceiling; deleting kid rows never changes or raises it. Same-or-lower kid levels
  switch freely. **On a protected device a pinless adult target always requires `ParentUnlock` or
  a fresh Quick Connect, regardless of ceilings, on every entry path** (chooser, cold start,
  `IntentService` deep links, remote targets, screensaver). First-kid admission via QR persists
  `kidProtection` and the admitted kid's ceiling before publishing that session (the
  "initialized to unlimited" case applies only to parent-led creation). The gate is a no-op only while the device is
  not protected (never admitted a kid, or protection explicitly turned off by an unlocked adult).
- PIN entry gets attempt backoff (e.g. 5 wrong -> 30 s, doubling).
- Gate on `hasPin` (passwords are disabled by SSO-only mode; `requireLogin` is not usable).

### 4.3 Add-kid flow (v1 scope)
1. "Add kid profile" card on the chooser (weaselfin, at least one saved non-kid user, not shown
   while a kid session is active).
2. Pick the parent (auto if one) -> PIN entry -> `ParentUnlock`. If that parent has no PIN and
   the device is NOT yet protected (4.2): enter the parent's session, create the PIN via
   `SetPinFlow` (trust-on-first-use), then continue. If the device is protected, a pinless adult
   is locked and must first be unlocked per 4.2.
3. If the account already has kids (`GET /tv/kids`), offer "Sign in Emma on this TV" (connect)
   or "Create new".
4. Form: name (1-20, same sanitize rules as the website) + level (3 options with descriptions).
5. `POST /tv/kids` with the unlocked parent's **Room access token** (not the shared ApiClient
   token), then initiate Quick Connect with a scoped client, `POST /tv/kids/{id}/connect`, poll,
   `authenticateWithQuickConnect`, `changeUser` (display name from the API response).
6. Errors: limit reached, name taken, parent revoked, network; retry reconnects the same kid.
- v1 TV scope excludes rename / change level / delete-from-account (portal card does those).
  "Remove from this TV" (long-press on a kid) removes only the local row.

### 4.4 Kid restrictions in the TV app
- Seerr: `connectForUser`, `reconnectIfNeeded` and provisioning return immediately for kids;
  Discover/Requests hidden. Ships in the very first Alpha used for Phase 0.
- Hidden for kids: Add User, Add kid, Switch server, server/account settings. Live TV already
  hidden by policy (`NavDrawerService.kt:348`). Kid badge on avatar.

### 4.5 Always return to the profile chooser (owner addition, 2026-10-12)
Rule: when more than one profile is saved on this TV for the current server (any mix of adults
and kids), every return to the app lands on the profile chooser instead of the last profile.
Single-profile TVs keep today's behaviour.
- Triggers: cold start; coming back from the background (Home button, another app, Shield
  sleep, the Shield's own screensaver/WholphinDreamService, which all stop the activity and hit
  `MainActivity.onRestart` -> `appStart(null)`, :318-330); and waking the in-app screensaver
  (`dispatchKeyEvent`, :299-301), which today just dismisses the overlay with no lifecycle change.
- Builds on the existing "Sign in automatically" off path (`MainActivity.kt:529`, :552-569 ->
  `SetupDestination.UserList`; `MainContent.kt:110-113` hides content on ON_STOP so the last
  profile's screen never flashes on return). On a multi-profile TV the rule forces that path and
  the setting shows as "Always on: more than one profile"; it is not a new toggle.
- The chooser always shows the PIN prompt for PIN profiles, including the profile that was just
  in use. Returning to the chooser runs the 4.1 chooser teardown (cancel account-bound work, clear
  `ParentUnlock`) but keeps the session ceiling (4.2), so the gate rules are unchanged.
- Exempt: returning from the external player (`playExternalViewModel.launched`, :324) and the
  app's own transient activities. Leaving mid-playback in the internal player saves progress as
  today, then the chooser shows on return.
- Launcher tiles/deep links (`IntentService`, `appStart` IntentResult.Target :476-497) open the
  chooser first, holding the target; it continues only if the picked profile is the one the
  tile belongs to (otherwise go to that profile's home).
- Play On (`RemotePlaybackService.onPlayRequest`, :84-134): while the chooser lock is up (chooser
  showing or app in the background), remote play requests are refused with the existing
  "locked" toast; they work only while a profile is actively in use. This closes the path where
  `inApp` is true on the chooser and the request would navigate into `AppContent(current)`.
- Kid household cost: parents enter their PIN on every return. Accepted by the owner's
  "always" wording; a grace period is not in v1.

### 4.6 Profile avatars for every profile (owner addition, 2026-10-12)
Every profile (adults and kids) can pick a picture from a WeaselPlex avatar set instead of the
initial in a square. Kids find their profile by picture without reading.
- **Display already works.** The profile screen and nav drawer load the Jellyfin user image and
  fall back to the initial only when there is none (`ui/setup/UserList.kt:323-360`
  `UserIconCardImage`; `ui/nav/NavDrawer.kt:182,374`; `SwitchUserViewModel.kt:275,295`). The
  new work is picking and saving.
- **Storage: the Jellyfin user image.** Saving uploads the chosen picture as the user's Primary
  image (`ImageApi.postUserImage`, SDK 1.7.1; `POST /UserImage`), so it also appears in the phone
  apps and the Web Player. Each user sets their own with their own token. Phase 0 confirms a
  non-admin member and a kid can set their own image on 12.1, and that BlockUnratedItems does
  not hide user images.
- **Avatar set ships in the app.** Images are bundled in the weaselfin flavor (converted to WebP),
  so the picker works offline and needs no new website endpoint. Stable file names are the IDs.
  Optional later: the same set on the theweasel.tv dashboard picker.
- **Where you pick:** (a) the add-kid flow gets a "Pick a picture" step after the name and level,
  saved after connect using the kid's new session; (b) "Change picture" on the profile screen's
  per-profile menu and in Settings for the signed-in profile. A kid may change their own picture
  (harmless; no PIN). Changing another profile's picture is not in v1. "Remove picture" goes back
  to the initial (`ImageApi.deleteUserImage`).
- **Picker UI:** a D-pad grid of avatars, the current one marked, the profile's name shown above.
- **Caching:** after saving, refresh the image URL (bump a cache key; Jellyfin user image tags
  change on upload) so the old picture doesn't linger in Coil's cache.
- **Owner supplies the images.** Until they arrive, Alpha builds use a few placeholder avatars so
  the flow can be tested. Specs: square 1:1, 512x512 px, PNG, sRGB, opaque full-bleed background
  (no transparency); subject inside the centre ~80% circle (phone app and Web Player may crop to a
  circle; the TV tile is a 120 dp square, the nav drawer icon 24 dp, about 48 px); no text,
  borders or rounded corners; readable at 48 px; under ~300 KB each; lowercase-hyphen file names
  that never change once shipped; 24-48 images in one consistent style; owner holds the rights
  (no TV/movie characters). The agent converts them to WebP for the app.

## 5. Phases

### Phase 0 — Verification spike (1–1.5 days; owner approval required for production steps)
Prereq: TV Alpha containing only the in-memory Seerr guard (no Room migration; owner installs).
In that Alpha `changeUser` publishes `_currentUserDto` before `_current`, and the guard treats a
user as adult only when `currentUserDto?.id == user.id` and its policy has a null
`MaxParentalRating`; anything else is kid (fail closed, skip Seerr).
With owner approval, create one test kid on WeaselPlex via the admin API using the draft kid
policy. API-level checks with the test kid's token (agent, owner-approved); Shield checks by the
owner. **Hard pass/fail gates, recorded in a written note:**
1. Parent `/Users/Me` shows `MaxParentalRating` null. Live scores from `/Localization/ParentalRatings` match table 1.1 (incl. TV-PG-x 10/1, TV-14-x
   14/1, R 17/0, TV-MA/NC-17 17/1, TV-X 18). Test items: Kids admits TV-PG-V, rejects PG-13;
   Older kids admits TV-14-L, rejects R; Teens admits R and TV-MA, rejects TV-X/TV-AO. Record the
   count of NC-17 titles (owner accepted they are visible to Teens).
2. Above-level item: denied in lists, search, Latest, Resume, NextUp, Similar, by direct id
   (`/Items/{id}`), `/Items/{id}/Images`, PlaybackInfo and download.
3. Unrated: audit counts per library (missing, NR, Not Rated, Unrated; Approved counts as rated); owner decides fix
   vs accept. Box sets: visible with only allowed children (no above-level artwork/titles leak).
   Episodes follow the series rating; extras/trailers behave.
4. Tightening a signed-in kid's level takes effect on next request.
5. Seerr: record auto-import + default permissions; prove the chosen deny mechanism.
6. Flowfin/SSO-only: Quick Connect for the kid works; plugin does not rewrite the kid's policy.
7. Delete the test kid (and its Seerr user) afterwards.
Exit: note + plan adjustments. Phase 1 may start in parallel on non-policy parts; nothing
deploys before Phase 0 passes.

### Phase 1 — Website backend (4–5 days)
Migration, service, two adapters (the TV adapter mints a `BusinessActorContext` carrying
`portal_user_id` so `enforceBusinessRateLimit`/`runIdempotentMutation` are reused; pre-auth per-IP
limiter follows provisioning-http.ts:288-300), create/connect/list/update/delete, kid policy
builder, lifecycle cascades (revoke vs sign-out distinct), rate limits, audit. Seerr deny is
net-new: Seerr admin adapter, systemd credential, env schema validation. Export the username
`sanitize` for the kid-name path. Tests: kid policy builder (table 1.1), token adapter
(valid/invalid/kid/revoked), quota under concurrency, compensation and stuck-provisioning repair,
revoke vs sign-out cascades (sign-out keeps kids active and re-pairable), idempotency. PR in weaseltv-platform; owner-gated deploy.

### Phase 2 — Portal (1–1.5 days)
Dashboard "Kid profiles" card (list, rename, change level, delete) and the approve-page picker.

### Phase 3 — TV app (7–8 days)
4.1–4.6. Alpha builds on the ThinkCentre (section 5A) via
`./scripts/build-alpha.sh --install 100.105.135.30:5555` for owner testing; feature flag off until Phase 1 is live. Tests on the finalized branch: Room migration,
add-kid ViewModel with fake API, `ProfileSwitchGate` (ceiling persistence across restart,
current-kid removal, unknown => gated), adult lockout rules, chooser-lock triggers (restart,
background return, in-app screensaver wake, external-player exemption, held deep link, Play On
refused while locked), avatar picker (save, remove, cache refresh).

### Phase 4 — Owner acceptance and release
Owner on Shield: two kids at different levels; sibling escalation blocked, including after a
TV restart on the chooser, with auto sign-in off, after removing the current kid from the TV,
and via a launcher/intent deep link; two existing unprotected adults (second adult locked after
the first kid); first kid added via QR locks unprotected adults; adult re-added via Add User
forced to set a PIN; PIN removal blocked; with two saved adults (B pinless) remove the sole kid,
then try B immediately, after restart with auto sign-in off, and via an intent deep link while
the parent's session is live (B stays locked); first kid added by QR, restart, then the
older sibling still needs a PIN; enter an adult
PIN on the chooser and dismiss before switching, kill the app, then the younger kid still needs a
PIN for the older sibling and adults; parent hands off to the younger kid (ceiling drops to
that kid's level); "Turn off kid
protection" works only behind an adult PIN with no kids left; a parent signs an older kid in by
QR while a younger kid's session was last (passes the gate); expired-code recovery; "sign out everywhere" then
re-pair a kid; revoke/reinstate parent; delete kid from portal. Chooser lock (two or more profiles): reopen after
Home, after Shield sleep, after the Shield screensaver, after the in-app screensaver, and from a
launcher tile all land on the chooser; external player return does not; Play On while on the
chooser is refused; a one-profile TV still auto-opens. Avatars: pick one while adding a kid,
change your own, remove it, and see it in the phone app and Web Player. Website deploy and stable TV release only when the owner asks. Notion updated.

### Phase 5 — Later
TV manage screen; phone-app polish; kid home layout; "Kids" row for parents.

Estimate: ~14–16 working days (provisional until Phase 0) of agent work plus owner testing and gated deploys.

## 5A. How we'll build it: orchestrator + parallel tracks on the ThinkCentre (owner, 2026-10-12)

**Machine:** the owner will open the build thread on the ThinkCentre (`thinkcentre`,
100.87.100.109), so all work happens there, per the AGENTS.md rule "work on the machine the thread
was opened on" (PR #57). Read-only check on 2026-10-12:
i7-4790 (4 cores / 8 threads), 15 GiB RAM, 23 GiB swap (16 GiB file + 7.5 GiB zram), KVM
available (VT-x, /dev/kvm), 208 GB free, JDK 17, Android SDK, Node 22, extension credentials in
`~/.gradle/gradle.properties`. The ThinkCentre reaches the Shield over Tailscale (100.105.135.30).
The VPS has 4 vCPUs and the same RAM, so the ThinkCentre has twice the build threads.

**Two Android builds side by side: yes, with caps.** The project uses `-Xmx2048m` for Gradle. One
Alpha build peaks at about 4–5 GB (Gradle + Kotlin daemon + R8), so two use about 9–10 GB of
15 GiB, with swap as headroom. Rules:
- At most 2 concurrent Gradle builds, each from its own worktree, with
  `org.gradle.workers.max=4` in each worktree (shares the 8 threads). A third build waits.
- No Android emulator while two builds run. Website (Node) tests run anytime.
- Keep build outputs and Gradle/Kotlin caches in the active worktrees (the AGENTS.md Alpha cache
  exception). Never delete the shared `~/.gradle` cache.
- If two builds swap heavily or get killed, drop to one build and record it.

**One-time setup: done 2026-10-12.**
1. Alpha signing key: the VPS's non-production debug key is now on the ThinkCentre (its old
   key backed up as `~/.android/debug.keystore.thinkcentre-backup-20261012-215747`). Both
   fingerprints match, so ThinkCentre Alphas install over the current Alpha.
2. Checkouts: the ThinkCentre's Wholphin and weaseltv-platform checkouts are fast-forwarded to
   GitHub. `scripts/sync-machines.sh` (PR #57) keeps both machines current.
3. Website toolchain: the website uses npm 11 / Node 24 (`packageManager: npm@11.12.1`,
   `.node-version` 24.15.0), not pnpm. The ThinkCentre's mise has Node 24.15.0 and now reads the
   repo's `.node-version`, so run website commands as `mise exec -- npm ...` there. The system
   Node is still 22.
4. Install route: `adb -s 100.105.135.30:5555 install -r <alpha apk>` from the ThinkCentre (the
   owner approves the ThinkCentre's ADB key on the Shield if prompted). Not yet tried from the
   ThinkCentre. If it fails, stop and report, with no other fallback.

**Orchestrator.** One orchestrator agent owns the integration branch `t3/kid-profiles`
(website: `t3/kid-profiles` in weaseltv-platform). It:
1. Day 0, alone (~0.5 day): freezes the TV<->website contract (endpoints, request/response JSON,
   error codes, idempotency, the kid policy fields) as a checked-in doc. Every track builds to it.
2. Launches the tracks, each in its own worktree and branch off the integration branch.
3. Verifies each track before merging: diff reviewed against this plan and the contract, track
   tests run, build passes. Sends work back if it drifts. Contract changes go through the
   orchestrator only.
4. Merges into the integration branch, then builds one Alpha from it and installs it on the
   Shield (owner verifies). Only the orchestrator installs to the Shield.
5. Keeps the Notion page current.

**Tracks**
| Track | Scope (plan sections) | Starts | Builds |
|---|---|---|---|
| P0. Server proof | Phase 0 | Owner OK | No Android build except the Seerr-guard Alpha |
| A. Website core | 3.1–3.3, TV adapter | After contract; policy values after P0 | Node only |
| B. Website lifecycle + Seerr deny | 3.4, 3.5 | After contract | Node only |
| C. TV core | 4.1, 4.2, 4.5, Room migration | Day 0 (no website dependency) | Android slot 1 |
| D. TV screens | 4.3, 4.4, 4.6 (avatars) against a fake API, then the real one | After contract | Android slot 2 |
| E. Dashboard | Phase 2 (3.6, portal card) | When A merges | Node only |

At most two TV tracks: C and D both touch `ServerRepository`, `MainActivity` and
`SwitchUserViewModel`. C owns those files; D adds screens and calls C's APIs. The orchestrator
merges C first when both change the same file.

**Owner gates (unchanged):** Phase 0's production test kid, website deploy, Seerr credential, and
any stable TV release. Alpha installs need no extra approval.

**Time:** agent work ~16–18 days (adds reviews and merges); calendar time until owner testing
about **6–7 working days**. The TV app sets the pace: C (~4 days) and D (~4–5 days) run side by
side, then the orchestrator integrates (~1 day). Two build slots keep both TV tracks building
without waiting. Costs more tokens (4–6 agents at once).

## 6. Accepted residual risks
- Teens profiles can see NC-17 titles (owner ruling; Jellyfin cannot separate NC-17 from TV-MA).
- A kid who learns an adult PIN can do anything an adult can on that TV.
- An adult who walks away while still using the app (no idle screensaver yet) leaves their
  profile open until the in-app screensaver or Shield sleep kicks in (4.5).
- First PIN creation is trust-on-first-use, only before the device is protected.
- Launcher rows/screensaver show the last-used profile's content.
- Parents cannot "Play On" from their phone into a kid's session (members lack remote control of
  other users).

## 7. (reserved)
## 8. (reserved)
## 9. Round-1 blockers -> v2 changes
- Chooser has a live session / gate premise wrong (Fable 1, Astra 2, Sol 3, Grok 1) -> 2, 4.1
  ParentUnlock, explicit teardown, Room token of the unlocked parent.
- Protect all adults, sibling escalation, PIN cleared on re-auth, long-press delete (Astra 1,
  Sol 2, Grok 1) -> 4.2.
- Workers using foreground creds (Astra 2) -> 4.1 workers isolated.
- No TV-callable route / origin check (Fable 2, Sol 7, Astra 3) -> 3.2.
- Ownership model / parent replacement (Astra 3, Sol 5) -> 3.1 portal_user_id; 3.4.
- Recovery, quota concurrency, idempotency with code (Astra 4, Sol 4, Grok 4) -> 3.3 create vs
  connect, lock, compensation.
- Policy overlay/fields, half-created user (Grok 3) -> 3.3 builder + read-back verify.
- Kids must not be identities; device-delete kills all (Grok 5) -> 3.1, 3.4.
- Teens/NC-17 sub-rating (Sol 1), thresholds as gates (Astra 6) -> Phase 0 gate 1–4.
- Seerr must be server-enforced and guarded before first test kid (Astra 5, Sol 6, Fable 4,
  Grok 2) -> 3.5, 4.4, Phase 0 prereq.
- Display name (Fable 3, Grok 4) -> 3.3 username, 4.1 displayName column.

## 10. Round-2 blockers -> v3 changes
- TV-MA/NC-17 identical 17/1 (Sol 1, Fable 1, Astra NB1) -> owner ruling in section 1 item 3;
  table 1.1 with sub-rating null for every level (TV-PG-x/TV-14-x kept); Phase 0 gate 1 rewritten.
- Existing unprotected adults / first-PIN as authority after kids exist / PIN removal (Astra 1,
  Sol 2) -> 4.2 bootstrap + lockout; locked adults need ParentUnlock or fresh Quick Connect;
  PIN removal blocked while kids exist.
- Sibling protection lost on restart/no-auto-login/kid removal/intents/screensaver, boolean can't
  compare tiers (Astra 2, Sol 3, Fable 2) -> 4.1 final columns (maxParentalRating, tri-state
  policyState, displayName); 4.2 single `ProfileSwitchGate` with persisted session ceiling,
  applied on every entry path; fail closed on unknown; cold start reads Room before restoring.
- Schema frozen by Phase 0 Alpha (Fable 2) -> Phase 0 Alpha in-memory only; one migration in
  Phase 3.
- Sign out everywhere disabled kids with no repair path (Astra 3, Sol 4) -> 3.4 revoke vs
  sign-out split; sign-out only rotates kid passwords.
- Worker isolation cost (Fable NB1) -> 4.1 workers skip/retry when not the current user; never
  restore. Screensaver never restores.

## 11. Round-3 blockers -> v4 changes
- Removing the last kid turns protection off (Astra 1, Sol 1) -> 4.2 durable `kidProtection`
  flag, independent of kid-row count; cleared only by an unlocked adult with no kids left; gate
  no-op only on unprotected devices; 4.3 step 2 TOFU limited to unprotected devices; Phase 4
  cases for sole-kid removal + restart + auto sign-in off.
- Non-blocking adopted: Phase 0 Alpha publication order + fail-closed guard (Fable 1); adult
  builder nulls rating fields (Fable 2) + Phase 0 check; Quick Connect = parent authorization for
  the gate (Fable 3); session ceiling written in `changeUser`, initialized at protection (Fable 4);
  connect mirrors QC helper locking (Fable 5); staged create outside `runIdempotentMutation`'s
  transaction (Sol 2); `setDisabled` fact corrected (Sol 3); Phase 3 5–6 days, total 12–14 (Fable 7).
- Grok 1 (first PIN as authority / latch): closed by the durable flag above; plus 4.1 limits
  `SetPinFlow`-granted `ParentUnlock` to unprotected devices, and adult Quick Connect on a
  protected device defers publication until in-flow PIN setup completes (cancel = no change).
- Grok 2 (ceiling raised to unlimited and never lowered): 4.2 now writes only the destination
  ceiling on a completed switch; `ParentUnlock` authorizes one switch and never touches the
  ceiling; Phase 4 dismissed-PIN + process-death + hand-off cases.
- Grok NB1 (workers skip, not retry, with no current user) and NB2 (deleting a pinless locked
  adult) adopted in 4.1 / 4.2.

## 12. Round-4 result
All four reviewers returned AGREE on v4 (Fable, Astra, Sol, Grok). Non-blocking items folded
into this final version: IntentService auto-restore branch gated before restore and QR-first-kid
restart case (Grok 1-2); pinless-adult lock named as part of the gate on every entry path
(Fable 1); QR first-kid admission persists protection + kid ceiling before publishing (Astra 2,
Sol 2); workers re-check the user after every suspension (Astra 1, Sol 1, Fable 2).
