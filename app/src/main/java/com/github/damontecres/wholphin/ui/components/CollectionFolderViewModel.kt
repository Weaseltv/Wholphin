package com.github.damontecres.wholphin.ui.components

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.LibraryDisplayInfoDao
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.filter.DefaultFilterOptions
import com.github.damontecres.wholphin.data.filter.FilterValueOption
import com.github.damontecres.wholphin.data.filter.ItemFilterBy
import com.github.damontecres.wholphin.data.model.ApprovedHomeLayout
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.CollectionFolderFilter
import com.github.damontecres.wholphin.data.model.GetItemsFilter
import com.github.damontecres.wholphin.data.model.GetItemsFilterOverride
import com.github.damontecres.wholphin.data.model.HomeCardAppearance
import com.github.damontecres.wholphin.data.model.HomeRowViewOptions
import com.github.damontecres.wholphin.data.model.LibraryDisplayInfo
import com.github.damontecres.wholphin.kids.isStaleSession
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.services.BackdropService
import com.github.damontecres.wholphin.services.CollectionGroups
import com.github.damontecres.wholphin.services.FavoriteWatchManager
import com.github.damontecres.wholphin.services.FilterOptionCache
import com.github.damontecres.wholphin.services.HomeSettingsService
import com.github.damontecres.wholphin.services.MediaManagementService
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.ServerReportService
import com.github.damontecres.wholphin.services.StreamChoiceService
import com.github.damontecres.wholphin.services.StreamingCollections
import com.github.damontecres.wholphin.services.ThemeSongPlayer
import com.github.damontecres.wholphin.services.UserPreferencesService
import com.github.damontecres.wholphin.services.deleteItem
import com.github.damontecres.wholphin.ui.SlimItemFields
import com.github.damontecres.wholphin.ui.data.SortAndDirection
import com.github.damontecres.wholphin.ui.equalsNotNull
import com.github.damontecres.wholphin.ui.launchDefault
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.main.settings.Library
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.showToast
import com.github.damontecres.wholphin.ui.toServerString
import com.github.damontecres.wholphin.util.ApiRequestPager
import com.github.damontecres.wholphin.util.BlockingList
import com.github.damontecres.wholphin.util.DataLoadingState
import com.github.damontecres.wholphin.util.ExceptionHandler
import com.github.damontecres.wholphin.util.GetItemsRequestHandler
import com.github.damontecres.wholphin.util.GetPersonsHandler
import com.github.damontecres.wholphin.util.LoadingState
import com.github.damontecres.wholphin.util.WholphinDispatchers
import com.github.damontecres.wholphin.util.successValue
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.api.request.GetPersonsRequest
import org.jellyfin.sdk.model.serializer.toUUID
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/*
 * The library page's ViewModel and state (moved out of CollectionFolderView.kt so the no-Compose UI can use them
 * without Compose, 2026-11-22). Unchanged.
 */

/** The Collections page's grouped lists by request, kept while the app runs so it reopens straight away. */
private val savedCollections = ConcurrentHashMap<GetItemsRequest, List<BaseItemDto>>()

