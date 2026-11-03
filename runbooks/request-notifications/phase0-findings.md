# Request notifications: Phase 0 findings

Date: 2026-10-13. Executed from **srv1160496 only**. This records discovery for the agreed v4 plan; it does not implement the Phase 1 app or deploy the Phase 4 push service.

## Evidence and limits

The live `GET /api/v1/status` returned Seerr **3.5.0**, commit **e2f24cb46079746936516c723b09820360f95113**. Source was checked at that exact tag and commit, rather than assuming the newest release or relying on September records. Source links below are pinned to that deployed commit.

The saved VPS testing credentials returned **401** on the single `POST /auth/jellyfin` attempt. There were **zero retries**, no `/auth/me` probe, no deliberate expiration, and no logout call. Normal cookie-session sign-in was therefore **not validated**. A working pre-existing administrator session allowed read-only setup inspection. The server's main API key (admin-equivalent), read through that session, was used with Seerr's documented `X-API-User` header to exercise one existing ordinary requester's permission context: **160 = REQUEST + AUTO_APPROVE**, without ADMIN, MANAGE_REQUESTS or REQUEST_VIEW. This is live permission/response validation through impersonation, not a successful ordinary-user cookie login. The key is not part of any deliverable or website configuration.

The scoped list and that request's detail both returned **200**. The list had one page and one owned, approved HD movie request (media PROCESSING). No other user's rows appeared. Both responses had `downloadStatus` and `downloadStatus4k`, and both arrays were empty. No active queue bytes, percentage, ETA, 4K request or TV request was observed for this account. The ordinary requester ID was a lowercase 32-character hexadecimal Jellyfin ID; the actual ID, name and title are excluded from these artifacts.

List media had **no `seasons` property**; detail media included `seasons` (empty for this movie). Request seasons were empty as expected for a movie. TV season behavior is source-confirmed, not a live TV observation. `phase0-live-summary.json` records only field presence, status values and counts.

The authorized temporary webhook Test was executed through `POST /settings/notifications/webhook/test`, which accepts temporary settings and does **not save them**. It returned **204**. The disposable receiver captured the JSON in `webhook-test-captured.json` with `Content-Type: application/json`. The template deliberately excludes notify-user names/emails, tokens and credentials. A Test has no request/media; all such placeholders become empty strings. This confirms the deployed templating and transport, **not** a real requester's identity substitution or a real ready/failed event. No real request was submitted or customer notification sent. The receiver was deleted (204); the saved webhook settings were compared before/after and were identical (disabled, types 0). Existing Discord settings were not changed.

