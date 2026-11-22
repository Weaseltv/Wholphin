# Card widgets (no-Compose UI): API reference

Owner: CARDS. Package `com.github.damontecres.wholphin.leanback.widget.card`, card recipes in
`leanback/theme/NeonCards.kt`, resources `res/drawable/lb_widget_card_*`. Every card, row and grid of the app is
built from these. They reproduce the old composables (`ui/cards/*`, `ui/detail/CardGrid.kt`,
`ui/components/{LoadingRow,FocusableItemRow,FittedCardLabel,HiddenFocusBox}.kt`) exactly: sizes, image URLs, badges,
progress bars, captions and focus treatment. Read `CONTRACT.md` first.

No Compose anywhere: Views, Leanback `HorizontalGridView` / `VerticalGridView`, Coil 3 for Views.

## 1. Old composable to new View

| Old (Compose) | New (View) | Notes |
|---|---|---|
| `GridCard` | `GridCardView.bind(item, style, widthPx, …)` | width = grid column width |
| `gridCardHeight(...)` | `GridCardView.heightPx(context, widthPx, ratio, showTitle, titleLines)` | |
| `SeasonCard(item = …)` / `SeasonCard(title = …)` | `SeasonCardView.bind(item, style, imageHeightDp, …)` / `bind(title, subtitle, name, imageUrl, …)` | |
| `BannerCard` / `BannerCardWithTitle` | `BannerCardView.bind(item, style, name, cardHeightDp, …, withTitle = false/true)` | |
| `DiscoverItemCard` | `DiscoverItemCardView.bind(item, style, widthPx, showOverlay, accent)` | |
| `PersonCard` | `PersonCardView.bind(person, style, widthPx, accent)` or `bind(name, role, imageUrl, favorite, …)` | |
| `GenreCard` / `StudioCard` | `GenreCardView.bind(id, name, imageUrl, style, widthPx \| heightPx)` / `StudioCardView.bind(…)` | |
| `ViewMoreCard` | `ViewMoreCardView.bind(style, aspectRatio, heightPx, widthPx, showTitle, accent)` | in rows: `ViewMoreSpec` |
| `ChapterCard` | `ChapterCardView.bind(chapter, style, cardHeightDp, accent)` | |
| `ItemCardImage` (+ overlay, fallback) | `ItemImageView.bind(ItemImageState(…), style)` | for custom cards / lists |
| `WatchedIcon`, `FavoriteIndicator`, `NeonBadge` | `WatchedIconView`, `FavoriteView(context, sizeSp)`, `NeonBadgeView.bind(text, accent, countAppearance)` | |
| `PosterCardTitle` + `PosterCardSubtitle` in `SlidingCardText` | `PosterCaptionView` (base `CardCaptionView`) | built into the cards |
| `FittedCardLabel` | `FittedLabelView.bind(text)` | |
| `ItemRow` | `CardRowView.setup(…)` + `setCards(CardRowAdapter, items, viewMore)` | |
| `ItemRowTitle` | `RowTitleView.bind(title, accent, RowTitleStyle, count, kicker, insetStartPx, insetEndPx)` | |
| `NeonSectionRule` / `NeonRule` | `SectionRuleView.bind(accent, thicknessDp, glowDp, strength)` | WeaselTV only |
| `FocusSafeLazyRow` | `CardStripView` (inside `CardRowView`, or alone) | |
| `PersonRow` / `DiscoverPersonRow` | `CardRowView.showPeople(…)` / `showDiscoverPeople(…)` | |
| `ChapterRow` | `CardRowView.showChapters(…)` | |
| `ExtrasRow` | `CardRowView.showExtras(…)` | |
| `LoadingRow` | `LoadingRowView.bind(title, state, style, onClickItem, …)` | |
| `FocusableItemRow` / `LoadingRowPlaceholder` | `PlaceholderRowView.bind(title, message, accent, isError, focusableRow = true/false, …)` | |
| `CardGrid` | `CardGridView.setCards(items, presenter, CardGridCallbacks, CardGridConfig, pageStyle)` | |
| `cardGridRowHeight(...)` | `CardGridView.rowHeightPx(context, gridWidthPx, columns, spacingDp, ratio, showTitle, topPaddingDp)` | |
| `AlphabetButtons` | `AlphabetPickerView` (inside `CardGridView`) | |
| `HiddenFocusBox` | `HiddenFocusView(context) { onFocus }` | |
| `neonCardBorder/Glow/Scale/Shape` | `cardFocusLook(context, style, accent, CardShape, CardContainer)` → `CardFrameView.setLook` | built into the cards |
| `neonProgress` | `CardProgressView` | built into the cards |
| `CollectionCardStyle` | `PageStyle.forCollectionCard(item, appearance, streamingAppearance)` | |
| `WatchlistCardStyle` | `PageStyle.forWatchlistItem(item, libraries)` (Accents.kt) | |
| `HomePageCardContent`'s locals | `PageStyle.forHomeRowCard(viewOptions, rowAccent, borderAccent)` | |
| `collectionPosterAccent`, `rememberIdColor`, `WatchedIconBackground/Color` | `collectionPosterAccent`, `idColor`, `watchedIconBackground/Color` (NeonCards.kt) | ARGB ints |
| `Cards.height2x3` / `heightEpisode` / `personRowCardWidth` | `CardDimens.HEIGHT_2X3_DP` / `HEIGHT_EPISODE_DP` / `PERSON_CARD_WIDTH_DP` | dp |
| `CollectionTypeBadge` | `CollectionTypeBadgeView` | unused today, as before |
| `JumpButtons` | not ported | every caller passed `showJumpButtons = false` |

