package com.github.damontecres.wholphin.leanback.search

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.leanback.widget.BaseGridView
import androidx.leanback.widget.VerticalGridView
import androidx.recyclerview.widget.RecyclerView
import com.github.damontecres.wholphin.data.model.DiscoverItem
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.CardDimens
import com.github.damontecres.wholphin.leanback.theme.NeonColors
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.applyTextRole
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.resolveNeonScope
import com.github.damontecres.wholphin.leanback.theme.withAlpha
import com.github.damontecres.wholphin.leanback.widget.control.AppButton
import com.github.damontecres.wholphin.leanback.widget.control.SearchEditTextBox
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.ui.nav.Destination

/*
 * Pieces shared by the Search area's screens (no-Compose UI, 2026-11-22): page gutters, the expandable buttons of the
 * old PlayButtons.kt, the search field's editing rules, a page list of rows and the switch of the old `SwitchColors()`.
 */

/** The WeaselTV page gutter at the side menu that section rules reach into (0 on other themes) */
internal fun Context.gutterStartPx(): Int = if (AppTheme.isNeon) dp(CardDimens.NEON_RULE_INSET_START_DP) else 0

/** The WeaselTV page gutter at the screen edge that section rules reach into (0 on other themes) */
internal fun Context.gutterEndPx(): Int = if (AppTheme.isNeon) dp(CardDimens.NEON_RULE_INSET_END_DP) else 0

/** Lay this view across the page gutters (negative margins), for rows whose section rules reach the edges */
internal fun <T : ViewGroup.MarginLayoutParams> T.bleedIntoGutters(context: Context): T =
    apply {
        marginStart = -context.gutterStartPx()
        marginEnd = -context.gutterEndPx()
    }

/** The old `onClickDiscover`: the library item when Seerr knows it, else the Requests details page */
internal fun discoverDestination(item: DiscoverItem): Destination =
    if (item.jellyfinItemId != null && item.type.baseItemKind != null) {
        Destination.MediaItem(itemId = item.jellyfinItemId, type = item.type.baseItemKind)
    } else {
        Destination.DiscoveredItem(item)
    }

/**
 * `ExpandableFaButton` (PlayButtons.kt): a Font Awesome glyph (16sp in a 32dp box) and the label, 40dp tall and at
 * least 40dp wide. WeaselTV: the uppercase label in the button type always shows (2 / 8dp around it, 4dp side
 * padding); other themes show the label (titleSmall, 4dp after it) only while focused, with 4dp padding all round.
 */
internal fun expandableFaButton(
    context: Context,
    @StringRes title: Int,
    @StringRes glyph: Int,
    titleOverride: String? = null,
): AppButton =
    AppButton(context).apply {
        val neon = AppTheme.isNeon
        minimumWidth = context.dp(40)
        setGlyph(glyph, sizeSp = 16f, boxDp = 32f)
        val label = titleOverride ?: context.getString(title)
        if (neon) {
            setContentPaddingDp(4f, 0f, 4f, 0f)
            contentHeightDp = 40f
            setLabel(label, allCaps = true)
            setLabelPaddingDp(2f, 8f)
        } else {
            setContentPaddingDp(4f, 4f, 4f, 4f)
            contentHeightDp = 32f
            labelView.applyTextRole(TextRole.TITLE_SMALL)
            setLabel(label)
            setLabelPaddingDp(0f, 4f)
            labelOnlyWhenFocused = true
        }
        exactContentHeight = true
        contentDescription = label
    }

/**
 * `ExpandablePlayButton` with an icon (PlayButtons.kt), for buttons that are not Play / Resume / Restart: a 28dp
 * icon (2dp in) and the label, 40dp tall. WeaselTV: the outline recipe with the uppercase label (6dp before, 8dp
 * after); other themes: the stock button with the titleSmall label shown only while focused (8dp before, 4dp after).
 */
internal fun expandableIconButton(
    context: Context,
    @StringRes title: Int,
    @DrawableRes icon: Int,
): AppButton =
    AppButton(context).apply {
        val neon = AppTheme.isNeon
        minimumWidth = context.dp(40)
        setIcon(icon, sizeDp = 28f)
        (iconView.layoutParams as ViewGroup.MarginLayoutParams).marginStart = context.dp(2)
        setContentPaddingDp(4f, 4f, 4f, 4f)
        contentHeightDp = 32f
        exactContentHeight = true
        if (neon) {
            setLabel(title, allCaps = true)
            setLabelPaddingDp(6f, 8f)
        } else {
            labelView.applyTextRole(TextRole.TITLE_SMALL)
            setLabel(title)
            setLabelPaddingDp(8f, 4f)
            labelOnlyWhenFocused = true
        }
        contentDescription = context.getString(title)
    }

