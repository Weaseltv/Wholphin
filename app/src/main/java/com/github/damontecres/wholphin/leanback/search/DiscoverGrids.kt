package com.github.damontecres.wholphin.leanback.search

import com.github.damontecres.wholphin.leanback.theme.surfaceAtElevation
import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.MetricAffectingSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.github.damontecres.wholphin.R
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
import com.github.damontecres.wholphin.data.filter.FilterValueOption
import com.github.damontecres.wholphin.data.model.DiscoverItem
import com.github.damontecres.wholphin.leanback.shell.DestinationFragment
import com.github.damontecres.wholphin.leanback.theme.AppFonts
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.NeonType
import com.github.damontecres.wholphin.leanback.theme.PageStyle
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.applyNeonEyebrow
import com.github.damontecres.wholphin.leanback.theme.applyTextRole
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.withAlpha
import com.github.damontecres.wholphin.leanback.widget.card.CardGridCallbacks
import com.github.damontecres.wholphin.leanback.widget.card.CardGridConfig
import com.github.damontecres.wholphin.leanback.widget.card.CardGridView
import com.github.damontecres.wholphin.leanback.widget.card.DiscoverItemCardView
import com.github.damontecres.wholphin.leanback.widget.card.cardPresenter
import com.github.damontecres.wholphin.leanback.widget.control.AppButton
import com.github.damontecres.wholphin.leanback.widget.control.AppDropdownMenu
import com.github.damontecres.wholphin.leanback.widget.control.CircularProgressView
import com.github.damontecres.wholphin.leanback.widget.control.DropdownMenuItemView
import com.github.damontecres.wholphin.leanback.widget.control.ErrorView
import com.github.damontecres.wholphin.leanback.widget.control.LoadingPageView
import com.github.damontecres.wholphin.leanback.widget.text.EMPTY_STAR_COLOR
import com.github.damontecres.wholphin.leanback.widget.text.FILLED_STAR_COLOR
import com.github.damontecres.wholphin.leanback.widget.text.FittedTitleView
import com.github.damontecres.wholphin.leanback.widget.text.NeonRuleView
import com.github.damontecres.wholphin.leanback.widget.text.SimpleStarRatingView
import com.github.damontecres.wholphin.leanback.widget.text.TimeDisplayView
import com.github.damontecres.wholphin.ui.components.ErrorViewModel
import com.github.damontecres.wholphin.ui.detail.discover.DiscoverPersonViewModel
import com.github.damontecres.wholphin.ui.discover.DiscoverRequestViewModel
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.util.DataLoadingState
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.withCreationCallback
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.SortOrder

/**
 * `GridTitle`: WeaselTV: the eyebrow, the uppercase page title (40sp, shrinking to fit three lines, short of the clock)
 * and the section rule reaching the page edges; other themes: the displaySmall title centred on one line.
 */
