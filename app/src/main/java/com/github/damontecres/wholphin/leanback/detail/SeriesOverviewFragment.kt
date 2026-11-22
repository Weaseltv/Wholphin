package com.github.damontecres.wholphin.leanback.detail

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.view.doOnPreDraw
import androidx.fragment.app.viewModels
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.ChosenStreams
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.PageStyle
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.dpF
import com.github.damontecres.wholphin.leanback.theme.provideButtonsOverArtwork
import com.github.damontecres.wholphin.leanback.theme.provideNeonAccent
import com.github.damontecres.wholphin.leanback.widget.card.BannerCardView
import com.github.damontecres.wholphin.leanback.widget.card.BaseCardView
import com.github.damontecres.wholphin.leanback.widget.card.CardCallbacks
import com.github.damontecres.wholphin.leanback.widget.card.CardGridAdapter
import com.github.damontecres.wholphin.leanback.widget.card.CardImages
import com.github.damontecres.wholphin.leanback.widget.card.CardStripView
import com.github.damontecres.wholphin.leanback.widget.card.RowScrollMode
import com.github.damontecres.wholphin.leanback.widget.card.cardPresenter
import com.github.damontecres.wholphin.leanback.widget.card.isPlayKeyUp
import com.github.damontecres.wholphin.leanback.widget.control.AppTabRow
import com.github.damontecres.wholphin.leanback.widget.control.ErrorView
import com.github.damontecres.wholphin.leanback.widget.control.LoadingPageView
import com.github.damontecres.wholphin.leanback.widget.text.FittedDetailHeaderLayout
import com.github.damontecres.wholphin.leanback.widget.text.HeaderDimens
import com.github.damontecres.wholphin.leanback.widget.text.OverviewTextView
import com.github.damontecres.wholphin.leanback.widget.text.QuickDetailsView
import com.github.damontecres.wholphin.leanback.widget.text.TitleOrLogoView
import com.github.damontecres.wholphin.leanback.widget.text.enabledDisplayToggles
import com.github.damontecres.wholphin.leanback.widget.text.reportDetailRowHeight
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.AspectRatios
import com.github.damontecres.wholphin.ui.components.ContextMenu
import com.github.damontecres.wholphin.ui.components.ContextMenuActions
import com.github.damontecres.wholphin.ui.data.ItemDetailsDialogInfo
import com.github.damontecres.wholphin.ui.detail.KidAction
import com.github.damontecres.wholphin.ui.detail.KidTitlePage
import com.github.damontecres.wholphin.ui.detail.series.EpisodeList
import com.github.damontecres.wholphin.ui.detail.series.SeriesOverviewPosition
import com.github.damontecres.wholphin.ui.detail.series.SeriesPageType
import com.github.damontecres.wholphin.ui.detail.series.SeriesState
import com.github.damontecres.wholphin.ui.detail.series.SeriesViewModel
import com.github.damontecres.wholphin.ui.formatSeasonNumber
import com.github.damontecres.wholphin.ui.logTab
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.util.DataLoadingState
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.withCreationCallback
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.PersonKind
import org.jellyfin.sdk.model.api.UserDto
import org.jellyfin.sdk.model.extensions.ticks
import org.jellyfin.sdk.model.serializer.toUUID
import kotlin.time.Duration

/** Room under the kid header for the episode row and the kid buttons (the adult page keeps 160dp) */
private const val KID_BUTTONS_RESERVE_DP = 216f
private const val ADULT_RESERVE_DP = 160f

/**
 * The season / episode page (SeriesOverview.kt + SeriesOverviewContent.kt): the season tabs, the series' title or logo
 * with the focused episode's header (name, quick details, stream chips, overview), the season's episodes as 120dp
 * banner cards, the focused episode's buttons, then Cast & crew, Guest stars and the season's Extras.
 *
 * Focus starts on the episode the page was opened on and comes back to where it was. Focusing an episode makes it the
 * page's episode (header, buttons, cast). While focus is elsewhere on the page the other episodes dim to 40 % on black.
 * Choosing a tab loads that season and focuses its first episode once loaded. The play key plays a focused episode.
 * The kid design (owner 2026-10-23) has a short episode header, the kid buttons and no cast, guest star or extras rows.
 */