## 2. Units, style and accents

- Sizes: parameters ending in `Dp` are dp (as the old code's `.dp` values), `Px` are pixels. Grid column widths are px
  (`GridItemDetails.widthPx` was px too).
- `PageStyle` (`leanback/theme/Accents.kt`) is what the CompositionLocals were. Every card takes the page's
  `pageStyle` (from `DestinationFragment.pageStyle`) and reads from it:
  - focus: `homeCardAppearance` (`LocalHomeCardAppearance`: corner radius, border width/opacity, glow spread/opacity,
    focus scale, accent override), `rowAccent` (`LocalHomeRowAccent`), `cardBorderAccent` (`LocalHomeCardBorderAccent`);
  - badges: `posterCountAppearance` (`LocalPosterCountAppearance`, WeaselTV) else `homeCardAppearance`;
  - `hidePosterTypeBadge` (`LocalHidePosterTypeBadge`; also set by `forCollectionCard`).
  To give one row or card a different appearance (what `CompositionLocalProvider` did), pass a copy:
  `style.forHomeRowCard(viewOptions, rowAccent, borderAccent)`, `style.forCollectionCard(item, appearance)`,
  `style.forWatchlistItem(item, libraries)`, or `style.copy(...)`.
- `accent` parameters default exactly as the old ones:
  - item cards: `style.accentFor(item)` (`neonAccentFor(item)`);
  - cards that defaulted to `LocalNeonAccent` (season by URL, person, genre, studio, chapter, View more, row titles):
    `style.sectionAccent`. `LocalNeonAccent` was the section accent except under `ProvideNeonAccent` (details pages: the
    item's type accent, `typeAccent(item.type)`); on such pages pass that accent explicitly.
- Theme: cards read `AppTheme.isNeon` / `AppTheme.palette` at bind time. When the theme or `pageStyle` changes, rebind
  (for adapters: `notifyDataSetChanged()` from `onPageStyleChanged`).

## 3. Focus treatment (built in)

`CardFrameView` is the focusable surface of every card (the tv-material3 `Card`); `BaseCardView.frame` exposes it.

- WeaselTV: corner radius from the appearance (square by default), focused border `borderWidthDp` in
  `cardBorderAccent ?: homeCardAccent(accent)` at `borderOpacityPercent`, glow `glowSpreadDp` in the glow colour of the
  accent at `glowOpacityPercent`, scale `focusScalePercent` (1.08 approved), 0.97 while OK is held.
- Other themes: tv-material3 `Card` defaults, looked up in tv-material3 1.1.0-alpha01: 8dp corners, 3dp border in the
  palette's `border` colour, 1.1 scale (also while pressed), no glow.
- Motion is tv-material3's: scale in 300 ms on focus, out in 500 ms, 120 ms to pressed, 300 ms on release, easing
  `CubicBezier(0, 0, 0.2, 1)`. Border and glow switch without animation; glow only on API 28+ (as tv-material3).
- The focused frame is lifted with `translationZ` above its caption; Leanback grids draw the focused item last.
- The caption under a card slides down 8dp while focused (`SlidingCardText`); WeaselTV titles turn from mid grey to
  white. Person names and "View more" scroll (marquee) after 1 s / 0.5 s of focus.
- Click / long click (OK held) / focus: set on `card.frame` (`setOnClickListener`, `setOnLongClickListener`,
  `setOnFocusChangeListener`); the adapters below do it for you. Cards use `frame.addFocusListener` internally.

**Clipping.** Glows and scaled cards draw outside their bounds. Cards never clip; `CardRowView`, `CardStripView`,
`CardGridView` clip only along their scrolling axis (Compose allowed 30dp across it). Containers you put them in
(page lists, fragment roots) should set `clipChildren = false` (and `clipToPadding = false` where they pad), or the
first/last card's glow is cut at your container's edge.

## 4. Cards

```kotlin
// Library grid card (inside a CardGridView presenter; cellWidthPx is the column width)
card.bind(item, style.forCollectionCard(item, collectionAppearance, streamingAppearance), cellWidthPx,
    imageAspectRatio = viewOptions.aspectRatio.ratio, imageScale = CardImageScale.from(viewOptions.contentScale),
    imageType = viewOptions.imageType.imageType, showTitle = viewOptions.showTitles)

// Home row card (HomePageCardContent)
val cardStyle = style.forHomeRowCard(viewOptions, rowAccent, borderAccent)
bannerCard.bind(item, cardStyle, name = item?.data?.seriesName ?: item?.name,
    cardHeightDp = viewOptions.heightDp.toFloat(), aspectRatio = ratio, imageType = imageType,
    imageScale = CardImageScale.from(contentScale), cornerText = item?.ui?.episodeUnplayedCornerText,
    played = item?.data?.userData?.played ?: false, favorite = item?.favorite ?: false,
    playPercent = item?.data?.userData?.playedPercentage ?: 0.0, useSeriesForPrimary = viewOptions.useSeries,
    typeBadge = badge, withTitle = viewOptions.showTitles, title = item?.title, subtitle = item?.subtitle)
genreCard.bind(item.id, item.name, item.imageUrlOverride, cardStyle, heightPx = context.dp(viewOptions.heightDp))

// Season / credits / search results
seasonCard.bind(item, style, imageHeightDp = CardDimens.HEIGHT_2X3_DP.toFloat())

// People
personCard.bind(person, style, accent = pageAccent)
```

- `CardImageScale` replaces `ContentScale` (`FIT`, `FILL_BOUNDS`, `CROP`, `NONE`, `FILL_WIDTH`, `FILL_HEIGHT`,
  `INSIDE`); `CardImageScale.from(PrefContentScale)` replaces `PrefContentScale.scale`. Image types are Jellyfin
  `ImageType` (`ViewOptionImageType.imageType`).
- Image URLs and fill sizes are computed inside the cards exactly as before (`ImageUrlService` via Hilt entry point,
  `CardImages.imageUrlService(context)`). Banner cards of collections shown by a non-primary image retry with the
  collection's poster when that fails, then show the name.
- `itemTypeBadge(context, item)`: the S2 · E4 / MOVIE / SHOW badge text of `ItemCardImage`.
- Cards are recyclable: the adapters call `card.recycle()` (cancels image loads). Rebinding a card to the same URL does
  not reload.

## 5. Rows

```kotlin
val row = CardRowView(context)              // one per row, eg a VerticalGridView item
val adapter = CardRowAdapter(
    presenter = object : CardPresenter<BaseItem>() {
        override fun viewType(item: BaseItem?, position: Int) = when (item?.type) {
            BaseItemKind.GENRE -> 1; BaseItemKind.STUDIO -> 2; else -> 0 }
        override fun create(parent: ViewGroup, viewType: Int): BaseCardView = when (viewType) {
            1 -> GenreCardView(parent.context); 2 -> StudioCardView(parent.context); else -> BannerCardView(parent.context) }
        override fun bind(card: BaseCardView, item: BaseItem?, position: Int, cellWidthPx: Int) { /* card.bind(…) */ }
        override fun key(item: BaseItem?, position: Int): Any = item?.id ?: position   // stable identity (optional)
    },
    callbacks = CardCallbacks(
        onClick = { index, item -> },          // not called for null placeholders
        onLongClick = { index, item -> },      // OK held: open the context menu (CONTROLS)
        onFocus = { index, item -> },          // every focused card
        onKey = { index, item, event -> isPlayKeyUp(event) && … },  // eg the play key
    ),
)
row.setup(title, titleAccent, pageStyle, CardRowStyle.forHomeRow(viewOptions, insetStartPx, insetEndPx), kicker)
row.setCards(adapter, items, viewMore = ViewMoreSpec(
    bind = { it.bind(cardStyle, aspectRatio, heightPx = context.dp(viewOptions.heightDp), showTitle = viewOptions.showTitles) },
    onClick = { /* view more */ },
))
row.focusCard(index)                        // `focusIndex` (reordering): select and focus a card
```

- One adapter per row; `setCards` diffs by `CardPresenter.key` (default: index, like `LazyRow` without keys) with
  stable ids, so updates rebind cards in place and keep focus. `cardPresenter<T, V>(::V) { card, item, pos, w -> }`
  builds a one-kind presenter from a lambda.
- `CardRowStyle` defaults are ItemRow's (weaselfin: 10dp spacing, 12dp to the cards, 35sp title, 3dp rule with 10dp
  glow at 80 %, count 14sp; WeaselTV card padding 4 / 16 / 8 / 8dp). Presets: `CardRowStyle.forHomeRow(viewOptions)`,
  `personRow()`, `chapterRow()`; `RowTitleStyle.forHomeRow(viewOptions)`.
- **Gutters.** On WeaselTV, section rules reach past the page's content edge into its gutters (24dp at the side menu,
  8dp at the screen edge except on Home: `CardDimens.NEON_RULE_INSET_START_DP` / `_END_DP`). Lay the row out across the
  gutters and pass them as `contentInsetStartPx` / `contentInsetEndPx` (0 on other themes): the title and the first card
  stay at the content edge, the rule runs the full width, and ItemRow cards scroll out under the start gutter
  (`stripBleedStart`). `RowTitleView` and `PlaceholderRowView` take the same insets.
