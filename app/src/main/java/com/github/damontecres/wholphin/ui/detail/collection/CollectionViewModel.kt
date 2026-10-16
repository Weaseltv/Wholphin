package com.github.damontecres.wholphin.ui.detail.collection

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.LibraryDisplayInfoDao
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.filter.FilterValueOption
import com.github.damontecres.wholphin.data.filter.ItemFilterBy
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.GetItemsFilter
import com.github.damontecres.wholphin.data.model.LibraryDisplayInfo
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.services.BackdropService
import com.github.damontecres.wholphin.services.FavoriteWatchManager
import com.github.damontecres.wholphin.services.FilterOptionCache
import com.github.damontecres.wholphin.services.ImageUrlService
import com.github.damontecres.wholphin.services.KeyValueService
import com.github.damontecres.wholphin.services.MediaManagementService
import com.github.damontecres.wholphin.services.MusicService
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.ServerReportService
import com.github.damontecres.wholphin.services.StreamingCollections
import com.github.damontecres.wholphin.services.StreamingQuickList
import com.github.damontecres.wholphin.services.StreamingQuickListService
import com.github.damontecres.wholphin.services.ThemeSongPlayer
import com.github.damontecres.wholphin.services.UserPreferencesService
import com.github.damontecres.wholphin.services.deleteItem
import com.github.damontecres.wholphin.ui.SlimItemFields
import com.github.damontecres.wholphin.ui.collectLatestIn
import com.github.damontecres.wholphin.ui.components.ViewOptions
import com.github.damontecres.wholphin.ui.data.RowColumn
import com.github.damontecres.wholphin.ui.data.SortAndDirection
import com.github.damontecres.wholphin.ui.detail.music.addToQueue
import com.github.damontecres.wholphin.ui.formatTypeName
import com.github.damontecres.wholphin.ui.launchDefault
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.showToast
import com.github.damontecres.wholphin.ui.toServerString
import com.github.damontecres.wholphin.ui.util.ResStringProvider
import com.github.damontecres.wholphin.util.ApiRequestPager
import com.github.damontecres.wholphin.util.ExceptionHandler
import com.github.damontecres.wholphin.util.GetItemsRequestHandler
import com.github.damontecres.wholphin.util.HomeRowLoadingState
import com.github.damontecres.wholphin.util.LoadingState
import com.github.damontecres.wholphin.util.WholphinDispatchers
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import timber.log.Timber
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = CollectionViewModel.Factory::class)
class CollectionViewModel
    @AssistedInject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val api: ApiClient,
        val serverRepository: ServerRepository,
        private val navigationManager: NavigationManager,
        private val preferencesService: UserPreferencesService,
        private val themeSongPlayer: ThemeSongPlayer,
        private val mediaManagementService: MediaManagementService,
        private val favoriteWatchManager: FavoriteWatchManager,
        private val backdropService: BackdropService,
        private val keyValueService: KeyValueService,
        private val libraryDisplayInfoDao: LibraryDisplayInfoDao,
        private val imageUrlService: ImageUrlService,
        private val musicService: MusicService,
        val serverReportService: ServerReportService,
        private val filterOptionCache: FilterOptionCache,
        private val quickListService: StreamingQuickListService,
        @Assisted private val itemId: UUID,
    ) : ViewModel() {
        @AssistedFactory
        interface Factory {
            fun create(itemId: UUID): CollectionViewModel
        }

        private val viewOptionsFlow =
            serverRepository.currentUserFlow
                .filterNotNull()
                .flatMapLatest {
                    keyValueService.get(it.id, VIEW_OPTIONS_KEY, CollectionViewOptions())
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), CollectionViewOptions())

        private val libraryDisplayInfoFlow =
            serverRepository.currentUserFlow
                .filterNotNull()
                .flatMapLatest {
                    libraryDisplayInfoDao.getItemAsFlow(it.rowId, itemId.toServerString())
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

        private val _state = MutableStateFlow(CollectionState())
        val state: StateFlow<CollectionState> = _state

        init {
            addCloseable { release() }
            // Get global per-user view options for collections
            viewOptionsFlow.collectLatestIn(viewModelScope) { viewOptions ->
                Timber.v("Updated viewOptions")
                _state.update {
                    it.copy(
                        viewOptions =
                            if (it.isStreaming) {
                                StreamingViewOptions
                            } else {
                                // Navigating the grid never swaps the header for the focused card, like library pages
                                viewOptions.copy(cardViewOptions = viewOptions.cardViewOptions.copy(showDetails = false))
                            },
                    )
                }
            }
            libraryDisplayInfoFlow
                .filterNotNull()
                .collectLatestIn(viewModelScope) { libraryDisplayInfo ->
                    Timber.v("Updated libraryDisplayInfo")
                    _state.update {
                        it.copy(
                            itemFilter = libraryDisplayInfo.filter,
                            sortAndDirection =
                                if (it.isStreaming && libraryDisplayInfo.sort == ItemSortBy.DEFAULT) {
                                    StreamingDefaultSort
                                } else {
                                    libraryDisplayInfo.sortAndDirection
                                },
                        )
                    }
                }
            viewModelScope.launchDefault {
                try {
                    val collection =
                        api.userLibraryApi
                            .getItem(itemId = itemId, userId = serverRepository.currentUser?.id)
                            .content
                            .let { BaseItem(it, false) }
                    backdropService.submit(collection)
                    val logoImageUrl =
                        if (ImageType.LOGO in collection.data.imageTags.orEmpty()) {
                            imageUrlService.getItemImageUrl(collection, ImageType.LOGO)
                        } else {
                            null
                        }
                    _state.update {
                        val streaming =
                            BuildConfig.FLAVOR == "weaselfin" &&
                                StreamingCollections.isStreamingCollection(collection.data)
                        it.copy(
                            collection = collection,
                            logoImageUrl = logoImageUrl,
                            viewOptions =
                                if (streaming) {
                                    StreamingViewOptions
                                } else {
                                    it.viewOptions
                                },
                            sortAndDirection =
                                if (streaming && it.sortAndDirection.sort == ItemSortBy.DEFAULT) {
                                    StreamingDefaultSort
                                } else {
                                    it.sortAndDirection
                                },
                            quickList = if (streaming) StreamingQuickList.NEW else null,
                        )
                    }
                    if (state.value.isStreaming) {
                        quickListService.prefetch(StreamingCollections.justWatchPackages(collection.name))
                    }
                    listenForStateUpdates()
                    themeSongPlayer.playThemeFor(itemId)
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    Timber.e(ex, "Error getting collection %s", itemId)
                    _state.update { it.copy(loadingState = LoadingState.Error(ex)) }
                }
            }
        }

        fun release() {
            themeSongPlayer.stop()
        }

        /**
         * Collects on [state] and fetches data when needed
         */
        private fun listenForStateUpdates() =
            viewModelScope.launchDefault {
                state
                    .map {
                        DataKey(
                            it.sortAndDirection,
                            it.itemFilter,
                            it.viewOptions.separateTypes,
                            it.quickList,
                        )
                    }.distinctUntilChanged()
                    .collectLatest { (sort, filter, separateTypes, quickList) ->
                        try {
                            updateData(sort, filter, separateTypes, quickList)
                        } catch (ex: CancellationException) {
                            throw ex
                        } catch (ex: Exception) {
                            Timber.e(
                                ex,
                                "Error fetching data for collection %s",
                                itemId,
                            )
                            _state.update { it.copy(loadingState = LoadingState.Error(ex)) }
                        }
                    }
            }

        private suspend fun updateData(
            sort: SortAndDirection,
            filter: GetItemsFilter,
            separateTypes: Boolean,
            quickList: StreamingQuickList?,
        ) {
            Timber.d("Begin updateData for %s", itemId)
            // NEW with filters beyond the type buttons uses the server's sorted list instead
            val useQuickList =
                quickList != null &&
                    state.value.isStreaming &&
                    (quickList != StreamingQuickList.NEW || quickListService.canFilterLocally(filter))
            val userId = serverRepository.currentUser?.id
            if (useQuickList && quickList == StreamingQuickList.NEW && userId != null) {
                // Show the newest titles as each page arrives instead of waiting for all of them
                var shown = false
                _state.update { it.copy(quickListLoading = quickList) }
                quickListService.newest(userId, itemId).collect { progress ->
                    if (progress.items.isEmpty() && !progress.complete) return@collect
                    val types = filter.includeItemTypes
                    val items = if (types.isNullOrEmpty()) progress.items else progress.items.filter { it.type in types }
                    _state.update {
                        if (it.quickList == quickList && it.itemFilter == filter) {
                            shown = shown || progress.items.isNotEmpty()
                            it.copy(
                                items = items,
                                loadingState = LoadingState.Success,
                                quickListLoading = if (progress.complete) null else it.quickListLoading,
                            )
                        } else {
                            it
                        }
                    }
                }
                _state.update { it.copy(quickListLoading = null) }
                // If nothing could be loaded this way, use the server's sorted list below
                if (shown) return
            } else if (useQuickList && quickList != null) {
                // Keep the current posters while the list loads; the button shows it's loading
                _state.update { it.copy(quickListLoading = quickList) }
                val result =
                    try {
                        fetchQuickList(quickList, filter)
                    } catch (ex: CancellationException) {
                        throw ex
                    } catch (ex: Exception) {
                        // Fall back to the normal collection rather than an error page
                        Timber.e(ex, "Error fetching %s for collection %s", quickList, itemId)
                        showToast(context, context.getString(R.string.quick_list_error, context.getString(quickList.title)))
                        _state.update { it.copy(quickList = null, quickListLoading = null) }
                        return
                    }
                // Only show a list that's still the one selected
                _state.update {
                    if (it.quickList == quickList && it.itemFilter == filter) {
                        it.copy(items = result, loadingState = LoadingState.Success, quickListLoading = null)
                    } else {
                        it
                    }
                }
                return
            }
            _state.update {
                it.copy(
                    loadingState = LoadingState.Loading,
                    items = emptyList(),
                    separateItems = emptyMap(),
                    quickListLoading = null,
                )
            }
            if (!separateTypes) {
                val types = if (state.value.isStreaming) StreamingCollections.types else typesInCollection
                val result = fetchItems(sort, filter, types)
                _state.update { it.copy(items = result) }
            } else {
                supervisorScope {
                    // One row per type the ALL / MOVIES / SHOWS choice allows
                    val rowTypes =
                        filter.includeItemTypes
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { chosen -> typesInCollection.filter { it in chosen } }
                            ?: typesInCollection
                    val jobs =
                        rowTypes.map { type ->
                            async(WholphinDispatchers.IO) {
                                val title = ResStringProvider(formatTypeName(type))
                                val result =
                                    try {
                                        val pager = fetchItems(sort, filter, listOf(type))
                                        HomeRowLoadingState.Success(
                                            title,
                                            pager,
                                            showViewMore = false,
                                        )
                                    } catch (ex: Exception) {
                                        Timber.e(
                                            ex,
                                            "Error fetching %s for collection %s",
                                            type,
                                            itemId,
                                        )
                                        HomeRowLoadingState.Error(title, exception = ex)
                                    }
                                type to result
                            }
                        }
                    jobs.forEach { job ->
                        val (type, row) = job.await()
                        _state.update {
                            val separateItems =
                                it.separateItems.toMutableMap().apply {
                                    put(type, row)
                                }
                            it.copy(separateItems = separateItems)
                        }
                    }
                }
            }
            _state.update { it.copy(loadingState = LoadingState.Success) }
            Timber.d("End updateData for %s", itemId)
        }

        /** The collection's members in [quickList] order, honoring [filter]. */
        private suspend fun fetchQuickList(
            quickList: StreamingQuickList,
            filter: GetItemsFilter,
        ): List<BaseItem?> {
            val userId = serverRepository.currentUser?.id ?: return emptyList()
            val packages = StreamingCollections.justWatchPackages(state.value.collection?.name)
            if (quickListService.canFilterLocally(filter)) {
                return quickListService.items(userId, itemId, packages, quickList, filter)
            }
            // Other filters need the server: rank the members, then fetch them with the filter
            val ranked = quickListService.items(userId, itemId, packages, quickList, GetItemsFilter())
            val ids = ranked.map { it.id }
            val byId =
                coroutineScope {
                    ids
                        .chunked(QUICK_LIST_CHUNK)
                        .map { chunk ->
                            async(WholphinDispatchers.IO) {
                                val request =
                                    createGetItemsRequest(null, filter, StreamingCollections.types)
                                        .copy(parentId = null, ids = chunk, enableTotalRecordCount = false)
                                GetItemsRequestHandler.execute(api, request).content.items
                            }
                        }.awaitAll()
                        .flatten()
                        .associateBy { it.id }
                }
            val items = ids.mapNotNull { id -> byId[id]?.let { BaseItem(it, true) } }
            return if (quickList == StreamingQuickList.TOP_10) items.take(10) else items
        }

        private suspend fun fetchItems(
            sort: SortAndDirection?,
            filter: GetItemsFilter?,
            types: List<BaseItemKind>,
        ): ApiRequestPager<GetItemsRequest> {
            val request = createGetItemsRequest(sort, filter, types)
            val useSeriesForPrimary = !state.value.viewOptions.separateTypes
            return ApiRequestPager(
                api,
                request,
                GetItemsRequestHandler,
                viewModelScope,
                useSeriesForPrimary = useSeriesForPrimary,
            ).init()
        }

        private fun createGetItemsRequest(
            sort: SortAndDirection?,
            filter: GetItemsFilter?,
            types: List<BaseItemKind>,
        ): GetItemsRequest {
            val includeItemTypes: List<BaseItemKind>?
            val excludeItemTypes: List<BaseItemKind>?
            // Workaround for https://github.com/jellyfin/jellyfin/issues/16454
            if (types.size == 1 && types.first() == BaseItemKind.BOX_SET) {
                includeItemTypes = null
                excludeItemTypes =
                    BaseItemKind.entries
                        .toMutableList()
                        .apply { remove(BaseItemKind.BOX_SET) }
            } else {
                includeItemTypes = types
                excludeItemTypes = null
            }
            val request =
                GetItemsRequest(
                    userId = serverRepository.currentUser?.id,
                    parentId = itemId,
                    includeItemTypes = includeItemTypes,
                    excludeItemTypes = excludeItemTypes,
                    recursive = false,
                    sortBy = sort?.let { listOf(sort.sort) },
                    sortOrder = sort?.let { listOf(sort.direction) },
                    fields = SlimItemFields,
                ).let {
                    val filtered = filter?.applyTo(it, false) ?: it
                    if (state.value.isStreaming) {
                        val allowedTypes = StreamingCollections.types
                        filtered.copy(
                            userId = requireNotNull(serverRepository.currentUser).id,
                            includeItemTypes =
                                filter
                                    ?.includeItemTypes
                                    ?.filter { it in allowedTypes }
                                    ?.takeIf { it.isNotEmpty() } ?: allowedTypes,
                        )
                    } else {
                        // ALL / MOVIES / SHOWS narrow the collection's own types
                        val chosen = filter?.includeItemTypes?.takeIf { it.isNotEmpty() }
                        if (chosen == null) {
                            filtered
                        } else {
                            filtered.copy(includeItemTypes = types.filter { it in chosen }.ifEmpty { chosen })
                        }
                    }
                }
            return request
        }

        /** Show the [quickList] ranking, or the normal sorted collection when null or tapped again. */
        fun changeQuickList(quickList: StreamingQuickList?) {
            // Pressing the selected list again goes back to NEW, the default
            _state.update { it.copy(quickList = if (quickList == it.quickList) StreamingQuickList.NEW else quickList) }
        }

        fun changeSort(sortAndDirection: SortAndDirection) {
            // Choosing a sort leaves any quick list
            _state.update { it.copy(quickList = null) }
            viewModelScope.launchIO {
                val user = serverRepository.currentUser
                val state = _state.value
                if (user != null) {
                    libraryDisplayInfoDao.saveItem(
                        LibraryDisplayInfo(
                            user = user,
                            itemId = itemId,
                            sort = sortAndDirection.sort,
                            direction = sortAndDirection.direction,
                            filter = state.itemFilter,
                            viewOptions = null,
                        ),
                    )
                }
            }
        }

        fun changeFilter(filter: GetItemsFilter) {
            viewModelScope.launchIO {
                val user = serverRepository.currentUser
                val state = _state.value
                if (user != null) {
                    libraryDisplayInfoDao.saveItem(
                        LibraryDisplayInfo(
                            user = user,
                            itemId = itemId,
                            sort = state.sortAndDirection.sort,
                            direction = state.sortAndDirection.direction,
                            filter = filter,
                            viewOptions = null,
                        ),
                    )
                }
            }
        }

        fun changeViewOptions(viewOptions: CollectionViewOptions) {
            viewModelScope.launchIO {
                if (!viewOptions.cardViewOptions.showDetails) {
                    val collection = state.value.collection
                    if (collection != null) {
                        backdropService.submit(collection)
                    } else {
                        backdropService.clearBackdrop()
                    }
                }
                serverRepository.currentUser?.id?.let { userId ->
                    keyValueService.save(userId, VIEW_OPTIONS_KEY, viewOptions)
                }
            }
        }

        suspend fun getPossibleFilterValues(filterOption: ItemFilterBy<*>): List<FilterValueOption> =
            filterOptionCache.getFilterOptionValues(
                itemId,
                filterOption,
            )

        suspend fun letterPosition(letter: Char): Int =
            withContext(WholphinDispatchers.IO) {
                val sort = state.value.sortAndDirection
                val filter = state.value.itemFilter
                val request =
                    createGetItemsRequest(
                        sort = sort,
                        filter = filter,
                        types = typesInCollection,
                    ).copy(
                        enableImageTypes = null,
                        fields = null,
                        nameLessThan = letter.toString(),
                        limit = 0,
                        enableTotalRecordCount = true,
                    )
                val result by GetItemsRequestHandler.execute(api, request)
                result.totalRecordCount
            }

        fun navigateTo(destination: Destination) {
            release()
            navigationManager.navigateTo(destination)
        }

        /**
         * Navigate to a random item in this collection, honoring the current filter
         */
        fun onClickRandom() {
            viewModelScope.launchIO {
                try {
                    val types = if (state.value.isStreaming) StreamingCollections.types else typesInCollection
                    val request =
                        createGetItemsRequest(
                            sort = SortAndDirection(ItemSortBy.RANDOM, SortOrder.ASCENDING),
                            filter = state.value.itemFilter,
                            types = types,
                        ).copy(limit = 1)
                    val random =
                        GetItemsRequestHandler
                            .execute(api, request)
                            .content.items
                            .firstOrNull()
                            ?.let { BaseItem(it, false) }
                    Timber.d("Got random item: %s", random?.id)
                    random?.let { navigateTo(it.destination()) }
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    Timber.e(ex, "Error getting random")
                    showToast(context, "Error: ${ex.localizedMessage}")
                }
            }
        }

        fun setWatched(
            itemId: UUID,
            played: Boolean,
            position: RowColumn?,
        ) = viewModelScope.launch(WholphinDispatchers.IO + ExceptionHandler()) {
            favoriteWatchManager.setWatched(itemId, played)
            if (itemId == state.value.collection?.id) {
                refreshCollection()
            } else if (position != null) {
                refreshItem(itemId, position, false)
            }
        }

        fun setFavorite(
            itemId: UUID,
            favorite: Boolean,
            position: RowColumn?,
        ) = viewModelScope.launch(ExceptionHandler() + WholphinDispatchers.IO) {
            favoriteWatchManager.setFavorite(itemId, favorite)
            if (itemId == state.value.collection?.id) {
                refreshCollection()
            } else if (position != null) {
                refreshItem(itemId, position, false)
            }
        }

        private fun refreshCollection() {
            viewModelScope.launchDefault {
                val collection =
                    api.userLibraryApi
                        .getItem(itemId)
                        .content
                        .let { BaseItem(it, false) }
                _state.update { it.copy(collection = collection) }
            }
        }

        fun canDelete(
            item: BaseItem,
            appPreferences: AppPreferences,
        ): Boolean = mediaManagementService.canDelete(item, appPreferences)

        fun deleteItem(
            item: BaseItem,
            position: RowColumn?,
        ) {
            deleteItem(context, mediaManagementService, item) {
                viewModelScope.launchIO {
                    if (position != null) {
                        refreshItem(itemId, position, true)
                    }
                }
            }
        }

        private suspend fun refreshItem(
            itemId: UUID,
            position: RowColumn,
            isDelete: Boolean,
        ) {
            state.value.let { state ->
                val items =
                    if (state.viewOptions.separateTypes) {
                        val key =
                            state.separateItems.keys
                                .toList()
                                .getOrNull(position.row)
                        (state.separateItems[key] as? HomeRowLoadingState.Success)?.items as? ApiRequestPager<*>
                    } else {
                        state.items as? ApiRequestPager<*>
                    }
                if (isDelete) {
                    items?.refreshPagesAfter(position.column)
                } else {
                    items?.refreshItem(position.column, itemId)
                }
            }
        }

        fun updateBackdrop(item: BaseItem) {
            viewModelScope.launchDefault {
                val collection = state.value.collection
                if (item.id == collection?.id) {
                    // Always show the collection's backdrop if requested
                    backdropService.submit(collection)
                } else if (state.value.viewOptions.cardViewOptions.showDetails) {
                    backdropService.submit(item)
                } else {
                    backdropService.clearBackdrop()
                }
            }
        }

        fun addToQueue(
            item: BaseItem,
            index: Int,
        ) = addToQueue(api, musicService, item, index)

        companion object {
            val typesInCollection =
                listOf(
                    BaseItemKind.MOVIE,
                    BaseItemKind.SERIES,
                    BaseItemKind.EPISODE,
                    BaseItemKind.VIDEO,
                    BaseItemKind.BOX_SET,
                    BaseItemKind.MUSIC_ARTIST,
                    BaseItemKind.MUSIC_ALBUM,
                    BaseItemKind.MUSIC_VIDEO,
                )

            const val VIEW_OPTIONS_KEY = "CollectionViewOptions"
        }
    }

