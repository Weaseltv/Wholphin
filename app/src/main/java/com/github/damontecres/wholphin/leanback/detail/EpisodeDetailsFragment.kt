package com.github.damontecres.wholphin.leanback.detail

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.fragment.app.viewModels
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.ChosenStreams
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.leanback.theme.PageStyle
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.provideNeonAccent
import com.github.damontecres.wholphin.leanback.widget.card.CardImages
import com.github.damontecres.wholphin.leanback.widget.text.FittedDetailHeaderLayout
import com.github.damontecres.wholphin.leanback.widget.text.HeaderDimens
import com.github.damontecres.wholphin.leanback.widget.text.OverviewTextView
import com.github.damontecres.wholphin.leanback.widget.text.QuickDetailsView
import com.github.damontecres.wholphin.leanback.widget.text.TitleOrLogoView
import com.github.damontecres.wholphin.leanback.widget.text.enabledDisplayToggles
import com.github.damontecres.wholphin.leanback.widget.text.logoUrlFor
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.components.ContextMenu
import com.github.damontecres.wholphin.ui.components.ContextMenuActions
import com.github.damontecres.wholphin.ui.data.ItemDetailsDialogInfo
import com.github.damontecres.wholphin.ui.detail.KidAction
import com.github.damontecres.wholphin.ui.detail.KidTitlePage
import com.github.damontecres.wholphin.ui.detail.episode.EpisodeState
import com.github.damontecres.wholphin.ui.detail.episode.EpisodeViewModel
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.util.DataLoadingState
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.withCreationCallback
import org.jellyfin.sdk.model.api.UserDto
import org.jellyfin.sdk.model.extensions.ticks
import org.jellyfin.sdk.model.serializer.toUUID
import kotlin.time.Duration

/**
 * The episode page (EpisodeDetails.kt): the series name, episode name, quick details, stream chips, tagline, overview
 * and director, fitted above a 280dp first-row allowance (the page has no rows), and the play buttons. The kid design
 * shows the series' logo, the episode's short metadata line and description, and the kid buttons, with no More menu.
 *
 * Focus starts on Play; the page reloads when it comes back.
 */
@AndroidEntryPoint
class EpisodeDetailsFragment : DetailPageFragment() {
    private val viewModel: EpisodeViewModel by viewModels(
        extrasProducer = {
            defaultViewModelCreationExtras.withCreationCallback<EpisodeViewModel.Factory> {
                it.create(requireDestination<Destination.MediaItem>().itemId)
            }
        },
    )

    private var page: EpisodePage? = null
    private var userDto: UserDto? = null
    private var themeStarted = false

    private val isAdmin: Boolean get() = userDto?.policy?.isAdministrator == true

