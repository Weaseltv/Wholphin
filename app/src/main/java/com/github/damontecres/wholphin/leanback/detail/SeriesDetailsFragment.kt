package com.github.damontecres.wholphin.leanback.detail

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.fragment.app.viewModels
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.studioNames
import com.github.damontecres.wholphin.leanback.theme.CardDimens
import com.github.damontecres.wholphin.leanback.theme.PageStyle
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.provideButtonsOverArtwork
import com.github.damontecres.wholphin.leanback.theme.provideNeonAccent
import com.github.damontecres.wholphin.leanback.widget.card.CardCallbacks
import com.github.damontecres.wholphin.leanback.widget.card.CardGridAdapter
import com.github.damontecres.wholphin.leanback.widget.card.CardImages
import com.github.damontecres.wholphin.leanback.widget.card.CardRowAdapter
import com.github.damontecres.wholphin.leanback.widget.dialog.ConfirmDialog
import com.github.damontecres.wholphin.leanback.widget.text.FittedDetailHeaderLayout
import com.github.damontecres.wholphin.leanback.widget.text.GenreTextView
import com.github.damontecres.wholphin.leanback.widget.text.HeaderDimens
import com.github.damontecres.wholphin.leanback.widget.text.OverviewTextView
import com.github.damontecres.wholphin.leanback.widget.text.QuickDetailsView
import com.github.damontecres.wholphin.leanback.widget.text.TitleOrLogoView
import com.github.damontecres.wholphin.leanback.widget.text.enabledDisplayToggles
import com.github.damontecres.wholphin.leanback.widget.text.reportDetailActionHeight
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.services.TrailerService
import com.github.damontecres.wholphin.ui.components.ContextMenu
import com.github.damontecres.wholphin.ui.components.ContextMenuActions
import com.github.damontecres.wholphin.ui.components.PersonContextActions
import com.github.damontecres.wholphin.ui.data.ItemDetailsDialogInfo
import com.github.damontecres.wholphin.ui.detail.KidAction
import com.github.damontecres.wholphin.ui.detail.KidTitlePage
import com.github.damontecres.wholphin.ui.detail.series.SeriesPageType
import com.github.damontecres.wholphin.ui.detail.series.SeriesState
import com.github.damontecres.wholphin.ui.detail.series.SeriesViewModel
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.seriesProductionYears
import com.github.damontecres.wholphin.util.DataLoadingState
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.withCreationCallback
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.serializer.toUUID

/** `SeasonsRowReserve`: the space kept below the buttons for the Seasons row (title, posters and captions) */
internal val SEASONS_ROW_RESERVE_DP: Float get() = CardDimens.HEIGHT_2X3_DP + 116f

private const val SEASONS_ROW = 1
private const val PEOPLE_ROW = 2
private const val EXTRAS_ROW = 4
private const val SIMILAR_ROW = 5

/**
 * The series page (SeriesDetails.kt): the header (title or logo, quick details, studios and genres, overview) fitted
 * above the Seasons row, the buttons (Watchlist, Trailers, Watched, Random and Shuffle expanding on focus, Delete; no
 * Play: pick a season first), then Seasons (n), People, Extras, More like this and (other flavors) Discover. Marking
 * the whole series watched asks first. The kid page (owner 2026-10-23) shows the kid header and buttons (Play arrives
 * once the next episode is found) and only Seasons and More like this, with no long-press menus.
 *
 * Focus starts on Watchlist (the kid page's Play, else Watchlist) and returns to where it was.
 */
@AndroidEntryPoint
class SeriesDetailsFragment : DetailPageFragment() {
    private val viewModel: SeriesViewModel by viewModels(
        extrasProducer = {
            defaultViewModelCreationExtras.withCreationCallback<SeriesViewModel.Factory> {
                it.create(requireDestination<Destination.MediaItem>().itemId, null, SeriesPageType.DETAILS)
            }
        },
    )

    private var page: SeriesPage? = null
    private var resumedPage = false

    private val itemId get() = requireDestination<Destination.MediaItem>().itemId

