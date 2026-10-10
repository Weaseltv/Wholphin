# WeaselPlex Alpha layout audit

The owner requested emulator navigation and authorized ThinkCentre for this audit.
Routine Alpha iterations still follow the separate fast-iteration policy.
Builds ran on the VPS. The temporary ThinkCentre emulator used a 1920 × 1080
landscape display at 320 dpi (960 × 540 dp), D-pad input and the current Alpha
preferences. The audit later expanded to the owner-authorized Shield, actual playback controls
and conditional native UI fixtures. Brief playback checks can advance playback
progress. Temporary test preferences and filters were restored; no requests,
watch-state changes, account changes or destructive menu actions were submitted.
The chronological notes below describe intermediate builds; the final outcome
section supersedes their earlier pending checks and test counts.

## Final outcome — October 10, 2026

The expanded audit is complete for the 148 screen/interaction cases in the
[coverage matrix](layout-audit-coverage-2026-10-10.md). The earlier chronological
notes retain intermediate findings; their pending checks and older test counts
are superseded by this outcome.

- Shared measured headings, row/card/caption bounds and focus decoration budgets
  now cover Home, all four Recommended/Library pages, detail pages, collections,
  person/discovery pages, grids and alternate list layouts. Complete focused
  content fits; partial unfocused neighboring rows are normal scrolling content.
- Preferences, nested menus, long descriptions, setup/account errors and player
  controls share bounded panels, readable text and page-colored focus styling.
  Expanded secondary actions remain outlined at rest. Alphabet letters retain
  white inactive text and page-colored active text/glow without a circle.
- Page accents and collection provider artwork are shared across Home and grids.
  Header titles preserve whole words and reserve measured clock space. Long
  chapter labels retain their timestamps and stable image dimensions.
- Cold Search now loads its library categories without visiting Home first.
  Search/Requests reserve clock space, keep View Options visible and release
  D-pad focus vertically; inactive fields also release Left/Right to adjacent
  controls while editing fields retain horizontal cursor movement.
- The final native suite passed **120 tests, zero failures, zero errors and zero
  skips**. Actual Compose fixtures cover 800/960 dp TV widths and enlarged font
  scales up to 1.5, late loading, long captions, first/final focus and repeated
  navigation. Motion recordings separately check repeated grid and detail focus
  transitions; static captures are not used to claim motion stability.
- Actual Shield/emulator sweeps cover available server data from first to final
  grid/menu entries, all seven Home rows, all four libraries, local/discovery
  details, Search/Requests, Settings, setup and playback controls. The final
  Alpha25 search/header delta passed on both devices. The Shield microphone
  reached Android's permission prompt, which was dismissed without granting
  access; conditional voice-overlay states passed native fixtures.
- Final arm64 and x86_64 Alpha builds succeeded on the VPS. Both devices accepted
  in-place installs with `Success`, preserving Alpha accounts/preferences. Both
  were returned to Home. No stable/customer release was published.

The owner's server lacks music, photo and live-TV libraries. Those actual
components, unavailable/error states, large menus and thumbnail previews are
covered by native fixtures rather than claimed as real server reproductions.
External-player UI belongs to another app. This result covers the recorded
viewport/data/state matrix; it is not a guarantee about every future server,
accessibility setting or device. No known unresolved layout finding remains in
the audited matrix. The active Alpha build/cache is retained; the VPS has 56 GB
free after the build.

## Shared layout rules

- Home and Recommended reserve the measured focused row, the next complete
  kicker/title/rule, the row's trailing gap and a bottom gutter. Header content measures naturally;
  fitting never relies on a fixed height left over from an older layout.
- Detail pages reserve complete titled rows, including captions, focus scale,
  outline and glow. Explicit row priorities handle cast/seasons arriving after
  lower rows have already loaded. Action-bar height is measured separately.
- Media, cast, chapter, discovery and playback-overlay rows share
  `FocusSafeLazyRow`. Grid focus scrolling includes the whole card and caption,
  with additional end padding for focus effects and the position footer.
- WeaselPlex action labels stay expanded. More and other secondary actions are
  outlined at rest; Play, Resume and Restart retain primary styling.
- Alphabet navigation uses page-colored text and glow for hover/focus/current
  letters, white inactive letters, and no circular indication.

## Navigation matrix

Review both the initial view and a focused card near the bottom; compare poster,
caption/year, focus effects, and the next complete section heading. A scrolling
grid may show partial **unfocused** neighboring rows; the focused card and its
caption must fit. Long descriptions should end on a complete ellipsized line.

| Area | Cases |
| --- | --- |
| Home | Continue Watching, Streaming Services, Movies, TV Shows, Stand Up Comedy, Sports, Weasel's Picks |
| Four libraries | First Recommended row, subsequent Recommended rows, Library first/lower rows, Genres, collection/studio tabs |
| Watchlist / Collections | First/lower rows, last Watchlist row, alphabet hover/focus |
| Local detail | South Park seasons and cast, season 29 episodes and expanded footer actions, movie cast/chapters, episode details |
| Collections detail | Streaming provider and curated collection, initial and focused grids |
| Person | Biography/header actions and filmography posters/captions |
| Search / Requests | Search field, local search results, request search result posters/captions and focused last result |

