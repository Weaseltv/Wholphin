# Kid profiles — frozen TV <-> website contract (v1.8)

Status: **FROZEN 2026-10-21** by the kid-profiles orchestrator. v1.1 (same day) only clarifies
website-side details found while building tracks A and B; nothing the TV sends or reads changed.
v1.2 (same day) applies the Phase 0 result (Jellyfin 12.2.0): `MaxParentalSubRating` is 1 at
every level; null behaves like 0 and hid all TV-MA/NC-17 from Teens. TV behaviour unchanged. v1.3 (2026-10-22) adds section 10, the owner's
content rules for every kids profile (TV-side filter on top of the server policy). v1.4 (2026-10-22) adds section 11: account
avatars and an account PIN that the website can reset, plus the website's "Your profiles" pages. v1.5 (2026-10-23) adds section 12: kid pages on the website (picture, watch history,
pairing a kid to another TV), PINs for every adult on a TV with kids, and deleted kids leaving the TV. v1.6 (2026-10-23) adds the owner's second avatar set (48 adult, 48 kid). v1.7 (2026-10-23) changes what a pinless adult sees on a TV with kids (12.4), adds Change picture to the website's
Your profile page and renames the account page's tile to "Profiles" (11.3). v1.8 (2026-10-23) adds the owner's 12 women
adult avatars (60 adult, 48 kid). Identical copies live in
`Wholphin/runbooks/kid-profiles/contract.md` and `weaseltv-platform/docs/kid-profiles/contract.md`. Tracks build to this
document. Changes go through the orchestrator only; a change bumps the version line above and
is made in both repos in the same step.

Source plan: "WeaselPlex Kid Profiles — Implementation Plan (FINAL, agreed)", latest with
avatars (Notion, 2026-10-12). Section numbers below refer to that plan.

## 1. Levels and the kid signal

| Wire id (`level`) | Label (TV and portal) | Description shown     | `MaxParentalRating` | `MaxParentalSubRating` | TV ceiling |
| ----------------- | --------------------- | --------------------- | ------------------- | ---------------------- | ---------- |
| `kids`            | Kids                  | Up to PG and TV-PG    | 10                  | 1                      | 10         |
| `older_kids`      | Older kids            | Up to PG-13 and TV-14 | 14                  | 1                      | 14         |
| `teens`           | Teens                 | Up to R and TV-MA     | 17                  | 1                      | 17         |

- Confirmed by Phase 0 on the live server (Jellyfin **12.2.0**, 2026-10-21; see
  `runbooks/kid-profiles/phase0-results.md` in Wholphin). Numbers may change only through the
  orchestrator, never the wire ids.
- **Kid signal (TV):** a Jellyfin user is a kid iff `UserDto.policy.maxParentalRating != null`.
  Its ceiling is that value. Adults have `null` (the website's member builder sets it to
  `null` explicitly, plan 3.3). The TV never infers the level from the name or from the
  website; the server policy is the source of truth.
- Ceiling order: `10 < 14 < 17 < unlimited (adult)`. Unknown is treated as the lowest
  ceiling for the current session and as "gated" for a target (plan 4.1, 4.2).

### 1.1 Kid policy fields (website writes, then reads back and verifies)

Starting from `weaselPlexMemberPolicy(current, folderIds)` plus member configuration:
`MaxParentalRating` and `MaxParentalSubRating` (table above),
`BlockUnratedItems: ["Movie","Series","Trailer","Book","ChannelContent","LiveTvChannel","LiveTvProgram","Other"]`,
`EnableLiveTvAccess: false`, `EnableLiveTvManagement: false`, `SyncPlayAccess: "None"`,
`EnableContentDeletion: false`, `IsAdministrator: false`, `IsDisabled: false` (unless the
parent is revoked). `IsHidden` and `EnableSharedDeviceControl` keep the member values.
Read-back must verify `MaxParentalRating`, `MaxParentalSubRating`, `BlockUnratedItems`,
`IsAdministrator=false`, `EnableLiveTvAccess=false`.

## 2. Display names

- Input `displayName`: the website applies the existing WeaselPlex username `sanitize`
  (NFC, keep `[\p{L}\p{N}_ \-'.@+]`, collapse whitespace, trim), then requires
  **1–20 characters** after sanitizing. Otherwise `400 INVALID_REQUEST`.
- The TV applies the same rule client-side for the form, but the website's sanitized value is
  authoritative and is returned as `displayName`.