Live admin `GET /settings/jobs` confirmed these schedules (six-field cron, in the server's timezone):

| Job | Cron | Meaning |
| --- | --- | --- |
| download-sync | `0 * * * * *` | Every minute |
| jellyfin-recently-added-scan | `0 */5 * * * *` | Every five minutes |
| jellyfin-full-scan | `0 0 3 * * *` | Daily at 03:00 |
| radarr-scan | `0 0 4 * * *` | Daily at 04:00 |
| sonarr-scan | `0 30 4 * * *` | Daily at 04:30 |
| availability-sync | `0 0 5 * * *` | Daily at 05:00 |
| download-sync-reset | `0 0 1 * * *` | Daily at 01:00 |

These are polling cadences, not a guarantee that a title is ready within five minutes. Download tracker errors can retain old queue data until a later update/reset. The live `syncEnabled` values and installed Sonarr/Radarr versions were not inspected.

## Decisions for the next phases

### 1. Progress comes from the list; missing progress is not a failed search

`Media.getDownloadingItem()` populates both qualities in an `AfterLoad` hook without an ordinary-user permission filter. Request list joins load media and receive these fields; request detail also receives them. Populate a bar from **list media queue data when present**, rather than polling every request detail just for a bar. Phase 0 observed the fields, not an active download: show stages until valid byte counts appear.

The tracker requires an appropriately configured `syncEnabled` service and maps raw queue `status`, size/sizeLeft, timeLeft and ETA. It does **not** forward `trackedDownloadState` or `trackedDownloadStatus`. Consequently **`importPending` is not a DownloadingItem.status value**. Use normalized `completed` as the Done set, and `queued`/`downloading` as the active set. Paused, failed, warning, delay, unknown and other queue statuses fall back to Checking; a queue failure alone does not prove that the request failed. Only finite positive sizes with `0 <= sizeLeft <= size` support a percentage; invalid ETA is omitted.

The current official [Sonarr QueueStatus](https://github.com/Sonarr/Sonarr/blob/develop/src/NzbDrone.Core/Queue/QueueStatus.cs) and [Radarr QueueStatus](https://github.com/Radarr/Radarr/blob/develop/src/NzbDrone.Core/Queue/QueueStatus.cs) enums also include Unknown, Paused, Failed, Warning, Delay, DownloadClientUnavailable and Fallback. These are upstream source evidence; no queue status strings were observed live, and installed service versions were not verified. Keep the implementation tolerant of unknown values.

Seerr's `getQueue()` forwards only `includeEpisode=true`, takes `response.data.records` and never walks pages or exposes totalRecords. The inherited page size was **not confirmed live**. Queue coverage is therefore not proven exhaustive, irrespective of its exact default. An empty array can mean no download, disabled sync, missing service IDs, stale tracker data or a download outside the returned page.

**Amend stage rules 9–10:** do not infer “Looking for it,” “Still looking,” or “couldn't find it” from an empty queue or request age. For an approved, nonavailable request without positive progress evidence, use neutral **“Waiting for your library”**, with “Download progress isn't available right now” where useful. A known future movie release can still display “Not out yet · Coming <date>.” Restore search-specific stages only when a later server contract gives reliable search/queue coverage. Do not add direct Sonarr/Radarr access to clients in this phase.

Sources: [Media.ts:375](https://github.com/seerr-team/seerr/blob/e2f24cb46079746936516c723b09820360f95113/server/entity/Media.ts#L375), [downloadtracker.ts](https://github.com/seerr-team/seerr/blob/e2f24cb46079746936516c723b09820360f95113/server/lib/downloadtracker.ts), [servarr/base.ts:166](https://github.com/seerr-team/seerr/blob/e2f24cb46079746936516c723b09820360f95113/server/api/servarr/base.ts#L166).

### 2. Hydrate TV seasons from request detail

The list query joins `request.seasons` (SeasonRequest) but does not join **`media.seasons`** (Season availability). TypeORM QueryBuilder does not automatically load the eager media-season relation. Detail uses `findOneOrFail`, which loads eager Media/Season relations.

**Amend TV and phone sync:** after enumerating owned rows, hydrate media season status via `GET /request/{id}` for tracked TV requests whose season snapshot needs refresh, including completed requests still monitored/displayed. Deduplicate shared media within a sync and bound detail concurrency (maximum four). Preserve last successful snapshots on failure and mark their stage stale/Checking; do not generate new readiness events from stale/unknown season state. Refresh the tracked TV season snapshots with each polling sync, not just lazily when a card becomes visible, so offscreen transitions can notify. Account/generation guards apply to these detail calls too. A complete request list alone is not a complete TV readiness baseline when required detail reads failed.

**Never use `request.seasons[].status` as media availability.** SeasonRequest COMPLETED can represent AVAILABLE or DELETED and does not encode PARTIALLY_AVAILABLE. Read `media.seasons[].status` or `status4k`, selected by request quality. Existing full-ready/partial rules require a nonempty known requested season set.

Sources: [request.ts:125](https://github.com/seerr-team/seerr/blob/e2f24cb46079746936516c723b09820360f95113/server/routes/request.ts#L125), [request.ts:434](https://github.com/seerr-team/seerr/blob/e2f24cb46079746936516c723b09820360f95113/server/routes/request.ts#L434), [MediaSubscriber.ts:97](https://github.com/seerr-team/seerr/blob/e2f24cb46079746936516c723b09820360f95113/server/subscriber/MediaSubscriber.ts#L97).

### 3. Own requests and list omissions

`requestedBy=<own id>` is accepted without REQUEST_VIEW. The server forces users without REQUEST_VIEW/MANAGE_REQUESTS to their own rows even if that parameter is omitted; a different user's ID gets 403. Bind the filter to the identity returned by the same authenticated session and retain the local owner check.

Recognized filters are approved, processing, pending, unavailable, failed, completed, available and deleted; `all` follows the default branch. Sorting by modified means `request.updatedAt` descending by default. PageInfo contains pages, pageSize, results and page; enumerate all pages rather than stopping after a terminal page.

**`filter=all` still excludes media BLOCKLISTED (6).** Therefore disappearance is not proof that a request was deleted/cancelled. For previously tracked rows that vanish after a complete list sync, fetch their owned `/request/{id}` once to distinguish blocklist/deletion from removal. Preserve inaccessible rows as stale/unknown; remove only after a second 404 on a later successful sync, since the detail route converts database errors to 404 too. Never synthesize a failure from disappearance alone. This does not discover old blocklisted rows missing from the first baseline; that is an explicit v1 limitation. The 1,000-row cap and incomplete-sync policy remain.

The deployed middleware returns **403** for missing/dead sessions as well as insufficient permission. Source confirms this; no intentionally bad session probe was sent. The v4 cooldown/single-flight `/auth/me` decision tree remains required. The failed password login's 401 is a different case. No `/auth/logout` cleanup: it can delete the shared Jellyfin device.

Sources: [request.ts:69](https://github.com/seerr-team/seerr/blob/e2f24cb46079746936516c723b09820360f95113/server/routes/request.ts#L69), [auth middleware](https://github.com/seerr-team/seerr/blob/e2f24cb46079746936516c723b09820360f95113/server/middleware/auth.ts), [logout route](https://github.com/seerr-team/seerr/blob/e2f24cb46079746936516c723b09820360f95113/server/routes/auth.ts#L914).

### 4. Webhook identity is stable; push is full-ready or failed only

Use **`{{requestedBy_jellyfinUserId}}`**. It maps directly to `request.requestedBy.jellyfinUserId` in the deployed version. `requestedBy_username` maps to editable displayName and must not identify a recipient. Validate/normalize the Jellyfin UUID format (32 hex or canonical dashed UUID) identically at device registration and webhook ingestion. Unknown/missing IDs fail closed; no username/email fallback or backend Seerr key is needed.

The webhook has **no `is4k` placeholder** and **no season availability array**. Derive quality from the **validated event string's `4K ` prefix** and verify that the event matches the notification type/media type; reject unknown combinations rather than silently assuming HD. Source-generated event strings are:

- `<optional 4K >Movie Now Available` / `Series Now Available` (approval of already available media).
- `<optional 4K >Movie Request Now Available` / `Series Request Now Available` (completion subscriber).
- `<optional 4K >Movie Request Failed` / `Series Request Failed`.
- `<optional 4K >Movie Request Declined` / `Series Request Declined`.

Only the completion subscriber's **`Series Request Now Available`** checks that **all requested seasons** are AVAILABLE at the requested quality and refuses empty requested sets. The approval path's **`Series Now Available`** checks only the show's overall status, which can remain AVAILABLE when a newly requested season is missing. **Acknowledge and ignore `Series Now Available` for phone ready delivery**, including its 4K variant; foreground polling decides readiness from actual requested-season states. The ready-event allowlist is exactly **`Movie Now Available`, `Movie Request Now Available`, `Series Request Now Available`**, each optionally prefixed `4K `, paired with MEDIA_AVAILABLE and its corresponding movie/tv media type.

Partial availability has no webhook event and stays in foreground polling/chips. Enable types **88 = 8 AVAILABLE + 16 FAILED + 64 DECLINED**. Auto-approval emits MEDIA_AUTO_APPROVED (128), or an approval-path AVAILABLE event based on overall media status; it is not independently proof of TV requested-season readiness. Automatic-request-only notifications have notifySystem=false and do not go to the webhook.

Repeated completion saves/approval paths can emit readiness again. Keep portable event keys `v1|<requestId>|hd|ready|-` / `v1|<requestId>|4k|ready|-` and corresponding `failed` keys; DECLINED maps to failed. In-app partial signatures retain the v4 season-state scheme. Backend dedupe is mandatory.

Correct the v4 note claiming every season edit requires MANAGE_REQUESTS: ordinary users can edit their own TV season set **while the request is pending**. The route rejects edits after approval/completion. The one-full-ready-event policy still holds for ordinary users after approval; repeated events after administrator edits remain the accepted deferred case.

**Omit `itemId` from webhook-derived push.** `media_jellyfinMediaId` picks HD before 4K irrespective of request quality (and can pick 4K for an HD request). A phone tap resolves the matching-quality ID through its own Seerr session, falling back to My requests. No quality substitution.

WebhookAgent makes one axios POST with no explicit timeout and no retry/outbox; NotificationManager does not retry it. Missed webhook pushes remain the accepted v1 limitation; foreground polling catches up. The Phase 4 receiver must persist/ack promptly.

Sources: [webhook.ts:17](https://github.com/seerr-team/seerr/blob/e2f24cb46079746936516c723b09820360f95113/server/lib/notifications/agents/webhook.ts#L17), [MediaRequestSubscriber.ts:120](https://github.com/seerr-team/seerr/blob/e2f24cb46079746936516c723b09820360f95113/server/subscriber/MediaRequestSubscriber.ts#L120), [MediaRequest.ts:707](https://github.com/seerr-team/seerr/blob/e2f24cb46079746936516c723b09820360f95113/server/entity/MediaRequest.ts#L707).

### 5. Frozen template and remaining integration checks

`webhook-template.json` is the Phase 4 template validated by the deployed Test endpoint. **Values substituted by placeholders are strings**; `v` is a number. Media statuses arrive as enum names (for example `AVAILABLE`), not numeric statuses. Parse positive request/TMDB IDs strictly from decimal strings (empty is not zero). Each placeholder occurrence is replaced once per string; use it once in each field. A `TEST_NOTIFICATION` with empty identifiers is acknowledged/ignored, never routed to phones. The `{{request}}` and `{{media}}` special keys conditionally nest template content; they do not serialize arbitrary objects.

The capture validates a Test, not real ready/failure/4K substitutions. During owner-authorized Phase 4 deployment, verify a genuine full-ready and failed event against this template, stable requester UUID and event allowlist before enabling customer delivery. Do not claim live partial, 4K or real-ready validation from this Phase 0 capture. No optional real-request test was needed for the source-derived decisions.

## Review and completion record

Opus 5.5 high reviewed the source/probe scope first. Its substantive findings on missing media seasons, UUID identity, quality fallback, dropped import state, queue paging, blocklist omissions and logout effects are incorporated above. Its initial “no webhook Test” restriction came from the parent's overly narrow review brief; the user's actual instruction authorized the agreed Phase 0, including a temporary webhook Test. The temporary-settings Test endpoint satisfied that scope without changing saved settings. No real request was authorized or submitted.

The existing administrator session was reused to avoid additional sign-ins after the 401; it was not created, logged out or revoked. The probe's own private files containing API credentials/raw responses are temporary and are removed after final review; pre-existing credential artifacts belong to earlier work and are not removed by this task. Sanitized artifacts contain no customer IDs, titles, email addresses, cookies or keys.

**Phase 0 discovery is complete.** Opus 5.5 high returned **AGREE** in the third review round after the TV approval-event correction. Its final confirmation is in `phase0-review.md`; no required changes remain. The result includes the stated live-validation limits, rather than claiming those missing cases were tested. Notion completion is recorded on the existing feature and four product pages. App build/install, release publication and device verification are outside Phase 0.
