package com.github.damontecres.wholphin.leanback.home

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.leanback.widget.BaseGridView
import androidx.leanback.widget.VerticalGridView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.HomeRowConfig
import com.github.damontecres.wholphin.data.model.JustWatchHomeList
import com.github.damontecres.wholphin.leanback.shell.CoachMarkTargets
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.CardDimens
import com.github.damontecres.wholphin.leanback.theme.PageStyle
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.widget.card.CardCallbacks
import com.github.damontecres.wholphin.leanback.widget.card.CardRowAdapter
import com.github.damontecres.wholphin.leanback.widget.card.CardRowStyle
import com.github.damontecres.wholphin.leanback.widget.card.CardRowView
import com.github.damontecres.wholphin.leanback.widget.card.CardViewPool
import com.github.damontecres.wholphin.leanback.widget.card.PlaceholderRowView
import com.github.damontecres.wholphin.leanback.widget.card.ViewMoreSpec
import com.github.damontecres.wholphin.leanback.widget.card.clipToOwnHeight
import com.github.damontecres.wholphin.leanback.widget.card.isPlayKeyUp
import com.github.damontecres.wholphin.leanback.widget.card.steadyWhenHeld
import com.github.damontecres.wholphin.leanback.widget.control.CircularProgressView
import com.github.damontecres.wholphin.ui.coachmark.CoachMarkIds
import com.github.damontecres.wholphin.ui.coachmark.CoachMarkTour
import com.github.damontecres.wholphin.ui.data.RowColumn
import com.github.damontecres.wholphin.ui.main.KidRowFocus
import com.github.damontecres.wholphin.ui.main.settings.Library
import com.github.damontecres.wholphin.ui.playback.playable
import com.github.damontecres.wholphin.util.HomeRowLoadingState
import com.github.damontecres.wholphin.util.LoadingState
import org.jellyfin.sdk.model.api.BaseItemKind
import timber.log.Timber
import kotlin.math.max
import kotlin.math.min

/** What a [HomeRowsView] does on its cards (`HomePageContent`'s callbacks) */
interface HomeRowsCallbacks {
    fun onClickItem(
        position: RowColumn,
        item: BaseItem,
    )

    fun onLongClickItem(
        position: RowColumn,
        item: BaseItem,
    )

    /** The remote's play key on a playable card */
    fun onClickPlay(
        position: RowColumn,
        item: BaseItem,
    )

    fun onClickViewMore(
        position: RowColumn,
        row: HomeRowLoadingState.Success,
    ) {}

    /** Every focused card (the page's `position`) */
    fun onFocusPosition(position: RowColumn) {}

    /** The focused card's item, for the backdrop */
    fun onUpdateBackdrop(item: BaseItem) {}

    /** Take focus on [view] for content that arrived later (`DestinationFragment.requestInitialFocus`) */
    fun requestInitialFocus(view: View): Boolean = view.requestFocus()
}

/**
 * Reordering one Home row: LEFT / RIGHT move the selected card ([onMove] with -1 or 1), OK places it ([onDone]),
 * UP / DOWN stay in the row. BACK (cancel) is the page's.
 */
class HomeRowReorder(
    val row: Int,
    val index: Int,
    val onMove: (delta: Int) -> Unit,
    val onDone: () -> Unit,
) {
    fun onKey(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (down) onMove(-1)
                true
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (down) onMove(1)
                true
            }

            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                true
            }

            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event.action == KeyEvent.ACTION_UP) onDone()
                true
            }

            else -> {
                false
            }
        }
    }
}

/**
 * `HomePageContent` without Compose: the focused item's header ([HomeRowsHeader], scaled to fit) over a Leanback list
 * of Home rows (titled card rows; focusable "Loading…", error or "No results" rows), with a spinner at the top right
 * while the rows refresh. Used by Home; reusable by any page that showed `HomePageContent` (Recommended tabs,
 * collection rows, the Home settings preview).
 *
 * Layout (WeaselTV): once the focused row is measured, the header gets exactly the height left after the focused row,
 * the next row's title and 16dp, so the next kicker, title and rule always show and shorter copy never moves the
 * rows; until then it is invisible. Focusing a row scrolls the list so the row's top (its kicker) sits 4dp below the
 * header (other themes: the focused card 50dp below), or lower when the row would not fit with 8dp below it — the old
 * Home bring-into-view spec. Rows keep their own horizontal position and focused card (focus enters a row at its last
 * focused card). BACK, the side menu and page focus are the hosting page's.
 */
