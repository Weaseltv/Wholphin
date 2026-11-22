package com.github.damontecres.wholphin.leanback.library

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Outline
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.leanback.widget.BaseGridView
import androidx.leanback.widget.VerticalGridView
import androidx.leanback.widget.ViewHolderTask
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import coil3.asImage
import coil3.load
import coil3.request.crossfade
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.HomeCardAppearance
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.ApprovedCollectionCardAppearanceView
import com.github.damontecres.wholphin.leanback.theme.ControlColors
import com.github.damontecres.wholphin.leanback.theme.ControlScales
import com.github.damontecres.wholphin.leanback.theme.ControlStyles
import com.github.damontecres.wholphin.leanback.theme.PageStyle
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.dpF
import com.github.damontecres.wholphin.leanback.theme.forCollectionCard
import com.github.damontecres.wholphin.leanback.theme.forWatchlistItem
import com.github.damontecres.wholphin.leanback.theme.surfaceAtElevation
import com.github.damontecres.wholphin.leanback.theme.withAlpha
import com.github.damontecres.wholphin.leanback.widget.card.AlphabetPickerView
import com.github.damontecres.wholphin.leanback.widget.card.BaseCardView
import com.github.damontecres.wholphin.leanback.widget.card.CardGridCallbacks
import com.github.damontecres.wholphin.leanback.widget.card.CardGridConfig
import com.github.damontecres.wholphin.leanback.widget.card.CardGridView
import com.github.damontecres.wholphin.leanback.widget.card.CardImageScale
import com.github.damontecres.wholphin.leanback.widget.card.CardImages
import com.github.damontecres.wholphin.leanback.widget.card.CardPresenter
import com.github.damontecres.wholphin.leanback.widget.card.FavoriteView
import com.github.damontecres.wholphin.leanback.widget.card.GridCardView
import com.github.damontecres.wholphin.leanback.widget.card.GridScrollMode
import com.github.damontecres.wholphin.leanback.widget.card.WatchedIconView
import com.github.damontecres.wholphin.leanback.widget.card.clipToOwnHeight
import com.github.damontecres.wholphin.leanback.widget.control.AppListItem
import com.github.damontecres.wholphin.leanback.widget.control.ListLeading
import com.github.damontecres.wholphin.leanback.widget.text.FittedDetailHeaderLayout
import com.github.damontecres.wholphin.leanback.widget.text.HeaderDimens
import com.github.damontecres.wholphin.leanback.widget.text.OverviewTextView
import com.github.damontecres.wholphin.leanback.widget.text.QuickDetailsView
import com.github.damontecres.wholphin.leanback.widget.text.TitleOrLogoView
import com.github.damontecres.wholphin.preferences.DisplayToggle
import com.github.damontecres.wholphin.ui.components.ViewOptions
import com.github.damontecres.wholphin.ui.components.ViewOptionsType
import com.github.damontecres.wholphin.ui.formatDuration
import com.github.damontecres.wholphin.ui.main.settings.Library
import com.github.damontecres.wholphin.ui.roundMinutes
import com.github.damontecres.wholphin.util.ExceptionHandler
import com.github.damontecres.wholphin.util.RequestPager
import com.github.damontecres.wholphin.util.WholphinDispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.extensions.ticks
import kotlin.math.max

/** What a library grid or list reports and asks for (the old `GridClickActions` plus the grid callbacks) */
class LibraryGridCallbacks(
    val onClickItem: (Int, BaseItem) -> Unit,
    val onLongClickItem: (Int, BaseItem, View) -> Unit,
    val onClickPlay: (Int, BaseItem) -> Unit,
    val letterPosition: suspend (Char) -> Int,
    /** Every focused card or row (`positionCallback`: the grid's column count, 1 for lists) */
    val onPosition: (columns: Int, position: Int) -> Unit,
)

