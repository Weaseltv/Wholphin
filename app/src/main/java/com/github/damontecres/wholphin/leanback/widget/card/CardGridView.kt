package com.github.damontecres.wholphin.leanback.widget.card

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.AudioManager
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.widget.AppCompatTextView
import androidx.leanback.widget.BaseGridView
import androidx.leanback.widget.GridLayoutManager
import androidx.leanback.widget.VerticalGridView
import androidx.leanback.widget.ViewHolderTask
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.RecyclerView
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.ApprovedHomeLayout
import com.github.damontecres.wholphin.leanback.theme.AppFonts
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.PageStyle
import com.github.damontecres.wholphin.leanback.theme.NeonColors
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.dpF
import com.github.damontecres.wholphin.leanback.theme.neonGlowAccent
import com.github.damontecres.wholphin.leanback.theme.withAlpha
import com.github.damontecres.wholphin.ui.detail.CardGridItem
import com.github.damontecres.wholphin.util.ExceptionHandler
import com.github.damontecres.wholphin.util.WholphinDispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * How a [CardGridView] scrolls to the focused card on themes other than WeaselTV (WeaselTV grids always scroll only
 * enough to show the card, `KeepVisibleBringIntoViewSpec`).
 */
sealed interface GridScrollMode {
    /** The TV default: the focused row's top at 30 % of the grid */
    data object Pivot : GridScrollMode

    /** `ScrollToTopBringIntoViewSpec`: the focused row's top [spaceAboveDp] below the grid's top */
    data class Top(
        val spaceAboveDp: Float,
    ) : GridScrollMode
}

/** Layout of a [CardGridView] (`CardGrid` parameters) */
data class CardGridConfig(
    val columns: Int = 6,
    /** Space between cards; WeaselTV always uses the owner-approved 10dp */
    val spacingDp: Float = 16f,
    /** Room above the first row for its focus effects (`topContentPadding`) */
    val topPaddingDp: Float = if (AppTheme.isNeon) 32f else 16f,
    /** The letter jump bar (sorted by name); its width is reserved either way, so cards keep their size */
    val showLetterButtons: Boolean = false,
    val initialPosition: Int = 0,
    val scrollMode: GridScrollMode = GridScrollMode.Pivot,
) {
    val spacingInEffectDp: Float get() = if (AppTheme.isNeon) ApprovedHomeLayout.CARD_SPACING_DP.toFloat() else spacingDp
    val bottomPaddingDp: Float get() = if (AppTheme.isNeon) max(topPaddingDp, 32f) + 16f else 16f
}

/** What happens in a [CardGridView] */
class CardGridCallbacks<T : CardGridItem>(
    val onClick: (index: Int, item: T) -> Unit,
    val onLongClick: (index: Int, item: T) -> Unit = { _, _ -> },
    /** The remote's play key on a playable item */
    val onClickPlay: (index: Int, item: T) -> Unit = { _, _ -> },
    /** Index of the first item starting with a letter (called off the main thread); -1 when none */
    val letterPosition: suspend (Char) -> Int = { -1 },
    /** `positionCallback`: every focused card */
    val onPosition: ((columns: Int, position: Int) -> Unit)? = null,
    /** Key events on a focused card first; return true to consume */
    val onKey: ((index: Int, item: T?, event: KeyEvent) -> Boolean)? = null,
)