@AndroidEntryPoint
class SeriesOverviewFragment : DetailPageFragment() {
    private val destinationOverview get() = requireDestination<Destination.SeriesOverview>()

    private val viewModel: SeriesViewModel by viewModels(
        extrasProducer = {
            defaultViewModelCreationExtras.withCreationCallback<SeriesViewModel.Factory> {
                val d = requireDestination<Destination.SeriesOverview>()
                it.create(d.itemId, d.seasonEpisode, SeriesPageType.OVERVIEW)
            }
        },
    )

    private var page: OverviewPage? = null
    private var userDto: UserDto? = null
    private var cameBackFromPage = false
    private var resumedPage = false

    /** The season tab chosen: focus its first episode once its episodes load (`requestFocusAfterSeason`) */
    private var requestFocusAfterSeason = false

    private val position: SeriesOverviewPosition get() = viewModel.position.value

    private fun episodes(): List<BaseItem?>? = (viewModel.state.value.episodes as? EpisodeList.Success)?.episodes

    private fun focusedEpisode(): BaseItem? = episodes()?.let { list -> list.getOrNull(position.episodeRowIndex) }

    private val isAdmin: Boolean get() = userDto?.policy?.isAdministrator == true

    private val contextActions by lazy {
        ContextMenuActions(
            navigateTo = viewModel::navigateTo,
            onClickWatch = { id, watched -> viewModel.setWatched(id, watched, position.episodeRowIndex) },
            onClickFavorite = { id, favorite -> viewModel.setFavorite(id, favorite, position.episodeRowIndex) },
            onClickAddPlaylist = { showPlaylistDialog(it) },
            onSendMediaInfo = viewModel.serverReportService::sendMediaReportFor,
            onDeleteItem = viewModel::deleteItem,
            onChooseVersion = { item, source -> viewModel.savePlayVersion(item, source.id!!.toUUID()) },
            onChooseTracks = { result ->
                viewModel.saveTrackSelection(result.item, result.itemPlayback, result.trackIndex, result.streamType)
            },
            onShowOverview = { showOverviewDialog(ItemDetailsDialogInfo(it), isAdmin) },
            onClearChosenStreams = { chosen -> focusedEpisode()?.let { viewModel.clearChosenStreams(it, chosen) } },
        )
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        page = null
        viewModel.serverRepository.currentUserDtoFlow.collectWhileStarted { userDto = it }
        viewModel.state.collectWhileStarted { render(it) }
        viewModel.position.collectWhileStarted { render(viewModel.state.value) }
        // The focused episode's chosen tracks and (adults) cast, whenever the episode or the list changes
        // (identity comparisons: comparing pagers by content would read, and so load, every page)
        combine(viewModel.position, viewModel.state.map { it.episodes }.distinctUntilChanged { a, b -> a === b }) { pos, eps ->
            (eps as? EpisodeList.Success)?.episodes?.getOrNull(pos.episodeRowIndex)
        }.collectWhileStarted { ep ->
            if (ep != null) {
                viewModel.lookUpChosenTracks(ep.id, ep)
                // A kid's page has no cast rows (owner 2026-10-23)
                if (!viewModel.kidPage) viewModel.lookupPeopleInEpisode(ep)
            }
        }
    }

    override fun onDestroyView() {
        page?.release()
        page = null
        super.onDestroyView()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        if (hidden) cameBackFromPage = true
        super.onHiddenChanged(hidden)
    }

    override fun onPageActive() {
        // Coming back from a page on top reloads the season's episodes (the page used to be composed again)
        if (cameBackFromPage) {
            cameBackFromPage = false
            val seasons = viewModel.state.value.seasons
            if (seasons.isNotEmpty()) {
                seasons.getOrNull(position.seasonTabIndex)?.let { viewModel.loadEpisodes(it.id, it.indexNumber) }
            }
        }
        if (viewModel.state.value.series is DataLoadingState.Success) {
            resumedPage = true
            viewModel.onResumePage()
        }
    }

