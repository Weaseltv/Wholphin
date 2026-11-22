package com.github.damontecres.wholphin.leanback.search

import android.animation.ValueAnimator
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.fragment.app.viewModels
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.DiscoverItem
import com.github.damontecres.wholphin.leanback.shell.DestinationFragment
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.PageStyle
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.widget.card.CardGridCallbacks
import com.github.damontecres.wholphin.leanback.widget.card.DiscoverItemCardView
import com.github.damontecres.wholphin.leanback.widget.card.cardPresenter
import com.github.damontecres.wholphin.leanback.widget.control.AppButton
import com.github.damontecres.wholphin.leanback.widget.control.AppTabRow
import com.github.damontecres.wholphin.leanback.widget.control.SearchEditTextBox
import com.github.damontecres.wholphin.leanback.widget.text.TimeDisplayView
import com.github.damontecres.wholphin.ui.discover.DiscoverSearchViewModel
import com.github.damontecres.wholphin.ui.discover.SeerrRequestsViewModel
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.search.SearchResult
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive

/**
 * The Requests page (`DiscoverPage` with `DiscoverSearchPage` and `SeerrRequestsPage`, no-Compose UI). WeaselPlex: one
 * ACTIVITY tab (owner design 2026-11-03); under it the search bar (voice button and field), then the request queue,
 * or the search results while a search runs (a Retry button under the bar when the search failed).
 *
 * Kept from the old page: typing searches Seerr after 400 ms (2 characters at least), the keyboard's Search action and
 * voice search at once; a submitted search moves focus to the results when they arrive (or back to the field when
 * there are none); first focus is the voice button (the field when it is hidden); scrolling the results past their
 * first row hides the tab row, and the search bar then stops short of the clock; BACK on the field stops editing, then
 * moves on. Arriving with a request id (a new request or a notification) focuses that request's row once.
 */
@AndroidEntryPoint
class DiscoverFragment : DestinationFragment() {
    private val searchViewModel: DiscoverSearchViewModel by viewModels()
    private val requestsViewModel: SeerrRequestsViewModel by viewModels()
    private lateinit var voice: VoiceSearch

    // The old page state
    private var query = ""
    private var position = -1
    private var pendingFocusQuery: String? = null
    private var showHeader = true
    private var voiceButtonVisible = true
    private var showClock = true
    private var queueVisibleReported = false
    private var started = false