/**
 * The editing rules every search page gave its `SearchEditTextBox`: the field is read-only until OK / Enter is
 * released on it (then the keyboard opens), so the D-pad moves past it to the buttons beside it; typing reports the
 * text ([onUserQuery]); the keyboard's Search action calls [onSubmit]; losing focus makes it read-only again.
 *
 * BACK while the field is focused ([handleBack]): while editing it stops editing and hides the keyboard, otherwise it
 * moves focus on to the next control, so BACK never leaves the page from the field (the old `BackHandler`).
 */
internal class SearchFieldController(
    val box: SearchEditTextBox,
) {
    /** Editing (Compose `isSearchActive`) */
    var active: Boolean = false
        private set

    /** The user changed the text */
    var onUserQuery: ((String) -> Unit)? = null

    /** The keyboard's Search action */
    var onSubmit: (() -> Unit)? = null

    /** False blocks OK from starting to edit (Requests: while a submitted search waits for its results) */
    var canActivate: () -> Boolean = { true }

    /** Called when editing starts or stops */
    var onActiveChanged: ((Boolean) -> Unit)? = null

    private var settingText = false

    init {
        box.readOnly = true
        box.onTextChanged = { text -> if (!settingText) onUserQuery?.invoke(text) }
        box.onSearch = { onSubmit?.invoke() }
        val inner = box.editText.onFocusChangeListener
        box.editText.setOnFocusChangeListener { v, focused ->
            inner?.onFocusChange(v, focused)
            if (!focused) setActive(false)
        }
    }

    val text: String get() = box.text

    /** Set the text without reporting it as typed (a voice result, a restored query) */
    fun setText(value: String) {
        settingText = true
        box.text = value
        settingText = false
    }

    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        box.readOnly = !value
        val imm = box.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (value) {
            // readOnly removed the key listener (input type none); a text input type brings it back
            box.editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            box.editText.setSelection(box.text.length)
            imm.showSoftInput(box.editText, 0)
        } else {
            imm.hideSoftInputFromWindow(box.editText.windowToken, 0)
        }
        onActiveChanged?.invoke(value)
    }

    /** Page-level key preview: OK / Enter released on the read-only field starts editing */
    fun handleKey(event: KeyEvent): Boolean {
        if (!box.editText.isFocused || active) return false
        val activation =
            event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                event.keyCode == KeyEvent.KEYCODE_ENTER ||
                event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (!activation || !canActivate()) return false
        if (event.action == KeyEvent.ACTION_UP) setActive(true)
        return true
    }

    /** BACK while the field is focused; see the class description */
    fun handleBack(): Boolean {
        if (!box.editText.isFocused) return false
        if (active) {
            setActive(false)
        } else {
            box.editText.focusSearch(View.FOCUS_FORWARD)?.requestFocus()
        }
        return true
    }
}

/**
 * A vertical page list of rows (the old page `LazyColumn`) on a Leanback [VerticalGridView]: each row is a view the
 * screen keeps (one per row key), so a row scrolled off screen keeps its cards, scroll and focus position, as the
 * Compose rows did. The grid draws the focused row last, so its card glow is on top.
 *
 * Scrolling: [keepVisible] scrolls only enough to show the focused row (WeaselTV detail pages,
 * `KeepVisibleBringIntoViewSpec`); otherwise the focused row's top sits 30 % down (the TV default pivot).
 */
