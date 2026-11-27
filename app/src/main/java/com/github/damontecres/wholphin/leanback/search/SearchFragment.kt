package com.github.damontecres.wholphin.leanback.search

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.fragment.app.viewModels
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.DiscoverItem
import com.github.damontecres.wholphin.leanback.shell.DestinationFragment
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.CardDimens
import com.github.damontecres.wholphin.leanback.theme.PageStyle
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.widget.card.BannerCardView
import com.github.damontecres.wholphin.leanback.widget.card.CardCallbacks
import com.github.damontecres.wholphin.leanback.widget.card.CardGridCallbacks
import com.github.damontecres.wholphin.leanback.widget.card.CardGridConfig
import com.github.damontecres.wholphin.leanback.widget.card.CardGridView
import com.github.damontecres.wholphin.leanback.widget.card.CardImages
import com.github.damontecres.wholphin.leanback.widget.card.CardPresenter
import com.github.damontecres.wholphin.leanback.widget.card.CardRowAdapter
import com.github.damontecres.wholphin.leanback.widget.card.CardRowStyle
import com.github.damontecres.wholphin.leanback.widget.card.CardRowView
import com.github.damontecres.wholphin.leanback.widget.card.DiscoverItemCardView
import com.github.damontecres.wholphin.leanback.widget.card.GridCardView
import com.github.damontecres.wholphin.leanback.widget.card.PersonCardView
import com.github.damontecres.wholphin.leanback.widget.card.PlaceholderRowView
import com.github.damontecres.wholphin.leanback.widget.card.RowTitleView
import com.github.damontecres.wholphin.leanback.widget.card.SeasonCardView
import com.github.damontecres.wholphin.leanback.widget.card.cardPresenter
import com.github.damontecres.wholphin.leanback.widget.control.AppButton
import com.github.damontecres.wholphin.leanback.widget.control.AppTabRow
import com.github.damontecres.wholphin.leanback.widget.control.SearchEditTextBox
import com.github.damontecres.wholphin.leanback.widget.dialog.ContextMenuHelper
import com.github.damontecres.wholphin.leanback.widget.text.TimeDisplayView
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.data.RowColumn
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.search.PrimarySearchTypes
import com.github.damontecres.wholphin.ui.search.SearchResult
import com.github.damontecres.wholphin.ui.search.SearchState
import com.github.damontecres.wholphin.ui.search.SearchViewModel
import com.github.damontecres.wholphin.ui.titleStringRes
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import timber.log.Timber

// Focus rows of the old page (SearchPage.kt): the search bar, the tabs, Requests / combined results, then the types
private const val SEARCH_ROW = 0
private const val TAB_ROW = SEARCH_ROW + 1
private const val SEERR_ROW = TAB_ROW + 1
private const val COMBINED_ROW = SEERR_ROW
private const val RESULTS_START = SEERR_ROW + 1

/** Typing waits this long before searching; the keyboard's Search action and voice search at once */
private const val SEARCH_DELAY_MS = 750L

/**
 * The Search page (`SearchPage`, no-Compose UI): the search bar (voice button, field, View options), on WeaselTV the
 * primary type switches, the Library / Requests tabs (combined results with Seerr), and the results: a row per type
 * (and a Requests row) or, with combined results, one grid.
 *
 * Behaviour kept from the old page: typing searches after 750 ms, the keyboard's Search action and voice search at
 * once; after a submitted search, focus moves to the first row that found something once the rows before it are done
 * (the old effect's row arithmetic is kept, see [autoFocusResults]); BACK on the field stops editing, then moves on;
 * long press on a library card opens the context menu; scrolling the combined grid past its first row hides the type
 * switches and tabs.
 */
@AndroidEntryPoint
class SearchFragment : DestinationFragment() {
    private val viewModel: SearchViewModel by viewModels()
    private lateinit var voice: VoiceSearch
    private val contextMenu by lazy { ContextMenuHelper(this, viewModel) { preferences } }

    // The old page state
    private var query = ""
    private var searchClicked = false
    private var immediateSearchQuery: String? = null
    private var selectedTab = 0
    private var showHeader = true
    private var combinedMode = false
    private var voiceButtonVisible = true
    private var debounceJob: Job? = null
    private var tabsKey: Pair<Boolean, Boolean>? = null
    private var shownTabTitles: List<String>? = null

