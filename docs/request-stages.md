# WeaselPlex request stages (TV phases 1–2)

The source contract is `runbooks/request-notifications/phase0-findings.md`.
The implementation is `services/RequestStage.kt`; examples for the phone port
are in `request-stages.fixture.json`. These are documentation fixtures, not a test suite.

Use request quality (`is4k`) consistently for media status, season status, queue,
and library ID. Never substitute the other quality. Evaluate in this order:

| Stage | Evidence |
| --- | --- |
| Couldn't be added / declined / not available | Request declined/failed, or quality-specific media blocked/deleted |
| Ready to watch | Movie available; TV has a nonempty requested-season set and every requested season available |
| Some episodes ready | At least one requested TV season partly/fully available, but not all fully available |
| Waiting for approval | Request pending |
| Adding to your library | Nonempty matching queue, every row completed |
| Downloading N% | At least one queued/downloading row with finite positive size and valid remaining bytes |
| Checking | Queue exists but its state/bytes are unknown; TV requested-season set is unknown |
| Not out yet | Movie has a known future release date (metadata only) |
| Waiting for your library | Approved/completed request lacks availability and positive progress evidence |

A partial TV row shows the stage for the remaining seasons as secondary text.
Progress is bytes-weighted for valid active rows, not whole-request completion.
ETA is shown only if it parses to a future instant. Unknown queue states stay
neutral. Empty queue, request age and a completed request are not proof of
search failure or availability.

List responses omit media-season availability: refresh TV media seasons from
owned request detail, at most four requests concurrently. A failed season
refresh retains its last-known stage (Checking if none) and cannot generate readiness events. Display old
terminal rows for 30 days; exhaustive sync/baseline tracking remains separate.

Removing already completed media shows Not available without generating a failure notification.

Portable event keys: `v1|requestId|hd-or-4k|ready-or-partial-or-failed|signature`.
Ready/failed signature is `-`; partial is sorted available season pairs, e.g.
`1:A,2:P`. Device stores are scoped by request server/user, with WeaselPlex's
pinned server normalized to `weaselplex`. Initialization is durable: existing
terminal rows are baselined, newly submitted local rows are exempt. Polling,
not phone push, is the only TV event source in these phases.

UI: Search / My Requests. My Requests has Ready, In progress and Needs attention,
refresh, update age, title/year/poster, stage, bytes progress/ETA and requested
date. Watch now opens the matching-quality library title; Details remains when
no matching library ID is known. Cancel acts on the exact owned request.

The nonmodal chip waits for a resumed, focused application window, browsing,
no screensaver/dimming, no dialog and no focused editable field. It takes primary
focus after saving the page's focused child, suppresses OK keys pressed before
its 700 ms guard, and restores focus on dismissal/Back/15 seconds without input.
Pending events are acknowledged on dismissal, activation or a visible timeout; an unsafe interruption leaves them pending for the next safe moment. Multiple updates form one
chip. Per-account Settings → More → Request notifications defaults on; the
Requests badge remains the fallback to ready updates.

No separate backend account/password is required: use the existing website SSO
and silent request-service Quick Connect path. Session expiry uses one separately
throttled identity probe and one silent reconnect; stored password authentication
is never automatically retried on an expiry.