    override fun onPageInactive() {
        if (resumedPage) {
            resumedPage = false
            viewModel.release()
        }
    }

    override fun onPageStyleChanged(style: PageStyle) {
        render(viewModel.state.value)
    }

    override fun onScreenFocus(restoring: Boolean) {
        if (!restoring) page?.firstFocus()?.let { if (it.isAttachedToWindow && it.requestFocus()) return }
        super.onScreenFocus(restoring)
    }

    private fun render(state: SeriesState) {
        when (val st = state.series) {
            is DataLoadingState.Error -> {
                showError(st)
            }

            DataLoadingState.Loading, DataLoadingState.Pending -> {
                showLoading()
            }

            is DataLoadingState.Success -> {
                var created: OverviewPage? = null
                val first =
                    showContent {
                        OverviewPage(requireContext(), viewModel.kidPage, pageStyle.accentFor(st.data))
                            .also {
                                created = it
                                page = it
                            }.content.viewport
                    }
                page?.bind(st.data, state, preferences)
                if (first) {
                    if (isPageActive && !resumedPage) {
                        resumedPage = true
                        viewModel.onResumePage()
                    }
                    created?.let { c -> c.content.afterLayout { c.firstFocus()?.let(::requestInitialFocus) } }
                }
            }
        }
    }

    private fun play(episode: BaseItem) {
        val resume =
            episode.data.userData
                ?.playbackPositionTicks
                ?.ticks ?: Duration.ZERO
        viewModel.navigateTo(Destination.Playback(episode.id, resume.inWholeMilliseconds))
    }

    private fun episodeMenu(
        ep: BaseItem,
        fromLongClick: Boolean,
        kid: Boolean,
    ) {
        showContextMenu(
            ContextMenu.ForBaseItem(
                fromLongClick = fromLongClick,
                item = ep,
                chosenStreams = viewModel.state.value.chosenStreams,
                showGoTo = false,
                showStreamChoices = true,
                canDelete = viewModel.canDelete(ep, preferences.appPreferences),
                canRemoveContinueWatching = false,
                canRemoveNextUp = false,
                actions = contextActions,
            ),
            kid,
            viewModel.streamChoiceService::chooseSource,
            userDto?.configuration?.subtitleLanguagePreference,
        )
    }

    private inner class OverviewPage(
        context: Context,
        val kid: Boolean,
        accentAtStart: Int,
    ) {
        val content = DetailPageContent(context, spacingDp = 8f, bottomPaddingDp = 16f, topPaddingDp = 16f)

        /** The season tabs, over the artwork: white outlines and a dark backing */
        private val tabs =
            AppTabRow(context).apply {
                provideButtonsOverArtwork()
                onTabClick = { index -> onTab(index) }
            }
        private var tabTitles: List<String> = emptyList()
        private var selectedTab = -1

        private val title = TitleOrLogoView(context)
        private val episodeHeader = FocusedEpisodeHeaderView(context, kid)
        private val fitted =
            FittedDetailHeaderLayout(context, reservedHeightDp = if (kid) KID_BUTTONS_RESERVE_DP else ADULT_RESERVE_DP).apply {
                val column =
                    LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        clipChildren = false
                        addView(
                            FrameLayout(context).apply { addView(title) },
                            LinearLayout.LayoutParams(MATCH, WRAP).apply {
                                marginStart =
                                    context.headerStartPx()
                            },
                        )
                        addView(episodeHeader, LinearLayout.LayoutParams(MATCH, WRAP))
                    }
                addView(column)
                onCompactChanged = { compact ->
                    title.compactHeader = compact
                    episodeHeader.compact = compact
                }
            }

