package com.github.damontecres.wholphin.ui.detail

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.data.LibraryDisplayInfoDao
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.filter.FilterValueOption
import com.github.damontecres.wholphin.data.filter.ItemFilterBy
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.GetItemsFilter
import com.github.damontecres.wholphin.data.model.LibraryDisplayInfo
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.services.HomeSettingsService
import com.github.damontecres.wholphin.services.NavDrawerService
import com.github.damontecres.wholphin.services.tvAccess
import com.github.damontecres.wholphin.ui.main.settings.Library
import com.github.damontecres.wholphin.services.BackdropService
import com.github.damontecres.wholphin.services.FavoriteWatchManager
import com.github.damontecres.wholphin.services.FilterOptionCache
import com.github.damontecres.wholphin.services.MediaManagementService
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.ServerReportService
import com.github.damontecres.wholphin.services.deleteItem
import com.github.damontecres.wholphin.ui.SlimItemFields
import com.github.damontecres.wholphin.ui.components.CollectionFolderState
import com.github.damontecres.wholphin.ui.components.CollectionFolderViewActions
import com.github.damontecres.wholphin.ui.components.ContextMenuProvider
import com.github.damontecres.wholphin.ui.components.ViewOptions
import com.github.damontecres.wholphin.ui.components.ViewOptionsPoster
import com.github.damontecres.wholphin.ui.data.SortAndDirection
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.nav.NavDrawerItem
import com.github.damontecres.wholphin.ui.showToast
import com.github.damontecres.wholphin.util.ApiRequestPager
import com.github.damontecres.wholphin.util.BlockingList
import com.github.damontecres.wholphin.util.DataLoadingState
import com.github.damontecres.wholphin.util.GetItemsRequestHandler
import com.github.damontecres.wholphin.util.LoadingState
import com.github.damontecres.wholphin.util.successValue
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

/**
 * WeaselPlex's Watchlist page: every title the member has added, in one grid that the
 * ALL / MOVIES / SHOWS buttons narrow, like a Streaming collection.
 *
 * The chosen button is stored as the filter's `includeItemTypes`, so it is remembered
 * along with the sort.
 */
