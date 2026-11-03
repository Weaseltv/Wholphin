# Phase 0 review confirmation

Reviewer: Claude Opus 5.5, high reasoning. Date: 2026-10-13. Three T3 delegated rounds: scope/source review; evidence review with one required TV-event correction; narrow confirmation after correction. No required findings remain.

# Phase 0 confirmation review (Opus 5.5, high) — 2026-10-13

Scope: only the round-2 M1 fix and whether the patch introduced a contradiction. I did not repeat the full source audit, made no network calls and did not delegate. Source: `/tmp/weaselplex-phase0-source` at HEAD `e2f24cb46079746936516c723b09820360f95113`.

## Verdict: AGREE

No must-change remains.

## M1 is fixed

- **Source re-checked.** In `MediaRequest.notifyApprovedOrDeclined()` (`server/entity/MediaRequest.ts:653-666`), the approval path checks only `media[status|status4k] === AVAILABLE`. `sendNotification` (`:722`) then emits `<4K >${mediaType} Now Available`. The completion subscriber works differently:
  - For TV (`MediaRequestSubscriber.ts:130-151`), it filters fresh `latestMedia.seasons` by requested season numbers and by quality (`status4k` or `status`). It requires `availableSeasons.length > 0 && === requestedSeasons.length` and then emits `<4K >Series Request Now Available`. Because available seasons are a subset of requested seasons, an empty requested set is refused. The doc's "refuses empty requested sets" is accurate.
  - For movies (`:65-80`), it checks the overall status at the request's quality and emits `<4K >Movie Request Now Available`.
- **findings §4 (lines 80-82).** The text now limits the all-requested-seasons claim to `Series Request Now Available`. It says that `Series Now Available` uses only the show's overall status, and it says to acknowledge and ignore that event, including the 4K variant. The ready allowlist is exactly `Movie Now Available`, `Movie Request Now Available` and `Series Request Now Available`, each with an optional `4K ` prefix and paired with MEDIA_AVAILABLE and a movie/tv media type. Line 82 now calls the approval-path AVAILABLE event "based on overall media status" and says it is not proof that TV requested seasons are ready. That is correct.
- **Implementable.** `webhook-template.json` carries `notificationType`, `event` and `mediaType`, so the receiver can enforce the event, type and media-type pairing.
- **Addendum §7.2.** It states the same allowlist and the same reason for ignoring `Series Now Available`.
- **Evidence copy.** `phase0-evidence.md` is byte-identical to `runbooks/request-notifications/phase0-findings.md` (`diff` rc=0).

## No new contradictions

- Line 75 still lists `Series Now Available` among the source-generated strings. That inventory is correct, and line 80 qualifies it. "Reject unknown combinations" (line 73) and "acknowledge and ignore a known event" do not conflict.
- The dedupe keys (line 84) apply only to accepted events. Ignoring `Series Now Available` removes one source of duplicates and adds none.
- **Optional wording edits all hold up against the source:**
  - "Main API key (admin-equivalent)" (line 9) is fine.
  - "Enum names" (line 96) matches `{{media_status}}`/`{{media_status4k}}` in the template.
  - "Second 404 before dropping a tracked row" (line 63) is consistent with the detail route mapping errors to 404.
  - Pending TV season edits (line 86) match `PUT /request/:id`, `server/routes/request.ts:485-501`. The owner may edit when `mediaType === 'tv'`; non-TV edits need REQUEST_ADVANCED; anything not PENDING gets 409.
- **Evidence limits are still explicit and not overclaimed:**
  - The password attempt got one 401, with no retries.
  - Impersonation is not a cookie login.
  - Progress arrays were empty.
  - No live TV or 4K request, and no real ready or failed event, was observed.
  - The Test returned 204 with temporary settings, saved config was unchanged, and the receiver was deleted.
  - No real request was made and no customer push was sent.

## Note (no change required)

The failed-side allowlist (`Request Failed`, `Request Declined`, optional `4K `, paired with types 16 and 64) appears in the event inventory and the dedupe mapping rather than as an "exactly" sentence. That is enough for Phase 4. Mirroring the ready sentence would only be cosmetic.