@SuppressLint("ViewConstructor")
class HomeRowsView(
    context: Context,
) : FrameLayout(context) {
    var pageStyle: PageStyle = PageStyle()
        set(value) {
            if (field != value) {
                field = value
                (header as? HomeHeaderView)?.pageStyle = value
                refreshAll()
            }
        }

    /** The user's libraries: row headings and accents come from them */
    var libraries: List<Library> = emptyList()
        set(value) {
            if (field != value) {
                field = value
                if (rows.isNotEmpty()) refreshAll()
            }
        }

    /** WeaselTV: Home's rows show a kicker and a title split from the row's meaning (`splitHomeTitles`) */
    var splitHomeTitles: Boolean = false

    /** Rows end with a View more card (when the row allows it) */
    var showViewMore: Boolean = true

    /** Rows that loaded empty show "No results" instead of nothing (the settings preview) */
    var showEmptyRows: Boolean = false

    /** Take focus on the first row with content once it arrives */
    var takeFocus: Boolean = true

    /** WeaselTV heading override (title, kicker) for a row (`rowHeading`) */
    var rowHeading: ((HomeRowLoadingState.Success) -> Pair<String, String?>)? = null

    /** The refresh spinner sits clear of the clock when it shows */
    var showClock: Boolean = false
        set(value) {
            field = value
            (spinner.layoutParams as LayoutParams).apply {
                val margin = context.dp(if (value) 40 else 20)
                setMargins(margin, margin, margin, margin)
            }
            spinner.requestLayout()
        }

    lateinit var callbacks: HomeRowsCallbacks

    /** The shell's coach-mark tour, when this flavor has one (Home only) */
    var coachMarkTour: CoachMarkTour? = null

    /** The row being reordered (Streaming Services): its kicker says how, keys move the card */
    var reorder: HomeRowReorder? = null
        set(value) {
            val old = field
            field = value
            old?.row?.let { adapter.notifyItemChanged(it) }
            value?.row?.takeIf { it != old?.row }?.let { adapter.notifyItemChanged(it) }
            value?.let { r -> rowViewAt(r.row)?.cardRow?.focusCard(r.index) }
        }

    /** The focused card (row, column); (-1, -1) before any */
    var position: RowColumn = RowColumn(-1, -1)
        private set

    private val neon = AppTheme.isNeon
    private val headerFit = HeaderFitLayout(context)
    private var header: HomeRowsHeader? = null
    private val grid = HomeRowsGrid(context)
    private val adapter = RowsAdapter()
    private val spinner = CircularProgressView(context)

    private var rows: List<HomeRowLoadingState> = emptyList()
    private var rowKeys: List<String>? = null
    private var firstFocused = false
    private var shownItem: BaseItem? = null
    private var headerBound = false
    private val kidRowFocus = KidRowFocus()

    // Per row key: card adapter, last selected card, measured row and title heights
    private val cardAdapters = HashMap<Any, CardRowAdapter<BaseItem>>()

    /**
     * The rows' cards, shared: a row scrolled into view takes the cards of rows scrolled out instead of creating its own
     * (every row's [HomeCardPresenter] makes the same card for a view type)
     */
    private val cardPool = CardViewPool()
    private val selectedCards = HashMap<Any, Int>()
    private val rowHeights = HashMap<Any, Int>()
    private val titleHeights = HashMap<Any, Int>()

    /** Header over rows; measures the header budget first */
    private val column =
        object : android.widget.LinearLayout(context) {
            override fun onMeasure(
                widthMeasureSpec: Int,
                heightMeasureSpec: Int,
            ) {
                updateHeaderBudget(MeasureSpec.getSize(heightMeasureSpec))
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }

    init {
        clipChildren = false
        clipToPadding = false
        column.orientation = android.widget.LinearLayout.VERTICAL
        column.clipChildren = false
        column.clipToPadding = false
        column.addView(headerFit, android.widget.LinearLayout.LayoutParams(MATCH, WRAP))
        // WeaselTV: the rows reach into the gutter at the side menu so their section rules do (none at the end on Home)
        val insetStart = if (neon) context.dp(CardDimens.NEON_RULE_INSET_START_DP) else 0
        column.addView(
            grid,
            android.widget.LinearLayout
                .LayoutParams(MATCH, 0, 1f)
                .apply { marginStart = -insetStart },
        )
        addView(column, LayoutParams(MATCH, MATCH))
        addView(spinner, LayoutParams(context.dp(40), context.dp(40), Gravity.TOP or Gravity.END))
        spinner.visibility = GONE
        showClock = false
        grid.adapter = adapter
        grid.rowsView = this
        grid.setOnChildLaidOutListener { _, view, laidOut, _ ->
            val pending = pendingFocus
            if (pending != null && pending.first == laidOut && view.hasFocusable()) {
                pendingFocus = null
                takeFocus(view, pending.second)
            }
        }
    }

    /** The header above the rows */
    val headerView: HomeRowsHeader? get() = header

    /** Bind the header again (eg the logo or display toggle preferences changed) */
    fun refreshHeader() {
        headerBound = false
        bindHeader()
    }

    /** Start from a focused card kept from before (the rows were replaced by a loading page) */
    fun restorePosition(position: RowColumn) {
        this.position = position
    }

    /** Use [header] above the rows (default: none until set) */
    fun setHeader(header: HomeRowsHeader) {
        this.header?.let { CoachMarkTargets.unregisterAll(it.view) }
        this.header = header
        headerFit.removeAllViews()
        headerFit.addView(header.view)
        (header as? HomeHeaderView)?.pageStyle = pageStyle
        headerBound = false
        bindHeader()
        CoachMarkTargets.register(CoachMarkIds.HOME_HEADER, headerFit)
    }

    /**
     * Show [rows] ([keys]: a kids Home's stable row keys, else null for positional rows) and the refresh state
     * ([refreshState] Pending / Loading shows the spinner).
     */
    fun submit(
        rows: List<HomeRowLoadingState>,
        keys: List<String>?,
        refreshState: LoadingState?,
    ) {
        val old = this.rows
        val oldKeys = rowKeys
        this.rows = rows
        rowKeys = keys?.takeIf { it.size == rows.size }
        spinner.visibility =
            if (refreshState == LoadingState.Pending || refreshState == LoadingState.Loading) VISIBLE else GONE
        DiffUtil
            .calculateDiff(
                object : DiffUtil.Callback() {
                    override fun getOldListSize() = old.size

                    override fun getNewListSize() = rows.size

                    override fun areItemsTheSame(
                        oldItemPosition: Int,
                        newItemPosition: Int,
                    ) = keyOf(oldItemPosition, oldKeys) == keyOf(newItemPosition, rowKeys)

                    override fun areContentsTheSame(
                        oldItemPosition: Int,
                        newItemPosition: Int,
                    ) = oldItemPosition == newItemPosition && old[oldItemPosition] == rows[newItemPosition]
                },
                false,
            ).dispatchUpdatesTo(adapter)
        followKidFocus()
        leaveEmptiedRow()
        bindHeader()
        takeFirstFocus()
        checkTour()
    }

    /**
     * The focused row finished loading with nothing in it (a kids Watch Again or a Continue Watching that the refresh
     * emptied: Home hides it, and its focused card went with it). Move to the nearest row with content, the next one
     * first, and give it focus if the page lost it, so a card stays highlighted.
     */
    private fun leaveEmptiedRow() {
        val row = position.row
        val state = rows.getOrNull(row) as? HomeRowLoadingState.Success ?: return
        if (state.items.isNotEmpty()) return
        val target =
            ((row + 1) until rows.size).firstOrNull { hasContent(it) } ?: (row - 1 downTo 0).firstOrNull { hasContent(it) } ?: return
        setPosition(RowColumn(target, 0))
        focusRow(target, force = false)
    }

    /** Re-bind every row (theme or style change) */
    fun refreshAll() {
        @Suppress("NotifyDataSetChanged")
        adapter.notifyDataSetChanged()
        headerBound = false
        bindHeader()
    }

    /**
     * Back to the top: forget the focused card and focus the first row with content (Home opened again from the side
     * menu: `homeVisit` changed). [focusNow] takes focus at once (the page is being shown).
     */
    fun resetToTop(focusNow: Boolean) {
        position = RowColumn(-1, -1)
        firstFocused = false
        bindHeader()
        if (focusNow) {
            val first = rows.indexOfFirst(::hasContent)
            if (first >= 0) {
                firstFocused = true
                focusRow(first, force = true)
            }
        } else {
            takeFirstFocus()
        }
    }

    /** Focus the row the page is on (the old `onEnter`), or the first row with content */
    fun focusCurrentRow(): Boolean {
        val target = position.row.takeIf { it >= 0 } ?: rows.indexOfFirst(::hasContent)
        if (target < 0) return false
        focusRow(target, force = true)
        return true
    }

    private fun keyOf(
        index: Int,
        keys: List<String>?,
    ): Any = keys?.getOrNull(index) ?: index

    private fun rowKey(index: Int): Any = keyOf(index, rowKeys)

    private fun hasContent(row: HomeRowLoadingState?): Boolean = row is HomeRowLoadingState.Success && row.items.isNotEmpty()

    private fun hasContent(index: Int): Boolean = hasContent(rows.getOrNull(index))

    private val keyed: Boolean get() = rowKeys != null

    // Focus ------------------------------------------------------------------------------------------------------------

    /**
     * Waiting for the first home row to load, then focus on it. A kids Home's rows load in parallel: only once every
     * row above it is done, so it opens on the top row, not on the first to arrive.
     */
    private fun takeFirstFocus() {
        if (!takeFocus || firstFocused || rows.isEmpty()) return
        if (position.row >= 0) {
            firstFocused = true
            focusRow(position.row.coerceIn(0, rows.lastIndex), force = false)
            return
        }
        val first = rows.indexOfFirst(::hasContent)
        if (first < 0) return
        if (keyed && !rows.take(first).all { it.completed }) return
        firstFocused = true
        focusRow(first, force = false)
    }

    /** Select row [index] (scrolling to it) and focus its last focused card */
    private fun focusRow(
        index: Int,
        force: Boolean,
    ) {
        grid.setSelectedPosition(index)
        // With focus in the list, Leanback moves it to the selected row itself
        if (grid.hasFocus()) return
        val view = grid.findViewHolderForAdapterPosition(index)?.itemView
        if (view != null && !view.isLayoutRequested && view.hasFocusable()) {
            pendingFocus = null
            takeFocus(view, force)
        } else {
            // Not laid out yet: once it is (one request, when the row exists)
            pendingFocus = index to force
        }
    }

    private fun takeFocus(
        view: View,
        force: Boolean,
    ) {
        if (force) view.requestFocus() else callbacks.requestInitialFocus(view)
    }

    private var pendingFocus: Pair<Int, Boolean>? = null

    private val relayout = Runnable { column.requestLayout() }

    /**
     * Kid content rules: a kids Home's focused row follows its row when rows are added or removed around it, or moves
     * (with focus) to the nearest row with content when it's gone, also once that content arrives later.
     */
    private fun followKidFocus() {
        val keys = rowKeys ?: return
        when (val action = kidRowFocus.onRows(keys, position.row) { hasContent(it) }) {
            KidRowFocus.Action.None -> {}

            is KidRowFocus.Action.Follow -> {
                setPosition(position.copy(row = action.row))
            }

            is KidRowFocus.Action.Recover -> {
                setPosition(RowColumn(action.row, 0))
                focusRow(action.row, force = true)
            }
        }
    }

    private fun setPosition(newPosition: RowColumn) {
        if (newPosition == position) return
        val rowChanged = newPosition.row != position.row
        position = newPosition
        rowKeys?.let { kidRowFocus.onFocusedRow(newPosition.row, it) }
        callbacks.onFocusPosition(newPosition)
        bindHeader()
        if (rowChanged) {
            registerCoachRow()
            column.requestLayout()
        }
    }

    private fun focusedItem(): BaseItem? = (rows.getOrNull(position.row) as? HomeRowLoadingState.Success)?.items?.getOrNull(position.column)

    private fun bindHeader() {
        val item = focusedItem()
        val h = header ?: return
        if (!headerBound || item != shownItem) {
            h.bind(item)
            headerBound = true
        }
        if (item != null && item != shownItem) callbacks.onUpdateBackdrop(item)
        shownItem = item
    }

    /**
     * The header's height budget (WeaselTV): keep the next kicker, title and rule above the viewport edge, using the
     * actual row measurements so font, card, caption and spacing tuning all share it.
     */
    private fun updateHeaderBudget(totalHeight: Int) {
        val focusedRow = max(position.row, 0)
        val nextRow = ((focusedRow + 1) until rows.size).firstOrNull { hasContent(it) }
        val focusedRowHeight = rowHeights[rowKey(focusedRow)]
        val focusedTitleHeight = titleHeights[rowKey(focusedRow)]
        val nextTitleHeight = nextRow?.let { titleHeights[rowKey(it)] ?: focusedTitleHeight }
        val oldMax = headerFit.maxHeightPx
        val oldFill = headerFit.fillHeight
        if (neon && focusedRowHeight != null) {
            headerFit.maxHeightPx = max(totalHeight - (focusedRowHeight + (nextTitleHeight ?: 0)) - context.dp(16), 1)
            // Reserve the measured budget for every title in this row: shorter copy must not lift the cards
            headerFit.fillHeight = true
            headerFit.alpha = 1f
        } else {
            headerFit.maxHeightPx = Int.MAX_VALUE
            headerFit.fillHeight = false
            // Don't show the header at its unfitted size before the focused row is measured
            headerFit.alpha = if (neon) 0f else 1f
        }
        if (oldMax != headerFit.maxHeightPx || oldFill != headerFit.fillHeight) headerFit.forceLayout()
    }

    private fun onRowMeasured(
        key: Any,
        height: Int,
        titleHeight: Int?,
    ) {
        val changed = rowHeights[key] != height || (titleHeight != null && titleHeights[key] != titleHeight)
        rowHeights[key] = height
        if (titleHeight != null) titleHeights[key] = titleHeight
        // Measured during layout: the header budget is recomputed in the next pass
        if (changed) {
            removeCallbacks(relayout)
            post(relayout)
        }
    }

    // Coach-mark tour --------------------------------------------------------------------------------------------------

    /** The view registered as the tour's HOME_ROW target */
    private var coachRowTarget: View? = null

    private fun registerCoachRow(holder: RowHolder? = rowViewAt(position.row)) {
        if (!CoachMarkTargets.enabled || holder == null) return
        val target = holder.cardRow?.strip ?: holder.placeholder ?: return
        if (target === coachRowTarget) return
        coachRowTarget?.let { CoachMarkTargets.unregister(CoachMarkIds.HOME_ROW, it) }
        coachRowTarget = target
        // The tour spotlights the focused row's posters, leaving room above them for its card
        CoachMarkTargets.register(CoachMarkIds.HOME_ROW, target)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (header != null) CoachMarkTargets.register(CoachMarkIds.HOME_HEADER, headerFit)
        coachRowTarget?.let { CoachMarkTargets.register(CoachMarkIds.HOME_ROW, it) }
    }

    override fun onDetachedFromWindow() {
        CoachMarkTargets.unregister(CoachMarkIds.HOME_HEADER, headerFit)
        coachRowTarget?.let { CoachMarkTargets.unregister(CoachMarkIds.HOME_ROW, it) }
        super.onDetachedFromWindow()
    }

    /**
     * The WeaselPlex tour starts on Trending Movies, the same row for everyone: focus its first poster's row, so the
     * details at the top match it too. Without that row, it starts on the focused row once the rows have loaded.
     */
    fun checkTour() {
        val tour = coachMarkTour?.takeIf { CoachMarkTargets.enabled } ?: return
        if (!tour.pending || tour.homeRowReady) return
        // After Home's own first focus, which would otherwise take it back
        if (!firstFocused && takeFocus) return
        val chartRow =
            rows.indexOfFirst {
                hasContent(it) &&
                    ((it as HomeRowLoadingState.Success).rowType as? HomeRowConfig.JustWatch)?.list == JustWatchHomeList.TRENDING_MOVIES
            }
        val loaded = rows.isNotEmpty() && rows.all { it.completed }
        if (chartRow >= 0) {
            focusRow(chartRow, force = true)
            tour.homeRowReady = true
        } else if (loaded) {
            tour.homeRowReady = true
        }
    }

    /** Once the tour is closed, Home goes back to its top row */
    fun onTourHomeToTop() {
        val tour = coachMarkTour ?: return
        if (!tour.homeToTop) return
        tour.homeToTop = false
        val first = rows.indexOfFirst(::hasContent)
        if (first >= 0) focusRow(first, force = true)
    }

    // Alignment --------------------------------------------------------------------------------------------------------

    /**
     * The old Home `BringIntoViewSpec` for the row of [focused]: the target's top (WeaselTV: the whole row from its
     * kicker; other themes: the focused card or placeholder) `spaceAbove` below the list's top (4dp / 50dp), but no
     * lower than leaves 8dp under it.
     */
    internal fun alignFor(
        rowView: View,
        focused: View,
    ) {
        val holder = grid.getChildViewHolder(rowView) as? RowHolder ?: return
        val target: View
        val alignTop: Int
        val size: Int
        val cardRow = holder.cardRow
        if (neon && cardRow != null) {
            target = cardRow
            alignTop = 0
            size = cardRow.height
        } else {
            target = focused
            alignTop = offsetWithin(focused, rowView)
            size = focused.height
        }
        if (target.height == 0 && size == 0) return
        val spaceAbove = context.dp(if (neon) 4 else 50)
        val clearance = context.dp(8)
        val offset = min(spaceAbove, max(grid.height - grid.paddingTop - size - clearance, 0))
        if (grid.itemAlignmentOffset != alignTop) grid.itemAlignmentOffset = alignTop
        if (grid.windowAlignmentOffset != offset) grid.windowAlignmentOffset = offset
    }

    private fun offsetWithin(
        view: View,
        ancestor: View,
    ): Int {
        var top = 0
        var v: View? = view
        while (v != null && v !== ancestor) {
            top += v.top
            v = v.parent as? View
        }
        return top
    }

    private fun rowViewAt(index: Int): RowHolder? = grid.findViewHolderForAdapterPosition(index) as? RowHolder

    // Rows -------------------------------------------------------------------------------------------------------------

    private inner class RowsAdapter : RecyclerView.Adapter<RowHolder>() {
        private val ids = HashMap<Any, Long>()

        init {
            // Rows keep their views (and focus) through updates and rebinds
            setHasStableIds(true)
        }

        override fun getItemCount(): Int = rows.size

        override fun getItemId(position: Int): Long = ids.getOrPut(rowKey(position)) { ids.size.toLong() }

        override fun getItemViewType(position: Int): Int =
            when (val row = rows[position]) {
                is HomeRowLoadingState.Success -> {
                    if (row.items.isNotEmpty()) {
                        TYPE_CARDS
                    } else if (showEmptyRows) {
                        TYPE_PLACEHOLDER
                    } else {
                        TYPE_EMPTY
                    }
                }

                else -> {
                    TYPE_PLACEHOLDER
                }
            }

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): RowHolder = RowHolder(RowContainer(parent.context), viewType)

        override fun onBindViewHolder(
            holder: RowHolder,
            position: Int,
        ) = holder.bind(position)

        override fun onViewRecycled(holder: RowHolder) = holder.recycle()
    }

    /** A row's view: the card row or placeholder, with the gap below it; reports its height and keys while reordering */
    private inner class RowContainer(
        context: Context,
    ) : FrameLayout(context) {
        var holder: RowHolder? = null

        init {
            clipChildren = false
            clipToPadding = false
            layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            val r = reorder
            val h = holder
            if (r != null && h != null && h.bindingAdapterPosition == r.row && r.onKey(event)) return true
            return super.dispatchKeyEvent(event)
        }

        override fun onLayout(
            changed: Boolean,
            left: Int,
            top: Int,
            right: Int,
            bottom: Int,
        ) {
            super.onLayout(changed, left, top, right, bottom)
            holder?.reportSize()
        }
    }

    private inner class RowHolder(
        val container: RowContainer,
        val type: Int,
    ) : RecyclerView.ViewHolder(container) {
        val cardRow: CardRowView? = if (type == TYPE_CARDS) CardRowView(container.context) else null
        val placeholder: PlaceholderRowView? = if (type == TYPE_PLACEHOLDER) PlaceholderRowView(container.context) else null
        private var boundKey: Any? = null

        init {
            container.holder = this
            cardRow?.strip?.setRecycledViewPool(cardPool)
            cardRow?.let { container.addView(it, LayoutParams(MATCH, WRAP)) }
            placeholder?.let { container.addView(it, LayoutParams(MATCH, WRAP)) }
            if (type == TYPE_EMPTY) container.layoutParams = RecyclerView.LayoutParams(MATCH, 0)
        }

        fun bind(index: Int) {
            val row = rows[index]
            val key = rowKey(index)
            val keyChanged = boundKey != key
            if (keyChanged) saveSelection()
            boundKey = key
            val ctx = container.context
            val insetStart = if (neon) ctx.dp(CardDimens.NEON_RULE_INSET_START_DP) else 0
            when (type) {
                TYPE_CARDS -> {
                    bindCards(index, row as HomeRowLoadingState.Success, key, keyChanged, insetStart)
                }

                TYPE_PLACEHOLDER -> {
                    val (message, error) =
                        when (row) {
                            is HomeRowLoadingState.Error -> row.localizedMessage to true
                            is HomeRowLoadingState.Success -> ctx.getString(R.string.no_results) to false
                            else -> ctx.getString(R.string.loading) to false
                        }
                    placeholder!!.bind(
                        title = row.title.getString(ctx),
                        message = message,
                        accent = pageStyle.sectionAccent,
                        isError = error,
                        focusableRow = true,
                        insetStartPx = insetStart,
                        insetEndPx = 0,
                    )
                    container.setPadding(0, 0, 0, ctx.dp(8))
                }

                else -> {
                    container.setPadding(0, 0, 0, 0)
                }
            }
            if (index == this@HomeRowsView.position.row) registerCoachRow(this)
        }

        private fun bindCards(
            index: Int,
            row: HomeRowLoadingState.Success,
            key: Any,
            keyChanged: Boolean,
            insetStart: Int,
        ) {
            val ctx = container.context
            val cards = cardRow!!
            val options = row.viewOptions
            val heading =
                rowHeading?.takeIf { neon }?.invoke(row)
                    ?: if (splitHomeTitles && neon) {
                        homeRowHeading(ctx, row.rowType, libraries, row.title.getString(ctx))
                    } else {
                        row.title.getString(ctx) to null
                    }
            val reordering = reorder?.row == index
            cards.setup(
                title = heading.first,
                titleAccent = homeRowAccentFor(row.rowType, libraries, pageStyle.sectionAccent),
                pageStyle = pageStyle,
                style = CardRowStyle.forHomeRow(options, insetStart, 0),
                kicker = if (reordering) ctx.getString(R.string.reorder_hint) else heading.second,
            )
            val adapter = cardAdapters.getOrPut(key) { CardRowAdapter(HomeCardPresenter()) }
            val presenter = adapter.presenter as HomeCardPresenter
            val configChanged =
                presenter.pageStyle != pageStyle || presenter.viewOptions != options || presenter.rowType != row.rowType ||
                    presenter.libraries != libraries
            presenter.pageStyle = pageStyle
            presenter.viewOptions = options
            presenter.rowType = row.rowType
            presenter.libraries = libraries
            // Stable card identities, so focus moves with a card being reordered
            presenter.keyByItem = isStreamingRow(row.rowType)
            adapter.callbacks =
                CardCallbacks(
                    onClick = { column, item -> callbacks.onClickItem(RowColumn(bindingAdapterPosition, column), item) },
                    onLongClick = { column, item -> callbacks.onLongClickItem(RowColumn(bindingAdapterPosition, column), item) },
                    onFocus = { column, _ ->
                        boundKey?.let { selectedCards[it] = column }
                        setPosition(RowColumn(bindingAdapterPosition, column))
                    },
                    onKey = { _, item, event ->
                        if (isPlayKeyUp(event) && item?.type?.playable == true) {
                            Timber.v("Clicked play on ${item.id}")
                            callbacks.onClickPlay(this@HomeRowsView.position, item)
                            true
                        } else {
                            false
                        }
                    },
                )
            val viewMore =
                if (showViewMore && row.showViewMore) {
                    ViewMoreSpec(
                        bind = { presenter.bindViewMore(it, isEpisode = row.items.last()?.type == BaseItemKind.EPISODE) },
                        onClick = {
                            val r = rows.getOrNull(bindingAdapterPosition) as? HomeRowLoadingState.Success ?: return@ViewMoreSpec
                            callbacks.onClickViewMore(RowColumn(bindingAdapterPosition, r.items.size), r)
                        },
                        onFocus = {
                            val size = (rows.getOrNull(bindingAdapterPosition) as? HomeRowLoadingState.Success)?.items?.size ?: 0
                            boundKey?.let { selectedCards[it] = size }
                            setPosition(RowColumn(bindingAdapterPosition, size))
                        },
                    )
                } else {
                    null
                }
            cards.setCards(adapter, row.items, viewMore)
            if (configChanged && adapter.itemCount > 0) adapter.notifyItemRangeChanged(0, adapter.itemCount)
            if (keyChanged) selectedCards[key]?.let { cards.strip.selectedPosition = it.coerceIn(0, max(adapter.itemCount - 1, 0)) }
            reorder?.takeIf { it.row == index }?.let { cards.focusCard(it.index) }
            container.setPadding(0, 0, 0, if (neon) ctx.dp(options.rowGapDp) else ctx.dp(8))
        }

        fun reportSize() {
            val key = boundKey ?: return
            if (type == TYPE_EMPTY) return
            onRowMeasured(key, container.height, cardRow?.titleView?.height)
        }

        private fun saveSelection() {
            val key = boundKey ?: return
            cardRow?.let { selectedCards[key] = it.selectedIndex }
        }

        fun recycle() {
            saveSelection()
            cardRow?.strip?.adapter = null
            boundKey = null
            val target = cardRow?.strip ?: placeholder
            if (target != null && target === coachRowTarget) {
                CoachMarkTargets.unregister(CoachMarkIds.HOME_ROW, target)
                coachRowTarget = null
            }
        }
    }

    /**
     * The row list: a Leanback grid aligning the focused row as [alignFor] says, with `Cards.height2x3` of room below
     * the last row (the old content padding), drawing the focused row last so its glow is on top.
     */
    private class HomeRowsGrid(
        context: Context,
    ) : VerticalGridView(context) {
        var rowsView: HomeRowsView? = null

        init {
            clipChildren = false
            clipToPadding = false
            itemAnimator = null
            setHasFixedSize(false)
            windowAlignment = BaseGridView.WINDOW_ALIGN_BOTH_EDGE
            windowAlignmentOffsetPercent = 0f
            windowAlignmentOffset = context.dp(if (AppTheme.isNeon) 4 else 50)
            itemAlignmentOffsetPercent = 0f
            itemAlignmentOffset = 0
            isItemAlignmentOffsetWithPadding = false
            setPadding(0, 0, 0, context.dp(CardDimens.HEIGHT_2X3_DP))
            // Rows scrolled up must not draw over the header (the Compose LazyColumn clipped them)
            clipToOwnHeight()
            steadyWhenHeld()
        }

        override fun requestChildFocus(
            child: View?,
            focused: View?,
        ) {
            if (child != null && focused != null) rowsView?.alignFor(child, focused)
            super.requestChildFocus(child, focused)
        }
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val TYPE_CARDS = 0
        const val TYPE_PLACEHOLDER = 1
        const val TYPE_EMPTY = 2
    }
}
