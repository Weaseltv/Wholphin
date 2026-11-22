# The no-Compose shell: what screens get from it

Written by the SHELL agent (2026-11-22) for the HOME, DETAILS, LIBRARY, SEARCH (and SETTINGS, PLAYBACK, SETUP) areas.
Code: `app/src/main/java/com/github/damontecres/wholphin/leanback/shell/`. Read `CONTRACT.md` first; this page only
adds what the shell does around your screens.

## 1. The frame

`MainActivity` (same class, same intents and lifecycle rules) sets one View tree built by `AppShell`. Bottom to top:

| Layer | What |
|---|---|
| setup layer | `SetupDestination.Loading` (spinner), `ServerList` / `UserList` fragments from `leanback.setup.SetupScreens`, or the app layer; 700 ms cross-fades between them, like the old NavDisplay |
| app layer (signed in) | `BackdropView`, `SideMenuView`, the pages (`NavHost`, container `R.id.lb_shell_pages`), the clock, request chips, the coach-mark tour overlay |
| over everything | the in-app screensaver (`ScreensaverView`, also used by `WholphinDreamService`) and its dim |
| own windows | the update popup and What's New (`ShellPrompts.kt`) |

The app layer exists only while the setup destination is `AppContent(current)`. Leaving it (profile chooser, server
list, loading) removes every page (ViewModels cleared), exactly as the old AppContent NavEntry did. With
`!signInAutomatically` or the kid "hide on stop" rule, the app layer is removed when the activity stops and a spinner
shows until the next setup screen.

## 2. Pages (`DestinationFragment`)

- One fragment per back-stack entry, created by `ScreenFactory.create(destination, preferences)`. The shell sets
  `destinationOrNull`, the `ARG_DESTINATION_KEY` argument (`NavHostState`) and `pageStyle` **before** the fragment is
  added, so `requireDestination()` and `pageStyle` work from `onCreate`.
- A page under another one is hidden (`onHiddenChanged(true)`, view `GONE`) and keeps its views, scroll positions and
  ViewModels. Popping removes it. The same destination twice in the stack gives two fragments.
- `ScreenFactory` returning null shows `UnsupportedScreenFragment` ("Unsupported item type: …"); until your area
  lands, every one of your destinations shows that.
- Activity recreation: pages whose destination still matches the restored back stack are kept (FragmentManager
  restores them, `NavHostState` gives back their destinations); the rest are dropped and rebuilt. After process
  death the FragmentManager restores `OrphanPageFragment` stand-ins and the shell rebuilds every page fresh from the
  saved back stack. Do not keep state that only lives in fragment arguments.
- `isTopScreen` (new): true while the page is the visible top page.

### Layout

- Pages with the side menu (`showsNavDrawer`, i.e. `!destination.fullScreen`) get layout margins from the shell, the
  old `NavDrawer` content padding: WeaselTV theme start 96dp (72dp rail + 24dp), end 8dp (0 on Home); other themes
  start 72dp (64 + 8), end 16dp. Lay your page out for the space you are given; do not add the rail width yourself.
- **Gutters (WeaselTV).** Your root view's edges are the Compose content edges, so the gutters are outside it: 24dp
  between your start edge and the rail, 8dp between your end edge and the screen (none on Home). Section rules that
  reach into them (CARDS: `CardDimens.NEON_RULE_INSET_START_DP` / `_END_DP`, passed as `contentInsetStartPx` /
  `contentInsetEndPx`) are laid out across the gutters with negative horizontal margins of those amounts on the row
  or the list holding the rows. The page container and the app layer never clip (`clipChildren = false`,
  `clipToPadding = false`); set the same on your root view and lists so card glows, scaled cards and rules are not
  cut at the content edge. Other themes have no gutters (insets 0).
- While the menu is open the shell slides those pages right (`translationX` on your root view, 168dp on WeaselTV,
  160dp elsewhere) with a critically damped spring. Never set `translationX` on your fragment's root view.
- Full-screen pages (`fullScreen = true`, `showsNavDrawer = false`) fill the screen; the menu and clock are hidden.
- Page transitions are the shell's (forward: your page fades in over 150 ms while the old one stays whole; BACK: the
  top page fades out over 150 ms and the one under it shows at once). Do not animate your root view in or out.

### Style, preferences, theme

- `pageStyle` is what the Compose tree provided (section accent, Home card appearance, `HomePageResolvedSettings`,
  poster-count appearance), computed exactly like `ApplicationContent` did, for your entry's place in the stack. It
  is updated (and `onPageStyleChanged` called) when the side menu items, Home settings or app theme change. Rows you
  configure yourself (`rowAccent`, `cardBorderAccent`, `hidePosterTypeBadge`, `PageStyle.forWatchlistItem`) are
  `copy(...)`s you make locally.
- `preferences` / `preferencesFlow` come from `ScreenHost.userPreferences` (never null once a page exists).
- `InterfaceCustomization(preferences.appPreferences)` replaces `LocalInterfaceCustomization`.
- The theme can change at runtime (Settings): collect `AppTheme.themeFlow` if your views cache colours. The shell
  restyles itself.
