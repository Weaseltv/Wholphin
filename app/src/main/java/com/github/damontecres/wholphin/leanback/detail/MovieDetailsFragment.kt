package com.github.damontecres.wholphin.leanback.detail

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.fragment.app.viewModels
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.ChosenStreams
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.DiscoverItem
import com.github.damontecres.wholphin.data.model.Person
import com.github.damontecres.wholphin.leanback.theme.CardDimens
import com.github.damontecres.wholphin.leanback.theme.ControlTextStyle
import com.github.damontecres.wholphin.leanback.theme.applyTextStyle
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.PageStyle
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.provideNeonAccent
import com.github.damontecres.wholphin.leanback.widget.card.CardCallbacks
import com.github.damontecres.wholphin.leanback.widget.card.CardImages
import com.github.damontecres.wholphin.leanback.widget.card.CardRowAdapter
import com.github.damontecres.wholphin.leanback.widget.card.CardRowView
import com.github.damontecres.wholphin.leanback.widget.text.FittedDetailHeaderLayout
import com.github.damontecres.wholphin.leanback.widget.text.GenreTextView
import com.github.damontecres.wholphin.leanback.widget.text.HeaderDimens
import com.github.damontecres.wholphin.leanback.widget.text.OverviewTextView
import com.github.damontecres.wholphin.leanback.widget.text.QuickDetailsView
import com.github.damontecres.wholphin.leanback.widget.text.TitleOrLogoView
import com.github.damontecres.wholphin.leanback.widget.text.enabledDisplayToggles
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.services.TrailerService
import com.github.damontecres.wholphin.ui.components.ContextMenu
import com.github.damontecres.wholphin.ui.components.ContextMenuActions
import com.github.damontecres.wholphin.ui.components.PersonContextActions
import com.github.damontecres.wholphin.ui.data.ItemDetailsDialogInfo
import com.github.damontecres.wholphin.ui.detail.KidAction
import com.github.damontecres.wholphin.ui.detail.KidTitlePage
import com.github.damontecres.wholphin.ui.detail.movie.MovieState
import com.github.damontecres.wholphin.ui.detail.movie.MovieViewModel
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.util.DataLoadingState
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.withCreationCallback
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.UserDto
import org.jellyfin.sdk.model.extensions.ticks
import org.jellyfin.sdk.model.serializer.toUUID
import kotlin.time.Duration

/** `CastRowReserve`: the space kept below the buttons for the cast row (its title, pictures, names and roles) */
internal const val CAST_ROW_RESERVE_DP = CardDimens.PERSON_CARD_WIDTH_DP + 110f

/**
 * `KidRowPeek`: the kid page keeps room only for the top of its first row (its title and the top of the posters), as in
 * the owner's mockup: the rows scroll up from below, and the header and its bigger buttons get the room.
 */
internal const val KID_ROW_PEEK_DP = 96f

private const val HEADER_ROW = 0
private const val PEOPLE_ROW = 1
private const val CHAPTER_ROW = 3
private const val EXTRAS_ROW = 4
private const val SIMILAR_ROW = 5

/**
 * The movie page (MovieDetails.kt), also used for videos and music videos: the header (title or logo, quick details,
 * genres, stream chips, tagline, overview, director) fitted above the first row, the play buttons, then People,
 * Chapters, Extras, More like this and (other flavors) Discover. The kid page (owner mockup 2026-10-23) shows a short
 * header, the kid buttons and only More like this, with no long-press or More menus.
 *
 * Focus starts on Play (the kid page's Play) and comes back to where it was; the page reloads when it comes back.
 */
@AndroidEntryPoint
class MovieDetailsFragment : DetailPageFragment() {
    private val viewModel: MovieViewModel by viewModels(
        extrasProducer = {
            defaultViewModelCreationExtras.withCreationCallback<MovieViewModel.Factory> {
                it.create(requireDestination<Destination.MediaItem>().itemId)
            }
        },
    )

    private var page: MoviePage? = null
    private var userDto: UserDto? = null
    private var themeStarted = false

    private val contextActions by lazy {
        ContextMenuActions(
            navigateTo = viewModel::navigateTo,
            onClickWatch = { id, watched -> viewModel.setWatched(id, watched) },
            onClickFavorite = { id, favorite -> viewModel.setFavorite(id, favorite) },
            onClickAddPlaylist = { showPlaylistDialog(it) },
            onSendMediaInfo = viewModel.serverReportService::sendMediaReportFor,
            onDeleteItem = viewModel::deleteItem,
            onChooseVersion = { item, source -> viewModel.savePlayVersion(item, source.id!!.toUUID()) },
            onChooseTracks = { result ->
                viewModel.saveTrackSelection(result.item, result.itemPlayback, result.trackIndex, result.streamType)
            },
            onShowOverview = { showOverviewDialog(ItemDetailsDialogInfo(it), isAdmin) },
            onClearChosenStreams = { viewModel.clearChosenStreams(it) },
        )
    }

