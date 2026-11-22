# CONTROLS widgets: API reference (no-Compose UI)

Buttons, chips, tabs, inputs, dropdowns, dialogs, the context menu, the item information dialog, loading / error
views and the text widgets, rebuilt as Views by the CONTROLS agent. Packages:

| Package | What |
|---|---|
| `leanback.theme` (`NeonControls.kt`, `NeonControlText.kt`) | control recipes (`ControlStyles`), surface drawable, accent scope, tally / rule / overline drawables, `NeonType` text roles, `RequestActivityPalette` |
| `leanback.widget.control` | `AppButton`, `AppListItem`, `AppTabRow`, `EditTextBox` / `SearchEditTextBox`, `SwitchWithLabelView`, `SliderBarView`, `AppDropdownMenu`, `CircularProgressView`, `LoadingPageView`, `ErrorView`, `ControlTrackers` |
| `leanback.widget.dialog` | `AppDialog`, `MenuDialog`, `ConfirmDialog`, `ConfirmDeleteDialog`, `ScrollableDialog`, `chooseVersionParams` / `chooseStreamParams`, `ContextMenuDialog`, `ContextMenuHelper`, `ItemDetailsDialog`, `PlaylistPickerDialog` |
| `leanback.widget.text` | `QuickDetailsView`, `OverviewTextView`, `TitleValueTextView`, `GenreTextView`, `SimpleStarRatingView`, `TomatoRatingView`, `TimeDisplayView` / `AppClock`, `TitleOrLogoView`, `TableRowView`, `FittedTitleView`, `NeonRuleView`, `HeaderDimens`, `DetailPageLayout` / `FittedDetailHeaderLayout` / `DetailViewport`, `FlowRowLayout`, `WrapWeightRow` |

Plain-Kotlin models that the old Compose files held moved to Compose-free files in the same package and with the same
names, so old and new code share them: `ui/components/ContextMenuModel.kt` (`ContextMenu`, `ContextMenuActions`,
`PersonContextActions`, `ChosenTrackResult`, `buildContextMenuEntries`, `playWithChoices`, ...),
`ui/components/ContextMenuProvider.kt`, `ui/components/ErrorViewModel.kt`, `ui/components/ItemKindLabel.kt`
(`itemKindLabel`), `ui/components/StreamTypeLabel.kt` (`resourceFor`), `ui/data/ItemDetailsInfo.kt`
(`ItemDetailsDialogInfo`, `titleIndex`, `build*StreamInfo`).

## 1. Rules every control follows

- **Look.** Each control takes its recipe from `ControlStyles` (the old `neon*` helpers in `ui/theme/Neon.kt`): the
  Neon Board look when `AppTheme.isNeon`, the stock tv-material3 look otherwise. Sizes, paddings, focus scales (buttons
  1.1, list rows 1.05), borders, glows and the 300 / 500 ms tv-material3 scale motion are the old values.
- **Accent.** Controls resolve their accent when attached: an explicit `accent` / `neonScope` on the control, else the
  nearest ancestor tag set with `view.provideNeonAccent(color)` (Compose `ProvideNeonAccent`) or
  `view.provideButtonsOverArtwork()` (`ProvideButtonsOverArtwork`: white outline, black 80 % backing), else the hosting
  `DestinationFragment`'s `pageStyle.sectionAccent` (`ProvideNeonSectionAccent`), else volt. Dialogs live in their own
  window: pass `accent` or `opener` (a view of the page) so their tally and focus colour match the page. If the page
  accent changes after attach, call `control.surface.rebuild()`.
- **Drawing outside.** Glows, the focus scale and some rules draw outside the control's bounds, as Compose did. The
  controls switch `clipChildren` / `clipToPadding` off on their direct parent when attached; any further ancestor that
  should not cut the glow needs `clipChildren = false` too (Leanback rows already do). Like Compose lazy lists, a
  scrolling list should clip at its own bounds and leave content padding for the first / last row's glow
  (`MenuListView` does this).
- **Disabled = still focusable.** tv-material3 surfaces stayed focusable when disabled (they have a focused-disabled
  look). Use `isActionEnabled = false` on `AppButton`, `AppListItem`, `DropdownMenuItemView`: they draw disabled and
  ignore clicks but keep focus. Do not call `setEnabled(false)` on them; that removes them from D-pad focus.
- **Focus.** Every clickable control is a single focusable view (children never take focus). D-pad centre presses it
  (scale back to 1), release clicks, holding it long-clicks (`setOnLongClickListener`). Controls do not play click
  sounds (Compose tv surfaces did not); the overview / title-value widgets play them where the old code did.