/**
 * `CardGrid`: a vertical grid of cards on a Leanback `VerticalGridView`, with the letter jump bar at the right, the
 * "n / total" footer and "No results" when empty.
 *
 * Behaviour kept from the old grid:
 * - Focus enters at the last focused card ([CardGridConfig.initialPosition] at first).
 * - WeaselTV: UP / DOWN keep the column across rows, even when the destination row is not laid out yet; DOWN into a short
 *   last row goes to its last card; DOWN on the last row stops; UP on the first row leaves the grid (toolbar).
 * - BACK (released) on any card but the first jumps to the first card (animated when within 6 rows); BACK held jumps
 *   back to the card focused before. Call [onBackPressed] from the page's `onBackPressed` too, in case the shell
 *   handles BACK before Views see it.
 * - Play key: [CardGridCallbacks.onClickPlay] for a playable item. Fast-forward / next / page-down jump down a page of
 *   rows (rewind / previous / page-up up); letter keys A–Z and the jump bar jump to that letter.
 *
 * The grid scrolls vertically and clips there; focused cards' glow may reach past its left and right edges, so its
 * parents should not clip children horizontally.
 */
class CardGridView(
    context: Context,
) : FrameLayout(context) {
    val grid = CardVerticalGrid(context)
    private val content = LinearLayout(context)
    private val gridBox = FrameLayout(context)
    private val footer = AppCompatTextView(context)
    private val empty = AppCompatTextView(context)
    val alphabet = AlphabetPickerView(context)
    private val alphabetSpacer = View(context)

    private var adapter: CardGridAdapter<*>? = null
    private var config = CardGridConfig()
    private var itemsSize = 0
    private var focusedIndex = 0
    private var previouslyFocusedIndex = 0
    private var longPressing = false
    private var letterJob: Job? = null
    private var playAt: (Int) -> Unit = {}
    private var letterPosition: suspend (Char) -> Int = { -1 }
    private var onPosition: ((Int, Int) -> Unit)? = null
    private var sortNameAt: (Int) -> String? = { null }
    private var initialized = false

    init {
        clipChildren = false
        content.orientation = LinearLayout.HORIZONTAL
        content.clipChildren = false
        gridBox.clipChildren = false
        gridBox.addView(grid, LayoutParams(MATCH, MATCH))
        footer.setBackgroundColor(0x80000000.toInt())
        val pad = context.dp(4)
        footer.setPadding(pad, pad, pad, pad)
        gridBox.addView(footer, LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        content.addView(gridBox, LinearLayout.LayoutParams(0, MATCH, 1f))
        content.addView(
            alphabet,
            LinearLayout.LayoutParams(WRAP, WRAP).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginStart = context.dp(16)
            },
        )
        content.addView(alphabetSpacer, LinearLayout.LayoutParams(0, 0).apply { marginStart = context.dp(16) })
        addView(content, LayoutParams(MATCH, MATCH))
        empty.gravity = Gravity.CENTER
        empty.setText(R.string.no_results)
        addView(empty, LayoutParams(WRAP, WRAP, Gravity.CENTER))

        grid.cardGrid = this
        alphabet.onLetter = ::jumpToLetter
    }

    /**
     * Show [items] (usually a `RequestPager`; call [refresh] or [observeChanges] when it loads pages) with [presenter].
     * Calling again with the same list only refreshes; a new list (new filter) keeps the focused position when it can.
     * [pageStyle] gives the letter bar its accent.
     */
    fun <T : CardGridItem> setCards(
        items: List<T?>,
        presenter: CardPresenter<T>,
        callbacks: CardGridCallbacks<T>,
        config: CardGridConfig = this.config,
        pageStyle: PageStyle? = null,
    ) {
        applyConfig(config)
        // The letter bar takes the page's section accent (LocalNeonSectionAccent)
        pageStyle?.let { alphabet.setAccent(it.sectionAccent) }
        @Suppress("UNCHECKED_CAST")
        val typed = (adapter as? CardGridAdapter<T>)?.takeIf { it.presenter === presenter } ?: CardGridAdapter(presenter)
        typed.callbacks =
            CardCallbacks(
                onClick = { i, item ->
                    focusOn(i)
                    callbacks.onClick(i, item)
                },
                onLongClick = callbacks.onLongClick,
                onFocus = { i, _ -> onCardFocused(i) },
                onKey = callbacks.onKey,
            )
        playAt = { index ->
            typed.itemAt(index)?.let { item ->
                if (item.playable) {
                    Timber.v("Clicked play on ${item.gridId}")
                    callbacks.onClickPlay(index, item)
                }
            }
        }
        letterPosition = callbacks.letterPosition
        onPosition = callbacks.onPosition
        sortNameAt = { typed.itemAt(it)?.sortName }
        if (adapter !== typed) {
            adapter = typed
            grid.adapter = typed
        }
        typed.cellWidthPx = grid.cellWidthFor(grid.width)
        if (!initialized) {
            initialized = true
            focusedIndex = config.initialPosition.coerceIn(0, (items.size - 1).coerceAtLeast(0))
            previouslyFocusedIndex = 0
            typed.setItems(items)
            grid.selectedPosition = focusedIndex
        } else {
            typed.setItems(items)
        }
        onItemCountChanged(items.size)
    }

    /** The list changed in place (a page loaded): rebind the cards shown */
    fun refresh() {
        val a = adapter ?: return
        a.refresh()
        onItemCountChanged(a.items.size)
    }

    /** Refresh whenever [changes] emits (eg `RequestPager.changes`) while [owner] is started */
    fun observeChanges(
        owner: LifecycleOwner,
        changes: Flow<*>,
    ): Job =
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                changes.collect { refresh() }
            }
        }

    private fun applyConfig(config: CardGridConfig) {
        val changed = config != this.config || !initialized
        this.config = config
        if (!changed) return
        grid.setNumColumns(config.columns)
        grid.setItemSpacing(context.dp(config.spacingInEffectDp))
        grid.setPadding(0, context.dp(config.topPaddingDp), 0, context.dp(config.bottomPaddingDp))
        grid.applyScroll(config.scrollMode)
        grid.requestLayout()
        val palette = AppTheme.palette
        footer.applySpec(roleSpec(TextRole.BODY_LARGE))
        footer.setPadding(context.dp(4), footer.paddingTop + context.dp(4), context.dp(4), footer.paddingBottom + context.dp(4))
        footer.setTextColor(palette.onBackground)
        empty.applySpec(roleSpec(TextRole.TITLE_LARGE))
        empty.setTextColor(palette.onSurface)
        alphabet.setup(context.getString(R.string.jump_letters))
        (alphabetSpacer.layoutParams as LinearLayout.LayoutParams).width = alphabet.reservedWidthPx()
    }

    /** Clamp focus into a shorter list and update the empty state, footer and letter bar */
    private fun onItemCountChanged(size: Int) {
        val oldSize = itemsSize
        itemsSize = size
        val isEmpty = size == 0
        content.visibility = if (isEmpty) GONE else VISIBLE
        empty.visibility = if (isEmpty) VISIBLE else GONE
        alphabet.visibility = if (config.showLetterButtons && !isEmpty) VISIBLE else GONE
        alphabetSpacer.visibility = if (alphabet.visibility == VISIBLE) GONE else VISIBLE
        if (!isEmpty && focusedIndex >= size) {
            // A smaller result set: focus the last card
            selectAndFocus(size - 1, focus = hasFocus())
        } else if (oldSize != size) {
            updateFooterAndLetter()
        }
    }

    private fun focusOn(index: Int) {
        if (index != focusedIndex) previouslyFocusedIndex = focusedIndex
        focusedIndex = index
        updateFooterAndLetter()
    }

    private fun onCardFocused(index: Int) {
        focusOn(index)
        onPosition?.invoke(config.columns, index)
    }

    private fun updateFooterAndLetter() {
        footer.text = "${(focusedIndex + 1).takeIf { it > 0 } ?: "?"} / $itemsSize"
        val letters = alphabet.letters
        if (letters.isEmpty()) return
        val current =
            sortNameAt(focusedIndex)
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

    /** Select [index], scrolling to it, and move focus to its card once laid out when [focus] */
    private fun selectAndFocus(
        index: Int,
        focus: Boolean = true,
        smooth: Boolean = false,
    ) {
        if (index !in 0 until itemsSize) return
        focusOn(index)
        val task =
            ViewHolderTask { holder ->
                if (focus) ((holder as? CardViewHolder)?.card?.frame ?: holder.itemView).requestFocus()
            }
        if (smooth) grid.setSelectedPositionSmooth(index, task) else grid.setSelectedPosition(index, task)
    }

    private fun jumpRows(): Int {
        val columns = config.columns
        return when {
            itemsSize >= 25_000 -> columns * 500
            itemsSize >= 7_000 -> columns * 50
            itemsSize >= 2_000 -> columns * 15
            else -> columns * 6
        }
    }

    private fun jump(delta: Int) {
        if (itemsSize == 0) return
        val first = grid.firstVisiblePosition().takeIf { it >= 0 } ?: focusedIndex
        selectAndFocus((first + delta).coerceIn(0, itemsSize - 1))
    }

    private fun jumpToTop() {
        // If close, animate the scroll
        selectAndFocus(0, smooth = focusedIndex < config.columns * 6)
    }

    private fun jumpToLetter(letter: Char) {
        val owner = findViewTreeLifecycleOwner() ?: return
        letterJob?.cancel()
        letterJob =
            owner.lifecycleScope.launch(ExceptionHandler()) {
                val position = withContext(WholphinDispatchers.IO) { letterPosition(letter.uppercaseChar()) }
                Timber.d("Alphabet jump to $position")
                if (position >= 0 && itemsSize > 0) selectAndFocus(position.coerceAtMost(itemsSize - 1))
            }
    }

    /** BACK from the page: jump to the first card when another card is focused. True when consumed. */
    fun onBackPressed(): Boolean {
        if (!hasFocus() || focusedIndex <= 0 || itemsSize == 0) return false
        jumpToTop()
        return true
    }

    /** WeaselTV column-preserving UP / DOWN, before the cards see the key (`onPreviewKeyEvent` on the grid) */
    internal fun previewGridKey(event: KeyEvent): Boolean {
        if (!AppTheme.isNeon || event.action != KeyEvent.ACTION_DOWN) return false
        val down = event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN
        if (!down && event.keyCode != KeyEvent.KEYCODE_DPAD_UP) return false
        val columns = config.columns
        // The selection moves at once, so repeats keep counting from the destination before it is laid out
        val from = grid.selectedPosition.takeIf { it >= 0 } ?: focusedIndex
        val nextRow = from / columns + if (down) 1 else -1
        if (nextRow < 0) return false // Leave for the toolbar
        if (nextRow * columns >= itemsSize) return true
        val target = (nextRow * columns + from % columns).coerceAtMost(itemsSize - 1)
        focusOn(target)
        grid.setSelectedPositionSmooth(target)
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (super.dispatchKeyEvent(event)) return true
        return onGridKey(event)
    }

    private fun onGridKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN && event.isLongPress) {
            longPressing = true
            val newPosition = previouslyFocusedIndex
            if (newPosition > 0) selectAndFocus(newPosition)
            return true
        } else if (event.action == KeyEvent.ACTION_UP) {
            if (longPressing && code == KeyEvent.KEYCODE_BACK) {
                longPressing = false
                return true
            }
            longPressing = false
        }
        if (event.action != KeyEvent.ACTION_UP) return false
        return when {
            code == KeyEvent.KEYCODE_BACK && focusedIndex > 0 && itemsSize > 0 -> {
                jumpToTop()
                true
            }

            isPlayKeyUp(event) -> {
                playAt(focusedIndex)
                true
            }

            isForwardKey(code) -> {
                jump(jumpRows())
                true
            }

            isBackwardKey(code) -> {
                jump(-jumpRows())
                true
            }

            config.showLetterButtons && itemsSize > 0 && code in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> {
                jumpToLetter(event.unicodeChar.toChar())
                true
            }

            else -> {
                false
            }
        }
    }

    /** The focused card's index */
    val selectedIndex: Int get() = focusedIndex

    companion object {
        /**
         * `cardGridRowHeight`: the first-row height a grid of [GridCardView]s needs in a detail page header, from the
         * grid's geometry: top padding, a card with a one-line title, and an 8dp gutter.
         */
        fun rowHeightPx(
            context: Context,
            gridWidthPx: Int,
            columns: Int,
            spacingDp: Float,
            imageAspectRatio: Float,
            showTitle: Boolean,
            topPaddingDp: Float = if (AppTheme.isNeon) 32f else 16f,
        ): Int {
            val spacing = context.dpF(if (AppTheme.isNeon) ApprovedHomeLayout.CARD_SPACING_DP.toFloat() else spacingDp)
            // The letter column (or its same-width spacer) is always reserved beside the grid
            val cardsWidth = gridWidthPx - context.dpF(16f) - AlphabetPickerView.reservedWidthPx(context)
            val cardWidth = ((cardsWidth - spacing * (columns - 1)) / columns).roundToInt()
            return context.dp(topPaddingDp) + GridCardView.heightPx(context, cardWidth, imageAspectRatio, showTitle, titleLines = 1) +
                context.dp(8)
        }
    }
}

