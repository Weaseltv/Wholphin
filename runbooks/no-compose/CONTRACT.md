# WeaselPlex without Compose: contract for the rewrite agents

Owner order 2026-11-22: rewrite the whole TV UI without Jetpack Compose. Settings use AndroidX Leanback
Preference only; every other surface uses Leanback widgets (`VerticalGridView`, `HorizontalGridView`,
`BaseGridView`, presenters) or `RecyclerView` + custom Views, `ConstraintLayout`/`FrameLayout`, and stock
Android focus. No hybrid: when the rewrite is finished the app has no Compose UI, no Compose dependency and no
Compose compiler plugin. Non-UI layers (Jellyfin SDK, Room, DataStore, Hilt services, playback engines, data
models, ViewModels) stay; only the presentation layer is replaced, keeping today's behaviour and look.

This page is the contract between the orchestrator, the SHELL and WIDGETS agents and the area agents. Read it
completely before writing code. The reference for a TV app that already did this is the WeaselTV TV app
(`~/work/weaseltv/weaseltv-apps`, branch `origin/tv/no-compose`, `docs/tv-rebuild/NO_COMPOSE_SHELL.md`); it is a
different app, so copy patterns, not code.

## 1. Where the new UI lives

All new UI code is under `app/src/main/java/com/github/damontecres/wholphin/leanback/`, one package per area:

| Package | Owner | Contents |
|---|---|---|
| `leanback.shell` | SHELL | `MainActivity` content, `NavHost` (fragment back stack), side menu (nav drawer), backdrop, setup layer host, overlays (screensaver, dim, coach marks, update / What's New prompts, request chips), `ScreenFactory`, `DestinationFragment` |
| `leanback.theme` | WIDGETS (orchestrator wrote the start) | `AppTheme`, `AppPalette`, `NeonColors`, `NeonSections`, `TextRole`, `AppFonts`, focus/glow drawables |
| `leanback.widget` | WIDGETS | cards, card rows, card grids, buttons, chips, tabs, dialogs, context menu, loading/error views, text widgets, image loading helpers. Documented in `runbooks/no-compose/WIDGETS.md` |
| `leanback.settings` | SETTINGS | every settings/preference screen on Leanback Preference |
| `leanback.playback` | PLAYBACK | player page, overlays, player dialogs, next up, external player, slideshow |
| `leanback.setup` | SETUP | server list, pinned-server sign-in, profile chooser, PIN entry, Quick Connect, Seerr server, kids flows |
| `leanback.home` | HOME | Home, Watchlist (Favorites destination), More (MoreHomeRow) |
| `leanback.detail` | DETAILS | movie, series, season/episode overview, episode, collection (box set), playlist, person |
| `leanback.library` | LIBRARY | library/collection folders, filters/sort/view options, genre/studio grids, item grids |
| `leanback.search` | SEARCH | search, voice search, Discover/Requests (Seerr) pages and Discover details |

Resources (layouts, drawables, styles) go in `app/src/main/res` with an area prefix:
`lb_shell_*`, `lb_widget_*`, `lb_settings_*`, `lb_playback_*`, `lb_setup_*`, `lb_home_*`, `lb_detail_*`,
`lb_library_*`, `lb_search_*` (file names and ids). Strings: reuse the existing ones in `res/values/strings.xml`
(and the `weaselfin` flavor overrides in `app/src/weaselfin/res`); add new ones only if a string was a literal.
Never edit another area's files. If you need something from another area that does not exist yet, build a small
private version inside your own package and say so in your report.

## 2. Ownership of the old Compose files

Each area rewrites the surfaces in these files. "Rewrite" means: same content, layout, focus order, D-pad
behaviour, BACK behaviour, dialogs and actions, built as Views/fragments in the area's package.

- **SHELL**: `MainActivity.kt`, `MainContent.kt`, `WholphinDreamService.kt` (screensaver as a View),
  `WholphinApplication.kt` (Compose bits), `ui/nav/*` (ApplicationContent, Backdrop, DestinationContent,
  NavDrawer, NavigationDrawerAndroid), `services/NavigationManager.kt` and `services/SetupNavigationManager.kt`
  (observable back stacks without Navigation3), `ui/coachmark/*`, `ui/components/AppScreensaver.kt`,
  `ui/setup/UpdatePrompt.kt`, `ui/setup/WhatsNewPrompt.kt`, `ui/components/RequestChipHost.kt`,
  `RequestNotificationCard.kt`, `RequestUiState.kt`, `ui/CoilConfig.kt` (becomes a Coil `SingletonImageLoader`
  set up for Views), `ui/CrossFadeFactory.kt`, `ui/util/*`, the Compose bits of `services/BackdropService.kt`,
  `services/ScreensaverService.kt`, `services/ImageUrlService.kt`, `kids/KidPageGuardViewModel` use.
- **WIDGETS**: `ui/cards/*`, `ui/theme/*` (Neon helpers become View drawables/helpers), `ui/detail/CardGrid.kt`,
  `ui/data/ItemDetailsDialogInfo.kt`, and the shared parts of `ui/components/`: Button, CircularProgress,
  Dialogs, ContextMenu, ContextMenuUtils, ErrorMessage, LoadingRow, FocusableItemRow, ListItemWrapper,
  SelectedLeadingContent, TabRow, EditTextBox, SwitchWithLabel, SliderBar, AppDropdownMenu, TvDropdownMenuItem,
  HeaderUtils, GenreText, OverviewText, TitleValueText, QuickDetails, Rating, TimeDisplay, TitleOrLogo,
  FittedCardLabel, HiddenFocusBox, Optional, TableRow, ViewportLayout.
- **SETTINGS**: `ui/preferences/**`, `ui/main/settings/**` (Home settings), `ui/components/LicenseInfo.kt`,
  `ui/detail/DebugPage.kt`, `ui/setup/InstallUpdatePage.kt`, `ui/avatars/*` (profile picture page/picker).
- **PLAYBACK**: `ui/playback/**`, `ui/slideshow/**`, `util/mpv/Utils.kt`.
- **SETUP**: `ui/setup/{PinEntry,PinnedServerContent,QuickConnectQrCode,ServerList,SwitchServerContent,
  SwitchUserContent,UserList}.kt`, `ui/setup/seerr/*`, `ui/kids/*`.
- **HOME**: `ui/main/HomePage.kt`, `ui/detail/HomeRowGrid.kt`, `ui/detail/FavoritesPage.kt`,
  `ui/detail/WatchlistPage.kt`.
- **DETAILS**: `ui/detail/{movie,series,episode,collection}/*`, `ui/detail/{PlaylistDetails,PlaylistList,
  PersonPage,KidTitleButtons}.kt`, `ui/components/{PlayButtons,VideoStreamDetails,SeriesComponents,
  TrailerDialog}.kt`.
- **LIBRARY**: `ui/components/{CollectionFolderView,CollectionFolderGrid,CollectionFolderList,FilterByButton,
  SortByButton,TypeFilterButton,ViewOptionsDialog,GenreCardGrid,StudioCardGrid,ItemGrid,TabbedPage,
  RecommendedContent,RecommendedMovie,RecommendedTvShow,StreamingProviderHeader}.kt`,
  `ui/detail/CollectionFolder*.kt`.
- **SEARCH**: `ui/search/**`, `ui/components/VoiceSearchButton.kt`, `ui/discover/**`, `ui/detail/discover/**`.

### Keep the old files compiling until the end

The old Compose files are deleted in one step at integration, after every area is done. Until then:

1. Do **not** delete or break old Compose files (another area's old code may still call them). Your new code must
   not call any `@Composable`, `Modifier`, `androidx.compose.*`, `androidx.tv.*`, `coil3.compose.*`,
   `androidx.navigation3.*`, `androidx.activity.compose.*` or `androidx.hilt.navigation.compose` API.
2. **Move non-UI code out of Compose files.** ViewModels, data/state classes, enums, sort/filter option lists,
   formatting helpers and other plain code that your new screens need and that sits in a file with Compose code:
   move it into a Compose-free file in the **same package with the same name** (so every caller still compiles),
   and remove Compose types from it (`@Stable`/`@Immutable` annotations, `mutableStateOf`, `SnapshotStateMap`,
   Compose `Color`, `Density`, `ImageBitmap`, `AnnotatedString`...; use `StateFlow`, `Int` ARGB colours, plain
   collections, `Bitmap`, `CharSequence`/`SpannableString`). Fix every caller you break, including old Compose
   callers (a tiny adapter is fine). Do not change what a ViewModel does.
3. In your final report list every old file that is now **Compose-only and safe to delete** once your screens
   replace it, and any remaining Compose references outside UI that the integrator must clean.

## 3. Screens are fragments

- A destination screen is an `@AndroidEntryPoint` subclass of `leanback.shell.DestinationFragment`
  (`leanback/shell/DestinationFragment.kt`). Return it from your area's `*Screens.create(destination, preferences)`
  (for example `leanback/detail/DetailScreens.kt`); return null for destinations you do not own. Only the
  SHELL edits `ScreenFactory`.
- The shell creates one fragment per back-stack entry, keeps it alive and hidden while later pages are on top
  (view hierarchy, scroll position and ViewModels survive) and removes it when the entry is popped (ViewModels
  cleared): the lifetime Navigation3 gave each composable. Fragments that are hidden get `onHiddenChanged(true)`.
- Read the destination with `requireDestination<Destination.MediaItem>()`, preferences with `preferences` /
  `preferencesFlow`. Navigate exactly as today through the injected `NavigationManager`
  (`navigateTo`, `goBack`, `navigateToFromDrawer`, `replaceTop`, `goToHome`, `reloadHome`).
- ViewModels: `by viewModels()`; assisted ones with
  `by viewModels(extrasProducer = { defaultViewModelCreationExtras.withCreationCallback<MyVm.Factory> { it.create(...) } })`
  (`dagger.hilt.android.lifecycle.withCreationCallback`). Composables that called `hiltViewModel(key = ...)` for
  several instances in one page: use a child fragment or a keyed `ViewModelProvider(this)[key, MyVm::class.java]`.
- Collect flows with `collectWhileStarted { }` / `launchWhileStarted { }` from `DestinationFragment` (view
  lifecycle, STARTED). Never block the main thread.
- Focus: override `onScreenFocus(restoring)` to choose the first focus (e.g. Play on a details page); the base
  restores the last focused view when the page above is popped. BACK inside the page (closing an inner panel):
  override `onBackPressed()` and return true. Page-level key handling (the old `onPreviewKeyEvent` on a page):
  `onPreviewKeyEvent(event)`.
- `DestinationFragment.showsNavDrawer` (from `Destination.fullScreen`) tells the shell whether the side menu sits
  next to the page. Non-fullscreen pages are laid out for the space right of the collapsed side menu, exactly as
  the Compose `NavDrawer` content area was.
- Dialogs: use the WIDGETS dialog helpers (they are `Dialog`/`DialogFragment`s, so the platform returns focus to
  the view that opened them). Until WIDGETS lands, Phase 1 areas may use their own `Dialog` subclasses.
- Setup-layer screens (SETUP) are plain fragments returned by `leanback/setup/SetupScreens.kt`.

## 4. Look and feel: same as today

- Colours: `AppTheme.palette` (the `MaterialTheme.colorScheme` roles, per theme) and `NeonColors`/`NeonSections`
  (the Neon Board tokens). `AppTheme.isNeon` replaces `isWeaselTv()`. All nine themes must keep working; the
  weaselfin default is WEASELTV (Neon Board).
- Text: `TextView.applyTextRole(TextRole.TITLE_MEDIUM)` replaces `MaterialTheme.typography.titleMedium`
  (Exo 2 / Audiowide in the weaselfin flavor). Sizes in dp/sp exactly as the old code.
- Keep every size, padding, corner radius, focus scale, border, glow, badge, progress bar, scrim and animation
  duration from the old code (the Neon recipe: 1dp accent border + accent glow + 1.06 scale on cards, 120 ms
  focus transition, 3dp tally for selected). The owner approved many layouts with exact numbers; the old
  Compose layout tests in `app/src/test/java/.../ui/components/*LayoutTest.kt` and `ApprovedHomeLayout` record
  them. Read the old composable and the matching test before building a View.
- Images: Coil 3 for Views (`io.coil-kt.coil3:coil`, `imageView.load(url) { ... }`); URLs from
  `ImageUrlService` exactly as the composables built them (same fill sizes).
- Strings: user-visible names follow the weaselfin flavor (`app/src/weaselfin/res/values/strings.xml`):
  Favorites is "Watchlist", Discover is "Requests". Live TV and Music are deleted from this app; do not add them
  back. Theme songs, music videos and playlists stay.

## 5. Focus and D-pad

Stock Android / Leanback focus. Leanback grids (`VerticalGridView`/`HorizontalGridView`) remember and restore
their selected position, align the focused item (use `windowAlignment`/`itemAlignment` to match the old
bring-into-view behaviour) and keep D-pad repeats smooth. Rules:

- Every focusable item shows its focus state exactly like the old one (scale, border, glow, fill colour).
- Views that are off-screen or hidden must be `GONE`/`INVISIBLE`, not just translated, or stock focus search
  finds them.
- LEFT from the left edge of a page with the side menu must reach the side menu (the shell handles it through
  focus search; a `HorizontalGridView` needs `setFocusOutAllowed(true, ...)` / `focusOutFront` so LEFT can leave
  its first item). UP/DOWN between rows, RIGHT at a row end stops (no wrap) unless the old UI wrapped.
- Long press (OK held) opens the same context menu as before (`View.setOnLongClickListener`).
- No posted/retried `requestFocus` loops; one `requestFocus()` on the right view at the right time.

## 6. Building and checking

- You work only in your own checkout (given in your brief, a git worktree on branch `t3/nc-<area>`). Use
  absolute paths. Commit often on your branch (`git add -A && git commit -m "..."`), push your branch to `origin`
  when your work is done, never push to `weaselfin`.
- Build only through the machine's build slots (the ThinkCentre has 15 GB RAM and the owner works on it):

  ```bash
  export ANDROID_HOME=$HOME/Android/Sdk
  ~/work/kid-profiles-orch/gradle-slot.sh ./gradlew :app:compileWeaselfinAlphaKotlin -PWeaselPlexTargetAbi=arm64-v8a --console=plain -q 2>&1 | grep -E "^e:|error:|BUILD" | head -60
  ```

  That compiles the Kotlin of the shipped flavor (about 3 minutes cold, less when incremental). Your area must
  compile with **zero errors** before you report. Do not run `clean`, do not install on any device, do not start
  an emulator, do not launch the app. Keep your checkout's build caches.
- When you finish: make sure no Gradle/Kotlin process of yours is left (the slot script stops daemons when no
  other build runs) and delete any scratch files you put in `/tmp`.
- Style: Kotlin, ktlint-clean (`~/work/tools/ktlint-1.8.0 -F "app/src/main/java/**/leanback/**/*.kt"`
  if present), no wildcard imports, KDoc on public classes. Comments explain behaviour the owner asked for.

## 7. Report

End with: the branch and last commit; the screens/surfaces you rewrote (and any you could not, with why); every
behaviour you were unsure about; the old files now safe to delete; Compose references you left for the
integrator; anything another area must provide.
