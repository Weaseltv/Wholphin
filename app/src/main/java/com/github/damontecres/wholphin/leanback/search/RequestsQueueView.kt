package com.github.damontecres.wholphin.leanback.search

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.dispose
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.target
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.leanback.theme.AppFonts
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.NeonColors
import com.github.damontecres.wholphin.leanback.theme.RequestActivityPalette
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.dpF
import com.github.damontecres.wholphin.leanback.theme.withAlpha
import com.github.damontecres.wholphin.leanback.widget.control.ListLeading
import com.github.damontecres.wholphin.leanback.widget.dialog.MenuDialog
import com.github.damontecres.wholphin.leanback.widget.dialog.MenuItem
import com.github.damontecres.wholphin.services.RequestProgressState
import com.github.damontecres.wholphin.services.RequestStatusItem
import com.github.damontecres.wholphin.services.RequestsState
import com.github.damontecres.wholphin.ui.discover.ActivityProgress
import com.github.damontecres.wholphin.ui.discover.ActivityRow
import com.github.damontecres.wholphin.ui.discover.ActivitySection
import com.github.damontecres.wholphin.ui.discover.SeerrRequestsViewModel
import com.github.damontecres.wholphin.ui.discover.activityRow
import com.github.damontecres.wholphin.ui.discover.liveProgress
import com.github.damontecres.wholphin.ui.discover.orderedActivityRows
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.Instant
import kotlin.math.abs

// Sizes for the 1080p reference frame (owner mockups 2026-11-03; mockup px / 2). The table spans the content width,
// and its five columns share it in these proportions.
private const val TITLE_WEIGHT = 4.7f
private const val TYPE_WEIGHT = .85f
private const val STATUS_WEIGHT = 1.95f
private const val PROGRESS_WEIGHT = 1.45f
private const val ACTION_WEIGHT = 1.35f
private const val POSTER_WIDTH_DP = 28f
private const val POSTER_HEIGHT_DP = 42f
private const val POSTER_GAP_DP = 11f
private const val ROW_HEIGHT_DP = 49f
private const val ROW_GAP_DP = 6f
private const val ROW_VERTICAL_INSET_DP = 3.5f
private const val ROW_START_INSET_DP = 10f
private const val ROW_END_INSET_DP = 8f
private const val BAR_WIDTH_DP = 75f
private const val BAR_HEIGHT_DP = 6f
private const val FADE_HEIGHT_DP = 40f

/**
 * Space kept above a focused row: the 6dp row gap plus 2dp of the row above, so its focus border never meets the
 * list's top edge and the row above stays laid out for the next Up press.
 */
private const val FOCUS_TOP_CLEARANCE_DP = ROW_GAP_DP + 2f

/** The section colours */
private fun ActivitySection.color(): Int =
    when (this) {
        ActivitySection.ACTIVE -> RequestActivityPalette.ACTIVE
        ActivitySection.WAITING -> RequestActivityPalette.WAITING
        ActivitySection.READY -> RequestActivityPalette.READY
    }

private fun requestPosterKey(
    scope: String?,
    url: String,
    width: Int,
    height: Int,
) = "request-poster|$scope|${width}x$height|$url"

private fun requestPoster(
    context: Context,
    scope: String?,
    url: String,
    width: Int,
    height: Int,
): ImageRequest.Builder {
    val key = requestPosterKey(scope, url, width, height)
    return ImageRequest
        .Builder(context)
        .data(url)
        .size(width, height)
        .memoryCacheKey(key)
        .placeholderMemoryCacheKey(key)
        .crossfade(false)
}

/** Style a text view with the Activity fonts (Exo 2 body / Audiowide display) */
private fun TextView.activityStyle(
    sizeSp: Float,
    color: Int,
    display: Boolean = false,
    weight: Int = 400,
    trackingEm: Float = 0f,
    lineHeightSp: Float? = null,
) {
    typeface = if (display) AppFonts.display(context) else AppFonts.body(context, weight)
    if (weight >= 700 && typeface?.isBold == false && !display) setTypeface(typeface, Typeface.BOLD)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    letterSpacing = trackingEm
    includeFontPadding = false
    setTextColor(color)
    lineHeightSp?.let {
        val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, it, resources.displayMetrics)
        val fontHeight = paint.getFontMetricsInt(null)
        setLineSpacing(px - fontHeight, 1f)
    }
}

