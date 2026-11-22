package com.github.damontecres.wholphin.leanback.search

import com.github.damontecres.wholphin.util.successValue
import com.github.damontecres.wholphin.services.jellyfinId
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import com.github.damontecres.wholphin.data.model.SeerrAvailability
import com.github.damontecres.wholphin.data.model.SeerrPermission
import com.github.damontecres.wholphin.data.model.Trailer
import com.github.damontecres.wholphin.data.model.hasPermission
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.services.SeerrUserConfig
import com.github.damontecres.wholphin.ui.detail.discover.DiscoverMovieState
import com.github.damontecres.wholphin.ui.detail.discover.DiscoverMovieViewModel
import com.github.damontecres.wholphin.ui.detail.discover.DiscoverSeriesState
import com.github.damontecres.wholphin.ui.detail.discover.DiscoverSeriesViewModel
import com.github.damontecres.wholphin.ui.formatDuration
import com.github.damontecres.wholphin.ui.isNotNullOrBlank
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.roundMinutes
import com.github.damontecres.wholphin.util.DataLoadingState
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.withCreationCallback
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.Locale
import kotlin.time.Duration.Companion.minutes

/**
 * Requests movie details (`DiscoverMovieDetails`, no-Compose UI): the movie's header (release date, runtime,
 * certification, ratings, genres, tagline, overview, director), the request buttons, People, More like this and
 * Recommended. The page loads again whenever it is shown again (`ResumeReload`); Request opens [RequestMovieDialog].
 */
@AndroidEntryPoint
class DiscoverMovieFragment : DiscoverDetailsFragment() {
    override val isMovie: Boolean = true

    private val viewModel: DiscoverMovieViewModel by viewModels(
        extrasProducer = {
            defaultViewModelCreationExtras.withCreationCallback<DiscoverMovieViewModel.Factory> {
                it.create(requireDestination<Destination.DiscoveredItem>().item)
            }
        },
    )
    private var userConfig: SeerrUserConfig? = null
    private var request4kEnabled = false
    private var requestDialog: RequestMovieDialog? = null

