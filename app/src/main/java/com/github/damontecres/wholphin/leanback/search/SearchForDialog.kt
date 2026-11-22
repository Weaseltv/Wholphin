package com.github.damontecres.wholphin.leanback.search

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.CardDimens
import com.github.damontecres.wholphin.leanback.theme.PageStyle
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.applyTextRole
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.widget.card.CardCallbacks
import com.github.damontecres.wholphin.leanback.widget.card.CardRowAdapter
import com.github.damontecres.wholphin.leanback.widget.card.CardRowView
import com.github.damontecres.wholphin.leanback.widget.card.RowTitleView
import com.github.damontecres.wholphin.leanback.widget.card.SeasonCardView
import com.github.damontecres.wholphin.leanback.widget.card.cardPresenter
import com.github.damontecres.wholphin.leanback.widget.control.ErrorView
import com.github.damontecres.wholphin.leanback.widget.control.SearchEditTextBox
import com.github.damontecres.wholphin.leanback.widget.dialog.AppDialog
import com.github.damontecres.wholphin.leanback.widget.dialog.DialogOptions
import com.github.damontecres.wholphin.ui.search.SearchForViewModel
import com.github.damontecres.wholphin.ui.search.SearchResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemKind

/**
 * `SearchForDialog` (no-Compose UI): a panel 80 % of the screen wide and 66 % tall (8dp above and below, no side
 * padding so rules and the divider reach its edges) with "Search Collections / Playlists", the voice button and the
 * search field, then Results and, under a divider, Suggestions (recently added), each a row of episode-height posters;
 * OK on one calls [onClick]. Typing searches after 750 ms, the keyboard's Search action and voice search at once; the
 * field's editing rules are the search pages' (OK starts editing, BACK stops it, then moves on).
 *
 * The host fragment owns [viewModel] (one per type, `SearchForViewModel.Factory`) and [voice] (created with the
 * fragment, see [VoiceSearch]); pass null for no voice button. Today Home settings (SETTINGS area) search with their
 * own Leanback Preference rows on the same view model, so nothing opens this dialog yet.
 */
