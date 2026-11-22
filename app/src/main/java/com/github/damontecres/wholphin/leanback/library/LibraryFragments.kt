package com.github.damontecres.wholphin.leanback.library

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.github.damontecres.wholphin.preferences.AppThemeColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.filter.DefaultFilterOptions
import com.github.damontecres.wholphin.data.filter.DefaultTvFilterOptions
import com.github.damontecres.wholphin.data.filter.ItemFilterBy
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.CollectionFolderFilter
import com.github.damontecres.wholphin.data.model.GetItemsFilter
import com.github.damontecres.wholphin.data.model.HomeRowViewOptions
import com.github.damontecres.wholphin.leanback.shell.DestinationFragment
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.PageStyle
import com.github.damontecres.wholphin.leanback.widget.card.CardGridCallbacks
import com.github.damontecres.wholphin.leanback.widget.card.CardGridConfig
import com.github.damontecres.wholphin.leanback.widget.card.CardGridView
import com.github.damontecres.wholphin.leanback.widget.card.GridCardView
import com.github.damontecres.wholphin.leanback.widget.card.cardPresenter
import com.github.damontecres.wholphin.leanback.widget.control.ErrorView
import com.github.damontecres.wholphin.leanback.widget.control.LoadingPageView
import com.github.damontecres.wholphin.leanback.widget.dialog.ContextMenuHelper
import com.github.damontecres.wholphin.leanback.widget.text.HeaderDimens
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.StreamingCollections
import com.github.damontecres.wholphin.ui.components.CollectionFolderViewModel
import com.github.damontecres.wholphin.ui.components.ErrorViewModel
import com.github.damontecres.wholphin.ui.components.GenreViewModel
import com.github.damontecres.wholphin.ui.components.ItemGridViewModel
import com.github.damontecres.wholphin.ui.components.RecommendedViewModel
import com.github.damontecres.wholphin.ui.components.StudioViewModel
import com.github.damontecres.wholphin.ui.components.TabViewModel
import com.github.damontecres.wholphin.ui.components.ViewOptionsPoster
import com.github.damontecres.wholphin.ui.components.recommendedMovieRows
import com.github.damontecres.wholphin.ui.components.recommendedTvShowRows
import com.github.damontecres.wholphin.ui.data.MovieSortOptions
import com.github.damontecres.wholphin.ui.data.SeriesSortOptions
import com.github.damontecres.wholphin.ui.data.VideoSortOptions
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.toServerString
import com.github.damontecres.wholphin.util.DataLoadingState
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.withCreationCallback
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ItemSortBy
import java.util.UUID
import javax.inject.Inject

/**
 * Base of the library pages: the injected [NavigationManager], the error ViewModel, keyed assisted ViewModels (the old
 * `hiltViewModel(key = …)`) and the page's visibility (shown / hidden behind a later page, or the app paused).
 */
abstract class LibraryFragment : DestinationFragment() {
    @Inject
    lateinit var navigationManager: NavigationManager

    protected val errorViewModel: ErrorViewModel by viewModels()

    private var visibleNow = false
    private var pageRootView: FrameLayout? = null
    private val viewJobs = mutableListOf<Job>()
    private var builtTheme: AppThemeColors? = null
    private var themeDirty = false

    /** Build the page's views (called again when the app theme changes, with the ViewModels kept) */
    protected abstract fun createContent(): View

    /** The page's style changed (side menu, Home settings) without a theme change */
    protected open fun onStyleChanged(style: PageStyle) {}

    /** Collect while the page's current views live (cancelled when they are rebuilt or destroyed) */
    protected fun <T> Flow<T>.collectInView(block: suspend (T) -> Unit) {
        viewJobs += collectWhileStarted(block)
    }

