package com.github.damontecres.wholphin.leanback.home

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.filter.CommunityRatingFilter
import com.github.damontecres.wholphin.data.filter.DecadeFilter
import com.github.damontecres.wholphin.data.filter.FavoriteFilter
import com.github.damontecres.wholphin.data.filter.FilterValueOption
import com.github.damontecres.wholphin.data.filter.FilterVideoType
import com.github.damontecres.wholphin.data.filter.GenreFilter
import com.github.damontecres.wholphin.data.filter.ItemFilterBy
import com.github.damontecres.wholphin.data.filter.OfficialRatingFilter
import com.github.damontecres.wholphin.data.filter.PlayedFilter
import com.github.damontecres.wholphin.data.filter.StudioFilter
import com.github.damontecres.wholphin.data.filter.VideoTypeFilter
import com.github.damontecres.wholphin.data.filter.YearFilter
import com.github.damontecres.wholphin.data.model.GetItemsFilter
import com.github.damontecres.wholphin.leanback.theme.AppFonts
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.NeonColors
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.applyTextRole
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.surfaceAtElevation
import com.github.damontecres.wholphin.leanback.theme.withAlpha
import com.github.damontecres.wholphin.leanback.widget.control.AppButton
import com.github.damontecres.wholphin.leanback.widget.control.AppDropdownMenu
import com.github.damontecres.wholphin.leanback.widget.control.AppListItem
import com.github.damontecres.wholphin.leanback.widget.control.CircularProgressView
import com.github.damontecres.wholphin.leanback.widget.control.DropdownMenuItemView
import com.github.damontecres.wholphin.leanback.widget.control.ListLeading
import com.github.damontecres.wholphin.leanback.widget.control.SliderBarView
import com.github.damontecres.wholphin.leanback.widget.control.TvSwitchView
import com.github.damontecres.wholphin.leanback.widget.dialog.AppDialog
import com.github.damontecres.wholphin.leanback.widget.dialog.DialogOptions
import com.github.damontecres.wholphin.leanback.widget.dialog.MenuDialog
import com.github.damontecres.wholphin.leanback.widget.dialog.MenuItem
import com.github.damontecres.wholphin.preferences.AppChoicePreference
import com.github.damontecres.wholphin.preferences.AppClickablePreference
import com.github.damontecres.wholphin.preferences.AppPreference
import com.github.damontecres.wholphin.preferences.AppSliderPreference
import com.github.damontecres.wholphin.preferences.AppSwitchPreference
import com.github.damontecres.wholphin.ui.data.SortAndDirection
import com.github.damontecres.wholphin.ui.data.getStringRes
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import java.util.UUID

/*
 * The sort, filter and view options controls of this area's folder pages (Watchlist, Favorites): private versions of
 * LIBRARY's `SortByButton`, `FilterByButton` and `ViewOptionsDialog` (runbooks/no-compose/CONTRACT.md section 1).
 */

/** A Font Awesome glyph as a drawable (for dropdown rows' leading slot); it follows the row's tint */
internal class GlyphDrawable(
    context: Context,
    @StringRes glyph: Int,
    sizeSp: Float = 16f,
) : Drawable() {
    private val text = context.getString(glyph)
    private val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = AppFonts.icons(context)
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp, context.resources.displayMetrics)
            textAlign = Paint.Align.CENTER
            color = AppTheme.palette.onSurface
        }
    private var tint: ColorStateList? = null

    override fun draw(canvas: Canvas) {
        val b = bounds
        val y = b.exactCenterY() - (paint.descent() + paint.ascent()) / 2
        canvas.drawText(text, b.exactCenterX(), y, paint)
    }

    override fun setTintList(tint: ColorStateList?) {
        this.tint = tint
        tint?.let { paint.color = it.getColorForState(state, it.defaultColor) }
        invalidateSelf()
    }

    override fun isStateful(): Boolean = tint?.isStateful == true

    override fun onStateChange(state: IntArray): Boolean {
        val t = tint ?: return false
        paint.color = t.getColorForState(state, t.defaultColor)
        invalidateSelf()
        return true
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = paint.textSize.toInt()

    override fun getIntrinsicHeight(): Int = paint.textSize.toInt()
}

