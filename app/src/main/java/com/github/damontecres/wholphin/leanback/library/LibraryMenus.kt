package com.github.damontecres.wholphin.leanback.library

import android.content.Context
import android.text.SpannableStringBuilder
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.widget.AppCompatTextView
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.api.seerr.model.TvShowStatus
import com.github.damontecres.wholphin.data.filter.CommunityRatingFilter
import com.github.damontecres.wholphin.data.filter.DecadeFilter
import com.github.damontecres.wholphin.data.filter.DiscoverFilter
import com.github.damontecres.wholphin.data.filter.DiscoverFilterBy
import com.github.damontecres.wholphin.data.filter.DiscoverMovieContentRatingFilter
import com.github.damontecres.wholphin.data.filter.DiscoverMovieGenreFilter
import com.github.damontecres.wholphin.data.filter.DiscoverMovieStudiosFilter
import com.github.damontecres.wholphin.data.filter.DiscoverRatingFilter
import com.github.damontecres.wholphin.data.filter.DiscoverSort
import com.github.damontecres.wholphin.data.filter.DiscoverSortAndDirection
import com.github.damontecres.wholphin.data.filter.DiscoverTvContentRatingFilter
import com.github.damontecres.wholphin.data.filter.DiscoverTvGenreFilter
import com.github.damontecres.wholphin.data.filter.DiscoverTvStatusFilter
import com.github.damontecres.wholphin.data.filter.DiscoverTvStudiosFilter
import com.github.damontecres.wholphin.data.filter.FavoriteFilter
import com.github.damontecres.wholphin.data.filter.FilterBy
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
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.applyTextRole
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.surfaceAtElevation
import com.github.damontecres.wholphin.leanback.theme.withAlpha
import com.github.damontecres.wholphin.leanback.widget.control.AppDropdownMenu
import com.github.damontecres.wholphin.leanback.widget.control.CircularProgressView
import com.github.damontecres.wholphin.leanback.widget.control.DropdownMenuItemView
import com.github.damontecres.wholphin.leanback.widget.text.EMPTY_STAR_COLOR
import com.github.damontecres.wholphin.leanback.widget.text.FILLED_STAR_COLOR
import com.github.damontecres.wholphin.leanback.widget.text.appendIcon
import com.github.damontecres.wholphin.ui.data.SortAndDirection
import com.github.damontecres.wholphin.ui.data.flip
import com.github.damontecres.wholphin.ui.data.getStringRes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import timber.log.Timber
import java.util.UUID

/*
 * The filter and sort menus of the library pages (`FilterByButton`, `DiscoverFilterByButton`, `SortByButton`,
 * `DiscoverSortByButton`) on the CONTROLS dropdown (no-Compose UI, 2026-11-22).
 */

/** How a filter type's values are selected and changed (the `when (filterOption)` blocks of FilterByButton) */
interface FilterSelection<F, O : FilterBy<F, *>> {
    fun count(
        filter: F,
        options: List<O>,
    ): Int

    fun delete(
        filter: F,
        options: List<O>,
    ): F

    fun isSelected(
        option: O,
        currentValue: Any?,
        value: FilterValueOption,
    ): Boolean

    /** The filter after OK on [value] (toggles list values, sets single ones) */
    fun choose(
        option: O,
        filter: F,
        currentValue: Any?,
        value: FilterValueOption,
        isSelected: Boolean,
    ): F

    /** The star rating row ("7+ ★") instead of plain text */
    fun isRating(option: O): Boolean
}

/** Toggle [item] in a list value; null when the list becomes empty */
private fun <T> toggled(
    list: List<T>,
    item: T,
    selected: Boolean,
): List<T>? = list.toMutableList().apply { if (selected) remove(item) else add(item) }.takeIf { it.isNotEmpty() }

/** The library filters ([ItemFilterBy] on [GetItemsFilter]), exactly as FilterByButton chose them */
@Suppress("UNCHECKED_CAST")
object ItemFilterSelection : FilterSelection<GetItemsFilter, ItemFilterBy<*>> {
    override fun count(
        filter: GetItemsFilter,
        options: List<ItemFilterBy<*>>,
    ): Int = filter.countFilters(options)

    override fun delete(
        filter: GetItemsFilter,
        options: List<ItemFilterBy<*>>,
    ): GetItemsFilter = filter.delete(options)

    override fun isRating(option: ItemFilterBy<*>): Boolean = option == CommunityRatingFilter

