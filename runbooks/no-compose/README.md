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
  makes Android move focus into the page below first.

Testing without the Shield: the TV-sized emulator AVD `weasel_tv_audit_api36` (x86_64; build with
`./scripts/build-alpha.sh --abi x86_64`), started headless with `emulator -avd weasel_tv_audit_api36 -no-window`.
Build through `~/work/kid-profiles-orch/gradle-slot.sh`.
