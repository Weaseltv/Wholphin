package com.github.damontecres.wholphin.leanback.home

import com.github.damontecres.wholphin.leanback.widget.card.clipToOwnHeight
import android.annotation.SuppressLint
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.leanback.widget.VerticalGridView
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import coil3.request.crossfade
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.filter.ItemFilterBy
import com.github.damontecres.wholphin.data.model.ApprovedHomeLayout
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.CollectionFolderFilter
import com.github.damontecres.wholphin.data.model.HomeRowViewOptions
import com.github.damontecres.wholphin.leanback.shell.DestinationFragment
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.ApprovedCollectionCardAppearanceView
import com.github.damontecres.wholphin.leanback.theme.PageStyle
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.dpF
import com.github.damontecres.wholphin.leanback.theme.forCollectionCard
import com.github.damontecres.wholphin.leanback.theme.forWatchlistItem
import com.github.damontecres.wholphin.leanback.theme.withAlpha
import com.github.damontecres.wholphin.leanback.widget.card.CardGridCallbacks
import com.github.damontecres.wholphin.leanback.widget.card.CardGridConfig
import com.github.damontecres.wholphin.leanback.widget.card.CardGridView
import com.github.damontecres.wholphin.leanback.widget.card.CardImageScale
import com.github.damontecres.wholphin.leanback.widget.card.CardImages
import com.github.damontecres.wholphin.leanback.widget.card.FavoriteView
import com.github.damontecres.wholphin.leanback.widget.card.GridCardView
import com.github.damontecres.wholphin.leanback.widget.card.GridScrollMode
import com.github.damontecres.wholphin.leanback.widget.card.cardPresenter
import com.github.damontecres.wholphin.leanback.widget.control.AppListItem
import com.github.damontecres.wholphin.leanback.widget.control.CircularProgressView
import com.github.damontecres.wholphin.leanback.widget.control.ErrorView
import com.github.damontecres.wholphin.leanback.widget.control.ListLeading
import com.github.damontecres.wholphin.leanback.widget.control.LoadingPageView
import com.github.damontecres.wholphin.leanback.widget.dialog.ContextMenuHelper
import com.github.damontecres.wholphin.leanback.widget.text.DetailPageLayout
import com.github.damontecres.wholphin.leanback.widget.text.FittedDetailHeaderLayout
import com.github.damontecres.wholphin.leanback.widget.text.HeaderDimens
import com.github.damontecres.wholphin.leanback.widget.text.OverviewTextView
import com.github.damontecres.wholphin.leanback.widget.text.QuickDetailsView
import com.github.damontecres.wholphin.leanback.widget.text.TitleOrLogoView
import com.github.damontecres.wholphin.leanback.widget.text.enabledDisplayToggles
import com.github.damontecres.wholphin.services.StreamingCollections
import com.github.damontecres.wholphin.ui.components.CollectionFolderState
import com.github.damontecres.wholphin.ui.components.CollectionFolderViewActions
import com.github.damontecres.wholphin.ui.components.ViewOptions
import com.github.damontecres.wholphin.ui.components.ViewOptionsType
import com.github.damontecres.wholphin.ui.formatDuration
import com.github.damontecres.wholphin.ui.main.settings.Library
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.roundMinutes
import com.github.damontecres.wholphin.util.DataLoadingState
import com.github.damontecres.wholphin.util.LoadingState
import com.github.damontecres.wholphin.util.RequestPager
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.extensions.ticks

/**
 * `CollectionFolderViewContent` for this area's folder pages (Watchlist, Favorites tabs): the sort / filter / view
 * options / random buttons (hidden once the grid scrolls past its first row), then the items as a grid of posters
 * (with the compact details header when "Show details" is on) or as a list, a spinner while new results load, and
 * the view options panel. A private version of LIBRARY's view (runbooks/no-compose/CONTRACT.md section 1).
 *
 * Focus when results arrive: the filter button while its list is open, the random button after it was used, the grid
 * when there are items, else [focusOnEmpty] or the first header button. BACK in the grid jumps to its first card.
 */