    final override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View =
        FrameLayout(requireContext())
            .apply {
                // The page container never clips the cards' focus glow
                clipChildren = false
                clipToPadding = false
            }.also { pageRootView = it }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        buildContent()
    }

    private fun buildContent() {
        val root = pageRootView ?: return
        viewJobs.forEach { it.cancel() }
        viewJobs.clear()
        root.removeAllViews()
        builtTheme = AppTheme.theme
        themeDirty = false
        root.addView(createContent(), FrameLayout.LayoutParams(MATCH, MATCH))
    }

    /**
     * A new theme rebuilds the views (they hold its colours), at once while the page shows, else when it shows again;
     * other style changes rebind the cards.
     */
    final override fun onPageStyleChanged(style: PageStyle) {
        if (AppTheme.theme != builtTheme) {
            if (isTopScreen && visibleNow) buildContent() else themeDirty = true
        } else {
            onStyleChanged(style)
        }
    }

    override fun onDestroyView() {
        viewJobs.forEach { it.cancel() }
        viewJobs.clear()
        pageRootView = null
        super.onDestroyView()
    }

    /** An assisted ViewModel kept under [key] in this page's store (several of one class on a tabbed page) */
    protected inline fun <reified VM : ViewModel, reified F : Any> keyedViewModel(
        key: String,
        crossinline create: (F) -> VM,
    ): VM {
        val extras = defaultViewModelCreationExtras.withCreationCallback<F> { create(it) }
        return ViewModelProvider(viewModelStore, defaultViewModelProviderFactory, extras)[key, VM::class.java]
    }

    override fun onResume() {
        super.onResume()
        if (!isHidden) setPageVisible(true)
    }

    override fun onPause() {
        setPageVisible(false)
        super.onPause()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        setPageVisible(!hidden && isResumed)
    }

    private fun setPageVisible(visible: Boolean) {
        if (visible == visibleNow) return
        visibleNow = visible
        if (visible && themeDirty) buildContent()
        if (visible) onPageVisible() else onPageHidden()
    }

    /** `LifecycleResumeEffect`: the page is showing (first time, back from a later page, app resumed) */
    protected open fun onPageVisible() {}

    /** The page went behind a later page or the app paused */
    protected open fun onPageHidden() {}

    /** The streaming collections' card appearance from the Home settings, as the old page computed it */
    protected fun streamingAppearanceFrom(viewModel: CollectionFolderViewModel) =
        viewModel.homeSettingsService.currentSettings.value.rows
            .firstOrNull { StreamingCollections.isStreamingRow(it.config) }
            ?.config
            ?.viewOptions
            ?.cardAppearance
            ?: StreamingCollections.row.viewOptions.cardAppearance
}

/**
 * A library folder shown as one grid (Collections, playlists, photos and home videos, folders, music videos, books,
 * genre and studio pages): the page title, the toolbar and the grid or list ([CollectionFolderContentView]).
 * The title and toolbar collapse while scrolled past the first row.
 */
@AndroidEntryPoint
class CollectionFolderFragment : LibraryFragment() {
    private val spec: FolderSpec by lazy { LibraryScreens.folderSpecFor(destination) }

    private val viewModel: CollectionFolderViewModel by viewModels(
        extrasProducer = {
            defaultViewModelCreationExtras.withCreationCallback<CollectionFolderViewModel.Factory> {
                it.create(
                    itemId = spec.itemId.toServerString(),
                    initialSortAndDirection = null,
                    recursive = spec.recursive,
                    collectionFilter = spec.filter,
                    useSeriesForPrimary = spec.useSeriesForPrimary,
                    defaultViewOptions = spec.defaultViewOptions,
                )
            }
        },
    )

    private val contextMenu by lazy { ContextMenuHelper(this, viewModel) { preferences } }
    private var content: CollectionFolderContentView? = null
    private var shownBefore = false

    override fun createContent(): View {
        val view =
            CollectionFolderContentView(
                context = requireContext(),
                fragment = this,
                config =
                    CollectionFolderConfig(
                        itemId = spec.itemId.toServerString(),
                        initialFilter = spec.filter,
                        recursive = spec.recursive,
                        sortOptions = spec.sortOptions,
                        filterOptions = spec.filterOptions,
                        defaultViewOptions = spec.defaultViewOptions,
                    ),
                viewActions = viewModel,
                actions =
                    CollectionFolderActions(
                        onClickItem = ::onClickItem,
                        showContextMenu = { index, item, opener -> contextMenu.showContextMenu(index, item, opener) },
                        isContextMenuShowing = { contextMenu.isShowing },
                        positionCallback = { columns, position ->
                            viewModel.position = position
                            // showHeader = position < columns: the page title comes and goes with the toolbar
                            content?.showTitle = position < columns
                        },
                    ),
                errorViewModel = errorViewModel,
            )
        content = view
        view.pageStyle = pageStyle
        viewModel.state.collectInView { view.bind(it, viewModel.position) }
        viewModel.homeSettingsService.currentSettings.collectInView {
            view.streamingAppearance = streamingAppearanceFrom(viewModel)
        }
        return view
    }

