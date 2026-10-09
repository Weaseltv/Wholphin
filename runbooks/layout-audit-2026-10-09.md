# WeaselPlex Alpha layout audit

The owner requested emulator navigation and authorized ThinkCentre for this audit.
Routine Alpha iterations still follow the separate fast-iteration policy.
Builds ran on the VPS. The temporary ThinkCentre emulator used a 1920 × 1080
landscape display at 320 dpi (960 × 540 dp), D-pad input and the current Alpha
preferences. Navigation was read-only; playback, watch state, favorites and
requests were not changed.

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
