package com.github.damontecres.wholphin.leanback.library

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Space
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.filter.ItemFilterBy
import com.github.damontecres.wholphin.data.model.ApprovedHomeLayout
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.CollectionFolderFilter
import com.github.damontecres.wholphin.data.model.HomeCardAppearance
import com.github.damontecres.wholphin.data.model.HomeRowViewOptions
import com.github.damontecres.wholphin.leanback.shell.DestinationFragment
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.ApprovedCollectionCardAppearanceView
import com.github.damontecres.wholphin.leanback.theme.PageStyle
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.widget.control.AppButton
import com.github.damontecres.wholphin.leanback.widget.control.ErrorView
import com.github.damontecres.wholphin.leanback.widget.control.LoadingPageView
import com.github.damontecres.wholphin.leanback.widget.text.enabledDisplayToggles
import com.github.damontecres.wholphin.services.StreamingCollections
import com.github.damontecres.wholphin.ui.components.CollectionFolderState
import com.github.damontecres.wholphin.ui.components.CollectionFolderViewActions
import com.github.damontecres.wholphin.ui.components.ErrorViewModel
import com.github.damontecres.wholphin.ui.components.ViewOptions
import com.github.damontecres.wholphin.ui.components.ViewOptionsType
import com.github.damontecres.wholphin.ui.main.settings.Library
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.util.DataLoadingState
import com.github.damontecres.wholphin.util.LoadingState
import com.github.damontecres.wholphin.util.RequestPager
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.serializer.toUUIDOrNull

/** The fixed parameters of a [CollectionFolderContentView] (`CollectionFolderView` / `CollectionFolderViewContent`) */
data class CollectionFolderConfig(
    val itemId: String,
    val initialFilter: CollectionFolderFilter,
    val recursive: Boolean,
    val sortOptions: List<ItemSortBy>,
    val filterOptions: List<ItemFilterBy<*>>,
    val defaultViewOptions: ViewOptions,
    /** The Collections page's card and heading options: every retired Picks row's values */
    val collectionHomeOptions: HomeRowViewOptions =
        ApprovedHomeLayout.apply(HomeRowViewOptions(useSeries = false, cardAppearance = ApprovedCollectionCardAppearanceView)),
    /** Watchlist cards take their library's colours (`WatchlistCardStyle`) */
    val watchlistLibraries: List<Library>? = null,
)

/** What a [CollectionFolderContentView] does on its items (`GridClickActions` and the page around it) */
class CollectionFolderActions(
    val onClickItem: (index: Int, item: BaseItem) -> Unit,
    /** Long press; null opens the page's context menu ([showContextMenu]) */
    val onLongClickItem: ((index: Int, item: BaseItem, view: View) -> Unit)? = null,
    val showContextMenu: (index: Int, item: BaseItem, view: View) -> Unit,
    val isContextMenuShowing: () -> Boolean,
    /** The play key on a card; null plays it (a photo album starts its slideshow) */
    val onClickPlayRemoteButton: ((index: Int, item: BaseItem) -> Unit)? = null,
    /** `positionCallback`: every focused card */
    val positionCallback: ((columns: Int, position: Int) -> Unit)? = null,
    /** `focusRequesterOnEmpty`: focus this (the tab row) instead of the header when there is nothing to focus */
    val focusOnEmpty: (() -> Boolean)? = null,
)

/**
 * A library folder's content (`CollectionFolderViewContent`): loading, error, or the header (the page title when
 * [showTitle], the toolbar: sort, filter, View options, and Random at the end; the Collections page's group buttons)
 * over the grid or list of items, with a spinner while it refreshes in the background.
 *
 * Behaviour kept from the old page:
 * - The header collapses while a card below the first row is focused and comes back on the first row (always shown
 *   while the items load).
 * - When the items arrive: focus goes to the filter button if its menu is open, to Random or the chosen group button
 *   after those were used, else to the grid, or (nothing to show / error) to [CollectionFolderActions.focusOnEmpty] or
 *   the first header button. Nothing moves while a context menu is open.
 * - The focused item feeds the backdrop when the view options show it, and the details header.
 * - View options open the side panel; each change is saved at once, and again when it closes.
 */
