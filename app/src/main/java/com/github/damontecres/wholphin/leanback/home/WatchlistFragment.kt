package com.github.damontecres.wholphin.leanback.home

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.fragment.app.viewModels
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.filter.DefaultForFavoritesFilterOptions
import com.github.damontecres.wholphin.data.model.ApprovedHomeLayout
import com.github.damontecres.wholphin.data.model.HomeRowViewOptions
import com.github.damontecres.wholphin.leanback.shell.DestinationFragment
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.ControlStyles
import com.github.damontecres.wholphin.leanback.theme.PageStyle
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.typeAccent
import com.github.damontecres.wholphin.leanback.widget.control.AppButton
import com.github.damontecres.wholphin.leanback.widget.dialog.ContextMenuHelper
import com.github.damontecres.wholphin.ui.components.ErrorViewModel
import com.github.damontecres.wholphin.ui.components.TypeFilterOption
import com.github.damontecres.wholphin.ui.components.ViewOptionsPoster
import com.github.damontecres.wholphin.ui.components.WatchlistTypeFilters
import com.github.damontecres.wholphin.ui.data.MovieSortOptions
import com.github.damontecres.wholphin.ui.detail.WatchlistViewModel
import dagger.hilt.android.AndroidEntryPoint

/**
 * The WeaselPlex Watchlist (no-Compose rewrite of ui/detail/WatchlistPage.kt; the Favorites destination in the
 * weaselfin flavor): the WATCHLIST page head (WeaselTV), ALL / MOVIES / SHOWS buttons where the per-type tabs were,
 * then the usual sort, filter, view options, random and grid. The buttons and head hide once the grid scrolls past its
 * first row. The list reloads in place each time the page shows again, so a title added or removed on its details
 * page is reflected on return. UP into the buttons lands on the chosen one.
 */
@AndroidEntryPoint
class WatchlistFragment : DestinationFragment() {
    private val viewModel: WatchlistViewModel by viewModels()
    private val errorViewModel: ErrorViewModel by viewModels()
    private val contextMenu by lazy { ContextMenuHelper(this, viewModel) { preferences } }

    private lateinit var filters: LinearLayout
    private lateinit var typeRow: SelectedFocusRow
    private lateinit var folder: FolderContentView
    private val typeButtons = ArrayList<AppButton>()
    private var title: GridTitleView? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val context = requireContext()
        val neon = AppTheme.isNeon
        val root =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                clipToPadding = false
            }
        filters =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                clipToPadding = false
            }
        if (neon) {
            title =
                GridTitleView(context).also {
                    filters.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                }
        }
        typeRow = SelectedFocusRow(context)
        // Same clearance the tab row it replaces left for the clock
        val endPadding = if (preferences.appPreferences.interfacePreferences.showClock) context.dp(184) else 0
        typeRow.setPadding(if (neon) 0 else context.dp(16), context.dp(if (neon) 12 else 16), endPadding, context.dp(16))
        WatchlistTypeFilters.forEachIndexed { index, option ->
            val button = typeFilterButton(context, option) { onTypeClicked(option) }
            typeButtons += button
            typeRow.addView(
                button,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    if (index > 0) marginStart = context.dp(16)
                },
            )
        }
        filters.addView(typeRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(filters, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        folder =
            FolderContentView(this, viewModel, contextMenu).apply {
                sortOptions = MovieSortOptions
                filterOptions = DefaultForFavoritesFilterOptions
                defaultViewOptions = ViewOptionsPoster
                pageStyle = this@WatchlistFragment.pageStyle
                onSendLogs = errorViewModel::sendLogs
                onClickItem = { _, item -> viewModel.navigateTo(item.destination()) }
                onPosition = { columns, index -> filters.showIf(index < columns) }
                focusOnEmpty = { typeButtons.getOrNull(selectedIndex()) }
            }
        root.addView(folder, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        title?.bind(getString(R.string.watchlist), pageStyle.sectionAccent, collectionsOptions = ApprovedHomeLayout.apply(HomeRowViewOptions()))
        typeRow.preferred = { typeButtons.getOrNull(selectedIndex()) }
        viewModel.state.collectWhileStarted { folder.bind(it) }
        viewModel.libraries.collectWhileStarted { folder.watchlistLibraries = it }
    }

    override fun onStart() {
        super.onStart()
        if (!isHidden) viewModel.refresh()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) viewModel.refresh()
    }

    override fun onPageStyleChanged(style: PageStyle) {
        folder.pageStyle = style
        title?.bind(getString(R.string.watchlist), style.sectionAccent, collectionsOptions = ApprovedHomeLayout.apply(HomeRowViewOptions()))
    }

    override fun onBackPressed(): Boolean = folder.onBackPressed()

    private fun selectedIndex(): Int =
        WatchlistTypeFilters
            .indexOfFirst { it.types == viewModel.state.value.filter.includeItemTypes }
            .coerceAtLeast(0)

    private fun onTypeClicked(option: TypeFilterOption) {
        val state = viewModel.state.value
        viewModel.onFilterChange(state.filter.copy(includeItemTypes = option.types), recursive = true)
    }
}

/**
 * `TypeFilterButton`: an outlined ALL / MOVIES / SHOWS button in the type's colour (SHOWS the series colour), its
 * uppercase label in that colour, 12dp side padding, 40dp tall.
 */
internal fun typeFilterButton(
    context: Context,
    option: TypeFilterOption,
    onClick: () -> Unit,
): AppButton {
    val accent = typeAccent(option.accentKind)
    return AppButton(context).apply {
        this.accent = accent
        setSurfaceStyle { scope -> ControlStyles.outlineButton(accent, scope.copy(outlineRest = accent)) }
        setContentPaddingDp(12f, 0f, 12f, 0f)
        contentHeightDp = 40f
        setLabel(context.getString(option.title).uppercase())
        onContentColor = { labelView.setTextColor(accent) }
        labelView.setTextColor(accent)
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }
}

/**
 * A row of buttons that, when focus comes in from outside, offers only [preferred] (the old `focusRestorer` on the
 * selected filter): UP from the grid's header lands on the chosen button, not the nearest one.
 */
internal class SelectedFocusRow(
    context: Context,
) : LinearLayout(context) {
    var preferred: (() -> View?)? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        clipToPadding = false
    }

    override fun addFocusables(
        views: ArrayList<View>,
        direction: Int,
        focusableMode: Int,
    ) {
        val target = preferred?.invoke()
        if (!hasFocus() && target != null && target.isShown) {
            target.addFocusables(views, direction, focusableMode)
            return
        }
        super.addFocusables(views, direction, focusableMode)
    }
}