        // The episode row (or its loading / error state)
        private val episodeSlot = FrameLayout(context).apply { clipChildren = false }
        private val loadingView = LoadingPageView(context, focusEnabled = false)
        private var errorView: View? = null
        val strip =
            CardStripView(context).apply {
                scrollMode = RowScrollMode.forDetailPage()
                setHorizontalSpacing(context.dp(16))
                setBasePadding(context.dp(16), 0, context.dp(16), 0)
                focusAppearance = pageStyle.homeCardAppearance
                reportDetailRowHeight(0)
            }
        private var epPosition = -1
        private var shownEpisodes: List<BaseItem?>? = null
        private val presenter =
            cardPresenter<BaseItem, BannerCardView>(::BannerCardView) { card, episode, index, _ ->
                card.bind(
                    item = episode,
                    style = pageStyle,
                    name = episode?.name,
                    cardHeightDp = 120f,
                    aspectRatio = episode?.aspectRatio?.coerceAtLeast(AspectRatios.FOUR_THREE) ?: AspectRatios.WIDE,
                    cornerText = episode?.ui?.episodeCornerText,
                    played = episode?.data?.userData?.played ?: false,
                    playPercent = episode?.data?.userData?.playedPercentage ?: 0.0,
                    useSeriesForPrimary = false,
                )
                decorate(card, index)
            }
        private val adapter =
            CardGridAdapter(
                presenter,
                CardCallbacks(
                    onClick = { index, ep ->
                        epPosition = index
                        play(ep)
                    },
                    onLongClick = { index, ep ->
                        epPosition = index
                        episodeMenu(ep, true, kid)
                    },
                    onFocus = { index, _ ->
                        if (index != position.episodeRowIndex) viewModel.position.value = position.copy(episodeRowIndex = index)
                    },
                    onKey = { _, ep, event ->
                        if (ep != null && isPlayKeyUp(event)) {
                            play(ep)
                            true
                        } else {
                            false
                        }
                    },
                ),
            )

        // The focused episode's buttons
        private val footer: ExpandablePlayButtonsView? =
            if (kid) {
                null
            } else {
                ExpandablePlayButtonsView(
                    context,
                    PlayButtonsActions(
                        playOnClick = { resume ->
                            focusedEpisode()?.let {
                                viewModel.release()
                                viewModel.navigateTo(Destination.Playback(it.id, resume.inWholeMilliseconds))
                            }
                        },
                        watchOnClick = {
                            focusedEpisode()?.let {
                                viewModel.setWatched(it.id, !(it.data.userData?.played ?: false), position.episodeRowIndex)
                            }
                            focusLastEpisodeCard()
                        },
                        favoriteOnClick = {
                            focusedEpisode()?.let {
                                viewModel.setFavorite(it.id, !(it.data.userData?.isFavorite ?: false), position.episodeRowIndex)
                            }
                        },
                        moreOnClick = { focusedEpisode()?.let { episodeMenu(it, false, kid) } },
                        trailerOnClick = {},
                        onConfirmDelete = { focusedEpisode()?.let { viewModel.deleteItem(it) } },
                        onChooseVersion = { source -> focusedEpisode()?.let { contextActions.onChooseVersion.invoke(it, source) } },
                    ),
                    collapseMore = false,
                    bottomPaddingDp = 0f,
                )
            }
        private val kidFooter: KidTitleButtonsView? =
            if (kid) {
                KidTitleButtonsView(
                    context,
                    accent = accentAtStart,
                    onPlay = { play -> focusedEpisode()?.let { viewModel.navigateTo(Destination.Playback(it.id, play.positionMs)) } },
                    onAction = { action ->
                        val ep = focusedEpisode() ?: return@KidTitleButtonsView
                        when (action) {
                            KidAction.START_OVER -> {
                                viewModel.navigateTo(Destination.Playback(ep.id, 0L))
                            }

                            KidAction.WATCHLIST -> {
                                viewModel.setFavorite(ep.id, !(ep.data.userData?.isFavorite ?: false), position.episodeRowIndex)
                            }

                            KidAction.SHUFFLE -> {}
                        }
                    },
                )
            } else {
                null
            }
        private val footerSlot =
            FrameLayout(context).apply {
                clipChildren = false
                clipToPadding = false
            }

