# Continue Watching / Next Up removal sync

## Cause

The customer Android TV app stores removed series on Jellyfin in display preferences
`default`, client `Wholphin`, custom preference `removeNextUp`. `LatestNextUpService`
uses those entries to filter Next Up. Alpha previously used client `Wholphin (Debug)`
through `DisplayPreferencesService.DEFAULT_CLIENT`, so the two TV builds read different
lists even for the same member.

The iOS and Android phone product shares `utils/atoms/dismissedNextUp.ts` in
Weaseltv/streamyfin. Its previous dismissal action only updated the device's MMKV
`dismissedNextUpSeries` atom. It neither read TV removals nor wrote its own removal to
Jellyfin. Watched/resume changes use Jellyfin separately; apparent synchronization
of those changes does not establish synchronization of this local dismissal list.
The owner confirmed using **Remove from Continue & Next Up** on iPhone. The reported
reverse-direction disappearance was not reproduced on devices in this investigation;
the old mobile action's source contains no server write.

These are client storage/refresh problems. No media server, Jellyfin or nginx
configuration change is required. The phone bug affects both iOS and Android phone.

## Shared contract and migration

All updated clients read and write the customer's existing storage:

- `GET` / `POST /DisplayPreferences/default?userId=<member>&client=Wholphin`
- Preserve the complete display-preferences DTO and all unrelated `CustomPrefs` keys.
- `CustomPrefs.removeNextUp` is a JSON **string** with the existing TV format:

```json
{"value":{"00000000-0000-0000-0000-000000000001":"2026-11-07T10:00:00.123"}}
```

New timestamps use UTC, serialized without an offset for compatibility with TV's
existing `LocalDateTime` serializer. Phone IDs normalize compact/dashed UUIDs and
case, and writes use dashed UUIDs accepted by the Kotlin serializer.

Alpha imports its old list once, merging the newest timestamp per series into the
shared list. A marker in the old Alpha namespace prevents a restored series being
imported again, including after app restart. Migration memory is scoped by server
and user. Home layout/debug preferences keep their existing separate behavior;
only the dismissal list is shared.

The mobile change imports the signed-in member's old device-only dismissals on the
first successful sync, then clears those migrated local entries only after the
server accepts them. Failed saves remain retryable. Subsequent reads use the shared
list. Requests and query caches are scoped by server and member; late callbacks
cannot update a newly signed-in account. Local read/modify/write operations are
serialized and every mutation reads fresh server preferences.

Both Resume and Next Up episode feeds apply dismissals. Movie/library browsing is
unaffected. Removing a series preserves watched state and resume progress. Explicit
play or marking played clears its shared dismissal; later playback timestamps also
allow resumed episodes. Background cleanup checks timestamps again before deleting
entries so a newer removal saved during its scan is retained.

Mobile Home refetches the list on foreground/focus, explicit refresh, and every
30 seconds while Home is active. TV Home reads the shared list when loading its
rows; its existing refresh/navigation behavior remains in place.

## Validation and delivery

Work stayed on ThinkCentre (`justin-ThinkCentre-M73`). No live member list was
modified for investigation, no device navigation/playback tests were run, and no
stable release was published. Phone API tests use an in-memory server; TV tests use
server-backed preference fixtures. Alpha install and native iPhone verification
are recorded when complete.

Final local validation: **14 TV checks passed** (six new synchronization regressions
plus eight existing Next Up checks), and **18 mobile checks passed** (nine new API
regressions plus nine existing ownership/playback-refresh checks). Mobile TypeScript,
changed-file Biome checks, and both iOS/Android production Metro/Hermes exports passed.
No physical Android phone testing is claimed.

The arm64 **WeaselPlex Alpha built in 2m 11s and installed in place on the owner's
Shield with ADB `Success`**, retaining the accepted pre-load implementation and
capacity correction. The owner verifies the visible rows; the agent did not launch
or navigate either TV build or modify the member's live list for this investigation.

Mobile source is [03af3f80](https://github.com/Weaseltv/streamyfin/commit/03af3f80ab79168a62e60450b1c5e14633e03ef8),
[draft PR #178](https://github.com/Weaseltv/streamyfin/pull/178).
The required PR-branch Check TestFlight was started from that frozen commit through
[the designated M4 workflow](https://github.com/Weaseltv/weaseltv-apps/actions/runs/42361706451).
Native iPhone/owner acceptance remains pending; a successful JS export is not device
validation. No stable app release, merge or release tag is included in this change.