    private lateinit var tabs: AppTabRow
    private lateinit var header: FocusRestoreRow
    private var voiceButton: AppButton? = null
    private lateinit var field: SearchFieldController
    private lateinit var retry: AppButton
    private lateinit var queueBox: FrameLayout
    private lateinit var queue: RequestsQueueView
    private lateinit var results: CombinedResultsView<DiscoverItem>
    private var endPaddingAnimator: ValueAnimator? = null
    private var endPaddingPx = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        query = searchViewModel.currentQuery
        val prefs = preferences.appPreferences.interfacePreferences
        voiceButtonVisible = prefs.searchPreferences.showVoiceSearchButton
        showClock = prefs.showClock
        voice =
            VoiceSearch(this, searchViewModel.voiceInputManager) { spoken ->
                setQuery(spoken, fromUser = false)
                triggerImmediateSearch(spoken)
            }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val context = requireContext()
        val root =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                clipToPadding = false
            }
        // TabbedPage: the tab row 16dp from the top and above the content, short of the clock when it shows
        tabs =
            AppTabRow(context).apply {
                topContentPaddingDp = 20f
                bottomContentPaddingDp = 20f
                setTabs(listOf(getString(R.string.activity)), 0)
                selectedIndex = 0
                onTabClick = { selectedIndex = 0 }
            }
        root.addView(
            tabs,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(16)
                bottomMargin = context.dp(16)
                marginEnd = if (showClock) context.dp(184) else 0
            },
        )

        endPaddingPx = endPaddingFor(showHeader)
        header =
            FocusRestoreRow(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                clipChildren = false
                clipToPadding = false
                setPaddingRelative(context.dp(16), 0, endPaddingPx, 0)
            }
        if (voice.isAvailable) {
            voiceButton =
                voice.createButton(context).also { button ->
                    header.addView(
                        button,
                        LinearLayout.LayoutParams(context.dp(40), context.dp(40)).apply { marginEnd = context.dp(12) },
                    )
                }
        }
        val box = SearchEditTextBox(context).apply { id = R.id.lb_search_field }
        header.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        field = SearchFieldController(box)
        field.setText(query)
        field.onUserQuery = { text ->
            pendingFocusQuery = null
            setQuery(text, fromUser = true)
        }
        field.onSubmit = { triggerImmediateSearch(query) }
        // A submitted search waiting for its results keeps OK from starting to edit again
        field.canActivate = { pendingFocusQuery == null }
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        retry =
            AppButton(context).apply {
                setLabel(R.string.retry)
                visibility = View.GONE
                setOnClickListener { triggerImmediateSearch(query) }
            }
        root.addView(
            retry,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = context.dp(16)
                marginEnd = context.dp(16)
            },
        )

        val content =
            FrameLayout(context).apply {
                clipChildren = false
                clipToPadding = false
            }
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        queueBox =
            FrameLayout(context).apply {
                clipChildren = false
                clipToPadding = false
                setPaddingRelative(context.dp(16), 0, endPaddingPx, 0)
            }
        queue =
            RequestsQueueView(
                context,
                requestsViewModel,
                requireDestination<Destination.Discover>().focusRequestId,
                canTakeFocus = { isTopScreen },
            )
        queueBox.addView(queue, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        content.addView(queueBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        results =
            CombinedResultsView(
                context,
                pageStyle = { pageStyle },
                presenter =
                    cardPresenter<DiscoverItem, DiscoverItemCardView>(::DiscoverItemCardView) { card, item, _, w ->
                        card.bind(item, pageStyle, widthPx = w, showOverlay = true)
                    },
                callbacks =
                    CardGridCallbacks(
                        onClick = { index, item ->
                            position = index
                            searchViewModel.navigationManager.navigateTo(discoverDestination(item))
                        },
                        onPosition = { columns, index ->
                            position = index
                            setShowHeader(index < columns)
                        },
                    ),
            )
        content.addView(results.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        voice.start(viewLifecycleOwner)
        queue.attach(viewLifecycleOwner)
        renderVoiceButton()

        // Start with the current preferences, then follow changes (the voice button can be hidden in Search)
        searchViewModel.userPreferencesService.flow
            .map { it.appPreferences.interfacePreferences.searchPreferences.showVoiceSearchButton }
            .collectWhileStarted { visible ->
                voiceButtonVisible = visible
                renderVoiceButton()
            }
        searchViewModel.seerrResults.collectWhileStarted { result ->
            render(result)
            onResults(result)
        }
        val requests = requestsViewModel.requests
        combine(requests.state, requests.progress.state, requestsViewModel.actionError) { state, progress, error ->
            Triple(state, progress, error)
        }.collectWhileStarted { (state, progress, error) -> queue.setState(state, progress, error) }
        launchWhileStarted {
            while (isActive) {
                delay(60_000)
                queue.tick()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        started = true
        updateQueueVisible()
    }

    override fun onStop() {
        super.onStop()
        started = false
        updateQueueVisible()
    }

    override fun onPause() {
        voice.stop()
        super.onPause()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) voice.stop()
        updateQueueVisible()
    }

    override fun onDestroyView() {
        endPaddingAnimator?.cancel()
        voice.release()
        if (queueVisibleReported) {
            queueVisibleReported = false
            requestsViewModel.requests.setVisible(false)
        }
        super.onDestroyView()
    }

    override fun onScreenFocus(restoring: Boolean) {
        if (restoring) {
            super.onScreenFocus(true)
            return
        }
        when {
            position >= 0 && results.showsGrid -> results.grid.grid.requestFocus()
            voiceButtonVisible && voiceButton?.isShown == true && voiceButton?.requestFocus() == true -> Unit
            else -> field.box.editText.requestFocus()
        }
    }

    override fun onPreviewKeyEvent(event: KeyEvent): Boolean = field.handleKey(event)

    override fun onBackPressed(): Boolean {
        if (field.handleBack()) return true
        if (results.showsGrid && results.grid.hasFocus()) return results.grid.onBackPressed()
        return false
    }

    override fun onPageStyleChanged(style: PageStyle) {
        results.grid.refresh()
    }

    // ---- searching ----

    private fun setQuery(
        value: String,
        fromUser: Boolean,
    ) {
        if (value == query) return
        query = value
        if (!fromUser) field.setText(value)
        searchViewModel.search(value)
    }

    private fun triggerImmediateSearch(searchQuery: String) {
        field.setActive(false)
        pendingFocusQuery = searchQuery.trim().takeIf { it.isNotEmpty() }
        // Every submitted search showed a new results grid
        results.startOver()
        searchViewModel.search(searchQuery, immediate = true)
    }

    /** The old `LaunchedEffect(pendingFocusQuery, seerrResults)` */
    private fun onResults(result: SearchResult) {
        val submitted = pendingFocusQuery ?: return
        if (submitted != searchViewModel.currentQuery) return
        if (result is SearchResult.SuccessSeerr && result.items.isNotEmpty()) {
            pendingFocusQuery = null
            if (isTopScreen) results.grid.grid.requestFocus()
        } else if (result is SearchResult.Error || result is SearchResult.SuccessSeerr) {
            pendingFocusQuery = null
            if (isTopScreen) field.box.editText.requestFocus()
        }
    }

    // ---- rendering ----

    private fun render(result: SearchResult) {
        if (view == null) return
        retry.visibility = if (result is SearchResult.Error) View.VISIBLE else View.GONE
        if (result == SearchResult.NoQuery) {
            results.view.visibility = View.GONE
            queueBox.visibility = View.VISIBLE
            // Scrolling search results can hide the tab; the queue always shows it
            setShowHeader(true)
        } else {
            queueBox.visibility = View.GONE
            results.view.visibility = View.VISIBLE
            results.bind(result)
        }
        updateQueueVisible()
    }

    private fun renderVoiceButton() {
        voiceButton?.visibility = if (voiceButtonVisible) View.VISIBLE else View.GONE
    }

    private fun setShowHeader(show: Boolean) {
        if (show == showHeader) return
        showHeader = show
        tabs.visibility = if (show) View.VISIBLE else View.GONE
        animateEndPadding(endPaddingFor(show))
    }

    /**
     * With the tab row shown, the search bar and the queue run the full content width (16dp); without it the bar
     * moves up beside the clock and stops short of it.
     */
    private fun endPaddingFor(fullWidth: Boolean): Int {
        val context = requireContext()
        return if (fullWidth || !AppTheme.isNeon) context.dp(16) else TimeDisplayView.headerEndPaddingPx(context)
    }

    private fun animateEndPadding(target: Int) {
        endPaddingAnimator?.cancel()
        endPaddingAnimator =
            ValueAnimator.ofInt(endPaddingPx, target).apply {
                duration = 300
                addUpdateListener {
                    endPaddingPx = it.animatedValue as Int
                    header.setPaddingRelative(header.paddingStart, header.paddingTop, endPaddingPx, header.paddingBottom)
                    queueBox.setPaddingRelative(queueBox.paddingStart, queueBox.paddingTop, endPaddingPx, queueBox.paddingBottom)
                }
                start()
            }
    }

    /** The queue tells the repository it is on screen (the old `DisposableEffect` of `SeerrRequestsPage`) */
    private fun updateQueueVisible() {
        if (view == null) return
        val visible = queueBox.visibility == View.VISIBLE && !isHidden && started
        if (visible != queueVisibleReported) {
            queueVisibleReported = visible
            requestsViewModel.requests.setVisible(visible)
        }
    }
}
