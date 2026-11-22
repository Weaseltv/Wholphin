package com.github.damontecres.wholphin.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.ApprovedHomeLayout
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.DiscoverItem
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.services.FavoriteWatchManager
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.SeerrService
import com.github.damontecres.wholphin.ui.AspectRatios
import com.github.damontecres.wholphin.ui.Cards
import com.github.damontecres.wholphin.ui.ItemRowFields
import com.github.damontecres.wholphin.ui.LocalImageUrlService
import com.github.damontecres.wholphin.ui.PreviewTvSpec
import com.github.damontecres.wholphin.ui.cards.SeasonCard
import com.github.damontecres.wholphin.ui.components.DetailPageViewport
import com.github.damontecres.wholphin.ui.components.ErrorMessage
import com.github.damontecres.wholphin.ui.components.ExpandableFaButton
import com.github.damontecres.wholphin.ui.components.FittedDetailHeader
import com.github.damontecres.wholphin.ui.components.LoadingPage
import com.github.damontecres.wholphin.ui.components.LoadingRow
import com.github.damontecres.wholphin.ui.components.LocalCompactDetailHeader
import com.github.damontecres.wholphin.ui.components.OverviewText
import com.github.damontecres.wholphin.ui.components.reportDetailRowHeight
import com.github.damontecres.wholphin.ui.data.ItemDetailsDialog
import com.github.damontecres.wholphin.ui.data.ItemDetailsDialogInfo
import com.github.damontecres.wholphin.ui.data.RowColumn
import com.github.damontecres.wholphin.ui.discover.DiscoverRow
import com.github.damontecres.wholphin.ui.discover.DiscoverRowData
import com.github.damontecres.wholphin.ui.formatDate
import com.github.damontecres.wholphin.ui.ifElse
import com.github.damontecres.wholphin.ui.isNotNullOrBlank
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.rememberPosition
import com.github.damontecres.wholphin.ui.theme.WholphinTheme
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.ui.util.ResStringProvider
import com.github.damontecres.wholphin.util.ApiRequestPager
import com.github.damontecres.wholphin.util.DataLoadingState
import com.github.damontecres.wholphin.util.DiscoverRequestType
import com.github.damontecres.wholphin.util.GetItemsRequestHandler
import com.github.damontecres.wholphin.util.RowLoadingState
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import timber.log.Timber
import java.time.LocalDate
import java.util.UUID

/*
 * The person page's view model and state, moved out of the Compose file PersonPage.kt (no-Compose UI, 2026-11-22).
 */

@HiltViewModel(assistedFactory = PersonViewModel.Factory::class)
class PersonViewModel
    @AssistedInject
    constructor(
        private val api: ApiClient,
        val navigationManager: NavigationManager,
        private val favoriteWatchManager: FavoriteWatchManager,
        private val seerrService: SeerrService,
        @Assisted val itemId: UUID,
    ) : ViewModel() {
        @AssistedFactory
        interface Factory {
            fun create(itemId: UUID): PersonViewModel
        }

        private val _state = MutableStateFlow(PersonState())
        val state: StateFlow<PersonState> = _state

        private suspend fun updatePerson(): BaseItem {
            val person =
                api.userLibraryApi
                    .getItem(itemId)
                    .content
                    .let { BaseItem(it) }
            _state.update {
                it.copy(
                    person = DataLoadingState.Success(person),
                )
            }
            return person
        }

        init {
            viewModelScope.launchIO {
                try {
                    val person = updatePerson()
                    val dto = person.data
                    if ((dto.movieCount ?: 0) > 0) {
                        fetchRow(person.id, BaseItemKind.MOVIE) { state, row ->
                            state.copy(movies = row)
                        }
                    } else {
                        _state.update { it.copy(movies = RowLoadingState.Success(emptyList())) }
                    }
                    if ((dto.seriesCount ?: 0) > 0) {
                        fetchRow(person.id, BaseItemKind.SERIES) { state, row ->
                            state.copy(series = row)
                        }
                    } else {
                        _state.update { it.copy(series = RowLoadingState.Success(emptyList())) }
                    }
                    if ((dto.episodeCount ?: 0) > 0) {
                        fetchRow(person.id, BaseItemKind.EPISODE) { state, row ->
                            state.copy(episodes = row)
                        }
                    } else {
                        _state.update { it.copy(episodes = RowLoadingState.Success(emptyList())) }
                    }
                    viewModelScope.launchIO {
                        // WeaselPlex has no Requests row on person pages
                        val results = if (BuildConfig.FLAVOR == "weaselfin") emptyList() else seerrService.similar(person).orEmpty()
                        _state.update {
                            it.copy(
                                discovered = results,
                                discoverPerson = seerrService.discoverPerson(person),
                            )
                        }
                    }
                } catch (ex: Exception) {
                    Timber.e(ex, "Error fetching person %s", itemId)
                    _state.update { it.copy(person = DataLoadingState.Error(ex)) }
                }
            }
        }

        private fun fetchRow(
            itemId: UUID,
            type: BaseItemKind,
            update: (PersonState, RowLoadingState) -> PersonState,
        ) {
            viewModelScope.launchIO {
                _state.update {
                    update.invoke(it, RowLoadingState.Loading)
                }
                try {
                    val request =
                        GetItemsRequest(
                            personIds = listOf(itemId),
                            includeItemTypes = listOf(type),
                            fields = ItemRowFields,
                            recursive = true,
                            sortBy = listOf(ItemSortBy.PREMIERE_DATE, ItemSortBy.PRODUCTION_YEAR, ItemSortBy.SORT_NAME),
                            sortOrder = listOf(SortOrder.DESCENDING, SortOrder.DESCENDING, SortOrder.ASCENDING),
                        )
                    val pager =
                        ApiRequestPager(
                            api,
                            request,
                            GetItemsRequestHandler,
                            viewModelScope,
                            pageSize = 15,
                            useSeriesForPrimary = false,
                        ).init()
                    _state.update {
                        update.invoke(it, RowLoadingState.Success(pager))
                    }
                } catch (ex: Exception) {
                    Timber.e(ex, "Error fetching $type for $itemId")
                    _state.update {
                        update.invoke(it, RowLoadingState.Error(ex))
                    }
                }
            }
        }

        fun setFavorite(favorite: Boolean) {
            viewModelScope.launchIO {
                favoriteWatchManager.setFavorite(itemId, favorite)
                updatePerson()
            }
        }
    }

data class PersonState(
    val person: DataLoadingState<BaseItem> = DataLoadingState.Pending,
    val movies: RowLoadingState = RowLoadingState.Pending,
    val series: RowLoadingState = RowLoadingState.Pending,
    val episodes: RowLoadingState = RowLoadingState.Pending,
    val discovered: List<DiscoverItem> = emptyList(),
    val discoverPerson: DiscoverItem? = null,
)

fun rowTitle(
    prefix: String,
    state: RowLoadingState,
): String =
    if (state is RowLoadingState.Success) {
        "$prefix (${state.items.size})"
    } else {
        prefix
    }
