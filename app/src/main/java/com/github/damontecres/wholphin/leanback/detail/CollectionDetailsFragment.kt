package com.github.damontecres.wholphin.leanback.detail

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.fragment.app.viewModels
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.HomeRowViewOptions
import com.github.damontecres.wholphin.leanback.home.HomeHeaderView
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.CardDimens
import com.github.damontecres.wholphin.leanback.theme.NeonSections
import com.github.damontecres.wholphin.leanback.theme.PageStyle
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.forHomeRowCard
import com.github.damontecres.wholphin.leanback.theme.typeAccent
import com.github.damontecres.wholphin.leanback.widget.card.BannerCardView
import com.github.damontecres.wholphin.leanback.widget.card.CardCallbacks
import com.github.damontecres.wholphin.leanback.widget.card.CardGridCallbacks
import com.github.damontecres.wholphin.leanback.widget.card.CardGridConfig
import com.github.damontecres.wholphin.leanback.widget.card.CardGridView
import com.github.damontecres.wholphin.leanback.widget.card.CardImageScale
import com.github.damontecres.wholphin.leanback.widget.card.CardRowAdapter
import com.github.damontecres.wholphin.leanback.widget.card.CardRowStyle
import com.github.damontecres.wholphin.leanback.widget.card.CardRowView
import com.github.damontecres.wholphin.leanback.widget.card.GridCardView
import com.github.damontecres.wholphin.leanback.widget.card.GridScrollMode
import com.github.damontecres.wholphin.leanback.widget.card.HiddenFocusView
import com.github.damontecres.wholphin.leanback.widget.card.PlaceholderRowView
import com.github.damontecres.wholphin.leanback.widget.card.cardPresenter
import com.github.damontecres.wholphin.leanback.widget.card.isPlayKeyUp
import com.github.damontecres.wholphin.leanback.widget.control.LoadingPageView
import com.github.damontecres.wholphin.leanback.widget.text.FittedDetailHeaderLayout
import com.github.damontecres.wholphin.leanback.widget.text.HeaderDimens
import com.github.damontecres.wholphin.leanback.widget.text.detailViewport
import com.github.damontecres.wholphin.leanback.widget.text.enabledDisplayToggles
import com.github.damontecres.wholphin.leanback.widget.text.reportDetailRowHeight
import com.github.damontecres.wholphin.ui.AspectRatio
import com.github.damontecres.wholphin.ui.components.ContextMenu
import com.github.damontecres.wholphin.ui.components.ContextMenuActions
import com.github.damontecres.wholphin.ui.data.ItemDetailsDialogInfo
import com.github.damontecres.wholphin.ui.data.RowColumn
import com.github.damontecres.wholphin.ui.detail.collection.CollectionState
import com.github.damontecres.wholphin.ui.detail.collection.CollectionViewModel
import com.github.damontecres.wholphin.ui.detail.collection.CollectionViewOptions
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.util.HomeRowLoadingState
import com.github.damontecres.wholphin.util.LoadingState
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.withCreationCallback
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemSortBy

/**
 * A collection (box set) page (CollectionDetails.kt): the collection's header (title or logo, quick details, genres,
 * tagline, overview; a streaming service's wordmark) and buttons above its items, in one grid (CollectionMixedGrid) or
 * one row per type (CollectionRows, "Separate types"). Streaming collections keep their header and buttons while their
 * posters reload.
 *
 * When focus moves into the items the header gives way to the focused item's header (with "Show details", else just
 * an invisible focus target); UP from the items' first row brings the collection's header and buttons back, with focus
 * on the last focused button. The focused item is the backdrop (the collection while its header shows). BACK in the
 * grid jumps to its first card, as before.
 */
@AndroidEntryPoint
class CollectionDetailsFragment : DetailPageFragment() {
    private val viewModel: CollectionViewModel by viewModels(
        extrasProducer = {
            defaultViewModelCreationExtras.withCreationCallback<CollectionViewModel.Factory> {
                it.create(requireDestination<Destination.MediaItem>().itemId)
            }
        },
    )

    private var page: CollectionPage? = null

    private val isAdmin: Boolean get() =
        viewModel.serverRepository.currentUserDto
            ?.policy
            ?.isAdministrator == true