    override fun isSelected(
        option: ItemFilterBy<*>,
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

    override fun choose(
        option: ItemFilterBy<*>,
        filter: GetItemsFilter,
        currentValue: Any?,
        value: FilterValueOption,
        isSelected: Boolean,
    ): GetItemsFilter {
        val opt = option as ItemFilterBy<Any>
        return when (option) {
            GenreFilter, StudioFilter -> {
                opt.set(toggled((currentValue as? List<UUID>).orEmpty(), value.value as UUID, isSelected), filter)
            }

            FavoriteFilter, PlayedFilter -> {
                opt.set(value.name.toBoolean(), filter)
            }

            OfficialRatingFilter -> {
                opt.set(toggled((currentValue as? List<String>).orEmpty(), value.name, isSelected), filter)
            }

            VideoTypeFilter -> {
                opt.set(toggled((currentValue as? List<FilterVideoType>).orEmpty(), value.value as FilterVideoType, isSelected), filter)
            }

            YearFilter, DecadeFilter -> {
                opt.set(toggled((currentValue as? List<Int>).orEmpty(), value.value as Int, isSelected), filter)
            }

            CommunityRatingFilter -> {
                opt.set(value.value as? Int, filter)
            }
        }
    }
}

/** The Requests (Seerr discover) filters, exactly as DiscoverFilterByButton chose them */
@Suppress("UNCHECKED_CAST")
object DiscoverFilterSelection : FilterSelection<DiscoverFilter, DiscoverFilterBy<*>> {
    override fun count(
        filter: DiscoverFilter,
        options: List<DiscoverFilterBy<*>>,
    ): Int = filter.countFilters(options)

    override fun delete(
        filter: DiscoverFilter,
        options: List<DiscoverFilterBy<*>>,
    ): DiscoverFilter = filter.delete(options)

    override fun isRating(option: DiscoverFilterBy<*>): Boolean = option == DiscoverRatingFilter

    override fun isSelected(
        option: DiscoverFilterBy<*>,
        currentValue: Any?,
        value: FilterValueOption,
    ): Boolean =
        when (option) {
            DiscoverMovieGenreFilter, DiscoverTvGenreFilter, DiscoverMovieStudiosFilter, DiscoverTvStudiosFilter -> {
                (currentValue as? List<Int>).orEmpty().contains(value.value)
            }

            DiscoverTvStatusFilter -> {
                (currentValue as? List<TvShowStatus>).orEmpty().contains(value.value)
            }

            DiscoverMovieContentRatingFilter, DiscoverTvContentRatingFilter -> {
                (currentValue as? List<String>).orEmpty().contains(value.value)
            }

            DiscoverRatingFilter -> {
                (currentValue as? Int) == value.value
            }
        }