/** How grid cards look: the page style, the Collections and streaming appearances, Watchlist libraries */
data class LibraryCardLook(
    val pageStyle: PageStyle,
    val collectionAppearance: HomeCardAppearance = ApprovedCollectionCardAppearanceView,
    val streamingAppearance: HomeCardAppearance = collectionAppearance,
    val watchlistLibraries: List<Library>? = null,
    /** `LocalHidePosterTypeBadge` for the whole grid (Movies and TV Shows on WeaselTV) */
    val hideTypeBadge: Boolean = false,
) {
    /** The style of [item]'s card (`WatchlistCardStyle` then `CollectionCardStyle`) */
    fun styleFor(item: BaseItem?): PageStyle {
        var style = if (hideTypeBadge) pageStyle.copy(hidePosterTypeBadge = true) else pageStyle
        if (watchlistLibraries != null) style = style.forWatchlistItem(item, watchlistLibraries)
        return style.forCollectionCard(item, collectionAppearance, streamingAppearance)
    }
}

/** Grid cards (`GridCard` in `CollectionFolderGrid`), reading the current options when bound */
internal class LibraryGridPresenter : CardPresenter<BaseItem>() {
    var viewOptions: ViewOptions = ViewOptions()
    var look: LibraryCardLook = LibraryCardLook(PageStyle())

    override fun create(
        parent: ViewGroup,
        viewType: Int,
    ): BaseCardView = GridCardView(parent.context)

    override fun bind(
        card: BaseCardView,
        item: BaseItem?,
        position: Int,
        cellWidthPx: Int,
    ) {
        val options = viewOptions
        (card as GridCardView).bind(
            item = item,
            style = look.styleFor(item),
            widthPx = cellWidthPx,
            imageAspectRatio = options.aspectRatio.ratio,
            imageScale = CardImageScale.from(options.contentScale),
            imageType = options.imageType.imageType,
            showTitle = options.showTitles,
        )
    }
}

/**
 * `CollectionFolderGrid`: the library grid ([CardGridView]: letter bar when sorted by name, footer, "No results") with,
 * when the view options show details, the focused item's header above it ([LibraryItemHeaderView], padded 48 / 32 /
 * 8dp). On WeaselTV the header is scaled down to fit above the grid's first row (`FittedDetailHeader`), and the grid
 * keeps 24dp above its first row (16dp elsewhere).
 */