/**
 * The Requests ACTIVITY queue (`SeerrRequestsPage`, no-Compose UI): the last action's or the service's error,
 * "Requests are reconnecting…" while offline, then the table (Active, Waiting, Ready to watch), the empty state, or the
 * loading / failure line.
 *
 * Table behaviour kept from the old page: the whole row is the focus stop (OK opens the title or its Requests page,
 * holding OK on an Active or Waiting row offers Cancel request); UP / DOWN move one row at a time, scrolling the next
 * row in first when needed, and UP leaves the table only from its first row; the list scrolls only as far as needed,
 * keeping 8dp above the focused row and keeping it above the 40dp fade at the bottom; arriving with a request id
 * focuses that request's row once; otherwise the queue starts at the top whenever its rows change while focus is
 * elsewhere; after a cancel, focus moves to the row that takes the cancelled one's place.
 */
internal class RequestsQueueView(
    context: Context,
    private val viewModel: SeerrRequestsViewModel,
    private val focusRequestId: Int?,
    /** May the queue take focus now (the page is the top page) */
    private val canTakeFocus: () -> Boolean,
) : LinearLayout(context) {
    private val errorText = TextView(context)
    private val reconnectingText = TextView(context)
    private val statusText = TextView(context)
    private val table = LinearLayout(context)
    private val empty = EmptyStateView(context)
    private val list = QueueList(context)
    private val adapter = QueueAdapter()
    private val timeFormat = android.text.format.DateFormat.getTimeFormat(context)
    private val posterWidth = context.dp(POSTER_WIDTH_DP)
    private val posterHeight = context.dp(POSTER_HEIGHT_DP)

    private var requestsState = RequestsState()
    private var progressState: RequestProgressState? = null
    private var actionError: String? = null
    private var clock: Instant = Instant.now()
    private var rows: List<ActivityRow> = emptyList()
    private var scope: String? = null
    private var focusHandled = focusRequestId == null
    private var cancelled: Pair<Int, Int>? = null
    private var menu: MenuDialog? = null
    private var warmJob: Job? = null
    private var owner: LifecycleOwner? = null

    init {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
        errorText.activityStyle(11f, AppTheme.palette.error)
        reconnectingText.activityStyle(11f, RequestActivityPalette.MUTED)
        reconnectingText.text = "Requests are reconnecting. Your last update is kept here."
        statusText.activityStyle(13f, RequestActivityPalette.MUTED)
        addView(errorText, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(8) })
        addView(reconnectingText, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(8) })

        table.orientation = VERTICAL
        table.clipChildren = false
        table.addView(
            ColumnHeadings(context),
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(18)
                bottomMargin = context.dp(4)
            },
        )
        list.adapter = adapter
        table.addView(list, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(table, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(empty, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(statusText, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(24) })
        render()
    }

    /** Start following the repository with [owner]'s lifecycle (the fragment's view) */
    fun attach(owner: LifecycleOwner) {
        this.owner = owner
    }

    fun setState(
        state: RequestsState,
        progress: RequestProgressState,
        error: String?,
    ) {
        requestsState = state
        progressState = progress.takeIf { state.connected && viewModel.requests.acceptsProgress(it) }
        actionError = error
        render()
    }

    /** The minute tick of the old page's clock */
    fun tick() {
        clock = Instant.now()
        render()
    }

    private fun formatAiringTime(instant: Instant): String = timeFormat.format(java.util.Date.from(instant))

    private fun render() {
        val state = requestsState
        val progress = progressState
        val computed =
            state.items.map { row ->
                val live = row.liveProgress(progress)
                activityRow(row, live, live != null && (live.stale || progress?.transportStale == true), clock, ::formatAiringTime)
            }
        val ordered = orderedActivityRows(computed)
        val oldOrder = rows.map { it.row.request.id }
        val scopeChanged = scope != state.scope
        if (scopeChanged) {
            scope = state.scope
            cancelled = null
            menu?.dismiss()
            menu = null
            focusHandled = focusRequestId == null
        }
        rows = ordered
        val message = actionError ?: state.error
        errorText.text = message
        errorText.visibility = if (message != null) VISIBLE else GONE
        reconnectingText.visibility = if (!state.connected) VISIBLE else GONE
        when {
            ordered.isNotEmpty() -> {
                table.visibility = VISIBLE
                empty.visibility = GONE
                statusText.visibility = GONE
            }

            state.lastUpdated != null && state.syncComplete && state.error == null && state.connected -> {
                table.visibility = GONE
                empty.visibility = VISIBLE
                statusText.visibility = GONE
            }

            else -> {
                table.visibility = GONE
                empty.visibility = GONE
                statusText.visibility = VISIBLE
                statusText.text =
                    if (state.lastUpdated == null && state.error == null) {
                        "Loading your requests…"
                    } else {
                        "Your requests couldn't be loaded right now."
                    }
            }
        }
        adapter.submit(ordered)
        val order = ordered.map { it.row.request.id }
        if (order != oldOrder || scopeChanged) onOrderChanged(order)
        warmRows()
    }

    /** The old `LaunchedEffect(order, focusRequestId, scope)` */
    private fun onOrderChanged(order: List<Int>) {
        cancelled?.let { (id, index) ->
            if (id !in order) {
                cancelled = null
                val next = order.getOrNull(index.coerceAtMost(order.lastIndex))
                if (next != null) focusPosition(order.indexOf(next))
                return
            }
        }
        if (!focusHandled) {
            val index = order.indexOf(focusRequestId)
            if (index >= 0 && canTakeFocus()) {
                (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(index, 0)
                focusHandled = true
                focusPosition(index)
                return
            }
        }
        // Otherwise the queue starts at the top: on opening the page, on coming back to it, and whenever rows change
        // section while focus is elsewhere
        if (!list.hasFocus()) list.scrollToPosition(0)
    }

    /** Focus the row at [index] once it is laid out */
    private fun focusPosition(index: Int) {
        if (!canTakeFocus()) return
        list.post {
            val view = list.layoutManager?.findViewByPosition(index)
            (view as? QueueItemView)?.row?.requestFocus()
        }
    }

    /** Warm only the viewport neighbours and the leading rows, never all history (metadata and posters) */
    private fun warmRows() {
        val lifecycleOwner = owner ?: return
        val lm = list.layoutManager as LinearLayoutManager
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        val wanted = rows.take(2).mapTo(linkedSetOf()) { it.row.request.id }
        if (first >= 0 && last >= first) {
            for (index in first..last) {
                for (nearby in (index - 1).coerceAtLeast(0)..(index + 1).coerceAtMost(rows.lastIndex)) {
                    wanted.add(rows[nearby].row.request.id)
                }
            }
        }
        val warm = rows.filter { it.row.request.id in wanted }.take(8)
        warmJob?.cancel()
        warmJob =
            lifecycleOwner.lifecycleScope.launch {
                warm.forEach { viewModel.requests.loadMetadata(it.row.request.id) }
                val limit = Semaphore(2)
                coroutineScope {
                    warm.mapNotNull { it.row.item?.posterUrl }.distinct().forEach { url ->
                        launch {
                            limit.withPermit {
                                try {
                                    context.imageLoader.execute(requestPoster(context, scope, url, posterWidth, posterHeight).build())
                                } catch (ex: CancellationException) {
                                    throw ex
                                } catch (_: Exception) {
                                    // Prefetch is optional; it cannot take down the page
                                }
                            }
                        }
                    }
                }
            }
    }

    private fun onLongPress(
        entry: ActivityRow,
        index: Int,
        opener: View,
    ) {
        menu?.dismiss()
        menu =
            MenuDialog.show(
                context,
                title = entry.row.title,
                entries =
                    listOf(
                        MenuItem(
                            headline = context.getString(R.string.cancel_request),
                            leading = ListLeading.Icon(R.drawable.lb_search_ic_close, withAlpha(0xFFFF0000.toInt(), .8f)),
                            onClick = {
                                cancelled = entry.row.request.id to index
                                viewModel.cancel(entry.row.request.id)
                            },
                        ),
                    ),
                onDismissRequest = { menu = null },
                opener = opener,
            )
    }

    /** UP / DOWN one row at a time (the old `RowNavigator`) */
    private fun onRowKey(
        index: Int,
        event: KeyEvent,
    ): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || index < 0) return false
        val delta =
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> -1
                KeyEvent.KEYCODE_DPAD_DOWN -> 1
                else -> return false
            }
        val next = index + delta
        // Up from the first row goes on to the search bar; nothing else leaves the queue
        if (next !in rows.indices) return delta > 0
        val lm = list.layoutManager as LinearLayoutManager
        val step = context.dp(ROW_HEIGHT_DP + ROW_GAP_DP)
        repeat(12) {
            val view = lm.findViewByPosition(next) as? QueueItemView
            if (view != null && view.row.requestFocus()) return true
            // The next row is not laid out yet: scroll it in (RecyclerView lays out new rows at once)
            list.scrollBy(0, delta * step)
        }
        return true
    }

    // ---- views ----

    /** The table's list: plain RecyclerView, scrolling only as far as needed (see [FocusClearanceLayoutManager]) */
    private inner class QueueList(
        context: Context,
    ) : RecyclerView(context) {
        init {
            layoutManager = FocusClearanceLayoutManager(context)
            itemAnimator = null
            clipToPadding = false
            clipChildren = false
            setPadding(0, 0, 0, context.dp(8))
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(context.dp(FADE_HEIGHT_DP))
            overScrollMode = OVER_SCROLL_NEVER
            descendantFocusability = FOCUS_AFTER_DESCENDANTS
            addOnScrollListener(
                object : OnScrollListener() {
                    override fun onScrollStateChanged(
                        recyclerView: RecyclerView,
                        newState: Int,
                    ) {
                        if (newState == SCROLL_STATE_IDLE) warmRows()
                    }
                },
            )
        }

        // Soft fade where the list continues below the screen edge, none at the top
        override fun getTopFadingEdgeStrength(): Float = 0f

        override fun getBottomFadingEdgeStrength(): Float = if (canScrollVertically(1)) 1f else 0f
    }

    /**
     * Scroll only as far as needed, but keep 8dp clear above the focused row and keep it above the bottom fade. On the
     * WeaselTV theme a section's first row brings its heading into view with it (the whole list item).
     */
    private inner class FocusClearanceLayoutManager(
        context: Context,
    ) : LinearLayoutManager(context, VERTICAL, false) {
        override fun requestChildRectangleOnScreen(
            parent: RecyclerView,
            child: View,
            rect: Rect,
            immediate: Boolean,
            focusedChildVisible: Boolean,
        ): Boolean {
            val topPx = context.dpF(FOCUS_TOP_CLEARANCE_DP)
            val bottomPx = context.dpF(FADE_HEIGHT_DP)
            val offset: Float
            val size: Float
            if (AppTheme.isNeon) {
                offset = child.top.toFloat()
                size = child.height.toFloat()
            } else {
                offset = (child.top + rect.top).toFloat()
                size = rect.height().toFloat()
            }
            val container = parent.height.toFloat()
            val viewport = (container - bottomPx).coerceAtLeast(size)
            val top = topPx.coerceAtMost((viewport - size).coerceAtLeast(0f))
            val distance = keepVisible(offset - top, size + top, viewport).toInt()
            if (distance == 0) return false
            if (immediate) parent.scrollBy(0, distance) else parent.smoothScrollBy(0, distance)
            return true
        }

        private fun keepVisible(
            offset: Float,
            size: Float,
            containerSize: Float,
        ): Float {
            val end = offset + size
            val distance =
                when {
                    offset < 0f && end > containerSize -> 0f
                    offset < 0f -> offset
                    end > containerSize -> end - containerSize
                    else -> 0f
                }
            return if (abs(distance) <= 1f) 0f else distance
        }
    }

    private class Holder(
        val item: QueueItemView,
    ) : RecyclerView.ViewHolder(item)

    private inner class QueueAdapter : RecyclerView.Adapter<Holder>() {
        private var items: List<ActivityRow> = emptyList()

        init {
            setHasStableIds(true)
        }

        fun submit(newItems: List<ActivityRow>) {
            val old = items
            items = newItems
            if (old.map { it.row.request.id } == newItems.map { it.row.request.id }) {
                newItems.indices.forEach { if (old[it] != newItems[it] || headingChanged(old, newItems, it)) notifyItemChanged(it) }
            } else {
                notifyDataSetChanged()
            }
        }

        private fun headingChanged(
            old: List<ActivityRow>,
            new: List<ActivityRow>,
            index: Int,
        ): Boolean = sectionCount(old, old[index].section) != sectionCount(new, new[index].section)

        private fun sectionCount(
            list: List<ActivityRow>,
            section: ActivitySection,
        ) = list.count { it.section == section }

        override fun getItemCount(): Int = items.size

        override fun getItemId(position: Int): Long = items[position].row.request.id.toLong()

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): Holder =
            Holder(
                QueueItemView(parent.context).apply {
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                },
            )

        override fun onBindViewHolder(
            holder: Holder,
            position: Int,
        ) {
            val entry = items[position]
            val heading = position == 0 || items[position - 1].section != entry.section
            holder.item.bind(
                entry = entry,
                heading = if (heading) entry.section else null,
                count = items.count { it.section == entry.section },
                first = position == 0,
                last = position == items.lastIndex,
            )
            viewModel.requests.loadMetadata(entry.row.request.id)
            holder.item.row.setOnClickListener { viewModel.open(entry.row.request.id, requestsState.scope) }
            holder.item.row.setOnKeyListener { _, _, event -> onRowKey(holder.bindingAdapterPosition, event) }
            val cancellable = entry.section != ActivitySection.READY && viewModel.requests.canCancel(entry.row)
            if (cancellable) {
                holder.item.row.setOnLongClickListener { v ->
                    onLongPress(entry, holder.bindingAdapterPosition, v)
                    true
                }
            } else {
                holder.item.row.setOnLongClickListener(null)
                holder.item.row.isLongClickable = false
            }
        }

        override fun onViewRecycled(holder: Holder) {
            holder.item.recycle()
        }
    }

    /** One list item: the section heading when it starts a section, then the row; rows 6dp apart */
    private inner class QueueItemView(
        context: Context,
    ) : LinearLayout(context) {
        private val heading = SectionHeadingView(context)
        val row = ActivityRowView(context)

        init {
            orientation = VERTICAL
            clipChildren = false
            addView(heading, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }

        fun bind(
            entry: ActivityRow,
            heading: ActivitySection?,
            count: Int,
            first: Boolean,
            last: Boolean,
        ) {
            if (heading != null) {
                this.heading.visibility = VISIBLE
                this.heading.bind(heading, count, first)
            } else {
                this.heading.visibility = GONE
            }
            setPadding(0, 0, 0, if (last) 0 else context.dp(ROW_GAP_DP))
            row.bind(entry, scope)
        }

        fun recycle() = row.recycle()
    }

    /** One table row: the focus stop (accent at 14 % with a 1dp accent border when focused), with its divider */
    private inner class ActivityRowView(
        context: Context,
    ) : LinearLayout(context) {
        private val background = RowBackground(context)
        private val poster = ImageView(context)
        private val title = TextView(context)
        private val year = TextView(context)
        private val type = TextView(context)
        private val status = TextView(context)
        private val detail = TextView(context)
        private val progress = ProgressCellView(context)
        private val pill = WatchNowPillView(context)
        private var entry: ActivityRow? = null
        private var posterKey: String? = null

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            isSoundEffectsEnabled = false
            descendantFocusability = FOCUS_BLOCK_DESCENDANTS
            if (android.os.Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
            setBackground(background)
            minimumHeight = context.dp(ROW_HEIGHT_DP)
            setPaddingRelative(
                context.dp(ROW_START_INSET_DP),
                context.dp(ROW_VERTICAL_INSET_DP),
                context.dp(ROW_END_INSET_DP),
                context.dp(ROW_VERTICAL_INSET_DP),
            )
            // Title column: poster, then title and year
            val titleCell =
                LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
            poster.scaleType = ImageView.ScaleType.CENTER_CROP
            poster.setBackgroundColor(NeonColors.CARD2)
            titleCell.addView(poster, LayoutParams(posterWidth, posterHeight))
            val titleText =
                LinearLayout(context).apply {
                    orientation = VERTICAL
                    setPaddingRelative(context.dp(POSTER_GAP_DP), 0, context.dp(8), 0)
                }
            title.activityStyle(11f, NeonColors.TEXT, display = true, trackingEm = .08f, lineHeightSp = 14f)
            title.maxLines = 1
            title.isSingleLine = true
            title.ellipsize = TextUtils.TruncateAt.END
            // Always one line: a title too long for the column steps its size down instead of wrapping
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(title, 8, 11, 1, TypedValue.COMPLEX_UNIT_SP)
            year.activityStyle(10f, RequestActivityPalette.MUTED, lineHeightSp = 13f)
            titleText.addView(title, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            titleText.addView(year, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            titleCell.addView(titleText, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(titleCell, LayoutParams(0, LayoutParams.WRAP_CONTENT, TITLE_WEIGHT))

            type.activityStyle(11f, RequestActivityPalette.MOVIE, weight = 700)
            addView(type, LayoutParams(0, LayoutParams.WRAP_CONTENT, TYPE_WEIGHT))

            val statusCell =
                LinearLayout(context).apply {
                    orientation = VERTICAL
                    setPaddingRelative(0, 0, context.dp(8), 0)
                }
            status.activityStyle(12f, NeonColors.TEXT, weight = 600, lineHeightSp = 15f)
            status.maxLines = 2
            status.ellipsize = TextUtils.TruncateAt.END
            detail.activityStyle(10f, RequestActivityPalette.MUTED, lineHeightSp = 12.5f)
            detail.maxLines = 2
            detail.ellipsize = TextUtils.TruncateAt.END
            statusCell.addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            statusCell.addView(detail, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(statusCell, LayoutParams(0, LayoutParams.WRAP_CONTENT, STATUS_WEIGHT))

            val progressCell = FrameLayout(context)
            progressCell.addView(progress, FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL or Gravity.START))
            addView(progressCell, LayoutParams(0, LayoutParams.WRAP_CONTENT, PROGRESS_WEIGHT))

            // Watch now stays against the table's right edge
            val actionCell = FrameLayout(context)
            actionCell.addView(pill, FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, context.dp(24), Gravity.CENTER_VERTICAL or Gravity.END))
            addView(actionCell, LayoutParams(0, LayoutParams.WRAP_CONTENT, ACTION_WEIGHT))
        }

        fun bind(
            entry: ActivityRow,
            scope: String?,
        ) {
            this.entry = entry
            val row = entry.row
            val accent = entry.section.color()
            val isTv = row.request.type == "tv"
            val typeColor = if (isTv) RequestActivityPalette.SERIES else RequestActivityPalette.MOVIE
            background.accent = accent
            title.text = row.title.uppercase()
            val yearText =
                listOfNotNull(
                    row.item
                        ?.releaseDate
                        ?.year
                        ?.toString(),
                    "4K".takeIf { row.request.is4k == true },
                ).joinToString(" · ")
            year.text = yearText
            year.visibility = if (yearText.isNotEmpty()) VISIBLE else GONE
            type.text = if (isTv) "Series" else "Movie"
            type.setTextColor(typeColor)
            status.text = entry.status
            status.setTextColor(if (entry.section == ActivitySection.ACTIVE) NeonColors.TEXT else accent)
            detail.text = entry.detail
            detail.visibility = if (entry.detail != null) VISIBLE else GONE
            progress.bind(entry.progress, typeColor)
            pill.visibility = if (entry.watchable) VISIBLE else GONE
            pill.bind(filled = entry.section == ActivitySection.READY, focused = isFocused, accent = accent)
            loadPoster(row, scope)
        }

        private fun loadPoster(
            row: RequestStatusItem,
            scope: String?,
        ) {
            val url = row.item?.posterUrl
            val key = url?.let { requestPosterKey(scope, it, posterWidth, posterHeight) }
            if (key == posterKey) return
            posterKey = key
            if (url == null) {
                poster.dispose()
                poster.setImageDrawable(null)
                return
            }
            context.imageLoader.enqueue(requestPoster(context, scope, url, posterWidth, posterHeight).target(poster).build())
        }

        fun recycle() {
            poster.dispose()
            poster.setImageDrawable(null)
            posterKey = null
        }

        override fun onFocusChanged(
            gainFocus: Boolean,
            direction: Int,
            previouslyFocusedRect: Rect?,
        ) {
            super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
            background.focused = gainFocus
            entry?.let { pill.bind(filled = it.section == ActivitySection.READY, focused = gainFocus, accent = it.section.color()) }
        }

        override fun drawableStateChanged() {
            super.drawableStateChanged()
            @Suppress("SENSELESS_COMPARISON")
            if (background != null) background.pressed = isPressed
        }
    }

    /** Row fill and border (focused or pressed: accent at 14 % over `card` with a 1dp accent border) and the divider */
    private class RowBackground(
        context: Context,
    ) : Drawable() {
        private val density = context.resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val rect = RectF()
        var accent = NeonColors.VOLT
            set(value) {
                field = value
                invalidateSelf()
            }
        var focused = false
            set(value) {
                field = value
                invalidateSelf()
            }
        var pressed = false
            set(value) {
                field = value
                invalidateSelf()
            }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val active = focused || pressed
            if (active) {
                paint.color = NeonColors.chipOn(accent)
                canvas.drawRect(b, paint)
            }
            // The row's divider runs along its bottom edge, under the focus border
            paint.color = RequestActivityPalette.DIVIDER
            canvas.drawRect(b.left.toFloat(), b.bottom - 1f, b.right.toFloat(), b.bottom.toFloat(), paint)
            if (active) {
                stroke.strokeWidth = density
                stroke.color = accent
                rect.set(b)
                rect.inset(density / 2f, density / 2f)
                canvas.drawRect(rect, stroke)
            }
        }

        override fun setAlpha(alpha: Int) {}

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    /** The progress column: a bar with its percentage in the type colour, a note, or a centred dash */
    private class ProgressCellView(
        context: Context,
    ) : LinearLayout(context) {
        private val bar = BarView(context)
        private val text = TextView(context)

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(bar, LayoutParams(context.dp(BAR_WIDTH_DP), context.dp(BAR_HEIGHT_DP)))
            addView(text, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            text.maxLines = 1
            text.ellipsize = TextUtils.TruncateAt.END
        }

        fun bind(
            progress: ActivityProgress,
            typeColor: Int,
        ) {
            val textParams = text.layoutParams as LayoutParams
            when (progress) {
                is ActivityProgress.Bar -> {
                    val fraction = progress.fraction.coerceIn(0f, 1f)
                    bar.visibility = VISIBLE
                    bar.set(fraction, typeColor)
                    text.activityStyle(13f, typeColor, weight = 700)
                    text.text = "${(fraction * 100).toInt()}%"
                    text.gravity = Gravity.START
                    textParams.width = LayoutParams.WRAP_CONTENT
                    textParams.marginStart = context.dp(8)
                }

                is ActivityProgress.Note -> {
                    bar.visibility = GONE
                    text.activityStyle(
                        if (progress.emphasized) 12f else 11f,
                        if (progress.emphasized) NeonColors.TEXT else RequestActivityPalette.MUTED,
                        weight = if (progress.emphasized) 600 else 400,
                    )
                    text.text = progress.text
                    text.gravity = Gravity.START
                    textParams.width = LayoutParams.WRAP_CONTENT
                    textParams.marginStart = 0
                }

                ActivityProgress.None -> {
                    bar.visibility = GONE
                    text.activityStyle(12f, RequestActivityPalette.MUTED)
                    text.text = "—"
                    text.gravity = Gravity.CENTER
                    textParams.width = context.dp(BAR_WIDTH_DP)
                    textParams.marginStart = 0
                }
            }
            text.layoutParams = textParams
        }
    }

    /** The 75 x 6dp progress pill: the track and the filled part */
    private class BarView(
        context: Context,
    ) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private var fraction = 0f
        private var color = RequestActivityPalette.MOVIE

        fun set(
            fraction: Float,
            color: Int,
        ) {
            this.fraction = fraction
            this.color = color
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val r = height / 2f
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            paint.color = RequestActivityPalette.TRACK
            canvas.drawRoundRect(rect, r, r, paint)
            if (fraction > 0f) {
                rect.set(0f, 0f, width * fraction, height.toFloat())
                paint.color = color
                canvas.drawRoundRect(rect, r, r, paint)
            }
        }
    }

    /**
     * "Watch now", drawn inside the row (not its own focus stop): white filled for Ready (and on the focused row),
     * outlined in `low` for Waiting, a 2dp accent border on the focused row; at least 108dp wide, 24dp tall.
     */
    private class WatchNowPillView(
        context: Context,
    ) : LinearLayout(context) {
        private val icon = ImageView(context)
        private val label = TextView(context)
        private val shape = GradientDrawable().apply { cornerRadius = context.dpF(12f) }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            minimumWidth = context.dp(108)
            background = shape
            setPadding(context.dp(14), 0, context.dp(14), 0)
            icon.setImageResource(R.drawable.lb_widget_ctl_ic_play_arrow)
            addView(icon, LayoutParams(context.dp(13), context.dp(13)))
            label.text = "Watch now"
            label.activityStyle(11f, NeonColors.TEXT, weight = 700, trackingEm = .02f)
            label.maxLines = 1
            addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = context.dp(6) })
        }

        fun bind(
            filled: Boolean,
            focused: Boolean,
            accent: Int,
        ) {
            val solid = filled || focused
            val content = if (solid) NeonColors.ON_ACCENT else NeonColors.TEXT
            shape.setColor(if (solid) NeonColors.TEXT else 0)
            when {
                focused -> shape.setStroke(context.dp(2), accent)
                filled -> shape.setStroke(0, 0)
                else -> shape.setStroke(context.dp(1), NeonColors.LOW)
            }
            icon.imageTintList = android.content.res.ColorStateList.valueOf(content)
            label.setTextColor(content)
        }
    }

    /** "ACTIVE [3]" with its dot, then the section rule */
    private class SectionHeadingView(
        context: Context,
    ) : LinearLayout(context) {
        private val dot = DotView(context, 7f)
        private val text = TextView(context)
        private val rule = View(context)

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(dot, LayoutParams(context.dp(7), context.dp(7)))
            text.maxLines = 1
            addView(
                text,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    marginStart = context.dp(8)
                    marginEnd = context.dp(10)
                },
            )
            rule.setBackgroundColor(RequestActivityPalette.DIVIDER)
            addView(rule, LayoutParams(0, context.dp(1), 1f))
        }

        fun bind(
            section: ActivitySection,
            count: Int,
            first: Boolean,
        ) {
            val color = section.color()
            dot.color = color
            text.activityStyle(13f, color, display = true, trackingEm = .1f)
            text.text = "${section.title.uppercase()} [$count]"
            setPadding(0, context.dp(if (first) 4 else 5), 0, context.dp(10))
        }
    }

    private class DotView(
        context: Context,
        sizeDp: Float,
    ) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val size = context.dp(sizeDp)
        var color: Int = 0
            set(value) {
                field = value
                paint.color = value
                invalidate()
            }

        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int,
        ) = setMeasuredDimension(size, size)

        override fun onDraw(canvas: Canvas) {
            canvas.drawCircle(width / 2f, height / 2f, width / 2f, paint)
        }
    }

    /** The column headings: TITLE (after the poster), TYPE, STATUS, PROGRESS in 9sp SemiBold, 0.2em, muted */
    private class ColumnHeadings(
        context: Context,
    ) : LinearLayout(context) {
        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(context.dp(ROW_START_INSET_DP), 0, context.dp(ROW_END_INSET_DP), 0)
            fun heading(
                text: String,
                weight: Float,
                startPaddingDp: Float = 0f,
            ) {
                addView(
                    TextView(context).apply {
                        this.text = text
                        activityStyle(9f, RequestActivityPalette.MUTED, weight = 600, trackingEm = .2f)
                        setPaddingRelative(context.dp(startPaddingDp), 0, 0, 0)
                    },
                    LayoutParams(0, LayoutParams.WRAP_CONTENT, weight),
                )
            }
            heading("TITLE", TITLE_WEIGHT, POSTER_WIDTH_DP + POSTER_GAP_DP)
            heading("TYPE", TYPE_WEIGHT)
            heading("STATUS", STATUS_WEIGHT)
            heading("PROGRESS", PROGRESS_WEIGHT)
            addView(View(context), LayoutParams(0, 1, ACTION_WEIGHT))
        }
    }

    /**
     * "NOTHING REQUESTED YET": a 95dp teal circle (with a soft glow) holding the search icon, the heading, two lines of
     * guidance and the legend of the three sections.
     */
    private class EmptyStateView(
        context: Context,
    ) : LinearLayout(context) {
        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
            setPadding(0, context.dp(32), 0, 0)
            addView(EmptyCircleView(context), LayoutParams(context.dp(95), context.dp(95)))
            addView(
                TextView(context).apply {
                    text = "NOTHING REQUESTED YET"
                    activityStyle(23f, NeonColors.TEXT, display = true, trackingEm = .08f)
                    gravity = Gravity.CENTER
                },
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(26) },
            )
            fun body(
                text: String,
                topDp: Int,
            ) = addView(
                TextView(context).apply {
                    this.text = text
                    activityStyle(15f, withAlpha(NeonColors.TEXT, .78f), lineHeightSp = 22f)
                    gravity = Gravity.CENTER
                },
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(topDp) },
            )
            body("Search for a movie or show above, or press the mic and say its name.", 16)
            body("Once you request something, you can follow it here.", 2)
            val legend =
                LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
            listOf(
                ActivitySection.ACTIVE to "downloading now",
                ActivitySection.WAITING to "not released yet",
                ActivitySection.READY to "ready to play",
            ).forEachIndexed { index, (section, note) ->
                legend.addView(
                    legendItem(context, section, note),
                    LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                        if (index > 0) marginStart = context.dp(28)
                    },
                )
            }
            addView(legend, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(34) })
        }

        private fun legendItem(
            context: Context,
            section: ActivitySection,
            note: String,
        ): View =
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(DotView(context, 6f).apply { color = section.color() }, LayoutParams(context.dp(6), context.dp(6)))
                addView(
                    TextView(context).apply {
                        text = section.title.uppercase()
                        activityStyle(12f, section.color(), display = true, trackingEm = .1f)
                    },
                    LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = context.dp(8) },
                )
                addView(
                    TextView(context).apply {
                        text = note
                        activityStyle(11f, RequestActivityPalette.MUTED)
                    },
                    LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = context.dp(8) },
                )
            }
    }

    /** The empty state's circle: glow (teal at 28 % fading out to 0.8 of its size), fill, 1.5dp border, search icon */
    private class EmptyCircleView(
        context: Context,
    ) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val icon = context.getDrawable(R.drawable.lb_widget_ctl_ic_search)?.mutate()

        init {
            icon?.setTint(RequestActivityPalette.EMPTY_GLOW)
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val size = minOf(width, height).toFloat()
            val glowRadius = size * .8f
            paint.shader =
                RadialGradient(
                    cx,
                    cy,
                    glowRadius,
                    intArrayOf(withAlpha(RequestActivityPalette.EMPTY_GLOW, .28f), withAlpha(RequestActivityPalette.EMPTY_GLOW, .28f), 0),
                    floatArrayOf(0f, .55f, 1f),
                    Shader.TileMode.CLAMP,
                )
            canvas.drawCircle(cx, cy, glowRadius, paint)
            paint.shader = null
            paint.style = Paint.Style.FILL
            paint.color = NeonColors.chipOn(RequestActivityPalette.EMPTY_BORDER)
            canvas.drawCircle(cx, cy, size / 2f, paint)
            val border = context.dpF(1.5f)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = border
            paint.color = RequestActivityPalette.EMPTY_BORDER
            canvas.drawCircle(cx, cy, size / 2f - border / 2f, paint)
            paint.style = Paint.Style.FILL
            val iconSize = context.dp(46)
            icon?.setBounds(
                (cx - iconSize / 2f).toInt(),
                (cy - iconSize / 2f).toInt(),
                (cx + iconSize / 2f).toInt(),
                (cy + iconSize / 2f).toInt(),
            )
            icon?.draw(canvas)
        }
    }
}