- Uniqueness: case-insensitive among the account's non-deleted kids -> `409 KID_NAME_TAKEN`.
- The Jellyfin user `Name` is `uniqueWeaselPlexUsername(displayName)` and may differ
  ("Emma 2"); it is returned as `mediaUserName`. The TV shows `displayName` when it has it
  (from create/connect/list), otherwise `UserDto.name` (plan 4.1).

## 3. Common HTTP rules (TV routes)

- Base: `https://theweasel.tv` (TV BuildConfig `WEASELPLEX_WEBSITE_URL`, weaselfin flavor).
  Route family: `/api/v1/weaselplex/tv/kids`.
- **Auth:** `Authorization: Bearer <Jellyfin access token of the parent>`. The TV sends the
  unlocked parent's Room access token, never the shared ApiClient token (plan 4.3 step 5).
  The website validates it with `GET {WEASELPLEX_MEDIA_API_BASE_URL}/Users/Me` using
  `Authorization: MediaBrowser Token="<token>"` against the configured server only, then maps
  media user -> live identity (`cleared_at is null`) -> approved request -> `portal_user_id`.
  Tokens are never logged or stored.
- No `Origin` check on this family. Optional `X-Request-Id` (UUID); echoed as the audit
  correlation id. Optional `X-WeaselPlex-Client: android-tv/<versionName>` (audit only).
- Requests: `Content-Type: application/json`, body at most 1 KiB, no query strings except where
  listed. Responses: `Cache-Control: no-store`.
- **Success envelope:** `{"data": {...}}`.
- **Error envelope** (RFC 7807, same as the rest of the site):
  `{"type":"https://theweasel.tv/problems/<code lowercased>","title":"<CODE>","status":<int>,"detail":"<English sentence>"}`.
  The TV switches on `title` (the code) and `status`, never on `detail`.
  `429` responses also carry a `Retry-After` header (whole seconds) and `"retryAfterMs"` in
  the body.
- **Feature flag:** website env `WEASELPLEX_KID_PROFILES_ENABLED` (default off). When off,
  every route in this family returns `404 WEASELPLEX_KIDS_UNAVAILABLE`; the TV shows
  "Kid profiles aren't available yet" and hides nothing else.

### 3.1 Rate limits (website; applied BEFORE any media-server call)

| Scope                                              | Limit           | Notes                                      |
| -------------------------------------------------- | --------------- | ------------------------------------------ |
| Per client IP, whole family                        | 30 / minute     | pre-auth                                   |
| Per token (keyed HMAC-SHA256 digest), whole family | 60 / 10 minutes | pre-auth                                   |
| Create, per account                                | 10 / hour       | after auth                                 |
| Connect, per account                               | 5 / 10 minutes  | after auth, mirrors existing Quick Connect |

### 3.2 Error codes

| Status | `title`                             | Meaning                                                                                                        | TV behaviour                                  |
| ------ | ----------------------------------- | -------------------------------------------------------------------------------------------------------------- | --------------------------------------------- |
| 400    | `INVALID_REQUEST`                   | Body failed validation (name length/characters, level), body over 1 KiB, wrong content type, or a query string | Show inline form error                        |
| 400    | `INVALID_JSON`                      | Malformed JSON                                                                                                 | Bug; generic error                            |
| 400    | `INVALID_IDEMPOTENCY_KEY`           | Missing/invalid key on create                                                                                  | Bug; generic error                            |
| 400    | `QUICK_CONNECT_INVALID_CODE`        | Code unknown or expired                                                                                        | Re-initiate Quick Connect, retry connect once |
| 401    | `AUTHENTICATION_REQUIRED`           | Token missing, invalid or expired                                                                              | "Sign this profile in again"                  |
| 403    | `WEASELPLEX_ACCESS_REQUIRED`        | Parent account not approved / revoked                                                                          | "Your WeaselPlex access isn't active"         |
| 403    | `KID_CALLER_FORBIDDEN`              | The token belongs to a kid profile                                                                             | Bug guard; generic error                      |
| 404    | `WEASELPLEX_KIDS_UNAVAILABLE`       | Feature flag off                                                                                               | "Kid profiles aren't available yet"           |
| 404    | `KID_NOT_FOUND`                     | Unknown kid id or not this account's                                                                           | Refresh list                                  |
| 409    | `KID_LIMIT_REACHED`                 | Already 5 non-deleted kids                                                                                     | "You already have 5 kid profiles"             |
| 409    | `KID_NAME_TAKEN`                    | Name used by another kid on the account                                                                        | Inline name error                             |
| 409    | `KID_NOT_ACTIVE`                    | Kid is `provisioning`, `disabled` or `deleting`                                                                | "This profile isn't available right now"      |
| 409    | `IDEMPOTENCY_CONFLICT`              | Same key, different body                                                                                       | Bug; new key                                  |
| 409    | `REQUEST_IN_PROGRESS`               | Same key still running                                                                                         | Wait 2 s, retry same key                      |
| 429    | `RATE_LIMITED`                      | See 3.1                                                                                                        | "Try again in N minutes" using `retryAfterMs` |
| 503    | `KID_PROVISIONING_FAILED`           | Create failed and was rolled back                                                                              | Retry allowed (same key re-runs)              |
| 503    | `MEDIA_ACCESS_PROVIDER_UNAVAILABLE` | Media server/Seerr unreachable                                                                                 | Generic retry                                 |
| 503    | `WEASELPLEX_UNAVAILABLE`            | Portal disabled / DB timeout                                                                                   | Generic retry                                 |
| 500    | `INTERNAL_ERROR`                    | Anything else                                                                                                  | Generic retry                                 |