- **Dialogs** are `android.app.Dialog` windows: when one closes, the platform puts focus back on the view that had it.
  Never restore focus by hand after a dialog.

## 2. Theme helpers (`leanback.theme`)

```kotlin
val style = ControlStyles.button(view.resolveNeonScope())      // Button.kt default
ControlStyles.primaryButton(scope)   // Play / Resume / Restart (accent fill, text ring, glow at rest)
ControlStyles.outlineButton(NeonColors.RED, scope)              // destructive Confirm
ControlStyles.glassButton(scope, stockContainer, stockFocused)  // player icon buttons
ControlStyles.listItem(scope)        // menu / dialog / list rows (neonListItem*)
ControlStyles.listItem(scope, borderWidthDp = w, borderOpacity = o, glowRadiusDp = r, glowAlpha = a)  // rail variant
ControlStyles.drawerItem(accent, stockContainer, stockContent)  // side menu colours (neonDrawerItemColors)
ControlStyles.dropdownItem(scope); ControlStyles.tab(scope, selected); ControlStyles.chip(scope)
ControlStyles.surfaceBorder(accent, restWidthDp, restColor); ControlStyles.surfaceGlow(accent)
```

- `ControlSurfaceDrawable(context, style)` draws a style for a `ControlState(focused, pressed, enabled, selected)`;
  `ControlScaleAnimator(view)` animates the focus scale. `SurfaceController(view) { scope -> style }` wires both to a
  view (background, state from `drawableStateChanged`, scope on attach) — use it for custom focusable surfaces (cards
  have their own in CARDS).
- `NeonTallyDrawable(context, accent)` (3dp selected bar + 12dp glow, inside the bounds), `NeonRuleBelowDrawable`,
  `NeonOverlineDrawable` (selected tab overline), `NeonIconGlowDrawable` (rail glyph glow), `neonFocusFill(accent,
  focused)`. All draw nothing off the WeaselTV theme.
- `NeonSizes`, `NeonGlowSpec`: the `NeonBoard.Size` / `GlowSpec` numbers in dp. `AppPalette.surfaceAtElevation(dp)`:
  tv-material3 `surfaceColorAtElevation`. `scaleAlpha(color, f)`.
- Text: `textView.applyTextStyle(NeonType.dialogTitle())` — every `NeonType` role (`hero`, `pageTitle`, `dialogTitle`,
  `playerTitle`, `condensedTitle`, `sectionTitle`, `numeral`, `wordmark`, `eyebrow`, `homeKicker`, `count`, `badge`,
  `button`, `tab`, `chip`, `railLabel`, `rowTitle`, `rowSubtitle`, `cardTitle`, `cardSubtitle`, `body`, `settingsLabel`,
  `settingsValue`, `small`) returns the stock slot off-theme. `ControlTextStyle.of(TextRole.X, sizeSp, weight)` for a
  stock slot with changes (Compose `style.copy(...)`); `"title".neonUpper()`;
  `textView.applyNeonEyebrow(text, accent)` (`NeonEyebrow`).
- `RequestActivityPalette` (ARGB): `ACTIVE`, `WAITING`, `READY`, `MOVIE`, `SERIES`, `TRACK`, `DIVIDER`, `MUTED`,
  `EMPTY_BORDER`, `EMPTY_GLOW`.

## 3. Buttons and chips

`AppButton(context)` — the old `Button`: icon or Font Awesome glyph + label in a row, 4dp padding around a 40dp content
row (48dp tall). Neon: outline square with a minimum height (wrapped labels grow it); stock: translucent pill with an
exact height. Focus scale 1.1.

```kotlin
val play = AppButton(context).apply {
    variant = ButtonVariant.PRIMARY            // DEFAULT, PRIMARY, GLASS, CHIP
    setIcon(R.drawable.lb_widget_ctl_ic_play_arrow)   // or setGlyph(R.string.fa_shuffle) (16sp in 32dp)
    setLabel(R.string.play, allCaps = AppTheme.isNeon)
    setLabelPaddingDp(6f, 8f)
    setOnClickListener { ... }
    setOnLongClickListener { ...; true }
}
val ok = AppButton.text(context, R.string.confirm) { ... }    // old TextButton: 8/4dp padding, 32dp content
button.setSurfaceStyle { scope -> ControlStyles.outlineButton(NeonColors.RED, scope) }   // any recipe
button.labelOnlyWhenFocused = true      // expanding button: label grows in when focused (the `collapsed` buttons)
button.isActionEnabled = false          // disabled look, still focusable
button.contentHeightDp = 40f; button.setContentPaddingDp(4f, 4f, 4f, 4f); button.exactContentHeight = true
button.accent = typeAccent(item.type)   // or provideNeonAccent on an ancestor
button.onContentColor = { color -> customChild.setTextColor(color) }   // custom children follow the state colour
```