/** The `VerticalGridView` of a [CardGridView] */
class CardVerticalGrid(
    context: Context,
) : VerticalGridView(context) {
    internal var cardGrid: CardGridView? = null
    private val clip = Rect()

    init {
        clipChildren = false
        clipToPadding = false
        itemAnimator = null
        // UP from the first row may leave (toolbar); DOWN from the last stays
        (layoutManager as? GridLayoutManager)?.setFocusOutAllowed(true, false)
        windowAlignment = BaseGridView.WINDOW_ALIGN_BOTH_EDGE
        itemAlignmentOffset = 0
        itemAlignmentOffsetPercent = 0f
        isItemAlignmentOffsetWithPadding = false
    }

    internal fun applyScroll(mode: GridScrollMode) {
        if (AppTheme.isNeon) {
            focusScrollStrategy = BaseGridView.FOCUS_SCROLL_ITEM
            return
        }
        focusScrollStrategy = BaseGridView.FOCUS_SCROLL_ALIGNED
        when (mode) {
            GridScrollMode.Pivot -> {
                windowAlignmentOffset = 0
                windowAlignmentOffsetPercent = 30f
            }

            is GridScrollMode.Top -> {
                windowAlignmentOffset = context.dp(mode.spaceAboveDp)
                windowAlignmentOffsetPercent = BaseGridView.WINDOW_ALIGN_OFFSET_PERCENT_DISABLED
            }
        }
    }

    private var columnsCount = 1

    override fun setNumColumns(numColumns: Int) {
        columnsCount = numColumns
        super.setNumColumns(numColumns)
    }

    /** Column width for a grid [width] px wide (Leanback divides the width the same way) */
    internal fun cellWidthFor(width: Int): Int {
        if (width <= 0) return 0
        val columns = columnsCount.coerceAtLeast(1)
        return ((width - paddingLeft - paddingRight - horizontalSpacing * (columns - 1)) / columns).coerceAtLeast(0)
    }

    override fun onMeasure(
        widthSpec: Int,
        heightSpec: Int,
    ) {
        // Card sizes follow the column width; set it before the cards are measured
        (adapter as? CardGridAdapter<*>)?.cellWidthPx = cellWidthFor(MeasureSpec.getSize(widthSpec))
        super.onMeasure(widthSpec, heightSpec)
    }

    override fun onLayout(
        changed: Boolean,
        l: Int,
        t: Int,
        r: Int,
        b: Int,
    ) {
        super.onLayout(changed, l, t, r, b)
        // Clip the scrolling axis only (Compose clipScrollableContainer allows 30dp beyond the cross axis)
        val extra = context.dp(30)
        clip.set(-extra, 0, r - l + extra, b - t)
        clipBounds = clip
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (cardGrid?.previewGridKey(event) == true) return true
        return super.dispatchKeyEvent(event)
    }

    /** The first card at least partly visible, or -1 */
    internal fun firstVisiblePosition(): Int {
        var best = -1
        var bestTop = Int.MAX_VALUE
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.bottom <= 0 || child.top >= height) continue
            val pos = getChildAdapterPosition(child)
            if (pos == RecyclerView.NO_POSITION) continue
            if (child.top < bestTop || (child.top == bestTop && pos < best)) {
                bestTop = child.top
                best = pos
            }
        }
        return best
    }
}