## 4. Kid object

```json
{
  "id": "6f1c7a8e-2b5d-4c1e-9a0b-3d2e1f4a5b6c",
  "displayName": "Emma",
  "level": "kids",
  "state": "active",
  "mediaUserId": "0123456789abcdef0123456789abcdef",
  "mediaUserName": "Emma",
  "createdAt": "2026-10-21T19:00:00.000Z"
}
```

- `id`: website UUID (the `media_kid_profiles.id`). `mediaUserId`: Jellyfin user id, 32 lower
  hex without dashes, `null` only while `provisioning`. The TV compares it with
  `UserDto.id` (normalise dashes/case).
- `state`: `provisioning | active | disabled`. `deleting` rows are never returned.
- Additive fields may appear later; clients ignore unknown fields.

## 5. TV routes

### 5.1 List — `GET /api/v1/weaselplex/tv/kids`

`200 {"data":{"kids":[Kid...],"limit":5}}`, sorted by `createdAt` ascending.

### 5.2 Create — `POST /api/v1/weaselplex/tv/kids`

Headers: `Idempotency-Key` required, 16–200 chars `[A-Za-z0-9._:-]` (the TV uses a UUID it
generates once per form submission and reuses on retry). Body (strict):
`{"displayName":"Emma","level":"kids"}`. The idempotency hash covers only these two fields.

- `201 {"data":{"kid":Kid}}` with `state: "active"`. A replay returns the stored `201` with
  `X-Idempotent-Replay: true`.
- Failures before `active` are compensated (media user deleted) and return
  `503 KID_PROVISIONING_FAILED`; they are not stored as replays, so retrying the same key
  re-runs creation.
- Other errors: 400 `INVALID_REQUEST`, 409 `KID_LIMIT_REACHED` / `KID_NAME_TAKEN` /
  `IDEMPOTENCY_CONFLICT` / `REQUEST_IN_PROGRESS`, 403, 401, 429, 503.
- Create does **not** sign anything in. The TV follows with Connect (5.3).

### 5.3 Connect — `POST /api/v1/weaselplex/tv/kids/{kidId}/connect`

Body (strict): `{"code":"123456"}` (6 digits, same field and rule as the existing portal Quick
Connect route). No idempotency key (authorizing an already-used code returns
`QUICK_CONNECT_INVALID_CODE`; the TV then starts a fresh code).

- The website authorizes the Quick Connect code **as the kid** (only `active` kids) under the
  same request-row lock as the existing Quick Connect helper.
- `200 {"data":{"authorized":true,"kid":Kid}}`.
- Errors: 400 `QUICK_CONNECT_INVALID_CODE` (also for a malformed connect body or a query
  string, mirroring portal Quick Connect), 404 `KID_NOT_FOUND`, 409 `KID_NOT_ACTIVE`,
  403, 401, 429, 503.

### 5.4 TV call sequence (add kid, plan 4.3)

1. Parent unlocked on the TV (PIN -> `ParentUnlock`).
2. `GET /tv/kids` with the parent's Room token. If kids exist, offer "Sign in <name> on this
   TV" (connect) or "Create new".