internal class PageRowsView(
    context: Context,
    keepVisible: Boolean = AppTheme.isNeon,
) : VerticalGridView(context) {
    private val rowsAdapter = RowsAdapter()

    init {
        clipChildren = false
        clipToPadding = false
        itemAnimator = null
        setHasFixedSize(false)
        windowAlignment = BaseGridView.WINDOW_ALIGN_BOTH_EDGE
        itemAlignmentOffset = 0
        itemAlignmentOffsetPercent = 0f
        isItemAlignmentOffsetWithPadding = true
        if (keepVisible) {
            focusScrollStrategy = BaseGridView.FOCUS_SCROLL_ITEM
        } else {
            focusScrollStrategy = BaseGridView.FOCUS_SCROLL_ALIGNED
            windowAlignmentOffset = 0
            windowAlignmentOffsetPercent = 30f
        }
        adapter = rowsAdapter
    }

    /** The rows now shown, in order; views are re-used by identity */
    fun setRows(rows: List<View>) {
        rowsAdapter.setRows(rows)
    }

    val rows: List<View> get() = rowsAdapter.rows

    /** Select the row holding [row] (scrolling to it), then run [whenLaidOut] once that row is laid out */
    fun selectRow(
        row: View,
        whenLaidOut: (() -> Unit)? = null,
    ) {
        val index = rowsAdapter.rows.indexOf(row)
        if (index < 0) return
        if (whenLaidOut == null) {
            selectedPosition = index
        } else {
            setSelectedPosition(index) { _: RecyclerView.ViewHolder -> whenLaidOut() }
        }
    }

    private class Holder(
        val box: FrameLayout,
    ) : RecyclerView.ViewHolder(box)

    private class RowsAdapter : RecyclerView.Adapter<Holder>() {
        var rows: List<View> = emptyList()
            private set
        private val ids = HashMap<View, Long>()
        private var nextId = 0L

        init {
            setHasStableIds(true)
        }

        fun setRows(newRows: List<View>) {
            if (newRows == rows) return
            rows = newRows
            newRows.forEach { ids.getOrPut(it) { nextId++ } }
            ids.keys.retainAll(newRows.toSet())
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = rows.size

        override fun getItemId(position: Int): Long = ids[rows[position]] ?: RecyclerView.NO_ID

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): Holder {
            val box =
                FrameLayout(parent.context).apply {
                    clipChildren = false
                    clipToPadding = false
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
            return Holder(box)
        }

        override fun onBindViewHolder(
            holder: Holder,
            position: Int,
        ) {
            val row = rows[position]
            if (row.parent === holder.box) return
            (row.parent as? ViewGroup)?.removeView(row)
            holder.box.removeAllViews()
            val lp = row.layoutParams as? FrameLayout.LayoutParams
            holder.box.addView(
                row,
                lp ?: FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }

        override fun onViewRecycled(holder: Holder) {
            holder.box.removeAllViews()
        }
    }
}

/**
 * A tv-material3 `Switch` (40 x 24 track) in the colours of the old `SwitchColors()`: WeaselTV tracks `line2` / the
 * accent with `mid` / `onAccent` knobs and borders; GREEN to BROWN themes thumbs in onPrimary; the stock colours
 * elsewhere. Not focusable: it shows the state of the row it sits in.
 */
internal class SearchSwitchView(
    context: Context,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var accent: Int? = null

    var checked: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    init {
        isFocusable = false
        layoutParams = ViewGroup.LayoutParams(context.dp(40), context.dp(24))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        accent = resolveNeonScope().accent
        invalidate()
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        setMeasuredDimension(context.dp(40), context.dp(24))
    }

    override fun onDraw(canvas: Canvas) {
        val p = AppTheme.palette
        val density = resources.displayMetrics.density
        val neonAccent = accent ?: NeonColors.VOLT
        val (track, thumb, border) =
            when (AppTheme.theme) {
                AppThemeColors.WEASELTV -> {
                    if (checked) {
                        Triple(neonAccent, NeonColors.ON_ACCENT, neonAccent)
                    } else {
                        Triple(NeonColors.LINE2, NeonColors.MID, NeonColors.LINE2)
                    }
                }

                AppThemeColors.GREEN, AppThemeColors.ORANGE, AppThemeColors.BOLD_BLUE, AppThemeColors.OLED_BLACK,
                AppThemeColors.RED, AppThemeColors.BROWN,
                -> {
                    if (checked) Triple(p.primary, p.onPrimary, p.primary) else Triple(p.surfaceVariant, p.onPrimary, p.border)
                }

                else -> {
                    if (checked) Triple(p.primary, p.onPrimary, p.primary) else Triple(p.surfaceVariant, p.border, p.border)
                }
            }
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        val r = height / 2f
        paint.style = Paint.Style.FILL
        paint.color = track
        canvas.drawRoundRect(rect, r, r, paint)
        if (!checked || AppTheme.theme == AppThemeColors.WEASELTV) {
            val w = 1.5f * density
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = w
            paint.color = border
            rect.inset(w / 2f, w / 2f)
            canvas.drawRoundRect(rect, r - w / 2f, r - w / 2f, paint)
        }
        paint.style = Paint.Style.FILL
        val handle = (if (checked) 18f else 12f) * density
        val cx = if (checked) width - height / 2f else height / 2f
        paint.color = thumb
        canvas.drawCircle(cx, height / 2f, handle / 2f, paint)
    }
}

/** Text colour of a placeholder message: WeaselTV `mid`, else onBackground; errors in the error colour */
internal fun placeholderMessageColor(isError: Boolean): Int =
    when {
        isError -> AppTheme.palette.error
        AppTheme.isNeon -> NeonColors.MID
        else -> AppTheme.palette.onBackground
    }

/** Translucent version of the WeaselTV text colour */
internal fun neonTextAlpha(alpha: Float): Int = withAlpha(NeonColors.TEXT, alpha)
