# DETAILS area (no-Compose UI): what it built and what others can reuse

Written by the DETAILS agent (2026-11-22). Code: `app/src/main/java/com/github/damontecres/wholphin/leanback/detail/`.
Read `CONTRACT.md`, `WIDGETS-CARDS.md`, `WIDGETS-CONTROLS.md` and `SHELL.md` first.

## Screens (`DetailScreens.create`)

| Destination | Fragment | Old composable |
|---|---|---|
| `MediaItem` MOVIE, VIDEO, MUSIC_VIDEO | `MovieDetailsFragment` | `MovieDetails` |
| `MediaItem` SERIES | `SeriesDetailsFragment` | `SeriesDetails` |
| `SeriesOverview` | `SeriesOverviewFragment` | `SeriesOverview` / `SeriesOverviewContent` |
| `MediaItem` EPISODE | `EpisodeDetailsFragment` | `EpisodeDetails` |
| `MediaItem` BOX_SET | `CollectionDetailsFragment` | `CollectionDetails` |
| `MediaItem` PLAYLIST | `PlaylistDetailsFragment` | `PlaylistDetails` |
| `MediaItem` PERSON | `PersonPageFragment` | `PersonPage` |

Every page extends `DetailPageFragment` (loading / error / content, page activity = the old `LifecycleResumeEffect`,
overview / playlist / context-menu dialogs with the kid guard). Detail pages build their list with `DetailPageList`
(a Leanback `VerticalGridView`: keep-visible scrolling on WeaselTV, 30 % pivot elsewhere) inside the CONTROLS
`DetailPageLayout`; the list spans the WeaselTV gutters (negative margins) and headers pad back to the content edge.

## Reusable pieces (public, in `leanback.detail`)

- `DetailButtons.play(...)` / `.fa(...)` / `.delete(...)` / `.trailers(...)`: the old `ExpandablePlayButton`,
  `ExpandableFaButton`, `DeleteButton` (with its confirmation) and `TrailerButton` as `ExpandableButton`s (an
  `AppButton`; 40dp tall, `collapsed` labels only while focused, `allCaps` = the WeaselTV default).
  `showTrailerDialog(anchor, trailers, onClick)` is `TrailerDialog`; `DetailButtons.showVersionChooser` the version menu.
- `ExpandablePlayButtonsView`: the whole `ExpandablePlayButtons` row (Play / Resume + Restart, Version, Watchlist,
  Watched, Trailers, Delete, More), bound with `PlayButtonsState` and `PlayButtonsActions`.
- `ActionButtonRow`: a horizontally scrolling button row that restores its last focused button (`focusRestorer`).
- `KidTitleButtonsView`: the kid title page's big Play and round Start over / Watchlist / Shuffle buttons.
- `StreamDetailsView` (`VideoStreamDetails`), `seriesNameView` / `episodeNameView` (`SeriesName` / `EpisodeName`),
  `KidMetadataLineView`, `KidEpisodeInfoView`, `taglineView`, `directorNames`.
- `SortButton` / `FilterButton`: DETAILS-private ports of `SortByButton` / `FilterByButton` (LIBRARY owns the
  originals and builds its own); `GridTitleView` (`GridTitle`), `StreamingWordmarkView`, the type-filter and quick-list
  buttons are likewise private ports of LIBRARY's composables. A collection's focused-item header uses HOME's
  `HomeHeaderView`.

## Moved out of Compose files (same package, same names)

`ui/detail/PersonViewModel.kt` (PersonViewModel, PersonState, rowTitle), `ui/detail/PlaylistViewModel.kt`
(PlaylistViewModel, FilterAndSort without `@Immutable`, PlaylistDetailsState), `ui/detail/series/SeriesOverviewPosition.kt`
(SeasonEpisode, SeasonEpisodeIds, SeriesOverviewPosition), `ui/detail/collection/CollectionViewOptions.kt`
(CollectionViewOptions); `CollectionState` lost `@Stable`.