    override fun navigateTo(destination: Destination) = viewModel.navigateTo(destination)

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.userConfig.collectWhileStarted {
            userConfig = it
            render(viewModel.state.value)
        }
        viewModel.request4kEnabled.collectWhileStarted {
            request4kEnabled = it
            render(viewModel.state.value)
        }
        viewModel.state.collectWhileStarted { render(it) }
    }

    override fun onResume() {
        super.onResume()
        if (!isHidden) viewModel.onResume()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        // Shown again after the page above it closed: the old page resumed (and reloaded)
        if (!hidden && isResumed) viewModel.onResume()
    }

    override fun onDestroyView() {
        requestDialog?.dismiss()
        requestDialog = null
        super.onDestroyView()
    }

    private fun render(state: DiscoverMovieState) {
        if (view == null) return
        when (val st = state.movie) {
            is DataLoadingState.Error -> {
                showError(st)
            }

            DataLoadingState.Loading, DataLoadingState.Pending -> {
                showLoading()
            }

            is DataLoadingState.Success -> {
                val movie = st.data
                ensurePage()
                val parts =
                    buildList {
                        movie.releaseDate?.let(::add)
                        movie.runtime
                            ?.toDouble()
                            ?.minutes
                            ?.roundMinutes
                            ?.let { add(resources.formatDuration(it)) }
                        val release =
                            movie.releases?.results?.firstOrNull { it.iso31661 == Locale.getDefault().country }
                                ?: movie.releases?.results?.firstOrNull { it.iso31661 == Locale.US.country }
                                ?: movie.releases?.results?.firstOrNull()
                        release
                            ?.releaseDates
                            ?.firstOrNull()
                            ?.certification
                            ?.takeIf { it.isNotNullOrBlank() }
                            ?.let(::add)
                    }
                val genres = movie.genres?.mapNotNull { it.name }.orEmpty()
                val director =
                    movie.credits
                        ?.crew
                        ?.filter { it.job == "Director" && it.name.isNotNullOrBlank() }
                        ?.joinToString(", ") { it.name!! }
                        ?.takeIf { it.isNotNullOrBlank() }
                header.bind(
                    title = movie.title,
                    logoUrl = logoUrl(movie.mediaInfo?.jellyfinId),
                    showLogo = preferences.appPreferences.interfacePreferences.showLogos,
                    details = ratingsLine(parts, state.rating),
                    genreNames = genres,
                    taglineText = movie.tagline?.takeIf { it.isNotNullOrBlank() },
                    overviewText = movie.overview,
                    directorName = director,
                    onOverviewClick = { showOverview(movie.title, movie.overview, genres) },
                )
                buttons.bind(
                    DiscoverButtonsState(
                        tmdbId = movie.id,
                        mediaType = "movie",
                        availability = SeerrAvailability.from(movie.mediaInfo?.status) ?: SeerrAvailability.UNKNOWN,
                        canRequest = userConfig.hasPermission(SeerrPermission.REQUEST),
                        canCancel = state.canCancelRequest,
                        trailers = state.trailers,
                    ),
                )
                bindRows(state.people, state.similar, state.recommended)
                requestDialog?.update(state.profileLoading, state.requestData, request4kEnabled)
            }
        }
    }

    override fun buttonActions(): DiscoverButtonsRow.Actions =
        object : DiscoverButtonsRow.Actions {
            override fun request() {
                val movie = viewModel.state.value.movie.successValue ?: return
                viewModel.requestOnClick()
                requestDialog?.dismiss()
                requestDialog =
                    RequestMovieDialog(
                        requireContext(),
                        opener = buttons,
                        movie = movie,
                        onSubmit = {
                            viewModel.request(it)
                            requestDialog?.dismiss()
                            requestDialog = null
                        },
                        onSendLogs = errorViewModel::sendLogs,
                    ).apply {
                        onDismissRequest = { requestDialog = null }
                        show()
                    }
                val state = viewModel.state.value
                requestDialog?.update(state.profileLoading, state.requestData, request4kEnabled)
            }

            override fun cancel() {
                val movie = viewModel.state.value.movie.successValue ?: return
                movie.mediaInfo
                    ?.requests
                    ?.filter { it.requestedBy?.id == userConfig?.id && it.status == 1 }
                    ?.let { own -> own.firstOrNull { it.is4k != true } ?: own.firstOrNull() }
                    ?.let { viewModel.cancelRequest(it.id) }
            }

            override fun goTo() {
                viewModel.goTo(viewModel.state.value.movie.successValue?.mediaInfo, BaseItemKind.MOVIE)
            }

            override fun playTrailer(trailer: Trailer) = this@DiscoverMovieFragment.playTrailer(trailer)

            override fun openRequest(
                requestId: Int,
                scope: String?,
            ) = requestsViewModel.open(requestId, scope)

            override fun showQueue(requestId: Int?) = this@DiscoverMovieFragment.showQueue(requestId)
        }
}

/**
 * Requests show details (`DiscoverSeriesDetails`, no-Compose UI): the show's header (first air date, episode runtime,
 * ratings, genres, overview), the request buttons, People, More like this and Recommended. Request opens
 * [RequestSeasonsDialog], which closes once the request is sent.
 */
@AndroidEntryPoint
class DiscoverSeriesFragment : DiscoverDetailsFragment() {
    override val isMovie: Boolean = false

    private val viewModel: DiscoverSeriesViewModel by viewModels(
        extrasProducer = {
            defaultViewModelCreationExtras.withCreationCallback<DiscoverSeriesViewModel.Factory> {
                it.create(requireDestination<Destination.DiscoveredItem>().item)
            }
        },
    )
    private var userConfig: SeerrUserConfig? = null
    private var requestDialog: RequestSeasonsDialog? = null

