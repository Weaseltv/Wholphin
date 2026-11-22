package com.github.damontecres.wholphin.leanback.library

import android.annotation.SuppressLint
import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.leanback.widget.BaseGridView
import androidx.leanback.widget.GridLayoutManager
import androidx.leanback.widget.VerticalGridView
import androidx.recyclerview.widget.RecyclerView
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.ApprovedHomeLayout
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.HomeRowViewOptions
import com.github.damontecres.wholphin.leanback.shell.DestinationFragment
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.CardDimens
import com.github.damontecres.wholphin.leanback.theme.NeonSections
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.forHomeRowCard
import com.github.damontecres.wholphin.leanback.theme.typeAccent
import com.github.damontecres.wholphin.leanback.widget.card.BannerCardView
import com.github.damontecres.wholphin.leanback.widget.card.BaseCardView
import com.github.damontecres.wholphin.leanback.widget.card.CardCallbacks
import com.github.damontecres.wholphin.leanback.widget.card.CardImageScale
import com.github.damontecres.wholphin.leanback.widget.card.CardPresenter
import com.github.damontecres.wholphin.leanback.widget.card.CardRowAdapter
import com.github.damontecres.wholphin.leanback.widget.card.CardRowStyle
import com.github.damontecres.wholphin.leanback.widget.card.CardRowView
import com.github.damontecres.wholphin.leanback.widget.card.GenreCardView
import com.github.damontecres.wholphin.leanback.widget.card.PlaceholderRowView
import com.github.damontecres.wholphin.leanback.widget.card.StudioCardView
import com.github.damontecres.wholphin.leanback.widget.card.ViewMoreSpec
import com.github.damontecres.wholphin.leanback.widget.card.clipToOwnHeight
import com.github.damontecres.wholphin.leanback.widget.control.ErrorView
import com.github.damontecres.wholphin.leanback.widget.control.LoadingPageView
import com.github.damontecres.wholphin.leanback.widget.dialog.ContextMenuHelper
import com.github.damontecres.wholphin.leanback.widget.text.FittedDetailHeaderLayout
import com.github.damontecres.wholphin.leanback.widget.text.HeaderDimens
import com.github.damontecres.wholphin.leanback.widget.text.enabledDisplayToggles
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.ui.components.ContextMenuProvider
import com.github.damontecres.wholphin.ui.components.ErrorViewModel
import com.github.damontecres.wholphin.ui.components.RecommendedState
import com.github.damontecres.wholphin.ui.components.RecommendedViewModel
import com.github.damontecres.wholphin.ui.data.RowColumn
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.util.ResArgStringProvider
import com.github.damontecres.wholphin.ui.util.ResStringProvider
import com.github.damontecres.wholphin.util.HomeRowLoadingState
import com.github.damontecres.wholphin.util.LoadingState
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID
import kotlin.math.max

/**
 * A library's Recommended tab (`RecommendedContent` with Home's row list, `HomePageContent`): the focused item's header
 * above rows of cards (Continue watching, Recently released / added, Top unwatched, Suggestions, the streaming services'
 * Top 10). Rows still loading or failed show as focusable placeholder rows; empty rows take no space.
 *
 * - The rows use the library's Home card appearance (WeaselTV: the approved Home presentation).
 * - [splitTitles] (Movies and TV Shows, WeaselTV): split headings ("JUST" over "RELEASED"), and while the first row
 *   with cards is focused the header is the compact one; below it the normal Home header.
 * - WeaselTV: the header is scaled to leave room for the focused row and the next row's heading; it stays hidden until
 *   the focused row has been measured. The focused row scrolls to 4dp below the header (50dp on other themes).
 * - Focus enters the rows at the row and card focused last; the first time at the first row with cards.
 * - The focused item feeds the backdrop. OK opens an item, the play key plays it, OK held opens its context menu, View
 *   more opens the full list.
 */