3. Create: `POST /tv/kids` with a fresh `Idempotency-Key`.
4. With a scoped, unauthenticated Jellyfin client: `POST /QuickConnect/Initiate` -> `{code, secret}`.
5. `POST /tv/kids/{id}/connect {"code"}`.
6. Poll `GET /QuickConnect/Connect?secret=` until `Authenticated`, then
   `POST /Users/AuthenticateWithQuickConnect {secret}` -> `AuthenticationResult` for the kid.
7. Hand the result to the TV's kid sign-in (persists kid protection + the kid's ceiling before
   publishing the session; plan 4.2), with `displayName` from step 3/5.
8. Optional avatar step (plan 4.6) runs in the kid's new session against Jellyfin directly.
   Retry/expired code: repeat 4–6 for the same kid id; never create again.

## 6. Portal routes (session adapter; existing Origin/CSRF + session checks)

All under `/api/v1/portal/weaselplex/kids`, same envelopes and error codes. Mutations
require `Idempotency-Key`.

- `GET /api/v1/portal/weaselplex/kids` -> `200 {"data":{"kids":[Kid...],"limit":5}}`
- `PATCH /api/v1/portal/weaselplex/kids/{kidId}` body (strict, at least one field)
  `{"displayName"?: "...", "level"?: "kids|older_kids|teens"}` -> `200 {"data":{"kid":Kid}}`.
  Re-applies the kid policy under the account lock. A rename updates `displayName` and renames
  the Jellyfin user to `uniqueWeaselPlexUsername(newName)`; TVs that already saved the kid keep
  their cached name until the next sign-in (plan 4.1, accepted).
- `DELETE /api/v1/portal/weaselplex/kids/{kidId}` -> `200 {"data":{"deleted":true}}` once the
  media user and Seerr user are gone; `202 {"data":{"deleted":false,"state":"deleting"}}` if a
  provider step must be retried (row kept, retried by the next call/repair).