@HiltViewModel
class WatchlistViewModel
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val api: ApiClient,
        private val navigationManager: NavigationManager,
        private val serverRepository: ServerRepository,
        private val libraryDisplayInfoDao: LibraryDisplayInfoDao,
        private val favoriteWatchManager: FavoriteWatchManager,
        private val backdropService: BackdropService,
        private val mediaManagementService: MediaManagementService,
        private val serverReportService: ServerReportService,
        private val filterOptionCache: FilterOptionCache,
        private val homeSettingsService: HomeSettingsService,
        private val navDrawerService: NavDrawerService,
    ) : ViewModel(),
        ContextMenuProvider,
        CollectionFolderViewActions {
        private val _state = MutableStateFlow(CollectionFolderState(viewOptions = ViewOptionsPoster))
        val state: StateFlow<CollectionFolderState> = _state

        private val _libraries = MutableStateFlow<List<Library>>(emptyList())
        val libraries: StateFlow<List<Library>> = _libraries
        private var loaded = false
        private var fetchJob: Job? = null

        /**
         * Loads the Watchlist the first time, then reloads it in place each time the page comes
         * back, so a title added or removed on its details page is reflected on return.
         */
        fun refresh() {
            if (!loaded) {
                loaded = true
                launchFetch(inPlace = false) {
                    val saved =
                        serverRepository.currentUser?.let { libraryDisplayInfoDao.getItem(it, DISPLAY_ID) }
                    Triple(
                        saved?.sortAndDirection ?: SortAndDirection.DEFAULT,
                        saved?.filter ?: GetItemsFilter(),
                        saved?.viewOptions ?: ViewOptionsPoster,
                    )
                }
            } else {
                val current = state.value
                launchFetch(inPlace = true) {
                    Triple(current.sortAndDirection, current.filter, current.viewOptions)
                }
            }
        }

        /**
         * Only the newest fetch may write results, so switching buttons quickly can't leave the
         * grid showing an older selection.
         */
        private fun launchFetch(
            inPlace: Boolean,
            params: suspend () -> Triple<SortAndDirection, GetItemsFilter, ViewOptions>,
        ) {
            fetchJob?.cancel()
            fetchJob =
                viewModelScope.launchIO {
                    val (sort, filter, viewOptions) = params()
                    fetch(sort, filter, viewOptions, inPlace)
                }
        }

        /**
         * @param inPlace keep showing the current titles until the new ones arrive, which keeps
         * D-pad focus on the button or sort that was just pressed
         */
        @VisibleForTesting
        internal suspend fun fetch(
            sortAndDirection: SortAndDirection,
            filter: GetItemsFilter,
            viewOptions: ViewOptions,
            inPlace: Boolean,
        ) {
            _state.update {
                it.copy(
                    item = DataLoadingState.Success(null),
                    items =
                        if (inPlace && it.items is DataLoadingState.Success) it.items else DataLoadingState.Loading,
                    backgroundLoading = LoadingState.Loading,
                    sortAndDirection = sortAndDirection,
                    filter = filter,
                    viewOptions = viewOptions,
                )
            }
            val items =
                try {
                    val user = serverRepository.currentUserDto
                    val libraries = user?.let { navDrawerService.getAllUserLibraries(it.id, it.tvAccess) }.orEmpty()
                    _libraries.value = libraries
                    val pager =
                        ApiRequestPager(
                            api,
                            createRequest(filter, sortAndDirection),
                            GetItemsRequestHandler,
                            viewModelScope,
                            pageSize = 50,
                            // Episodes show their show's poster so the grid stays uniform
                            useSeriesForPrimary = true,
                            transformItems = { page ->
                                if (user != null) homeSettingsService.resolveItemLibraries(page, user.id, libraries) else page
                            },
                        ).init()
                    DataLoadingState.Success(pager)
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    Timber.e(ex, "Error fetching the Watchlist")
                    DataLoadingState.Error(ex)
                }
            _state.update { it.copy(items = items, backgroundLoading = LoadingState.Success) }
        }

        private fun saveDisplayInfo(
            sortAndDirection: SortAndDirection,
            filter: GetItemsFilter,
            viewOptions: ViewOptions,
        ) {
            serverRepository.currentUser?.let { user ->
                viewModelScope.launchIO {
                    libraryDisplayInfoDao.saveItem(
                        LibraryDisplayInfo(
                            userId = user.rowId,
                            itemId = DISPLAY_ID,
                            sort = sortAndDirection.sort,
                            direction = sortAndDirection.direction,
                            filter = filter,
                            viewOptions = viewOptions,
                        ),
                    )
                }
            }
        }

        private val pager: ApiRequestPager<*>?
            get() = state.value.items.successValue as? ApiRequestPager<*>

        override fun isAdministrator(): Boolean = serverRepository.currentUserDto?.policy?.isAdministrator == true

        override fun navigateTo(destination: Destination) = navigationManager.navigateTo(destination)

        override fun canDelete(
            item: BaseItem,
            appPreferences: AppPreferences,
        ): Boolean = mediaManagementService.canDelete(item, appPreferences)

        override fun deleteItem(
            index: Int,
            item: BaseItem,
        ) {
            deleteItem(context, mediaManagementService, item) {
                viewModelScope.launchIO { pager?.refreshPagesAfter(index) }
            }
        }

        override fun setWatched(
            position: Int,
            itemId: UUID,
            played: Boolean,
        ) {
            viewModelScope.launchIO {
                favoriteWatchManager.setWatched(itemId, played)
                pager?.refreshItem(position, itemId)
            }
        }

        override fun setFavorite(
            position: Int,
            itemId: UUID,
            favorite: Boolean,
        ) {
            viewModelScope.launchIO {
                favoriteWatchManager.setFavorite(itemId, favorite)
                if (favorite) {
                    pager?.refreshItem(position, itemId)
                } else {
                    // Removed: reload from here on so the title drops out and the rest move up
                    pager?.refreshPagesAfter(position)
                }
            }
        }

        override fun sendReportFor(itemId: UUID) = serverReportService.sendMediaReportFor(itemId)

        override fun updateBackdrop(item: BaseItem) {
            viewModelScope.launchIO { backdropService.submit(item) }
        }

        override fun onSortChange(
            sortAndDirection: SortAndDirection,
            recursive: Boolean,
            filter: GetItemsFilter,
        ) {
            val viewOptions = state.value.viewOptions
            saveDisplayInfo(sortAndDirection, filter, viewOptions)
            launchFetch(inPlace = true) { Triple(sortAndDirection, filter, viewOptions) }
        }

        override fun onFilterChange(
            newFilter: GetItemsFilter,
            recursive: Boolean,
        ) {
            val current = state.value
            saveDisplayInfo(current.sortAndDirection, newFilter, current.viewOptions)
            launchFetch(inPlace = true) { Triple(current.sortAndDirection, newFilter, current.viewOptions) }
        }

        override suspend fun getFilterOptionValues(filterOption: ItemFilterBy<*>): List<FilterValueOption> =
            filterOptionCache.getFilterOptionValues(null, filterOption)

        override suspend fun positionOfLetter(letter: Char): Int? {
            val current = state.value
            val request =
                createRequest(current.filter, current.sortAndDirection).copy(
                    nameLessThan = letter.toString(),
                    enableImageTypes = null,
                    fields = null,
                    enableUserData = false,
                    limit = 0,
                    enableTotalRecordCount = true,
                )
            return GetItemsRequestHandler.execute(api, request).content.totalRecordCount
        }

        override fun saveViewOptions(viewOptions: ViewOptions) {
            _state.update { it.copy(viewOptions = viewOptions) }
            val current = state.value
            saveDisplayInfo(current.sortAndDirection, current.filter, viewOptions)
            if (!viewOptions.showBackdrop) {
                viewModelScope.launchIO { backdropService.clearBackdrop() }
            }
        }

        override fun onClickRandom() {
            viewModelScope.launchIO {
                try {
                    @Suppress("UNCHECKED_CAST")
                    val random = (state.value.items.successValue as? BlockingList<BaseItem?>)?.randomBlocking()
                    random?.let { navigateTo(it.destination()) }
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    Timber.e(ex, "Error getting a random Watchlist title")
                    showToast(context, "Error: ${ex.localizedMessage}")
                }
            }
        }

        companion object {
            /** Where the sort, button and view options are saved. */
            val DISPLAY_ID = "${NavDrawerItem.Favorites.id}_watchlist"

            /** Everything the ALL button covers. */
            val WatchlistTypes =
                listOf(
                    BaseItemKind.MOVIE,
                    BaseItemKind.SERIES,
                    BaseItemKind.SEASON,
                    BaseItemKind.EPISODE,
                    BaseItemKind.VIDEO,
                    BaseItemKind.BOX_SET,
                    BaseItemKind.MUSIC_VIDEO,
                )

            @VisibleForTesting
            internal fun createRequest(
                filter: GetItemsFilter,
                sortAndDirection: SortAndDirection,
            ): GetItemsRequest =
                filter
                    .applyTo(
                        GetItemsRequest(
                            recursive = true,
                            fields = SlimItemFields,
                            sortBy = listOf(sortAndDirection.sort),
                            sortOrder = listOf(sortAndDirection.direction),
                        ),
                    ).copy(
                        // applyTo copies the filter's favorite value, which is unset here
                        isFavorite = true,
                        includeItemTypes =
                            filter.includeItemTypes
                                ?.filter { it in WatchlistTypes }
                                ?.takeIf { it.isNotEmpty() }
                                ?: WatchlistTypes,
                    )
        }
    }