@HiltViewModel(assistedFactory = CollectionFolderViewModel.Factory::class)
class CollectionFolderViewModel
    @AssistedInject
    constructor(
        private val savedStateHandle: SavedStateHandle,
        private val api: ApiClient,
        @param:ApplicationContext private val context: Context,
        val serverRepository: ServerRepository,
        private val libraryDisplayInfoDao: LibraryDisplayInfoDao,
        private val favoriteWatchManager: FavoriteWatchManager,
        private val backdropService: BackdropService,
        private val navigationManager: NavigationManager,
        private val themeSongPlayer: ThemeSongPlayer,
        private val userPreferencesService: UserPreferencesService,
        private val mediaManagementService: MediaManagementService,
        val streamChoiceService: StreamChoiceService,
        val serverReportService: ServerReportService,
        private val filterOptionCache: FilterOptionCache,
        val homeSettingsService: HomeSettingsService,
        @Assisted val itemId: String,
        @Assisted initialSortAndDirection: SortAndDirection?,
        @Assisted("recursive") private val recursive: Boolean,
        @Assisted private val collectionFilter: CollectionFolderFilter,
        @Assisted("useSeriesForPrimary") private val useSeriesForPrimary: Boolean,
        @Assisted defaultViewOptions: ViewOptions,
    ) : ViewModel(),
        ContextMenuProvider,
        CollectionFolderViewActions {
        @AssistedFactory
        interface Factory {
            fun create(
                itemId: String,
                initialSortAndDirection: SortAndDirection?,
                @Assisted("recursive") recursive: Boolean,
                collectionFilter: CollectionFolderFilter,
                @Assisted("useSeriesForPrimary") useSeriesForPrimary: Boolean,
                defaultViewOptions: ViewOptions,
            ): CollectionFolderViewModel
        }

        private val _state = MutableStateFlow(CollectionFolderState(viewOptions = defaultViewOptions))
        val state: StateFlow<CollectionFolderState> = _state

        var position: Int
            get() = savedStateHandle.get<Int>("position") ?: 0
            set(value) {
                savedStateHandle["position"] = value
            }

        init {
            viewModelScope.launchIO {
                try {
                    val item =
                        itemId.toUUIDOrNull()?.let {
                            api.userLibraryApi
                                .getItem(it)
                                .content
                                .let(::BaseItem)
                        }

                    val libraryDisplayInfo =
                        serverRepository.currentUser?.let { user ->
                            val id = collectionFilter.libraryDisplayInfoIdOverride ?: itemId
                            libraryDisplayInfoDao.getItem(user, id)
                        }
                    _state.update {
                        it.copy(
                            viewOptions = libraryDisplayInfo?.viewOptions ?: defaultViewOptions,
                        )
                    }

                    val collectionsPage =
                        BuildConfig.FLAVOR == "weaselfin" &&
                            (
                                item?.data?.collectionType == CollectionType.BOXSETS ||
                                    collectionFilter.filter.includeItemTypes == listOf(BaseItemKind.BOX_SET)
                            )
                    val orderPreferences = context.getSharedPreferences("collections_display_order", Context.MODE_PRIVATE)
                    val orderKey =
                        "explicit_order_v2_${serverRepository.currentUser?.rowId}_" +
                            "${collectionFilter.libraryDisplayInfoIdOverride ?: itemId}"
                    val useApprovedOrder = collectionsPage && !orderPreferences.getBoolean(orderKey, false)
                    val sortAndDirection =
                        if (useApprovedOrder) {
                            SortAndDirection.DEFAULT
                        } else {
                            if (collectionFilter.useSavedLibraryDisplayInfo) {
                                libraryDisplayInfo?.sortAndDirection
                            } else {
                                null
                            } ?: initialSortAndDirection ?: SortAndDirection.DEFAULT
                        }

                    val filterToUse =
                        if (collectionFilter.useSavedLibraryDisplayInfo && libraryDisplayInfo?.filter != null) {
                            collectionFilter.filter.merge(libraryDisplayInfo.filter)
                        } else {
                            collectionFilter.filter
                        }

                    _state.update { it.copy(item = DataLoadingState.Success(item)) }
                    if (useApprovedOrder) saveLibraryDisplayInfo(newFilter = filterToUse, newSort = sortAndDirection)
                    loadResults(true, sortAndDirection, recursive, filterToUse, useSeriesForPrimary)
                        .join()
                    // The grouped list finds the groups itself; other sorts ask for them
                    if (collectionsPage && state.value.collectionGroups.isEmpty()) loadCollectionGroups()
                    if (useApprovedOrder && state.value.items is DataLoadingState.Success) {
                        orderPreferences.edit().putBoolean(orderKey, true).apply()
                    }
//                    onResumePage()
                } catch (ex: Exception) {
                    Timber.e(ex, "Error during init")
                    _state.update {
                        it.copy(
                            item = DataLoadingState.Error(ex),
                        )
                    }
                }
            }
            mediaManagementService.deletedItemFlow
                .onEach { deletedItem ->
                    refreshAfterDelete(position, deletedItem.item)
                }.catch { ex ->
                    Timber.e(ex, "Error refreshing after deleted item")
                }.launchIn(viewModelScope)
        }

        private suspend fun refreshAfterDelete(
            position: Int,
            deletedItem: BaseItem,
        ) {
            try {
                val currentItems = state.value.items.successValue
                if (currentItems != null && currentItems !is ApiRequestPager<*>) {
                    // Don't show the deleted collection again from memory
                    savedCollections.clear()
                    collections = null
                    loadResults(true, state.value.sortAndDirection, recursive, state.value.filter, useSeriesForPrimary).join()
                    return
                }
                val pager = currentItems as? ApiRequestPager<*>
                position.let {
                    Timber.v("Item deleted: position=%s, id=%s", it, itemId)
                    val item = pager?.get(it)
                    // Exact item deleted (eg a movie) or deleted item was within the series
                    if (item?.id == deletedItem.id ||
                        equalsNotNull(item?.data?.id, deletedItem.data.seriesId)
                    ) {
                        pager?.refreshPagesAfter(position)
                    }
                }
            } catch (ex: Exception) {
                Timber.e(ex, "Error refreshing after deleted item %s", itemId)
                if (!ex.isStaleSession()) showToast(context, "Error refreshing after item deleted")
            }
        }

        private fun saveLibraryDisplayInfo(
            newFilter: GetItemsFilter = state.value.filter,
            newSort: SortAndDirection = state.value.sortAndDirection,
            viewOptions: ViewOptions? = state.value.viewOptions,
        ) {
            if (collectionFilter.useSavedLibraryDisplayInfo) {
                serverRepository.currentUser?.let { user ->
                    viewModelScope.launchIO {
                        val libraryDisplayInfo =
                            LibraryDisplayInfo(
                                userId = user.rowId,
                                itemId = collectionFilter.libraryDisplayInfoIdOverride ?: itemId,
                                sort = newSort.sort,
                                direction = newSort.direction,
                                filter = newFilter,
                                viewOptions = viewOptions,
                            )
                        libraryDisplayInfoDao.saveItem(libraryDisplayInfo)
                    }
                }
            }
        }

        override fun saveViewOptions(viewOptions: ViewOptions) {
            position = 0
            _state.update { it.copy(viewOptions = viewOptions) }
            viewModelScope.launch(ExceptionHandler() + WholphinDispatchers.IO) {
                saveLibraryDisplayInfo(viewOptions = viewOptions)
                if (!viewOptions.showBackdrop) {
                    backdropService.clearBackdrop()
                }
            }
        }

        override fun onClickRandom() {
            viewModelScope.launchIO {
                try {
                    val random =
                        (state.value.items.successValue as? BlockingList<BaseItem?>)?.randomBlocking()
                    Timber.d("Got random item: %s", random?.id)
                    random?.destination()?.let {
                        navigateTo(random.destination())
                    }
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    Timber.e(ex, "Error getting random")
                    if (!ex.isStaleSession()) showToast(context, "Error: ${ex.localizedMessage}}")
                }
            }
        }

        override fun onFilterChange(
            newFilter: GetItemsFilter,
            recursive: Boolean,
        ) {
            Timber.v("onFilterChange: filter=%s", newFilter)
            saveLibraryDisplayInfo(newFilter)
            loadResults(
                false,
                state.value.sortAndDirection,
                recursive,
                newFilter,
                useSeriesForPrimary,
            )
        }

        override fun onSortChange(
            sortAndDirection: SortAndDirection,
            recursive: Boolean,
            filter: GetItemsFilter,
        ) {
            Timber.v(
                "onSortChange: sort=%s, recursive=%s, filter=%s",
                sortAndDirection,
                recursive,
                filter,
            )
            saveLibraryDisplayInfo(filter, sortAndDirection)
            loadResults(true, sortAndDirection, recursive, filter, useSeriesForPrimary)
        }

        override fun onCollectionGroupChange(group: String?) {
            _state.update { it.copy(collectionGroup = group) }
            position = 0
            // The grouped list has every collection, so a group is picked from it without a reload. Kid content rules
            // (review round 6): a kid or unknown session fetches fresh through the rules on every group pick
            val kept = if (isKidOrUnknown(serverRepository.currentUser)) null else collections
            kept?.let(::showCollections)
                ?: loadResults(true, state.value.sortAndDirection, recursive, state.value.filter, useSeriesForPrimary)
        }

        /** Every collection's tags, to find which groups the member has collections in. */
        private suspend fun loadCollectionGroups() {
            try {
                val request =
                    createGetItemsRequest(SortAndDirection.DEFAULT, recursive, collectionFilter.filter).copy(
                        tags = null,
                        fields = listOf(ItemFields.TAGS),
                        enableImages = false,
                        enableImageTypes = null,
                        enableUserData = false,
                        enableTotalRecordCount = false,
                    )
                val groups = CollectionGroups.groups(GetItemsRequestHandler.execute(api, request).content.items)
                _state.update { it.copy(collectionGroups = groups) }
            } catch (ex: Exception) {
                Timber.w(ex, "Couldn't load the collection groups")
            }
        }

        private var loadJob: Job? = null

        /** Every collection the grouped list was last shown from, so a group can be picked without a reload. */
        @Volatile
        private var collections: List<BaseItemDto>? = null

        private fun loadResults(
            resetState: Boolean,
            sortAndDirection: SortAndDirection,
            recursive: Boolean,
            filter: GetItemsFilter,
            useSeriesForPrimary: Boolean,
        ): Job {
            loadJob?.cancel()
            return viewModelScope
                .launch(WholphinDispatchers.IO) {
                    val request = createGetItemsRequest(sortAndDirection, recursive, filter)
                    val groupedCollections =
                        BuildConfig.FLAVOR == "weaselfin" && filter.override == GetItemsFilterOverride.NONE &&
                            request.includeItemTypes == listOf(BaseItemKind.BOX_SET) &&
                            sortAndDirection.sort in listOf(ItemSortBy.SORT_NAME, ItemSortBy.DEFAULT)
                    if (groupedCollections) {
                        loadCollections(request, sortAndDirection, filter)
                        return@launch
                    }
                    collections = null
                    _state.update {
                        it.copy(
                            items = DataLoadingState.Loading,
                            backgroundLoading = LoadingState.Loading,
                            sortAndDirection = sortAndDirection,
                            filter = filter,
                        )
                    }
                    try {
                        val newPager = createPager(sortAndDirection, recursive, filter, useSeriesForPrimary).init()
                        if (newPager.isNotEmpty()) newPager.getBlocking(0)
                        _state.update {
                            it.copy(
                                items = DataLoadingState.Success(newPager),
                                backgroundLoading = LoadingState.Success,
                            )
                        }
                    } catch (ex: CancellationException) {
                        throw ex
                    } catch (ex: Exception) {
                        Timber.e(
                            ex,
                            "Exception while loading data: sort=%s, filter=%s",
                            sortAndDirection,
                            filter,
                        )
                        _state.update {
                            it.copy(
                                items = DataLoadingState.Error(ex),
                                backgroundLoading = LoadingState.Pending,
                            )
                        }
                    }
                }.also { loadJob = it }
        }

        /**
         * Every member-scoped collection in the server's sort-name order, group by group. Shown
         * straight away from memory when the page has loaded them before, then refreshed. Watched
         * and favourite marks follow in a second request: the server takes several seconds to
         * work them out for every collection, and the posters don't need to wait for them.
         */
        private suspend fun loadCollections(
            request: GetItemsRequest,
            sortAndDirection: SortAndDirection,
            filter: GetItemsFilter,
        ) {
            val all =
                request.copy(
                    tags = null,
                    sortBy = listOf(ItemSortBy.SORT_NAME),
                    sortOrder =
                        listOf(
                            if (sortAndDirection.direction ==
                                SortOrder.DESCENDING
                            ) {
                                SortOrder.DESCENDING
                            } else {
                                SortOrder.ASCENDING
                            },
                        ),
                    fields = (request.fields.orEmpty() + ItemFields.TAGS + ItemFields.CHILD_COUNT).distinct(),
                    startIndex = null,
                    limit = null,
                    enableTotalRecordCount = false,
                )
            // Kid content rules (review round 6): a kid or unknown session never uses the saved list; every open fetches
            // fresh through the rules, and a failed fetch leaves nothing from before on screen
            val kidSession = isKidOrUnknown(serverRepository.currentUser)
            val cached = if (kidSession) null else savedCollections[all]
            _state.update { it.copy(sortAndDirection = sortAndDirection, filter = filter) }
            if (cached != null) {
                showCollections(cached)
            } else {
                _state.update { it.copy(items = DataLoadingState.Loading, backgroundLoading = LoadingState.Loading) }
            }
            try {
                val marks = cached.orEmpty().associate { it.id to it.userData }
                val fresh =
                    CollectionGroups
                        .inGroupOrder(GetItemsRequestHandler.execute(api, all.copy(enableUserData = false)).content.items)
                        .map { it.copy(userData = marks[it.id]) }
                showCollections(fresh)
                val userData =
                    GetItemsRequestHandler
                        .execute(api, all.copy(fields = null, enableImages = false, enableImageTypes = null))
                        .content.items
                        .associate { it.id to it.userData }
                val complete = fresh.map { it.copy(userData = userData[it.id] ?: it.userData) }
                if (!kidSession) savedCollections[all] = complete
                showCollections(complete)
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                Timber.e(ex, "Exception while loading collections")
                if (kidSession) {
                    collections = null
                    _state.update { it.copy(items = DataLoadingState.Error(ex), backgroundLoading = LoadingState.Pending) }
                } else if (state.value.items !is DataLoadingState.Success) {
                    _state.update { it.copy(items = DataLoadingState.Error(ex), backgroundLoading = LoadingState.Pending) }
                }
            }
        }

        /** [all] collections, or just the selected group's, and the groups they're in. */
        private fun showCollections(all: List<BaseItemDto>) {
            collections = all
            val group = state.value.collectionGroup
            val shown = if (group == null) all else all.filter { CollectionGroups.tag(group) in it.tags.orEmpty() }
            _state.update {
                it.copy(
                    items =
                        DataLoadingState.Success(
                            BlockingList.of(shown.map { BaseItem(it.copy(sortName = it.name), useSeriesForPrimary) }),
                        ),
                    backgroundLoading = LoadingState.Success,
                    collectionGroups = CollectionGroups.groups(all),
                )
            }
        }

        private suspend fun refreshItem(
            position: Int,
            itemId: UUID,
        ) {
            val items = state.value.items.successValue ?: return
            if (items is ApiRequestPager<*>) {
                items.refreshItem(position, itemId)
            } else {
                val updated = BaseItem(api.userLibraryApi.getItem(itemId).content, useSeriesForPrimary)
                // Keep a new favourite or watched mark when another group is picked
                collections = collections?.map { if (it.id == itemId) it.copy(userData = updated.data.userData) else it }
                _state.update { current ->
                    val currentItems = current.items.successValue ?: return@update current
                    current.copy(
                        items =
                            DataLoadingState.Success(
                                BlockingList.of(
                                    currentItems.map {
                                        if (it?.id == itemId) updated else it
                                    },
                                ),
                            ),
                    )
                }
            }
        }

        private fun createPager(
            sortAndDirection: SortAndDirection,
            recursive: Boolean,
            filter: GetItemsFilter,
            useSeriesForPrimary: Boolean,
        ): ApiRequestPager<out Any> =
            when (filter.override) {
                GetItemsFilterOverride.NONE -> {
                    val request =
                        createGetItemsRequest(
                            sortAndDirection = sortAndDirection,
                            recursive = recursive,
                            filter = filter,
                        )
                    val newPager =
                        ApiRequestPager(
                            api,
                            request,
                            GetItemsRequestHandler,
                            viewModelScope,
                            useSeriesForPrimary = useSeriesForPrimary,
                        )
                    newPager
                }

                GetItemsFilterOverride.PERSON -> {
                    val request =
                        filter.applyTo(
                            GetPersonsRequest(
                                enableImageTypes = listOf(ImageType.PRIMARY, ImageType.THUMB),
                            ),
                        )
                    val newPager =
                        ApiRequestPager(
                            api,
                            request,
                            GetPersonsHandler,
                            viewModelScope,
                            useSeriesForPrimary = useSeriesForPrimary,
                        )
                    newPager
                }
            }

        private fun createGetItemsRequest(
            sortAndDirection: SortAndDirection,
            recursive: Boolean,
            filter: GetItemsFilter,
        ): GetItemsRequest {
            val item = state.value.item.successValue
            val includeItemTypes =
                item
                    ?.data
                    ?.collectionType
                    ?.baseItemKinds
                    .orEmpty()
            val request =
                filter.applyTo(
                    GetItemsRequest(
                        userId = serverRepository.currentUser?.id,
                        parentId = item?.id,
                        enableImageTypes =
                            listOf(
                                ImageType.PRIMARY,
                                ImageType.THUMB,
                                ImageType.BACKDROP,
                                ImageType.LOGO,
                            ),
                        includeItemTypes = includeItemTypes,
                        recursive = recursive,
                        excludeItemIds = item?.let { listOf(item.id) },
                        sortBy =
                            buildList {
                                if (sortAndDirection.sort != ItemSortBy.DEFAULT) {
                                    add(sortAndDirection.sort)
                                    if (sortAndDirection.sort != ItemSortBy.SORT_NAME) {
                                        add(ItemSortBy.SORT_NAME)
                                    }
                                    if (item?.data?.collectionType == CollectionType.MOVIES) {
                                        add(ItemSortBy.PRODUCTION_YEAR)
                                    }
                                }
                            },
                        sortOrder =
                            buildList {
                                if (sortAndDirection.sort != ItemSortBy.DEFAULT) {
                                    add(sortAndDirection.direction)
                                    if (sortAndDirection.sort != ItemSortBy.SORT_NAME) {
                                        add(SortOrder.ASCENDING)
                                    }
                                    if (item?.data?.collectionType == CollectionType.MOVIES) {
                                        add(SortOrder.ASCENDING)
                                    }
                                }
                            },
                        // Collections show their title count on the card
                        fields =
                            if (item?.data?.collectionType == CollectionType.BOXSETS) {
                                SlimItemFields + ItemFields.CHILD_COUNT
                            } else {
                                SlimItemFields
                            },
                    ),
                    // An unset UI type filter must not erase BOX_SET from the library query:
                    // ordering selection below relies on the effective query, not just the page title.
                    overwriteIncludeTypes =
                        BuildConfig.FLAVOR != "weaselfin" ||
                            item?.data?.collectionType != CollectionType.BOXSETS || filter.includeItemTypes != null,
                )
            return state.value.collectionGroup?.let { request.copy(tags = listOf(CollectionGroups.tag(it))) } ?: request
        }

        override suspend fun getFilterOptionValues(filterOption: ItemFilterBy<*>): List<FilterValueOption> =
            filterOptionCache.getFilterOptionValues(
                itemId.toUUID(),
                filterOption,
            )

        /**
         * The count must come from the same endpoint that [createPager] pages through, otherwise
         * the index refers to a different result set and lands past the end of the list.
         */
        override suspend fun positionOfLetter(letter: Char): Int? =
            withContext(WholphinDispatchers.IO) {
                val filter = state.value.filter
                val items = state.value.items.successValue
                if (items != null && items !is ApiRequestPager<*>) {
                    return@withContext items
                        .indexOfFirst {
                            it?.name?.trim()?.startsWith(letter.toString(), ignoreCase = true) == true
                        }.takeIf { it >= 0 }
                }
                when (filter.override) {
                    // GetPersonsRequest has no nameLessThan or startIndex, so /Persons cannot be
                    // counted up to a letter at all
                    GetItemsFilterOverride.PERSON -> {
                        null
                    }

                    GetItemsFilterOverride.NONE -> {
                        GetItemsRequestHandler.countMatching(
                            api = api,
                            request =
                                createGetItemsRequest(
                                    sortAndDirection = state.value.sortAndDirection,
                                    recursive = recursive,
                                    filter = filter,
                                ).copy(
                                    enableImageTypes = null,
                                    fields = null,
                                    nameLessThan = letter.toString(),
                                    limit = 0,
                                    enableTotalRecordCount = true,
                                    enableUserData = false,
                                ),
                        )
                    }
                }
            }

        override fun setWatched(
            position: Int,
            itemId: UUID,
            played: Boolean,
        ) {
            viewModelScope.launch(ExceptionHandler() + WholphinDispatchers.IO) {
                favoriteWatchManager.setWatched(itemId, played)
                refreshItem(position, itemId)
            }
        }

        override fun setFavorite(
            position: Int,
            itemId: UUID,
            favorite: Boolean,
        ) {
            viewModelScope.launch(ExceptionHandler() + WholphinDispatchers.IO) {
                favoriteWatchManager.setFavorite(itemId, favorite)
                refreshItem(position, itemId)
            }
        }

        override fun updateBackdrop(item: BaseItem) {
            viewModelScope.launchIO {
                backdropService.submit(item)
            }
        }

        override fun navigateTo(destination: Destination) {
            release()
            navigationManager.navigateTo(destination)
        }

        fun release() {
            themeSongPlayer.stop()
        }

        fun onResumePage() {
            viewModelScope.launchIO {
                state.value.item.successValue?.let {
                    Timber.v("onResumePage: %s", state.value.items::class)
                    if (it.type == BaseItemKind.BOX_SET && state.value.items !is DataLoadingState.Error) {
                        themeSongPlayer.playThemeFor(it.id)
                    }
                }
            }
        }

        override fun deleteItem(
            index: Int,
            item: BaseItem,
        ) {
            deleteItem(context, mediaManagementService, item) {
                viewModelScope.launchDefault {
                    refreshAfterDelete(index, item)
                }
            }
        }

        override fun canDelete(
            item: BaseItem,
            appPreferences: AppPreferences,
        ): Boolean = mediaManagementService.canDelete(item, appPreferences)

        override fun sendReportFor(itemId: UUID) = serverReportService.sendMediaReportFor(itemId)

        override fun isAdministrator(): Boolean = serverRepository.currentUserDto?.policy?.isAdministrator == true
    }

