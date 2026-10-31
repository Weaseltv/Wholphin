# WeaselPlex request stages (TV phases 1–2)

The source contract is `runbooks/request-notifications/phase0-findings.md`.
The implementation is `services/RequestStage.kt`; examples for the phone port
are in `request-stages.fixture.json`. These are documentation fixtures, not a test suite.
The phone port consumes their expected event keys, retry/season transitions,
initial seeded baseline and push/poll presentation-order cases in its unit tests.

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
| Checking your library | A download was previously observed for this request, its progress has disappeared, and library availability is not yet confirmed |
| Not out yet | Movie has a known future release date (metadata only) |
| Request accepted | Approved/completed request lacks availability and positive progress evidence, with no remembered download; explain that progress is not yet available |

A partial TV row shows the stage for the remaining seasons as secondary text.
Progress is bytes-weighted for valid active rows, not whole-request completion.
ETA is shown only if it parses to a future instant. Unknown queue states stay
neutral. Empty queue, request age and a completed request are not proof of
search failure or availability. The account journal remembers observed downloads
across refresh/restart. A disappearing queue after observed progress shows
Checking your library, without retaining an obsolete percentage or inventing
completion/failure. Ready/failed/confirmed removal/cancellation clear that marker.

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
focus after saving the focused page/rail region and its child, suppresses OK keys pressed before
its 700 ms guard, and restores focus on dismissal/Back/one minute without input.
The top-center poster card is flat and square, with a one-pixel Requests-teal
border and solid teal left stripe. Its uppercase title uses Audiowide; status,
separate year/type line, copy and buttons use Exo 2. Only the selected Watch now
button glows; the card, poster and Dismiss remain square without glow. The
instruction is "Your request is ready." without OK/Back hints. The thin solid
teal bottom bar shows the remaining minute and resets with remote input.
Pending events are acknowledged on dismissal, activation or a visible timeout; an unsafe interruption leaves them pending for the next safe moment. Multiple updates form one
chip. Per-account Settings → More → Request notifications defaults on; the
Requests badge remains the fallback to ready updates.

Dismissal restores focus before the chip is removed; it never falls back to
requesting the whole app, whose first target is the profile button. My Requests
also captures the exact selected action (Details/Watch now, Cancel or Refresh).
The saved page region is re-entered first, then that stable control is explicitly
focused so a tab entry policy cannot leave selection on My Requests. Controls
retain their focus requester through status/label changes. Disposed controls and
regions are never requested again. Other pages and the rail retain their existing
region/row/item restorers.

A successful locally submitted request seeds a separate, memory-only confirmation
in the account state. Request sent / Request updated uses the same poster card
at bottom center for six seconds, without actions or focus acquisition. The
ready-popup preference does not disable submission feedback. Normal presentation
guards still apply; a ready popup takes precedence and expired confirmations do
not replay. Submission does not acknowledge availability events or change badges.

The Requests badge sits above/right of the icon in expanded/collapsed navigation.
It counts unread request IDs with ready/partial stages, independently of the popup
setting. My Requests clears all unread IDs; a single chip primary action clears
that ID, while a grouped View requests action clears all. Dismiss, Back and timeout
acknowledge the popup without clearing unread IDs. A no-longer-ready/confirmed-gone
request stops contributing. State is durable per account, with a 9+ display cap.

No separate backend account/password is required: use the existing website SSO
and silent request-service Quick Connect path. Session expiry uses one separately
throttled identity probe and one silent reconnect; stored password authentication
is never automatically retried on an expiry.

TV My Requests can additionally display the personal v1 live-progress overlay
from the pinned requests host. Its download/unpack/import/Finishing up labels
are presentation-only; they do not change this canonical stage fixture, request
history or notification journal. Ready/partial/failure hints trigger a verified
Seerr refresh through the existing account/client guard. See
`runbooks/request-notifications/tv-live-progress.md` for transport, lifecycle,
fallback, source identity and owner verification.