    private val isAdmin: Boolean get() = userDto?.policy?.isAdministrator == true

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        page = null
        viewModel.serverRepository.currentUserDtoFlow.collectWhileStarted { userDto = it }
        viewModel.state.collectWhileStarted { render(it) }
    }

    override fun onDestroyView() {
        page = null
        super.onDestroyView()
    }

    override fun onPageActive() {
        viewModel.onResume()
        if (viewModel.state.value.loading is DataLoadingState.Success) startTheme()
    }

    override fun onPageInactive() {
        if (themeStarted) {
            themeStarted = false
            viewModel.release()
        }
    }

    private fun startTheme() {
        themeStarted = true
        viewModel.maybePlayThemeSong(requireDestination<Destination.MediaItem>().itemId)
    }

    override fun onPageStyleChanged(style: PageStyle) {
        render(viewModel.state.value)
    }

    override fun onScreenFocus(restoring: Boolean) {
        if (!restoring) page?.firstFocus()?.let { if (it.isAttachedToWindow && it.requestFocus()) return }
        super.onScreenFocus(restoring)
    }

    private fun render(state: MovieState) {
        when (val s = state.loading) {
            is DataLoadingState.Error -> {
                showError(s)
            }

            DataLoadingState.Loading, DataLoadingState.Pending -> {
                showLoading()
            }

            is DataLoadingState.Success -> {
                var created: MoviePage? = null
                val first =
                    showContent {
                        MoviePage(requireContext(), viewModel.kidPage, pageStyle.accentFor(s.data)).also {
                            created = it
                            page = it
                        }.content.viewport
                    }
                val p = page ?: return
                p.bind(s.data, state, preferences)
                if (first) {
                    if (isPageActive) startTheme()
                    created?.let { c -> c.content.afterLayout { c.firstFocus()?.let(::requestInitialFocus) } }
                }
            }
        }
    }

    /** The views of the movie page, built once */
    private inner class MoviePage(
        context: Context,
        val kid: Boolean,
        kidAccent: Int,
    ) {
        val content = DetailPageContent(context, spacingDp = 16f, bottomPaddingDp = 8f)
        private var movie: BaseItem? = null
        private var state: MovieState? = null

        val header = MovieHeaderView(context, kid)
        private val fitted =
            FittedDetailHeaderLayout(context, firstRowHeightDp = if (kid) KID_ROW_PEEK_DP else CAST_ROW_RESERVE_DP).apply {
                addView(header)
                onCompactChanged = { header.compact = it }
            }
        private val buttons: ExpandablePlayButtonsView? =
            if (kid) {
                null
            } else {
                ExpandablePlayButtonsView(
                    context,
                    PlayButtonsActions(
                        playOnClick = { position -> movie?.let { viewModel.navigateTo(Destination.Playback(it.id, position.inWholeMilliseconds)) } },
                        watchOnClick = { movie?.let { viewModel.setWatched(it.id, !it.played) } },
                        favoriteOnClick = { movie?.let { viewModel.setFavorite(it.id, !it.favorite) } },
                        moreOnClick = { showMore() },
                        trailerOnClick = { TrailerService.onClick(requireContext(), it, viewModel::navigateTo) },
                        onConfirmDelete = { viewModel.state.value.movie?.let { viewModel.deleteItem(it) } },
                        onChooseVersion = { source -> movie?.let { contextActions.onChooseVersion.invoke(it, source) } },
                    ),
                    collapseMore = true,
                    bottomPaddingDp = 16f,
                )
            }
        private val kidButtons: KidTitleButtonsView? =
            if (kid) {
                KidTitleButtonsView(
                    context,
                    accent = kidAccent,
                    onPlay = { play -> movie?.let { viewModel.navigateTo(Destination.Playback(it.id, play.positionMs)) } },
                    onAction = { action ->
                        val m = movie ?: return@KidTitleButtonsView
                        when (action) {
                            KidAction.START_OVER -> viewModel.navigateTo(Destination.Playback(m.id, 0L))
                            KidAction.WATCHLIST -> viewModel.setFavorite(m.id, !m.favorite)
                            KidAction.SHUFFLE -> {}
                        }
                    },
                )
            } else {
                null
            }
        private val headerBlock =
            verticalBlock(context).apply {
                content.headerPadding(this)
                addView(fitted, android.widget.LinearLayout.LayoutParams(MATCH, WRAP))
                buttons?.let { addView(it, it.bledLayoutParams()) }
                kidButtons?.let { addView(it, android.widget.LinearLayout.LayoutParams(MATCH, WRAP)) }
            }

        private val peopleRow = detailCardRow(context, PEOPLE_ROW)
        private val chapterRow = detailCardRow(context, CHAPTER_ROW)
        private val extrasRow = detailCardRow(context, EXTRAS_ROW)
        private val similarRow = detailCardRow(context, SIMILAR_ROW)
        private val discoverRow = detailCardRow(context)
        private val similarBinder = SeasonRowBinder(pageStyle, CardDimens.HEIGHT_2X3_DP.toFloat())
        private val similarAdapter =
            CardRowAdapter(
                similarBinder.presenter,
                CardCallbacks(
                    onClick = { _, item -> viewModel.navigateTo(item.destination()) },
                    onLongClick = { _, item -> showSimilarMenu(item) },
                ),
            )
        private val discoverBinder = DiscoverRowBinder(pageStyle) { _, item -> viewModel.navigateTo(item.destination) }

        fun firstFocus(): View? = buttons?.restoreTarget ?: kidButtons?.firstFocusView

        fun bind(
            movie: BaseItem,
            state: MovieState,
            preferences: UserPreferences,
        ) {
            this.movie = movie
            this.state = state
            val accent = pageStyle.accentFor(movie)
            content.viewport.provideNeonAccent(accent)
            header.bind(movie, state.chosenStreams, preferences) { showOverviewDialog(ItemDetailsDialogInfo(movie), isAdmin) }
            buttons?.bind(
                PlayButtonsState(
                    title = movie.title ?: "",
                    resumePosition = movie.data.userData?.playbackPositionTicks?.ticks ?: Duration.ZERO,
                    watched = movie.data.userData?.played ?: false,
                    favorite = movie.data.userData?.isFavorite ?: false,
                    canDelete = state.canDelete,
                    trailers = state.trailers,
                    mediaSources = movie.data.mediaSources.orEmpty(),
                    chosenStreams = state.chosenStreams,
                ),
            )
            kidButtons?.let {
                val play = KidTitlePage.moviePlay(movie.data)
                it.bind(play, KidTitlePage.movieActions(play), movie.data.userData?.isFavorite ?: false)
            }
            val rows = mutableListOf(DetailRowSpec("header", { headerBlock }))
            // Kids get only More like this (owner 2026-10-23)
            if (!kid) {
                if (state.people.isNotEmpty()) {
                    rows += DetailRowSpec("people", { peopleRow }) { bindPeople(state.people, accent) }
                }
                if (state.chapters.isNotEmpty()) {
                    rows +=
                        DetailRowSpec("chapters", { chapterRow }) {
                            chapterRow.showChapters(
                                state.chapters,
                                pageStyle,
                                onClick = { viewModel.navigateTo(Destination.Playback(movie.id, it.position.inWholeMilliseconds)) },
                                onLongClick = {},
                                titleAccent = accent,
                                style = detailChapterRowStyle(requireContext()),
                                accent = accent,
                            )
                        }
                }
                if (state.extras.isNotEmpty()) {
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
            }
            if (state.similar.isNotEmpty()) {
                rows +=
                    DetailRowSpec("similar", { similarRow }) {
                        similarBinder.update(pageStyle, similarImageHeightDp(movie))
                        similarRow.setup(getString(R.string.more_like_this), accent, pageStyle, detailItemRowStyle(requireContext()))
                        similarRow.setCards(similarAdapter, state.similar)
                    }
            }
            if (!kid && state.discovered.isNotEmpty()) {
                rows += DetailRowSpec("discover", { discoverRow }) { discoverBinder.bind(discoverRow, pageStyle, accent, state.discovered) }
            }
            content.list.listAdapter.submit(rows)
        }

        private fun bindPeople(
            people: List<Person>,
            accent: Int,
        ) {
            peopleRow.showPeople(
                people,
                pageStyle,
                onClick = { viewModel.navigateTo(Destination.MediaItem(it.id, BaseItemKind.PERSON)) },
                onLongClick = { _, person ->
                    showContextMenu(
                        ContextMenu.ForPerson(
                            fromLongClick = true,
                            person = person,
                            actions = PersonContextActions(navigateTo = viewModel::navigateTo, onClickFavorite = { id, f -> viewModel.setFavorite(id, f) }),
                        ),
                        kid,
                        null,
                        null,
                    )
                },
                titleAccent = accent,
                style = detailPersonRowStyle(requireContext()),
                accent = accent,
            )
        }

        private fun showMore() {
            val m = movie ?: return
            val s = viewModel.state.value
            showContextMenu(
                ContextMenu.ForBaseItem(
                    fromLongClick = false,
                    item = m,
                    chosenStreams = s.chosenStreams,
                    showGoTo = false,
                    showStreamChoices = true,
                    canDelete = s.canDelete,
                    canRemoveContinueWatching = false,
                    canRemoveNextUp = false,
                    actions = contextActions,
                ),
                kid,
                viewModel.streamChoiceService::chooseSource,
                userDto?.configuration?.subtitleLanguagePreference,
            )
        }

        private fun showSimilarMenu(item: BaseItem) {
            showContextMenu(
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
                kid,
                viewModel.streamChoiceService::chooseSource,
                userDto?.configuration?.subtitleLanguagePreference,
            )
        }
    }
}