    private val contextActions by lazy {
        ContextMenuActions(
            navigateTo = viewModel::navigateTo,
            onClickWatch = { id, watched -> viewModel.setWatched(id, watched) },
            onClickFavorite = { id, favorite -> viewModel.setFavorite(id, favorite) },
            onClickAddPlaylist = { showPlaylistDialog(it) },
            onSendMediaInfo = viewModel.serverReportService::sendMediaReportFor,
            onDeleteItem = viewModel::deleteItem,
            onShowOverview = { showOverviewDialog(ItemDetailsDialogInfo(it), isAdmin) },
            onChooseVersion = { item, source -> viewModel.savePlayVersion(item, source.id!!.toUUID()) },
            onChooseTracks = { result ->
                viewModel.saveTrackSelection(result.item, result.itemPlayback, result.trackIndex, result.streamType)
            },
            onClearChosenStreams = { viewModel.clearChosenStreams(it) },
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
        viewModel.canDelete.collectWhileStarted { render(viewModel.state.value) }
    }

    override fun onDestroyView() {
        page = null
        super.onDestroyView()
    }

    override fun onPageActive() {
        viewModel.onResume()
        (viewModel.state.value.episode as? DataLoadingState.Success)?.let { startTheme(it.data) }
    }

    override fun onPageInactive() {
        if (themeStarted) {
            themeStarted = false
            viewModel.release()
        }
    }

    private fun startTheme(ep: BaseItem) {
        themeStarted = true
        ep.data.seriesId?.let { viewModel.maybePlayThemeSong(it) }
    }

    override fun onPageStyleChanged(style: PageStyle) {
        render(viewModel.state.value)
    }

    override fun onScreenFocus(restoring: Boolean) {
        if (!restoring) page?.firstFocus()?.let { if (it.isAttachedToWindow && it.requestFocus()) return }
        super.onScreenFocus(restoring)
    }

    private fun render(state: EpisodeState) {
        when (val st = state.episode) {
            is DataLoadingState.Error -> {
                showError(st)
            }

            DataLoadingState.Loading, DataLoadingState.Pending -> {
                showLoading()
            }

            is DataLoadingState.Success -> {
                var created: EpisodePage? = null
                val first =
                    showContent {
                        EpisodePage(requireContext(), viewModel.kidPage, pageStyle.accentFor(st.data)).also {
                            created = it
                            page = it
                        }.content.viewport
                    }
                page?.bind(st.data, state, preferences)
                if (first) {
                    if (isPageActive) startTheme(st.data)
                    created?.let { c -> c.content.afterLayout { c.firstFocus()?.let(::requestInitialFocus) } }
                }
            }
        }
    }

    private inner class EpisodePage(
        context: Context,
        val kid: Boolean,
        accentAtStart: Int,
    ) {
        val content = DetailPageContent(context, spacingDp = 16f, bottomPaddingDp = 8f)
        private var ep: BaseItem? = null
        private val header = EpisodeHeaderView(context, kid)

        // This page has no row under its buttons, so none is ever measured. Say so up front, with the same 280dp the
        // header fell back to after waiting 300 ms for one: same layout, shown sooner
        private val fitted =
            FittedDetailHeaderLayout(context, firstRowHeightDp = 280f).apply {
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
                        playOnClick = { position -> ep?.let { viewModel.navigateTo(Destination.Playback(it.id, position.inWholeMilliseconds)) } },
                        watchOnClick = { ep?.let { viewModel.setWatched(it.id, !it.played) } },
                        favoriteOnClick = { ep?.let { viewModel.setFavorite(it.id, !it.favorite) } },
                        moreOnClick = { showMore() },
                        trailerOnClick = {},
                        onConfirmDelete = { ep?.let { viewModel.deleteItem(it) } },
                        onChooseVersion = { source -> ep?.let { contextActions.onChooseVersion.invoke(it, source) } },
                    ),
                    collapseMore = false,
                    bottomPaddingDp = 16f,
                )
            }
        private val kidButtons: KidTitleButtonsView? =
            if (kid) {
                KidTitleButtonsView(
                    context,
                    accent = accentAtStart,
                    onPlay = { play -> ep?.let { viewModel.navigateTo(Destination.Playback(it.id, play.positionMs)) } },
                    onAction = { action ->
                        val e = ep ?: return@KidTitleButtonsView
                        when (action) {
                            KidAction.START_OVER -> viewModel.navigateTo(Destination.Playback(e.id, 0L))
                            KidAction.WATCHLIST -> viewModel.setFavorite(e.id, !e.favorite)
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
                addView(fitted, LinearLayout.LayoutParams(MATCH, WRAP))
                buttons?.let { addView(it, it.bledLayoutParams()) }
                kidButtons?.let { addView(it, LinearLayout.LayoutParams(MATCH, WRAP)) }
            }

        fun firstFocus(): View? = buttons?.restoreTarget ?: kidButtons?.firstFocusView

        private fun showMore() {
            val e = ep ?: return
            showContextMenu(
                ContextMenu.ForBaseItem(
                    fromLongClick = false,
                    item = e,
                    chosenStreams = viewModel.state.value.chosenStreams,
                    showGoTo = false,
                    showStreamChoices = true,
                    canDelete = viewModel.canDelete.value,
                    canRemoveContinueWatching = false,
                    canRemoveNextUp = false,
                    actions = contextActions,
                ),
                kid,
                viewModel.streamChoiceService::chooseSource,
                userDto?.configuration?.subtitleLanguagePreference,
            )
        }

        fun bind(
            ep: BaseItem,
            state: EpisodeState,
            preferences: UserPreferences,
        ) {
            this.ep = ep
            content.viewport.provideNeonAccent(pageStyle.accentFor(ep))
            header.bind(ep, state.chosenStreams, preferences, state.seriesRating) { showOverviewDialog(ItemDetailsDialogInfo(ep), isAdmin) }
            buttons?.bind(
                PlayButtonsState(
                    title = ep.title ?: "",
                    resumePosition = ep.data.userData?.playbackPositionTicks?.ticks ?: Duration.ZERO,
                    watched = ep.data.userData?.played ?: false,
                    favorite = ep.data.userData?.isFavorite ?: false,
                    canDelete = viewModel.canDelete.value,
                    trailers = null,
                    mediaSources = ep.data.mediaSources.orEmpty(),
                    chosenStreams = state.chosenStreams,
                ),
            )
            kidButtons?.let {
                val play = KidTitlePage.episodePlay(ep.data)
                it.bind(play, KidTitlePage.movieActions(play), ep.data.userData?.isFavorite ?: false)
            }
            content.list.listAdapter.submit(listOf(DetailRowSpec("header", { headerBlock })))
        }
    }
}

/**
 * `EpisodeDetailsHeader`: the series name (the series' logo or name on the kid page) and the episode name, 75 % wide,
 * then in a column 60 % wide the quick details, stream chips, tagline, overview (3 lines) and director; 8dp apart.
 */
class EpisodeHeaderView(
    context: Context,
    private val kid: Boolean,
) : FractionColumn(context, 1f, 8f) {
    private val seriesName = seriesNameView(context)
    private val logo = TitleOrLogoView(context)
    private val episodeNameText = episodeNameView(context)
    private val info = FractionColumn(context, .6f, 4f)
    private val quickDetails = QuickDetailsView(context)
    private val streams = StreamDetailsView(context)
    private val tagline = taglineView(context)
    private val overview = OverviewTextView(context).apply { maxLines = 3 }
    private val director = detailText(context, scaledRole(TextRole.BODY_MEDIUM, 1f))
    private val kidInfo = KidEpisodeInfoView(context)

    init {
        setPadding(0, context.dp(HeaderDimens.detailTopPadding()), 0, context.dp(8f))
        addView(
            FractionColumn(context, .75f).apply {
                addView(if (kid) logo else seriesName, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
        addView(
            FractionColumn(context, .75f).apply { addView(episodeNameText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)) },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
        if (kid) {
            kidInfo.fraction = .6f
            addView(kidInfo, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        } else {
            info.addView(quickDetails, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            info.addPadded(streams, startDp = 0f, bottomDp = 8f)
            info.addView(tagline, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            info.addView(overview, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            info.addView(director, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(info, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
    }

    var compact: Boolean = false
        set(value) {
            field = value
            logo.compactHeader = value
            overview.compact = value
            kidInfo.compact = value
        }

    fun bind(
        ep: BaseItem,
        chosenStreams: ChosenStreams?,
        preferences: UserPreferences,
        seriesRating: String?,
        overviewOnClick: () -> Unit,
    ) {
        val dto = ep.data
        if (kid) {
            // The series' logo (falling back to its name), as on the season/episode page
            logo.bind(dto.seriesName, logoUrlFor(ep, CardImages.imageUrlService(context)), preferences.appPreferences.interfacePreferences.showLogos)
        } else {
            seriesName.text = dto.seriesName ?: ""
        }
        episodeNameText.text = episodeName(dto)
        if (kid) {
            kidInfo.bind(dto, seriesRating)
            return
        }
        quickDetails.bind(ep.ui.quickDetailsText, ep.timeRemainingOrRuntime, enabledToggles = enabledDisplayToggles(preferences.appPreferences))
        streams.bind(chosenStreams, dto.mediaSourceCount ?: 0)
        val taglineText = dto.taglines?.firstOrNull()
        tagline.visibility = if (taglineText != null) View.VISIBLE else View.GONE
        tagline.text = taglineText
        overview.visibility = if (dto.overview != null) View.VISIBLE else View.GONE
        overview.setText(dto.overview)
        overview.onClick = overviewOnClick
        val directors = directorNames(dto.people)
        director.visibility = if (directors != null) View.VISIBLE else View.GONE
        director.text = directors?.let { context.getString(R.string.directed_by, it) }
    }
}