class LibraryGridView(
    context: Context,
) : LinearLayout(context) {
    private val headerFrame = CollapsibleFrame(context)
    private val headerFit = FittedDetailHeaderLayout(context, reservedHeightDp = 0f)
    val header = LibraryItemHeaderView(context)
    val grid = CardGridView(context)
    private val presenter = LibraryGridPresenter()
    private var options = ViewOptions()
    private var changesJob: Job? = null
    private var showLogo = true
    private var toggles: Set<DisplayToggle> = emptySet()
    private var focusedItem: BaseItem? = null

    init {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
        header.setPaddingRelative(
            context.dp(HeaderDimens.START_PADDING),
            context.dp(HeaderDimens.TOP_PADDING),
            0,
            context.dp(HeaderDimens.BOTTOM_PADDING),
        )
        headerFit.addView(header, ViewGroup.LayoutParams(MATCH, WRAP))
        headerFrame.addView(headerFit, FrameLayout.LayoutParams(MATCH, WRAP))
        addView(headerFrame, LayoutParams(MATCH, WRAP))
        addView(grid, LayoutParams(MATCH, 0, 1f))
        headerFrame.setExpanded(false, animate = false)
    }

    /**
     * Show [items] with [viewOptions]; [initialPosition] is the card focused first. Calling again with a new list
     * instance (filter, sort) keeps the focused position where possible; the same list only rebinds.
     */
    fun setItems(
        owner: LifecycleOwner,
        items: List<BaseItem?>,
        viewOptions: ViewOptions,
        sortByName: Boolean,
        look: LibraryCardLook,
        initialPosition: Int,
        callbacks: LibraryGridCallbacks,
        showLogo: Boolean,
        toggles: Set<DisplayToggle>,
    ) {
        val optionsChanged = viewOptions != options
        options = viewOptions
        this.showLogo = showLogo
        this.toggles = toggles
        presenter.viewOptions = viewOptions
        presenter.look = look
        val spacing = viewOptions.spacing.toFloat()
        val topPadding = if (AppTheme.isNeon) 24f else 16f
        grid.setCards(
            items = items,
            presenter = presenter,
            callbacks =
                CardGridCallbacks(
                    onClick = callbacks.onClickItem,
                    onLongClick = { index, item ->
                        val card = grid.grid.findViewHolderForAdapterPosition(index)?.itemView
                        callbacks.onLongClickItem(index, item, card ?: grid)
                    },
                    onClickPlay = callbacks.onClickPlay,
                    letterPosition = callbacks.letterPosition,
                    onPosition = { columns, position ->
                        callbacks.onPosition(columns, position)
                    },
                ),
            config =
                CardGridConfig(
                    columns = viewOptions.columns,
                    spacingDp = spacing,
                    topPaddingDp = topPadding,
                    showLetterButtons = sortByName,
                    initialPosition = initialPosition,
                    scrollMode = if (viewOptions.showDetails) GridScrollMode.Top(spacing) else GridScrollMode.Pivot,
                ),
            pageStyle = look.pageStyle,
        )
        if (AppTheme.isNeon && viewOptions.showDetails) {
            // The details header used ScrollToTopBringIntoViewSpec on every theme: the focused row's top `spacing`
            // below the grid's top
            grid.grid.focusScrollStrategy = BaseGridView.FOCUS_SCROLL_ALIGNED
            grid.grid.windowAlignment = BaseGridView.WINDOW_ALIGN_NO_EDGE
            grid.grid.windowAlignmentOffset = context.dp(spacing) + grid.grid.paddingTop
            grid.grid.windowAlignmentOffsetPercent = BaseGridView.WINDOW_ALIGN_OFFSET_PERCENT_DISABLED
        } else if (AppTheme.isNeon) {
            grid.grid.focusScrollStrategy = BaseGridView.FOCUS_SCROLL_ITEM
            grid.grid.windowAlignment = BaseGridView.WINDOW_ALIGN_BOTH_EDGE
        }
        if (optionsChanged) grid.refresh()
        changesJob?.cancel()
        changesJob = (items as? RequestPager<*>)?.let { grid.observeChanges(owner, it.changes) }
        headerFrame.setExpanded(viewOptions.showDetails)
        if (viewOptions.showDetails) bindHeader()
        requestLayout()
    }

    /** The focused item changed (or loaded) */
    fun setFocusedItem(item: BaseItem?) {
        if (item == focusedItem) return
        focusedItem = item
        if (options.showDetails) bindHeader()
    }

    private fun bindHeader() {
        header.bind(
            focusedItem,
            presenter.look.pageStyle,
            showLogo = showLogo,
            compactLogo = true,
            compactDetails = AppTheme.isNeon,
            toggles = toggles,
        )
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        if (AppTheme.isNeon && options.showDetails) {
            // Size the header for the grid's first row up front, so it doesn't change once items load
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val height = MeasureSpec.getSize(heightMeasureSpec)
            val row =
                CardGridView.rowHeightPx(
                    context,
                    width,
                    options.columns,
                    options.spacing.toFloat(),
                    options.aspectRatio.ratio,
                    options.showTitles,
                    24f,
                )
            headerFit.maxHeightPx = max(height - row, context.dp(1))
        } else {
            headerFit.maxHeightPx = null
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /** BACK on the grid: jump to the first card */
    fun onBackPressed(): Boolean = grid.onBackPressed()

    /** Focus the grid's selected card */
    fun focusGrid(): Boolean = grid.grid.requestFocus()

    override fun onDetachedFromWindow() {
        changesJob?.cancel()
        super.onDetachedFromWindow()
    }
}

/**
 * `CollectionFolderList`: the library as a list (LIST / DENSE_LIST view options). Optional details of the focused item
 * at the start (a third of the width: title or logo, poster, quick details, five lines of overview), the rows in the
 * middle (16dp side padding, 20dp room above and below, the options' spacing between rows), and the letter bar at the
 * end when sorted by name. The list sits 8dp below the header while the first row is focused, 48dp otherwise. BACK
 * while the list has focus and a later row is focused jumps to the first row.
 */
class LibraryListView(
    context: Context,
) : LinearLayout(context) {
    private val row = LinearLayout(context)
    private val detailsFrame = FrameLayout(context)
    private val details = ListDetailsView(context)
    val list = VerticalGridView(context)
    private val alphabet = AlphabetPickerView(context)
    private val adapter = ListAdapter()
    private var callbacks: LibraryGridCallbacks? = null
    private var items: List<BaseItem?> = emptyList()
    private var position = 0
    private var collectionType: CollectionType? = null
    private var options = ViewOptions(type = ViewOptionsType.LIST)
    private var changesJob: Job? = null
    private var showHeader = true
    private var topAnimator: ValueAnimator? = null
    private var owner: LifecycleOwner? = null
    private var letterJob: Job? = null

    init {
        list.clipToOwnHeight()
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
        row.orientation = HORIZONTAL
        row.clipChildren = false
        row.clipToPadding = false
        detailsFrame.addView(details, FrameLayout.LayoutParams(MATCH, MATCH))
        val pad = context.dp(8)
        details.setPadding(pad, pad, pad, pad)
        row.addView(detailsFrame, LayoutParams(0, MATCH).apply { marginEnd = context.dp(8) })
        list.clipChildren = false
        list.clipToPadding = true
        val h = context.dp(16)
        val v = context.dp(DIALOG_LIST_EDGE_DP)
        list.setPadding(h, v, h, v)
        list.setNumColumns(1)
        list.itemAnimator = null
        list.windowAlignment = BaseGridView.WINDOW_ALIGN_BOTH_EDGE
        list.focusScrollStrategy = BaseGridView.FOCUS_SCROLL_ITEM
        list.adapter = adapter
        row.addView(list, LayoutParams(0, MATCH, 1f))
        row.addView(
            alphabet,
            LayoutParams(WRAP, WRAP).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginStart = context.dp(8) + context.dp(16)
            },
        )
        addView(row, LayoutParams(MATCH, MATCH))
        alphabet.setup(context.getString(R.string.jump_letters))
        alphabet.onLetter = ::jumpToLetter
        applyTop(animate = false)
    }

    fun setItems(
        owner: LifecycleOwner,
        items: List<BaseItem?>,
        viewOptions: ViewOptions,
        collectionType: CollectionType?,
        sortByName: Boolean,
        accent: Int,
        initialPosition: Int,
        callbacks: LibraryGridCallbacks,
    ) {
        this.owner = owner
        this.callbacks = callbacks
        val newList = items !== this.items
        if (newList && this.items.isEmpty()) position = initialPosition.coerceIn(0, max(items.size - 1, 0))
        this.items = items
        this.collectionType = collectionType
        val optionsChanged = viewOptions != options
        options = viewOptions
        list.setItemSpacing(context.dp(viewOptions.spacing))
        alphabet.setAccent(accent)
        alphabet.visibility = if (sortByName && items.isNotEmpty()) VISIBLE else GONE
        detailsFrame.visibility = if (viewOptions.showDetails) VISIBLE else GONE
        if (newList || optionsChanged) {
            adapter.reset()
            if (position >= items.size) position = max(items.size - 1, 0)
            list.selectedPosition = position
        }
        changesJob?.cancel()
        changesJob =
            (items as? RequestPager<*>)?.let { pager ->
                owner.lifecycleScope.launch {
                    owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        pager.changes.collect {
                            adapter.refresh()
                            updateFocused()
                        }
                    }
                }
            }
        updateFocused()
    }

    private fun updateFocused() {
        val item = items.getOrNull(position)
        if (options.showDetails) details.bind(item)
        val letters = alphabet.letters
        if (letters.isNotEmpty()) {
            val current =
                item
                    ?.sortName
                    ?.firstOrNull()
                    ?.uppercaseChar()
                    ?.let {
                        when (it) {
                            in '0'..'9' -> '#'
                            in 'A'..'Z' -> it
                            else -> null
                        }
                    } ?: letters[0]
            alphabet.setCurrentLetter(current)
        }
    }

    private fun onRowFocused(index: Int) {
        position = index
        val header = index < 1
        if (header != showHeader) {
            showHeader = header
            applyTop(animate = true)
        }
        callbacks?.onPosition?.invoke(1, index)
        updateFocused()
    }

    /** `animateDpAsState`: 8dp below the header while the first row is focused, 48dp otherwise */
    private fun applyTop(animate: Boolean) {
        val target = context.dp(if (showHeader) 8 else 48)
        topAnimator?.cancel()
        if (!animate) {
            row.setPadding(0, target, 0, context.dp(16))
            return
        }
        topAnimator =
            ValueAnimator.ofInt(row.paddingTop, target).apply {
                duration = 300
                addUpdateListener { row.setPadding(0, it.animatedValue as Int, 0, context.dp(16)) }
                start()
            }
    }

    private fun jumpTo(newPosition: Int) {
        if (newPosition < 0 || items.isEmpty()) return
        val target = newPosition.coerceAtMost(items.size - 1)
        position = target
        list.setSelectedPositionSmooth(target, ViewHolderTask { it.itemView.requestFocus() })
    }

    private fun jumpToLetter(letter: Char) {
        val owner = owner ?: return
        val cb = callbacks ?: return
        letterJob?.cancel()
        letterJob =
            owner.lifecycleScope.launch(ExceptionHandler()) {
                val target = withContext(WholphinDispatchers.IO) { cb.letterPosition(letter.uppercaseChar()) }
                jumpTo(target)
            }
    }

    /** `BackHandler(contentHasFocus && position > 0)`: jump to the first row */
    fun onBackPressed(): Boolean {
        if (!row.hasFocus() || position <= 0) return false
        jumpTo(0)
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && row.hasFocus() && position > 0) {
            if (event.action == KeyEvent.ACTION_UP) jumpTo(0)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** Focus the selected row */
    fun focusList(): Boolean = list.requestFocus()

    override fun onDetachedFromWindow() {
        changesJob?.cancel()
        topAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    private inner class ListAdapter : RecyclerView.Adapter<RowHolder>() {
        private var count = 0

        init {
            setHasStableIds(true)
        }

        /** A new list or new options: rebind everything */
        @Suppress("NotifyDataSetChanged")
        fun reset() {
            count = items.size
            notifyDataSetChanged()
        }

        /** The pager loaded a page: rebind in place (or everything when its size changed) */
        fun refresh() {
            if (items.size != count) {
                reset()
            } else if (count > 0) {
                notifyItemRangeChanged(0, count)
            }
        }

        override fun getItemCount(): Int = count

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getItemViewType(position: Int): Int = if (options.type == ViewOptionsType.DENSE_LIST) 1 else 0

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): RowHolder {
            val holder = RowHolder(LibraryListRowView(parent.context, dense = viewType == 1))
            holder.row.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                items.getOrNull(pos)?.let { item ->
                    position = pos
                    callbacks?.onClickItem?.invoke(pos, item)
                }
            }
            holder.row.setOnLongClickListener { v ->
                val pos = holder.bindingAdapterPosition
                items.getOrNull(pos)?.let { item ->
                    position = pos
                    callbacks?.onLongClickItem?.invoke(pos, item, v)
                }
                true
            }
            holder.row.setOnFocusChangeListener { _, focused ->
                holder.row.onFocus(focused)
                val pos = holder.bindingAdapterPosition
                if (focused && pos != RecyclerView.NO_POSITION) onRowFocused(pos)
            }
            holder.row.setOnKeyListener { _, _, event ->
                val pos = holder.bindingAdapterPosition
                val item = items.getOrNull(pos)
                if (item != null && event.action == KeyEvent.ACTION_UP && isPlayKey(event.keyCode) && item.playable) {
                    callbacks?.onClickPlay?.invoke(pos, item)
                    true
                } else {
                    false
                }
            }
            return holder
        }

        override fun onBindViewHolder(
            holder: RowHolder,
            position: Int,
        ) {
            holder.row.bind(items.getOrNull(position), options.type == ViewOptionsType.DENSE_LIST, collectionType)
        }
    }

    private class RowHolder(
        val row: LibraryListRowView,
    ) : RecyclerView.ViewHolder(row)
}

