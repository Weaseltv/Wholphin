package com.github.damontecres.wholphin.leanback.detail

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ImageSpan
import android.text.style.MetricAffectingSpan
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
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
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.applyTextRole
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.surfaceAtElevation
import com.github.damontecres.wholphin.leanback.widget.control.AppButton
import com.github.damontecres.wholphin.leanback.widget.control.AppDropdownMenu
import com.github.damontecres.wholphin.leanback.widget.control.CircularProgressView
import com.github.damontecres.wholphin.leanback.widget.control.DropdownMenuItemView
import com.github.damontecres.wholphin.leanback.widget.dialog.MenuDividerView
import com.github.damontecres.wholphin.leanback.widget.text.EMPTY_STAR_COLOR
import com.github.damontecres.wholphin.leanback.widget.text.FILLED_STAR_COLOR
import com.github.damontecres.wholphin.ui.data.SortAndDirection
import com.github.damontecres.wholphin.ui.data.flip
import com.github.damontecres.wholphin.ui.data.getStringRes
import com.github.damontecres.wholphin.util.ExceptionHandler
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import java.util.UUID

/*
 * The sort and filter buttons of the collection and playlist pages: SortByButton.kt (`SortByButton`,
 * `ExpandableSortButton`) and FilterByButton.kt (`FilterByButton`, `ExpandableFilterButton`) as Views. These are
 * DETAILS-private ports (LIBRARY owns the originals and builds its own Views); they use the CONTROLS dropdown menu.
 */

/** A span drawing its text in another typeface (Font Awesome glyphs inside a label) */
internal class TypefaceSpanCompat(
    private val typeface: Typeface,
) : MetricAffectingSpan() {
    override fun updateDrawState(tp: TextPaint) {
        tp.typeface = typeface
    }

    override fun updateMeasureState(tp: TextPaint) {
        tp.typeface = typeface
    }
}