    override fun navigateTo(destination: Destination) = viewModel.navigateTo(destination)

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.userConfig.collectWhileStarted {
            userConfig = it
            render(viewModel.state.value)
        }
        viewModel.request4kEnabled.collectWhileStarted { render(viewModel.state.value) }
        viewModel.state.collectWhileStarted { render(it) }
    }

    override fun onDestroyView() {
        requestDialog?.dismiss()
        requestDialog = null
        super.onDestroyView()
    }

    private fun render(state: DiscoverSeriesState) {
        if (view == null) return
        when (val st = state.tvSeries) {
            is DataLoadingState.Error -> {
                showError(st)
            }

            DataLoadingState.Loading, DataLoadingState.Pending -> {
                showLoading()
            }

            is DataLoadingState.Success -> {
                val series = st.data
                ensurePage()
                val parts =
                    buildList {
                        series.firstAirDate?.let(::add)
                        series.episodeRunTime
                            ?.average()
                            ?.takeIf { !it.isNaN() && it > 0 }
                            ?.minutes
                            ?.roundMinutes
                            ?.let { add(resources.formatDuration(it)) }
                    }
                val genres = series.genres?.mapNotNull { it.name }.orEmpty()
                header.bind(
                    title = series.name,
                    logoUrl = logoUrl(series.mediaInfo?.jellyfinId),
                    showLogo = preferences.appPreferences.interfacePreferences.showLogos,
                    details = ratingsLine(parts, state.rating),
                    genreNames = genres,
                    taglineText = null,
                    overviewText = series.overview,
                    directorName = null,
                    onOverviewClick = { showOverview(series.name, series.overview, genres) },
                )
                // The show's genres have 4dp more below them
                (header.genres.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin = requireContext().dp(4)
                buttons.bind(
                    DiscoverButtonsState(
                        tmdbId = series.id,
                        mediaType = "tv",
                        availability = SeerrAvailability.from(series.mediaInfo?.status) ?: SeerrAvailability.UNKNOWN,
                        canRequest = userConfig.hasPermission(SeerrPermission.REQUEST),
                        canCancel = state.canCancelRequest,
                        trailers = state.trailers,
                    ),
                )
                bindRows(state.people, state.similar, state.recommended)
                updateDialog(state)
            }
        }
    }

    private fun updateDialog(state: DiscoverSeriesState) {
        requestDialog?.update(
            loading = state.profileLoading,
            data = state.requestData,
            seasons = state.seasons,
            seasons4k = state.seasons4k,
            request4kEnabled = viewModel.request4kEnabled.value,
            submitting = state.requestSubmitting,
            errorMessage = state.requestError,
        )
    }

    override fun buttonActions(): DiscoverButtonsRow.Actions =
        object : DiscoverButtonsRow.Actions {
            override fun request() {
                viewModel.requestOnClick()
                val state = viewModel.state.value
                requestDialog?.dismiss()
                requestDialog =
                    RequestSeasonsDialog(
                        requireContext(),
                        opener = buttons,
                        id = state.tvSeries.successValue?.id ?: -1,
                        title = state.tvSeries.successValue?.name ?: "",
                        onSubmit = { request ->
                            viewModel.request(
                                request,
                                onSuccess = {
                                    requestDialog?.dismiss()
                                    requestDialog = null
                                },
                            )
                        },
                        onSendLogs = errorViewModel::sendLogs,
                    ).apply {
                        onDismissRequest = { requestDialog = null }
                        show()
                    }
                updateDialog(viewModel.state.value)
            }

            override fun cancel() {
                val series = viewModel.state.value.tvSeries.successValue ?: return
                series.mediaInfo
                    ?.requests
                    ?.filter { it.requestedBy?.id == userConfig?.id && it.status == 1 }
                    ?.let { own -> own.firstOrNull { it.is4k != true } ?: own.firstOrNull() }
                    ?.let { viewModel.cancelRequest(it.id) }
            }

            override fun goTo() {
                viewModel.goTo(viewModel.state.value.tvSeries.successValue?.mediaInfo, BaseItemKind.SERIES)
            }

            override fun playTrailer(trailer: Trailer) = this@DiscoverSeriesFragment.playTrailer(trailer)

            override fun openRequest(
                requestId: Int,
                scope: String?,
            ) = requestsViewModel.open(requestId, scope)

            override fun showQueue(requestId: Int?) = this@DiscoverSeriesFragment.showQueue(requestId)
        }
}