- Glass buttons over video: `setSurfaceStyle { ControlStyles.glassButton(it, 0x40000000, selectedColor) }` with the
  player's own stock colours, 48dp (Neon) / 36dp square with 10 / 4dp padding.
- Chips: `variant = ButtonVariant.CHIP`, `isChipSelected = true`; 36dp tall (`NeonSizes.CHIP`) with
  `labelView.applyTextStyle(NeonType.chip())`. (The old app had no Compose chip; this is the Neon chip recipe.)

## 4. List rows

`AppListItem(context, dense = false)` — tv-material3 `ListItem` / `DenseListItem` (ListItemWrapper): leading slot, overline
/ headline / supporting, trailing text or view. Heights 48 / 56 (leading) / 64 (two lines) / 80 (three); dense 40 / 40
/ 56 / 72. Focus scale 1.05.

```kotlin
AppListItem(context).apply {
    setHeadline("Subtitles"); setSupporting("English", reserveLine = true); setOverline(null)
    setLeading(ListLeading.SelectedDot(selected))   // Icon(res, tint) / Glyph(fa, color) / Custom(view) / null
    setTrailing("12 items")                          // or setTrailingView(view)
    isItemSelected = selected; isActionEnabled = true
    setOnClickListener { ... }
}
```

`SelectedDotView` is `SelectedLeadingContent` on its own.

## 5. Tabs

`AppTabRow(context, keyed = false)` — `TabRow` / `KeyedTabRow`.

```kotlin
tabs.setTabs(listOf("Movies", "Collections"), selected = 0)
tabs.onTabClick = { index -> viewModel.updateSelectedTabIndex(index) }
tabs.selectedIndex = index     // after the ViewModel changes: updates the look, scrolls the tab a third of the way in
tabs.topContentPaddingDp = 20f; tabs.bottomContentPaddingDp = 20f
```

Focus: entering with LEFT lands on the last tab, RIGHT on the first, UP / DOWN on the selected tab; OK selects (focus
alone does not). The Neon row bleeds 20dp past its slot on both sides (it adjusts its own margins when its parent's
layout params are `MarginLayoutParams`).

## 6. Inputs

`EditTextBox(context, EditTextBox.Variant.STATE | VALUE)` — the decorated field; `editText` is the real `EditText`.

```kotlin
val name = EditTextBox(context, EditTextBox.Variant.VALUE).apply {
    text = initial; placeholder = getString(R.string.name)
    editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
    editText.imeOptions = EditorInfo.IME_ACTION_DONE
    onImeAction = { action -> if (action == EditorInfo.IME_ACTION_DONE) { submit(); true } else false }
    onTextChanged = { viewModel.setName(it) }
    isInputValid = { it.isNotBlank() }; setPassword(false); maxLines = 1; readOnly = false
}
val search = SearchEditTextBox(context).apply { onSearch = { viewModel.search(text) } }
```

Focusing a field with the D-pad shows the soft keyboard, as Compose text fields did (`showKeyboardOnFocus`). In
`SearchEditTextBox`, UP / DOWN always leave the field; LEFT / RIGHT leave only when `readOnly`. Fields report focus to
`ControlTrackers.textInputActive`.

`SwitchWithLabelView` (`label.text`, `checked`, `onStateChange`, `isSwitchEnabled`): the whole row is focusable; OK
calls `onStateChange(!checked)` (set `checked` from the result).

`SliderBarView` (`min`, `max`, `value`, `interval`, `enableWrapAround`, `outlineColor`, `onChange`): LEFT / RIGHT change
the value and call `onChange` at once; OK calls `onChange(value)`. The track grows from 6 to 12dp when focused.

`AppDropdownMenu(anchor)` — sort / filter popups (`AppDropdownMenu` + `TvDropdownMenuItem`):

```kotlin
val menu = AppDropdownMenu(sortButton)
SortOption.entries.forEach { option -> menu.addItem(getString(option.label), leadingIcon = check(option)) { choose(option); menu.dismiss() } }
menu.onDismissRequest = { ... }
menu.show()    // first row focused; BACK closes; focus returns to the anchor
```