- Portal create is **not** in v1 (kids are created on the TV).
- **Approve-page picker (plan 3.6):** the existing `POST /api/v1/portal/weaselplex/quickconnect`
  accepts an optional `"kidId"` (UUID) beside `"code"`. Absent = "You" (today's behaviour).
  Present = authorize the code as that `active` kid; errors as 5.3. The approve page lists the
  account's active kids from the GET route.

## 7. Not website business (TV <-> Jellyfin only)

- Avatars (plan 4.6): bundled in the weaselfin flavor; saved with the profile's own token via
  `POST /Users/{userId}/Images/Primary`, removed with `DELETE /Users/{userId}/Images/Primary`.
  Avatar ids are the bundled file names (lowercase-hyphen, never renamed).
- Kid detection, session ceiling, kid protection, PIN rules: TV only (except where the PIN is
  stored and the website's PIN reset, section 11; plan 4.1, 4.2, 4.5),
  using the kid signal in section 1.

## 8. TV-internal seam between tracks C and D

Frozen Kotlin interfaces live in
`app/src/main/java/com/github/damontecres/wholphin/kids/KidContracts.kt` on the integration
branch, with stub implementations bound by `KidStubsModule` so both tracks compile.

- **Track C** implements `KidSessionGate` (gate, ParentUnlock, PIN verification with backoff,
  kid sign-in, kid/adult classification) and removes the stub binding for it.
- **Track D** implements `KidProfilesApi` (OkHttp client for section 5) and the screens; it
  only calls `KidSessionGate`, never `ServerRepository.changeUser` directly for kid sign-in.
- Signature changes to these interfaces go through the orchestrator.

## 9. Website data seam between tracks A and B

The `media_kid_profiles` table, its enums and migration `0041_media_kid_profiles` are frozen on
the weaseltv-platform integration branch (`packages/db/src/schema/media-access.ts`,
`packages/db/migrations/0041_media_kid_profiles.sql`). Columns: `id`, `portal_user_id`,
`request_id`, `media_user_id` (32 lower hex, unique when set), `media_user_name`,
`seerr_user_id`, `display_name` (1–20, trimmed; unique per owner, case-insensitive, among
rows not `deleting`), `level`, `state` (`provisioning|active|disabled|deleting`), `version`,
`last_error`, `created_at`, `updated_at`.

- **Track A** owns create/connect/list/update/delete, the TV token adapter, the kid policy
  builder (`weaselPlexKidPolicy(current, folderIds, level)` in
  `packages/provider-adapters/src/media-server-policy.ts`), and the per-account lock helper.
- **Track B** owns the Seerr admin adapter (`packages/provider-adapters/src/seerr.ts`,
  implementing the frozen `SeerrUserPort` in `src/seerr-port.ts`), its env/credential schema,
  and the lifecycle cascades (revoke, reinstate, sign-out-everywhere, account deletion). B calls A's kid policy
  builder and lock helper; until A lands, B codes against those names and A's PR defines them.
- Website-only error: `503 WEASELPLEX_KIDS_CLEANUP_PENDING` (staff account deletion while kid
  provider deletes are still being retried). TV routes never return it.
- `last_error` holds short machine codes only (e.g. `provisioning_abandoned`), never provider
  messages or secrets.
- Audit actions use the `weaselplex.kid.*` prefix.
- Schema changes go through the orchestrator.

## 10. Content rules for every kids profile (owner decision 2026-10-22, v1.3)

The server policy (section 1.1) stays as the first wall. On top of it the TV filters every title it shows a
kid session or unknown session (section 1, "Unknown is treated as the lowest ceiling"). Adults are not filtered.

**Data.** Jellyfin item metadata: `OfficialRating`, `Genres`, `PremiereDate`, `ProductionYear`, `LockedFields`
(needs `fields=Settings`), `Type`; for episodes and seasons also the parent series (`SeriesId`). `Genres`
needs `fields=Genres`.

**Decision order** (the first rule that applies wins):

1. **Horror is always hidden.** Any genre containing "horror" (case-insensitive) hides the item. Episodes and
   seasons use the parent series' genres. Beats every other rule, including a hand-set rating.
2. **No usable US rating = hidden.** Normalize `OfficialRating`: trim, uppercase, strip a leading `US-`.
   Usable: movies `G, PG, PG-13, R, NC-17`; TV `TV-Y, TV-Y7, TV-Y7-FV, TV-G, TV-PG, TV-14, TV-MA`.
   Everything else is hidden: empty, NR, NOT RATED, UNRATED, APPROVED, PASSED, X, every non-US value
   (`GB-15`, `16`, `12`, `NC16`, `L`, `TP`, ...). Episodes and seasons with no rating of their own use the
   series' rating; if the series has none, hide.
3. **Hand-set rating wins.** `LockedFields` contains `OfficialRating`: use the rating as it is and skip rule 4.
4. **Pre-PG-13 era.** A `Movie` rated PG with `PremiereDate` before 1984-07-01 (UTC date) counts as PG-13.
   No `PremiereDate`: use `ProductionYear <= 1984`. Not for TV ratings.
5. **Compare** on one scale: 0 = G, TV-Y, TV-Y7, TV-Y7-FV, TV-G; 1 = PG, TV-PG; 2 = PG-13, TV-14;
   3 = R, TV-MA; 4 = NC-17. Show only if the item's level <= the profile's maximum level.

**Profile maximum level** from the session ceiling (section 1): Kids (10) = 1, Older kids (14) = 2,
Teens (17) = 3. For other values: >= 18 -> 4, >= 17 -> 3, >= 13 -> 2, >= 10 -> 1, else 0. Unknown ceiling:
no title is shown.

**Orchestrator interpretations** (owner may overrule):

- Episodes and seasons are also hidden whenever their series is hidden, even if the episode has its own
  lower rating ("its episodes hidden too").
- A PG movie with neither `PremiereDate` nor `ProductionYear` counts as PG-13 (fail closed).
- Rule 1 also hides an episode or season whose own genres contain "horror", even if the series'
  genres don't (horror is always hidden).
- Rules apply to every item type except containers: libraries/folders/user views, people, genres (the
  "Horror" genre itself is hidden), studios, years, playlists (their contents are filtered), and box sets
  (shown only while at least one child is visible). Unknown types are treated as titles. Trailers and extras
  need their own usable rating.
- Teens: NC-17 is level 4, so it is now hidden for Teens (the server's 17/1 still admits it).

**Where.** Every surface: home rows (including "Top 10 on ..." rows), library grids, search, collections,
Continue Watching, Next Up, recommendations, "more like this", person and genre pages, the Android TV launcher
rows, cached lists, deep links and direct item ids. Playback is refused by item id after a fresh check.
**Fail closed:** if metadata can't be loaded, the item is hidden (lists) or refused (detail, playback).

**Server side (defense in depth).** Each kids profile is its own Jellyfin user (section 9), with the section 1.1
policy: `MaxParentalRating` = the level, `BlockUnratedItems` including Movie and Series. Jellyfin has no
"Episode" unrated type; episodes without a rating follow their series (Phase 0 gate 3). The server cannot express
rules 1, 2 (non-US values it scores), 4 or the Teens NC-17 cut, so those live on the TV only. Other Jellyfin
clients signed in as a kid get only the server policy.

## 11. Your profile: avatar and account PIN (owner decision 2026-10-22, v1.4)

Every profile (adult or kid) has an avatar and an adult may have a PIN. Both belong to the Jellyfin user, so
they follow the person to every TV and survive signing out and back in.

### 11.1 Avatar

- Stored as the Jellyfin user's Primary image (section 7). Adults choose from the 60 adult avatars, kids from
  the 48 kid avatars (`app/src/weaselfin/assets/avatars/`).
- **First sign-in.** When an adult signs in on a TV (username/password or Quick Connect, not a profile switch)
  and their user has no Primary image, the TV asks them to choose an avatar before going on. No skip; if the
  upload fails it says so and offers "Try again" and "Continue without a picture".
- **Existing profiles.** An adult already saved on a TV whose user has no Primary image gets a random adult
  avatar, once per user per TV. An existing image is never replaced automatically.
- **Change.** "Change picture" in the select-user long-press menu, for every saved profile with a token:
  kids pick from the kid list, adults from the adult list. An adult with a PIN enters it first.

### 11.2 Account PIN

- **Store.** Jellyfin DisplayPreferences, id `weaselplex-profile`, client `weaselplex`, `CustomPrefs`:
  `pin` (the presses, see below; empty or missing = no PIN) and `pinUpdatedAt` (ISO-8601 UTC). The TV reads
  and writes it with the profile's own token, merging into the existing prefs; the website writes it with the
  admin key the same way (GET, merge the two keys, POST). Other keys are left alone.
- **Format.** New PINs are exactly 4 presses from `U D L R` (up, down, left, right), e.g. `ULLD`. Older PINs
  saved on a TV may be other lengths or digits; they keep working until changed.
- **TV.** The account PIN is the authority; the TV's saved PIN (Room `users.pin`) is a cache.
  - Pull when the select-user page opens and before a PIN prompt shows (best effort, short timeout; the cache
    is used if the server can't be reached). A server PIN replaces the cache.
  - A wrong entry pulls once and checks again, so a PIN reset on the website works straight away.
  - No server PIN but a cached one: the TV uploads the cached PIN (one-time migration).
  - Setting, changing or removing a PIN writes the server first; if that fails the TV shows an error and
    changes nothing.
- **Change PIN** ("Change PIN" in the select-user long-press menu, profiles with a PIN): current PIN, then the
  new PIN, then the new PIN again. A mismatch says so and asks for the new PIN again.
- **Forgot PIN.** On every PIN screen, after more than 3 wrong entries on that screen the TV shows
  "Forgot your PIN? You can reset it on the website at theweasel.tv/weaselplex/profiles" and a QR code for
  `https://theweasel.tv/weaselplex/profiles`. The existing backoff still applies.

### 11.3 Website

- Account page (`/weaselplex`): the kid-profiles section moves to its own page. A "Your profiles" tile sits
  under "Get the app" and above "Web player" and opens `/weaselplex/profiles`.
- `/weaselplex/profiles`: two tiles, "Your profile" (`/weaselplex/profiles/you`) and "Kids profiles"
  (`/weaselplex/profiles/kids`, today's kid-profiles section). Same sign-in and access rules as the account
  page; signed-out visitors (for example from the TV's QR code) go through sign-in and come back.
- `/weaselplex/profiles/you`: the profile name, its avatar if it has one, and "Reset PIN": four arrow buttons
  and four boxes, entered twice, then saved.
- Route `PUT /api/v1/portal/weaselplex/profile/pin`, body (strict) `{"pin":"ULLD"}` matching `^[UDLR]{4}$`
  -> `200 {"data":{"hasPin":true,"pinUpdatedAt":"..."}}`. Portal Origin/CSRF/session checks, active
  WeaselPlex access required (403 `WEASELPLEX_ACCESS_REQUIRED`), 10 per hour per account (429
  `RATE_LIMITED`), 503 `MEDIA_ACCESS_PROVIDER_UNAVAILABLE` if Jellyfin can't be reached. Audit action
  `weaselplex.profile.pin_reset`. The PIN is never logged or stored by the website.
- `GET /api/v1/portal/weaselplex/profile` -> `200 {"data":{"displayName":"...","hasPin":bool,"pinUpdatedAt":"..."|null,"hasAvatar":bool}}`.
- `GET /api/v1/portal/weaselplex/profile/avatar`: the session's own media user's Primary image (never a kid's, no
  caller-supplied id), resized, raster `image/*` only, `Cache-Control: private, no-store`; 404 when there is none.
- **v1.7 (owner 2026-10-23):** the account page's tile is titled "Profiles" (was "Your profiles"); the page
  title of `/weaselplex/profiles` follows. `/weaselplex/profiles/you` gets **Change picture**: the 60 adult avatars
  (same ids as the TV's `avatars/adults/`). `PUT /api/v1/portal/weaselplex/profile/avatar`, body (strict)
  `{"avatarId":"wolf-crimson"}` -> `200 {"data":{"avatarId":"wolf-crimson"}}`; uploads the PNG as the session's own
  media user's Primary image with the admin key (12.1's format). Same portal checks and errors as the PIN route
  (403 `WEASELPLEX_ACCESS_REQUIRED`, 400 `INVALID_REQUEST` for an unknown id, 429 `RATE_LIMITED` at 30 per hour
  per account, 503 `MEDIA_ACCESS_PROVIDER_UNAVAILABLE`); never a kid's user, no caller-supplied user id. Audit
  `weaselplex.profile.avatar_changed`. No PIN is asked (the account owner is signed in on the website).

## 12. Kid pages, pairing a kid to another TV, adult PINs, deleted kids (owner decision 2026-10-23, v1.5)

### 12.1 Website kid pages

- `/weaselplex/profiles/kids`: one title card holds the "Kids profiles" heading, the count ("3 of 5"), "Each kid gets
  their own profile that only shows movies and shows for their age. You can have up to 5 kid profiles." and "To add a
  kid, open WeaselPlex on your TV and choose **Select user → Add kid profile**." No subtitle and no second heading.
  Each kid is one button (avatar or initial, name, level label, level description) that opens
  `/weaselplex/profiles/kids/{kidId}`.
- `/weaselplex/profiles/kids/{kidId}`: Back (to Kids profiles); the kid's avatar, name and level; **Rename**,
  **Change level**, **Change picture**, **Delete** (section 6 rules and routes); **Pair to a TV** (12.3); **Watch
  history** (12.2). A kid id that isn't this account's (or is `deleting`) shows "We couldn't find that kid profile"
  with a link back. Same sign-in and access rules as the other profile pages.
- **Change picture:** the 48 kid avatars, same ids as the TV (`app/src/weaselfin/assets/avatars/kids/`, file name
  without `.webp`). `PUT /api/v1/portal/weaselplex/kids/{kidId}/avatar`, body (strict) `{"avatarId":"octopus-lime"}`
  -> `200 {"data":{"avatarId":"octopus-lime"}}`. Portal Origin/CSRF/session checks and active access (403
  `WEASELPLEX_ACCESS_REQUIRED`); the kid must be this account's (404 `KID_NOT_FOUND`) and `active` (409
  `KID_NOT_ACTIVE`); unknown id -> 400 `INVALID_REQUEST`; 30 per hour per account (429 `RATE_LIMITED`); 503
  `MEDIA_ACCESS_PROVIDER_UNAVAILABLE`. The website uploads the avatar as a PNG Primary image of the kid's media user
  with the admin key (same format as the TV: base64 body, `Content-Type: image/png`). Audit
  `weaselplex.kid.avatar_changed`.
- `GET /api/v1/portal/weaselplex/kids/{kidId}/avatar`: the kid's Primary image, same rules as the 11.3 avatar route
  (this account's kid only, raster only, size cap, `private, no-store`, 404 when none).

### 12.2 Watch history

- `GET /api/v1/portal/weaselplex/kids/{kidId}/history` -> `200 {"data":{"items":[Item...]}}`, newest first, at most
  50: movies and episodes the kid's media user has finished or started, ordered by `UserData.LastPlayedDate`.
  `Item = {"itemId","type":"Movie"|"Episode","name","seriesName"|null,"seasonNumber"|null,"episodeNumber"|null,
"lastPlayedAt"|null,"finished":bool,"progressPercent":int|null,"hasImage":bool}`. Read with the admin key as that
  user (`userId=`). Same portal checks and kid ownership as 12.1; 60 per hour per account.
- `GET /api/v1/portal/weaselplex/kids/{kidId}/history/{itemId}/image`: the item's poster (episodes may fall back to
  the series poster), only for an item that kid's media user can see; raster only, size cap, resized,
  `private, no-store`.

### 12.3 Pairing a kid to another TV

A kid made on one account (a parent) can be added to another account's TV (a grandparent). History, level and avatar
follow because they belong to the kid's media user.

- **The pairing code is a Jellyfin Quick Connect code.** TV: Select user -> Add kid profile -> parent unlock -> adult
  PINs (12.4) -> **Add an existing kid profile**. The TV starts Quick Connect with a scoped, unauthenticated client and
  shows the 6-digit code with "On your phone or computer, go to theweasel.tv/weaselplex/profiles, choose Kids
  profiles, pick the kid, then Pair to a TV and enter this code." and a QR code for
  `https://theweasel.tv/weaselplex/profiles/kids`. It polls; when the code expires it gets a new one by itself.
  The same screen also offers this account's own active kids that aren't on the TV yet (section 5.4 connect).
- **Website:** the kid page's "Pair to a TV" takes the 6 digits and calls the existing
  `POST /api/v1/portal/weaselplex/quickconnect` with `{"code","kidId"}` (section 6). No new route.
- **TV, once authenticated:** the result must be a kid (section 1 kid signal). An adult result is refused: the TV
  signs that token out (`POST /Sessions/Logout` with it) and says the code was approved for a grown-up profile. A kid
  goes through `KidSessionGate.signInKid` with `UserDto.name` as the display name (the TV can't list another account's
  kids). If the parent unlock ran out while waiting, the TV asks for the parent's PIN again and then finishes with the
  same result.
- Kids from another account don't count against this account's 5; the website only lists the account's own kids.

### 12.4 Every signed-in adult on a TV with kids has a PIN

- Before a kid is created on, signed in on or paired to a TV, after the parent unlock: every other saved adult on that
  TV that has a token must have a PIN. The TV pulls their account PINs first (11.2); an account that already has one
  counts, so an existing PIN is turned on by itself. Each remaining adult gets **Create PIN** (4 presses, then again;
  saved to that adult's account with their token, 11.2) or **Remove from this TV**. The kid step goes on only when
  none remain. Back leaves the add-kid flow without adding a kid.
- Saved adults without a token need their password or Quick Connect to get in; when they sign in on a TV with kids
  they set a PIN first (11.2, unchanged).
- **Picking a pinless adult on a TV with kids (v1.7, owner 2026-10-23)** no longer asks for another adult's PIN. It
  shows "<Name> needs a PIN" ("This TV has kid profiles, so every grown-up needs a PIN.") with **Create a PIN** and
  **Remove from this TV**. Create a PIN first signs that profile in again (Quick Connect or password, the same
  sign-in a signed-out profile gets), so only its owner can set it; the fresh sign-in then asks for the new PIN
  (11.2) and goes in. Remove from this TV needs no PIN (the profile can't be used without one anyway). The same
  applies to removing a pinless adult from the long-press menu. Account PINs are pulled first, so an account that
  already has a PIN just asks for it.

### 12.5 Deleted kids leave the TV; pictures stay current

- When the select-user page opens, the TV checks every saved kid profile with its own token (`GET /Users/Me`). HTTP
  401 (the media user was deleted, or its sessions were ended from the website) removes that profile from the TV: its
  saved row and its cached PIN, picture and kid state. Network errors and other statuses change nothing. Adults are
  never removed this way. Kid protection stays on.
- The same call returns the profile's `PrimaryImageTag`; the chooser puts it in the image URL so a picture changed on
  the website or another TV shows.

### 12.6 One-time avatar backfill (done 2026-10-23)

- Every non-administrator adult media user without a Primary image got a random adult avatar (the TV's PNG
  format), once, by the orchestrator (25 users). The owner then asked that no two auto-assigned profiles share one:
  the two duplicates got unused avatars from the second set (v1.6). Avatars people choose themselves may repeat. 11.1's per-TV random avatar stays for profiles saved before the TV
  update reaches them; accounts made from now on choose at first sign-in.