- **Focus and D-pad** (`CardStripView`): focus enters a row at its last focused card (first time: card 0, or
  `strip.selectedPosition = n`); LEFT on the first card leaves the row (the shell's focus search reaches the side menu);
  RIGHT on the last card stops; UP/DOWN leave to the rows above/below. Items are top-aligned.
- **Scrolling** (`RowScrollMode`): `PIVOT` (default, the TV default spec: the focused card's leading edge at 30 % of
  the row, 125 ms ease) or `KEEP_VISIBLE` (WeaselTV detail pages: scroll just enough, keeping the row padding as
  margin). `RowScrollMode.forDetailPage()` picks the right one; the presets use it.
- **Focus clearance**: on WeaselTV the strip's top/bottom padding grows to `height × (scale − 1) / 2 + glow + border`
  (at least 8dp) of `pageStyle.homeCardAppearance`, and on `KEEP_VISIBLE` rows the end padding too
  (`FocusSafeLazyRow`).
- **Rows in a page list.** Keep one `CardRowAdapter` per row (by row key) rather than per recycled `CardRowView`, and
  save / restore `row.strip.selectedPosition` per row key when a row view is recycled: the Compose rows kept their scroll
  and focus position while scrolled off-screen (`rememberLazyListState` is saveable). Put the rows in a Leanback
  `VerticalGridView` with `clipChildren = false`; it draws the focused row last, so its glow is on top.
- Presets: `row.showPeople(people, style, onClick, onLongClick, title, titleAccent, style, accent)`,
  `showDiscoverPeople(…)`, `showChapters(…)`, `showExtras(…)`.
- `LoadingRowView.bind(title, RowLoadingState, style, onClickItem)` shows "Loading…", the error (red rule), "No
  results" or the cards (2:3 season cards by default; pass `presenter` for others).
- `PlaceholderRowView.bind(title, message, accent, isError, focusableRow = true)` is Home's focusable loading / error /
  empty row (accent fill at 14 % on focus); `focusableRow = false` is `LoadingRowPlaceholder` (32dp below, no focus).

## 6. Grid

```kotlin
val grid = CardGridView(context)
grid.setCards(
    items = pager,                                   // List<T?>, usually a RequestPager; T : CardGridItem
    presenter = cardPresenter<BaseItem, GridCardView>(::GridCardView) { card, item, _, cellWidthPx ->
        card.bind(item, style, cellWidthPx, imageAspectRatio = ratio, imageScale = scale, showTitle = showTitles)
    },
    callbacks = CardGridCallbacks(
        onClick = { index, item -> },
        onLongClick = { index, item -> },
        onClickPlay = { index, item -> },            // play key on a playable item
        letterPosition = { letter -> viewModel.positionOfLetter(letter) },  // suspend, runs on IO
        onPosition = { columns, position -> },       // `positionCallback`
    ),
    config = CardGridConfig(columns = viewOptions.columns, spacingDp = viewOptions.spacing.toFloat(),
        topPaddingDp = 24f, showLetterButtons = sort == ItemSortBy.SORT_NAME, initialPosition = position,
        scrollMode = if (showDetails) GridScrollMode.Top(viewOptions.spacing.toFloat()) else GridScrollMode.Pivot),
    pageStyle = pageStyle,                           // the letter bar's accent
)
grid.observeChanges(viewLifecycleOwner, pager.changes)  // rebind loaded cards as pages arrive
override fun onBackPressed() = grid.onBackPressed()     // in the DestinationFragment
```

- **Paging**: `RequestPager.get(i)` returns null and starts loading the page; cards for null items are placeholders.
  The adapter reads only bound positions, like the Compose grid. `observeChanges` (or `grid.refresh()` after
  `pager.changes`) rebinds visible cards in place: ids are positions, so focus stays put. A new list (new filter / sort)
  via `setCards` keeps the focused position when it still exists, else focuses the last card.
- **Geometry**: `columns` columns, spacing `spacingDp` (WeaselTV: always 10dp) both ways, card width
  `(width − spacing × (columns − 1)) / columns`, top padding `topPaddingDp` (default 32 / 16dp), bottom
  `max(top, 32) + 16dp` on WeaselTV, 16dp otherwise. The letter bar (or a spacer as wide) sits 16dp to the right, so
  cards keep their size either way. "n / total" footer at the bottom; "No results" when empty.
- **D-pad**: focus enters at the last focused card. WeaselTV: UP/DOWN keep the column across rows (repeat keys count from
  the destination even before it is laid out), DOWN into a short last row takes its last card, DOWN on the last row
  stops, UP on the first row leaves (toolbar). Other themes: Leanback's spatial moves. LEFT on the first column leaves
  the grid; RIGHT on the last column enters the letter bar at the current letter.
- **Keys** (when focus is in the grid or letter bar): BACK released on any card but the first jumps to the first card
  (animated within 6 rows); BACK held jumps back to the previously focused card; play/pause plays a playable item;
  fast-forward / next / page-down / channel-down jump a page of rows down (6 rows; more for large libraries),
  rewind / previous / page-up / channel-up up; letters A–Z jump to that letter when the letter bar is shown. On
  Android 16+ (predictive back) BACK reaches only the page: call `grid.onBackPressed()` from `onBackPressed`.
- **Scrolling**: WeaselTV: just enough to show the focused card inside the padding (`KeepVisibleBringIntoViewSpec`).
  Other themes: `GridScrollMode.Pivot` (row top at 30 %) or `Top(spaceAboveDp)` (`ScrollToTopBringIntoViewSpec`).
- `CardGridView.rowHeightPx(...)` gives the first row's height for headers sized before items load.
- To focus the grid (the old `gridFocusRequester`): `grid.grid.requestFocus()` focuses the selected card;
  `grid.selectedIndex` is the focused card's index.
- `HiddenFocusView` is 1px tall: on API 28+ Views of no size cannot take focus (Compose's 0dp box could).

## 7. Images

- `CardImageView` (an `ImageView`): `load(url, crossfade, onError)`, `clear()`, `imageScale`.
- `ImageView.loadImageUrl(url, crossfade, onError, onSuccess)` for any ImageView: Coil 3 with the app's singleton
  loader (set up by the SHELL), sized from the view, no placeholder, loader default crossfade (off), failures logged
  with `logCoilError`.
- `CardImages.imageUrlService(context)` / `CardImages.itemImageUrl(context, item, imageType, fillWidthPx,
  fillHeightPx, useSeriesForPrimary)` for URLs sized like the cards'.

## 8. Text styles

Card text follows the old styles to the line height (Material 3 Compose text: every line exactly the line height, no
font padding), so captions measure as before: poster title 14sp/600/1.3 (WeaselTV) or bodyMedium SemiBold, subtitle
13sp/1.3 or bodySmall, row title Audiowide uppercase at the row's size with its tracking, etc. These helpers are
internal to the package; CONTROLS owns the general text widgets.