        private val topBlock =
            verticalBlock(context, 4f).apply {
                content.headerPadding(this)
                addView(tabs, LinearLayout.LayoutParams(MATCH, WRAP))
                addView(fitted, LinearLayout.LayoutParams(MATCH, WRAP))
                episodeSlot.addView(strip, FrameLayout.LayoutParams(MATCH, WRAP))
                episodeSlot.addView(loadingView, FrameLayout.LayoutParams(MATCH, WRAP))
                addView(episodeSlot, LinearLayout.LayoutParams(MATCH, WRAP))
                footer?.let { footerSlot.addView(it, it.bledLayoutParams()) }
                kidFooter?.let { footerSlot.addView(it, FrameLayout.LayoutParams(MATCH, WRAP)) }
                addView(footerSlot, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.dp(4) })
            }
        private val castRow = detailCardRow(context)
        private val guestRow = detailCardRow(context)
        private val extrasRow = detailCardRow(context)

        // Dimming: other episodes fade to 40 % while focus is on the page but not on the episodes
        private var dimming = 1f
        private var dimAnimator: ValueAnimator? = null
        private val focusListener =
            ViewTreeObserver.OnGlobalFocusChangeListener { _, _ -> updateDimming() }

        init {
            strip.adapter = adapter
            content.viewport.viewTreeObserver.addOnGlobalFocusChangeListener(focusListener)
            val tabParams = tabs.layoutParams as LinearLayout.LayoutParams
            // The tabs sit below the clock on the WeaselTV theme and run to the page's edge; the first tab lines up with
            // the title and buttons. Other themes leave room for the clock.
            if (AppTheme.isNeon) {
                tabParams.marginStart = context.dp(HeaderDimens.START_PADDING)
                tabParams.marginEnd = 0
            } else {
                tabParams.marginStart = 0
                tabParams.marginEnd = context.dp(if (preferences.appPreferences.interfacePreferences.showClock) 184 else 16)
            }
            tabParams.bottomMargin = context.dp(4)
        }

        fun release() {
            content.viewport.viewTreeObserver.removeOnGlobalFocusChangeListener(focusListener)
            dimAnimator?.cancel()
        }

        fun firstFocus(): View? {
            if (strip.visibility == View.VISIBLE && strip.adapter?.itemCount ?: 0 > 0) return strip
            return footer?.restoreTarget ?: kidFooter?.firstFocusView ?: tabs.tabAt(selectedTab.coerceAtLeast(0))
        }

        private fun onTab(index: Int) {
            selectedTab = index
            tabs.selectedIndex = index
            logTab("series_overview", index)
            if (index != position.seasonTabIndex) {
                viewModel.state.value.seasons.getOrNull(index)?.let { season ->
                    viewModel.loadEpisodes(season.id, season.indexNumber)
                    viewModel.position.value = SeriesOverviewPosition(index, 0)
                }
                requestFocusAfterSeason = true
            } else if (viewModel.state.value.episodes is EpisodeList.Success && strip.visibility == View.VISIBLE) {
                // The season shown: focus its episode at once
                strip.requestFocus()
            } else {
                requestFocusAfterSeason = true
            }
        }

        /** After Watched in the footer, focus goes back to the episode row's last clicked card */
        private fun focusLastEpisodeCard() {
            val index = if (epPosition >= 0) epPosition else position.episodeRowIndex
            strip.findViewHolderForAdapterPosition(index)?.itemView?.let { card ->
                (card as? BaseCardView)?.frame?.requestFocus() ?: card.requestFocus()
            }
        }

        private fun updateDimming() {
            val pageHasFocus = topBlock.hasFocus() || castRow.hasFocus() || guestRow.hasFocus() || extrasRow.hasFocus()
            val target = if (pageHasFocus && !strip.hasFocus()) .4f else 1f
            if (target == dimming && dimAnimator?.isRunning != true) return
            dimAnimator?.cancel()
            dimAnimator =
                ValueAnimator.ofFloat(dimming, target).apply {
                    addUpdateListener {
                        dimming = it.animatedValue as Float
                        decorateAll()
                    }
                    start()
                }
        }

        private fun decorateAll() {
            for (i in 0 until strip.childCount) {
                val child = strip.getChildAt(i)
                val index = strip.getChildAdapterPosition(child)
                if (index >= 0) decorate(child, index)
            }
        }

        private val blackBackground =
            GradientDrawable().apply {
                setColor(0xFF000000.toInt())
                cornerRadius = context.dpF(8f)
            }

        /** Episodes other than the page's episode sit on black and fade with [dimming] */
        private fun decorate(
            card: View,
            index: Int,
        ) {
            val current = index == position.episodeRowIndex
            card.background = if (current) null else blackBackground
            card.alpha = if (current) 1f else dimming
        }

        fun bind(
            series: BaseItem,
            state: SeriesState,
            preferences: UserPreferences,
        ) {
            val accent = pageStyle.accentFor(series)
            content.viewport.provideNeonAccent(accent)
            val pos = position
            // Tabs
            val titles =
                state.seasons.map { season ->
                    season?.name ?: season?.data?.indexNumber?.let { formatSeasonNumber(it) } ?: ""
                }
            if (titles != tabTitles) {
                val hadFocus = tabs.hasFocus()
                tabTitles = titles
                selectedTab = pos.seasonTabIndex
                tabs.setTabs(titles, pos.seasonTabIndex)
                if (hadFocus) tabs.tabAt(selectedTab)?.requestFocus()
            } else if (selectedTab != pos.seasonTabIndex) {
                selectedTab = pos.seasonTabIndex
                tabs.selectedIndex = pos.seasonTabIndex
            }
            watchPager("seasons", state.seasons) { render(viewModel.state.value) }

            title.bind(series, preferences.appPreferences.interfacePreferences.showLogos, CardImages.imageUrlService(requireContext()))
            val ep = focusedEpisode()
            episodeHeader.bind(ep, state.chosenStreams, preferences, series.data.officialRating) {
                ep?.let { showOverviewDialog(ItemDetailsDialogInfo(it), isAdmin) }
            }

            // Episodes
            when (val eps = state.episodes) {
                EpisodeList.Loading -> {
                    errorView?.let { episodeSlot.removeView(it) }
                    errorView = null
                    loadingView.visibility = View.VISIBLE
                    strip.visibility = View.GONE
                }

                is EpisodeList.Error -> {
                    loadingView.visibility = View.GONE
                    strip.visibility = View.GONE
                    errorView?.let { episodeSlot.removeView(it) }
                    errorView =
                        ErrorView(requireContext(), eps.message, eps.exception, onSendLogs = errorViewModel::sendLogs).also {
                            episodeSlot.addView(it, FrameLayout.LayoutParams(MATCH, WRAP))
                        }
                }

                is EpisodeList.Success -> {
                    errorView?.let { episodeSlot.removeView(it) }
                    errorView = null
                    loadingView.visibility = View.GONE
                    strip.visibility = View.VISIBLE
                    if (shownEpisodes !== eps.episodes) {
                        val newList = shownEpisodes != null
                        shownEpisodes = eps.episodes
                        if (epPosition < 0) epPosition = pos.episodeRowIndex
                        adapter.setItems(eps.episodes)
                        strip.selectedPosition = pos.episodeRowIndex.coerceIn(0, (eps.episodes.size - 1).coerceAtLeast(0))
                        if (requestFocusAfterSeason) {
                            // Changing seasons: move focus once the new episodes are loaded
                            requestFocusAfterSeason = false
                            strip.doOnPreDraw { strip.requestFocus() }
                        } else if (newList && strip.hasFocus()) {
                            strip.doOnPreDraw { strip.requestFocus() }
                        }
                    }
                    watchPager("episodes", eps.episodes) {
                        adapter.refresh()
                        render(viewModel.state.value)
                    }
                    decorateAll()
                }
            }

            // Footer
            if (ep != null) {
                footerSlot.visibility = View.VISIBLE
                footer?.bind(
                    PlayButtonsState(
                        title = ep.title ?: "",
                        resumePosition =
                            ep.data.userData
                                ?.playbackPositionTicks
                                ?.ticks ?: Duration.ZERO,
                        watched = ep.data.userData?.played ?: false,
                        favorite = ep.data.userData?.isFavorite ?: false,
                        canDelete = viewModel.canDelete(ep, preferences.appPreferences),
                        trailers = null,
                        mediaSources = ep.data.mediaSources.orEmpty(),
                        chosenStreams = state.chosenStreams,
                    ),
                )
                kidFooter?.let {
                    val play = KidTitlePage.episodePlay(ep.data)
                    it.bind(play, KidTitlePage.movieActions(play), ep.data.userData?.isFavorite ?: false)
                }
            } else {
                footerSlot.visibility = View.GONE
            }

            // Rows
            val rows = mutableListOf(DetailRowSpec("top", { topBlock }))
            val (guestStars, castAndCrew) = state.peopleInEpisode.people.partition { it.type == PersonKind.GUEST_STAR }
            if (state.peopleInEpisode.people.isNotEmpty() && !kid) {
                if (castAndCrew.isNotEmpty()) {
                    rows +=
                        DetailRowSpec("cast", { castRow }) {
                            castRow.showPeople(
                                castAndCrew,
                                pageStyle,
                                onClick = { viewModel.navigateTo(Destination.MediaItem(it.id, BaseItemKind.PERSON)) },
                                title = getString(R.string.cast_and_crew),
                                titleAccent = accent,
                                style = detailPersonRowStyle(requireContext()),
                                accent = accent,
                            )
                        }
                }
                if (guestStars.isNotEmpty()) {
                    rows +=
                        DetailRowSpec("guests", { guestRow }) {
                            guestRow.showPeople(
                                guestStars,
                                pageStyle,
                                onClick = { viewModel.navigateTo(Destination.MediaItem(it.id, BaseItemKind.PERSON)) },
                                title = getString(R.string.guest_stars),
                                titleAccent = accent,
                                style = detailPersonRowStyle(requireContext()),
                                accent = accent,
                            )
                        }
                }
            }
            if (state.extras.isNotEmpty() && !kid) {
                rows +=
                    DetailRowSpec("extras", { extrasRow }) {
                        extrasRow.showExtras(
                            state.extras,
                            pageStyle,
                            onClickItem = { _, extra -> viewModel.navigateTo(extra.destination) },
                            onLongClickItem = { _, _ -> },
                            titleAccent = accent,
                            style = detailItemRowStyle(requireContext()),
                            accent = accent,
                        )
                    }
            }
            content.list.listAdapter.submit(rows)
        }
    }
}

