# WeaselPlex TV UI without Compose

Since 2026-11-22 (owner order) the TV app has no Jetpack Compose: no Compose sources, tests, compiler plugin, build
feature or dependency. Settings and every preference panel are AndroidX Leanback Preference; everything else is
Leanback (`VerticalGridView`/`HorizontalGridView`) or RecyclerView with custom Views. Do not add Compose back.

Where things are (`app/src/main/java/com/github/damontecres/wholphin/leanback/`):

| Package | What | Notes |
|---|---|---|
| `shell` | `MainActivity` content, `NavHost` (one fragment per back-stack entry), side menu, backdrop, setup layer, screensaver, tour, update / What's New prompts, request chips, `ScreenFactory`, `DestinationFragment` | [SHELL.md](SHELL.md) |
| `theme` | `AppTheme`/`AppPalette` (all nine themes), `NeonColors`, `TextRole`, `Accents.kt` (`PageStyle`, section/type accents), Neon card and control recipes | |
| `widget.card` | every card, `CardRowView`, `CardGridView`, image helpers, `clipToOwnHeight`, `requestSelectedFocus`, `AvatarImages` | [WIDGETS-CARDS.md](WIDGETS-CARDS.md) |
| `widget.control`, `.dialog`, `.text` | buttons, tabs, inputs, dialogs, context menu, item info, loading/error, text widgets | [WIDGETS-CONTROLS.md](WIDGETS-CONTROLS.md) |
| `settings` | Leanback Preference settings framework, every settings page, Home settings, `PreferencePanel` (view-options side panels) | |
| `playback`, `setup`, `home`, `detail`, `library`, `search` | the screens of each area, each with its `*Screens.kt` factory | [DETAILS.md](DETAILS.md) |

How the rewrite was organised: [CONTRACT.md](CONTRACT.md).

Lessons from testing on a TV (keep them in mind when changing lists and focus):

- The activity theme must set `preferenceTheme` and `defaultBrandColor`: Leanback's preference list dialogs read them
  from the activity theme, and crash without `defaultBrandColor` on an AppCompat theme.
- A multi-column Leanback grid places each item in its shortest column. Grid cards must all be the same height
  (`GridCardView`/`DiscoverItemCardView` reserve two title lines and a subtitle), or UP/DOWN land on the wrong card.
- With the keep-visible focus strategy (`FOCUS_SCROLL_ITEM`), `requestFocus()` on a grid focuses the first fully visible
  item, not the selected one; `CardStripView`/`CardVerticalGrid` override it (`requestSelectedFocus`).
- Selecting a position in a strip nested in another list can wait for a layout that never comes; the series overview
  scrolls to its episode with `setSelectedPositionSmooth` after the strip's first layout.
- Do not replace a grid's adapter when a page rebuilds its presenter (that resets the position and drops focus);
  `CardGridView` reuses the adapter for a presenter from the same call site.
- On Android 16 a BACK release can reach the page below after BACK popped the page above. Handle BACK releases only
  when the press was seen too.
- Lists turn `clipChildren` off so focus glows aren't cut; call `clipToOwnHeight()` so scrolled-out rows don't draw over
  headers.
- The shell saves a page's focused view when another page covers it (`focusToRestore`), because removing the page above
  makes Android move focus into the page below first. It saves the backdrop too (`backdropToRestore`): a Compose page
  re-submitted its backdrop when it came back, a kept fragment does not.

Found on the Shield (2026-11-22, second pass):

- A Leanback `VerticalGridView` keeps focus at its first and last rows unless `setFocusOutAllowed` says otherwise. A list
  under tabs or buttons needs `setFocusOutAllowed(true, false)`, or UP never reaches them (the Movies Recommended tab).
- A `HorizontalGridView` inside a list needs `setRowHeight(WRAP_CONTENT)`; with the default row height it collapses in a
  list (rows drew over each other in the Home settings preview).
- A view added with an `add(view, wrap(...))` helper loses its own layout params. Spacers are `SpacerView`s, which keep
  their size whatever params they get; other fixed sizes need `maxHeight` or explicit params.
- The side menu's rows are as wide as the open menu even when it is collapsed, so a plain focus search from a page's top
  controls can land in the menu. The shell keeps UP and DOWN inside the page; only LEFT opens the menu.
- A card strip entered with UP or DOWN focuses its selected card (`CardStripView.addFocusables`), as the Compose rows'
  focus restorer did.
- `adb shell input keyevent --longpress 23` and phone remote apps send a long press as one flagged key event. The
  activity turns that into the focused view's long click (`InjectedLongPress`); a View's own long click needs the key held.
- The Shield blocks screenshots while a profile with a PIN is current (`FLAG_SECURE`); use `uiautomator dump` text there.
  Screenshots of the 4K screen take about 5 seconds, longer than the player's controls stay up: send player keys
  without screenshots in between.
- Demo accounts for device tests (Alex, Sam, kid Riley) and their rules are described by the TV user-guide thread; the
  logins live outside git in `~/.config/weaseltv-guides/`. Never use the owner's own profile for tests.
- Playback is part of every device test, on every account, with both players (Settings → Advanced → Playback Backend).
  MPV draws on its own surface: a screenshot taken before its first frame is black, and its media session reports no
  position, so read MPV's state from logcat (`MpvLogger`, "playback restart complete").
- The open side menu (480px) lies over the page's left edge in both directions: UP/DOWN from a page searches only the
  page, and UP/DOWN inside the menu searches only the menu (`AppShell.route`). Without the second rule, Up from Search
  found Watchlist's ALL tab before the profile row, so a kid couldn't reach Select user from that page.
- The series page follows the player's queue: the player records each episode it starts
  (`ActivePlaybackRegistry.lastEpisode`) and the page selects it on return (`SeriesViewModel.onBackFromPage`).
- Kids' Collections loads every collection in one request (for its groups), and the kid rules check each one with a
  request of its own: 386 for Riley. Every request is a ~0.3 s round trip through NC1; home devices route that way on
  purpose, like customers. The checks run 16 at a time (`KidContentGuard.MAX_CONCURRENT_LOOKUPS`, each with its turn
  before its time budget starts), and the Jellyfin API client allows 20 requests per server (`AppModule`; OkHttp's
  default is 5). Cold first visit on the Shield: about 80 s one at a time, 37 s with 4, 27 s with 8, 14–17 s with 16.
  The rest is Jellyfin's own list query (~4 s). Don't drop the checks: the app hides more than Jellyfin's ratings do.
- A Compose `LaunchedEffect` inside a page that only composes once something exists (the player, a loaded item) runs
  after that thing exists. In a Fragment, `onViewCreated` runs before it: start such work when the flow first emits it
  (`PlaybackFragment.updateDensity` threw "lateinit property player" on every playback start until 2026-11-22).

Testing without the Shield: the TV-sized emulator AVD `weasel_tv_audit_api36` (x86_64; build with
`./scripts/build-alpha.sh --abi x86_64`), started headless with `emulator -avd weasel_tv_audit_api36 -no-window`.
Build through `~/work/kid-profiles-orch/gradle-slot.sh`.