internal fun gridTitleView(
    context: Context,
    title: String,
    eyebrow: String,
    accent: Int,
): View {
    if (!AppTheme.isNeon) {
        return TextView(context).apply {
            text = title
            applyTextRole(TextRole.DISPLAY_SMALL)
            setTextColor(AppTheme.palette.onBackground)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
    }
    val inset = context.dp(16)
    return LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
        addView(
            TextView(context).apply {
                applyNeonEyebrow(eyebrow, accent)
                setPaddingRelative(inset, 0, inset, 0)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        addView(
            FittedTitleView(context, maxLines = 3).apply {
                setStyledText(title.uppercase(), NeonType.pageTitle())
                setTextColor(AppTheme.palette.onSurface)
                setPaddingRelative(inset, 0, TimeDisplayView.headerEndPaddingPx(context), 0)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(2)
            },
        )
        addView(
            NeonRuleView(context).apply {
                section = true
                glowStrength = .35f
                ruleAccent = accent
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .bleedIntoGutters(context)
                .apply { topMargin = context.dp(6) },
        )
    }
}

/**
 * The Requests person page (`DiscoverPersonPage`, no-Compose UI): "Discover" (Requests) over the person's name, then a
 * 6-column grid of their credits (16dp apart) starting at the destination's index, or "No results". OK on a credit
 * opens its Requests page; BACK in the grid jumps to the first card first.
 */
@AndroidEntryPoint
class DiscoverPersonFragment : DestinationFragment() {
    private val viewModel: DiscoverPersonViewModel by viewModels(
        extrasProducer = {
            defaultViewModelCreationExtras.withCreationCallback<DiscoverPersonViewModel.Factory> {
                it.create(requireDestination<Destination.DiscoveredItem>().item)
            }
        },
    )
    private val errorViewModel: ErrorViewModel by viewModels()
    private lateinit var root: FrameLayout
    private var grid: CardGridView? = null
    private var shown: DataLoadingState<List<DiscoverItem>>? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        root =
            FrameLayout(requireContext()).apply {
                clipChildren = false
                clipToPadding = false
            }
        return root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.credits.collectWhileStarted { render(it) }
    }

    private fun render(state: DataLoadingState<List<DiscoverItem>>) {
        if (state == shown) return
        val wasSuccess = shown is DataLoadingState.Success
        shown = state
        val context = requireContext()
        when (state) {
            is DataLoadingState.Error -> {
                root.removeAllViews()
                grid = null
                val error = ErrorView(context, state, errorViewModel::sendLogs)
                root.addView(error)
                requestInitialFocus(error)
            }

            DataLoadingState.Loading, DataLoadingState.Pending -> {
                root.removeAllViews()
                grid = null
                root.addView(LoadingPageView(context))
            }

            is DataLoadingState.Success -> {
                if (wasSuccess) {
                    grid?.setCards(state.data, presenter, callbacks, config(), pageStyle)
                    return
                }
                root.removeAllViews()
                val person = requireDestination<Destination.DiscoveredItem>().item
                val column =
                    LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        clipChildren = false
                        clipToPadding = false
                    }
                val discover = getString(R.string.discover)
                val title =
                    if (AppTheme.isNeon) {
                        gridTitleView(context, person.title ?: discover, discover, pageStyle.sectionAccent)
                    } else {
                        gridTitleView(context, discover + (person.title?.let { ": $it" } ?: ""), discover, pageStyle.sectionAccent)
                    }
                column.addView(
                    title,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        if (AppTheme.isNeon) topMargin = context.dp(27)
                    },
                )
                if (state.data.isEmpty()) {
                    column.addView(
                        TextView(context).apply {
                            setText(R.string.no_results)
                            applyTextRole(TextRole.TITLE_LARGE)
                            setTextColor(AppTheme.palette.onBackground)
                            gravity = Gravity.CENTER_HORIZONTAL
                        },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
                    )
                    grid = null
                } else {
                    val cards = CardGridView(context)
                    grid = cards
                    column.addView(cards, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                    cards.setCards(state.data, presenter, callbacks, config(), pageStyle)
                    requestInitialFocus(cards.grid)
                }
                root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
        }
    }

    private val presenter =
        cardPresenter<DiscoverItem, DiscoverItemCardView>(::DiscoverItemCardView) { card, item, _, w ->
            card.bind(item, pageStyle, widthPx = w, showOverlay = true)
        }

    private val callbacks =
        CardGridCallbacks<DiscoverItem>(
            onClick = { _, item -> viewModel.navigationManager.navigateTo(Destination.DiscoveredItem(item)) },
        )

    private fun config() =
        CardGridConfig(
            columns = 6,
            spacingDp = 16f,
            initialPosition = requireDestination<Destination.DiscoveredItem>().startIndex,
        )

    override fun onScreenFocus(restoring: Boolean) {
        if (!restoring) grid?.grid?.let { if (it.requestFocus()) return }
        super.onScreenFocus(restoring)
    }

    override fun onBackPressed(): Boolean = grid?.takeIf { it.hasFocus() }?.onBackPressed() ?: false

    override fun onPageStyleChanged(style: PageStyle) {
        grid?.refresh()
    }
}

/** A Requests item of a kind this app does not know: the old "Unknown discover type" text */
@AndroidEntryPoint
class UnknownDiscoverFragment : DestinationFragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View =
        TextView(requireContext()).apply {
            text = "Unknown discover type"
            applyTextRole(TextRole.BODY_LARGE)
            setTextColor(AppTheme.palette.onSurface)
        }
}

/** A Font Awesome glyph inside other text (Compose `SpanStyle(fontFamily = FontAwesome)`) */
private class IconFontSpan(
    private val typeface: Typeface,
) : MetricAffectingSpan() {
    override fun updateDrawState(paint: TextPaint) {
        paint.typeface = typeface
    }

    override fun updateMeasureState(paint: TextPaint) {
        paint.typeface = typeface
    }
}

/** `DiscoverSortAndDirection.toAnnotatedString()`: the caret (up ascending, down descending) and the sort name */
private fun sortLabel(
    context: Context,
    current: DiscoverSortAndDirection,
): CharSequence =
    SpannableStringBuilder().apply {
        val caret = context.getString(if (current.direction == SortOrder.ASCENDING) R.string.fa_caret_up else R.string.fa_caret_down)
        append(caret)
        setSpan(IconFontSpan(AppFonts.icons(context)), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        append(" ")
        append(context.getString(current.sort.stringRes))
    }

/**
 * `DiscoverSortByButton`: a text button with the current sort; OK opens the sort menu (the current one marked with
 * its direction; choosing it again flips the direction), holding OK flips the direction at once.
 */
internal class DiscoverSortButton(
    context: Context,
    private val onSortChange: (DiscoverSortAndDirection) -> Unit,
) : FrameLayout(context) {
    private val button = AppButton.text(context, "") { showMenu() }
    private var options: List<DiscoverSort> = emptyList()
    private var current = DiscoverSortAndDirection()

    init {
        clipChildren = false
        addView(button, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        button.setOnLongClickListener {
            onSortChange(current.flip())
            true
        }
    }

    fun bind(
        options: List<DiscoverSort>,
        current: DiscoverSortAndDirection,
    ) {
        this.options = options
        this.current = current
        button.setLabel(sortLabel(context, current))
    }

    private fun showMenu() {
        val menu = AppDropdownMenu(button)
        options.forEach { option ->
            val icon =
                if (option == current.sort) {
                    if (current.direction == SortOrder.ASCENDING) R.drawable.lb_search_ic_caret_up else R.drawable.lb_search_ic_caret_down
                } else {
                    null
                }
            menu.addItem(context.getString(option.stringRes), leadingIcon = icon) {
                menu.dismiss()
                val direction =
                    when {
                        current.sort != option -> current.direction
                        current.direction == SortOrder.ASCENDING -> SortOrder.DESCENDING
                        else -> SortOrder.ASCENDING
                    }
                onSortChange(DiscoverSortAndDirection(option, direction))
            }
        }
        menu.show()
    }
}

/**
 * `ExpandableFilterButton` + `DiscoverFilterByButton`: the filter glyph, the number of filters in use and, while
 * focused, "Filter". OK opens the filter menu: Remove (all filters) when any is set, then each filter (checked when
 * set); a filter opens its values (loading, "No results" or the values, checked when chosen; Remove when set), and
 * closing that returns to the filter menu on the same filter. Single-value filters close their list once chosen.
 */
internal class DiscoverFilterButton(
    context: Context,
    private val scope: () -> kotlinx.coroutines.CoroutineScope,
    private val getPossibleValues: suspend (DiscoverFilterBy<*>) -> List<FilterValueOption>,
    private val onFilterChange: (DiscoverFilter) -> Unit,
) : FrameLayout(context) {
    private val count = TextView(context).apply { applyTextRole(TextRole.TITLE_SMALL) }
    private val button =
        AppButton(context).apply {
            minimumWidth = context.dp(40)
            setContentPaddingDp(0f, 0f, 0f, 0f)
            contentHeightDp = 40f
            exactContentHeight = true
            setGlyph(R.string.fa_filter, sizeSp = 16f, boxDp = 40f)
            labelOnlyWhenFocused = true
            if (AppTheme.isNeon) {
                setLabel(R.string.filter, allCaps = true)
                setLabelPaddingDp(2f, 8f)
            } else {
                labelView.applyTextRole(TextRole.TITLE_SMALL)
                setLabel(R.string.filter)
                setLabelPaddingDp(4f, 4f)
            }
            addView(count, 1, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = context.dp(4) })
            onContentColor = { count.setTextColor(it) }
            surface.onStateChanged = { updateGlyphWidth() }
            setOnClickListener { showFilters() }
        }
    private var options: List<DiscoverFilterBy<*>> = emptyList()
    private var current = DiscoverFilter()
    private var lastOpened: DiscoverFilterBy<*>? = null
    private var menu: AppDropdownMenu? = null
    private var nested: AppDropdownMenu? = null

    init {
        clipChildren = false
        addView(button, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    fun bind(
        options: List<DiscoverFilterBy<*>>,
        current: DiscoverFilter,
    ) {
        this.options = options
        this.current = current
        val filters = current.countFilters(options)
        count.text = filters.toString()
        count.visibility = if (filters > 0) VISIBLE else GONE
        updateGlyphWidth()
    }

    /** The glyph box narrows to 28dp beside the count or the label */
    private fun updateGlyphWidth() {
        val filters = current.countFilters(options)
        val width = context.dp(if (filters > 0 || button.isFocused) 28 else 40)
        val lp = button.glyphView.layoutParams
        if (lp.width != width) {
            lp.width = width
            button.glyphView.layoutParams = lp
        }
    }

    private fun change(newFilter: DiscoverFilter) {
        current = newFilter
        bind(options, newFilter)
        onFilterChange(newFilter)
    }

    private fun removeRow(
        target: AppDropdownMenu,
        onClick: () -> Unit,
    ) {
        val row = target.addItem(context.getString(R.string.remove), leadingIcon = R.drawable.lb_widget_ctl_ic_delete) { onClick() }
        row.setLeadingIcon(R.drawable.lb_widget_ctl_ic_delete, withAlpha(0xFFFF0000.toInt(), .8f))
        row.onContentColor = { _ ->
            row.textView.setTextColor(if (row.isFocused) AppTheme.palette.onError else AppTheme.palette.error)
        }
        target.addView(MenuDividerLine(context))
    }

    private fun showFilters() {
        val m = AppDropdownMenu(button, containerColor = AppTheme.palette.surfaceAtElevation(3f))
        menu = m
        if (current.countFilters(options) > 0) {
            removeRow(m) {
                change(current.delete(options))
                m.dismiss()
            }
        }
        var focusRow: DropdownMenuItemView? = null
        val target = lastOpened?.takeIf { it in options } ?: options.firstOrNull()
        options.forEach { option ->
            val row =
                m.addItem(context.getString(option.stringRes), leadingIcon = if (option.get(current) != null) R.drawable.lb_widget_ctl_ic_check else null) {
                    lastOpened = option
                    m.onDismissRequest = null
                    m.dismiss()
                    showValues(option)
                }
            if (option == target) focusRow = row
        }
        m.onDismissRequest = { menu = null }
        m.show()
        focusRow?.requestFocus()
    }

    @Suppress("UNCHECKED_CAST")
    private fun showValues(option: DiscoverFilterBy<*>) {
        val filterOption = option as DiscoverFilterBy<Any>
        val m = AppDropdownMenu(button, containerColor = AppTheme.palette.surfaceAtElevation(5f))
        nested = m
        m.addView(
            TextView(context).apply {
                setText(option.stringRes)
                applyTextRole(TextRole.TITLE_MEDIUM)
                setTextColor(AppTheme.palette.onSurface)
                setPadding(context.dp(16), context.dp(8), context.dp(16), context.dp(8))
            },
        )
        m.addView(MenuDividerLine(context))
        val currentValue = filterOption.get(current)
        if (currentValue != null) {
            removeRow(m) {
                change(filterOption.set(null, current))
                m.dismiss()
            }
        }
        val progress = CircularProgressView(context)
        val holder = FrameLayout(context).apply { addView(progress, LayoutParams(context.dp(48), context.dp(48))) }
        m.addView(holder)
        // Closing the values returns to the filter menu, on this filter
        m.onDismissRequest = {
            nested = null
            showFilters()
        }
        m.show()
        scope().launch {
            val values = runCatching { getPossibleValues(option) }.getOrDefault(emptyList())
            holder.removeAllViews()
            if (values.isEmpty()) {
                holder.addView(
                    TextView(context).apply {
                        setText(R.string.no_results)
                        applyTextRole(TextRole.BODY_LARGE)
                        setTextColor(AppTheme.palette.onSurface)
                    },
                )
                return@launch
            }
            var first: View? = null
            values.forEach { value ->
                val selected = isSelected(filterOption, filterOption.get(current), value)
                val row = DropdownMenuItemView(context, elevationDp = 8f)
                if (option == DiscoverRatingFilter) {
                    val stars = SimpleStarRatingView(context).apply { setRatingText("${value.name}+") }
                    row.setText("")
                    row.setTrailingView(null)
                    row.addView(stars, 1)
                    row.onContentColor = { _ ->
                        stars.starColor = if (row.isFocused) EMPTY_STAR_COLOR else FILLED_STAR_COLOR
                    }
                } else {
                    row.setText(value.name)
                }
                row.setLeadingIcon(if (selected) R.drawable.lb_widget_ctl_ic_check else null)
                row.setOnClickListener {
                    change(toggled(filterOption, filterOption.get(current), value, selected))
                    if (!filterOption.supportMultiple) {
                        m.dismiss()
                    } else {
                        row.setLeadingIcon(if (!selected) R.drawable.lb_widget_ctl_ic_check else null)
                        // Rebuild with the new choices so the checks stay right
                        m.onDismissRequest = null
                        m.dismiss()
                        showValues(option)
                    }
                }
                m.addView(row)
                if (first == null) first = row
            }
            first?.requestFocus()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun isSelected(
        filterOption: DiscoverFilterBy<Any>,
        currentValue: Any?,
        value: FilterValueOption,
    ): Boolean =
        when (filterOption) {
            DiscoverMovieGenreFilter, DiscoverTvGenreFilter, DiscoverMovieStudiosFilter, DiscoverTvStudiosFilter,
            DiscoverTvStatusFilter, DiscoverMovieContentRatingFilter, DiscoverTvContentRatingFilter,
            -> (currentValue as? List<Any>).orEmpty().contains(value.value)

            DiscoverRatingFilter -> (currentValue as? Int) == value.value
        }

    @Suppress("UNCHECKED_CAST")
    private fun toggled(
        filterOption: DiscoverFilterBy<Any>,
        currentValue: Any?,
        value: FilterValueOption,
        selected: Boolean,
    ): DiscoverFilter =
        when (filterOption) {
            DiscoverMovieGenreFilter, DiscoverTvGenreFilter, DiscoverMovieStudiosFilter, DiscoverTvStudiosFilter,
            DiscoverTvStatusFilter, DiscoverMovieContentRatingFilter, DiscoverTvContentRatingFilter,
            -> {
                val list = (currentValue as? List<Any>).orEmpty().toMutableList()
                if (selected) list.remove(value.value!!) else list.add(value.value!!)
                filterOption.set(list.takeIf { it.isNotEmpty() }, current)
            }

            DiscoverRatingFilter -> {
                filterOption.set(value.value as? Int, current)
            }
        }

}

/** `HorizontalDivider()` in a menu: a 1dp line in the outline-variant colour */
private class MenuDividerLine(
    context: Context,
) : View(context) {
    private val paint = Paint().apply { color = AppTheme.palette.borderVariant }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) = setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), context.dp(1))

    override fun onDraw(canvas: android.graphics.Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }
}

/**
 * A Seerr discover grid (`DiscoverRequestGrid` with its title, DiscoverMoreResult; no-Compose UI): the header (the
 * page title, and for Discover Movies / Shows the sort and filter buttons) while the first row is focused, then a
 * 6-column grid of Requests cards starting at the destination's index, loading page by page. OK opens a card.
 */
@AndroidEntryPoint
class DiscoverMoreFragment : DestinationFragment() {
    private val viewModel: DiscoverRequestViewModel by viewModels(
        extrasProducer = {
            defaultViewModelCreationExtras.withCreationCallback<DiscoverRequestViewModel.Factory> {
                val destination = requireDestination<Destination.DiscoverMoreResult>()
                it.create(destination.type, destination.startIndex, destination.initialFilter)
            }
        },
    )
    private val errorViewModel: ErrorViewModel by viewModels()
    private lateinit var headerView: LinearLayout
    private lateinit var buttonsRow: LinearLayout
    private lateinit var sortButton: DiscoverSortButton
    private lateinit var filterButton: DiscoverFilterButton
    private lateinit var body: FrameLayout
    private var grid: CardGridView? = null
    private var gridPager: List<DiscoverItem?>? = null
    private var showHeader = true
    private var loadingShown: Any? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val context = requireContext()
        val destination = requireDestination<Destination.DiscoverMoreResult>()
        showHeader = viewModel.state.value.startIndex < 6
        val root =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                clipToPadding = false
            }
        headerView =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                clipToPadding = false
            }
        val title = destination.titleOverride?.getString(context) ?: getString(destination.type.stringRes)
        headerView.addView(
            gridTitleView(context, title, getString(R.string.library), pageStyle.sectionAccent),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        sortButton = DiscoverSortButton(context, viewModel::onSortChange)
        filterButton =
            DiscoverFilterButton(
                context,
                scope = { viewLifecycleOwner.lifecycleScope },
                getPossibleValues = viewModel::getPossibleFilterValues,
                onFilterChange = viewModel::onFilterChange,
            )
        buttonsRow =
            FocusRestoreRow(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                clipChildren = false
                clipToPadding = false
                setPaddingRelative(context.dp(16), 0, context.dp(16), 0)
                addView(sortButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(
                    filterButton,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginStart = context.dp(8)
                    },
                )
            }
        headerView.addView(
            buttonsRow,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(8)
            },
        )
        root.addView(headerView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        body =
            FrameLayout(context).apply {
                clipChildren = false
                clipToPadding = false
            }
        root.addView(
            body,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = context.dp(8) },
        )
        applyHeader()
        return root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.state.collectWhileStarted { state ->
            sortButton.visibility = if (state.sortOptions.isNotEmpty()) View.VISIBLE else View.GONE
            filterButton.visibility = if (state.filterOptions.isNotEmpty()) View.VISIBLE else View.GONE
            buttonsRow.visibility = if (state.sortOptions.isNotEmpty() || state.filterOptions.isNotEmpty()) View.VISIBLE else View.GONE
            sortButton.bind(state.sortOptions, state.sortAndDirection)
            filterButton.bind(state.filterOptions, state.filter)
            renderBody(state.loading, state.startIndex)
        }
    }

    private fun applyHeader() {
        headerView.visibility = if (showHeader) View.VISIBLE else View.GONE
    }

    private fun renderBody(
        loading: DataLoadingState<List<DiscoverItem?>>,
        startIndex: Int,
    ) {
        val context = requireContext()
        when (loading) {
            DataLoadingState.Pending, DataLoadingState.Loading -> {
                if (loadingShown is LoadingPageView) return
                body.removeAllViews()
                grid = null
                gridPager = null
                loadingShown = LoadingPageView(context).also { body.addView(it) }
            }

            is DataLoadingState.Error -> {
                body.removeAllViews()
                grid = null
                gridPager = null
                loadingShown = ErrorView(context, loading, errorViewModel::sendLogs).also { body.addView(it) }
            }

            is DataLoadingState.Success -> {
                val pager = loading.data
                if (pager === gridPager) return
                gridPager = pager
                loadingShown = null
                body.removeAllViews()
                val cards = CardGridView(context)
                grid = cards
                body.addView(cards, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                cards.setCards(
                    items = pager,
                    presenter =
                        cardPresenter<DiscoverItem, DiscoverItemCardView>(::DiscoverItemCardView) { card, item, _, w ->
                            card.bind(item, pageStyle, widthPx = w)
                        },
                    callbacks =
                        CardGridCallbacks(
                            onClick = { _, item -> viewModel.navigationManager.navigateTo(item.destination) },
                            onPosition = { columns, index ->
                                val show = index < columns
                                if (show != showHeader) {
                                    showHeader = show
                                    applyHeader()
                                }
                            },
                        ),
                    config = CardGridConfig(columns = 6, initialPosition = startIndex),
                    pageStyle = pageStyle,
                )
                (pager as? com.github.damontecres.wholphin.util.RequestPager<*>)?.let {
                    cards.observeChanges(viewLifecycleOwner, it.changes)
                }
                // The old grid took focus once it had loaded
                cards.grid.requestFocus()
            }
        }
    }

    override fun onScreenFocus(restoring: Boolean) {
        if (!restoring) grid?.grid?.let { if (it.requestFocus()) return }
        super.onScreenFocus(restoring)
    }

    override fun onBackPressed(): Boolean = grid?.takeIf { it.hasFocus() }?.onBackPressed() ?: false

    override fun onPageStyleChanged(style: PageStyle) {
        grid?.refresh()
    }
}