/**
 * `FocusedEpisodeHeader`: the episode's name, then its quick details, stream chips and overview (3 lines), 8dp apart,
 * in a column 60 % wide. The kid design shows "S1 E9 • 23m • [rating]" (the series' rating as fallback) and the full
 * description.
 */
class FocusedEpisodeHeaderView(
    context: Context,
    private val kid: Boolean,
) : FractionColumn(context, .6f, 8f) {
    private val name = episodeNameView(context)
    private val quickDetails = QuickDetailsView(context)
    private val streams = StreamDetailsView(context)
    private val overview = OverviewTextView(context).apply { maxLines = 3 }
    private val kidInfo = KidEpisodeInfoView(context)

    init {
        addPadded(name)
        if (kid) {
            addView(kidInfo, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        } else {
            addPadded(quickDetails)
            addPadded(streams)
            addView(overview, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
    }

    var compact: Boolean = false
        set(value) {
            field = value
            overview.compact = value
            kidInfo.compact = value
        }

    fun bind(
        ep: BaseItem?,
        chosenStreams: ChosenStreams?,
        preferences: UserPreferences,
        seriesRating: String?,
        overviewOnClick: () -> Unit,
    ) {
        val dto = ep?.data
        name.text = episodeName(dto)
        if (kid) {
            kidInfo.visibility = if (dto != null) View.VISIBLE else View.GONE
            dto?.let { kidInfo.bind(it, seriesRating) }
            return
        }
        if (ep != null) {
            quickDetails.visibility = View.VISIBLE
            quickDetails.bind(
                ep.ui.quickDetailsText,
                ep.timeRemainingOrRuntime,
                enabledToggles = enabledDisplayToggles(preferences.appPreferences),
            )
        } else {
            quickDetails.visibility = View.GONE
        }
        streams.visibility = if (dto != null) View.VISIBLE else View.GONE
        if (dto != null) streams.bind(chosenStreams, dto.mediaSourceCount ?: 0)
        overview.setText(dto?.overview ?: "")
        overview.onClick = overviewOnClick
    }
}