/**
 * `MovieDetailsHeader`: the title or logo (75 % wide), then in a column 60 % wide the quick details, genres, stream
 * chips, tagline, overview (3 lines) and director, with the WeaselTV theme's 20 % larger text. The kid page shows the
 * year, runtime and rating badge, the genres and up to 8 lines of the description instead.
 */
class MovieHeaderView(
    context: Context,
    private val kid: Boolean,
) : FractionColumn(context, 1f, 4f) {
    private val scale = HeaderDimens.detailInfoTextScale()
    private val titleColumn = FractionColumn(context, .75f)
    private val title = TitleOrLogoView(context)
    private val info = FractionColumn(context, .60f, 4f)
    private val quickDetails = QuickDetailsView(context).apply { setTextStyle(scaledRole(TextRole.TITLE_SMALL, scale)) }
    private val genres = GenreTextView(context).apply { setTextAppearanceStyle(scaledRole(TextRole.BODY_MEDIUM, scale)) }
    private val streams = StreamDetailsView(context, 12f * scale)
    private val tagline = taglineView(context, scale)
    private val overview =
        OverviewTextView(context).apply {
            maxLines = if (kid) KidTitlePage.overviewLines(compact = false) else 3
            if (kid) compactMaxLines = KidTitlePage.overviewLines(compact = true)
            setTextStyle(scaledRole(TextRole.BODY_MEDIUM, scale))
        }
    private val director = detailText(context, scaledRole(TextRole.BODY_MEDIUM, scale))
    private val kidLine = KidMetadataLineView(context, scaledRole(TextRole.TITLE_SMALL, scale))

    init {
        setPadding(0, context.dp(HeaderDimens.detailTopPadding()), 0, context.dp(8f))
        titleColumn.addPadded(title)
        addView(titleColumn, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        if (kid) {
            info.addPadded(kidLine, topDp = 4f)
            info.addPadded(genres)
            info.addView(overview, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        } else {
            info.addPadded(quickDetails)
            info.addPadded(genres)
            info.addPadded(streams, topDp = 4f, bottomDp = 16f)
            info.addPadded(tagline)
            info.addView(overview, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            info.addPadded(director)
        }
        addView(info, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    /** The header is compact (`LocalCompactDetailHeader`) */
    var compact: Boolean = false
        set(value) {
            field = value
            title.compactHeader = value
            overview.compact = value
            genres.compact = value
        }

    fun bind(
        movie: BaseItem,
        chosenStreams: ChosenStreams?,
        preferences: UserPreferences,
        overviewOnClick: () -> Unit,
    ) {
        val dto = movie.data
        title.bind(movie, preferences.appPreferences.interfacePreferences.showLogos, CardImages.imageUrlService(context))
        val genreList = dto.genres.orEmpty()
        genres.visibility = if (genreList.isNotEmpty()) View.VISIBLE else View.GONE
        genres.setGenres(genreList)
        overview.onClick = overviewOnClick
        if (kid) {
            kidLine.bind(
                listOfNotNull(dto.productionYear?.toString(), kidRuntime(context, dto.runTimeTicks)),
                dto.officialRating,
            )
            val text = dto.overview?.takeIf { it.isNotBlank() }
            overview.visibility = if (text != null) View.VISIBLE else View.GONE
            overview.setText(text)
            return
        }
        quickDetails.bind(movie.ui.quickDetailsText, movie.timeRemainingOrRuntime, enabledToggles = enabledDisplayToggles(preferences.appPreferences))
        streams.bind(chosenStreams, dto.mediaSourceCount ?: 0)
        val taglineText = dto.taglines?.firstOrNull()
        tagline.visibility = if (taglineText != null) View.VISIBLE else View.GONE
        tagline.text = taglineText
        overview.visibility = if (dto.overview != null) View.VISIBLE else View.GONE
        overview.setText(dto.overview)
        val directors = directorNames(dto.people)
        director.visibility = if (directors != null) View.VISIBLE else View.GONE
        director.text = directors?.let { context.getString(R.string.directed_by, it) }
    }
}

/** Apply a text style to a [GenreTextView] (`GenreText(textStyle = …)`) */
internal fun GenreTextView.setTextAppearanceStyle(style: ControlTextStyle) = applyTextStyle(style)