/** The remote's play / pause keys (`isPlayKeyUp`) */
internal fun isPlayKey(code: Int): Boolean =
    code == KeyEvent.KEYCODE_MEDIA_PLAY || code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_MEDIA_PAUSE

/**
 * A library list row (`ListItemMovie` / `ListItemSeries` / `ListItemGeneric`): the favourite heart, the title (scrolling
 * after half a second of focus), the subtitle, and the runtime and watched mark (movies), the unwatched count or watched
 * mark (shows) or the runtime. Dense rows put the year (movies) or subtitle (shows) in the title. The row keeps its size
 * when focused (0.95 while pressed); off WeaselTV it has a half-transparent surface fill.
 */
internal class LibraryListRowView(
    context: Context,
    dense: Boolean,
) : AppListItem(context, null, dense) {
    private val trailingRow =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
    private val runtime = TextView(context)
    private val watched = WatchedIconView(context)
    private val favorite = FavoriteView(context)
    private val marquee = Runnable { headlineView.isSelected = true }

    init {
        trailingRow.addView(runtime, LayoutParams(WRAP, WRAP))
        trailingRow.addView(watched, LayoutParams(context.dp(24), context.dp(24)).apply { marginStart = context.dp(4) })
        setSurfaceStyle { scope ->
            val base = ControlStyles.listItem(scope)
            val scaled = base.copy(scale = ControlScales(focused = 1f, pressed = .95f))
            if (AppTheme.isNeon) {
                scaled
            } else {
                scaled.copy(container = base.container.copy(rest = withAlpha(AppTheme.palette.surfaceAtElevation(1f), .5f)))
            }
        }
        headlineView.ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
        headlineView.marqueeRepeatLimit = -1
        headlineView.isSingleLine = true
        onContentColor = { color -> runtime.setTextColor(color) }
    }

    fun onFocus(focused: Boolean) {
        removeCallbacks(marquee)
        headlineView.isSelected = false
        if (focused) postDelayed(marquee, 500)
    }

    fun bind(
        item: BaseItem?,
        dense: Boolean,
        collectionType: CollectionType?,
    ) {
        val duration =
            item
                ?.data
                ?.runTimeTicks
                ?.ticks
                ?.roundMinutes
                ?.let { resources.formatDuration(it) } ?: ""
        setLeading(if (item?.favorite == true) ListLeading.Custom(favorite) else null)
        when (collectionType) {
            CollectionType.MOVIES -> {
                val title =
                    if (dense && item != null && item.title != null && item.data.productionYear != null) {
                        "${item.title} (${item.data.productionYear})"
                    } else {
                        item?.title ?: ""
                    }
                setHeadline(title)
                setSupporting(if (!dense) item?.subtitle ?: "" else null)
                runtime.visibility = VISIBLE
                runtime.text = duration
                watched.visibility = if (item?.played == true) VISIBLE else GONE
                setTrailingView(trailingRow)
            }

            CollectionType.TVSHOWS -> {
                val title =
                    if (item != null) {
                        if (dense) "${item.title} (${item.subtitle})" else item.title ?: ""
                    } else {
                        ""
                    }
                setHeadline(title)
                setSupporting(if (!dense) item?.subtitle ?: "" else null)
                val unplayed = item?.countBadge
                if (unplayed != null && unplayed > 0) {
                    runtime.visibility = VISIBLE
                    runtime.text = unplayed.toString()
                    watched.visibility = GONE
                    setTrailingView(trailingRow)
                } else if (item?.played == true) {
                    runtime.visibility = GONE
                    watched.visibility = VISIBLE
                    setTrailingView(trailingRow)
                } else {
                    setTrailingView(null)
                }
            }

            else -> {
                setHeadline(item?.title ?: "")
                setSupporting(item?.subtitle)
                runtime.visibility = VISIBLE
                runtime.text = duration
                watched.visibility = GONE
                setTrailingView(trailingRow)
            }
        }
    }
}