Rows are `DropdownMenuItemView`s (48dp min, 112–280dp wide); `addView` adds custom rows (eg nested filter rows built from
`DropdownMenuItemView` with `setTrailingView`).

## 7. Dialogs

All are `AppDialog`s: `show()`, `dismiss()`, `onDismissRequest` (BACK, or the dialog closing itself after a choice;
not a plain `dismiss()` from code). `DialogOptions(usePlatformDefaultWidth, elevationDp, tallyColor, outline, look,
gravity, dismissOnBack)`.

```kotlin
// A basic dialog with your own content (BasicDialog): Neon card + line2 edge + tally; stock 8dp rounded surface
AppDialog(context, DialogOptions(usePlatformDefaultWidth = false), opener = view).apply {
    setPanelContent(myContent); onDismissRequest = { ... }; show()
}
// What's New style outlined panel
AppDialog(context, DialogOptions(look = PanelLook.OUTLINE, outline = NeonColors.VOLT))

// List dialog (DialogPopup): title, rule, rows; first row focused, UP/DOWN wrap at the ends
MenuDialog.show(context, title, listOf(
    MenuItem.icon(getString(R.string.play), R.drawable.lb_widget_ctl_ic_play_arrow, dismissOnClick = true) { play() },
    MenuItem.glyph(getString(R.string.shuffle), R.string.fa_shuffle) { shuffle() },
    MenuDivider,
    MenuItem(headline = "English", supporting = "AAC 5.1", leading = ListLeading.SelectedDot(true), onClick = { ... }),
), onDismissRequest = { ... }, dismissOnClick = true, waitToLoad = fromLongClick, opener = view)

// Track / version choosers (chooseStream / chooseVersionParams)
MenuDialog(context, chooseStreamParams(resources, streams, current, MediaStreamType.SUBTITLE, lang) { index -> ... }, elevationDp = 3f).show()

ConfirmDialog.show(context, title, body, onConfirm = { ... })          // closes itself; Cancel focused first
ConfirmDialog(context, title, body, onCancel, onConfirm)               // you dismiss it (as Compose)
ConfirmDeleteDialog(context, item.title ?: "", onCancel = { d.dismiss() }, onConfirm = { delete() }).show()
ScrollableDialog(context, widthDp = 600f, maxHeightDp = 380f).apply { content.addView(textView); show() }
ItemDetailsDialog(context, ItemDetailsDialogInfo(item), showFilePath = isAdmin, opener = view).show()
PlaylistPickerDialog(context, title, vm.playlistState, onClick, onSearch, createEnabled, onCreatePlaylist).show()
```

- `MenuDialog`: Neon 448dp panel, uppercase title shrinking from 26sp to fit three lines, accent rule edge to edge, 24dp
  insets, rows 20dp inside the list's ends (`DIALOG_LIST_EDGE_DP`). `waitToLoad` (long-press menus): rows look disabled
  and ignore clicks for 1 s or until OK is released. It does not count in `ControlTrackers.openDialogs` (the Compose
  `DialogPopup` did not either); `AppDialog`s with a basic panel do (`BasicDialog`).
- `ScrollableDialog` / `ItemDetailsDialog`: the content area takes focus; UP / DOWN scroll 100px.
- `PlaylistPickerDialog`: a CONTROLS-private port of `PlaylistDialog` (ui/detail/PlaylistList.kt) for the context menu;
  DETAILS may reuse it.

## 8. Context menu

Entries and actions are plain Kotlin (`buildContextMenuEntries` in `ui/components/ContextMenuModel.kt`, shared with the
old Compose menu), so every item type and state shows the same entries as before.

```kotlin
// Grids and rows (ContextMenuUtils): one per screen
private val contextMenu by lazy { ContextMenuHelper(this, viewModel /* ContextMenuProvider */) { preferences } }
card.setOnLongClickListener { contextMenu.showContextMenu(position, item, it); true }

// Full control (details pages, Home rows with stream choices, next up, reorder...)
ContextMenuDialog.show(
    context,
    ContextMenu.ForBaseItem(fromLongClick = true, item = item, chosenStreams = chosen, showGoTo = true,
        showStreamChoices = true, canDelete = canDelete, canRemoveContinueWatching = true, canRemoveNextUp = true,
        actions = ContextMenuActions(navigateTo = nav::navigateTo, onShowOverview = { ItemDetailsDialog(...).show() }, ...)),
    getMediaSource = viewModel::getMediaSource, preferredSubtitleLanguage = prefs.subtitleLanguage,
    onDismissRequest = { }, opener = card,
)
ContextMenuDialog.show(context, ContextMenu.ForPerson(true, person, PersonContextActions(...)), null, null, {})
```