    private fun onClickItem(
        index: Int,
        item: BaseItem,
    ) {
        when (spec.kind) {
            FolderKind.GENERIC -> {
                navigationManager.navigateTo(item.destination(index))
            }

            FolderKind.PLAYLIST -> {
                navigationManager.navigateTo(item.destination())
            }

            FolderKind.PHOTO_ALBUM -> {
                val state = viewModel.state.value
                val destination =
                    if (item.type == BaseItemKind.PHOTO) {
                        Destination.Slideshow(
                            parentId = spec.itemId,
                            index = index,
                            filter = CollectionFolderFilter(filter = state.filter),
                            sortAndDirection = state.sortAndDirection,
                            recursive = spec.recursive,
                            startSlideshow = false,
                        )
                    } else {
                        item.destination(index)
                    }
                viewModel.navigateTo(destination)
            }
        }
    }

    override fun onStyleChanged(style: PageStyle) {
        content?.pageStyle = style
    }

    override fun onPageVisible() {
        if (shownBefore) content?.onPageShown()
        shownBefore = true
        viewModel.onResumePage()
    }

    override fun onPageHidden() {
        viewModel.release()
    }

    override fun onBackPressed(): Boolean = content?.onBackPressed() ?: false

    override fun onDestroyView() {
        content = null
        super.onDestroyView()
    }
}

/**
 * Movie and TV show libraries (`CollectionFolderMovie` / `CollectionFolderTv`): tabs (remembered per library when the
 * setting is on) over Recommended, the library grid, Collections or Studios, and Genres. The tab row collapses while a
 * tab's content is scrolled past its first row.
 */
abstract class TabbedLibraryFragment : LibraryFragment() {
    protected val itemId: UUID get() = requireDestination<Destination.MediaItem>().itemId

    /** The tab titles */
    protected abstract val tabTitles: List<Int>

    /** Build tab [index]'s content */
    protected abstract fun createTab(index: Int): LibraryTabContent

    private val tabViewModel: TabViewModel by viewModels(
        extrasProducer = {
            defaultViewModelCreationExtras.withCreationCallback<TabViewModel.Factory> {
                it.create(itemId.toString(), tabTitles.size, null)
            }
        },
    )

    protected var page: LibraryTabbedPageView? = null
    private val foldersByTab = HashMap<Int, CollectionFolderViewModel>()

    override fun createContent(): View {
        val neon = AppTheme.isNeon
        foldersByTab.clear()
        val view =
            LibraryTabbedPageView(
                context = requireContext(),
                titles = tabTitles.map { getString(it) },
                createContent = ::createTab,
                tabTopPaddingDp = if (neon) 0f else 16f,
                tabContentTopPaddingDp = if (neon) 8f else 20f,
                tabBottomPaddingDp = if (neon) 0f else 16f,
                tabContentBottomPaddingDp = if (neon) 16f else 20f,
                clockEndPaddingDp = if (preferences.appPreferences.interfacePreferences.showClock) 184f else 0f,
            )
        page = view
        view.onTabClick = tabViewModel::updateSelectedTabIndex
        tabViewModel.state.collectInView { index ->
            if (index in tabTitles.indices) view.select(index)
        }
        tabViewModel.isShowClock.collectInView { view.setClockEndPaddingDp(if (it) 184f else 0f) }
        return view
    }

    /** `showTabs = showHeader` */
    protected fun showTabs(show: Boolean) {
        page?.setShowTabs(show)
    }

    override fun onStyleChanged(style: PageStyle) {
        page?.onPageStyleChanged()
    }