@SuppressLint("ViewConstructor")
class FolderContentView(
    private val fragment: DestinationFragment,
    private val actions: CollectionFolderViewActions,
    private val contextMenu: ContextMenuHelper,
) : FrameLayout(fragment.requireContext()) {
    var sortOptions: List<ItemSortBy> = emptyList()
    var filterOptions: List<ItemFilterBy<*>> = emptyList()
    var defaultViewOptions: ViewOptions = ViewOptions()

    /** The Watchlist's libraries: its cards wear their library's colours (`WatchlistCardStyle`) */
    var watchlistLibraries: List<Library>? = null
        set(value) {
            if (field != value) {
                field = value
                grid?.refresh()
            }
        }

    /** OK on a card */
    var onClickItem: (Int, BaseItem) -> Unit = { _, item -> actions.navigateTo(item.destination()) }

    /** `positionCallback`: every focused card (columns: 1 in a list) */
    var onPosition: ((columns: Int, position: Int) -> Unit)? = null

    /** "Send app logs" on the error page */
    var onSendLogs: () -> Unit = {}

    /** Where focus goes when there are no results (`focusRequesterOnEmpty`) */
    var focusOnEmpty: (() -> View?)? = null

    /** The page's style (section accent, card appearance) */
    var pageStyle: PageStyle = PageStyle()
        set(value) {
            field = value
            grid?.refresh()
        }

    private val neon = AppTheme.isNeon
    private val column =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
    private val header =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
    private val sortButton = SortButton(context)
    private val filterButton = FilterButton(context, fragment.viewLifecycleOwner)
    private val viewOptionsButton = faButton(context, R.string.view_options, R.string.fa_sliders) { showViewOptions() }
    private val randomButton =
        faButton(context, R.string.sort_by_random, R.string.fa_dice) {
            clickedRandom = true
            actions.onClickRandom()
        }
    private val content =
        FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
        }
    private val spinner =
        FrameLayout(context).apply {
            background =
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(withAlpha(AppTheme.palette.background, .25f))
                }
            val pad = context.dp(4)
            setPadding(pad, pad, pad, pad)
            addView(CircularProgressView(context), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            visibility = GONE
        }

    private var state: CollectionFolderState? = null
    private var contentKind = 0
    private var grid: CardGridView? = null
    private var gridPage: DetailPageLayout? = null
    private var detailsHeader: HomeHeaderView? = null
    private var detailsBox: FittedDetailHeaderLayout? = null
    private var list: FolderListView? = null
    private var position = 0
    private var showHeader = true
    private var clickedRandom = false
    private var filterOpen = false
    private var changesJob: Job? = null
    private var viewOptionsDialog: PreferenceOptionsDialog<ViewOptions>? = null
    private var shownItems: List<BaseItem?>? = null

    /** The values every saved Picks row held when it was retired, so collection posters look the same */
    private val collectionAppearance =
        ApprovedHomeLayout.apply(HomeRowViewOptions(cardAppearance = ApprovedCollectionCardAppearanceView)).cardAppearance
    private val streamingAppearance = StreamingCollections.row.viewOptions.cardAppearance

    init {
        clipChildren = false
        clipToPadding = false
        val spacing = context.dp(if (neon) 16 else 8)
        header.addView(sortButton)
        header.addView(filterButton, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = spacing })
        header.addView(viewOptionsButton, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = spacing })
        header.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
        header.addView(randomButton, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = spacing })
        column.addView(header, LinearLayout.LayoutParams(MATCH, WRAP))
        column.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))
        addView(column, LayoutParams(MATCH, MATCH))
        content.addView(
            spinner,
            LayoutParams(context.dp(64), context.dp(64), Gravity.CENTER).apply {
                setMargins(context.dp(16), context.dp(16), context.dp(16), context.dp(16))
            },
        )
        sortButton.onSortChange =
            {
                actions.onSortChange(
                    it,
                    true,
                    state?.filter ?: com.github.damontecres.wholphin.data.model
                        .GetItemsFilter(),
                )
            }
        filterButton.onFilterChange = { actions.onFilterChange(it, true) }
        filterButton.getPossibleValues = { actions.getFilterOptionValues(it) }
        filterButton.onShow = { filterOpen = it }
    }

    /** Show [state] (the ViewModel's `CollectionFolderState`) */
    fun bind(state: CollectionFolderState) {
        val old = this.state
        this.state = state
        bindHeader(state)
        viewOptionsDialog?.takeIf { it.isShowing }?.update(state.viewOptions)
        when (val items = state.items) {
            is DataLoadingState.Error -> {
                if (contentKind != KIND_ERROR) {
                    showContent(KIND_ERROR) { ErrorView(context, items, onSendLogs = onSendLogs) }
                    (focusOnEmpty?.invoke() ?: sortButton.button).requestFocusIfTop()
                }
            }

            DataLoadingState.Loading, DataLoadingState.Pending -> {
                showContent(KIND_LOADING) { LoadingPageView(context) }
            }

            is DataLoadingState.Success -> {
                val listMode = state.viewOptions.type != ViewOptionsType.GRID
                val entered = (contentKind != KIND_GRID && contentKind != KIND_LIST) || ((contentKind == KIND_LIST) != listMode)
                if (listMode) showList(items.data, state) else showGrid(items.data, state)
                if (entered) focusAfterLoad(items.data)
            }
        }
        spinner.showIf(state.backgroundLoading == LoadingState.Loading)
        spinner.bringToFront()
        if (old?.viewOptions?.showBackdrop != state.viewOptions.showBackdrop) updateBackdrop()
    }

    /** BACK: the grid / list jumps to its first card */
    fun onBackPressed(): Boolean = grid?.onBackPressed() ?: list?.onBackPressed() ?: false

    private fun bindHeader(state: CollectionFolderState) {
        val listNotEmpty = (state.items as? DataLoadingState.Success)?.data?.isNotEmpty() == true
        header.showIf(showHeader || state.items !is DataLoadingState.Success)
        val endPadding = context.dp(16 + if (state.sortAndDirection.sort == ItemSortBy.SORT_NAME) 24 else 0)
        header.setPadding(if (neon) 0 else context.dp(16), 0, endPadding, if (neon) context.dp(8) else 0)
        sortButton.showIf(sortOptions.isNotEmpty())
        sortButton.bind(sortOptions, state.sortAndDirection)
        filterButton.showIf(filterOptions.isNotEmpty())
        filterButton.bind(filterOptions, state.filter)
        randomButton.isActionEnabled = listNotEmpty
    }

    private fun showContent(
        kind: Int,
        create: () -> View,
    ) {
        if (contentKind == kind) return
        contentKind = kind
        clearContent()
        content.addView(create(), 0, LayoutParams(MATCH, MATCH))
    }

    private fun clearContent() {
        changesJob?.cancel()
        grid = null
        gridPage = null
        detailsHeader = null
        detailsBox = null
        list = null
        shownItems = null
        (0 until content.childCount).map { content.getChildAt(it) }.filter { it !== spinner }.forEach { content.removeView(it) }
    }

    private fun focusAfterLoad(items: List<BaseItem?>) {
        val target: View? =
            when {
                contextMenu.isShowing -> null
                filterOpen -> filterButton.button
                clickedRandom -> randomButton
                items.isNotEmpty() -> grid?.grid ?: list?.grid
                else -> focusOnEmpty?.invoke() ?: sortButton.button.takeIf { sortOptions.isNotEmpty() } ?: viewOptionsButton
            }
        target?.requestFocusIfTop()
        clickedRandom = false
    }

    private fun View.requestFocusIfTop() {
        if (fragment.isTopScreen) requestFocus()
    }

    private fun onFocused(
        columns: Int,
        index: Int,
    ) {
        position = index
        val newShowHeader = index < columns
        if (newShowHeader != showHeader) {
            showHeader = newShowHeader
            state?.let { header.showIf(showHeader || it.items !is DataLoadingState.Success) }
        }
        onPosition?.invoke(columns, index)
        updateBackdrop()
        bindDetails()
    }

    private fun focusedItem(): BaseItem? = shownItems?.getOrNull(position)

    private fun updateBackdrop() {
        if (state?.viewOptions?.showBackdrop != true) return
        focusedItem()?.let { actions.updateBackdrop(it) }
    }

    private fun bindDetails() {
        detailsHeader?.bind(focusedItem())
        list?.bindDetails(focusedItem())
    }

    // Grid ---------------------------------------------------------------------------------------------------------

    private fun showGrid(
        items: List<BaseItem?>,
        state: CollectionFolderState,
    ) {
        val options = state.viewOptions
        if (contentKind != KIND_GRID) {
            showContent(KIND_GRID) {
                val page = DetailPageLayout(context)
                val pageColumn =
                    LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        clipChildren = false
                        clipToPadding = false
                    }
                val g = CardGridView(context)
                grid = g
                gridPage = page
                pageColumn.addView(g, LinearLayout.LayoutParams(MATCH, 0, 1f))
                page.addView(pageColumn, LayoutParams(MATCH, MATCH))
                page
            }
        }
        val g = grid ?: return
        updateDetailsHeader(options)
        val topPadding = if (neon) 24f else 16f
        val libraries = watchlistLibraries
        g.setCards(
            items = items,
            presenter =
                cardPresenter<BaseItem, GridCardView>(::GridCardView) { card, item, _, width ->
                    val base = if (libraries != null) pageStyle.forWatchlistItem(item, libraries) else pageStyle
                    val style = base.forCollectionCard(item, collectionAppearance, streamingAppearance)
                    card.bind(
                        item,
                        style,
                        width,
                        imageAspectRatio = options.aspectRatio.ratio,
                        imageScale = CardImageScale.from(options.contentScale),
                        imageType = options.imageType.imageType,
                        showTitle = options.showTitles,
                    )
                },
            callbacks =
                CardGridCallbacks(
                    onClick = { index, item ->
                        position = index
                        onClickItem(index, item)
                    },
                    onLongClick = { index, item ->
                        position = index
                        contextMenu.showContextMenu(index, item, g)
                    },
                    onClickPlay = { index, item -> play(index, item) },
                    letterPosition = { actions.positionOfLetter(it) ?: -1 },
                    onPosition = { columns, index -> onFocused(columns, index) },
                ),
            config =
                CardGridConfig(
                    columns = options.columns,
                    spacingDp = options.spacing.toFloat(),
                    topPaddingDp = topPadding,
                    showLetterButtons = state.sortAndDirection.sort == ItemSortBy.SORT_NAME,
                    initialPosition = 0,
                    scrollMode = if (options.showDetails) GridScrollMode.Top(options.spacing.toFloat()) else GridScrollMode.Pivot,
                ),
            pageStyle = pageStyle,
        )
        if (shownItems !== items) {
            shownItems = items
            (items as? RequestPager<*>)?.let { pager ->
                changesJob?.cancel()
                changesJob = g.observeChanges(fragment.viewLifecycleOwner, pager.changes)
            }
        }
        bindDetails()
    }

    /** "Show details": the focused item's compact header above the grid, fitted above its first row */
    private fun updateDetailsHeader(options: ViewOptions) {
        val page = gridPage ?: return
        val pageColumn = page.getChildAt(0) as LinearLayout
        val show = options.showDetails
        if (!show) {
            detailsBox?.let { pageColumn.removeView(it) }
            detailsBox = null
            detailsHeader = null
            return
        }
        if (detailsBox != null) return
        val prefs = fragment.preferences.appPreferences
        val headerView =
            HomeHeaderView(
                context,
                showLogo = prefs.interfacePreferences.showLogos,
                compactLogo = true,
                compactDetails = neon,
                box = HeaderDimens.standard.copy(height = null),
            ).apply {
                pageStyle = this@FolderContentView.pageStyle
                displayToggles = enabledDisplayToggles(prefs)
            }
        val rowHeight =
            CardGridView.rowHeightPx(
                context,
                gridWidthPx = width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels,
                columns = options.columns,
                spacingDp = options.spacing.toFloat(),
                imageAspectRatio = options.aspectRatio.ratio,
                showTitle = options.showTitles,
                topPaddingDp = if (neon) 24f else 16f,
            )
        val box = FittedDetailHeaderLayout(context, reservedHeightDp = 0f, firstRowHeightDp = rowHeight / resources.displayMetrics.density)
        box.addView(headerView, LayoutParams(MATCH, WRAP))
        pageColumn.addView(box, 0, LinearLayout.LayoutParams(MATCH, WRAP))
        detailsBox = box
        detailsHeader = headerView
    }

    private fun play(
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
        actions.navigateTo(destination)
    }

    // List ---------------------------------------------------------------------------------------------------------

    private fun showList(
        items: List<BaseItem?>,
        state: CollectionFolderState,
    ) {
        if (contentKind != KIND_LIST) {
            showContent(KIND_LIST) { FolderListView().also { list = it } }
        }
        val l = list ?: return
        l.bind(items, state.viewOptions)
        if (shownItems !== items) {
            shownItems = items
            (items as? RequestPager<*>)?.let { pager ->
                changesJob?.cancel()
                changesJob =
                    fragment.viewLifecycleOwner.lifecycleScope.launch {
                        fragment.viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                            pager.changes.collect { l.refresh() }
                        }
                    }
            }
        }
        bindDetails()
    }

    /**
     * `CollectionFolderList`: the items as list rows (title, subtitle, runtime, favourite heart; dense rows with
     * "Show details" off a list of the layout), spaced by the view options' spacing, with the focused item's details
     * (logo or title, poster, quick details, overview) in a third of the width on the left when "Show details" is on.
     * BACK jumps to the first row.
     */
    private inner class FolderListView : LinearLayout(context) {
        val grid = VerticalGridView(context)
        private val details = LinearLayout(context)
        private val detailsTitle = TitleOrLogoView(context, fillLogo = true)
        private val detailsImage = ImageView(context)
        private val detailsQuick = QuickDetailsView(context)
        private val detailsOverview = OverviewTextView(context)
        private val adapter = ListAdapter()
        private var options = ViewOptions()

        init {
            orientation = HORIZONTAL
            clipChildren = false
            clipToPadding = false
            grid.clipToOwnHeight()
            setPadding(0, context.dp(8), 0, context.dp(16))
            details.orientation = VERTICAL
            details.gravity = Gravity.CENTER_HORIZONTAL
            val pad = context.dp(8)
            details.setPadding(pad, pad, pad, pad)
            details.addView(detailsTitle, LayoutParams(MATCH, context.dp(HeaderDimens.LOGO_HEIGHT)))
            detailsImage.scaleType = ImageView.ScaleType.FIT_CENTER
            detailsImage.clipToOutline = true
            detailsImage.background = GradientDrawable().apply { cornerRadius = context.dpF(8f) }
            details.addView(detailsImage, LayoutParams(MATCH, 0, 1f).apply { topMargin = pad })
            details.addView(detailsQuick, LayoutParams(WRAP, WRAP).apply { topMargin = pad })
            detailsOverview.maxLines = 5
            details.addView(detailsOverview, LayoutParams(MATCH, WRAP).apply { topMargin = pad })
            addView(
                FractionFrame(context, .33f).apply { addView(details, LayoutParams(MATCH, MATCH)) },
                LayoutParams(WRAP, MATCH).apply {
                    marginEnd =
                        context.dp(8)
                },
            )
            grid.adapter = adapter
            grid.clipToPadding = false
            val edge = context.dp(20)
            grid.setPadding(context.dp(16), edge, context.dp(16), edge)
            grid.windowAlignment = androidx.leanback.widget.BaseGridView.WINDOW_ALIGN_BOTH_EDGE
            grid.focusScrollStrategy = androidx.leanback.widget.BaseGridView.FOCUS_SCROLL_ITEM
            addView(grid, LayoutParams(0, MATCH, 1f))
        }

        fun bind(
            items: List<BaseItem?>,
            options: ViewOptions,
        ) {
            val changed = this.options != options || adapter.items !== items
            this.options = options
            (getChildAt(0) as View).showIf(options.showDetails)
            grid.setItemSpacing(context.dp(options.spacing))
            adapter.items = items
            if (changed) {
                @Suppress("NotifyDataSetChanged")
                adapter.notifyDataSetChanged()
            }
        }

        fun refresh() {
            @Suppress("NotifyDataSetChanged")
            adapter.notifyDataSetChanged()
        }

        fun onBackPressed(): Boolean {
            if (!grid.hasFocus() || grid.selectedPosition <= 0) return false
            grid.setSelectedPositionSmooth(0)
            return true
        }

        fun bindDetails(item: BaseItem?) {
            if (!options.showDetails) return
            detailsTitle.bind(item, true, CardImages.imageUrlService(context))
            val url =
                item?.imageUrlOverride
                    ?: CardImages
                        .imageUrlService(
                            context,
                        ).getItemImageUrl(item, org.jellyfin.sdk.model.api.ImageType.PRIMARY, useSeriesForPrimary = true)
            detailsImage.load(url) { crossfade(300) }
            detailsQuick.showIf(item != null)
            item?.let { detailsQuick.bind(it.ui.quickDetailsText, null) }
            detailsOverview.setText(item?.data?.overview ?: "")
        }

        private inner class ListAdapter : RecyclerView.Adapter<ListHolder>() {
            var items: List<BaseItem?> = emptyList()

            override fun getItemCount(): Int = items.size

            override fun getItemViewType(position: Int): Int = if (options.type == ViewOptionsType.DENSE_LIST) 1 else 0

            override fun onCreateViewHolder(
                parent: ViewGroup,
                viewType: Int,
            ): ListHolder = ListHolder(AppListItem(parent.context, dense = viewType == 1))

            override fun onBindViewHolder(
                holder: ListHolder,
                position: Int,
            ) = holder.bind(items.getOrNull(position))
        }

        private inner class ListHolder(
            val row: AppListItem,
        ) : RecyclerView.ViewHolder(row) {
            private val heart = FavoriteView(row.context, 16f)

            init {
                row.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
                row.setOnClickListener {
                    val index = bindingAdapterPosition
                    adapter.items.getOrNull(index)?.let { onClickItem(index, it) }
                }
                row.setOnLongClickListener {
                    val index = bindingAdapterPosition
                    adapter.items.getOrNull(index)?.let { contextMenu.showContextMenu(index, it, row) }
                    true
                }
                row.setOnFocusChangeListener { _, focused -> if (focused) onFocused(1, bindingAdapterPosition) }
            }

            fun bind(item: BaseItem?) {
                row.setLeading(if (item?.favorite == true) ListLeading.Custom(heart) else null)
                row.setHeadline(item?.title.orEmpty())
                row.setSupporting(item?.subtitle)
                row.setTrailing(
                    item
                        ?.data
                        ?.runTimeTicks
                        ?.ticks
                        ?.roundMinutes
                        ?.let { row.resources.formatDuration(it) }
                        .orEmpty(),
                )
            }
        }
    }

    // View options ---------------------------------------------------------------------------------------------------

    private fun showViewOptions() {
        val st = state ?: return
        val options =
            when (st.viewOptions.type) {
                ViewOptionsType.GRID -> ViewOptions.GRID_OPTIONS
                ViewOptionsType.LIST, ViewOptionsType.DENSE_LIST -> ViewOptions.LIST_OPTIONS
            }
        viewOptionsDialog =
            PreferenceOptionsDialog(
                context = context,
                value = st.viewOptions,
                prefs = options,
                onChange = actions::saveViewOptions,
                onReset = { actions.saveViewOptions(defaultViewOptions) },
                accent = pageStyle.sectionAccent,
            ).also { dialog ->
                dialog.onDismissRequest = {
                    viewOptionsDialog = null
                    state?.let { actions.saveViewOptions(it.viewOptions) }
                }
                dialog.setOnDismissListener { viewOptionsDialog = null }
                dialog.show()
            }
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val KIND_LOADING = 1
        const val KIND_ERROR = 2
        const val KIND_GRID = 3
        const val KIND_LIST = 4
    }
}