    override fun choose(
        option: DiscoverFilterBy<*>,
        filter: DiscoverFilter,
        currentValue: Any?,
        value: FilterValueOption,
        isSelected: Boolean,
    ): DiscoverFilter {
        val opt = option as DiscoverFilterBy<Any>
        return when (option) {
            DiscoverMovieGenreFilter, DiscoverTvGenreFilter, DiscoverMovieStudiosFilter, DiscoverTvStudiosFilter -> {
                opt.set(toggled((currentValue as? List<Int>).orEmpty(), value.value as Int, isSelected), filter)
            }

            DiscoverTvStatusFilter -> {
                opt.set(toggled((currentValue as? List<TvShowStatus>).orEmpty(), value.value as TvShowStatus, isSelected), filter)
            }

            DiscoverMovieContentRatingFilter, DiscoverTvContentRatingFilter -> {
                opt.set(toggled((currentValue as? List<String>).orEmpty(), value.value as String, isSelected), filter)
            }

            DiscoverRatingFilter -> {
                opt.set(value.value as? Int, filter)
            }
        }
    }
}

/**
 * `FilterByButton` / `DiscoverFilterByButton` on a [FilterButtonView]: OK opens the list of filters (Remove first when
 * any is set, a check on each filter in use; focus on the filter opened last, else the first). Choosing a filter
 * replaces that list with its values (title, Remove when set, a spinner while the values load, "No results", or the
 * values with a check on each chosen one; the first value focused). A single-choice value, Remove or BACK goes back
 * to the list of filters; values of multiple-choice filters toggle and keep their menu open. BACK on the list of filters
 * closes it ([onShow] false).
 *
 * Call [update] whenever the current filter changes.
 */
class FilterMenuController<F, O : FilterBy<F, *>>(
    private val button: FilterButtonView,
    private val filterOptions: List<O>,
    private val selection: FilterSelection<F, O>,
    private val scope: CoroutineScope,
    private val getPossibleValues: suspend (O) -> List<FilterValueOption>,
    private val onFilterChange: (F) -> Unit,
    private val onShow: ((Boolean) -> Unit)? = null,
    initial: F,
) {
    private val context: Context = button.context
    private var current: F = initial
    private var lastOpened: O? = null
    private var parentMenu: AppDropdownMenu? = null
    private var nested: NestedMenu? = null

    /** True while the menus are open (Compose `dropDown`) */
    private var dropDown = false

    init {
        button.filterCount = selection.count(current, filterOptions)
        button.setOnClickListener {
            onShow?.invoke(true)
            dropDown = true
            showParent()
        }
    }

    /** The filter changed (from the ViewModel): update the count and an open value menu */
    fun update(filter: F) {
        current = filter
        button.filterCount = selection.count(filter, filterOptions)
        nested?.refresh()
    }

    private fun showParent() {
        val menu = AppDropdownMenu(button, containerColor = AppTheme.palette.surfaceAtElevation(3f))
        parentMenu = menu
        if (selection.count(current, filterOptions) > 0) {
            menu.addView(
                removeRow(3f) {
                    onFilterChange(selection.delete(current, filterOptions))
                    // Closes everything without telling onShow, as before
                    dropDown = false
                    dismissSilently(menu)
                },
            )
            menu.addView(dropdownDivider(context))
        }
        val focusOption = lastOpened?.takeIf { it in filterOptions } ?: filterOptions.firstOrNull()
        var focusRow: View? = null
        filterOptions.forEach { option ->
            val row =
                DropdownMenuItemView(context).apply {
                    setText(context.getString(option.stringRes))
                    setLeadingCheck(this, option.get(current) != null)
                    setOnClickListener {
                        lastOpened = option
                        dismissSilently(menu)
                        showNested(option)
                    }
                }
            menu.addView(row)
            if (option == focusOption) focusRow = row
        }
        menu.onDismissRequest = {
            if (parentMenu === menu) {
                parentMenu = null
                onShow?.invoke(false)
                dropDown = false
            }
        }
        menu.show()
        focusRow?.requestFocus()
    }

    /** Close [menu] without the BACK / outside handling (switching between the two lists) */
    private fun dismissSilently(menu: AppDropdownMenu) {
        if (parentMenu === menu) parentMenu = null
        menu.dismiss()
    }

    private fun showNested(option: O) {
        val menu = NestedMenu(option)
        nested = menu
        menu.show()
    }

    private fun closeNested(menu: NestedMenu) {
        if (nested === menu) nested = null
        menu.job?.cancel()
        // nestedDropDown = null: the list of filters shows again while the menus are open
        if (dropDown) showParent()
    }

    private fun removeRow(
        elevationDp: Float,
        onClick: () -> Unit,
    ): DropdownMenuItemView =
        DropdownMenuItemView(context, elevationDp).apply {
            setText(context.getString(R.string.remove))
            setLeadingIcon(R.drawable.lb_widget_ctl_ic_delete, tint = withAlpha(0xFFFF0000.toInt(), .8f))
            val row = this
            onContentColor = {
                val palette = AppTheme.palette
                row.textView.setTextColor(if (row.isFocused) palette.onError else palette.error)
            }
            setOnClickListener { onClick() }
        }

    /** A check when [checked]; otherwise the 24dp slot stays reserved (Material3 always reserved it) */
    private fun setLeadingCheck(
        row: DropdownMenuItemView,
        checked: Boolean,
    ) {
        row.setLeadingIcon(R.drawable.lb_widget_ctl_ic_check, tint = if (checked) null else 0)
    }

    /** The menu of one filter's values */
    private inner class NestedMenu(
        val option: O,
    ) {
        val menu = AppDropdownMenu(button, containerColor = AppTheme.palette.surfaceAtElevation(5f))
        var job: Job? = null
        private var values: List<FilterValueOption>? = null
        private val removeRow: DropdownMenuItemView
        private val removeDivider: View
        private val valueRows = mutableListOf<Pair<FilterValueOption, DropdownMenuItemView>>()
        private val loadingView: View
        private var closedByChoice = false

        init {
            val title =
                AppCompatTextView(context).apply {
                    text = context.getString(option.stringRes)
                    applyTextRole(TextRole.TITLE_MEDIUM)
                    setTextColor(AppTheme.palette.onSurface)
                    setPadding(context.dp(16), context.dp(8), context.dp(16), context.dp(8))
                }
            menu.addView(title)
            menu.addView(dropdownDivider(context))
            removeRow =
                removeRow(5f) {
                    onFilterChange(setValue(null))
                    finish()
                }
            removeDivider = dropdownDivider(context)
            menu.addView(removeRow)
            menu.addView(removeDivider)
            loadingView =
                FrameLayout(context).apply {
                    addView(
                        CircularProgressView(context),
                        FrameLayout.LayoutParams(context.dp(48), context.dp(48), Gravity.CENTER_VERTICAL),
                    )
                }
            menu.addView(loadingView)
            refresh()
            menu.onDismissRequest = {
                if (!closedByChoice) closeNested(this)
            }
        }

        @Suppress("UNCHECKED_CAST")
        private fun setValue(value: Any?): F = (option as FilterBy<F, Any>).set(value, current)

        fun show() {
            menu.show()
            job =
                scope.launch {
                    val result =
                        try {
                            getPossibleValues(option)
                        } catch (ex: CancellationException) {
                            throw ex
                        } catch (ex: Exception) {
                            Timber.e(ex, "Error getting filter values")
                            emptyList()
                        }
                    if (nested !== this@NestedMenu) return@launch
                    values = result
                    showValues(result)
                }
        }

        private fun finish() {
            closedByChoice = true
            menu.dismiss()
            closeNested(this)
        }

        private fun showValues(result: List<FilterValueOption>) {
            loadingView.visibility = View.GONE
            if (result.isEmpty()) {
                menu.addView(
                    AppCompatTextView(context).apply {
                        setText(R.string.no_results)
                        applyTextRole(TextRole.BODY_LARGE)
                        setTextColor(AppTheme.palette.onSurface)
                    },
                )
                return
            }
            result.forEach { value ->
                val row = DropdownMenuItemView(context, 8f)
                valueRows += value to row
                if (selection.isRating(option)) {
                    row.onContentColor = { bindRating(row, value) }
                }
                row.setOnClickListener {
                    val currentValue = option.get(current)
                    val selected = selection.isSelected(option, currentValue, value)
                    val newFilter = selection.choose(option, current, currentValue, value, selected)
                    onFilterChange(newFilter)
                    if (!option.supportMultiple) {
                        finish()
                    } else {
                        update(newFilter)
                    }
                }
                menu.addView(row)
            }
            refresh()
            valueRows.firstOrNull()?.second?.requestFocus()
        }

        private fun bindRating(
            row: DropdownMenuItemView,
            value: FilterValueOption,
        ) {
            row.textView.text =
                SpannableStringBuilder("${value.name}+").apply {
                    append(" ")
                    appendIcon(context, R.drawable.lb_widget_ctl_ic_star, if (row.isFocused) EMPTY_STAR_COLOR else FILLED_STAR_COLOR)
                }
        }

        /** Show the current value: Remove when set, the checks on chosen values */
        fun refresh() {
            val currentValue = option.get(current)
            val set = currentValue != null
            removeRow.visibility = if (set) View.VISIBLE else View.GONE
            removeDivider.visibility = removeRow.visibility
            valueRows.forEach { (value, row) ->
                setLeadingCheck(row, selection.isSelected(option, currentValue, value))
                if (selection.isRating(option)) bindRating(row, value) else row.setText(value.name)
            }
        }
    }
}

/** `FilterByButton` for a library page */
fun itemFilterMenu(
    button: FilterButtonView,
    filterOptions: List<ItemFilterBy<*>>,
    scope: CoroutineScope,
    initial: GetItemsFilter,
    getPossibleValues: suspend (ItemFilterBy<*>) -> List<FilterValueOption>,
    onFilterChange: (GetItemsFilter) -> Unit,
    onShow: ((Boolean) -> Unit)? = null,
) = FilterMenuController(button, filterOptions, ItemFilterSelection, scope, getPossibleValues, onFilterChange, onShow, initial)

/** `DiscoverFilterByButton` for the Requests pages (SEARCH may use it) */
fun discoverFilterMenu(
    button: FilterButtonView,
    filterOptions: List<DiscoverFilterBy<*>>,
    scope: CoroutineScope,
    initial: DiscoverFilter,
    getPossibleValues: suspend (DiscoverFilterBy<*>) -> List<FilterValueOption>,
    onFilterChange: (DiscoverFilter) -> Unit,
    onShow: ((Boolean) -> Unit)? = null,
) = FilterMenuController(button, filterOptions, DiscoverFilterSelection, scope, getPossibleValues, onFilterChange, onShow, initial)

/**
 * `SortByButton` / `DiscoverSortByButton`: OK on [button] opens the sort choices (the current one with its direction
 * caret); choosing the current sort reverses it, another keeps the direction. Long press reverses the current sort.
 * [label] renders the button's text for the current value (the old `toAnnotatedString`).
 */
class SortMenuController<S, V>(
    private val button: View,
    private val options: List<S>,
    private val sortOf: (V) -> S,
    private val directionOf: (V) -> SortOrder,
    private val make: (S, SortOrder) -> V,
    private val nameOf: (S) -> String,
    private val onSortChange: (V) -> Unit,
    private val bindButton: (V) -> Unit,
    initial: V,
) {
    private var current: V = initial

    init {
        bindButton(initial)
        button.setOnClickListener { show() }
        button.setOnLongClickListener {
            onSortChange(make(sortOf(current), directionOf(current).flip()))
            true
        }
    }

    fun update(value: V) {
        current = value
        bindButton(value)
    }

    private fun show() {
        val menu = AppDropdownMenu(button)
        val currentSort = sortOf(current)
        val currentDirection = directionOf(current)
        options.forEach { option ->
            val row =
                DropdownMenuItemView(button.context).apply {
                    setText(nameOf(option))
                    if (option == currentSort) {
                        setLeadingIcon(
                            if (currentDirection ==
                                SortOrder.ASCENDING
                            ) {
                                R.drawable.lb_library_ic_caret_up
                            } else {
                                R.drawable.lb_library_ic_caret_down
                            },
                        )
                    } else {
                        // The leading slot stays reserved, as Material3's always did
                        setLeadingIcon(R.drawable.lb_library_ic_caret_up, tint = 0)
                    }
                    setOnClickListener {
                        menu.dismiss()
                        val newDirection = if (currentSort == option) currentDirection.flip() else currentDirection
                        onSortChange(make(option, newDirection))
                    }
                }
            menu.addView(row)
        }
        menu.show()
    }
}

/** The text sort button of library headers (`SortByButton`, not collapsed): an `AppButton.text` with the caret label */
fun itemSortMenu(
    button: com.github.damontecres.wholphin.leanback.widget.control.AppButton,
    sortOptions: List<ItemSortBy>,
    initial: SortAndDirection,
    allCaps: Boolean,
    onSortChange: (SortAndDirection) -> Unit,
) = SortMenuController(
    button = button,
    options = sortOptions,
    sortOf = { it.sort },
    directionOf = { it.direction },
    make = { s, d -> SortAndDirection(s, d) },
    nameOf = { button.context.getString(getStringRes(it)) },
    onSortChange = onSortChange,
    bindButton = { button.setLabel(it.label(button.context, allCaps)) },
    initial = initial,
)

/** `DiscoverSortByButton` for the Requests pages (SEARCH may use it) */
fun discoverSortMenu(
    button: com.github.damontecres.wholphin.leanback.widget.control.AppButton,
    sortOptions: List<DiscoverSort>,
    initial: DiscoverSortAndDirection,
    onSortChange: (DiscoverSortAndDirection) -> Unit,
) = SortMenuController(
    button = button,
    options = sortOptions,
    sortOf = { it.sort },
    directionOf = { it.direction },
    make = { s, d -> DiscoverSortAndDirection(s, d) },
    nameOf = { button.context.getString(it.stringRes) },
    onSortChange = onSortChange,
    bindButton = { value ->
        val caret = button.context.getString(if (value.direction == SortOrder.ASCENDING) R.string.fa_caret_up else R.string.fa_caret_down)
        button.setLabel(
            SpannableStringBuilder(caret).apply {
                setSpan(
                    FontAwesomeSpan(
                        com.github.damontecres.wholphin.leanback.theme.AppFonts
                            .icons(button.context),
                    ),
                    0,
                    caret.length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                append(" ")
                append(button.context.getString(value.sort.stringRes))
            },
        )
    },
    initial = initial,
)