@Stable
data class CollectionState(
    val loadingState: LoadingState = LoadingState.Pending,
    val collection: BaseItem? = null,
    val sortAndDirection: SortAndDirection =
        SortAndDirection(
            ItemSortBy.DEFAULT,
            SortOrder.ASCENDING,
        ),
    val itemFilter: GetItemsFilter = GetItemsFilter(),
    val viewOptions: CollectionViewOptions = CollectionViewOptions(),
    val items: List<BaseItem?> = emptyList(),
    val separateItems: Map<BaseItemKind, HomeRowLoadingState> = emptyMap(),
    val logoImageUrl: String? = null,
    val quickList: StreamingQuickList? = null,
    /** The quick list being loaded, while the previous posters stay on screen. */
    val quickListLoading: StreamingQuickList? = null,
) {
    val isStreaming: Boolean
        get() = BuildConfig.FLAVOR == "weaselfin" && collection?.data?.let(StreamingCollections::isStreamingCollection) == true
}

private const val QUICK_LIST_CHUNK = 100

private data class DataKey(
    val sort: SortAndDirection,
    val filter: GetItemsFilter,
    val separateTypes: Boolean,
    val quickList: StreamingQuickList?,
)

private val StreamingDefaultSort = SortAndDirection(ItemSortBy.PREMIERE_DATE, SortOrder.DESCENDING)

private val StreamingViewOptions =
    CollectionViewOptions(
        separateTypes = false,
        cardViewOptions = ViewOptions(showTitles = true),
    )