    override fun onBackPressed(): Boolean = page?.onBackPressed() ?: false

    private var shownBefore = false

    override fun onPageVisible() {
        page?.selectedIndex?.let { foldersByTab[it] }?.onResumePage()
        // Back from a later page: the old page was composed again (eg Recommended loaded its rows again)
        if (shownBefore) page?.selectedContent?.onPageShownAgain()
        shownBefore = true
    }

    override fun onPageHidden() {
        foldersByTab.values.forEach { it.release() }
    }

    override fun onDestroyView() {
        page = null
        super.onDestroyView()
    }

    /** The Recommended tab with [rows] */
    protected fun recommendedTab(viewModel: RecommendedViewModel): LibraryTabContent {
        val content =
            RecommendedContentView(
                context = requireContext(),
                fragment = this,
                viewModel = viewModel,
                errorViewModel = errorViewModel,
                splitTitles = true,
                headerBox = HeaderDimens.recommended(),
                onFocusPosition = { pos -> showTabs(pos.row < 1) },
            )
        viewModel.state.collectInView { content.bind(it) }
        return content
    }

    /** A library grid tab (Library, Collections) over [types], kept in its own ViewModel under [key] */
    protected fun folderTab(
        index: Int,
        key: String,
        types: List<BaseItemKind>,
        sortOptions: List<ItemSortBy>,
        filterOptions: List<ItemFilterBy<*>>,
    ): LibraryTabContent {
        val filter = CollectionFolderFilter(filter = GetItemsFilter(includeItemTypes = types))
        val viewModel =
            keyedViewModel<CollectionFolderViewModel, CollectionFolderViewModel.Factory>(key) {
                it.create(
                    itemId = itemId.toServerString(),
                    initialSortAndDirection = null,
                    recursive = true,
                    collectionFilter = filter,
                    useSeriesForPrimary = true,
                    defaultViewOptions = ViewOptionsPoster,
                )
            }
        foldersByTab[index] = viewModel
        val contextMenu = ContextMenuHelper(this, viewModel) { preferences }
        lateinit var content: CollectionFolderContentView
        content =
            CollectionFolderContentView(
                context = requireContext(),
                fragment = this,
                config =
                    CollectionFolderConfig(
                        itemId = itemId.toServerString(),
                        initialFilter = filter,
                        recursive = true,
                        sortOptions = sortOptions,
                        filterOptions = filterOptions,
                        defaultViewOptions = ViewOptionsPoster,
                    ),
                viewActions = viewModel,
                actions =
                    CollectionFolderActions(
                        onClickItem = { _, item -> navigationManager.navigateTo(item.destination()) },
                        showContextMenu = { i, item, opener -> contextMenu.showContextMenu(i, item, opener) },
                        isContextMenuShowing = { contextMenu.isShowing },
                        positionCallback = { columns, position ->
                            viewModel.position = position
                            showTabs(position < columns)
                        },
                        focusOnEmpty = { page?.focusSelectedTab() ?: false },
                    ),
                errorViewModel = errorViewModel,
            )
        content.showTitle = false
        content.pageStyle = pageStyle
        viewModel.state.collectInView { content.bind(it, viewModel.position) }
        viewModel.homeSettingsService.currentSettings.collectInView {
            content.streamingAppearance = streamingAppearanceFrom(viewModel)
        }
        return object : LibraryTabContent {
            override val view: View get() = content

            override fun onShown(firstTime: Boolean) {
                viewModel.onResumePage()
                // The old tab content was composed again: once its items are there, focus goes into them
                if (!firstTime && isTopScreen) {
                    val items = (viewModel.state.value.items as? DataLoadingState.Success)?.data
                    if (items != null && items.isNotEmpty()) content.focusContent() else page?.focusSelectedTab()
                }
                foldersByTab.forEach { (i, vm) -> if (i != index) vm.release() }
            }

            override fun onBackPressed(): Boolean = content.onBackPressed()

            override fun onPageStyleChanged() {
                content.pageStyle = pageStyle
            }
        }
    }