    // Views
    private lateinit var root: LinearLayout
    private lateinit var header: FocusRestoreRow
    private var voiceButton: AppButton? = null
    private lateinit var field: SearchFieldController
    private lateinit var viewOptionsButton: AppButton
    private var typeToggles: SearchTypeToggleRow? = null
    private lateinit var tabs: AppTabRow
    private lateinit var content: FrameLayout
    private lateinit var rowsList: PageRowsView
    private lateinit var libraryResults: CombinedResultsView<BaseItem>
    private lateinit var seerrResults: CombinedResultsView<DiscoverItem>
    private val typeRows = HashMap<BaseItemKind, ResultRow<BaseItem>>()
    private var discoverRow: ResultRow<DiscoverItem>? = null
    private var noResultsRow: PlaceholderRowView? = null
    private var focusListener: ViewTreeObserver.OnGlobalFocusChangeListener? = null

    private var viewOptionsDialog: SearchViewOptionsDialog? = null
    private var typeDialog: SearchTypeOptionsDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        query = requireDestination<Destination.Search>().query
        val prefs = preferences.appPreferences.interfacePreferences.searchPreferences
        combinedMode = prefs.combinedSearchResults
        voiceButtonVisible = prefs.showVoiceSearchButton
        voice =
            VoiceSearch(this, viewModel.voiceInputManager) { spoken ->
                val changed = spoken != query
                setQuery(spoken, fromUser = false)
                triggerImmediateSearch(spoken)
                // The old `searchClicked` was remembered per query: a new spoken query started it over as false
                if (changed) searchClicked = false
            }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val context = requireContext()
        val neon = AppTheme.isNeon
        root =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                clipToPadding = false
            }

        // Search bar: 16dp in, 16dp from the top, ending short of the clock on WeaselTV
        header =
            FocusRestoreRow(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                clipChildren = false
                clipToPadding = false
                setPaddingRelative(
                    context.dp(16),
                    context.dp(16),
                    if (neon) TimeDisplayView.headerEndPaddingPx(context) else context.dp(16),
                    0,
                )
            }
        if (voice.isAvailable) {
            voiceButton =
                voice.createButton(context).also { button ->
                    header.addView(
                        button,
                        LinearLayout.LayoutParams(context.dp(40), context.dp(40)).apply { marginEnd = context.dp(12) },
                    )
                }
        }
        val box = SearchEditTextBox(context).apply { id = R.id.lb_search_field }
        header.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        field = SearchFieldController(box)
        header.defaultFocus = box.editText
        field.setText(query)
        field.onUserQuery = { setQuery(it, fromUser = true) }
        field.onSubmit = { triggerImmediateSearch(query) }
        viewOptionsButton =
            expandableFaButton(context, R.string.view_options, R.string.fa_sliders).apply {
                id = R.id.lb_search_view_options
                setOnClickListener { showViewOptions() }
            }
        header.addView(
            viewOptionsButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = context.dp(12)
            },
        )
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        if (neon) {
            typeToggles =
                SearchTypeToggleRow(context, viewModel::onClickExcludeSearchableType).also {
                    root.addView(it, spacedParams(context))
                }
        }
        tabs =
            AppTabRow(context).apply {
                visibility = View.GONE
                onTabClick = { index -> onTabClick(index) }
            }
        root.addView(
            tabs,
            spacedParams(context).apply {
                marginStart = context.dp(16)
                marginEnd = context.dp(16)
            },
        )

        content =
            FrameLayout(context).apply {
                clipChildren = false
                clipToPadding = false
            }
        root.addView(
            content,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = context.dp(12) },
        )
        rowsList =
            PageRowsView(context, keepVisible = false).apply {
                id = R.id.lb_search_results
                setPadding(0, context.dp(8), 0, context.dp(44))
            }
        content.addView(
            rowsList,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).bleedIntoGutters(context),
        )
        libraryResults =
            CombinedResultsView(
                context,
                pageStyle = { pageStyle },
                presenter =
                    cardPresenter<BaseItem, GridCardView>(::GridCardView) { card, item, _, w ->
                        card.bind(item, pageStyle, w)
                    },
                callbacks =
                    CardGridCallbacks(
                        onClick = { index, item ->
                            setPosition(RowColumn(COMBINED_ROW, index))
                            onClickItem(item)
                        },
                        onLongClick = { index, item -> onLongClickItem(COMBINED_ROW, index, item) },
                        onClickPlay = { index, item ->
                            setPosition(RowColumn(COMBINED_ROW, index))
                            viewModel.navigationManager.navigateTo(Destination.Playback(item))
                        },
                        onPosition = { columns, index -> onGridPosition(columns, index) },
                    ),
            )
        seerrResults =
            CombinedResultsView(
                context,
                pageStyle = { pageStyle },
                presenter =
                    cardPresenter<DiscoverItem, DiscoverItemCardView>(::DiscoverItemCardView) { card, item, _, w ->
                        card.bind(item, pageStyle, widthPx = w, showOverlay = true)
                    },
                callbacks =
                    CardGridCallbacks(
                        onClick = { index, item ->
                            setPosition(RowColumn(COMBINED_ROW, index))
                            viewModel.navigationManager.navigateTo(discoverDestination(item))
                        },
                        onPosition = { columns, index -> onGridPosition(columns, index) },
                    ),
            )
        content.addView(
            libraryResults.view,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        content.addView(
            seerrResults.view,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        return root
    }

    private fun spacedParams(context: Context) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(12)
        }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        voice.start(viewLifecycleOwner)
        focusListener =
            ViewTreeObserver
                .OnGlobalFocusChangeListener { _, newFocus ->
                    if (newFocus != null && isDescendant(newFocus, tabs)) setPosition(RowColumn(TAB_ROW, 0))
                }.also { view.viewTreeObserver.addOnGlobalFocusChangeListener(it) }

        // Start with the current preferences, then follow changes from View options
        viewModel.userPreferencesService.flow
            .map { it.appPreferences.interfacePreferences.searchPreferences }
            .collectWhileStarted { prefs ->
                val combinedChanged = prefs.combinedSearchResults != combinedMode
                combinedMode = prefs.combinedSearchResults
                voiceButtonVisible = prefs.showVoiceSearchButton
                viewOptionsDialog?.update(combinedMode, voiceButtonVisible)
                if (combinedChanged) restartDebounce()
                render()
            }
        combine(viewModel.state, viewModel.seerrActive) { state, active -> state to active }
            .collectWhileStarted { (state, active) ->
                typeDialog?.update(state.excludedSearchableTypes, state.discoverEnabled)
                render(state, active)
                autoFocusResults(state, active)
            }
        restartDebounce()
    }

    override fun onDestroyView() {
        focusListener?.let { view?.viewTreeObserver?.removeOnGlobalFocusChangeListener(it) }
        focusListener = null
        voice.release()
        viewOptionsDialog?.dismiss()
        typeDialog?.dismiss()
        viewOptionsDialog = null
        typeDialog = null
        super.onDestroyView()
    }

    override fun onPause() {
        voice.stop()
        super.onPause()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) voice.stop()
    }

    override fun onScreenFocus(restoring: Boolean) {
        if (restoring) {
            super.onScreenFocus(true)
            return
        }
        // The old page focused its saved row on open: the search bar (its field) at first
        if (!focusRow(viewModel.position.value.row)) field.box.editText.requestFocus()
    }

    override fun onPreviewKeyEvent(event: KeyEvent): Boolean = field.handleKey(event)

    override fun onBackPressed(): Boolean {
        if (field.handleBack()) return true
        if (libraryResults.view.visibility == View.VISIBLE && libraryResults.grid.hasFocus()) return libraryResults.grid.onBackPressed()
        if (seerrResults.view.visibility == View.VISIBLE && seerrResults.grid.hasFocus()) return seerrResults.grid.onBackPressed()
        return false
    }

    override fun onPageStyleChanged(style: PageStyle) {
        typeRows.values.forEach { it.restyle() }
        discoverRow?.restyle()
        libraryResults.grid.refresh()
        seerrResults.grid.refresh()
    }

    // ---- query and searching ----

    private fun setQuery(
        value: String,
        fromUser: Boolean,
    ) {
        if (value == query) return
        query = value
        if (!fromUser) field.setText(value)
        // `searchClicked` was remembered per query
        searchClicked = false
        restartDebounce()
        render()
    }

    /** The old `LaunchedEffect(query, combinedMode)`: search 750 ms after the last change, unless already searched */
    private fun restartDebounce() {
        debounceJob?.cancel()
        if (view == null) return
        if (immediateSearchQuery == query) {
            immediateSearchQuery = null
            return
        }
        val q = query
        val combined = combinedMode
        debounceJob =
            launchWhileStarted {
                delay(SEARCH_DELAY_MS)
                viewModel.search(q, combined)
            }
    }

    private fun triggerImmediateSearch(searchQuery: String) {
        immediateSearchQuery = searchQuery
        searchClicked = true
        viewModel.search(searchQuery, combinedMode)
    }

    private fun setPosition(position: RowColumn) {
        Timber.v("pos=%s", position)
        viewModel.position.value = position
    }

    private fun onClickItem(item: BaseItem) {
        Timber.v("Clicked %s, type=%s", item.id, item.type)
        viewModel.navigationManager.navigateTo(item.destination())
    }

    private fun onLongClickItem(
        rowIndex: Int,
        index: Int,
        item: BaseItem,
    ) {
        setPosition(RowColumn(rowIndex, index))
        contextMenu.showContextMenu(index, item, view?.findFocus())
    }

    private fun onGridPosition(
        columns: Int,
        index: Int,
    ) {
        val show = index < columns
        if (show != showHeader) {
            showHeader = show
            render()
        }
    }

    private fun onTabClick(index: Int) {
        selectedTab = index
        setPosition(RowColumn(if (index == 0) COMBINED_ROW else SEERR_ROW, 0))
        render()
    }

    // ---- rendering ----

    private fun render() {
        render(viewModel.state.value, viewModel.seerrActive.value)
    }

    private fun render(
        state: SearchState,
        seerrActive: Boolean,
    ) {
        if (view == null) return
        // The selected tab started over when Seerr or Requests search was switched on or off, and with no query
        val key = seerrActive to state.discoverEnabled
        if (key != tabsKey) {
            tabsKey = key
            selectedTab = 0
        }
        if (!seerrActive || query.isBlank()) selectedTab = 0

        voiceButton?.visibility = if (voiceButtonVisible) View.VISIBLE else View.GONE
        typeToggles?.let { row ->
            row.visibility = if (showHeader) View.VISIBLE else View.GONE
            row.bind(state.possibleSearchableTypes, state.excludedSearchableTypes)
        }
        val discoverShown = seerrActive && state.discoverEnabled
        val tabTitles =
            buildList {
                add(getString(R.string.library))
                if (discoverShown) add(getString(R.string.discover))
            }
        if (shownTabTitles != tabTitles) {
            shownTabTitles = tabTitles
            tabs.setTabs(tabTitles, selectedTab)
        }
        tabs.selectedIndex = selectedTab
        val showTabs = discoverShown && query.isNotBlank() && showHeader && combinedMode
        tabs.visibility = if (showTabs) View.VISIBLE else View.GONE

        val isLibraryTab = selectedTab == 0
        rowsList.visibility = if (isLibraryTab && !combinedMode) View.VISIBLE else View.GONE
        libraryResults.view.visibility = if (isLibraryTab && combinedMode) View.VISIBLE else View.GONE
        seerrResults.view.visibility = if (!isLibraryTab && combinedMode) View.VISIBLE else View.GONE
        when {
            isLibraryTab && combinedMode -> libraryResults.bind(state.combinedResults, query)
            !isLibraryTab && combinedMode -> seerrResults.bind(state.seerrResults, query)
            isLibraryTab -> renderRows(state, discoverShown)
        }
    }

    private fun renderRows(
        state: SearchState,
        discoverShown: Boolean,
    ) {
        val context = requireContext()
        val shown = mutableListOf<View>()
        state.includedSearchableTypes.forEachIndexed { index, type ->
            val row =
                typeRows.getOrPut(type) {
                    ResultRow(context, RESULTS_START + index, typeRowPresenter(type)).apply {
                        callbacks =
                            CardCallbacks(
                                onClick = { i, item ->
                                    setPosition(RowColumn(rowIndex, i))
                                    onClickItem(item)
                                },
                                onLongClick = { i, item -> onLongClickItem(rowIndex, i, item) },
                            )
                    }
                }
            row.rowIndex = RESULTS_START + index
            val result = state.results[type] ?: SearchResult.Searching
            if (row.bind(getString(type.titleStringRes), result)) shown += row.view
        }
        if (discoverShown) {
            val row =
                discoverRow ?: ResultRow(
                    context,
                    SEERR_ROW,
                    cardPresenter<DiscoverItem, DiscoverItemCardView>(::DiscoverItemCardView) { card, item, _, _ ->
                        card.bind(item, pageStyle, showOverlay = true)
                    },
                ).apply {
                    callbacks =
                        CardCallbacks(
                            onClick = { i, item ->
                                setPosition(RowColumn(SEERR_ROW, i))
                                viewModel.navigationManager.navigateTo(discoverDestination(item))
                            },
                        )
                    discoverRow = this
                }
            if (row.bind(getString(R.string.discover), state.seerrResults)) shown += row.view
        }
        // Every row came back empty: say so instead of leaving the page blank
        val allEmpty =
            shown.isEmpty() &&
                query.isNotBlank() &&
                state.includedSearchableTypes.isNotEmpty() &&
                state.includedSearchableTypes.all { state.results[it] is SearchResult.Success }
        if (allEmpty) {
            val row = noResultsRow ?: PlaceholderRowView(context).also { noResultsRow = it }
            row.bind(
                getString(R.string.results),
                context.noResultsText(query, pageStyle.sectionAccent),
                pageStyle.sectionAccent,
                focusableRow = false,
                insetStartPx = context.gutterStartPx() + context.dp(16),
                insetEndPx = context.gutterEndPx() + context.dp(16),
            )
            shown += row
        }
        rowsList.setRows(shown)
    }

    /** `SearchPageCard`: episodes as titled banner cards, people as person cards, the rest as 2:3 posters */
    private fun typeRowPresenter(type: BaseItemKind): CardPresenter<BaseItem> =
        when (type) {
            BaseItemKind.EPISODE -> {
                cardPresenter<BaseItem, BannerCardView>(::BannerCardView) { card, item, _, _ ->
                    card.bind(
                        item,
                        pageStyle,
                        cardHeightDp = CardDimens.HEIGHT_EPISODE_DP.toFloat(),
                        useSeriesForPrimary = item?.useSeriesForPrimary ?: true,
                        withTitle = true,
                        title = item?.title,
                        subtitle = item?.subtitle,
                    )
                    // `Modifier.padding(horizontal = 8.dp)` around each episode card
                    (card.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                        val side = card.context.dp(8)
                        if (lp.marginStart != side) {
                            lp.marginStart = side
                            lp.marginEnd = side
                            card.layoutParams = lp
                        }
                    }
                }
            }

            BaseItemKind.PERSON -> {
                cardPresenter<BaseItem, PersonCardView>(::PersonCardView) { card, item, _, _ ->
                    val url = item?.let { CardImages.imageUrlService(card.context).getItemImageUrl(it, ImageType.PRIMARY) }
                    card.bind(item?.name ?: "", null, url, item?.favorite ?: false, pageStyle)
                }
            }

            else -> {
                cardPresenter<BaseItem, SeasonCardView>(::SeasonCardView) { card, item, _, _ ->
                    card.bind(item, pageStyle, imageHeightDp = CardDimens.HEIGHT_2X3_DP.toFloat(), showImageOverlay = true)
                }
            }
        }

    // ---- focus ----

    /**
     * The old effect after a submitted search: once the first section with results has no searching section before it,
     * focus its row. Kept as it was, including that a row-per-type page passes the section's index as the focus row
     * (0 is the search bar, 2 the Requests row), so the field usually keeps focus there.
     */
    private fun autoFocusResults(
        state: SearchState,
        seerrActive: Boolean,
    ) {
        if (!searchClicked || viewModel.position.value.row > TAB_ROW || !isTopScreen) return
        val isLibraryTab = selectedTab == 0
        val results =
            if (isLibraryTab) {
                if (combinedMode) listOf(state.combinedResults) else state.includedSearchableTypes.map { state.results[it] }
            } else {
                listOf(state.seerrResults)
            }
        val firstSuccess = results.indexOfFirst { it is SearchResult.Success || it is SearchResult.SuccessSeerr }
        if (firstSuccess < 0) return
        if (results.subList(0, firstSuccess).any { it is SearchResult.Searching }) return
        val target =
            if (isLibraryTab) {
                if (combinedMode) COMBINED_ROW else firstSuccess
            } else {
                SEERR_ROW
            }
        // Let the rows settle into the list before moving focus
        root.post { if (isTopScreen) focusRow(target) }
        Timber.v("seerrActive=%s target=%s", seerrActive, target)
    }

    /** Focus one of the old page's focus rows, if it is showing; true when focus moved */
    private fun focusRow(row: Int): Boolean {
        val isLibraryTab = selectedTab == 0
        return when {
            row == SEARCH_ROW -> {
                field.box.editText.requestFocus()
            }

            row == TAB_ROW -> {
                tabs.visibility == View.VISIBLE && tabs.requestFocus()
            }

            combinedMode && row == COMBINED_ROW -> {
                val results = if (isLibraryTab) libraryResults else seerrResults
                results.view.visibility == View.VISIBLE && results.grid.visibility == View.VISIBLE && results.grid.grid.requestFocus()
            }

            !combinedMode && row == SEERR_ROW -> {
                discoverRow?.focus(rowsList) == true
            }

            !combinedMode && row >= RESULTS_START -> {
                val type =
                    viewModel.state.value.includedSearchableTypes
                        .getOrNull(row - RESULTS_START)
                type?.let { typeRows[it] }?.focus(rowsList) == true
            }

            else -> {
                false
            }
        }
    }

    private fun isDescendant(
        view: View,
        ancestor: View,
    ): Boolean {
        var v: View? = view
        while (v != null) {
            if (v === ancestor) return true
            v = v.parent as? View
        }
        return false
    }

    // ---- dialogs ----

    private fun showViewOptions() {
        if (viewOptionsDialog?.isShowing == true) return
        viewOptionsDialog =
            SearchViewOptionsDialog(
                requireContext(),
                opener = viewOptionsButton,
                combinedResults = combinedMode,
                voiceSearchButtonVisible = voiceButtonVisible,
                onCombinedResultsChange = viewModel::setCombinedResults,
                onVoiceSearchButtonVisibleChange = viewModel::setVoiceSearchButtonVisible,
                onClickFilterTypes = { showTypeOptions() },
            ).apply {
                onDismissRequest = { viewOptionsDialog = null }
                show()
            }
    }

    private fun showTypeOptions() {
        if (typeDialog?.isShowing == true) return
        val state = viewModel.state.value
        val seerrActive = viewModel.seerrActive.value
        typeDialog =
            SearchTypeOptionsDialog(
                requireContext(),
                opener = viewOptionsButton,
                pageStyle = pageStyle,
                searchableTypes =
                    if (AppTheme.isNeon) {
                        state.possibleSearchableTypes.filterNot { it in PrimarySearchTypes }
                    } else {
                        state.possibleSearchableTypes
                    },
                excludedSearchableTypes = state.excludedSearchableTypes,
                discoverAvailable = seerrActive,
                discoverEnabled = state.discoverEnabled,
                onClick = viewModel::onClickExcludeSearchableType,
                onClickDiscover = viewModel::onClickExcludeDiscover,
            ).apply {
                onDismissRequest = { typeDialog = null }
                show()
            }
    }

    // ---- result views ----

    /**
     * One results row (`SearchRowResult`): "Searching…" or the error under the row title, or the cards; nothing
     * without a query or results. One adapter for the row's life, so its scroll and focus survive updates.
     */
    private inner class ResultRow<T : Any>(
        context: Context,
        var rowIndex: Int,
        presenter: CardPresenter<T>,
    ) {
        val view = FrameLayout(context).apply { clipChildren = false }
        private val placeholder = PlaceholderRowView(context)
        private val cards = CardRowView(context)
        private val adapter = CardRowAdapter(presenter)
        private var lastWasCards = false
        private var title = ""
        var callbacks: CardCallbacks<T>
            get() = adapter.callbacks
            set(value) {
                adapter.callbacks = value
            }

        init {
            view.addView(placeholder, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            view.addView(cards, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        private fun style(): CardRowStyle =
            CardRowStyle(
                contentInsetStartPx = view.context.gutterStartPx() + view.context.dp(16),
                contentInsetEndPx = view.context.gutterEndPx() + view.context.dp(16),
            )

        /** @return whether the row shows */
        @Suppress("UNCHECKED_CAST")
        fun bind(
            title: String,
            result: SearchResult,
        ): Boolean {
            this.title = title
            val style = style()

            fun message(
                text: String,
                error: Boolean,
            ): Boolean {
                lastWasCards = false
                cards.visibility = View.GONE
                placeholder.visibility = View.VISIBLE
                placeholder.bind(
                    title,
                    text,
                    pageStyle.sectionAccent,
                    isError = error,
                    focusableRow = false,
                    insetStartPx = style.contentInsetStartPx,
                    insetEndPx = style.contentInsetEndPx,
                )
                return true
            }

            fun showCards(items: List<T?>): Boolean {
                if (items.isEmpty()) return false
                placeholder.visibility = View.GONE
                cards.visibility = View.VISIBLE
                cards.setup(title, pageStyle.sectionAccent, pageStyle, style)
                cards.setCards(adapter, items)
                // A row that comes back after "Searching…" starts at its first card, like a new Compose row
                if (!lastWasCards) cards.strip.selectedPosition = 0
                lastWasCards = true
                return true
            }
            return when (result) {
                is SearchResult.Error -> message(result.ex.localizedMessage ?: "Error occurred during search", true)
                SearchResult.NoQuery -> false.also { lastWasCards = false }
                SearchResult.Searching -> message(getString(R.string.searching), false)
                is SearchResult.Success -> showCards(result.items as List<T?>).also { if (!it) lastWasCards = false }
                is SearchResult.SuccessSeerr -> showCards(result.items as List<T?>).also { if (!it) lastWasCards = false }
            }
        }

        fun restyle() {
            if (cards.visibility == View.VISIBLE) {
                cards.setup(title, pageStyle.sectionAccent, pageStyle, style())
                adapter.notifyDataSetChanged()
            }
        }

        fun focus(list: PageRowsView): Boolean {
            if (cards.visibility != View.VISIBLE || view !in list.rows) return false
            // Focus the row's cards once the list has laid the row out
            list.selectRow(view) { cards.strip.requestFocus() }
            return true
        }
    }

    companion object {
        /** For [SearchScreens] */
        fun newInstance(
            @Suppress("UNUSED_PARAMETER") preferences: UserPreferences,
        ): SearchFragment = SearchFragment()
    }
}

/**
 * A row that, entered from above or below, gives focus back to the child that had it last (else [defaultFocus]):
 * Compose `focusGroup().focusRestorer(field)` on the search bar.
 */
internal open class FocusRestoreRow(
    context: Context,
) : LinearLayout(context) {
    var defaultFocus: View? = null
    private var lastFocused: View? = null

    override fun requestChildFocus(
        child: View?,
        focused: View?,
    ) {
        super.requestChildFocus(child, focused)
        lastFocused = focused
    }

    override fun addFocusables(
        views: ArrayList<View>,
        direction: Int,
        focusableMode: Int,
    ) {
        if (!hasFocus() && (direction == FOCUS_UP || direction == FOCUS_DOWN)) {
            val target = lastFocused?.takeIf { it.isShown && it.isFocusable && isAncestorOf(it) } ?: defaultFocus
            if (target != null && target.isShown) {
                views.add(target)
                return
            }
        }
        super.addFocusables(views, direction, focusableMode)
    }

    private fun isAncestorOf(view: View): Boolean {
        var v: View? = view
        while (v != null) {
            if (v === this) return true
            v = v.parent as? View
        }
        return false
    }
}