@SuppressLint("ViewConstructor")
class RecommendedContentView(
    context: Context,
    private val fragment: DestinationFragment,
    private val viewModel: RecommendedViewModel,
    private val errorViewModel: ErrorViewModel,
    private val splitTitles: Boolean,
    private val headerBox: HeaderDimens.HeaderBox,
    /** The focused row and card, counting only rows that are shown (`onFocusPosition`) */
    private val onFocusPosition: (RowColumn) -> Unit,
) : FrameLayout(context),
    LibraryTabContent {
    override val view: View get() = this

    private val neon = AppTheme.isNeon
    private var shownKind = -1
    private var rowsView: RowsView? = null
    private var overlay: View? = null
    private var lastState: RecommendedState? = null

    /** The row and card the context menu is for (the old page read its focus position) */
    private var menuPosition = RowColumn(-1, -1)

    private val contextMenu by lazy {
        ContextMenuHelper(
            fragment,
            object : ContextMenuProvider {
                override fun isAdministrator(): Boolean =
                    viewModel.serverRepository.currentUserDto
                        ?.policy
                        ?.isAdministrator == true

                override fun navigateTo(destination: Destination) = viewModel.navigationManager.navigateTo(destination)

                override fun canDelete(
                    item: BaseItem,
                    appPreferences: AppPreferences,
                ): Boolean = viewModel.canDelete(item, appPreferences)

                override fun deleteItem(
                    index: Int,
                    item: BaseItem,
                ) = viewModel.deleteItem(menuPosition, item)

                override fun setWatched(
                    position: Int,
                    itemId: UUID,
                    played: Boolean,
                ) = viewModel.setWatched(menuPosition, itemId, played)

                override fun setFavorite(
                    position: Int,
                    itemId: UUID,
                    favorite: Boolean,
                ) = viewModel.setFavorite(menuPosition, itemId, favorite)

                override fun sendReportFor(itemId: UUID) = viewModel.serverReportService.sendMediaReportFor(itemId)
            },
        ) { fragment.preferences }
    }

    init {
        clipChildren = false
        clipToPadding = false
    }

    /** Show the ViewModel's state */
    fun bind(state: RecommendedState) {
        lastState = state
        when (val loading = state.loading) {
            is LoadingState.Error -> {
                if (shownKind != KIND_ERROR) {
                    shownKind = KIND_ERROR
                    showOverlay(ErrorView(context, loading, errorViewModel::sendLogs))
                }
            }

            LoadingState.Pending -> {
                // The few moments before it is known whether last time's rows can be shown: hold focus as the loading
                // page does, without flashing the loading picture in front of rows that are about to appear
                if (shownKind != KIND_PENDING) {
                    shownKind = KIND_PENDING
                    val holder =
                        View(context).apply {
                            isFocusable = true
                            isFocusableInTouchMode = false
                            if (android.os.Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
                        }
                    showOverlay(holder)
                    if (fragment.isTopScreen && isShown) holder.requestFocus()
                }
            }

            LoadingState.Loading -> {
                if (shownKind != KIND_LOADING) {
                    shownKind = KIND_LOADING
                    showOverlay(LoadingPageView(context))
                }
            }

            LoadingState.Success -> {
                if (shownKind != KIND_SUCCESS) {
                    shownKind = KIND_SUCCESS
                    showOverlay(null)
                }
                val rows =
                    rowsView ?: RowsView(context).also {
                        rowsView = it
                        addView(it, LayoutParams(MATCH, MATCH))
                    }
                rows.visibility = VISIBLE
                rows.setRows(presentRows(state.rows))
            }
        }
    }

    /** WeaselTV: the approved Home presentation with the library's card appearance (`LocalHomeCardAppearance`) */
    private fun presentRows(rows: List<HomeRowLoadingState>): List<HomeRowLoadingState> {
        if (!neon) return rows
        val appearance = fragment.pageStyle.homeCardAppearance
        return rows.map { row ->
            if (row is HomeRowLoadingState.Success) {
                row.copy(viewOptions = ApprovedHomeLayout.applyPresentation(row.viewOptions).copy(cardAppearance = appearance))
            } else {
                row
            }
        }
    }

    private fun showOverlay(view: View?) {
        val old = overlay
        overlay = view
        if (view != null) {
            addView(view, LayoutParams(MATCH, MATCH))
            rowsView?.visibility = GONE
        }
        old?.let { removeView(it) }
    }

    override fun onShown(firstTime: Boolean) {
        // OneTimeLaunchedEffect { viewModel.init() }: every time the tab's content appears
        viewModel.init()
        // The old tab content started over when selected again: focus on the first row with cards, rows at their start
        if (!firstTime) rowsView?.startOver()
    }

    override fun onPageShownAgain() {
        viewModel.init()
    }

    override fun onPageStyleChanged() {
        lastState?.let { bind(it) }
        rowsView?.rebindAll()
    }

    /** `recommendedRowHeading`: split WeaselTV headings as (title, kicker) */
    private fun heading(row: HomeRowLoadingState.Success): Pair<String, String?> {
        val title = row.title
        return when ((title as? ResStringProvider)?.stringResId) {
            R.string.continue_watching, R.string.next_up -> {
                context.getString(R.string.continue_watching) to "${context.getString(R.string.next_up)} &"
            }

            R.string.recently_released -> {
                context.getString(R.string.recommended_title_released) to context.getString(R.string.recommended_kicker_just)
            }

            R.string.recently_added -> {
                context.getString(R.string.recommended_title_added) to context.getString(R.string.recommended_kicker_just)
            }

            R.string.top_unwatched -> {
                context.getString(R.string.recommended_title_unwatched) to context.getString(R.string.recommended_kicker_top_rated)
            }

            R.string.suggestions -> {
                context.getString(R.string.suggestions) to context.getString(R.string.recommended_kicker_your)
            }

            else -> {
                if ((title as? ResArgStringProvider)?.stringResId == R.string.recommended_title_on_service) {
                    // A streaming service's Top 10: "TOP 10" over "ON NETFLIX"
                    title.getString(context) to context.getString(R.string.quick_list_top_10)
                } else {
                    title.getString(context) to null
                }
            }
        }
    }

    /** The header, the rows, focus and measurements (`HomePageContent`) */
    private inner class RowsView(
        context: Context,
    ) : LinearLayout(context) {
        private val headerFit = FittedDetailHeaderLayout(context)
        private val header = LibraryItemHeaderView(context)
        private val list = VerticalGridView(context)
        private val adapter = RowsAdapter()
        private var rows: List<HomeRowLoadingState> = emptyList()
        private var position = RowColumn(-1, -1)
        private var firstFocused = false
        private val rowAdapters = HashMap<Int, CardRowAdapter<BaseItem>>()
        private val rowSelections = HashMap<Int, Int>()
        private val rowHeights = HashMap<Int, Int>()
        private val titleHeights = HashMap<Int, Int>()
        private var focusedItem: BaseItem? = null
        private var headerShown: Pair<BaseItem?, Boolean>? = null
        private val insetStart = if (neon) context.dp(CardDimens.NEON_RULE_INSET_START_DP) else 0
        private val insetEnd = if (neon) context.dp(CardDimens.NEON_RULE_INSET_END_DP) else 0

        init {
            orientation = VERTICAL
            clipChildren = false
            clipToPadding = false
            list.clipToOwnHeight()
            headerFit.addView(header, ViewGroup.LayoutParams(MATCH, WRAP))
            HeaderDimens.apply(header, headerBox)
            addView(headerFit, LayoutParams(MATCH, WRAP))
            if (neon) headerFit.alpha = 0f
            list.clipChildren = false
            list.clipToPadding = false
            list.itemAnimator = null
            list.setNumColumns(1)
            // UP from the first row leaves the list (to the tabs and buttons above it), as the Compose list did; a Leanback list keeps focus at its ends by default
            (list.layoutManager as? GridLayoutManager)?.setFocusOutAllowed(true, false)
            list.setPadding(0, 0, 0, context.dp(CardDimens.HEIGHT_2X3_DP))
            // The focused row's top at 4dp (WeaselTV) / 50dp below the list's top; the first row stays at the top
            list.windowAlignment = BaseGridView.WINDOW_ALIGN_LOW_EDGE
            list.windowAlignmentOffset = context.dp(if (neon) 4 else 50)
            list.windowAlignmentOffsetPercent = BaseGridView.WINDOW_ALIGN_OFFSET_PERCENT_DISABLED
            list.itemAlignmentOffset = 0
            list.itemAlignmentOffsetPercent = 0f
            list.isItemAlignmentOffsetWithPadding = false
            list.adapter = adapter
            addView(
                list,
                LayoutParams(MATCH, 0, 1f).apply {
                    // The rows lie across the page gutters so their rules reach the side menu and the screen edge
                    marginStart = -insetStart
                    marginEnd = -insetEnd
                },
            )
        }

        private fun hasContent(row: HomeRowLoadingState?) = row is HomeRowLoadingState.Success && row.items.isNotEmpty()

        private fun firstContentRow(): Int = rows.indexOfFirst { hasContent(it) }

        fun setRows(newRows: List<HomeRowLoadingState>) {
            val old = rows
            rows = newRows
            if (old.size != newRows.size) {
                @Suppress("NotifyDataSetChanged")
                adapter.notifyDataSetChanged()
            } else {
                newRows.indices.forEach { if (old[it] != newRows[it]) adapter.notifyItemChanged(it) }
            }
            updateFocusedItem()
            takeFirstFocus()
        }

        fun rebindAll() {
            rowAdapters.clear()
            @Suppress("NotifyDataSetChanged")
            adapter.notifyDataSetChanged()
            bindHeader(force = true)
        }

        /** `takeFocus`: once, on the remembered row, else on the first row with cards when it arrives */
        private fun takeFirstFocus() {
            if (firstFocused || rows.isEmpty()) return
            if (!fragment.isTopScreen || !isShown) return
            val target =
                if (position.row >= 0) {
                    position.row.coerceIn(0, rows.lastIndex)
                } else {
                    firstContentRow().takeIf { it >= 0 } ?: return
                }
            firstFocused = true
            focusRow(target)
        }

        private fun focusRow(index: Int) {
            list.setSelectedPosition(index) { holder -> holder.itemView.requestFocus() }
            // Before the row is laid out the list holds focus and hands it to the row (Leanback)
            if (!list.hasFocus()) list.requestFocus()
        }

        /** The tab was selected again: like a new composition, the rows start over and the first one takes focus */
        fun startOver() {
            position = RowColumn(-1, -1)
            firstFocused = false
            rowSelections.clear()
            for (i in 0 until list.childCount) (list.getChildAt(i) as? CardRowView)?.strip?.selectedPosition = 0
            headerShown = null
            updateFocusedItem()
            takeFirstFocus()
        }

        private fun onCardFocused(
            row: Int,
            column: Int,
        ) {
            position = RowColumn(row, column)
            rowSelections[row] = column
            val emptyBefore = rows.subList(0, row).count { it is HomeRowLoadingState.Success && it.items.isEmpty() }
            onFocusPosition(RowColumn(row - emptyBefore, column))
            updateFocusedItem()
            updateHeaderBudget()
        }

        private fun updateFocusedItem() {
            val item = (rows.getOrNull(position.row) as? HomeRowLoadingState.Success)?.items?.getOrNull(position.column)
            if (item != focusedItem) {
                focusedItem = item
                item?.let(viewModel::updateBackdrop)
            }
            bindHeader()
        }

        private fun bindHeader(force: Boolean = false) {
            val firstContent = firstContentRow()
            val split = splitTitles && neon
            val compact = split && position.row <= firstContent
            val key = focusedItem to compact
            if (!force && key == headerShown) return
            headerShown = key
            // Below the first row the normal Home header and its padding
            HeaderDimens.apply(header, if (split && position.row > firstContent) HeaderDimens.home() else headerBox)
            val prefs = fragment.preferences.appPreferences
            header.bind(
                focusedItem,
                fragment.pageStyle,
                showLogo = prefs.interfacePreferences.showLogos,
                compactLogo = split,
                compactDetails = compact,
                toggles = enabledDisplayToggles(prefs),
            )
        }

        /** WeaselTV: keep the focused row and the next row's heading below the header */
        private fun updateHeaderBudget() {
            if (!neon) return
            val focusedIndex = position.row.coerceAtLeast(0)
            val rowHeight = rowHeights[focusedIndex] ?: return
            val nextIndex = ((focusedIndex + 1) until rows.size).firstOrNull { hasContent(rows[it]) }
            val nextTitle = nextIndex?.let { titleHeights[it] ?: titleHeights[focusedIndex] } ?: 0
            val budget = max(height - (rowHeight + nextTitle) - context.dp(16), context.dp(1))
            headerFit.fillHeight = true
            if (headerFit.maxHeightPx != budget) headerFit.maxHeightPx = budget
            if (headerFit.alpha < 1f) headerFit.alpha = 1f
        }

        override fun onSizeChanged(
            w: Int,
            h: Int,
            oldw: Int,
            oldh: Int,
        ) {
            super.onSizeChanged(w, h, oldw, oldh)
            post { updateHeaderBudget() }
        }

        private fun rowAccentFor(item: BaseItem?): Int {
            val rowColor = fragment.pageStyle.sectionAccent
            return if (item != null &&
                item.type in listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.SEASON, BaseItemKind.EPISODE)
            ) {
                if (rowColor == NeonSections.StandUpComedy.border ||
                    rowColor == NeonSections.Sports.border
                ) {
                    rowColor
                } else {
                    typeAccent(item.type)
                }
            } else {
                rowColor
            }
        }

        private fun adapterFor(rowIndex: Int): CardRowAdapter<BaseItem> =
            rowAdapters.getOrPut(rowIndex) {
                CardRowAdapter(
                    presenter = HomeCardPresenter { rows.getOrNull(rowIndex) as? HomeRowLoadingState.Success },
                    callbacks =
                        CardCallbacks(
                            onClick = { _, item -> viewModel.navigationManager.navigateTo(item.destination()) },
                            onLongClick = { index, item ->
                                menuPosition = RowColumn(rowIndex, index)
                                position = menuPosition
                                contextMenu.showContextMenu(index, item, findFocus())
                            },
                            onFocus = { index, _ -> onCardFocused(rowIndex, index) },
                            onKey = { _, item, event ->
                                if (event.action == KeyEvent.ACTION_UP && isPlayKey(event.keyCode) && item?.playable == true) {
                                    viewModel.navigationManager.navigateTo(Destination.Playback(item))
                                    true
                                } else {
                                    false
                                }
                            },
                        ),
                )
            }

        /** `HomePageCardContent` for a Recommended row: genre and studio cards, else banner cards (with titles if set) */
        private inner class HomeCardPresenter(
            private val row: () -> HomeRowLoadingState.Success?,
        ) : CardPresenter<BaseItem>() {
            override fun viewType(
                item: BaseItem?,
                position: Int,
            ): Int =
                when (item?.type) {
                    BaseItemKind.GENRE -> 1
                    BaseItemKind.STUDIO -> 2
                    else -> 0
                }

            override fun create(
                parent: ViewGroup,
                viewType: Int,
            ): BaseCardView =
                when (viewType) {
                    1 -> GenreCardView(parent.context)
                    2 -> StudioCardView(parent.context)
                    else -> BannerCardView(parent.context)
                }

            override fun key(
                item: BaseItem?,
                position: Int,
            ): Any = position

            override fun bind(
                card: BaseCardView,
                item: BaseItem?,
                position: Int,
                cellWidthPx: Int,
            ) {
                val options = row()?.viewOptions ?: HomeRowViewOptions()
                val style = fragment.pageStyle.forHomeRowCard(options, rowAccentFor(item), null)
                val heightPx = card.context.dp(options.heightDp)
                when (card) {
                    is GenreCardView -> {
                        card.bind(item?.id, item?.name, item?.imageUrlOverride, style, heightPx = heightPx)
                    }

                    is StudioCardView -> {
                        card.bind(item?.id, item?.name, item?.imageUrlOverride, style, heightPx = heightPx)
                    }

                    is BannerCardView -> {
                        val episode = item?.type == BaseItemKind.EPISODE
                        card.bind(
                            item = item,
                            style = style,
                            name = item?.data?.seriesName ?: item?.name,
                            cardHeightDp = options.heightDp.toFloat(),
                            aspectRatio = (if (episode) options.episodeAspectRatio else options.aspectRatio).ratio,
                            cornerText = item?.ui?.episodeUnplayedCornerText,
                            played = item?.data?.userData?.played ?: false,
                            favorite = item?.favorite ?: false,
                            playPercent = item?.data?.userData?.playedPercentage ?: 0.0,
                            imageType = (if (episode) options.episodeImageType else options.imageType).imageType,
                            imageScale = CardImageScale.from(if (episode) options.episodeContentScale else options.contentScale),
                            useSeriesForPrimary = options.useSeries,
                            withTitle = options.showTitles,
                            title = item?.title,
                            subtitle = item?.subtitle,
                        )
                    }
                }
            }
        }

        private inner class RowsAdapter : RecyclerView.Adapter<RowHolder>() {
            init {
                setHasStableIds(true)
            }

            override fun getItemCount(): Int = rows.size

            override fun getItemId(position: Int): Long = position.toLong()

            override fun getItemViewType(position: Int): Int =
                when (val row = rows[position]) {
                    is HomeRowLoadingState.Success -> if (row.items.isNotEmpty()) TYPE_ROW else TYPE_EMPTY
                    else -> TYPE_PLACEHOLDER
                }

            override fun onCreateViewHolder(
                parent: ViewGroup,
                viewType: Int,
            ): RowHolder {
                val view: View =
                    when (viewType) {
                        TYPE_ROW -> CardRowView(parent.context)
                        TYPE_PLACEHOLDER -> PlaceholderRowView(parent.context)
                        else -> View(parent.context)
                    }
                view.layoutParams = RecyclerView.LayoutParams(MATCH, if (viewType == TYPE_EMPTY) 0 else WRAP)
                val holder = RowHolder(view)
                view.addOnLayoutChangeListener { v, _, top, _, bottom, _, _, _, _ ->
                    val index = holder.bindingAdapterPosition
                    if (index == RecyclerView.NO_POSITION) return@addOnLayoutChangeListener
                    val lp = v.layoutParams as? ViewGroup.MarginLayoutParams
                    val h = bottom - top + (lp?.bottomMargin ?: 0)
                    val titleH = (v as? CardRowView)?.titleView?.height
                    var changed = rowHeights.put(index, h) != h
                    if (titleH != null) changed = (titleHeights.put(index, titleH) != titleH) || changed
                    if (changed) post { updateHeaderBudget() }
                }
                if (view is PlaceholderRowView) {
                    view.setOnFocusChangeListener { _, focused -> if (focused) onCardFocused(holder.bindingAdapterPosition, 0) }
                }
                return holder
            }

            override fun onBindViewHolder(
                holder: RowHolder,
                position: Int,
            ) {
                val row = rows[position]
                val view = holder.itemView
                val gap =
                    if (neon && row is HomeRowLoadingState.Success) row.viewOptions.rowGapDp else 8
                (view.layoutParams as RecyclerView.LayoutParams).bottomMargin =
                    if (row is HomeRowLoadingState.Success && row.items.isEmpty()) 0 else view.context.dp(gap)
                val accent = fragment.pageStyle.sectionAccent
                when (row) {
                    is HomeRowLoadingState.Loading, is HomeRowLoadingState.Pending -> {
                        (view as PlaceholderRowView).bind(
                            row.title.getString(context),
                            context.getString(R.string.loading),
                            accent,
                            insetStartPx = insetStart,
                            insetEndPx = insetEnd,
                        )
                    }

                    is HomeRowLoadingState.Error -> {
                        (view as PlaceholderRowView).bind(
                            row.title.getString(context),
                            row.localizedMessage,
                            accent,
                            isError = true,
                            insetStartPx = insetStart,
                            insetEndPx = insetEnd,
                        )
                    }

                    is HomeRowLoadingState.Success -> {
                        val rowView = view as? CardRowView ?: return
                        val (title, kicker) = if (splitTitles && neon) heading(row) else row.title.getString(context) to null
                        val options = row.viewOptions
                        rowView.setup(
                            title = title,
                            titleAccent = accent,
                            pageStyle = fragment.pageStyle,
                            style = CardRowStyle.forHomeRow(options, insetStart, insetEnd),
                            kicker = kicker,
                        )
                        val adapter = adapterFor(position)
                        rowView.setCards(
                            adapter,
                            row.items,
                            viewMore =
                                if (row.showViewMore) {
                                    val viewMoreStyle = fragment.pageStyle.forHomeRowCard(options, accent, null)
                                    val episode = row.items.lastOrNull()?.type == BaseItemKind.EPISODE
                                    ViewMoreSpec(
                                        bind = {
                                            it.bind(
                                                viewMoreStyle,
                                                aspectRatio = (if (episode) options.episodeAspectRatio else options.aspectRatio).ratio,
                                                heightPx = context.dp(options.heightDp),
                                                showTitle = options.showTitles,
                                            )
                                        },
                                        onClick = { viewModel.onClickViewMore(RowColumn(position, row.items.size), row) },
                                        onFocus = { onCardFocused(position, row.items.size) },
                                    )
                                } else {
                                    null
                                },
                        )
                        rowSelections[position]?.let { if (rowView.strip.selectedPosition != it) rowView.strip.selectedPosition = it }
                    }
                }
            }

            override fun onViewRecycled(holder: RowHolder) {
                val index = holder.bindingAdapterPosition
                val view = holder.itemView
                if (view is CardRowView && index != RecyclerView.NO_POSITION) rowSelections[index] = view.strip.selectedPosition
            }
        }
    }

    private class RowHolder(
        view: View,
    ) : RecyclerView.ViewHolder(view)

    private companion object {
        const val KIND_ERROR = 0
        const val KIND_PENDING = 1
        const val KIND_LOADING = 2
        const val KIND_SUCCESS = 3

        const val TYPE_ROW = 0
        const val TYPE_PLACEHOLDER = 1
        const val TYPE_EMPTY = 2
    }
}