/**
 * The details at the start of the library list (`CollectionFolderListDetails`): the title or logo (60dp, centred), the
 * poster (rounded 8dp, filling the rest), the quick details and five lines of overview.
 */
internal class ListDetailsView(
    context: Context,
) : LinearLayout(context) {
    private val title = TitleOrLogoView(context, fillLogo = true)
    private val image = ImageView(context)
    private val quick = QuickDetailsView(context)
    private val overview = OverviewTextView(context)
    private var shown: BaseItem? = null

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(title, LayoutParams(MATCH, context.dp(HeaderDimens.LOGO_HEIGHT)))
        image.scaleType = ImageView.ScaleType.FIT_CENTER
        image.adjustViewBounds = true
        image.outlineProvider =
            object : ViewOutlineProvider() {
                override fun getOutline(
                    view: View,
                    outline: Outline,
                ) {
                    val d = (view as ImageView).drawable
                    if (d == null || d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0) {
                        outline.setRoundRect(0, 0, view.width, view.height, context.dpF(8f))
                        return
                    }
                    // Round the drawn picture, not the empty space beside it
                    val scale = minOf(view.width.toFloat() / d.intrinsicWidth, view.height.toFloat() / d.intrinsicHeight)
                    val w = (d.intrinsicWidth * scale).toInt()
                    val h = (d.intrinsicHeight * scale).toInt()
                    val l = (view.width - w) / 2
                    val t = (view.height - h) / 2
                    outline.setRoundRect(l, t, l + w, t + h, context.dpF(8f))
                }
            }
        image.clipToOutline = true
        addView(image, LayoutParams(WRAP, 0, 1f).apply { topMargin = context.dp(8) })
        addView(quick, LayoutParams(WRAP, WRAP).apply { topMargin = context.dp(8) })
        overview.maxLines = 5
        // enabled = false: not focusable
        overview.isFocusable = false
        overview.isClickable = false
        overview.background = null
        addView(overview, LayoutParams(MATCH, WRAP).apply { topMargin = context.dp(8) })
    }

    fun bind(item: BaseItem?) {
        if (item == shown && item != null) return
        shown = item
        title.bind(item, showLogo = true, imageUrlService = CardImages.imageUrlService(context))
        val url =
            item?.imageUrlOverride
                ?: CardImages.imageUrlService(context).getItemImageUrl(item, ImageType.PRIMARY, useSeriesForPrimary = true)
        // useExistingImageAsPlaceholder: keep the previous poster until the next one arrives
        image.load(url) {
            crossfade(300)
            placeholder(image.drawable?.asImage())
            listener(onSuccess = { _, _ -> image.invalidateOutline() })
        }
        if (item != null) {
            quick.visibility = VISIBLE
            quick.bind(item.ui.quickDetailsText, null)
        } else {
            quick.visibility = GONE
        }
        overview.setText(item?.data?.overview ?: "")
    }
}
