package com.github.damontecres.wholphin.leanback.search

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.PageStyle
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.widget.card.CardGridCallbacks
import com.github.damontecres.wholphin.leanback.widget.card.CardGridConfig
import com.github.damontecres.wholphin.leanback.widget.card.CardGridView
import com.github.damontecres.wholphin.leanback.widget.card.CardPresenter
import com.github.damontecres.wholphin.leanback.widget.card.PlaceholderRowView
import com.github.damontecres.wholphin.leanback.widget.card.RowTitleView
import com.github.damontecres.wholphin.ui.detail.CardGridItem
import com.github.damontecres.wholphin.ui.search.SearchResult

/**
 * Combined results (`SearchCombinedResults`): "Searching…", the error or "No results" under a Results title (16dp
 * padding), or the Results title over a 6-column grid of cards.
 */
internal class CombinedResultsView<T : CardGridItem>(
    context: Context,
    private val pageStyle: () -> PageStyle,
    private val presenter: CardPresenter<T>,
    private val callbacks: CardGridCallbacks<T>,
) {
    val view = FrameLayout(context).apply { clipChildren = false }
    private val placeholderBox = FrameLayout(context).apply { clipChildren = false }
    private val placeholder = PlaceholderRowView(context)
    private val gridColumn =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
        }
    private val title = RowTitleView(context)
    var grid = CardGridView(context)
        private set
    private var lastWasGrid = false

    init {
        val pad = context.dp(16)
        placeholderBox.setPadding(pad, pad, pad, pad)
        placeholderBox.addView(
            placeholder,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).bleedIntoGutters(context),
        )
        gridColumn.addView(
            title,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).bleedIntoGutters(context),
        )
        gridColumn.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        view.addView(placeholderBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        view.addView(gridColumn, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** The next results get a fresh grid at the first card (Requests: a new grid for every submitted search) */
    fun startOver() {
        lastWasGrid = false
    }

    /** The grid shows cards and can take focus */
    val showsGrid: Boolean get() = view.visibility == View.VISIBLE && gridColumn.visibility == View.VISIBLE

    @Suppress("UNCHECKED_CAST")
    fun bind(result: SearchResult) {
        val context = view.context
        val resultsTitle = context.getString(R.string.results)
        fun message(
            text: String,
            error: Boolean,
        ) {
            lastWasGrid = false
            gridColumn.visibility = View.GONE
            placeholderBox.visibility = View.VISIBLE
            placeholder.bind(
                resultsTitle,
                text,
                pageStyle().sectionAccent,
                isError = error,
                focusableRow = false,
                insetStartPx = context.gutterStartPx(),
                insetEndPx = context.gutterEndPx(),
            )
        }

        fun showGrid(items: List<T?>) {
            if (items.isEmpty()) {
                message(context.getString(R.string.no_results), false)
                return
            }
            if (!lastWasGrid) {
                // The old grid was a new composition after "Searching…": start a fresh one at the first card
                gridColumn.removeView(grid)
                grid = CardGridView(context)
                gridColumn.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            }
            lastWasGrid = true
            placeholderBox.visibility = View.GONE
            gridColumn.visibility = View.VISIBLE
            title.bind(
                resultsTitle,
                pageStyle().sectionAccent,
                insetStartPx = context.gutterStartPx(),
                insetEndPx = context.gutterEndPx(),
                titleStartPaddingDp = if (AppTheme.isNeon) 0f else 8f,
            )
            grid.setCards(items, presenter, callbacks, CardGridConfig(), pageStyle())
        }
        when (result) {
            SearchResult.NoQuery -> {
                lastWasGrid = false
                placeholderBox.visibility = View.GONE
                gridColumn.visibility = View.GONE
            }

            SearchResult.Searching -> {
                message(context.getString(R.string.searching), false)
            }

            is SearchResult.Error -> {
                message(result.ex.localizedMessage ?: "Error occurred during search", true)
            }

            is SearchResult.Success -> {
                showGrid(result.items as List<T?>)
            }

            is SearchResult.SuccessSeerr -> {
                showGrid(result.items as List<T?>)
            }
        }
    }
}