`DetailViewportStateTest` protects against late-arriving first-row data and
caption height changes selecting the wrong row measurement. Build x86_64 Alpha
for emulator verification; build arm64 Alpha separately for Shield installation.
Neither the emulator build nor this audit publishes a stable release.

## Results and limits

- The two focused viewport regression tests passed with
  `:app:testWeaselfinDebugUnitTest --tests com.github.damontecres.wholphin.ui.components.DetailViewportStateTest`.
  Existing CollectionFolder and Watchlist test fixtures needed their newly required
  service arguments before test compilation could run.
- Home/Recommended headings, initial detail posters, focused grid captions/years,
  and season episode actions fit the audited TV viewport. More is outlined at rest
  and uses the page-colored border/text/glow on focus.
- Movies/TV genre cards retain rounded focus borders. Comedy/Sports Genres show
  an empty result state for this account.
- Playback overlays use the shared row component but playback was not started.
  An external discovery movie header/action bar was checked without submitting a
  request; its additional rows were empty. Discovery series details use the shared
  measured detail header but were not separately visually audited.
- Both emulator and Shield Alpha builds succeeded. The arm64 Alpha installed
  directly from the VPS to the Shield with `adb install -r` returning `Success`.
  No stable release was published. The emulator crash buffer was empty.
- Coverage uses representative titles and the owner's current Alpha sizing,
  not every possible metadata value or display resolution.

## Motion regression follow-up

The owner's three subsequent recordings exposed a regression after the static
layout audit: vertical card vibration, horizontal-navigation jumps, and a second
vertical correction when entering the South Park seasons row. Every supplied
video frame was extracted and reviewed around the transitions. In the show
recording the section rule moves from y=222 to y=130, then corrects again to
y=112 while focus remains in the row; this repeats on the next Play-to-row entry.
Static screenshots did not establish motion stability.

- Whole-row visibility requests added by the clipping fix overlapped automatic
  child focus scrolling. Whole-grid-card requests also disagreed with the TV
  pivot policy used for the inner image. Remove the extra row request and use a
  shared visibility-only scroll specification in WeaselPlex grids and detail
  pages. Once the complete card is visible, its image agrees that no additional
  movement is needed. Retain whole-card caption/decoration clearance.
- Subtract the actual rounded pixel padding when measuring row content height,
  so fractional dp rounding cannot feed back into the next padding measurement.
- Six unit checks passed: the existing two first-row measurement tests and four
  scroll checks covering captions versus inner images, horizontal focus changes,
  fractional pixel remainders, and oversized targets.
- Recorded baseline and fixed emulator navigation, inspecting every recorded
  frame. In the fixed grid, the unaffected neighboring poster column stays at
  the same vertical position throughout repeated left/right focus changes.
  Normal vertical moves still reveal complete focused cards and captions.
- Repeated Play-to-seasons transitions were recorded both at Specials and
  Season 29. The fixed section rule remained at y=223.5 in every recorded frame,
  without a delayed second correction. Episode-row/footer transitions were also
  checked. The original show jump did not reproduce exactly in the emulator's
  baseline recording; the owner's higher frame-rate recordings establish that
  symptom. Emulator results cover this viewport and representative titles,
  rather than proving all device timing or display combinations.
- Continue including idle pauses and repeated focus transitions in requested
  emulator audits, alongside complete-card/caption checks. Do not infer motion
  stability from static screenshots alone.


## Expanded full-app audit after v1.2.29 — chronological notes

The owner explicitly requested an exhaustive audit and authorized direct Shield
ADB navigation and the ThinkCentre emulator. Builds remain on the VPS. This
section supersedes the earlier audit's scope and does not claim completion or
publication. The current batch is tracked in draft PR 53.

Verification uses the logged-in Shield, the API 36 emulator, and native-rendered
Compose fixtures at 960 × 540 dp and 800 × 540 dp, including font scales 1.2 and
1.5. Conditional libraries without current server data receive component-level
fixtures; that is distinct from testing real network data. Playback checks are
brief and do not submit requests, toggle watched/favorite state, delete media,
or send server reports.

Findings addressed so far include:

- Home and detail header sizing, whole-word long titles, wrapped metadata, and
  late-loading focused card captions. Native regressions reproduced the late
  caption clipping and incorrect remote page-jump focus before their fixes.
- Shared neutral dialogs, bounded option panels, natural headings, complete
  preference focus bounds, nested filter navigation, and keyboard-safe editors.
- Requests search fields trapped vertical D-pad navigation; their native
  regression now passes. Movie/show result badges use the current library colors
  and shared poster insets. Long request season labels measure naturally.
- Collection previews, collection/request/music/photo action bars and playlist
  pickers now share viewport and focus rules. A 30-entry playlist picker and
  first/final collection preferences pass at enlarged text.
- Alternate media list layouts lacked edge clearance and used separate focus
  styling. The native regression failed with the first row at the clip edge;
  standard and dense movie/show/music/generic variants pass with shared padding,
  full focus bounds, page-color outline/glow, and square list surfaces.