/** Text in the Font Awesome font inside a label */
private class IconFontSpan(
    private val typeface: Typeface,
) : MetricAffectingSpan() {
    override fun updateDrawState(tp: TextPaint) {
        tp.typeface = typeface
    }

    override fun updateMeasureState(tp: TextPaint) {
        tp.typeface = typeface
    }
}

/** `SortAndDirection.toAnnotatedString`: the direction caret in Font Awesome, a space and the sort's name */
internal fun sortLabel(
    context: Context,
    sort: SortAndDirection,
    allCaps: Boolean,
): CharSequence {
    val caret = context.getString(if (sort.direction == SortOrder.ASCENDING) R.string.fa_caret_up else R.string.fa_caret_down)
    val name = context.getString(getStringRes(sort.sort)).let { if (allCaps) it.uppercase() else it }
    return SpannableStringBuilder(caret).apply {
        setSpan(IconFontSpan(AppFonts.icons(context)), 0, caret.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        append(" ")
        append(name)
    }
}

/**
 * `SortByButton`: a text button showing the current sort; OK opens the list of [options] (the current one with its
 * direction caret; choosing it again reverses the direction), OK held reverses the current sort.
 */
internal class SortButton(
    context: Context,
) : FrameLayout(context) {
    val button: AppButton = AppButton.text(context, "") { showMenu() }
    private var options: List<ItemSortBy> = emptyList()
    private var current: SortAndDirection = SortAndDirection.DEFAULT
    var onSortChange: (SortAndDirection) -> Unit = {}

    init {
        clipChildren = false
        addView(button, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        button.setOnLongClickListener {
            onSortChange(current.flip())
            true
        }
    }

    fun bind(
        options: List<ItemSortBy>,
        current: SortAndDirection,
    ) {
        this.options = options
        this.current = current
        button.setLabel(sortLabel(context, current, AppTheme.isNeon))
    }

    private fun showMenu() {
        val menu = AppDropdownMenu(button)
        options.forEach { option ->
            val row =
                menu.addItem(context.getString(getStringRes(option))) {
                    menu.dismiss()
                    val direction = if (current.sort == option) current.direction.flip() else current.direction
                    onSortChange(SortAndDirection(option, direction))
                }
            if (option == current.sort) {
                row.setLeadingIconDrawable(
                    GlyphDrawable(context, if (current.direction == SortOrder.ASCENDING) R.string.fa_caret_up else R.string.fa_caret_down),
                )
            }
        }
        menu.show()
    }
}

/** A leading drawable on a dropdown row (the old `leadingIcon` with a glyph or a check) */
internal fun DropdownMenuItemView.setLeadingIconDrawable(drawable: Drawable) {
    setLeadingIcon(R.drawable.lb_widget_ctl_ic_check)
    (getChildAt(0) as? android.widget.ImageView)?.setImageDrawable(drawable)
}

/**
 * `FilterByButton` / `ExpandableFilterButton`: the filter glyph, the number of active filters, and "Filter" while
 * focused. OK opens the filter list (Remove all when any is set, then each filter with a check when set); a filter
 * opens its values (loaded on demand) with Remove when set; filters taking several values stay open as values are
 * toggled, the others close after a choice.
 */
internal class FilterButton(
    context: Context,
    private val owner: LifecycleOwner,
) : FrameLayout(context) {
    val button = AppButton(context)
    private var options: List<ItemFilterBy<*>> = emptyList()
    private var current: GetItemsFilter = GetItemsFilter()
    private var count = 0
    var onFilterChange: (GetItemsFilter) -> Unit = {}
    var getPossibleValues: suspend (ItemFilterBy<*>) -> List<FilterValueOption> = { emptyList() }

    /** The filter list is open (focus returns here when results reload) */
    var onShow: ((Boolean) -> Unit)? = null
    private var lastOpened: ItemFilterBy<*>? = null
    private var mainOpen = false

    init {
        clipChildren = false
        button.setGlyph(R.string.fa_filter)
        button.contentHeightDp = 40f
        button.exactContentHeight = true
        button.minimumWidth = context.dp(40)
        button.setContentPaddingDp(0f, 0f, 0f, 0f)
        button.setOnClickListener {
            onShow?.invoke(true)
            showMain()
        }
        button.setOnFocusChangeListener { _, _ -> updateLabel() }
        addView(button, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    fun bind(
        options: List<ItemFilterBy<*>>,
        current: GetItemsFilter,
    ) {
        this.options = options
        this.current = current
        count = current.countFilters(options)
        updateLabel()
    }

    private fun updateLabel() {
        val focused = button.isFocused
        val label = context.getString(R.string.filter).let { if (AppTheme.isNeon) it.uppercase() else it }
        val text =
            buildString {
                if (count > 0) append(count)
                if (focused) {
                    if (isNotEmpty()) append("  ")
                    append(label)
                }
            }
        button.setLabel(text.ifEmpty { null })
        button.setLabelPaddingDp(if (count > 0) 0f else 2f, if (focused) 8f else 4f)
    }

    private fun showMain() {
        mainOpen = true
        val menu = AppDropdownMenu(button)
        if (count > 0) {
            removeRow(menu) {
                menu.dismiss()
                onFilterChange(current.delete(options))
            }
            menu.addView(divider())
        }
        var focusRow: View? = null
        options.forEach { option ->
            val row =
                menu.addItem(context.getString(option.stringRes)) {
                    lastOpened = option
                    mainOpen = false
                    menu.onDismissRequest = null
                    menu.dismiss()
                    showNested(option)
                }
            if (option.get(current) != null) row.setLeadingIcon(R.drawable.lb_widget_ctl_ic_check)
            if (option == (lastOpened?.takeIf { it in options } ?: options.firstOrNull())) focusRow = row
        }
        menu.onDismissRequest = {
            mainOpen = false
            onShow?.invoke(false)
        }
        menu.show()
        focusRow?.requestFocus()
    }

    @Suppress("UNCHECKED_CAST")
    private fun showNested(option: ItemFilterBy<*>) {
        val filterOption = option as ItemFilterBy<Any>
        val menu = AppDropdownMenu(button, containerColor = AppTheme.palette.surfaceAtElevation(5f))
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        menu.addView(column)
        var values: List<FilterValueOption>? = null

        fun build() {
            column.removeAllViews()
            column.addView(
                TextView(context).apply {
                    applyTextRole(TextRole.TITLE_MEDIUM)
                    setTextColor(AppTheme.palette.onSurface)
                    setText(filterOption.stringRes)
                    setPadding(context.dp(16), context.dp(8), context.dp(16), context.dp(8))
                },
            )
            column.addView(divider())
            val currentValue = filterOption.get(current)
            if (currentValue != null) {
                column.addView(
                    removeRow(null) {
                        onFilterChange(filterOption.set(null, current))
                        menu.dismiss()
                    },
                )
                column.addView(divider())
            }
            val list = values
            when {
                list == null -> {
                    column.addView(CircularProgressView(context), LinearLayout.LayoutParams(context.dp(48), context.dp(48)))
                }

                list.isEmpty() -> {
                    column.addView(
                        TextView(context).apply {
                            applyTextRole(TextRole.BODY_MEDIUM)
                            setTextColor(AppTheme.palette.onSurface)
                            setText(R.string.no_results)
                        },
                    )
                }

                else -> {
                    var first: View? = null
                    list.forEach { value ->
                        val selected = isSelected(filterOption, currentValue, value)
                        val row = DropdownMenuItemView(context, elevationDp = 8f)
                        if (filterOption == CommunityRatingFilter) {
                            row.setText(
                                SpannableStringBuilder("${value.name}+ ").apply {
                                    val star = context.getString(R.string.fa_star)
                                    val start = length
                                    append(star)
                                    setSpan(IconFontSpan(AppFonts.icons(context)), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                                },
                            )
                        } else {
                            row.setText(value.name)
                        }
                        if (selected) row.setLeadingIcon(R.drawable.lb_widget_ctl_ic_check)
                        row.setOnClickListener {
                            val newFilter = toggled(filterOption, currentValue, value, selected)
                            current = newFilter
                            onFilterChange(newFilter)
                            if (!filterOption.supportMultiple) {
                                menu.dismiss()
                            } else {
                                build()
                                column.getChildAt(column.indexOfChildFocusable(value))?.requestFocus()
                            }
                        }
                        row.tag = value
                        column.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                        if (first == null) first = row
                    }
                    first?.requestFocus()
                }
            }
        }
        build()
        menu.onDismissRequest = {
            // Back to the filter list, on the filter just opened
            showMain()
        }
        menu.show()
        owner.lifecycleScope.launch {
            values = getPossibleValues(filterOption)
            if (menu.isShowing) build()
        }
    }

    private fun LinearLayout.indexOfChildFocusable(value: FilterValueOption): Int = (0 until childCount).firstOrNull { getChildAt(it).tag == value } ?: -1

    private fun removeRow(
        menu: AppDropdownMenu?,
        onClick: () -> Unit,
    ): DropdownMenuItemView {
        val row = DropdownMenuItemView(context)
        row.setText(context.getString(R.string.remove))
        row.setLeadingIcon(R.drawable.lb_widget_ctl_ic_delete, tint = withAlpha(0xFFFF0000.toInt(), .8f))
        row.onContentColor = { color ->
            row.textView.setTextColor(if (row.isFocused) AppTheme.palette.onError else AppTheme.palette.error)
        }
        row.setOnClickListener { onClick() }
        menu?.addView(row)
        return row
    }

    private fun divider(): View =
        View(context).apply {
            setBackgroundColor(if (AppTheme.isNeon) NeonColors.LINE2 else AppTheme.palette.borderVariant)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(1))
        }

    @Suppress("UNCHECKED_CAST")
    private fun isSelected(
        option: ItemFilterBy<Any>,
        currentValue: Any?,
        value: FilterValueOption,
    ): Boolean =
        when (option) {
            GenreFilter, StudioFilter -> (currentValue as? List<UUID>).orEmpty().contains(value.value)
            FavoriteFilter, PlayedFilter -> (currentValue as? Boolean) == value.name.toBoolean()
            OfficialRatingFilter -> (currentValue as? List<String>).orEmpty().contains(value.name)
            VideoTypeFilter -> (currentValue as? List<FilterVideoType>).orEmpty().contains(value.value)
            YearFilter, DecadeFilter -> (currentValue as? List<Int>).orEmpty().contains(value.value)
            CommunityRatingFilter -> (currentValue as? Int) == value.value
        }

    @Suppress("UNCHECKED_CAST")
    private fun toggled(
        option: ItemFilterBy<Any>,
        currentValue: Any?,
        value: FilterValueOption,
        selected: Boolean,
    ): GetItemsFilter {
        fun <T> toggle(
            list: List<T>,
            item: T,
        ): List<T>? = list.toMutableList().apply { if (selected) remove(item) else add(item) }.takeIf { it.isNotEmpty() }
        return when (option) {
            GenreFilter, StudioFilter -> option.set(toggle((currentValue as? List<UUID>).orEmpty(), value.value as UUID), current)
            FavoriteFilter, PlayedFilter -> option.set(value.name.toBoolean(), current)
            OfficialRatingFilter -> option.set(toggle((currentValue as? List<String>).orEmpty(), value.name), current)
            VideoTypeFilter -> option.set(toggle((currentValue as? List<FilterVideoType>).orEmpty(), value.value as FilterVideoType), current)
            YearFilter, DecadeFilter -> option.set(toggle((currentValue as? List<Int>).orEmpty(), value.value as Int), current)
            CommunityRatingFilter -> option.set(value.value as? Int, current)
        }
    }
}

/**
 * `PreferenceOptionsDialog` with the view options (`ViewOptionsDialog`): a 320dp panel at the right of the screen
 * (no dim), at most 480dp tall, titled "View options", listing [options] for [value]: choices (OK picks from a list),
 * switches, sliders (LEFT / RIGHT change the value) and Reset ([onReset]). Every change goes to [onChange] at once.
 */
internal class PreferenceOptionsDialog<P : Any>(
    context: Context,
    private var value: P,
    private val options: List<AppPreference<P, *>>,
    private val onChange: (P) -> Unit,
    private val onReset: () -> Unit,
    accent: Int,
) : AppDialog(
        context,
        DialogOptions(usePlatformDefaultWidth = false, gravity = Gravity.END),
        accent,
    ) {
    private val list =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            val edge = context.dp(MenuDialog.DIALOG_LIST_EDGE_DP)
            setPadding(context.dp(16), edge, context.dp(16), edge)
        }

    init {
        val column =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
            }
        column.addView(
            TextView(context).apply {
                applyTextRole(TextRole.TITLE_LARGE)
                setTextColor(AppTheme.palette.onSurface)
                text = context.getString(R.string.view_options).let { if (AppTheme.isNeon) it.uppercase() else it }
                setPadding(context.dp(24), context.dp(24), context.dp(24), context.dp(8))
            },
        )
        val scroller =
            object : ScrollView(context) {
                override fun onMeasure(
                    widthMeasureSpec: Int,
                    heightMeasureSpec: Int,
                ) {
                    super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(context.dp(420), MeasureSpec.AT_MOST))
                }
            }
        scroller.isVerticalScrollBarEnabled = false
        scroller.clipChildren = false
        scroller.addView(list)
        column.addView(scroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setPanelContent(column, FrameLayout.LayoutParams(context.dp(320), ViewGroup.LayoutParams.WRAP_CONTENT))
        build()
        window?.setDimAmount(0f)
    }

    /** Show [newValue] (the saved options changed, eg the layout switched the option list) */
    fun update(newValue: P) {
        val focusedIndex = (0 until list.childCount).firstOrNull { list.getChildAt(it).hasFocus() }
        value = newValue
        build()
        focusedIndex?.let { list.getChildAt(it.coerceAtMost(list.childCount - 1))?.requestFocus() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun build() {
        list.removeAllViews()
        options.forEach { pref ->
            val row: View =
                when (pref) {
                    is AppChoicePreference<P, *> -> choiceRow(pref as AppChoicePreference<P, Any?>)
                    is AppSwitchPreference<P> -> switchRow(pref)
                    is AppSliderPreference<P> -> sliderRow(pref)
                    is AppClickablePreference<P> -> clickRow(pref)
                    else -> return@forEach
                }
            list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        if (list.childCount > 0 && list.findFocus() == null) list.getChildAt(0).requestFocus()
    }

    private fun choiceRow(pref: AppChoicePreference<P, Any?>): View {
        val names = context.resources.getStringArray(pref.displayValues)
        val current = pref.getter(value)
        val index = pref.valueToIndex(current)
        return AppListItem(context).apply {
            setHeadline(context.getString(pref.title))
            setSupporting(names.getOrNull(index))
            setOnClickListener {
                MenuDialog
                    .show(
                        context,
                        context.getString(pref.title),
                        names.mapIndexed { i, name ->
                            MenuItem(
                                headline = name,
                                leading = ListLeading.SelectedDot(i == index),
                                dismissOnClick = true,
                                onClick = { change(pref.setter(value, pref.indexToValue(i))) },
                            )
                        },
                        opener = this,
                    )
            }
        }
    }

    private fun switchRow(pref: AppSwitchPreference<P>): View {
        val checked = pref.getter(value)
        val switch = TvSwitchView(context).apply { this.checked = checked }
        return AppListItem(context).apply {
            setHeadline(context.getString(pref.title))
            pref.summary(context, checked)?.let { setSupporting(it) }
            setTrailingView(switch)
            setOnClickListener { change(pref.setter(value, !checked)) }
        }
    }

    private fun sliderRow(pref: AppSliderPreference<P>): View {
        val current = pref.getter(value)
        val column =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(context.dp(16), context.dp(8), context.dp(16), context.dp(8))
                clipChildren = false
            }
        val label =
            TextView(context).apply {
                applyTextRole(TextRole.TITLE_MEDIUM)
                setTextColor(AppTheme.palette.onSurface)
                text = "${context.getString(pref.title)}: ${pref.summary(context, current) ?: current}"
            }
        column.addView(label)
        val slider =
            SliderBarView(context).apply {
                min = pref.min
                max = pref.max
                interval = pref.interval
                this.value = current
                onChange = { newValue ->
                    label.text = "${context.getString(pref.title)}: ${pref.summary(context, newValue) ?: newValue}"
                    value = pref.setter(value, newValue)
                    onChange(value)
                }
            }
        column.addView(slider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(24)).apply { topMargin = context.dp(4) })
        return column
    }

    private fun clickRow(pref: AppClickablePreference<P>): View =
        AppListItem(context).apply {
            setHeadline(context.getString(pref.title))
            setOnClickListener { onReset() }
        }

    private fun change(newValue: P) {
        value = newValue
        onChange(newValue)
        update(newValue)
    }
}