Follow-up dialogs (choose version / audio / subtitles, delete confirmation, "Play with") open on top of the menu; BACK
returns to the menu, a choice closes everything. Focus returns to the long-pressed card.

## 9. Loading and error

- `LoadingPageView(context, focusEnabled = true, delayMs = 0)` — `LoadingPage` (`delayMs = 300` for
  `DelayedLoadingPage`): fills its space, takes focus on attach, shows the Neon loading artwork (kids' set when
  `LoadingArtwork.signedInKids`, which the shell sets) or a 48dp spinner, logs "content after loading page" to
  `NavTiming` when removed.
- `CircularProgressView` — `CircularProgress` in the border / accent colour; size it with layout params.
- `ErrorView(context, message, exception, onSendLogs = errorViewModel::sendLogs, onRetry = { ... })` (also takes a
  `LoadingState.Error` / `DataLoadingState.Error`): Neon red icon box, title, Try again, Send app logs, message and
  "Caused by" chain; at most 480dp tall; UP / DOWN scroll 100px while there is more, then move focus. Get the
  `ErrorViewModel` with `by viewModels()`.

## 10. Text widgets

```kotlin
QuickDetailsView(context).bind(item.ui.quickDetailsText, timeRemaining = item.timeRemainingOrRuntime,
    endsAt = null, enabledToggles = enabledDisplayToggles(preferences.appPreferences))
OverviewTextView(context).apply { maxLines = 3; compact = headerCompact; setText(item.data.overview); onClick = { ... } }
TitleValueTextView(context).apply { set(getString(R.string.director), name); onClick = { ... } }
GenreTextView(context).apply { setGenres(item.data.genres.orEmpty()); compact = headerCompact }
SimpleStarRatingView(context).setRating(item.data.communityRating); TomatoRatingView(context).setRating(item.data.criticRating)
TimeDisplayView(context)        // in a FrameLayout, gravity TOP or END; TimeDisplayView.headerEndPaddingPx(context) for headings
TitleOrLogoView(context).apply { compactHeader = compact; bind(item, showLogo = true, imageUrlService) }
TitleOrLogoView(context, fillLogo = true)    // SimpleTitleOrLogo
TableRowView.from(context, getString(R.string.path), path)
NeonRuleView(context)            // section rule (GONE off-theme); section = true for NeonSectionRule
FittedTitleView(context, maxLines = 3).setStyledText(title, NeonType.dialogTitle())   // FittedPanelTitle
```

- `QuickDetailsView` is a `FlowRowLayout`: pieces wrap as units ("2024 • 2h 10m", " • PG-13", " • 7.5★",
  " • 85%🍅", " • Ends at 9:41 PM"); the end time follows `AppClock`. `setTextStyle` / `setTextColor` change the look.
- `HeaderDimens`: `HeaderUtils` numbers and `detailTopPadding()`, `detailInfoTextScale()`, `home()`, `recommended()`.
- Detail header fitting (ViewportLayout.kt): wrap a detail page in `DetailPageLayout` (Neon: hidden until the first row
  and action bar report their heights or 300 ms, then the header size is locked and the page fades in over 100 ms);
  put the header in `FittedDetailHeaderLayout(context, reservedHeightDp, firstRowHeightDp)`, which scales it down from
  the top-left to fit and reports `onCompactChanged(budget < 230dp)`; call `row.reportDetailRowHeight(priority)` and
  `actionBar.reportDetailActionHeight()`. `focusClearancePx(context, appearance, contentHeight)` is the room a card row
  keeps for the focused card's scale, border and glow (`FocusSafeLazyRow`).
- `AppClock.now`: a `StateFlow<LocalDateTime>` ticking every 2 s while collected (the old `LocalClock`).

## 11. For the shell

- Observe `ControlTrackers.openDialogs` and `ControlTrackers.textInputActive` instead of `RequestUiState.dialogs` /
  `textInputActive`.
- Set `LoadingArtwork.signedInKids` when a profile signs in (the old `LocalKidsLoadingArtwork`).
- Side-menu rows: `AppListItem` with `setSurfaceStyle { ControlStyles.listItem(it, borderWidthDp = ..., borderOpacity = ...,
  glowRadiusDp = ..., glowAlpha = ...) }` and the colours of `ControlStyles.drawerItem(...)`, plus `NeonTallyDrawable`
  and `NeonIconGlowDrawable`.
