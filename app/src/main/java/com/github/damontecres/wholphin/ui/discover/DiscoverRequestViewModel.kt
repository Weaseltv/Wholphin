package com.github.damontecres.wholphin.ui.discover

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.data.filter.DiscoverFilter
import com.github.damontecres.wholphin.data.filter.DiscoverFilterBy
import com.github.damontecres.wholphin.data.filter.DiscoverSort
import com.github.damontecres.wholphin.data.filter.DiscoverSortAndDirection
import com.github.damontecres.wholphin.data.filter.FilterValueOption
import com.github.damontecres.wholphin.data.filter.discoverMovieFilters
import com.github.damontecres.wholphin.data.filter.discoverTvFilters
import com.github.damontecres.wholphin.data.model.DiscoverItem
import com.github.damontecres.wholphin.services.FilterOptionCache
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.SeerrApi
import com.github.damontecres.wholphin.services.SeerrService
import com.github.damontecres.wholphin.ui.launchDefault
import com.github.damontecres.wholphin.util.DataLoadingState
import com.github.damontecres.wholphin.util.DiscoverMovieRequestHandler
import com.github.damontecres.wholphin.util.DiscoverRequestPager
import com.github.damontecres.wholphin.util.DiscoverRequestType
import com.github.damontecres.wholphin.util.DiscoverTvRequestHandler
import com.github.damontecres.wholphin.util.TrendingRequestHandler
import com.github.damontecres.wholphin.util.UpcomingMovieRequestHandler
import com.github.damontecres.wholphin.util.UpcomingTvRequestHandler
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import timber.log.Timber