    /** The Genres tab */
    protected fun genresTab(
        types: List<BaseItemKind>,
        collectionType: CollectionType,
    ): LibraryTabContent {
        val viewModel: GenreViewModel =
            keyedViewModel<GenreViewModel, GenreViewModel.Factory>("genres") { it.create(itemId, types) }
        val content = GenreGridContentView(requireContext(), this, errorViewModel, viewModel, itemId, types, collectionType)
        viewModel.state.collectInView { content.bind(it) }
        return content
    }

    /** The Studios tab */
    protected fun studiosTab(types: List<BaseItemKind>): LibraryTabContent {
        val viewModel: StudioViewModel =
            keyedViewModel<StudioViewModel, StudioViewModel.Factory>("studios") { it.create(itemId, types) }
        val content = StudioGridContentView(requireContext(), this, errorViewModel, viewModel, itemId, types)
        viewModel.state.collectInView { content.bind(it) }
        return content
    }
}

/** A movie library: Recommended, Library, Collections, Genres */
@AndroidEntryPoint
class MovieLibraryFragment : TabbedLibraryFragment() {
    override val tabTitles = listOf(R.string.recommended, R.string.library, R.string.collections, R.string.genres)

    private val recommendedViewModel: RecommendedViewModel by viewModels(
        extrasProducer = {
            defaultViewModelCreationExtras.withCreationCallback<RecommendedViewModel.Factory> {
                it.create(
                    parentId = itemId,
                    suggestionsType = BaseItemKind.MOVIE,
                    recommendedRows = recommendedMovieRows(itemId),
                    viewOptions = HomeRowViewOptions(),
                    serviceTop10Type = BaseItemKind.MOVIE,
                )
            }
        },
    )

    override fun createTab(index: Int): LibraryTabContent =
        when (index) {
            0 -> recommendedTab(recommendedViewModel)
            1 -> folderTab(index, "${itemId}_library", listOf(BaseItemKind.MOVIE), MovieSortOptions, DefaultFilterOptions)
            2 -> folderTab(index, "${itemId}_collection", listOf(BaseItemKind.BOX_SET), VideoSortOptions, DefaultFilterOptions)
            else -> genresTab(listOf(BaseItemKind.MOVIE), CollectionType.MOVIES)
        }
}

/** A TV show library: Recommended, Library, Genres, Studios */
@AndroidEntryPoint
class TvLibraryFragment : TabbedLibraryFragment() {
    override val tabTitles = listOf(R.string.recommended, R.string.library, R.string.genres, R.string.studios)

    private val recommendedViewModel: RecommendedViewModel by viewModels(
        extrasProducer = {
            defaultViewModelCreationExtras.withCreationCallback<RecommendedViewModel.Factory> {
                it.create(
                    parentId = itemId,
                    suggestionsType = BaseItemKind.SERIES,
                    recommendedRows =
                        recommendedTvShowRows(
                            itemId,
                            preferences.appPreferences.homePagePreferences.enableRewatchingNextUp,
                        ),
                    viewOptions = HomeRowViewOptions(),
                    serviceTop10Type = BaseItemKind.SERIES,
                )
            }
        },
    )

    override fun createTab(index: Int): LibraryTabContent =
        when (index) {
            0 -> recommendedTab(recommendedViewModel)
            1 -> folderTab(index, "${itemId}_library", listOf(BaseItemKind.SERIES), SeriesSortOptions, DefaultTvFilterOptions)
            2 -> genresTab(listOf(BaseItemKind.SERIES), CollectionType.TVSHOWS)
            else -> studiosTab(listOf(BaseItemKind.SERIES))
        }
}

/**
 * `ItemGrid`: a titled grid of any list of items (View more of a Recommended row, extras…), starting at the
 * destination's position. OK opens an item, the play key plays it, OK held opens its context menu.
 */
@AndroidEntryPoint
class ItemGridFragment : LibraryFragment() {
    private val gridDestination: Destination.ItemGrid<*> get() = requireDestination()

    private val viewModel: ItemGridViewModel by viewModels(
        extrasProducer = {
            defaultViewModelCreationExtras.withCreationCallback<ItemGridViewModel.Factory> { it.create(gridDestination) }
        },
    )