- Player track information, subtitle download/delay controls, update notes,
  licenses, guide/DVR choices, asynchronous language menus, music queue/lyrics,
  and photo filter controls have bounded native regression fixtures.

Screen-hierarchy capture requires a fresh unique output file and a successful
Android dump response. A fixed filename could reuse stale XML when Android
failed to dump without a nonzero exit code; affected focus readings must be
rechecked. Screenshots and native assertions remain separate evidence. Filenames
such as “first” or “last” are not sufficient: focus, row identity, and grid
position must agree with the image before a case is marked verified.

The coverage ledger remains incomplete. Remaining device sweeps include library
menu parity, collection and discovery details, player controls, conditional
setup states, and true first/final entry checks. No new stable release has been
published from this batch.

### Continued audit, October 10

- Resumed direct Shield testing after the owner's power returned. Global Collections
  reached the actual final 66/66 card; Movies Collections reached 57/57 and the
  Angel collection reached 36/36, with complete selected posters/captions.
- Checked the real movie overview, technical information through Subtitle (11)
  Swedish, the 13-position subtitle chooser, and playlist-name input with the
  keyboard visible. The overview now shares page-colored focus border/glow, and
  its old second on-focus scrolling request is disabled under the WeaselPlex theme.
- The long playback chooser test's programmatic scroll fought its retained first
  focus. Replaced that test interaction with actual D-pad navigation. All first/last
  audio and subtitle entries then passed; this was a test conflict, not evidence of
  a remaining device navigation loop.
- A long chapter-label fixture exposed text consuming the timestamp's space.
  Chapter cards now reserve a stable wide-card size and a two-line ellipsized name,
  avoiding late image loads changing card widths.
- Cold-start Search reproduced a separate issue: only People appeared until Home
  had loaded first. Search now observes navigation-library types and refreshes an
  existing query when they arrive, preserving excluded types. Added a delayed-library
  initialization regression test. Latest Alpha/device recheck is still pending.
- The comprehensive layout suite completed 79 cases with one remaining assertion
  in the new chapter fixture: its original check treated intentional two-line
  ellipsis as clipping. Updated that check to inspect actual visible line bounds.
  The corrected suite still needs a complete passing rerun.

This audit remains in progress. No customer release has been published from this
work, and native fixtures for unavailable server libraries are recorded separately
from screens verified with the owner's real data.

### Continued audit after Shield power recovery (Oct 10)

- Direct Shield ADB and native 4K PNG capture resumed. The post-recovery 1080p screenrecord encoder produced artifacts; those images are excluded from color/visual sign-off. Native screencaps retain clean rendering.
- Cold-opening Search before navigation libraries loaded exposed a real startup race. Search now observes accessible library kinds, preserves current type exclusions and refreshes an active query when kinds arrive. The failing-before regression now passes. Real cold Search displays Movies, TV Shows, Episodes and People again.
- Shared SeasonCard now includes its caption in automatic focus visibility, covering Search, extras and the player queue. The native enlarged-text caption regression fails without this fix and passes with it. BannerCardWithTitle uses the same rule. Shared animated caption padding retains a constant rounded pixel budget.
- Player chapter cards now retain stable image dimensions while loading, and long names use two bounded lines while keeping the timestamp visible. Queue and chapter overlay tests actually navigate twenty cards to their final item at enlarged text.
- The latest combined native layout/regression suite passed 83 tests with zero failures, errors or skips. Actual device menu/page checks remain in progress; this result is not a substitute for finishing that audit.

### Expanded menus and setup coverage (Oct 10, ongoing)

- The broad native suite subsequently passed 95 tests. Full SeriesOverviewContent
  tests exercise thirty season tabs, enlarged episode copy and eight repeated
  card-to-footer transitions. Full SwitchUserContent tests cover QR instructions
  and code, twelve profiles, and connection errors at 1.5 font scale.
- A long account-connection error hid the retry action. The fresh connection error
  state now has bounded, D-pad-scrollable content and an explicit focus handoff to
  Get a new code. The regression uses remote keys to reach the complete action.
  The normal QR pane retains its previously verified layout.
- A separate long context-menu heading reproduced clipping of the first action
  below the viewport. Context menus and preferences now share fitted panel-title
  measurement, preserving word boundaries and a bounded heading. The regression
  navigates twelve actions at enlarged text and verifies each complete focus bound.
- Native Shield screenshots confirm the chapter row's actual first Chapter 1 and
  final Chapter 20; full subtitle and Play With child menus; and episode technical
  information through its final subtitle section. Child menus wrap from last to
  first, so key counts alone are excluded as evidence of reaching the last entry.
- Alphabet D-pad focus was reviewed across all four libraries, Watchlist and
  Collections: inactive letters white, active letters in the page accent with
  text glow, without a circle. Pointer hover remains a separate check.
- Global Collections sort, all eight filters and view options were checked through
  their final entries. Year and Decade settle to a readable No results state with
  this server, rather than supplying selectable values.

Remaining real-device boundaries, player states and conditional flows are still
being checked. This is an Alpha batch; no customer release has been published.