/** A Seerr discover grid (DiscoverMoreResult): sort, filter and paging (moved out of DiscoverRequestGrid.kt) */
@HiltViewModel(assistedFactory = DiscoverRequestViewModel.Factory::class)
class DiscoverRequestViewModel
    @AssistedInject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val seerrService: SeerrService,
        val navigationManager: NavigationManager,
        private val api: SeerrApi,
        private val filterOptionCache: FilterOptionCache,
        @Assisted val type: DiscoverRequestType,
        @Assisted startIndex: Int,
        @Assisted initialFilter: DiscoverFilter,
    ) : ViewModel() {
        @AssistedFactory
        interface Factory {
            fun create(
                type: DiscoverRequestType,
                startIndex: Int,
                initialFilter: DiscoverFilter,
            ): DiscoverRequestViewModel
        }

        private val _state = MutableStateFlow(DiscoverRequestState())
        val state: StateFlow<DiscoverRequestState> = _state

        init {
            viewModelScope.launchDefault {
                val (sortOptions, potentialFilterOptions) =
                    when (type) {
                        DiscoverRequestType.DISCOVER_TV -> DiscoverSort.entries.toList() to discoverTvFilters
                        DiscoverRequestType.DISCOVER_MOVIES -> DiscoverSort.entries.toList() to discoverMovieFilters
                        DiscoverRequestType.TRENDING -> emptyList<DiscoverSort>() to emptyList()
                        DiscoverRequestType.UPCOMING_TV -> emptyList<DiscoverSort>() to emptyList()
                        DiscoverRequestType.UPCOMING_MOVIES -> emptyList<DiscoverSort>() to emptyList()
                        DiscoverRequestType.UNKNOWN -> emptyList<DiscoverSort>() to emptyList()
                    }
                val sortAndDirection =
                    when (type) {
                        DiscoverRequestType.DISCOVER_TV -> DiscoverSortAndDirection()
                        DiscoverRequestType.DISCOVER_MOVIES -> DiscoverSortAndDirection()
                        DiscoverRequestType.TRENDING -> DiscoverSortAndDirection()
                        DiscoverRequestType.UPCOMING_TV -> DiscoverSortAndDirection()
                        DiscoverRequestType.UPCOMING_MOVIES -> DiscoverSortAndDirection()
                        DiscoverRequestType.UNKNOWN -> DiscoverSortAndDirection()
                    }
                val filterOptions =
                    if (potentialFilterOptions.isNotEmpty()) {
                        // If the initial filter includes filtering (eg genres), then don't allow for changing it
                        potentialFilterOptions.filter { it.get(initialFilter) == null }
                    } else {
                        potentialFilterOptions
                    }
                _state.update {
                    it.copy(
                        startIndex = startIndex,
                        sortOptions = sortOptions,
                        filterOptions = filterOptions,
                        sortAndDirection = sortAndDirection,
                    )
                }
                fetchData(sortAndDirection, initialFilter, startIndex)
            }
        }

        private var fetchJob: Job? = null

        private suspend fun fetchData(
            sortAndDirection: DiscoverSortAndDirection,
            filter: DiscoverFilter,
            startIndex: Int = 0,
        ) {
            fetchJob?.cancel()
            try {
                _state.update {
                    it.copy(
                        filter = filter,
                        sortAndDirection = sortAndDirection,
                        loading = DataLoadingState.Loading,
                    )
                }
                val filter = filter.copy(sortBy = sortAndDirection)
                fetchJob =
                    viewModelScope.launchDefault {
                        val pager =
                            when (type) {
                                DiscoverRequestType.DISCOVER_TV -> {
                                    DiscoverRequestPager(
                                        api,
                                        DiscoverTvRequestHandler(filter),
                                        seerrService::createDiscoverItem,
                                        viewModelScope,
                                    )
                                }

                                DiscoverRequestType.DISCOVER_MOVIES -> {
                                    DiscoverRequestPager(
                                        api,
                                        DiscoverMovieRequestHandler(filter),
                                        seerrService::createDiscoverItem,
                                        viewModelScope,
                                    )
                                }

                                DiscoverRequestType.TRENDING -> {
                                    DiscoverRequestPager(
                                        api,
                                        TrendingRequestHandler,
                                        seerrService::createDiscoverItem,
                                        viewModelScope,
                                    )
                                }

                                DiscoverRequestType.UPCOMING_TV -> {
                                    DiscoverRequestPager(
                                        api,
                                        UpcomingTvRequestHandler,
                                        seerrService::createDiscoverItem,
                                        viewModelScope,
                                    )
                                }

                                DiscoverRequestType.UPCOMING_MOVIES -> {
                                    DiscoverRequestPager(
                                        api,
                                        UpcomingMovieRequestHandler,
                                        seerrService::createDiscoverItem,
                                        viewModelScope,
                                    )
                                }

                                DiscoverRequestType.UNKNOWN -> {
                                    throw IllegalArgumentException("Cannot display grid for DiscoverRequestType.UNKNOWN")
                                }
                            }.init(startIndex)
                        _state.update {
                            it.copy(
                                loading = DataLoadingState.Success(pager),
                            )
                        }
                    }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                Timber.e(ex, "Error initializing %s", type)
                _state.update {
                    it.copy(
                        loading = DataLoadingState.Error(ex),
                    )
                }
            }
        }

        fun onSortChange(newSort: DiscoverSortAndDirection) {
            viewModelScope.launchDefault {
                _state.update { it.copy(startIndex = 0) }
                fetchData(newSort, state.value.filter)
            }
        }

        fun onFilterChange(newFilter: DiscoverFilter) {
            viewModelScope.launchDefault {
                _state.update { it.copy(startIndex = 0) }
                fetchData(state.value.sortAndDirection, newFilter)
            }
        }

        suspend fun getPossibleFilterValues(filter: DiscoverFilterBy<*>): List<FilterValueOption> =
            filterOptionCache.getFilterOptionValues(null, filter)
    }

data class DiscoverRequestState(
    val loading: DataLoadingState<List<DiscoverItem?>> = DataLoadingState.Pending,
    val startIndex: Int = 0,
    val sortOptions: List<DiscoverSort> = emptyList(),
    val filterOptions: List<DiscoverFilterBy<*>> = emptyList(),
    val filter: DiscoverFilter = DiscoverFilter(),
    val sortAndDirection: DiscoverSortAndDirection = DiscoverSortAndDirection(),
)