    private val contextMenu by lazy { ContextMenuHelper(this, viewModel) { preferences } }
    private var root: FrameLayout? = null
    private var changesJob: kotlinx.coroutines.Job? = null
    private var shownItems: List<BaseItem?>? = null
    private var overlay: View? = null
    private var column: LinearLayout? = null
    private var grid: CardGridView? = null
    private var title: LibraryGridTitleView? = null
    private var kind = -1

    override fun createContent(): View {
        val context = requireContext()
        kind = -1
        overlay = null
        shownItems = null
        changesJob?.cancel()
        val titleView = LibraryGridTitleView(context)
        val gridView = CardGridView(context)
        val col =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                clipToPadding = false
                addView(titleView, LinearLayout.LayoutParams(MATCH, WRAP))
                addView(gridView, LinearLayout.LayoutParams(MATCH, 0, 1f))
                visibility = View.GONE
            }
        title = titleView
        grid = gridView
        column = col
        val box =
            FrameLayout(context).apply {
                clipChildren = false
                clipToPadding = false
                addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
            }
        root = box
        viewModel.state.collectInView { state -> bind(state.items) }
        return box
    }

    private fun showOverlay(view: View?) {
        val root = root ?: return
        val old = overlay
        overlay = view
        if (view != null) {
            root.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
            column?.visibility = View.GONE
        }
        old?.let { root.removeView(it) }
    }

    private val presenter =
        cardPresenter<BaseItem, GridCardView>(::GridCardView) { card, item, _, width ->
            card.bind(item, pageStyle, width, imageAspectRatio = gridDestination.viewOptions.aspectRatio.ratio)
        }

    private fun bind(items: DataLoadingState<List<BaseItem?>>) {
        when (items) {
            is DataLoadingState.Error -> {
                if (kind != 1) {
                    kind = 1
                    showOverlay(ErrorView(requireContext(), items, errorViewModel::sendLogs))
                }
            }

            DataLoadingState.Loading, DataLoadingState.Pending -> {
                if (kind != 0) {
                    kind = 0
                    showOverlay(LoadingPageView(requireContext()))
                }
            }

            is DataLoadingState.Success -> {
                val entered = kind != 2
                kind = 2
                column?.visibility = View.VISIBLE
                val grid = grid ?: return
                title?.bind(gridDestination.title.getString(requireContext()), pageStyle.sectionAccent)
                val options = gridDestination.viewOptions
                grid.setCards(
                    items = items.data,
                    presenter = presenter,
                    callbacks =
                        CardGridCallbacks(
                            onClick = { index, item -> viewModel.navigateTo(item.destination(index)) },
                            onLongClick = { index, item ->
                                contextMenu.showContextMenu(index, item, grid.grid.findViewHolderForAdapterPosition(index)?.itemView)
                            },
                            onClickPlay = { _, item -> viewModel.navigateTo(Destination.Playback(item)) },
                        ),
                    config =
                        CardGridConfig(
                            columns = options.columns,
                            spacingDp = options.spacing.toFloat(),
                            showLetterButtons = false,
                            initialPosition = gridDestination.initialPosition,
                        ),
                    pageStyle = pageStyle,
                )
                if (items.data !== shownItems) {
                    shownItems = items.data
                    changesJob?.cancel()
                    changesJob =
                        (items.data as? com.github.damontecres.wholphin.util.RequestPager<*>)?.let {
                            grid.observeChanges(viewLifecycleOwner, it.changes)
                        }
                }
                // LaunchedEffect(Unit) { focusRequester.tryRequestFocus() } once the items are there, then the loading
                // picture goes
                if (entered && isTopScreen) grid.grid.requestFocus()
                if (entered) showOverlay(null)
            }
        }
    }

    override fun onStyleChanged(style: PageStyle) {
        grid?.refresh()
    }

    override fun onBackPressed(): Boolean = grid?.onBackPressed() ?: false

    override fun onDestroyView() {
        root = null
        overlay = null
        column = null
        grid = null
        title = null
        kind = -1
        shownItems = null
        changesJob?.cancel()
        super.onDestroyView()
    }
}