    private val contextActions by lazy {
        ContextMenuActions(
            navigateTo = viewModel::navigateTo,
            onClickWatch = { id, watched ->
                // Confirm if marking the whole series
                if (id == itemId) confirmWatched() else viewModel.setWatched(id, watched, null)
            },
            onClickFavorite = { id, favorite -> viewModel.setFavorite(id, favorite, null) },
            onClickAddPlaylist = { showPlaylistDialog(it) },
            onSendMediaInfo = viewModel.serverReportService::sendMediaReportFor,
            onDeleteItem = viewModel::deleteItem,
            onChooseVersion = { item, source -> viewModel.savePlayVersion(item, source.id!!.toUUID()) },
            onChooseTracks = { result ->
                viewModel.saveTrackSelection(result.item, result.itemPlayback, result.trackIndex, result.streamType)
            },
            onShowOverview = { showOverviewDialog(ItemDetailsDialogInfo(it), false) },
            onClearChosenStreams = {},
        )
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        page = null
        viewModel.state.collectWhileStarted { render(it) }
    }

    override fun onDestroyView() {
        page = null
        super.onDestroyView()
    }

    override fun onPageActive() {
        viewModel.refresh()
        if (viewModel.state.value.series is DataLoadingState.Success) {
            resumedPage = true
            viewModel.onResumePage()
        }
    }

    override fun onPageInactive() {
        resumedPage = false
        viewModel.release()
    }

    override fun onPageStyleChanged(style: PageStyle) {
        render(viewModel.state.value)
    }

    override fun onScreenFocus(restoring: Boolean) {
        if (!restoring) page?.firstFocus()?.let { if (it.isAttachedToWindow && it.requestFocus()) return }
        super.onScreenFocus(restoring)
    }