- Images: Coil's singleton loader is set up exactly like the old `CoilConfig` (images' own OkHttp dispatcher, memory
  and disk caches, the tagged-image cache strategy, the NavTiming listener) when the preferences are first read,
  before any page exists; `imageView.load(url)` and `context.imageLoader` use it.
- `pageStyle.posterCountAppearance` is `homeMediaAppearanceView(homeSettings)` on the WeaselTV theme (null
  elsewhere), as `LocalPosterCountAppearance` was; `pageStyle.cardAppearance` prefers it.

## 3. Focus

- First focus: override `onScreenFocus(restoring)`. It is called right after your page is added (restoring = false)
  and when the page above it is popped (restoring = true; the base restores the last focused view, which the shell
  records for you as focus moves).
- **Content that arrives later** (data loading): call `requestInitialFocus(view)` when it arrives. It takes focus only
  while your page is the top page and focus is not already inside it, which also pulls focus out of the side menu
  after a menu click, as the Compose pages' load-time `requestFocus` did.
- While a new page has nothing focusable yet, focus waits on an invisible sink in the page container (never on the
  menu). Your loading view does not need to be focusable.
- Hidden or off-screen views must be `GONE`/`INVISIBLE` (stock focus search).

### The side menu

- **LEFT into the menu needs nothing from you**: any focus search that leaves your page to the left and lands in the
  menu is redirected to the current section's row, and the menu opens (focus in the menu = open). A
  `HorizontalGridView` keeps LEFT at its first item by default: call `setFocusOutAllowed(true, …)` (focus out front)
  on rows whose first item should reach the menu. If your page decides on its own when LEFT opens the menu (eg LEFT
  at a row's first card handled in a key listener), call `screenHost.focusNavDrawer()`.
- RIGHT from the menu calls your `onScreenFocus(restoring = true)`.
- Up/Down/Left never leave the menu. BACK with More expanded collapses it.

### Coach-mark tour (HOME)

```kotlin
CoachMarkTargets.register(CoachMarkIds.HOME_HEADER, headerView)   // once the header exists
CoachMarkTargets.register(CoachMarkIds.HOME_ROW, rowView)         // the row focus is on; again when it changes
CoachMarkTargets.unregisterAll(view)                              // when a view goes away
```

Inject `CoachMarkTour` (singleton): set `homeRowReady = true` once the starting row (Trending Movies) has focus, or
when there is no such row, while `pendingFlow` is true; when `homeToTopFlow` turns true, go back to the top row and
set `homeToTop = false`. The tour takes the remote itself while it runs (keys never reach your page). The menu's
targets and scrolling are the shell's.

## 4. Keys and BACK

- Key order: screensaver (wakes it and eats the key) → coach-mark tour → your `onPreviewKeyEvent(event)` (only while
  focus is inside your page, as Compose preview handlers were) → normal View dispatch.
- BACK: your `onBackPressed()` first (return true to consume an inner step). `OnBackPressedCallback`s you register
  with `viewLifecycleOwner` also run before the shell. Then the shell: More expanded → collapse it; on Home with the
  menu closed → open the menu on Home; otherwise `navigationManager.goBack()`; at the root the activity's default
  (the app goes to the background).

## 5. ScreenHost (MainActivity)

| Member | Use |
|---|---|
| `userPreferences: StateFlow<UserPreferences?>` | the signed-in user's preferences |
| `clearBackdrop()` | the old `onClearBackdrop` (Home's reload, pages that hide the backdrop) |
| `focusNavDrawer(): Boolean` | open the menu on the current section; false when the page has no menu |

The shell already clears the backdrop when these pages show (first time and when revealed by BACK): full-screen
pages, box sets, playlists, collection folders, folders, user views, people, photo albums, filtered collections,
More (Home row grid), item grids, Watchlist, Search, Requests, Discover people and Discover "more" grids. Detail pages
submit their own backdrop through `BackdropService` as before.

## 6. Other shared pieces

- `AppClock.time` (StateFlow<String>): the formatted time every 2 s, replacing `LocalClock.timeString`.
- Kids loading artwork: the shell keeps `LoadingArtworkRotation.rememberProfile(...)` current, so a View loading
  mark reads `LoadingArtworkRotation.lastProfileWasKids(context)` (the old `LocalKidsLoadingArtwork`).
- Request chips stay away from text entry (an `EditText` with focus) and dialogs (window focus) by themselves; the
  old `RequestUiState` / `trackRequestTextInput` / `rememberRequestFocus` are not needed in Views.
- Setup screens (SETUP): `SetupScreens.create(destination)` returns a plain Fragment for `ServerList` / `UserList`;
  the shell adds it to `R.id.lb_shell_setup` with the 700 ms cross-fade and removes it when the destination changes.
  Handle BACK there with an `OnBackPressedCallback`.