    private fun contextActionsFor(position: RowColumn?) =
        ContextMenuActions(
            navigateTo = viewModel::navigateTo,
            onClickWatch = { id, watched -> viewModel.setWatched(id, watched, position) },
            onClickFavorite = { id, favorite -> viewModel.setFavorite(id, favorite, position) },
            onClickAddPlaylist = { showPlaylistDialog(it) },
            onSendMediaInfo = viewModel.serverReportService::sendMediaReportFor,
            onDeleteItem = { viewModel.deleteItem(it, position) },
            onShowOverview = { showOverviewDialog(ItemDetailsDialogInfo(it), isAdmin) },
            // Not supported on this page
            onChooseVersion = { _, _ -> },
            onChooseTracks = { },
            onClearChosenStreams = { },
        )

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        page = null
        viewModel.state.collectWhileStarted { render(it) }
    }

    override fun onDestroyView() {
        page?.release()
        page = null
        super.onDestroyView()
    }

    override fun onPageStyleChanged(style: PageStyle) {
        render(viewModel.state.value)
    }

    override fun onScreenFocus(restoring: Boolean) {
        if (!restoring) page?.firstFocus()?.let { if (it.isAttachedToWindow && it.requestFocus()) return }
        super.onScreenFocus(restoring)
    }

    override fun onBackPressed(): Boolean = page?.onBackPressed() ?: false

    private fun render(state: CollectionState) {
        val loading = state.loadingState
        when {
            loading is LoadingState.Error -> {
                showError(loading)
            }

            // Keep tagged collection controls during reloads so D-pad focus stays on the chosen filter / sort
            loading == LoadingState.Success || state.isStreaming -> {
                if (state.collection == null) {
                    showLoading()
                    return
                }
                var created: CollectionPage? = null
                val first =
                    showContent {
                        CollectionPage(requireContext())
                            .also {
                                created = it
                                page = it
                            }.viewport
                    }
                page?.bind(state)
                if (first) created?.let { c -> c.afterLayout { c.firstFocus()?.let(::requestInitialFocus) } }
            }

            else -> {
                showLoading()
            }
        }
    }

    private fun onLongClickItem(
        position: RowColumn,
        item: BaseItem,
        opener: View?,
    ) {
        showContextMenu(
            ContextMenu.ForBaseItem(
                fromLongClick = true,
                item = item,
                chosenStreams = null,
                showGoTo = true,
                showStreamChoices = false,
                canDelete = viewModel.canDelete(item, preferences.appPreferences),
                canRemoveContinueWatching = false,
                canRemoveNextUp = false,
                actions = contextActionsFor(position),
            ),
            kidPage = false,
            getMediaSource = null,
            preferredSubtitleLanguage = null,
            opener = opener,
        )
    }

    private inner class CollectionPage(
        context: Context,
    ) {
        val viewport =
            com.github.damontecres.wholphin.leanback.widget.text
                .DetailPageLayout(context)
        private val column =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                clipToPadding = false
            }
        private var state: CollectionState? = null

        /** Focus is in the items: the focused item's header shows instead of the collection's */
        private var itemsContentHasFocus = false
        private var focusedItem: BaseItem? = null
        private var backdropItem: BaseItem? = null

        // The collection's header and buttons
        private val collectionHeader = CollectionHeaderView(context)
        private val collectionFitted =
            FittedDetailHeaderLayout(context).apply {
                // The wrapper keeps the header's fixed height (other themes), which the fitting would otherwise ignore
                addView(FrameLayout(context).apply { addView(collectionHeader) })
                onCompactChanged = { collectionHeader.compact = it }
            }
        private val buttons =
            CollectionButtonsView(
                this@CollectionDetailsFragment,
                context,
                CollectionButtonsView.Actions(
                    onFilterChange = viewModel::changeFilter,
                    onSortChange = viewModel::changeSort,
                    onClickRandom = viewModel::onClickRandom,
                    onQuickListChange = viewModel::changeQuickList,
                    getPossibleFilterValues = viewModel::getPossibleFilterValues,
                    onClickViewOptions = { showViewOptions() },
                    favoriteOnClick = { state?.collection?.let { viewModel.setFavorite(it.id, !it.favorite, null) } },
                    onConfirmDelete = { state?.collection?.let { viewModel.deleteItem(it, null) } },
                    moreOnClick = { showMore() },
                ),
            )
        private val collectionBlock =
            FractionColumn(context, 1f, 8f).apply {
                addView(collectionFitted, LinearLayout.LayoutParams(MATCH, WRAP))
                addView(buttons, LinearLayout.LayoutParams(MATCH, WRAP))
            }

        // The focused item's header: an invisible focus target above the items brings the collection header back
        private val hiddenFocus = HiddenFocusView(context) { viewport.post { showCollectionHeader() } }

        // HOME's `HomePageHeader` port, in the old box: 8dp in, the detail top padding, 8dp below; 180dp tall off WeaselTV
        private val itemHeader =
            HomeHeaderView(
                context,
                showLogo = preferences.appPreferences.interfacePreferences.showLogos,
                box =
                    HeaderDimens.HeaderBox(
                        HeaderDimens.START_PADDING,
                        HeaderDimens.detailTopPadding(),
                        0f,
                        8f,
                        if (AppTheme.isNeon) null else HeaderDimens.HEIGHT,
                    ),
            )
        private val itemFitted =
            FittedDetailHeaderLayout(context).apply {
                addView(FrameLayout(context).apply { addView(itemHeader) })
            }
        private val itemBlock =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                addView(hiddenFocus)
                addView(itemFitted, LinearLayout.LayoutParams(MATCH, WRAP))
                visibility = View.GONE
            }
        private val headerSwitcher =
            FrameLayout(context).apply {
                clipChildren = false
                clipToPadding = false
                addView(collectionBlock, FrameLayout.LayoutParams(MATCH, WRAP))
                addView(itemBlock, FrameLayout.LayoutParams(MATCH, WRAP))
            }

        // The items
        private val contentBox =
            FrameLayout(context).apply {
                clipChildren = false
                clipToPadding = false
            }
        private val streamingLoading = LoadingPageView(context, focusEnabled = false)
        private val grid = CardGridView(context)
        private val rows = DetailPageList(context, spacingDp = 0f)
        private val rowViews = mutableMapOf<BaseItemKind, View>()
        private val rowAdapters = mutableMapOf<BaseItemKind, CardRowAdapter<BaseItem>>()
        private val gridPresenter =
            cardPresenter<BaseItem, GridCardView>(::GridCardView) { card, item, _, cellWidthPx ->
                val options = state?.viewOptions?.cardViewOptions
                if (options != null) {
                    card.bind(
                        item,
                        pageStyle,
                        cellWidthPx,
                        imageAspectRatio = options.aspectRatio.ratio,
                        imageScale = CardImageScale.from(options.contentScale),
                        imageType = options.imageType.imageType,
                        showTitle = options.showTitles,
                    )
                }
            }
        private val rowKey = Any()

        private val focusListener =
            ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
                if (newFocus != null && isInside(newFocus, contentBox) && !itemsContentHasFocus) showItemHeader()
                if (newFocus != null && isInside(newFocus, collectionBlock)) state?.collection?.let { setBackdrop(it) }
            }

        init {
            column.addView(headerSwitcher, LinearLayout.LayoutParams(MATCH, WRAP))
            contentBox.addView(grid, FrameLayout.LayoutParams(MATCH, MATCH))
            rows.setPadding(0, context.dp(8), 0, 0)
            contentBox.addView(
                rows,
                FrameLayout.LayoutParams(MATCH, MATCH).apply {
                    marginStart = -context.neonGutterStartPx()
                    marginEnd = -context.neonGutterEndPx()
                },
            )
            contentBox.addView(streamingLoading, FrameLayout.LayoutParams(MATCH, MATCH))
            column.addView(contentBox, LinearLayout.LayoutParams(MATCH, 0, 1f))
            viewport.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))
            viewport.viewTreeObserver.addOnGlobalFocusChangeListener(focusListener)
            // Size the header for the grid's first row up front, so it doesn't shrink once items load
            viewport.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ -> if (r - l != or - ol) reportGridRow() }
        }

        fun release() {
            viewport.viewTreeObserver.removeOnGlobalFocusChangeListener(focusListener)
        }

        fun afterLayout(block: () -> Unit) {
            androidx.core.view.OneShotPreDrawListener
                .add(viewport) { block() }
        }

        fun firstFocus(): View? = if (itemsContentHasFocus) null else buttons.restoreTarget

        fun onBackPressed(): Boolean = grid.visibility == View.VISIBLE && grid.hasFocus() && grid.onBackPressed()

        private fun isInside(
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

        private fun setBackdrop(item: BaseItem) {
            if (item === backdropItem) return
            backdropItem = item
            viewModel.updateBackdrop(item)
        }

        private fun reportGridRow() {
            val s = state ?: return
            if (s.viewOptions.separateTypes) return
            val vp = viewport.detailViewport() ?: return
            val options = s.viewOptions.cardViewOptions
            val height =
                CardGridView.rowHeightPx(
                    requireContext(),
                    viewport.width,
                    options.columns,
                    options.spacing.toFloat(),
                    options.aspectRatio.ratio,
                    options.showTitles,
                    collectionGridTopPaddingDp(),
                )
            vp.reportRow(rowKey, height, 0)
        }

        private val enter = PathInterpolator(0f, 0f, .2f, 1f)

        /** The header switch (AnimatedContent): the new header fades and scales in after 90 ms, the old one goes */
        private fun swapIn(view: View) {
            view.alpha = 0f
            view.scaleX = .92f
            view.scaleY = .92f
            view.pivotX = 0f
            view.pivotY = 0f
            view
                .animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay(90)
                .setDuration(220)
                .setInterpolator(enter)
                .start()
        }

        private fun showItemHeader() {
            itemsContentHasFocus = true
            collectionBlock.visibility = View.GONE
            itemBlock.visibility = View.VISIBLE
            itemFitted.visibility = if (state?.viewOptions?.cardViewOptions?.showDetails == true) View.VISIBLE else View.GONE
            swapIn(itemBlock)
            bindItemHeader()
        }

        private fun showCollectionHeader() {
            if (!itemsContentHasFocus) return
            itemsContentHasFocus = false
            // Focus moves to the buttons before the item header (holding the focus target) goes away
            collectionBlock.visibility = View.VISIBLE
            buttons.restoreTarget?.requestFocus()
            itemBlock.visibility = View.GONE
            swapIn(collectionBlock)
            focusedItem = state?.collection
            state?.collection?.let { setBackdrop(it) }
        }

        private fun bindItemHeader() {
            val s = state ?: return
            itemHeader.pageStyle = pageStyle
            itemHeader.displayToggles = enabledDisplayToggles(preferences.appPreferences)
            itemHeader.showLogo = preferences.appPreferences.interfacePreferences.showLogos
            itemHeader.bind(focusedItem)
            focusedItem?.let { setBackdrop(it) }
            itemFitted.visibility = if (s.viewOptions.cardViewOptions.showDetails) View.VISIBLE else View.GONE
        }

        private fun onFocusItem(item: BaseItem?) {
            focusedItem = item
            if (itemsContentHasFocus) bindItemHeader()
        }

        fun bind(state: CollectionState) {
            val old = this.state
            this.state = state
            val collection = state.collection ?: return
            if (focusedItem == null) focusedItem = collection
            val ctx = requireContext()
            val streaming = state.isStreaming
            collectionHeader.bind(collection, streaming, state.logoImageUrl, preferences, pageStyle.sectionAccent) {
                showOverviewDialog(ItemDetailsDialogInfo(collection), isAdmin)
            }
            val top = if (streaming) 27f else HeaderDimens.detailTopPadding()
            val bottom = if (streaming) 12f else HeaderDimens.BOTTOM_PADDING
            collectionHeader.setPadding(0, ctx.dp(top), 0, ctx.dp(bottom))
            if (!AppTheme.isNeon) collectionHeader.fixHeaderHeight(if (streaming) 150f else HeaderDimens.HEIGHT, top, bottom)
            buttons.bind(state, viewModel.canDelete(collection, preferences.appPreferences), pageStyle.sectionAccent)
            if (!itemsContentHasFocus) setBackdrop(collection) else bindItemHeader()

            // Items
            if (streaming && state.loadingState != LoadingState.Success) {
                streamingLoading.visibility = View.VISIBLE
                grid.visibility = View.GONE
                rows.visibility = View.GONE
            } else if (state.viewOptions.separateTypes) {
                streamingLoading.visibility = View.GONE
                grid.visibility = View.GONE
                rows.visibility = View.VISIBLE
                bindRows(state)
            } else {
                streamingLoading.visibility = View.GONE
                rows.visibility = View.GONE
                grid.visibility = View.VISIBLE
                bindGrid(state)
            }
            if (old?.viewOptions != state.viewOptions) reportGridRow()
        }

        private fun bindGrid(state: CollectionState) {
            val options = state.viewOptions.cardViewOptions
            grid.setCards(
                items = state.items,
                presenter = gridPresenter,
                callbacks =
                    CardGridCallbacks(
                        onClick = { _, item -> viewModel.navigateTo(item.destination()) },
                        onLongClick = { index, item -> onLongClickItem(RowColumn(0, index), item, grid.findFocus()) },
                        onClickPlay = { _, item -> viewModel.navigateTo(Destination.Playback(item = item)) },
                        letterPosition = viewModel::letterPosition,
                        onPosition = { _, position -> onFocusItem(state.items.getOrNull(position)) },
                    ),
                config =
                    CardGridConfig(
                        columns = options.columns,
                        spacingDp = options.spacing.toFloat(),
                        topPaddingDp = collectionGridTopPaddingDp(),
                        showLetterButtons = state.quickList == null && state.sortAndDirection.sort == ItemSortBy.SORT_NAME,
                        initialPosition = 0,
                        scrollMode = if (options.showDetails) GridScrollMode.Top(options.spacing.toFloat()) else GridScrollMode.Pivot,
                    ),
                pageStyle = pageStyle,
            )
            watchPager("items", state.items) { grid.refresh() }
        }

        /** `CollectionRows`: one row per type (episodes and videos wide, playlists square), from the Home row look */
        private fun bindRows(state: CollectionState) {
            val showTitles = state.viewOptions.cardViewOptions.showTitles
            val specs = mutableListOf<DetailRowSpec>()
            state.separateItems.entries.forEachIndexed { rowIndex, (type, row) ->
                val viewOptions =
                    when (type) {
                        BaseItemKind.EPISODE -> {
                            HomeRowViewOptions(
                                heightDp = CardDimens.HEIGHT_EPISODE_DP,
                                episodeAspectRatio = AspectRatio.WIDE,
                                showTitles = showTitles,
                                useSeries = false,
                            )
                        }

                        BaseItemKind.PLAYLIST -> {
                            HomeRowViewOptions(
                                heightDp = CardDimens.HEIGHT_EPISODE_DP,
                                aspectRatio = AspectRatio.SQUARE,
                                showTitles = showTitles,
                            )
                        }

                        BaseItemKind.VIDEO, BaseItemKind.MUSIC_VIDEO -> {
                            HomeRowViewOptions(
                                heightDp = CardDimens.HEIGHT_EPISODE_DP,
                                aspectRatio = AspectRatio.WIDE,
                                showTitles = showTitles,
                            )
                        }

                        else -> {
                            HomeRowViewOptions(showTitles = showTitles)
                        }
                    }
                val view = rowViews.getOrPut(type) { collectionRowView(requireContext()) }
                when (row) {
                    is HomeRowLoadingState.Success -> {
                        if (row.items.isEmpty()) return@forEachIndexed
                        specs +=
                            DetailRowSpec("row-$type", { view }) {
                                bindRow(view, type, rowIndex, row, viewOptions)
                            }
                    }

                    is HomeRowLoadingState.Error -> {
                        specs +=
                            DetailRowSpec(
                                "row-$type",
                                { view },
                            ) { bindPlaceholder(view, row.title.getString(requireContext()), row.localizedMessage, true) }
                    }

                    is HomeRowLoadingState.Loading, is HomeRowLoadingState.Pending -> {
                        specs +=
                            DetailRowSpec(
                                "row-$type",
                                { view },
                            ) { bindPlaceholder(view, row.title.getString(requireContext()), getString(R.string.loading), false) }
                    }
                }
            }
            rows.listAdapter.submit(specs)
        }

        private fun collectionRowView(context: Context): FrameLayout =
            FrameLayout(context).apply {
                clipChildren = false
                clipToPadding = false
                layoutParams = ViewGroup.LayoutParams(MATCH, WRAP)
                addView(CardRowView(context).apply { reportDetailRowHeight() }, FrameLayout.LayoutParams(MATCH, WRAP))
                addView(PlaceholderRowView(context), FrameLayout.LayoutParams(MATCH, WRAP))
            }

        private fun bindPlaceholder(
            view: View,
            title: String,
            message: String,
            error: Boolean,
        ) {
            val frame = view as FrameLayout
            val row = frame.getChildAt(0) as CardRowView
            val placeholder = frame.getChildAt(1) as PlaceholderRowView
            row.visibility = View.GONE
            placeholder.visibility = View.VISIBLE
            val ctx = view.context
            placeholder.bind(
                title,
                message,
                pageStyle.sectionAccent,
                isError = error,
                focusableRow = true,
                insetStartPx = ctx.neonGutterStartPx(),
                insetEndPx = ctx.neonGutterEndPx(),
            )
        }

        private fun bindRow(
            view: View,
            type: BaseItemKind,
            rowIndex: Int,
            row: HomeRowLoadingState.Success,
            viewOptions: HomeRowViewOptions,
        ) {
            val frame = view as FrameLayout
            val rowView = frame.getChildAt(0) as CardRowView
            frame.getChildAt(1).visibility = View.GONE
            rowView.visibility = View.VISIBLE
            val ctx = view.context
            val section = pageStyle.sectionAccent
            val adapter =
                rowAdapters.getOrPut(type) {
                    CardRowAdapter(
                        cardPresenter<BaseItem, BannerCardView>(::BannerCardView) { card, item, _, _ ->
                            bindHomeCard(card, item, viewOptions, section)
                        },
                    )
                }
            adapter.callbacks =
                CardCallbacks(
                    onClick = { _, item -> viewModel.navigateTo(item.destination()) },
                    onLongClick = { index, item -> onLongClickItem(RowColumn(rowIndex, index), item, rowView.findFocus()) },
                    onFocus = { _, item -> onFocusItem(item) },
                    onKey = { _, item, event ->
                        if (item != null && isPlayKeyUp(event) && item.playable) {
                            viewModel.navigateTo(Destination.Playback(item = item))
                            true
                        } else {
                            false
                        }
                    },
                )
            rowView.setup(
                row.title.getString(ctx),
                section,
                pageStyle.forHomeRowCard(viewOptions, null),
                CardRowStyle.forHomeRow(viewOptions, ctx.neonGutterStartPx(), ctx.neonGutterEndPx()),
            )
            rowView.setCards(adapter, row.items)
        }

        /** `HomePageCardContent` for a collection row: the row's card look, accents as a Home row without a type */
        private fun bindHomeCard(
            card: BannerCardView,
            item: BaseItem?,
            viewOptions: HomeRowViewOptions,
            section: Int,
        ) {
            val rowAccent =
                if (item?.type in listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.SEASON, BaseItemKind.EPISODE)) {
                    if (section == NeonSections.StandUpComedy.border ||
                        section == NeonSections.Sports.border
                    ) {
                        section
                    } else {
                        typeAccent(item?.type)
                    }
                } else {
                    section
                }
            val style = pageStyle.forHomeRowCard(viewOptions, rowAccent)
            val episode = item?.type == BaseItemKind.EPISODE
            card.bind(
                item = item,
                style = style,
                name = item?.data?.seriesName ?: item?.name,
                cardHeightDp = viewOptions.heightDp.toFloat(),
                aspectRatio = if (episode) viewOptions.episodeAspectRatio.ratio else viewOptions.aspectRatio.ratio,
                imageType = if (episode) viewOptions.episodeImageType.imageType else viewOptions.imageType.imageType,
                imageScale = CardImageScale.from(if (episode) viewOptions.episodeContentScale else viewOptions.contentScale),
                cornerText = item?.ui?.episodeUnplayedCornerText,
                played = item?.data?.userData?.played ?: false,
                favorite = item?.favorite ?: false,
                playPercent = item?.data?.userData?.playedPercentage ?: 0.0,
                useSeriesForPrimary = viewOptions.useSeries,
                withTitle = viewOptions.showTitles,
                title = item?.title,
                subtitle = item?.subtitle,
            )
        }

        private fun showMore() {
            val collection = state?.collection ?: return
            showContextMenu(
                ContextMenu.ForBaseItem(
                    fromLongClick = false,
                    item = collection,
                    chosenStreams = null,
                    showGoTo = false,
                    showStreamChoices = false,
                    canDelete = viewModel.canDelete(collection, preferences.appPreferences),
                    canRemoveContinueWatching = false,
                    canRemoveNextUp = false,
                    actions = contextActionsFor(null),
                ),
                kidPage = false,
                getMediaSource = null,
                preferredSubtitleLanguage = null,
            )
        }

        private fun showViewOptions() {
            val s = state ?: return
            CollectionViewOptionsDialog(
                context = requireContext(),
                initial = s.viewOptions,
                accent = pageStyle.sectionAccent,
                onViewOptionsChange = viewModel::changeViewOptions,
            ).show()
        }
    }
}

/** Gravity of the view options panel: the end of the screen */
internal const val VIEW_OPTIONS_GRAVITY = Gravity.END