internal class SearchForDialog(
    context: Context,
    private val owner: LifecycleOwner,
    private val viewModel: SearchForViewModel,
    private val searchType: BaseItemKind,
    private val voice: VoiceSearch?,
    private val pageStyle: PageStyle,
    private val onSendLogs: () -> Unit,
    private val onClick: (BaseItem) -> Unit,
    opener: View? = null,
) : AppDialog(context, DialogOptions(usePlatformDefaultWidth = false, elevationDp = 3f), opener = opener) {
    private val field = SearchFieldController(SearchEditTextBox(context))
    private val results = ResultsRow(context, context.getString(R.string.results))
    private val suggestions = ResultsRow(context, context.getString(R.string.suggestions))
    private var query = ""
    private var immediateSearchQuery: String? = null
    private var debounce: Job? = null
    private var stateJob: Job? = null

    init {
        val content =
            object : LinearLayout(context) {
                override fun onMeasure(
                    widthMeasureSpec: Int,
                    heightMeasureSpec: Int,
                ) {
                    val metrics = resources.displayMetrics
                    super.onMeasure(
                        MeasureSpec.makeMeasureSpec((metrics.widthPixels * .8f).toInt(), MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec((metrics.heightPixels * .66f).toInt(), MeasureSpec.EXACTLY),
                    )
                }
            }.apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                clipToPadding = false
                setPadding(0, context.dp(8), 0, context.dp(8))
            }
        val what =
            when (searchType) {
                BaseItemKind.BOX_SET -> context.getString(R.string.collections)
                BaseItemKind.PLAYLIST -> context.getString(R.string.playlists)
                else -> ""
            }
        content.addView(
            TextView(context).apply {
                text = context.getString(R.string.search_for, what)
                applyTextRole(TextRole.TITLE_LARGE)
                setTextColor(AppTheme.palette.onSurface)
                gravity = Gravity.CENTER_HORIZONTAL
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        val bar =
            FocusRestoreRow(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                clipChildren = false
                clipToPadding = false
                defaultFocus = field.box.editText
            }
        voice?.takeIf { it.isAvailable }?.let {
            bar.addView(
                it.createButton(context),
                LinearLayout.LayoutParams(context.dp(40), context.dp(40)).apply {
                    marginEnd =
                        context.dp(12)
                },
            )
        }
        bar.addView(field.box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(
            FrameLayout(context).apply {
                clipChildren = false
                addView(
                    bar,
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
                )
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(8)
            },
        )
        val list = PageRowsView(context, keepVisible = AppTheme.isNeon).apply { setPadding(0, 0, 0, context.dp(28)) }
        val divider =
            object : View(context) {
                private val paint = Paint().apply { color = AppTheme.palette.borderVariant }

                override fun onMeasure(
                    widthMeasureSpec: Int,
                    heightMeasureSpec: Int,
                ) = setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), context.dp(1))

                override fun onDraw(canvas: Canvas) = canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            }
        listOf(results.view, divider, suggestions.view).forEachIndexed { index, row ->
            row.layoutParams =
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    if (index > 0) topMargin = context.dp(8)
                }
        }
        list.setRows(listOf(results.view, divider, suggestions.view))
        content.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = context.dp(8) })
        setPanelContent(content)

        field.onUserQuery = { setQuery(it) }
        field.onSubmit = { triggerImmediateSearch(query) }
    }

    /** A voice result from the host's [VoiceSearch]: search for it at once */
    fun onSpeechResult(spoken: String) {
        field.setText(spoken)
        triggerImmediateSearch(spoken)
        setQuery(spoken)
    }

    private fun setQuery(value: String) {
        if (value == query) return
        query = value
        debounce?.cancel()
        if (immediateSearchQuery == value) {
            immediateSearchQuery = null
            return
        }
        debounce =
            owner.lifecycleScope.launch {
                delay(750L)
                viewModel.search(searchType, value)
            }
    }

    private fun triggerImmediateSearch(value: String) {
        immediateSearchQuery = value
        viewModel.search(searchType, value)
    }

    override fun onStart() {
        super.onStart()
        stateJob =
            owner.lifecycleScope.launch {
                viewModel.state.collect { state ->
                    results.bind(state.results)
                    suggestions.bind(state.recent)
                }
            }
        field.box.editText.requestFocus()
    }

    override fun onStop() {
        stateJob?.cancel()
        debounce?.cancel()
        voice?.stop()
        super.onStop()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (field.handleKey(event)) return true
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP && field.handleBack()) return true
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN && field.box.editText.isFocused) return true
        return super.dispatchKeyEvent(event)
    }

    /** `SearchForResultsRow` */
    private inner class ResultsRow(
        context: Context,
        private val title: String,
    ) {
        val view = FrameLayout(context).apply { clipChildren = false }
        private val titleOnly = RowTitleView(context)
        private val cards = CardRowView(context)
        private val adapter =
            CardRowAdapter(
                cardPresenter<BaseItem, SeasonCardView>(::SeasonCardView) { card, item, _, _ ->
                    card.bind(item, pageStyle, imageHeightDp = CardDimens.HEIGHT_EPISODE_DP.toFloat())
                },
                CardCallbacks(onClick = { _, item -> onClick(item) }, onLongClick = { _, _ -> }),
            )
        private var error: View? = null

        fun bind(result: SearchResult) {
            error?.let { view.removeView(it) }
            error = null
            view.removeView(titleOnly)
            view.removeView(cards)

            fun titleRow(text: String) {
                titleOnly.bind(text, pageStyle.sectionAccent)
                view.addView(titleOnly)
            }
            when (result) {
                is SearchResult.Error -> {
                    error = ErrorView(view.context, "Error", result.ex, onSendLogs).also { view.addView(it) }
                }

                SearchResult.NoQuery -> {
                    titleRow(view.context.getString(R.string.no_results))
                }

                SearchResult.Searching -> {
                    titleRow(view.context.getString(R.string.searching))
                }

                is SearchResult.SuccessSeerr -> {
                    error =
                        TextView(view.context)
                            .apply {
                                text = "Not supported"
                                setTextColor(AppTheme.palette.error)
                            }.also { view.addView(it) }
                }

                is SearchResult.Success -> {
                    if (result.items.isEmpty()) {
                        titleRow(view.context.getString(R.string.no_results))
                    } else {
                        cards.setup(title, pageStyle.sectionAccent, pageStyle)
                        cards.setCards(adapter, result.items)
                        view.addView(cards)
                    }
                }
            }
        }
    }
}