/** "▲ NAME": the direction caret in Font Awesome, a space and the sort's name (`SortAndDirection.toAnnotatedString`) */
internal fun sortLabel(
    context: Context,
    sort: SortAndDirection,
    allCaps: Boolean,
): CharSequence {
    val caret = context.getString(if (sort.direction == SortOrder.ASCENDING) R.string.fa_caret_up else R.string.fa_caret_down)
    val name = context.getString(getStringRes(sort.sort)).let { if (allCaps) it.uppercase() else it }
    return SpannableStringBuilder()
        .append(caret, TypefaceSpanCompat(AppFonts.icons(context)), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        .append(" ")
        .append(name)
}

/**
 * `SortByButton`: [collapsed] is the sort icon that shows the current sort while focused (`ExpandableSortButton`);
 * otherwise a text button "▲ Name". OK opens the sort options (the current one shows its direction; choosing it again
 * reverses it); holding OK reverses the current sort.
 */
class SortButton(
    context: Context,
    private val sortOptions: List<ItemSortBy>,
    private val collapsed: Boolean,
    private val allCaps: Boolean = AppTheme.isNeon,
    private val onSortChange: (SortAndDirection) -> Unit,
) {
    private var current: SortAndDirection? = null

    val button: AppButton =
        if (collapsed) {
            ExpandableButton(context, collapsed = true, allCaps = true).apply {
                setContentPaddingDp(0f, 0f, 0f, 0f)
                contentHeightDp = MIN_BUTTON_DP
                exactContentHeight = true
                minimumWidth = context.dp(MIN_BUTTON_DP)
                setGlyph(R.string.fa_arrow_up_wide_short, sizeSp = 16f, boxDp = MIN_BUTTON_DP)
                setLabelPaddingDp(2f, 8f)
                labelOnlyWhenFocused = true
                // The icon's box narrows to 28dp while the label shows
                setOnFocusChangeListener { _, hasFocus ->
                    glyphView.layoutParams =
                        glyphView.layoutParams.apply { width = context.dp(if (hasFocus) MIN_BUTTON_DP - 12f else MIN_BUTTON_DP) }
                }
            }
        } else {
            AppButton.text(context, "") {}
        }

    init {
        button.setOnClickListener { showMenu() }
        button.setOnLongClickListener {
            current?.let { onSortChange(it.flip()) }
            true
        }
    }

    fun bind(current: SortAndDirection) {
        this.current = current
        val context = button.context
        if (collapsed) {
            button.setGlyph(
                if (current.direction == SortOrder.ASCENDING) R.string.fa_arrow_up_wide_short else R.string.fa_arrow_down_wide_short,
                sizeSp = 16f,
                boxDp = if (button.isFocused) MIN_BUTTON_DP - 12f else MIN_BUTTON_DP,
            )
            button.setLabel(context.getString(getStringRes(current.sort)), allCaps = true)
        } else {
            button.setLabel(sortLabel(context, current, allCaps))
        }
    }

    private fun showMenu() {
        val cur = current ?: return
        val context = button.context
        val menu = AppDropdownMenu(button)
        sortOptions.forEach { option ->
            val row = menu.addItem(context.getString(getStringRes(option))) {}
            if (option == cur.sort) {
                val caret =
                    AppCompatTextView(context).apply {
                        typeface = AppFonts.icons(context)
                        setText(if (cur.direction == SortOrder.ASCENDING) R.string.fa_caret_up else R.string.fa_caret_down)
                        gravity = Gravity.CENTER
                    }
                row.addView(caret, 0, LinearLayout.LayoutParams(context.dp(24), context.dp(24)).apply { marginEnd = context.dp(12) })
                row.onContentColor = { caret.setTextColor(it) }
            }
            row.setOnClickListener {
                menu.dismiss()
                val direction = if (cur.sort == option) cur.direction.flip() else cur.direction
                onSortChange(SortAndDirection(option, direction))
            }
        }
        menu.show()
    }
}

/**
 * `FilterByButton` with its `ExpandableFilterButton`: the filter icon, the number of active filters, and "FILTER"
 * while focused. OK opens the filter options (Remove all when any is set; a check on active ones); choosing one opens
 * its values (loaded with [getPossibleValues]; Remove when set; checks on chosen values; star ratings for the community
 * rating). Single-choice filters close their values on a choice; closing the values returns to the options, focused on
 * the filter last opened.
 */
class FilterButton(
    private val fragment: Fragment,
    context: Context,
    private val filterOptions: List<ItemFilterBy<*>>,
    private val getPossibleValues: suspend (ItemFilterBy<*>) -> List<FilterValueOption>,
    private val allCaps: Boolean = AppTheme.isNeon,
    private val onFilterChange: (GetItemsFilter) -> Unit,
) {
    private var current = GetItemsFilter()
    private var lastOpened: ItemFilterBy<*>? = null
    private var nested: NestedMenu? = null
    private val countView =
        AppCompatTextView(context).apply {
            applyTextRole(TextRole.TITLE_SMALL)
            setPaddingRelative(0, 0, context.dp(4), 0)
            visibility = View.GONE
        }

    val button: AppButton =
        ExpandableButton(context, collapsed = true, allCaps = allCaps).apply {
            setContentPaddingDp(0f, 0f, 0f, 0f)
            contentHeightDp = MIN_BUTTON_DP
            exactContentHeight = true
            minimumWidth = context.dp(MIN_BUTTON_DP)
            setGlyph(R.string.fa_filter, sizeSp = 16f, boxDp = MIN_BUTTON_DP)
            // The count sits between the icon and the label
            addView(countView, 2, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            onContentColor = { countView.setTextColor(it) }
            if (allCaps) {
                setLabel(context.getString(R.string.filter), allCaps = true)
                setLabelPaddingDp(2f, 8f)
            } else {
                setLabel(context.getString(R.string.filter))
                labelView.applyTextRole(TextRole.TITLE_SMALL)
                setLabelPaddingDp(4f, 4f)
            }
            labelOnlyWhenFocused = true
            setOnFocusChangeListener { _, _ -> updateGlyphBox() }
            setOnClickListener { showOptions() }
        }

    private fun updateGlyphBox() {
        val count = current.countFilters(filterOptions)
        val narrow = count > 0 || button.isFocused
        button.glyphView.layoutParams =
            button.glyphView.layoutParams.apply { width = button.context.dp(if (narrow) MIN_BUTTON_DP - 12f else MIN_BUTTON_DP) }
    }

    fun bind(current: GetItemsFilter) {
        this.current = current
        val count = current.countFilters(filterOptions)
        countView.text = count.toString()
        countView.visibility = if (count > 0) View.VISIBLE else View.GONE
        updateGlyphBox()
        nested?.refresh()
    }

    private fun removeRow(
        context: Context,
        elevationDp: Float,
        onClick: () -> Unit,
    ): DropdownMenuItemView =
        DropdownMenuItemView(context, elevationDp).apply {
            setText(context.getString(R.string.remove))
            setLeadingIcon(R.drawable.lb_widget_ctl_ic_delete, tint = 0xCCFF0000.toInt())
            val palette = AppTheme.palette
            onContentColor = { textView.setTextColor(if (isFocused) palette.onError else palette.error) }
            setOnClickListener { onClick() }
        }

    private fun showOptions() {
        val context = button.context
        val menu = AppDropdownMenu(button, containerColor = AppTheme.palette.surfaceAtElevation(3f))
        val count = current.countFilters(filterOptions)
        if (count > 0) {
            menu.addView(
                removeRow(context, 3f) {
                    onFilterChange(current.delete(filterOptions))
                    menu.dismiss()
                },
            )
            menu.addView(MenuDividerView(context))
        }
        var focusRow: View? = null
        filterOptions.forEach { option ->
            val active = option.get(current) != null
            val row =
                menu.addItem(context.getString(option.stringRes), leadingIcon = if (active) R.drawable.lb_widget_ctl_ic_check else null) {
                    lastOpened = option
                    menu.dismiss()
                    showValues(option)
                }
            if (option == (lastOpened?.takeIf { it in filterOptions } ?: filterOptions.firstOrNull())) focusRow = row
        }
        menu.show()
        focusRow?.requestFocus()
    }

    private fun showValues(option: ItemFilterBy<*>) {
        val menu = NestedMenu(option)
        nested = menu
        menu.show()
    }

    /** The values of one filter (the nested dropdown), refreshed as the filter changes */
    private inner class NestedMenu(
        private val option: ItemFilterBy<*>,
    ) {
        private val context = button.context
        private val menu = AppDropdownMenu(button, containerColor = AppTheme.palette.surfaceAtElevation(5f))
        private val valueRows = mutableListOf<Pair<DropdownMenuItemView, FilterValueOption>>()
        private var values: List<FilterValueOption>? = null
        private val removeSlot = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        private val valuesSlot = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        fun show() {
            menu.addView(
                AppCompatTextView(context).apply {
                    text = context.getString(option.stringRes)
                    applyTextRole(TextRole.TITLE_MEDIUM)
                    setTextColor(AppTheme.palette.onSurface)
                    setPadding(context.dp(16), context.dp(8), context.dp(16), context.dp(8))
                },
            )
            menu.addView(MenuDividerView(context))
            menu.addView(removeSlot)
            menu.addView(valuesSlot)
            valuesSlot.addView(
                FrameLayout(context).apply {
                    addView(CircularProgressView(context), FrameLayout.LayoutParams(context.dp(48), context.dp(48)))
                },
            )
            menu.onDismissRequest = {
                if (nested === this) nested = null
                // Closing the values returns to the filter options
                showOptions()
            }
            refreshRemove()
            menu.show()
            fragment.viewLifecycleOwner.lifecycleScope.launch(ExceptionHandler()) {
                val loaded = getPossibleValues(option)
                values = loaded
                showLoaded(loaded)
            }
        }

        private fun refreshRemove() {
            removeSlot.removeAllViews()
            if (option.get(current) != null) {
                removeSlot.addView(
                    removeRow(context, 5f) {
                        @Suppress("UNCHECKED_CAST")
                        onFilterChange((option as ItemFilterBy<Any>).set(null, current))
                        menu.dismiss()
                    },
                )
                removeSlot.addView(MenuDividerView(context))
            }
        }

        private fun showLoaded(loaded: List<FilterValueOption>) {
            valuesSlot.removeAllViews()
            valueRows.clear()
            if (loaded.isEmpty()) {
                valuesSlot.addView(
                    AppCompatTextView(context).apply {
                        setText(R.string.no_results)
                        setTextColor(AppTheme.palette.onSurface)
                    },
                )
                return
            }
            loaded.forEachIndexed { index, value ->
                val row = DropdownMenuItemView(context, 8f)
                if (option == CommunityRatingFilter) {
                    row.setText(starText("${value.name}+", FILLED_STAR_COLOR))
                    row.onContentColor =
                        { row.textView.text = starText("${value.name}+", if (row.isFocused) EMPTY_STAR_COLOR else FILLED_STAR_COLOR) }
                } else {
                    row.setText(value.name)
                }
                row.setOnClickListener { choose(value) }
                valuesSlot.addView(row)
                valueRows += row to value
                if (index == 0) row.requestFocus()
            }
            refreshChecks()
        }

        private fun refreshChecks() {
            val currentValue = option.get(current)
            valueRows.forEach { (row, value) ->
                row.setLeadingIcon(if (isSelected(currentValue, value)) R.drawable.lb_widget_ctl_ic_check else null)
            }
        }

        fun refresh() {
            refreshRemove()
            refreshChecks()
        }

        @Suppress("UNCHECKED_CAST")
        private fun isSelected(
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
        private fun choose(value: FilterValueOption) {
            val currentValue = option.get(current)
            val selected = isSelected(currentValue, value)
            val newFilter =
                when (option) {
                    GenreFilter, StudioFilter -> {
                        val list = (currentValue as? List<UUID>).orEmpty().toMutableList()
                        if (selected) list.remove(value.value!!) else list.add(value.value!! as UUID)
                        (option as ItemFilterBy<List<UUID>>).set(list.takeIf { it.isNotEmpty() }, current)
                    }

                    FavoriteFilter, PlayedFilter -> {
                        (option as ItemFilterBy<Boolean>).set(value.name.toBoolean(), current)
                    }

                    OfficialRatingFilter -> {
                        val list = (currentValue as? List<String>).orEmpty().toMutableList()
                        if (selected) list.remove(value.name) else list.add(value.name)
                        (option as ItemFilterBy<List<String>>).set(list.takeIf { it.isNotEmpty() }, current)
                    }

                    VideoTypeFilter -> {
                        val list = (currentValue as? List<FilterVideoType>).orEmpty().toMutableList()
                        if (selected) list.remove(value.value) else list.add(value.value as FilterVideoType)
                        (option as ItemFilterBy<List<FilterVideoType>>).set(list.takeIf { it.isNotEmpty() }, current)
                    }

                    YearFilter, DecadeFilter -> {
                        val list = (currentValue as? List<Int>).orEmpty().toMutableList()
                        if (selected) list.remove(value.value) else list.add(value.value as Int)
                        (option as ItemFilterBy<List<Int>>).set(list.takeIf { it.isNotEmpty() }, current)
                    }

                    CommunityRatingFilter -> {
                        (option as ItemFilterBy<Int>).set(value.value as? Int, current)
                    }
                }
            // Shown at once; the page's new state follows
            current = newFilter
            onFilterChange(newFilter)
            if (!option.supportMultiple) {
                menu.dismiss()
            } else {
                refresh()
            }
        }

        private fun starText(
            text: String,
            color: Int,
        ): CharSequence {
            val drawable: Drawable = ContextCompat.getDrawable(context, R.drawable.lb_widget_ctl_ic_star)!!.mutate()
            drawable.setTint(color)
            return SpannableStringBuilder(text).apply {
                append(" ")
                val start = length
                append("*")
                setSpan(StarSpan(drawable), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }
}

/** An inline star the text's size (`SimpleStarRating`) */
private class StarSpan(
    drawable: Drawable,
) : ImageSpan(drawable, ALIGN_BOTTOM) {
    override fun getSize(
        paint: Paint,
        text: CharSequence?,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?,
    ): Int {
        val size = paint.textSize.toInt()
        drawable.setBounds(0, 0, size, size)
        return size
    }
}