@SuppressLint("ViewConstructor")
class CollectionFolderContentView(
    context: Context,
    private val fragment: DestinationFragment,
    private val config: CollectionFolderConfig,
    private val viewActions: CollectionFolderViewActions,
    private val actions: CollectionFolderActions,
    private val errorViewModel: ErrorViewModel,
) : FrameLayout(context) {
    private val neon = AppTheme.isNeon
    private var state: CollectionFolderState? = null
    private var position = 0
    private var positionSet = false

    /** The page title (hidden by tabbed pages and while scrolled down) */
    var showTitle: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            state?.let { bindHeader(it) }
        }

    var pageStyle: PageStyle = fragment.pageStyle
        set(value) {
            field = value
            refreshCards()
        }

    var streamingAppearance: HomeCardAppearance = StreamingCollections.row.viewOptions.cardAppearance
        set(value) {
            if (field != value) {
                field = value
                refreshCards()
            }
        }

    // Flags of the old page (rememberSaveable)
    private var clickedRandom = false
    private var clickedGroup = false
    private var filterDropdownShowing = false
    private var showHeader = true

    // Item states
    private var shownItemKind: Int = -1
    private var shownItemsKind: Int = -1
    private var shownItems: List<BaseItem?>? = null
    private var shownType: ViewOptionsType? = null
    private var focusedItem: BaseItem? = null
    private var changesJob: Job? = null

    // Views
    private val successColumn = LinearLayout(context)
    private val headerFrame = CollapsibleFrame(context)
    private val headerColumn = LinearLayout(context)
    private val titleView = LibraryGridTitleView(context)
    private val toolbar = LinearLayout(context)
    private val toolbarStart = LinearLayout(context)
    private val toolbarEnd = LinearLayout(context)
    private val groupsColumn = LinearLayout(context)
    private val contentBox = FrameLayout(context)
    private val contentHolder = FrameLayout(context)
    private val spinner = BackgroundSpinnerView(context)
    private var gridView: LibraryGridView? = null
    private var listView: LibraryListView? = null
    private var pageOverlay: View? = null

    private var sortButton: AppButton? = null
    private var sortMenu: SortMenuController<ItemSortBy, com.github.damontecres.wholphin.ui.data.SortAndDirection>? = null
    private var filterButton: FilterButtonView? = null
    private var filterMenu: FilterMenuController<com.github.damontecres.wholphin.data.model.GetItemsFilter, ItemFilterBy<*>>? = null
    private val viewOptionsButton: AppButton
    private val randomButton: AppButton
    private var groupButtons: List<Pair<String?, QuickListButtonView>> = emptyList()
    private var shownGroups: List<String>? = null
    private var viewOptionsDialog: LibraryViewOptionsDialog? = null

    init {
        clipChildren = false
        clipToPadding = false
        successColumn.orientation = LinearLayout.VERTICAL
        successColumn.clipChildren = false
        successColumn.clipToPadding = false
        headerColumn.orientation = LinearLayout.VERTICAL
        headerColumn.clipChildren = false
        headerColumn.clipToPadding = false
        headerColumn.addView(titleView, LinearLayout.LayoutParams(MATCH, WRAP))

        val spacing = context.dp(if (neon) 16 else 8)
        toolbar.orientation = LinearLayout.HORIZONTAL
        toolbar.gravity = Gravity.CENTER_VERTICAL
        toolbar.clipChildren = false
        toolbar.clipToPadding = false
        toolbarStart.orientation = LinearLayout.HORIZONTAL
        toolbarStart.gravity = Gravity.CENTER_VERTICAL
        toolbarStart.clipChildren = false
        toolbarEnd.orientation = LinearLayout.HORIZONTAL
        toolbarEnd.gravity = Gravity.CENTER_VERTICAL
        toolbarEnd.clipChildren = false

        fun addButton(
            parent: LinearLayout,
            view: View,
        ) {
            parent.addView(
                view,
                LinearLayout.LayoutParams(WRAP, WRAP).apply { if (parent.childCount > 0) marginStart = spacing },
            )
        }
        if (config.sortOptions.isNotEmpty()) {
            val button = AppButton.text(context, "") {}
            sortButton = button
            addButton(toolbarStart, button)
        }
        if (config.filterOptions.isNotEmpty()) {
            val button = FilterButtonView(context, allCaps = neon)
            filterButton = button
            addButton(toolbarStart, button)
        }
        viewOptionsButton = expandableFaButton(context, R.string.view_options, R.string.fa_sliders, allCaps = neon)
        viewOptionsButton.setOnClickListener { showViewOptions() }
        addButton(toolbarStart, viewOptionsButton)
        randomButton = expandableFaButton(context, R.string.sort_by_random, R.string.fa_dice, allCaps = neon)
        randomButton.setOnClickListener {
            clickedRandom = true
            viewActions.onClickRandom()
        }
        addButton(toolbarEnd, randomButton)
        toolbar.addView(toolbarStart, LinearLayout.LayoutParams(WRAP, WRAP))
        toolbar.addView(Space(context), LinearLayout.LayoutParams(0, 0, 1f))
        toolbar.addView(toolbarEnd, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = spacing })
        headerColumn.addView(toolbar, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.dp(8) })

        groupsColumn.orientation = LinearLayout.VERTICAL
        groupsColumn.clipChildren = false
        groupsColumn.clipToPadding = false
        groupsColumn.setPadding(0, 0, 0, context.dp(8))
        groupsColumn.visibility = GONE
        headerColumn.addView(groupsColumn, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = context.dp(8) })

        headerFrame.addView(headerColumn, FrameLayout.LayoutParams(MATCH, WRAP))
        successColumn.addView(headerFrame, LinearLayout.LayoutParams(MATCH, WRAP))

        contentBox.clipChildren = false
        contentBox.clipToPadding = false
        contentHolder.clipChildren = false
        contentHolder.clipToPadding = false
        contentBox.addView(contentHolder, FrameLayout.LayoutParams(MATCH, MATCH))
        contentBox.addView(
            spinner,
            FrameLayout.LayoutParams(context.dp(64), context.dp(64), Gravity.CENTER).apply {
                val m = context.dp(16)
                setMargins(m, m, m, m)
            },
        )
        successColumn.addView(contentBox, LinearLayout.LayoutParams(MATCH, 0, 1f))
        successColumn.visibility = GONE
        addView(successColumn, LayoutParams(MATCH, MATCH))
    }

    /** Show [state]; [savedPosition] (the ViewModel's position) is where the grid starts the first time */
    fun bind(
        state: CollectionFolderState,
        savedPosition: Int,
    ) {
        if (!positionSet) {
            position = savedPosition
            positionSet = true
        }
        this.state = state
        when (val st = state.item) {
            DataLoadingState.Loading, DataLoadingState.Pending -> {
                if (shownItemKind != KIND_LOADING) {
                    shownItemKind = KIND_LOADING
                    showOverlay(LoadingPageView(context))
                }
            }

            is DataLoadingState.Error -> {
                if (shownItemKind != KIND_ERROR) {
                    shownItemKind = KIND_ERROR
                    showOverlay(ErrorView(context, st, errorViewModel::sendLogs))
                }
            }

            is DataLoadingState.Success -> {
                val entered = shownItemKind != KIND_SUCCESS
                shownItemKind = KIND_SUCCESS
                if (entered) successColumn.visibility = VISIBLE
                bindHeader(state)
                bindItems(state, st.data)
                // Only now, with the new content holding focus, remove the page's loading picture (removing a focused
                // view first would drop focus out of the page)
                if (entered) showOverlay(null)
            }
        }
        viewOptionsDialog?.takeIf { it.isShowing }?.update(state.viewOptions)
    }

    private fun showOverlay(view: View?) {
        val old = pageOverlay
        pageOverlay = view
        if (view != null) {
            addView(view, LayoutParams(MATCH, MATCH))
            successColumn.visibility = GONE
        }
        old?.let { removeView(it) }
    }

    private fun isCollectionsLayout(item: BaseItem?): Boolean =
        neon && (
            item?.data?.collectionType == CollectionType.BOXSETS ||
                config.initialFilter.filter.includeItemTypes == listOf(BaseItemKind.BOX_SET)
        )

    private fun bindHeader(state: CollectionFolderState) {
        val item = (state.item as? DataLoadingState.Success)?.data
        val collectionsLayout = isCollectionsLayout(item)
        val title =
            config.initialFilter.nameOverride
                ?: item?.name
                ?: item?.data?.collectionType?.name
                ?: context.getString(R.string.collection)
        val collectionsOptions = config.collectionHomeOptions.takeIf { collectionsLayout }
        titleView.visibility = if (showTitle) VISIBLE else GONE
        if (showTitle) titleView.bind(title, pageStyle.sectionAccent, collectionsOptions = collectionsOptions)

        val sortByName = state.sortAndDirection.sort == ItemSortBy.SORT_NAME
        toolbar.setPaddingRelative(
            if (neon) 0 else context.dp(16),
            if (collectionsOptions != null) context.dp(4) else 0,
            context.dp(16 + if (sortByName) 24 else 0),
            if (neon) context.dp(8) else 0,
        )
        val scope = fragment.viewLifecycleOwner.lifecycleScope
        sortButton?.let { button ->
            val menu =
                sortMenu ?: itemSortMenu(button, config.sortOptions, state.sortAndDirection, neon) {
                    viewActions.onSortChange(it, config.recursive, currentFilter())
                }.also { sortMenu = it }
            menu.update(state.sortAndDirection)
        }
        filterButton?.let { button ->
            val menu =
                filterMenu ?: itemFilterMenu(
                    button = button,
                    filterOptions = config.filterOptions,
                    scope = scope,
                    initial = state.filter,
                    getPossibleValues = { viewActions.getFilterOptionValues(it) },
                    onFilterChange = { viewActions.onFilterChange(it, config.recursive) },
                    onShow = { filterDropdownShowing = it },
                ).also { filterMenu = it }
            menu.update(state.filter)
        }
        val items = (state.items as? DataLoadingState.Success)?.data
        randomButton.isActionEnabled = items?.isNotEmpty() == true
        bindGroups(if (collectionsLayout) state.collectionGroups else emptyList(), state.collectionGroup)
        headerFrame.setExpanded(showHeader || state.items !is DataLoadingState.Success)
    }

    private fun currentFilter() = state?.filter ?: config.initialFilter.filter

    /**
     * The Collections page's group shortcuts on two rows, ALL first: 28dp quick-list buttons, 8dp apart, 11sp, grey
     * outlines at rest; the accent shows on focus and on the group that is showing.
     */
    private fun bindGroups(
        groups: List<String>,
        selected: String?,
    ) {
        if (groups != shownGroups) {
            // A rebuilt row keeps focus on the same group's button
            val focusedGroup = groupButtons.firstOrNull { it.second.isFocused }
            shownGroups = groups
            groupsColumn.removeAllViews()
            if (groups.isEmpty()) {
                groupButtons = emptyList()
                groupsColumn.visibility = GONE
            } else {
                groupsColumn.visibility = VISIBLE
                val buttons: List<String?> = listOf(null) + groups
                val rows = buttons.chunked((buttons.size + 1) / 2)
                val all = context.getString(R.string.streaming_all).uppercase()
                val created = mutableListOf<Pair<String?, QuickListButtonView>>()
                rows.forEachIndexed { rowIndex, row ->
                    val line =
                        LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            clipChildren = false
                            clipToPadding = false
                        }
                    row.forEach { group ->
                        val button =
                            QuickListButtonView(context, heightDp = 28f, horizontalPaddingDp = 8f, fontSizeSp = 11f).apply {
                                setLabel(group?.uppercase() ?: all)
                                setOnClickListener {
                                    clickedGroup = true
                                    viewActions.onCollectionGroupChange(group)
                                }
                            }
                        created += group to button
                        line.addView(
                            button,
                            LinearLayout.LayoutParams(WRAP, WRAP).apply { if (line.childCount > 0) marginStart = context.dp(8) },
                        )
                    }
                    groupsColumn.addView(
                        line,
                        LinearLayout.LayoutParams(WRAP, WRAP).apply { if (rowIndex > 0) topMargin = context.dp(8) },
                    )
                }
                groupButtons = created
                if (focusedGroup != null) groupButtons.firstOrNull { it.first == focusedGroup.first }?.second?.requestFocus()
            }
        }
        groupButtons.forEach { (group, button) -> button.selectedList = group == selected }
    }

    private fun bindItems(
        state: CollectionFolderState,
        item: BaseItem?,
    ) {
        spinner.setShown(state.items is DataLoadingState.Success && state.backgroundLoading == LoadingState.Loading)
        when (val items = state.items) {
            DataLoadingState.Loading, DataLoadingState.Pending -> {
                if (shownItemsKind != KIND_LOADING) {
                    shownItemsKind = KIND_LOADING
                    replaceContent(LoadingPageView(context))
                }
            }

            is DataLoadingState.Error -> {
                if (shownItemsKind != KIND_ERROR) {
                    shownItemsKind = KIND_ERROR
                    replaceContent(ErrorView(context, items, errorViewModel::sendLogs))
                    takeFocus(actions.focusOnEmpty?.let { { it() } } ?: { focusHeader() })
                }
            }

            is DataLoadingState.Success -> {
                val entered = shownItemsKind != KIND_SUCCESS
                shownItemsKind = KIND_SUCCESS
                showItems(state, item, items.data, entered)
                if (entered) {
                    val target: (() -> Boolean)? =
                        when {
                            actions.isContextMenuShowing() -> null
                            filterDropdownShowing -> ({ filterButton?.requestFocus() == true })
                            clickedRandom -> ({ randomButton.requestFocus() })
                            clickedGroup -> ({ groupButtons.firstOrNull { it.second.selectedList }?.second?.requestFocus() == true })
                            items.data.isNotEmpty() -> ({ focusItems() })
                            else -> actions.focusOnEmpty ?: { focusHeader() }
                        }
                    target?.let(::takeFocus)
                    clickedRandom = false
                    clickedGroup = false
                }
                removeStaleContent()
            }
        }
    }

    /** One focus request on the right view, only while this is the visible top page (the old LaunchedEffect) */
    private fun takeFocus(request: () -> Boolean) {
        if (fragment.isTopScreen && fragment.view?.isShown == true) request()
    }

    private fun focusHeader(): Boolean {
        if (!headerFrame.expanded) headerFrame.setExpanded(true, animate = false)
        return (0 until toolbarStart.childCount).any { toolbarStart.getChildAt(it).requestFocus() }
    }

    private fun focusItems(): Boolean = gridView?.focusGrid() ?: listView?.focusList() ?: false

    /** Show [view] in the content area; it is added before the old content goes, so focus never leaves the page */
    private fun replaceContent(view: View) {
        changesJob?.cancel()
        val old = (0 until contentHolder.childCount).map { contentHolder.getChildAt(it) }
        gridView = null
        listView = null
        shownItems = null
        shownType = null
        contentHolder.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
        old.forEach { contentHolder.removeView(it) }
    }

    private val staleContent = mutableListOf<View>()

    private fun removeStaleContent() {
        staleContent.forEach { contentHolder.removeView(it) }
        staleContent.clear()
    }

    private fun cardLook(item: BaseItem?): LibraryCardLook =
        LibraryCardLook(
            pageStyle = pageStyle,
            collectionAppearance = config.collectionHomeOptions.cardAppearance,
            streamingAppearance = streamingAppearance,
            watchlistLibraries = config.watchlistLibraries,
            hideTypeBadge = neon && item?.data?.collectionType in listOf(CollectionType.MOVIES, CollectionType.TVSHOWS),
        )

    private fun showItems(
        state: CollectionFolderState,
        item: BaseItem?,
        items: List<BaseItem?>,
        entered: Boolean,
    ) {
        val type = state.viewOptions.type
        val collectionType = item?.data?.collectionType
        val sortByName = state.sortAndDirection.sort == ItemSortBy.SORT_NAME
        val callbacks = gridCallbacks(state)
        val owner = fragment.viewLifecycleOwner
        val prefs = fragment.preferences.appPreferences
        if (type != shownType || (gridView == null && listView == null)) {
            changesJob?.cancel()
            // The old content (loading picture, the other layout) goes once the new one is in place and focused
            staleContent += (0 until contentHolder.childCount).map { contentHolder.getChildAt(it) }
            gridView = null
            listView = null
            if (type == ViewOptionsType.GRID) {
                gridView = LibraryGridView(context).also { contentHolder.addView(it, FrameLayout.LayoutParams(MATCH, MATCH)) }
            } else {
                listView = LibraryListView(context).also { contentHolder.addView(it, FrameLayout.LayoutParams(MATCH, MATCH)) }
            }
            shownType = type
            shownItems = null
        }
        val viewOptions = if (isCollectionsLayout(item)) state.viewOptions.copy(showTitles = false) else state.viewOptions
        gridView?.setItems(
            owner = owner,
            items = items,
            viewOptions = viewOptions,
            sortByName = sortByName,
            look = cardLook(item),
            initialPosition = position,
            callbacks = callbacks,
            showLogo = prefs.interfacePreferences.showLogos,
            toggles = enabledDisplayToggles(prefs),
        )
        listView?.setItems(
            owner = owner,
            items = items,
            viewOptions = state.viewOptions,
            collectionType = collectionType,
            sortByName = sortByName,
            accent = pageStyle.sectionAccent,
            initialPosition = position,
            callbacks = callbacks,
        )
        if (items !== shownItems) {
            shownItems = items
            changesJob?.cancel()
            changesJob =
                (items as? RequestPager<*>)?.let { pager ->
                    owner.lifecycleScope.launch {
                        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                            pager.changes.collect { updateFocusedItem() }
                        }
                    }
                }
        }
        updateFocusedItem()
    }

    private fun gridCallbacks(state: CollectionFolderState): LibraryGridCallbacks =
        LibraryGridCallbacks(
            onClickItem = { index, item ->
                position = index
                actions.onClickItem(index, item)
            },
            onLongClickItem = { index, item, view ->
                position = index
                val long = actions.onLongClickItem
                if (long != null) long(index, item, view) else actions.showContextMenu(index, item, view)
            },
            onClickPlay = { index, item ->
                val custom = actions.onClickPlayRemoteButton
                if (custom != null) custom(index, item) else playItem(index, item)
            },
            letterPosition = { viewActions.positionOfLetter(it) ?: -1 },
            onPosition = { columns, pos ->
                val header = pos < columns
                if (header != showHeader) {
                    showHeader = header
                    headerFrame.setExpanded(showHeader || this.state?.items !is DataLoadingState.Success)
                }
                position = pos
                actions.positionCallback?.invoke(columns, pos)
                updateFocusedItem()
            },
        )

    /** `onClickPlayRemoteButton`'s default: a photo album starts its slideshow, anything else plays */
    private fun playItem(
        index: Int,
        item: BaseItem,
    ) {
        val st = state ?: return
        val destination =
            if (item.type == BaseItemKind.PHOTO_ALBUM) {
                Destination.Slideshow(
                    parentId = item.id,
                    index = index,
                    filter = CollectionFolderFilter(filter = st.filter),
                    sortAndDirection = st.sortAndDirection,
                    recursive = true,
                    startSlideshow = true,
                )
            } else {
                Destination.Playback(item)
            }
        viewActions.navigateTo(destination)
    }

    /** `onClickPlayAll`'s default (kept for callers; the old header showed no play-all button) */
    fun playAll(shuffle: Boolean) {
        val st = state ?: return
        val id = config.itemId.toUUIDOrNull() ?: return
        val destination =
            if ((st.item as? DataLoadingState.Success)?.data?.type == BaseItemKind.PHOTO_ALBUM) {
                Destination.Slideshow(
                    parentId = id,
                    index = 0,
                    filter = CollectionFolderFilter(filter = st.filter),
                    sortAndDirection = st.sortAndDirection,
                    recursive = true,
                    startSlideshow = true,
                )
            } else {
                Destination.PlaybackList(
                    itemId = id,
                    startIndex = 0,
                    shuffle = shuffle,
                    recursive = config.recursive,
                    sortAndDirection = st.sortAndDirection,
                    filter = st.filter,
                )
            }
        viewActions.navigateTo(destination)
    }

    private fun updateFocusedItem() {
        val items = shownItems ?: return
        val item = items.getOrNull(position)
        if (item == focusedItem) return
        focusedItem = item
        gridView?.setFocusedItem(item)
        if (state?.viewOptions?.showBackdrop == true && item != null) viewActions.updateBackdrop(item)
    }

    private fun refreshCards() {
        val st = state ?: return
        if (st.items is DataLoadingState.Success) bindItems(st, (st.item as? DataLoadingState.Success)?.data)
    }

    private fun showViewOptions() {
        val st = state ?: return
        val dialog =
            LibraryViewOptionsDialog(
                context = context,
                viewOptions = st.viewOptions,
                defaultViewOptions = config.defaultViewOptions,
                accent = pageStyle.sectionAccent,
                onViewOptionsChange = viewActions::saveViewOptions,
                onDismiss = {
                    viewOptionsDialog = null
                    state?.let { viewActions.saveViewOptions(it.viewOptions) }
                },
            )
        viewOptionsDialog = dialog
        dialog.show()
    }

    /** The page was shown again (BACK from a later page): the old page's one-shot focus flags start over */
    fun onPageShown() {
        clickedRandom = false
        clickedGroup = false
    }

    /** BACK: the grid / list jumps to its first item first */
    fun onBackPressed(): Boolean = gridView?.onBackPressed() ?: listView?.onBackPressed() ?: false

    /** Focus the items (grid or list), else the header */
    fun focusContent(): Boolean = focusItems() || focusHeader()

    override fun onDetachedFromWindow() {
        changesJob?.cancel()
        viewOptionsDialog?.dismiss()
        viewOptionsDialog = null
        super.onDetachedFromWindow()
    }

    private companion object {
        const val KIND_LOADING = 0
        const val KIND_ERROR = 1
        const val KIND_SUCCESS = 2
    }
}