    private fun confirmWatched() {
        val series = viewModel.state.value.series as? DataLoadingState.Success ?: return
        val played =
            series.data.data.userData
                ?.played ?: false
        ConfirmDialog.show(
            context = requireContext(),
            title = series.data.name ?: "",
            body = getString(if (played) R.string.mark_entire_series_as_unplayed else R.string.mark_entire_series_as_played),
            onConfirm = { viewModel.setWatchedSeries(!played) },
            opener = view?.findFocus(),
        )
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
                var created: SeriesPage? = null
                val first =
                    showContent {
                        SeriesPage(requireContext(), viewModel.kidPage, pageStyle.accentFor(st.data))
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

    private inner class SeriesPage(
        context: Context,
        val kid: Boolean,
        accentAtStart: Int,
    ) {
        val content = DetailPageContent(context, spacingDp = 16f, bottomPaddingDp = 80f)
        private var series: BaseItem? = null

        val header = SeriesHeaderView(context, kid)
        private val fitted =
            FittedDetailHeaderLayout(context, firstRowHeightDp = if (kid) KID_ROW_PEEK_DP else SEASONS_ROW_RESERVE_DP).apply {
                addView(header)
                onCompactChanged = { header.compact = it }
            }

        // Over the series artwork: white outlines and a dark backing for readability
        private val buttons: ActionButtonRow? = if (kid) null else ActionButtonRow(context, 16f, BUTTON_GLOW_ROOM_DP)
        private val favoriteButton =
            DetailButtons.fa(context, R.string.add_favorite, R.string.fa_heart) {
                series?.let { viewModel.setFavorite(it.id, !(it.data.userData?.isFavorite ?: false), null) }
            }
        private val trailerHolder =
            DetailButtons.trailers(context) { TrailerService.onClick(requireContext(), it, viewModel::navigateTo) }
        private val watchedButton = DetailButtons.fa(context, R.string.mark_watched, R.string.fa_eye_slash) { confirmWatched() }
        private val randomButton =
            DetailButtons.fa(context, R.string.sort_by_random, R.string.fa_dice, collapsed = true) { viewModel.onClickRandomEpisode() }
        private val shuffleButton =
            DetailButtons.fa(context, R.string.shuffle, R.string.fa_shuffle, collapsed = true) {
                series?.let { viewModel.navigateTo(Destination.PlaybackList(itemId = it.id, shuffle = true)) }
            }
        private val deleteButton =
            DetailButtons.delete(context, title = { series?.title ?: "" }) { series?.let { contextActions.onDeleteItem.invoke(it) } }

        private val kidButtons: KidTitleButtonsView? =
            if (kid) {
                KidTitleButtonsView(
                    context,
                    accent = accentAtStart,
                    onPlay = { viewModel.navigateTo(Destination.Playback(it.itemId, it.positionMs)) },
                    onAction = { action ->
                        val s = series ?: return@KidTitleButtonsView
                        when (action) {
                            KidAction.WATCHLIST -> {
                                viewModel.setFavorite(s.id, !(s.data.userData?.isFavorite ?: false), null)
                            }

                            KidAction.SHUFFLE -> {
                                viewModel.navigateTo(Destination.PlaybackList(itemId = s.id, shuffle = true))
                            }

                            KidAction.START_OVER -> {}
                        }
                    },
                )
            } else {
                null
            }

        private val headerBlock =
            verticalBlock(context).apply {
                content.headerPadding(this)
                addView(fitted, LinearLayout.LayoutParams(MATCH, WRAP))
                buttons?.let { row ->
                    row.provideButtonsOverArtwork()
                    listOf(
                        favoriteButton,
                        trailerHolder.button,
                        watchedButton,
                        randomButton,
                        shuffleButton,
                        deleteButton,
                    ).forEach(row::addButton)
                    row.firstFocus = favoriteButton
                    // `padding(start = startPadding + 16, end = 16)` inside a row bled 16dp to the start, 16dp below
                    row.strip.setPaddingRelative(
                        context.dp(HeaderDimens.START_PADDING + BUTTON_GLOW_ROOM_DP),
                        0,
                        context.dp(BUTTON_GLOW_ROOM_DP),
                        0,
                    )
                    row.setPaddingRelative(0, 0, 0, context.dp(16))
                    row.reportDetailActionHeight()
                    addView(
                        row,
                        LinearLayout.LayoutParams(MATCH, WRAP).apply { marginStart = -context.dp(BUTTON_GLOW_ROOM_DP) },
                    )
                }
                kidButtons?.let { addView(it, LinearLayout.LayoutParams(MATCH, WRAP)) }
            }

        private val seasonsRow = detailCardRow(context, SEASONS_ROW)
        private val peopleRow = detailCardRow(context, PEOPLE_ROW)
        private val extrasRow = detailCardRow(context, EXTRAS_ROW)
        private val similarRow = detailCardRow(context, SIMILAR_ROW)
        private val discoverRow = detailCardRow(context)
        private val seasonBinder = SeasonRowBinder(pageStyle, CardDimens.HEIGHT_2X3_DP.toFloat())
        private val seasonsAdapter =
            CardGridAdapter(
                seasonBinder.presenter,
                CardCallbacks(
                    onClick = { _, item -> viewModel.navigateTo(item.destination()) },
                    onLongClick = { _, season ->
                        showMenu(
                            ContextMenu.ForBaseItem(
                                fromLongClick = true,
                                item = season,
                                chosenStreams = null,
                                showGoTo = true,
                                showStreamChoices = false,
                                canDelete = viewModel.canDelete(season, preferences.appPreferences),
                                canRemoveContinueWatching = false,
                                canRemoveNextUp = false,
                                actions = contextActions,
                            ),
                        )
                    },
                ),
            )
        private val similarBinder = SeasonRowBinder(pageStyle, CardDimens.HEIGHT_2X3_DP.toFloat())
        private val similarAdapter =
            CardRowAdapter(
                similarBinder.presenter,
                CardCallbacks(
                    onClick = { _, item -> viewModel.navigateTo(item.destination()) },
                    onLongClick = { _, item ->
                        showMenu(
                            ContextMenu.ForBaseItem(
                                fromLongClick = true,
                                item = item,
                                chosenStreams = null,
                                showGoTo = true,
                                showStreamChoices = false,
                                canDelete = false,
                                canRemoveContinueWatching = false,
                                canRemoveNextUp = false,
                                actions = contextActions,
                            ),
                        )
                    },
                ),
            )
        private val discoverBinder = DiscoverRowBinder(pageStyle) { _, item -> viewModel.navigateTo(item.destination) }

        fun firstFocus(): View? = buttons?.restoreTarget ?: kidButtons?.firstFocusView

        private fun showMenu(menu: ContextMenu) = showContextMenu(menu, kid, viewModel.streamChoiceService::chooseSource, null)

        fun bind(
            series: BaseItem,
            state: SeriesState,
            preferences: UserPreferences,
        ) {
            this.series = series
            val accent = pageStyle.accentFor(series)
            content.viewport.provideNeonAccent(accent)
            val favorite = series.data.userData?.isFavorite ?: false
            val played = series.data.userData?.played ?: false
            header.bind(series, state.seasons.size, preferences) { showOverviewDialog(ItemDetailsDialogInfo(series), false) }
            if (buttons != null) {
                DetailButtons.updateFa(
                    favoriteButton,
                    getString(if (favorite) R.string.remove_favorite else R.string.add_favorite),
                    R.string.fa_heart,
                    if (favorite) FAVORITE_RED else null,
                )
                trailerHolder.bind(state.trailers)
                DetailButtons.updateFa(
                    watchedButton,
                    getString(if (played) R.string.mark_unwatched else R.string.mark_watched),
                    if (played) R.string.fa_eye else R.string.fa_eye_slash,
                )
                if (!state.canDeleteSeries && deleteButton.hasFocus()) favoriteButton.requestFocus()
                deleteButton.visibility = if (state.canDeleteSeries) View.VISIBLE else View.GONE
            }
            kidButtons?.bind(state.kidPlay, KidTitlePage.seriesActions, favorite)

            val rows = mutableListOf(DetailRowSpec("header", { headerBlock }))
            rows +=
                DetailRowSpec("seasons", { seasonsRow }) {
                    seasonBinder.update(pageStyle, CardDimens.HEIGHT_2X3_DP.toFloat())
                    bindSeasons(state.seasons, accent)
                }
            watchPager("seasons", state.seasons) { bindSeasons(viewModel.state.value.seasons, pageStyle.accentFor(series)) }
            // Kids get only Seasons and More like this (owner 2026-10-23)
            if (state.people.isNotEmpty() && !kid) {
                rows +=
                    DetailRowSpec("people", { peopleRow }) {
                        peopleRow.showPeople(
                            state.people,
                            pageStyle,
                            onClick = { viewModel.navigateTo(Destination.MediaItem(it.id, BaseItemKind.PERSON)) },
                            onLongClick = { _, person ->
                                showMenu(
                                    ContextMenu.ForPerson(
                                        fromLongClick = true,
                                        person = person,
                                        actions = PersonContextActions(contextActions.navigateTo, contextActions.onClickFavorite),
                                    ),
                                )
                            },
                            titleAccent = accent,
                            style = detailPersonRowStyle(requireContext()),
                            accent = accent,
                        )
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
            if (state.similar.isNotEmpty()) {
                rows +=
                    DetailRowSpec("similar", { similarRow }) {
                        similarBinder.update(pageStyle, CardDimens.HEIGHT_2X3_DP.toFloat())
                        similarRow.setup(getString(R.string.more_like_this), accent, pageStyle, detailItemRowStyle(requireContext()))
                        similarRow.setCards(similarAdapter, state.similar)
                    }
            }
            if (state.discovered.isNotEmpty() && !kid) {
                rows += DetailRowSpec("discover", { discoverRow }) { discoverBinder.bind(discoverRow, pageStyle, accent, state.discovered) }
            }
            content.list.listAdapter.submit(rows)
        }

        private fun bindSeasons(
            seasons: List<BaseItem?>,
            accent: Int,
        ) {
            seasonsRow.showPaged(
                getString(R.string.tv_seasons) + " (${seasons.size})",
                accent,
                pageStyle,
                detailItemRowStyle(requireContext()),
                seasonsAdapter,
                seasons,
            )
        }
    }
}

/**
 * `SeriesDetailsHeader`: the title or logo (75 % wide), then in a column 60 % wide the quick details, the studios
 * (bodyLarge) and genres, and the overview (3 lines); a compact header puts studios and genres on one line. The kid
 * page shows the years aired, the number of seasons and the rating badge, the genres and up to 8 lines of the
 * description.
 */
class SeriesHeaderView(
    context: Context,
    private val kid: Boolean,
) : FractionColumn(context, 1f, 4f) {
    private val scale = HeaderDimens.detailInfoTextScale()
    private val titleColumn = FractionColumn(context, .75f)
    private val title = TitleOrLogoView(context)
    private val info = FractionColumn(context, .60f, 4f)
    private val quickDetails = QuickDetailsView(context).apply { setTextStyle(scaledRole(TextRole.TITLE_SMALL, scale)) }
    private val combined = GenreTextView(context).apply { setTextAppearanceStyle(scaledRole(TextRole.BODY_MEDIUM, scale)) }
    private val studios = GenreTextView(context).apply { setTextAppearanceStyle(scaledRole(TextRole.BODY_LARGE, scale)) }
    private val genres = GenreTextView(context).apply { setTextAppearanceStyle(scaledRole(TextRole.BODY_MEDIUM, scale)) }
    private val overview =
        OverviewTextView(context).apply {
            maxLines = if (kid) KidTitlePage.overviewLines(compact = false) else 3
            if (kid) compactMaxLines = KidTitlePage.overviewLines(compact = true)
            setTextStyle(scaledRole(TextRole.BODY_MEDIUM, scale))
        }
    private val kidLine = KidMetadataLineView(context, scaledRole(TextRole.TITLE_SMALL, scale))
    private var series: BaseItem? = null
    private var seasonCount = 0

    init {
        setPadding(0, context.dp(HeaderDimens.detailTopPadding()), 0, context.dp(8f))
        titleColumn.addPadded(title)
        addView(titleColumn, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        if (kid) {
            info.addPadded(kidLine, topDp = 4f)
            info.addPadded(genres)
        } else {
            info.addPadded(quickDetails)
            info.addPadded(combined)
            info.addPadded(studios)
            info.addPadded(genres, bottomDp = 4f)
        }
        info.addView(overview, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(info, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    var compact: Boolean = false
        set(value) {
            field = value
            title.compactHeader = value
            overview.compact = value
            combined.compact = value
            studios.compact = value
            genres.compact = value
            series?.let { applyGenres(it) }
        }

    fun bind(
        series: BaseItem,
        seasonCount: Int,
        preferences: UserPreferences,
        overviewOnClick: () -> Unit,
    ) {
        this.series = series
        this.seasonCount = seasonCount
        val dto = series.data
        title.bind(series, preferences.appPreferences.interfacePreferences.showLogos, CardImages.imageUrlService(context))
        overview.onClick = overviewOnClick
        if (kid) {
            val seasons =
                if (seasonCount >
                    0
                ) {
                    context.resources.getQuantityString(R.plurals.kid_seasons, seasonCount, seasonCount)
                } else {
                    null
                }
            kidLine.bind(listOfNotNull(dto.seriesProductionYears, seasons), dto.officialRating)
            val genreList = dto.genres.orEmpty()
            genres.visibility = if (genreList.isNotEmpty()) View.VISIBLE else View.GONE
            genres.setGenres(genreList)
            val text = dto.overview?.takeIf { it.isNotBlank() }
            overview.visibility = if (text != null) View.VISIBLE else View.GONE
            overview.setText(text)
            return
        }
        quickDetails.bind(series.ui.quickDetailsText, null, enabledToggles = enabledDisplayToggles(preferences.appPreferences))
        applyGenres(series)
        overview.visibility = if (dto.overview != null) View.VISIBLE else View.GONE
        overview.setText(dto.overview)
    }

    private fun applyGenres(series: BaseItem) {
        if (kid) return
        val dto = series.data
        if (compact) {
            combined.visibility = View.VISIBLE
            combined.setGenres(series.studioNames + dto.genres.orEmpty())
            studios.visibility = View.GONE
            genres.visibility = View.GONE
        } else {
            combined.visibility = View.GONE
            studios.visibility = if (dto.studios != null) View.VISIBLE else View.GONE
            studios.setGenres(series.studioNames)
            val genreList = dto.genres.orEmpty()
            genres.visibility = if (genreList.isNotEmpty()) View.VISIBLE else View.GONE
            genres.setGenres(genreList)
        }
    }
}
