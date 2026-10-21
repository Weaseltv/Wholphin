# Kid profiles — frozen TV <-> website contract (v1)

Status: **FROZEN 2026-10-21** by the kid-profiles orchestrator. Identical copies live in
`Wholphin/runbooks/kid-profiles/contract.md` and `weaseltv-platform/docs/kid-profiles/contract.md`. Tracks build to this
document. Changes go through the orchestrator only; a change bumps the version line above and
is made in both repos in the same step.

Source plan: "WeaselPlex Kid Profiles — Implementation Plan (FINAL, agreed)", latest with
avatars (Notion, 2026-10-12). Section numbers below refer to that plan.

## 1. Levels and the kid signal

| Wire id (`level`) | Label (TV and portal) | Description shown | `MaxParentalRating` | `MaxParentalSubRating` | TV ceiling |
|---|---|---|---|---|---|
| `kids` | Kids | Up to PG and TV-PG | 10 | `null` | 10 |
| `older_kids` | Older kids | Up to PG-13 and TV-14 | 14 | `null` | 14 |
| `teens` | Teens | Up to R and TV-MA | 17 | `null` | 17 |

- Values are provisional until Phase 0 confirms them on the live 12.1 server; Phase 0 may
  change the numbers, never the wire ids.
- **Kid signal (TV):** a Jellyfin user is a kid iff `UserDto.policy.maxParentalRating != null`.
  Its ceiling is that value. Adults have `null` (the website's member builder sets it to
  `null` explicitly, plan 3.3). The TV never infers the level from the name or from the
  website; the server policy is the source of truth.
- Ceiling order: `10 < 14 < 17 < unlimited (adult)`. Unknown is treated as the lowest
  ceiling for the current session and as "gated" for a target (plan 4.1, 4.2).

### 1.1 Kid policy fields (website writes, then reads back and verifies)
Starting from `weaselPlexMemberPolicy(current, folderIds)` plus member configuration:
`MaxParentalRating` (table above), `MaxParentalSubRating: null`,
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
| Scope | Limit | Notes |
|---|---|---|
| Per client IP, whole family | 30 / minute | pre-auth |
| Per token hash (sha256), whole family | 60 / 10 minutes | pre-auth |
| Create, per account | 10 / hour | after auth |
| Connect, per account | 5 / 10 minutes | after auth, mirrors existing Quick Connect |

### 3.2 Error codes
| Status | `title` | Meaning | TV behaviour |
|---|---|---|---|
| 400 | `INVALID_REQUEST` | Body failed validation (name length/characters, level) | Show inline form error |
| 400 | `INVALID_JSON` | Malformed JSON | Bug; generic error |
| 400 | `INVALID_IDEMPOTENCY_KEY` | Missing/invalid key on create | Bug; generic error |
| 400 | `QUICK_CONNECT_INVALID_CODE` | Code unknown or expired | Re-initiate Quick Connect, retry connect once |
| 401 | `AUTHENTICATION_REQUIRED` | Token missing, invalid or expired | "Sign this profile in again" |
| 403 | `WEASELPLEX_ACCESS_REQUIRED` | Parent account not approved / revoked | "Your WeaselPlex access isn't active" |
| 403 | `KID_CALLER_FORBIDDEN` | The token belongs to a kid profile | Bug guard; generic error |
| 404 | `WEASELPLEX_KIDS_UNAVAILABLE` | Feature flag off | "Kid profiles aren't available yet" |
| 404 | `KID_NOT_FOUND` | Unknown kid id or not this account's | Refresh list |
| 409 | `KID_LIMIT_REACHED` | Already 5 non-deleted kids | "You already have 5 kid profiles" |
| 409 | `KID_NAME_TAKEN` | Name used by another kid on the account | Inline name error |
| 409 | `KID_NOT_ACTIVE` | Kid is `provisioning`, `disabled` or `deleting` | "This profile isn't available right now" |
| 409 | `IDEMPOTENCY_CONFLICT` | Same key, different body | Bug; new key |
| 409 | `REQUEST_IN_PROGRESS` | Same key still running | Wait 2 s, retry same key |
| 429 | `RATE_LIMITED` | See 3.1 | "Try again in N minutes" using `retryAfterMs` |
| 503 | `KID_PROVISIONING_FAILED` | Create failed and was rolled back | Retry allowed (same key re-runs) |
| 503 | `MEDIA_ACCESS_PROVIDER_UNAVAILABLE` | Media server/Seerr unreachable | Generic retry |
| 503 | `WEASELPLEX_UNAVAILABLE` | Portal disabled / DB timeout | Generic retry |
| 500 | `INTERNAL_ERROR` | Anything else | Generic retry |

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
- Errors: 400 `QUICK_CONNECT_INVALID_CODE`, 404 `KID_NOT_FOUND`, 409 `KID_NOT_ACTIVE`,
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
- Kid detection, session ceiling, kid protection, PIN rules: TV only (plan 4.1, 4.2, 4.5),
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
- Schema changes go through the orchestrator.