data class CollectionFolderState(
    val item: DataLoadingState<BaseItem?> = DataLoadingState.Loading,
    val items: DataLoadingState<List<BaseItem?>> = DataLoadingState.Loading,
    val backgroundLoading: LoadingState = LoadingState.Loading,
    val sortAndDirection: SortAndDirection = SortAndDirection.DEFAULT,
    val filter: GetItemsFilter = GetItemsFilter(),
    val viewOptions: ViewOptions,
    /** The Collections page's group shortcuts, in order; empty elsewhere. */
    val collectionGroups: List<String> = emptyList(),
    /** The group showing, or null for all collections. Not saved, so the page opens on ALL. */
    val collectionGroup: String? = null,
)

interface CollectionFolderViewActions {
    fun navigateTo(destination: Destination)

    fun updateBackdrop(item: BaseItem)

    fun onSortChange(
        sortAndDirection: SortAndDirection,
        recursive: Boolean,
        filter: GetItemsFilter,
    )

    fun onFilterChange(
        newFilter: GetItemsFilter,
        recursive: Boolean,
    )

    suspend fun getFilterOptionValues(filterOption: ItemFilterBy<*>): List<FilterValueOption>

    suspend fun positionOfLetter(letter: Char): Int?

    fun saveViewOptions(viewOptions: ViewOptions)

    /**
     * Navigate to a random item
     */
    fun onClickRandom()

    /** Show one of the Collections page's groups, or all collections when [group] is null. */
    fun onCollectionGroupChange(group: String?) {}
}