/**
 * `AlphabetButtons`: the letter jump bar beside a grid. Focus enters at the current letter (the focused card's), the
 * bar scrolls to keep the current letter visible, OK jumps. WeaselTV: glyphs in white, the current / focused one in the
 * page accent with a glow; other themes: small round buttons, dimmed until the bar has focus.
 */
class AlphabetPickerView(
    context: Context,
) : ScrollView(context) {
    var letters: String = ""
        private set
    var onLetter: (Char) -> Unit = {}
    private var accent = NeonColors.VOLT
    private var current: Char? = null
    private val column = LinearLayout(context)
    private val cells = ArrayList<LetterView>()
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    init {
        isVerticalScrollBarEnabled = false
        isFillViewport = false
        clipChildren = false
        column.orientation = LinearLayout.VERTICAL
        column.clipChildren = false
        val padV = context.dpF(1.1f).roundToInt()
        val padH = context.dp(2)
        column.setPadding(padH, padV, padH, padV)
        addView(column, LayoutParams(WRAP, WRAP))
    }

    /** Build the cells for [letters] (R.string.jump_letters); [accent] is the page accent */
    fun setup(
        letters: String,
        accent: Int = this.accent,
    ) {
        this.accent = accent
        if (letters == this.letters) {
            cells.forEach { it.refresh() }
            return
        }
        this.letters = letters
        column.removeAllViews()
        cells.clear()
        val size = cellSizePx(context)
        val gap = context.dpF(1.1f).roundToInt()
        letters.forEachIndexed { index, c ->
            val cell = LetterView(context, c)
            column.addView(
                cell,
                LinearLayout.LayoutParams(size, size).apply { if (index > 0) topMargin = gap },
            )
            cells += cell
        }
    }

    /** The page accent for the WeaselTV letters (`LocalNeonSectionAccent`) */
    fun setAccent(accent: Int) {
        this.accent = accent
        cells.forEach { it.refresh() }
    }

    fun setCurrentLetter(letter: Char) {
        if (letter == current) return
        current = letter
        cells.forEach { it.refresh() }
        val cell = cells.getOrNull(letters.indexOf(letter)) ?: return
        post {
            val top = cell.top + column.top
            val bottom = cell.bottom + column.top
            if (top < scrollY || bottom > scrollY + height) smoothScrollTo(0, top - column.paddingTop)
        }
    }

    /** Width reserved for the bar */
    fun reservedWidthPx(): Int = reservedWidthPx(context).roundToInt()

    override fun addFocusables(
        views: ArrayList<View>,
        direction: Int,
        focusableMode: Int,
    ) {
        // Focus enters at the current letter
        if (!hasFocus()) {
            val cell = cells.getOrNull(letters.indexOf(current ?: letters.firstOrNull() ?: ' ')) ?: cells.firstOrNull()
            if (cell != null && cell.isShown) {
                views.add(cell)
                return
            }
        }
        super.addFocusables(views, direction, focusableMode)
    }

    override fun onRequestFocusInDescendants(
        direction: Int,
        previouslyFocusedRect: Rect?,
    ): Boolean {
        val cell = cells.getOrNull(letters.indexOf(current ?: ' ')) ?: cells.firstOrNull()
        return cell?.requestFocus(direction, previouslyFocusedRect) ?: super.onRequestFocusInDescendants(direction, previouslyFocusedRect)
    }

    override fun requestChildFocus(
        child: View?,
        focused: View?,
    ) {
        super.requestChildFocus(child, focused)
        cells.forEach { it.refresh() }
    }

    override fun clearChildFocus(child: View?) {
        super.clearChildFocus(child)
        cells.forEach { it.refresh() }
    }

    private inner class LetterView(
        context: Context,
        val letter: Char,
    ) : AppCompatTextView(context) {
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        init {
            isFocusable = true
            isClickable = true
            gravity = Gravity.CENTER
            text = letter.toString()
            contentDescription = letter.toString()
            includeFontPadding = false
            setOnClickListener { onLetter(letter) }
            refresh()
        }

        fun refresh() {
            val neon = AppTheme.isNeon
            val palette = AppTheme.palette
            val isCurrent = letter == current
            val focused = isFocused
            val spec = roleSpec(TextRole.BODY_SMALL)
            setTextSizeSp(spec.sizeSp)
            typeface = AppFonts.body(context, spec.weight)
            letterSpacing = spec.trackingEm
            if (neon) {
                val active = isCurrent || focused
                setTextColor(if (active) accent else 0xFFFFFFFF.toInt())
                if (active) setShadowLayer(context.dpF(6f), 0f, 0f, neonGlowAccent(accent)) else setShadowLayer(0f, 0f, 0f, 0)
                alpha = 1f
                bg.color = 0
            } else {
                setShadowLayer(0f, 0f, 0f, 0)
                // Keep the selected letter fully visible while the bar is unfocused
                val pickerFocused = this@AlphabetPickerView.hasFocus()
                alpha =
                    when {
                        isCurrent && !pickerFocused -> 1f
                        pickerFocused -> .85f
                        else -> .25f
                    }
                // tv-material3 Button: default colours for the current letter, transparent otherwise
                bg.color =
                    when {
                        focused && isCurrent -> palette.onSurface
                        focused -> palette.primaryContainer
                        isCurrent -> withAlpha(palette.onSurface, .8f)
                        else -> 0
                    }
                setTextColor(
                    when {
                        isCurrent && focused -> palette.border
                        isCurrent -> palette.tertiary
                        focused -> palette.onPrimaryContainer
                        else -> palette.onSurface
                    },
                )
            }
            invalidate()
        }

        private fun setTextSizeSp(sp: Float) {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        }

        override fun onFocusChanged(
            gainFocus: Boolean,
            direction: Int,
            previouslyFocusedRect: Rect?,
        ) {
            super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
            if (gainFocus && AppTheme.isNeon) audio.playSoundEffect(AudioManager.FX_FOCUS_NAVIGATION_UP)
            refresh()
        }

        override fun onDraw(canvas: Canvas) {
            if (bg.color ushr 24 != 0) {
                rect.set(0f, 0f, width.toFloat(), height.toFloat())
                canvas.drawOval(rect, bg)
            }
            super.onDraw(canvas)
        }
    }

    companion object {
        /** Cell size: 14dp, or on WeaselTV enough for a scaled "W" (bodySmall, 14sp line) */
        fun cellSizePx(context: Context): Int {
            val base = context.dp(14)
            if (!AppTheme.isNeon) return base
            val spec = roleSpec(TextRole.BODY_SMALL)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.typeface = AppFonts.body(context, spec.weight)
            paint.textSize = context.spPx(spec.sizeSp)
            paint.letterSpacing = spec.trackingEm
            val glyphWidth = paint.measureText("W").roundToInt()
            val lineHeight = context.spPx(14f).roundToInt()
            return maxOf(base, glyphWidth, lineHeight)
        }

        /** The bar's width: 2dp padding on each side of a cell */
        fun reservedWidthPx(context: Context): Float = context.dpF(2f) * 2 + cellSizePx(context)
    }
}
